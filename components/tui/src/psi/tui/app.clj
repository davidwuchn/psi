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
   [charm.render.core]  ; loaded so we can patch enter-alt-screen!
   [charm.terminal :as charm-term]
   [cheshire.core :as json]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.persistence :as persist]
   [psi.tui.ansi :as ansi]
   [psi.tui.extension-ui :as ext-ui]
   [psi.tui.markdown :as md])
  (:import
   [java.time Instant]
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

;; ── Alt-screen fix ───────────────────────────────────────────
;; charm.clj v0.1.42: create-renderer stores :alt-screen from opts
;; in the renderer atom. enter-alt-screen! checks
;; (when-not (:alt-screen @renderer)) — which short-circuits because
;; the flag is already true. Result: alt-screen is never entered,
;; so the TUI runs inline in the main buffer and Display cursor
;; tracking desyncs on content height changes.

(alter-var-root
 #'charm.render.core/enter-alt-screen!
 (constantly
  (fn [renderer]
    (let [terminal (:terminal @renderer)]
      (charm-term/enter-alt-screen terminal)
      (charm-term/clear-screen terminal)
      (charm-term/cursor-home terminal))
    (swap! renderer assoc :alt-screen true))))

;; ── Styles ──────────────────────────────────────────────────

(def ^:private title-style   (charm/style :fg charm/magenta :bold true))
(def ^:private user-style    (charm/style :fg charm/cyan :bold true))
(def ^:private assist-style  (charm/style :fg charm/green :bold true))
(def ^:private error-style   (charm/style :fg charm/red))
(def ^:private dim-style     (charm/style :fg 240))
(def ^:private sep-style     (charm/style :fg 240))
(def ^:private tool-style    (charm/style :fg charm/yellow :bold true))
(def ^:private tool-ok-style (charm/style :fg charm/green))
(def ^:private tool-err-style (charm/style :fg charm/red))
(def ^:private tool-dim-style (charm/style :fg 245))

;; ── Spinner frames (driven by poll ticks, no separate timer) ──

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

;; ── Custom message predicates ───────────────────────────────

(defn agent-result? [m] (= :agent-result (:type m)))
(defn agent-error?  [m] (= :agent-error  (:type m)))
(defn agent-poll?   [m] (= :agent-poll   (:type m)))
(defn agent-event?  [m] (= :agent-event  (:type m)))

(defn- key-token->string
  "Normalize charm key token to a string when possible."
  [k]
  (cond
    (string? k)  k
    (keyword? k) (name k)
    (char? k)    (str k)
    :else        nil))

(defn- printable-key
  "Return a single printable character string for key token, else nil."
  [k]
  (let [s (key-token->string k)]
    (when (and (string? s)
               (= 1 (count s))
               (>= (int (.charAt ^String s 0)) 32))
      s)))

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
        (let [ch (printable-key (:key m))]
          (when ch
            (swap! ui-atom update-in [:dialog-queue :active :input-text]
                   (fn [s] (str (or s "") ch))))
          [state nil])

        :else [state nil]))))

;; ── Session selector ────────────────────────────────────────
;;
;; State lives under :session-selector in the app state map:
;;   {:sessions     [SessionInfo ...]   — loaded for current scope
;;    :all-sessions [SessionInfo ...]   — loaded for "all" scope (lazy)
;;    :scope        :current | :all
;;    :search       ""                  — current search string
;;    :selected     0                   — cursor index into filtered list
;;    :loading?     false}
;;
;; Sessions are displayed as a flat filtered list (no tree for simplicity).
;; Tab toggles scope.  Type to search.  ↑/↓ navigate.  Enter selects.  Esc cancels.

(defn- format-age
  "Human-readable age string from a timestamp (Instant or Date)."
  [ts]
  (when ts
    (let [epoch-ms (cond
                     (instance? Instant ts) (.toEpochMilli ^Instant ts)
                     (instance? java.util.Date ts) (.getTime ^java.util.Date ts)
                     :else nil)]
      (when epoch-ms
        (let [diff-ms   (- (System/currentTimeMillis) epoch-ms)
              diff-mins (quot diff-ms 60000)
              diff-hrs  (quot diff-ms 3600000)
              diff-days (quot diff-ms 86400000)]
          (cond
            (< diff-mins 1)   "now"
            (< diff-mins 60)  (str diff-mins "m")
            (< diff-hrs 24)   (str diff-hrs "h")
            (< diff-days 7)   (str diff-days "d")
            (< diff-days 30)  (str (quot diff-days 7) "w")
            (< diff-days 365) (str (quot diff-days 30) "mo")
            :else             (str (quot diff-days 365) "y")))))))

