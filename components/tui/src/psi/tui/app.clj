(ns psi.tui.app
  "Psi TUI application using charm.clj's Elm Architecture.

   Replaces the custom ProcessTerminal + differential renderer with
   charm.clj's JLine3-backed terminal and Elm update/view loop.

   Public API:
     start!        — blocking entry point for --tui mode
     make-init     — create init fn (for testing)
     make-update   — create update fn (for testing)
     view          — pure view fn (for testing)

   Architecture:
     init    → [initial-state nil]
     update  → (state, msg) → [new-state, cmd]
     view    → state → string

   Agent integration:
     When user submits text, `run-agent-fn!` is called with (text queue).
     It starts the agent in a background thread and puts the result
     on the queue. A polling command reads from the queue, driving
     the spinner animation on each poll timeout."
  (:require
   [charm.core :as charm]
   [charm.input.keymap] ; loaded so we can patch before use
   [charm.message :as msg]
   [clojure.string :as str]
   [psi.tui.extension-ui :as ext-ui])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]
   [org.jline.keymap KeyMap]
   [org.jline.terminal Terminal]))

;; ── JLine compat patch ──────────────────────────────────────
;; charm.clj v0.1.42: bind-from-capability! calls (String. ^chars seq)
;; but JLine 3.30+ KeyMap/key returns String, not char[].
;; Patch the private fn at load time, before create-keymap is called.

(alter-var-root
 #'charm.input.keymap/bind-from-capability!
 (constantly
  (fn [^KeyMap keymap ^Terminal terminal cap event]
    (when terminal
      (when-let [seq-val (KeyMap/key terminal cap)]
        (let [^String seq-str (if (string? seq-val)
                                seq-val
                                (String. ^chars seq-val))]
          (when (and (pos? (count seq-str))
                     (= (int (.charAt seq-str 0)) 27))
            (.bind keymap event (subs seq-str 1)))))))))

;; ── Styles ──────────────────────────────────────────────────

(def ^:private title-style   (charm/style :fg charm/magenta :bold true))
(def ^:private user-style    (charm/style :fg charm/cyan :bold true))
(def ^:private assist-style  (charm/style :fg charm/green :bold true))
(def ^:private error-style   (charm/style :fg charm/red))
(def ^:private dim-style     (charm/style :fg 240))
(def ^:private sep-style     (charm/style :fg 240))

;; ── Spinner frames (driven by poll ticks, no separate timer) ──

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

;; ── Custom message predicates ───────────────────────────────

(defn agent-result? [m] (= :agent-result (:type m)))
(defn agent-error?  [m] (= :agent-error  (:type m)))
(defn agent-poll?   [m] (= :agent-poll   (:type m)))

;; ── Dialog state helpers ────────────────────────────────────

(defn- has-active-dialog? [state]
  (boolean (some-> (:ui-state-atom state) ext-ui/active-dialog)))

(defn- handle-dialog-key
  "Route keypress to the active dialog. Returns [new-state cmd] or nil
   if no dialog is active."
  [state m]
  (when-let [ui-atom (:ui-state-atom state)]
    (when-let [dialog (ext-ui/active-dialog ui-atom)]
      (cond
        ;; Escape cancels any dialog
        (msg/key-match? m "escape")
        (do (ext-ui/cancel-dialog! ui-atom)
            [state nil])

        ;; Enter confirms / submits
        (msg/key-match? m "enter")
        (case (:kind dialog)
          :confirm
          (do (ext-ui/resolve-dialog! ui-atom (:id dialog) true)
              [state nil])

          :select
          (let [idx     (or (:selected-index dialog) 0)
                options (:options dialog)
                value   (when (seq options) (:value (nth options idx nil)))]
            (when value
              (ext-ui/resolve-dialog! ui-atom (:id dialog) value))
            [state nil])

          :input
          (let [text (or (:input-text dialog) "")]
            (ext-ui/resolve-dialog! ui-atom (:id dialog) text)
            [state nil])

          ;; fallback
          [state nil])

        ;; For select: up/down to change selection
        (and (= :select (:kind dialog)) (msg/key-match? m "up"))
        (do (swap! ui-atom update-in [:dialog-queue :active :selected-index]
                   (fn [i] (max 0 (dec (or i 0)))))
            [state nil])

        (and (= :select (:kind dialog)) (msg/key-match? m "down"))
        (do (swap! ui-atom update-in [:dialog-queue :active :selected-index]
                   (fn [i] (min (dec (count (:options dialog)))
                                (inc (or i 0)))))
            [state nil])

        ;; For input: printable chars and backspace
        (and (= :input (:kind dialog)) (msg/key-match? m "backspace"))
        (do (swap! ui-atom update-in [:dialog-queue :active :input-text]
                   (fn [s] (let [s (or s "")]
                             (if (pos? (count s)) (subs s 0 (dec (count s))) s))))
            [state nil])

        (and (= :input (:kind dialog)) (msg/key-press? m))
        (let [ch (:key m)]
          (when (and ch (= 1 (count ch)))
            (swap! ui-atom update-in [:dialog-queue :active :input-text]
                   (fn [s] (str (or s "") ch))))
          [state nil])

        :else [state nil]))))