(defn- filter-sessions
  "Return sessions matching `query` (case-insensitive substring on first-message + name)."
  [sessions query]
  (if (str/blank? query)
    sessions
    (let [q (str/lower-case (str/trim query))]
      (filterv (fn [s]
                 (or (str/includes? (str/lower-case (or (:first-message s) "")) q)
                     (str/includes? (str/lower-case (or (:name s) "")) q)
                     (str/includes? (str/lower-case (or (:cwd s) "")) q)))
               sessions))))

(defn- session-selector-init
  "Build the initial session selector state for `cwd`."
  [cwd current-session-file]
  (let [dir      (persist/session-dir-for cwd)
        sessions (persist/list-sessions dir)]
    {:sessions              sessions
     :all-sessions          nil       ;; loaded lazily on Tab
     :scope                 :current
     :search                ""
     :selected              0
     :loading?              false
     :current-session-file  current-session-file}))

(defn- selector-sessions
  "Return the active sessions list for the current scope."
  [{:keys [scope sessions all-sessions]}]
  (if (= :current scope) sessions (or all-sessions [])))

(defn- selector-filtered
  "Return filtered + bounded sessions."
  [sel-state]
  (filter-sessions (selector-sessions sel-state) (:search sel-state)))

(defn- selector-clamp
  "Clamp :selected to valid range after list changes."
  [sel-state]
  (let [n (count (selector-filtered sel-state))]
    (update sel-state :selected #(max 0 (min % (dec (max 1 n)))))))

(defn- selector-move
  "Move cursor by `delta`, clamped."
  [sel-state delta]
  (let [n (count (selector-filtered sel-state))]
    (selector-clamp
     (update sel-state :selected #(max 0 (min (dec (max 1 n)) (+ % delta)))))))

(defn- selector-type
  "Append/delete character from search string."
  [sel-state key-token]
  (let [key-str    (key-token->string key-token)
        new-search (cond
                     (= "backspace" key-str)
                     (let [s (:search sel-state)]
                       (if (pos? (count s)) (subs s 0 (dec (count s))) s))

                     :else
                     (if-let [ch (printable-key key-token)]
                       (str (:search sel-state) ch)
                       (:search sel-state)))]
    (selector-clamp (assoc sel-state :search new-search))))

;; ── Commands ────────────────────────────────────────────────

(defn poll-cmd
  "Command that polls the agent queue with a short timeout.
   Returns :agent-result, :agent-error, :agent-event, or :agent-poll.

   Queue payloads accepted:
   - {:kind :done  :result ...}
   - {:kind :error :message ...}
   - {:type :agent-event ...}  ; progress events
   "
  [^LinkedBlockingQueue queue]
  (charm/cmd
   (fn []
     (if-let [event (.poll queue 120 TimeUnit/MILLISECONDS)]
       (cond
         (= :done (:kind event))
         {:type :agent-result :result (:result event)}

         (= :error (:kind event))
         {:type :agent-error :error (:message event)}

         (= :agent-event (:type event))
         event

         :else
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
                     and dialogs from extensions.
   `opts` map:
     :cwd                  — working directory string (for /resume)
     :current-session-file — current session file path (highlighted in selector)
     :resume-fn!           — (fn [session-path]) called when user selects a session
     :dispatch-fn          — (fn [text]) → command result map or nil; central command dispatch"
  ([model-name] (make-init model-name nil))
  ([model-name query-fn] (make-init model-name query-fn nil))
  ([model-name query-fn ui-state-atom] (make-init model-name query-fn ui-state-atom {}))
  ([model-name query-fn ui-state-atom opts]
   (fn []
     (let [introspected (when query-fn
                          (query-fn [:psi.agent-session/prompt-templates
                                     :psi.agent-session/skills
                                     :psi.agent-session/extension-summary
                                     :psi.agent-session/session-file]))]
       [{:messages              []
         :phase                 :idle
         :error                 nil
         :input                 (charm/text-input :prompt "刀: "
                                                  :placeholder "Type a message…"
                                                  :focused true)
         :spinner-frame         0
         :model-name            model-name
         :prompt-templates      (or (:psi.agent-session/prompt-templates introspected) [])
         :skills                (or (:psi.agent-session/skills introspected) [])
         :extension-summary     (or (:psi.agent-session/extension-summary introspected) {})
         :query-fn              query-fn
         :ui-state-atom         ui-state-atom
         :dispatch-fn           (:dispatch-fn opts)
         :cwd                   (or (:cwd opts) (System/getProperty "user.dir"))
         :current-session-file  (or (:current-session-file opts)
                                    (:psi.agent-session/session-file introspected))
         :resume-fn!            (:resume-fn! opts)
         :session-selector      nil   ;; non-nil when /resume is active
         :queue                 nil
         :width                 80
         :height                24
         ;; Live turn progress
         :stream-text           nil
         :tool-calls            {}
         :tool-order            []}
        nil]))))

;; ── Update helpers ──────────────────────────────────────────

(defn- open-session-selector
  "Enter session-selector phase."
  [state]
  (let [sel (session-selector-init (:cwd state) (:current-session-file state))]
    [(assoc state
            :phase            :selecting-session
            :session-selector sel
            :input            (charm/text-input-reset (:input state)))
     nil]))

(defn- handle-dispatch-result
  "Translate a command dispatch result map into [new-state cmd].
   Returns nil if the result is nil (not a command)."
  [state result]
  (when result
    (case (:type result)
      :quit
      [state charm/quit-cmd]

      :resume
      (open-session-selector state)

      :new-session
      [(-> state
           (assoc :messages []
                  :error    nil
                  :input    (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (:message result)}))
       nil]

      :text
      [(-> state
           (assoc :input (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (:message result)}))
       nil]

      (:login-error :logout)
      [(-> state
           (assoc :input (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (:message result)}))
       nil]

      :extension-cmd
      (do (try
            (when-let [handler (:handler result)]
              (handler (:args result)))
            (catch Exception e
              ;; Extension errors are swallowed to TUI state
              (timbre/warn "Extension command error:" (ex-message e))))
          [(assoc state :input (charm/text-input-reset (:input state)))
           nil])

      ;; Login start — show URL. For callback-server providers, the
      ;; dispatch-fn in main.clj kicks off async completion. For manual-code
      ;; providers, the next input will be treated as the auth code.
      :login-start
      [(-> state
           (assoc :input (charm/text-input-reset (:input state)))
           (update :messages conj
                   {:role :assistant
                    :text (str "🔑 Login to " (get-in result [:provider :name])
                               "\n\nOpen this URL in your browser:\n" (:url result)
                               (if (:uses-callback-server result)
                                 "\n\nWaiting for browser callback…"
                                 "\n\nPaste the authorization code below ↓"))}))
       nil]

      ;; Fallback — treat as text
      [(-> state
           (assoc :input (charm/text-input-reset (:input state)))
           (update :messages conj {:role :assistant :text (str result)}))
       nil])))

(defn- handle-selector-key
  "Handle a keypress while the session selector is open.
  Returns [new-state cmd]."
  [state m]
  (let [sel       (:session-selector state)
        key-token (when (msg/key-press? m) (:key m))]
    (cond
      ;; Escape — cancel, return to idle
      (msg/key-match? m "escape")
      [(assoc state :phase :idle :session-selector nil) nil]

      ;; Ctrl+C — quit
      (msg/key-match? m "ctrl+c")
      [state charm/quit-cmd]

      ;; Tab — toggle scope
      (msg/key-match? m "tab")
      (let [new-scope (if (= :current (:scope sel)) :all :current)
            new-sel   (if (and (= :all new-scope) (nil? (:all-sessions sel)))
                        ;; Lazily load all sessions on first Tab to :all
                        (let [all (persist/list-all-sessions)]
                          (-> sel
                              (assoc :scope :all :all-sessions all)
                              selector-clamp))
                        (-> sel
                            (assoc :scope new-scope)
                            selector-clamp))]
        [(assoc state :session-selector new-sel) nil])

      ;; Up
      (msg/key-match? m "up")
      [(update state :session-selector selector-move -1) nil]

      ;; Down
      (msg/key-match? m "down")
      [(update state :session-selector selector-move 1) nil]

      ;; Enter — select the highlighted session
      (msg/key-match? m "enter")
      (let [filtered (selector-filtered sel)
            chosen   (nth filtered (:selected sel) nil)]
        (if chosen
          (let [path      (:path chosen)
                resume-fn (:resume-fn! state)
                ;; resume-fn! returns [{:role :user/:assistant :text "..."}...]
                ;; or nil if it fails
                restored  (when resume-fn (resume-fn path))
                new-state (-> state
                              (assoc :phase            :idle
                                     :session-selector nil
                                     :current-session-file path
                                     :messages         (or restored [])
                                     :stream-text      nil
                                     :tool-calls       {}
                                     :tool-order       []))]
            [new-state nil])
          ;; Nothing selected — just close
          [(assoc state :phase :idle :session-selector nil) nil]))

      ;; Backspace / printable chars — update search
      (msg/key-press? m)
      [(update state :session-selector selector-type key-token) nil]

      :else [state nil])))

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
                :spinner-frame 0
                :stream-text   nil
                :tool-calls    {}
                :tool-order    []))
     (poll-cmd queue)]))

(defn- submit-input
  "Extract text from input, start agent, return [new-state cmd].
   Commands are dispatched via the dispatch-fn stored in state.
   Non-command input is forwarded to the agent."
  [state run-agent-fn!]
  (let [text (str/trim (charm/text-input-value (:input state)))]
    (cond
      (str/blank? text)
      [state nil]

      ;; Dispatch commands via central dispatcher
      :else
      (let [dispatch-fn (:dispatch-fn state)
            result      (when dispatch-fn (dispatch-fn text))]
        (if result
          (handle-dispatch-result state result)
          ;; Not a command — send to agent
          (submit-to-agent state run-agent-fn! text))))))

(defn- handle-agent-event
  "Process an intermediate progress event from the executor."
  [state event]
  (let [kind (:event-kind event)]
    (case kind
      :text-delta
      [(assoc state :stream-text (:text event)) nil]

      :tool-start
      (let [id   (:tool-id event)
            tc   {:name   (:tool-name event)
                  :args   ""
                  :status :pending
                  :result nil
                  :is-error false}]
        [(-> state
             (assoc-in [:tool-calls id] tc)
             (update :tool-order conj id))
         nil])

      :tool-delta
      [(assoc-in state [:tool-calls (:tool-id event) :args]
                 (:arguments event))
       nil]

      :tool-executing
      [(-> state
           (assoc-in [:tool-calls (:tool-id event) :status] :running)
           (assoc-in [:tool-calls (:tool-id event) :parsed-args]
                     (:parsed-args event)))
       nil]

      :tool-result
      [(-> state
           (assoc-in [:tool-calls (:tool-id event) :status]
                     (if (:is-error event) :error :success))
           (assoc-in [:tool-calls (:tool-id event) :result]
                     (:result-text event))
           (assoc-in [:tool-calls (:tool-id event) :is-error]
                     (:is-error event)))
       nil]

      ;; unknown event-kind — ignore
      [state nil])))

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
         (assoc :phase       :idle
                :error       error
                :queue       nil
                :stream-text nil
                :tool-calls  {}
                :tool-order  []))
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

      ;; Agent progress event (tool start, delta, result, text delta)
      (agent-event? m)
      (let [[new-state cmd] (handle-agent-event state m)]
        [new-state (or cmd (poll-cmd (:queue state)))])

      ;; Agent result
      (agent-result? m)
      (handle-agent-result state (:result m))

      ;; Agent error
      (agent-error? m)
      [(-> state
           (assoc :phase       :idle
                  :error       (:error m)
                  :queue       nil
                  :stream-text nil
                  :tool-calls  {}
                  :tool-order  []))
       nil]

      ;; Agent poll timeout → advance spinner, keep polling
      (agent-poll? m)
      (handle-agent-poll state)

      ;; Session selector active — route all key input to selector handler
      (= :selecting-session (:phase state))
      (handle-selector-key state m)

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
    (let [rendered (or (md/render-markdown text) text)
          lines    (str/split-lines rendered)
          first-line (str (charm/render assist-style "ψ: ") (first lines))
          rest-lines (map #(str "   " %) (rest lines))]
      (str/join "\n" (cons first-line rest-lines)))

    ;; fallback
    (str "[" (name role) "] " text)))

(defn- render-messages [messages]
  (when (seq messages)
    (str (str/join "\n\n" (map render-message messages)) "\n")))

(defn- render-separator []
  (charm/render sep-style (apply str (repeat 40 "─"))))

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

(def ^:private footer-query
  [:psi.agent-session/cwd
   :psi.agent-session/git-branch
   :psi.agent-session/session-name
   :psi.agent-session/usage-input
   :psi.agent-session/usage-output
   :psi.agent-session/usage-cache-read
   :psi.agent-session/usage-cache-write
   :psi.agent-session/usage-cost-total
   :psi.agent-session/context-fraction
   :psi.agent-session/context-window
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/model-provider
   :psi.agent-session/model-id
   :psi.agent-session/model-reasoning
   :psi.agent-session/thinking-level
   :psi.ui/statuses])

(defn- footer-data
  [state]
  (if-let [query-fn (:query-fn state)]
    (try
      (or (query-fn footer-query) {})
      (catch Exception _
        {}))
    {}))

(defn- format-token-count
  [n]
  (let [n (or n 0)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (format "%.1fk" (/ n 1000.0))
      (< n 1000000) (str (Math/round (double (/ n 1000.0))) "k")
      (< n 10000000) (format "%.1fM" (/ n 1000000.0))
      :else (str (Math/round (double (/ n 1000000.0))) "M"))))

(defn- sanitize-status-text
  [text]
  (-> (or text "")
      (str/replace #"[\r\n\t]" " ")
      (str/replace #" +" " ")
      (str/trim)))

(defn- replace-home-with-tilde
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and (string? path) (string? home) (str/starts-with? path home))
      (str "~" (subs path (count home)))
      (or path ""))))

(defn- middle-truncate
  [s width]
  (if (<= (ansi/display-width s) width)
    s
    (let [half (- (quot width 2) 2)]
      (if (> half 1)
        (let [start (subs s 0 (min (count s) half))
              end-len (max 0 (dec half))
              end (if (pos? end-len)
                    (subs s (max 0 (- (count s) end-len)))
                    "")]
          (str start "..." end))
        (subs s 0 (min (count s) (max 1 width)))))))

(defn- trim-right-visible
  [s width]
  (if (<= (ansi/visible-width s) width)
    s
    (ansi/strip-ansi (ansi/truncate-to-width s width "..."))))

(defn- context-piece
  [fraction context-window auto-compact?]
  (let [suffix (if auto-compact? " (auto)" "")
        window (format-token-count (or context-window 0))
        text (if (number? fraction)
               (str (format "%.1f" (* 100.0 fraction)) "%/" window suffix)
               (str "?/" window suffix))]
    (cond
      (and (number? fraction) (> fraction 0.9)) (charm/render error-style text)
      (and (number? fraction) (> fraction 0.7)) (charm/render notify-warning-style text)
      :else (charm/render dim-style text))))

(defn- build-footer-lines
  [state width]
  (let [d                (footer-data state)
        cwd              (or (:psi.agent-session/cwd d) (:cwd state) "")
        git-branch       (:psi.agent-session/git-branch d)
        session-name     (:psi.agent-session/session-name d)
        usage-input      (or (:psi.agent-session/usage-input d) 0)
        usage-output     (or (:psi.agent-session/usage-output d) 0)
        usage-cache-read (or (:psi.agent-session/usage-cache-read d) 0)
        usage-cache-write (or (:psi.agent-session/usage-cache-write d) 0)
        usage-cost-total (or (:psi.agent-session/usage-cost-total d) 0.0)
        context-fraction (:psi.agent-session/context-fraction d)
        context-window   (:psi.agent-session/context-window d)
        auto-compact?    (boolean (:psi.agent-session/auto-compaction-enabled d))
        model-provider   (:psi.agent-session/model-provider d)
        model-id         (:psi.agent-session/model-id d)
        model-reasoning? (boolean (:psi.agent-session/model-reasoning d))
        thinking-level   (:psi.agent-session/thinking-level d)
        statuses         (or (:psi.ui/statuses d) [])

        path0 (replace-home-with-tilde cwd)
        path1 (if (seq git-branch) (str path0 " (" git-branch ")") path0)
        path2 (if (seq session-name) (str path1 " • " session-name) path1)
        path-line (charm/render dim-style (middle-truncate path2 (max 1 width)))

        left-parts (cond-> []
                     (pos? usage-input) (conj (charm/render dim-style (str "↑" (format-token-count usage-input))))
                     (pos? usage-output) (conj (charm/render dim-style (str "↓" (format-token-count usage-output))))
                     (pos? usage-cache-read) (conj (charm/render dim-style (str "R" (format-token-count usage-cache-read))))
                     (pos? usage-cache-write) (conj (charm/render dim-style (str "W" (format-token-count usage-cache-write))))
                     (pos? usage-cost-total) (conj (charm/render dim-style (format "$%.3f" (double usage-cost-total))))
                     :always (conj (context-piece context-fraction context-window auto-compact?)))
        left0 (str/join " " left-parts)
        left  (if (> (ansi/visible-width left0) width)
                (trim-right-visible left0 width)
                left0)

        model-label (or model-id "no-model")
        right-base (if model-reasoning?
                     (if (= :off thinking-level)
                       (str model-label " • thinking off")
                       (str model-label " • " (name (or thinking-level :off))))
                     model-label)
        provider-label (or model-provider "no-provider")
        right0 (str "(" provider-label ") " right-base)
        right (charm/render dim-style right0)

        left-w  (ansi/visible-width left)
        right-w (ansi/visible-width right)
        min-pad 2
        total-needed (+ left-w min-pad right-w)
        stats-line
        (cond
          (<= total-needed width)
          (str left (apply str (repeat (- width left-w right-w) " ")) right)

          (> (- width left-w min-pad) 3)
          (let [avail (- width left-w min-pad)
                right-trunc (charm/render dim-style (trim-right-visible right0 avail))]
            (str left (apply str (repeat (max min-pad (- width left-w (ansi/visible-width right-trunc))) " ")) right-trunc))

          :else left)

        status-line
        (when (seq statuses)
          (let [joined (->> statuses
                            (sort-by :extension-id)
                            (map (comp sanitize-status-text :text))
                            (remove str/blank?)
                            (str/join " "))]
            (when (seq joined)
              (ansi/truncate-to-width joined width (charm/render dim-style "...")))))

        lines (cond-> [path-line stats-line]
                status-line (conj status-line))]
    lines))

(defn- render-footer
  [state width]
  (let [lines (build-footer-lines state width)]
    (str (str/join "\n" lines) "\n")))

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

;; ── Text input word wrap ─────────────────────────────────────

(defn- wrap-chunks
  "Split plain text into word-wrapped chunks with position tracking.
   Returns [{:text \"line\" :start N :end N} ...] where start/end are
   character indices in the original text. Whitespace at break points
   is consumed (trimmed from line end, skipped at next line start)."
  [^String text max-width]
  (if (or (nil? text) (empty? text))
    [{:text "" :start 0 :end 0}]
    (let [len (count text)]
      (loop [start 0, chunks []]
        (if (>= start len)
          (if (empty? chunks)
            [{:text "" :start 0 :end 0}]
            chunks)
          (let [[end-idx]
                (loop [j start, col 0, last-break -1]
                  (if (>= j len)
                    [j]
                    (let [c (.charAt text j)
                          cw (ansi/char-width c)]
                      (if (> (+ col cw) max-width)
                        ;; Overflow — break at word boundary or hard break
                        (if (pos? last-break)
                          [last-break]
                          [j])
                        ;; Fits — advance; track break after space before non-space
                        (recur (inc j) (+ col cw)
                               (if (and (Character/isWhitespace c)
                                        (< (inc j) len)
                                        (not (Character/isWhitespace
                                              (.charAt text (inc j)))))
                                 (inc j)
                                 last-break))))))
                chunk-text (str/trimr (subs text start end-idx))
                ;; Skip whitespace for next line start
                next-start (loop [ns (long end-idx)]
                             (if (and (< ns len)
                                      (Character/isWhitespace (.charAt text ns)))
                               (recur (inc ns))
                               ns))]
            (if (= next-start start)
              ;; Safety: force progress
              (recur (inc start)
                     (conj chunks {:text (str (.charAt text start))
                                   :start start :end (inc start)}))
              (recur next-start
                     (conj chunks {:text chunk-text
                                   :start start :end end-idx})))))))))

(defn- wrap-text-input-view
  "Render text input with word wrapping at terminal width.
   Continuation lines indent to align with the prompt end."
  [input width]
  (let [{:keys [prompt value pos focused cursor-style
                prompt-style placeholder-style placeholder]} input
        prompt-str (if prompt-style
                     (charm/render prompt-style prompt)
                     (or prompt ""))
        prompt-w   (ansi/visible-width (or prompt ""))
        avail      (max 1 (- width prompt-w))
        indent     (apply str (repeat prompt-w \space))
        cursor-sty (or cursor-style (charm/style :reverse true))]
    (if (and (empty? value) placeholder (not (str/blank? placeholder)))
      ;; Placeholder
      (str prompt-str
           (if focused
             (str (charm/render cursor-sty (subs placeholder 0 1))
                  (if placeholder-style
                    (charm/render placeholder-style (subs placeholder 1))
                    (subs placeholder 1)))
             (if placeholder-style
               (charm/render placeholder-style placeholder)
               placeholder)))
      ;; Normal value — word-wrap and place cursor
      (let [text   (apply str value)
            chunks (wrap-chunks text avail)]
        (str/join
         "\n"
         (map-indexed
          (fn [i {:keys [text start end]}]
            (let [prefix  (if (zero? i) prompt-str indent)
                  is-last (= i (dec (count chunks)))
                  ;; Cursor in this chunk? Last chunk owns pos >= start;
                  ;; others own start <= pos < end
                  cursor? (and focused
                               (>= pos start)
                               (or is-last (< pos end)))]
              (if cursor?
                (let [lp     (- pos start)
                      before (subs text 0 (min lp (count text)))
                      c-char (if (< lp (count text))
                               (subs text lp (inc lp))
                               " ")
                      after  (if (< lp (count text))
                               (subs text (inc lp))
                               "")]
                  (str prefix before
                       (charm/render cursor-sty c-char)
                       after))
                (str prefix text))))
          chunks))))))

;; ── Session selector rendering ──────────────────────────────

(def ^:private selector-title-style  (charm/style :fg charm/magenta :bold true))
(def ^:private selector-sel-style    (charm/style :fg charm/cyan :bold true))
(def ^:private selector-cur-style    (charm/style :fg charm/yellow))
(def ^:private selector-hint-style   dim-style)
(def ^:private selector-search-style (charm/style :fg charm/green))

(defn- shorten-path [p]
  (let [home (System/getProperty "user.home")]
    (if (and p (.startsWith ^String p home))
      (str "~" (subs p (count home)))
      (or p ""))))

(defn- render-session-selector
  "Render the /resume session picker."
  [sel-state current-session-file width]
  (let [{:keys [scope search selected]} sel-state
        filtered  (selector-filtered sel-state)
        n         (count filtered)
        scope-str (if (= :current scope)
                    (str (charm/render selector-sel-style "◉ Current") "  ○ All")
                    (str "○ Current  " (charm/render selector-sel-style "◉ All")))
        title     (str (charm/render selector-title-style "Resume Session")
                       "  " scope-str
                       "  " (charm/render selector-hint-style "[Tab=scope ↑↓=nav Enter=select Esc=cancel]"))]
    (str title "\n"
         (charm/render selector-search-style (str "Search: " search "█")) "\n"
         (render-separator) "\n"
         (if (zero? n)
           (charm/render dim-style "  (no sessions found)\n")
           (str/join "\n"
                     (map-indexed
                      (fn [i info]
                        (let [is-sel     (= i selected)
                              is-current (= (:path info) current-session-file)
                              age        (format-age (:modified info))
                              label      (or (:name info) (:first-message info) "(empty)")
                              label      (str/replace label #"\n" " ")
                              cwd-part   (when (= :all scope)
                                           (str " " (charm/render dim-style
                                                                  (shorten-path (:cwd info)))))
                              right      (str (charm/render dim-style
                                                            (str (:message-count info) " " age))
                                              (or cwd-part ""))
                              right-w    (count right) ; approximate
                              avail      (max 10 (- width 4 right-w))
                              label-tr   (if (> (count label) avail)
                                           (str (subs label 0 (- avail 1)) "…")
                                           label)
                              cursor     (if is-sel
                                           (charm/render selector-sel-style "▸ ")
                                           "  ")
                              styled-lbl (cond
                                           is-current (charm/render selector-cur-style label-tr)
                                           is-sel     (charm/render selector-sel-style label-tr)
                                           :else      label-tr)
                              pad        (str/join (repeat (max 1 (- width 2 (count label-tr) right-w)) " "))]
                          (str cursor styled-lbl pad right)))
                      filtered)))
         "\n"
         (when (pos? n)
           (charm/render dim-style (str "  " (inc selected) "/" n "\n"))))))

;; ── Tool progress rendering ──────────────────────────────────

(def ^:private tool-result-preview-lines 5)

(defn- tool-header
  "Format tool name and key argument for display."
  [tool-name parsed-args args-str]
  (let [args (or parsed-args
                 (try (json/parse-string args-str)
                      (catch Exception _ nil)))]
    (case tool-name
      "read"  (str (charm/render tool-style "read")  " " (get args "path" "…"))
      "bash"  (str (charm/render tool-style "$")      " " (get args "command" "…"))
      "edit"  (str (charm/render tool-style "edit")  " " (get args "path" "…"))
      "write" (str (charm/render tool-style "write") " " (get args "path" "…"))
      (str (charm/render tool-style tool-name)))))

(defn- tool-status-indicator
  "Status icon for a tool execution."
  [status spinner-char]
  (case status
    :pending (str spinner-char)
    :running (str spinner-char)
    :success (charm/render tool-ok-style "✓")
    :error   (charm/render tool-err-style "✗")
    ""))

(defn- truncate-result
  "Truncate tool result to N lines."
  [text max-lines]
  (when (and text (not (str/blank? text)))
    (let [lines (str/split-lines text)
          n     (count lines)]
      (if (<= n max-lines)
        text
        (str (str/join "\n" (take max-lines lines))
             "\n" (charm/render dim-style
                                (str "… (" (- n max-lines) " more lines)")))))))

(defn- render-tool-calls
  "Render all tool calls for the current turn."
  [tool-calls tool-order spinner-char]
  (when (seq tool-order)
    (str/join
     "\n"
     (for [id tool-order
           :let [tc (get tool-calls id)]
           :when tc]
       (let [status-icon (tool-status-indicator (:status tc) spinner-char)
             header      (tool-header (:name tc) (:parsed-args tc) (:args tc))
             result      (truncate-result (:result tc) tool-result-preview-lines)
             result-style (if (:is-error tc) tool-err-style tool-dim-style)]
         (str "  " status-icon " " header
              (when result
                (str "\n"
                     (str/join "\n"
                               (map #(str "    " (charm/render result-style %))
                                    (str/split-lines result)))))))))))

(defn- render-stream-text
  "Render accumulated streaming text from the LLM with markdown styling."
  [text]
  (when (and text (not (str/blank? text)))
    (let [rendered (or (md/render-markdown text) text)
          lines    (str/split-lines rendered)
          first-line (str (charm/render assist-style "ψ: ") (first lines))
          rest-lines (map #(str "   " %) (rest lines))]
      (str (str/join "\n" (cons first-line rest-lines)) "\n"))))

(defn view
  "Render the full TUI state to a string."
  [state]
  (let [{:keys [messages phase error input spinner-frame model-name
                prompt-templates skills extension-summary ui-state-atom
                stream-text tool-calls tool-order
                session-selector current-session-file width]} state
        spinner-char   (nth spinner-frames (mod spinner-frame (count spinner-frames)))
        dialog-active? (has-active-dialog? state)
        has-progress?  (or (seq stream-text) (seq tool-order))
        term-width     (or width 80)]
    (if (= :selecting-session phase)
      ;; Session selector takes over the whole screen
      (str (render-banner model-name prompt-templates skills extension-summary)
           "\n"
           (render-session-selector session-selector current-session-file term-width))
      ;; Normal chat view
      (str (render-banner model-name prompt-templates skills extension-summary)
           "\n"
           (render-messages messages)
           ;; Current turn progress
           (when (= :streaming phase)
             (if has-progress?
               (str (render-stream-text stream-text)
                    (render-tool-calls tool-calls tool-order spinner-char)
                    "\n")
               (str "\n" (charm/render assist-style "ψ: ")
                    spinner-char " thinking…\n")))
           (when error
             (str "\n" (charm/render error-style (str "[error: " error "]")) "\n"))
           ;; Widgets above editor
           (render-widgets ui-state-atom :above-editor)
           "\n"
           (render-separator) "\n"
           ;; Dialog replaces editor when active
           (if dialog-active?
             (render-dialog ui-state-atom)
             (if (= :idle phase)
               (wrap-text-input-view input term-width)
               (charm/render dim-style "(waiting for response…)")))
           "\n"
           (render-separator) "\n"
           ;; Widgets below editor
           (render-widgets ui-state-atom :below-editor)
           ;; Default footer (path, stats, statuses)
           (render-footer state term-width)
           ;; Notifications toast
           (render-notifications ui-state-atom)))))

;; ── Public entry point ──────────────────────────────────────

(defn start!
  "Run the Psi TUI. Blocks until the user exits.

   `model-name`     — display name for the banner
   `run-agent-fn!`  — (fn [text queue]) starts agent in background;
                       must put {:kind :done :result msg} or
                       {:kind :error :message str} on queue.
   `opts`           — optional map:
                       :query-fn            — (fn [eql-query]) for session introspection
                       :ui-state-atom       — extension UI state atom
                       :cwd                 — working directory for /resume filtering
                       :current-session-file — current session file path for highlight
                       :resume-fn!          — (fn [session-path]) => [{:role :user/:assistant :text ...}]"
  ([model-name run-agent-fn!]
   (start! model-name run-agent-fn! {}))
  ([model-name run-agent-fn! opts]
   (charm/run {:init   (make-init model-name (:query-fn opts) (:ui-state-atom opts) opts)
               :update (make-update run-agent-fn!)
               :view   view
               :alt-screen true})))