;; ── Commands ────────────────────────────────────────────────

(defn poll-cmd
  "Command that polls the agent queue with a short timeout.
   Returns :agent-result, :agent-error, or :agent-poll (keep polling)."
  [^LinkedBlockingQueue queue]
  (charm/cmd
   (fn []
     (if-let [event (.poll queue 120 TimeUnit/MILLISECONDS)]
       (case (:kind event)
         :done  {:type :agent-result :result (:result event)}
         :error {:type :agent-error  :error  (:message event)}
         {:type :agent-poll})
       {:type :agent-poll}))))

;; ── Init ────────────────────────────────────────────────────

(defn make-init
  "Create an init function for the charm program.
   `model-name` is displayed in the banner.
   `query-fn`  — optional (fn [eql-query]) → result map; used to
                  introspect the session for prompt templates, etc.
   `ui-state-atom` — optional extension UI state atom; when present,
                     the TUI renders widgets, status, notifications,
                     and dialogs from extensions."
  ([model-name] (make-init model-name nil))
  ([model-name query-fn] (make-init model-name query-fn nil))
  ([model-name query-fn ui-state-atom]
   (fn []
     (let [introspected (when query-fn
                          (query-fn [:psi.agent-session/prompt-templates
                                     :psi.agent-session/skills
                                     :psi.agent-session/extension-summary]))]
       [{:messages          []
         :phase             :idle
         :error             nil
         :input             (charm/text-input :prompt "刀: "
                                              :placeholder "Type a message…"
                                              :focused true)
         :spinner-frame     0
         :model-name        model-name
         :prompt-templates  (or (:psi.agent-session/prompt-templates introspected) [])
         :skills            (or (:psi.agent-session/skills introspected) [])
         :extension-summary (or (:psi.agent-session/extension-summary introspected) {})
         :ui-state-atom     ui-state-atom
         :queue             nil
         :width             80
         :height            24}
        nil]))))

;; ── Update helpers ──────────────────────────────────────────

(defn- handle-command
  "Process a /command, returning [new-state cmd] or nil if unrecognised."
  [state text]
  (case text
    ("/quit" "/exit") [state charm/quit-cmd]
    nil))

(defn- submit-to-agent
  "Start the agent with `text`, return [new-state cmd]."
  [state run-agent-fn! text]
  (let [queue (LinkedBlockingQueue.)]
    (run-agent-fn! text queue)
    [(-> state
         (update :messages conj {:role :user :text text})
         (assoc :phase         :streaming
                :error         nil
                :input         (charm/text-input-reset (:input state))
                :queue         queue
                :spinner-frame 0))
     (poll-cmd queue)]))

(defn- submit-input
  "Extract text from input, start agent, return [new-state cmd].
   Built-in commands (/quit, /exit) are handled directly.
   All other /name input is forwarded to the agent where prompt
   template expansion and extension command dispatch occur."
  [state run-agent-fn!]
  (let [text (str/trim (charm/text-input-value (:input state)))]
    (cond
      (str/blank? text)
      [state nil]

      ;; Built-in TUI commands
      (some? (handle-command state text))
      (handle-command state text)

      ;; Everything else — including /template-name — goes to the agent
      :else
      (submit-to-agent state run-agent-fn! text))))

(defn- handle-agent-result
  "Process completed agent result."
  [state result]
  (let [text   (str/join
                (keep #(when (= :text (:type %)) (:text %))
                      (:content result)))
        errors (keep #(when (= :error (:type %)) (:text %))
                     (:content result))
        error  (first errors)
        display (if (seq text) text "(no response)")]
    [(-> state
         (update :messages conj {:role :assistant :text display})
         (assoc :phase :idle
                :error error
                :queue nil))
     nil]))

(defn- handle-agent-poll
  "Agent still running — advance spinner, keep polling."
  [state]
  (let [n (count spinner-frames)]
    [(update state :spinner-frame #(mod (inc %) n))
     (poll-cmd (:queue state))]))

;; ── Update ──────────────────────────────────────────────────

(defn make-update
  "Create an update function.

   `run-agent-fn!` is called with (text queue) and should start the
   agent in a background thread that puts {:kind :done :result msg}
   or {:kind :error :message str} on queue when finished."
  [run-agent-fn!]
  (fn [state m]
    ;; Dismiss expired notifications on every tick
    (when-let [ui-atom (:ui-state-atom state)]
      (ext-ui/dismiss-expired! ui-atom)
      (ext-ui/dismiss-overflow! ui-atom))

    (cond
      ;; Quit: ctrl+c always; escape when idle and no dialog
      (msg/key-match? m "ctrl+c")
      [state charm/quit-cmd]

      (and (= :idle (:phase state))
           (not (has-active-dialog? state))
           (msg/key-match? m "escape"))
      [state charm/quit-cmd]

      ;; Window resize
      (msg/window-size? m)
      [(assoc state :width (:width m) :height (:height m)) nil]

      ;; Dialog active — route all key input to dialog handler
      (and (has-active-dialog? state) (msg/key-press? m))
      (or (handle-dialog-key state m) [state nil])

      ;; Agent result
      (agent-result? m)
      (handle-agent-result state (:result m))

      ;; Agent error
      (agent-error? m)
      [(assoc state :phase :idle
              :error (:error m)
              :queue nil) nil]

      ;; Agent poll timeout → advance spinner, keep polling
      (agent-poll? m)
      (handle-agent-poll state)

      ;; Enter → submit (idle + has text)
      (and (= :idle (:phase state))
           (msg/key-match? m "enter"))
      (submit-input state run-agent-fn!)

      ;; All other keys → text input (idle only)
      (and (= :idle (:phase state))
           (msg/key-press? m))
      (let [[new-input cmd] (charm/text-input-update (:input state) m)]
        [(assoc state :input new-input) cmd])

      ;; Ignore everything else (keys during streaming, etc.)
      :else
      [state nil])))

;; ── View ────────────────────────────────────────────────────

(defn- render-banner [model-name prompt-templates skills extension-summary]
  (let [visible-skills (remove :disable-model-invocation skills)
        ext-count      (:extension-count extension-summary 0)]
    (str (charm/render title-style "ψ Psi Agent Session") "\n"
         (charm/render dim-style (str "  Model: " model-name)) "\n"
         (when (seq prompt-templates)
           (str (charm/render dim-style
                              (str "  Prompts: "
                                   (str/join ", " (map #(str "/" (:name %)) prompt-templates))))
                "\n"))
         (when (seq visible-skills)
           (str (charm/render dim-style
                              (str "  Skills: "
                                   (str/join ", " (map :name visible-skills))))
                "\n"))
         (when (pos? ext-count)
           (str (charm/render dim-style
                              (str "  Exts: " ext-count " loaded"))
                "\n"))
         (charm/render dim-style "  ESC to quit") "\n")))

(defn- render-message [{:keys [role text]}]
  (case role
    :user
    (str (charm/render user-style "刀: ") text)

    :assistant
    (let [lines       (str/split-lines text)
          first-line  (str (charm/render assist-style "ψ: ") (first lines))
          rest-lines  (map #(str "   " %) (rest lines))]
      (str/join "\n" (cons first-line rest-lines)))

    ;; fallback
    (str "[" (name role) "] " text)))

(defn- render-messages [messages]
  (when (seq messages)
    (str (str/join "\n\n" (map render-message messages)) "\n")))

(defn- render-separator [width]
  (charm/render sep-style (apply str (repeat (max 1 width) "─"))))

;; ── Extension UI rendering ──────────────────────────────────

(def ^:private notify-info-style    dim-style)
(def ^:private notify-warning-style (charm/style :fg charm/yellow))
(def ^:private notify-error-style   error-style)

(defn- render-widgets [ui-state-atom placement]
  (when ui-state-atom
    (let [widgets (ext-ui/widgets-by-placement ui-state-atom placement)]
      (when (seq widgets)
        (str (str/join "\n"
                       (mapcat :content widgets))
             "\n")))))

(defn- render-statuses [ui-state-atom width]
  (when ui-state-atom
    (let [statuses (ext-ui/all-statuses ui-state-atom)]
      (when (seq statuses)
        (str "\n" (render-separator width) "\n"
             (str/join " │ "
                       (map #(charm/render dim-style (:text %)) statuses))
             "\n")))))

(defn- render-notifications [ui-state-atom]
  (when ui-state-atom
    (let [notes (ext-ui/visible-notifications ui-state-atom)]
      (when (seq notes)
        (str (str/join "\n"
                       (map (fn [n]
                              (let [style (case (:level n)
                                            :warning notify-warning-style
                                            :error   notify-error-style
                                            notify-info-style)]
                                (charm/render style (str "  " (:message n)))))
                            notes))
             "\n")))))

(defn- render-dialog [ui-state-atom]
  (when ui-state-atom
    (when-let [dialog (ext-ui/active-dialog ui-state-atom)]
      (case (:kind dialog)
        :confirm
        (str (charm/render title-style (:title dialog)) "\n"
             "  " (:message dialog) "\n"
             (charm/render dim-style "  Enter=confirm  Escape=cancel") "\n")

        :select
        (let [idx     (or (:selected-index dialog) 0)
              options (:options dialog)]
          (str (charm/render title-style (:title dialog)) "\n"
               (str/join "\n"
                         (map-indexed
                          (fn [i opt]
                            (if (= i idx)
                              (str (charm/render user-style (str "▸ " (:label opt)))
                                   (when (:description opt)
                                     (str "  " (charm/render dim-style (:description opt)))))
                              (str "  " (:label opt))))
                          options))
               "\n"
               (charm/render dim-style "  ↑/↓=navigate  Enter=select  Escape=cancel") "\n"))

        :input
        (str (charm/render title-style (:title dialog)) "\n"
             "  " (or (:input-text dialog) "") "█" "\n"
             (charm/render dim-style "  Enter=submit  Escape=cancel") "\n")

        ;; fallback
        ""))))

(defn view
  "Render the full TUI state to a string."
  [state]
  (let [{:keys [messages phase error input spinner-frame model-name width
                prompt-templates skills extension-summary ui-state-atom]} state
        spinner-char (nth spinner-frames (mod spinner-frame (count spinner-frames)))
        dialog-active? (has-active-dialog? state)]
    (str (render-banner model-name prompt-templates skills extension-summary)
         "\n"
         (render-messages messages)
         (when (= :streaming phase)
           (str "\n" (charm/render assist-style "ψ: ")
                spinner-char " thinking…\n"))
         (when error
           (str "\n" (charm/render error-style (str "[error: " error "]")) "\n"))
         ;; Widgets above editor
         (render-widgets ui-state-atom :above-editor)
         "\n"
         (render-separator width) "\n"
         ;; Dialog replaces editor when active
         (if dialog-active?
           (render-dialog ui-state-atom)
           (if (= :idle phase)
             (charm/text-input-view input)
             (charm/render dim-style "(waiting for response…)")))
         ;; Widgets below editor
         (render-widgets ui-state-atom :below-editor)
         ;; Status footer
         (render-statuses ui-state-atom width)
         ;; Notifications toast
         (render-notifications ui-state-atom))))

;; ── Public entry point ──────────────────────────────────────

(defn start!
  "Run the Psi TUI. Blocks until the user exits.

   `model-name`     — display name for the banner
   `run-agent-fn!`  — (fn [text queue]) starts agent in background;
                       must put {:kind :done :result msg} or
                       {:kind :error :message str} on queue.
   `opts`           — optional map:
                       :query-fn      — (fn [eql-query]) for session introspection
                       :ui-state-atom — extension UI state atom"
  ([model-name run-agent-fn!]
   (start! model-name run-agent-fn! {}))
  ([model-name run-agent-fn! opts]
   (charm/run {:init   (make-init model-name (:query-fn opts) (:ui-state-atom opts))
               :update (make-update run-agent-fn!)
               :view   view
               :alt-screen true})))
