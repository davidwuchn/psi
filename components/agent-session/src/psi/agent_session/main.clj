(ns psi.agent-session.main
  "Entry point: run an interactive agent session on the terminal.

   Usage:
     clojure -M -m psi.agent-session.main
     clojure -M -m psi.agent-session.main --model claude-3-5-haiku
     clojure -M -m psi.agent-session.main --log-level DEBUG
     clojure -M -m psi.agent-session.main --tui

   What it does:
     1. Creates an agent session (statechart + agent-core + extension registry)
     2. Wires the ai provider (Anthropic by default) into the executor
     3. Registers the four built-in tools: read, bash, edit, write
     4. Drops into a REPL-style prompt loop — type a message, get a response
        OR (with --tui) renders an interactive TUI session
     5. /quit or EOF exits (plain mode); Escape or Ctrl+C exits (TUI mode)

   Environment variables:
     ANTHROPIC_API_KEY   — required for Anthropic models
     OPENAI_API_KEY      — required for OpenAI models
     PSI_MODEL           — model key override (e.g. claude-3-5-haiku, gpt-4o)

   Introspection (plain mode only):
     /status  — print session diagnostics via EQL
     /history — print message history
     /help    — print available commands"
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.core :as session]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.tools :as tools]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.tui.core :as tui]
   [psi.tui.components :as comp]
   [psi.tui.protocols :as proto]
   [psi.tui.terminal :as terminal])
  (:gen-class))

;; ============================================================
;; Logging
;; ============================================================

(def ^:private valid-log-levels
  #{:trace :debug :info :warn :error :fatal :report})

(defn- set-log-level!
  "Set Timbre's minimum log level (case-insensitive keyword).
  Defaults to :info if the string is unrecognised."
  [level-str]
  (let [kw    (keyword (str/lower-case (or level-str "info")))
        level (if (valid-log-levels kw) kw :info)]
    (timbre/set-min-level! level)))

(defn- log-level-from-args
  "Extract --log-level <LEVEL> from CLI args, or fall back to INFO."
  [args]
  (or (second (drop-while #(not= "--log-level" %) args))
      "INFO"))

;; ============================================================
;; Model resolution
;; ============================================================

(def ^:private default-model-key :claude-3-5-haiku)

(defn- resolve-model
  "Return an ai.schemas.Model map for `model-key` keyword."
  [model-key]
  (or (get models/all-models model-key)
      (throw (ex-info (str "Unknown model: " model-key
                           "\nAvailable: " (str/join ", " (map name (keys models/all-models))))
                      {:model-key model-key}))))

(defn- model-key-from-args
  "Extract --model <key> from CLI args vector, or fall back to PSI_MODEL env var."
  [args]
  (let [env-model (System/getenv "PSI_MODEL")]
    (keyword
     (or (second (drop-while #(not= "--model" %) args))
         env-model
         (name default-model-key)))))

;; ============================================================
;; Output helpers
;; ============================================================

(defn- print-banner [model]
  (println)
  (println "╔══════════════════════════════════════╗")
  (println "║  ψ  Psi Agent Session                ║")
  (println "╚══════════════════════════════════════╝")
  (println (str "  Model : " (:name model)))
  (println (str "  Tools : " (str/join ", " (map :name tools/all-tool-schemas))))
  (println "  /help for commands, /quit to exit")
  (println))

(defn- print-status [ctx]
  (let [d (session/query-in ctx
                            [:psi.agent-session/phase
                             :psi.agent-session/session-id
                             :psi.agent-session/model
                             :psi.agent-session/thinking-level
                             :psi.agent-session/extension-summary
                             :psi.agent-session/session-entry-count
                             :psi.agent-session/context-tokens
                             :psi.agent-session/context-window
                             :psi.agent-session/context-fraction])]
    (println "\n── Session status ─────────────────────")
    (println (str "  Phase   : " (:psi.agent-session/phase d)))
    (println (str "  ID      : " (:psi.agent-session/session-id d)))
    (println (str "  Model   : " (get-in d [:psi.agent-session/model :id] "none")))
    (println (str "  Entries : " (:psi.agent-session/session-entry-count d)))
    (when-let [frac (:psi.agent-session/context-fraction d)]
      (println (str "  Context : " (int (* 100 frac)) "%")))
    (println "───────────────────────────────────────\n")))

(defn- print-history [ctx]
  (let [msgs (:messages (agent/get-data-in (:agent-ctx ctx)))]
    (println "\n── Message history ────────────────────")
    (if (empty? msgs)
      (println "  (empty)")
      (doseq [msg msgs]
        (let [role  (:role msg)
              text  (or (some #(when (= :text (:type %)) (:text %)) (:content msg))
                        (str "[" (:role msg) "]"))]
          (println (str "  [" role "] " (subs text 0 (min 120 (count text))))))))
    (println "───────────────────────────────────────\n")))

(defn- print-help []
  (println "\n── Commands ───────────────────────────")
  (println "  /quit    — exit the session")
  (println "  /status  — show session diagnostics")
  (println "  /history — show message history")
  (println "  /new     — start a fresh session")
  (println "  /help    — show this help")
  (println "  (anything else is sent to the agent)")
  (println "───────────────────────────────────────\n"))

;; ============================================================
;; Response printing — streams to stdout as tokens arrive
;; ============================================================

(defn- print-assistant-message
  "Print the text content of an assistant message map.
  If the message carries an error, print it clearly instead of (or after) any partial text."
  [msg]
  (let [text   (str/join (keep #(when (= :text  (:type %)) (:text %)) (:content msg)))
        errors (keep     #(when (= :error (:type %)) (:text %)) (:content msg))]
    (when (seq text)
      (println (str "\nψ: " text "\n")))
    (doseq [err errors]
      (println (str "\n[Provider error: " err "]\n")))
    (when (and (empty? text) (empty? errors))
      (println "\nψ: (no response)\n"))))

;; ============================================================
;; Core: one prompt → response cycle
;; ============================================================

(defn- run-prompt!
  "Send `text` to the agent and block until done, printing the response."
  [ctx ai-ctx ai-model text]
  (let [user-msg {:role      "user"
                  :content   [{:type :text :text text}]
                  :timestamp (java.time.Instant/now)}
        result   (executor/run-agent-loop! ai-ctx (:agent-ctx ctx) ai-model [user-msg])]
    (print-assistant-message result)))

;; ============================================================
;; Main prompt loop
;; ============================================================

(defn run-session
  "Create a session and enter the interactive prompt loop.
  Returns when the user exits."
  [model-key]
  (let [ai-model (resolve-model model-key)
        ;; ai context: nil signals the executor to use the public ai/stream-response API
        ai-ctx   nil
        ;; session context — agent-core + statechart + extension registry
        ctx      (session/create-context
                  {:initial-session {:model {:provider (name (:provider ai-model))
                                             :id       (:id ai-model)
                                             :reasoning (:supports-reasoning ai-model)}}})]
    ;; Wire agent-session resolvers into the global query graph so
    ;; :psi.agent-session/* attributes are queryable.
    (session/register-resolvers!)
    ;; Register built-in tools into agent-core
    (agent/set-tools-in! (:agent-ctx ctx) tools/all-tool-schemas)
    (agent/set-system-prompt-in!
     (:agent-ctx ctx)
     (str "You are ψ (Psi), a helpful AI coding assistant.\n"
          "Working directory: " (System/getProperty "user.dir") "\n"
          "You have access to tools: read, bash, edit, write.\n"
          "Use them to help with coding tasks."))
    (print-banner ai-model)
    (loop []
      (print "刀: ")
      (flush)
      (when-let [line (try (read-line) (catch Exception _ nil))]
        (let [trimmed (str/trim line)]
          (cond
            (or (= trimmed "/quit") (= trimmed "/exit"))
            (println "\nψ: Goodbye.\n")

            (= trimmed "/status")
            (do (print-status ctx) (recur))

            (= trimmed "/history")
            (do (print-history ctx) (recur))

            (= trimmed "/new")
            (do (session/new-session-in! ctx)
                (println "\n[New session started]\n")
                (recur))

            (or (= trimmed "/help") (= trimmed "/?"))
            (do (print-help) (recur))

            (str/blank? trimmed)
            (recur)

            :else
            (do
              (try
                (run-prompt! ctx ai-ctx ai-model trimmed)
                (catch Exception e
                  (println (str "\n[Error: " (ex-message e) "]\n"))))
              (recur))))))))

;; ============================================================
;; TUI session
;; ============================================================

;; TUI state shape:
;;   {:messages       [{:role :user|:assistant :text "…"}]
;;    :streaming-text nil | String   ; partial assistant response while streaming
;;    :phase          :idle | :streaming | :error
;;    :error          nil | String}

(defn- make-tui-state []
  {:messages       []
   :streaming-text nil
   :phase          :idle
   :error          nil})

(defn- message->lines
  "Format a single conversation message as a vector of display strings."
  [{:keys [role text]}]
  (let [prefix     (case role
                     :user      "刀: "
                     :assistant "ψ: "
                     "   ")
        all-lines  (str/split-lines text)
        first-line (str prefix (first all-lines))
        rest-lines (mapv #(str (apply str (repeat (count prefix) \space)) %) (rest all-lines))]
    (into [first-line] rest-lines)))

(defn- build-tui-children
  "Build the TUI child component list from current tui-state."
  [{:keys [messages streaming-text phase error]} editor]
  (let [;; History messages
        history-lines (into [] (mapcat message->lines messages))
        history-text  (if (seq history-lines)
                        (str/join "\n" history-lines)
                        "")
        history-comp  (when (seq history-lines)
                        (comp/create-text history-text))

        ;; Streaming partial response
        stream-comp   (when streaming-text
                        (comp/create-text (str "ψ: " streaming-text)))

        ;; Loader shown while streaming
        loader-comp   (when (= phase :streaming)
                        (assoc (comp/create-loader "thinking…") :running true))

        ;; Error display
        error-comp    (when error
                        (comp/create-text (str "[error: " error "]")))

        ;; Separator line
        sep-comp      (comp/create-text (apply str (repeat 40 "─")))

        ;; Prompt label above editor
        prompt-comp   (comp/create-text "刀:")]

    (filterv some?
             [history-comp
              stream-comp
              loader-comp
              error-comp
              sep-comp
              prompt-comp
              editor])))

(defn- start-render-loop!
  "Start a daemon thread that calls tick-in! at ~30 fps.
  Returns the thread."
  [tui-ctx stop-atom]
  (doto (Thread.
         (fn []
           (while (not @stop-atom)
             (try
               (tui/tick-in! tui-ctx)
               (catch Exception _))
             (Thread/sleep 33)))
         "tui-render-loop")
    (.setDaemon true)
    (.start)))

(defn- run-agent-async!
  "Run the agent loop in a background thread.
  Updates `tui-state-atom` with streaming tokens and completion.
  Rebuilds TUI children on each update and requests a render.
  `editor-atom` holds the current Editor component (for child rebuild)."
  [ctx ai-ctx ai-model text tui-ctx tui-state-atom editor-atom]
  (future
    (swap! tui-state-atom assoc :phase :streaming :streaming-text "" :error nil)
    (tui/swap-tui-children-in! tui-ctx
                               (build-tui-children @tui-state-atom @editor-atom))
    (tui/request-render-in! tui-ctx)
    (try
      (let [user-msg   {:role      "user"
                        :content   [{:type :text :text text}]
                        :timestamp (java.time.Instant/now)}
            result     (executor/run-agent-loop!
                        ai-ctx
                        (:agent-ctx ctx)
                        ai-model
                        [user-msg])
            final-text (or (some #(when (= :text (:type %)) (:text %))
                                 (:content result))
                           "")
            error-text (first (keep #(when (= :error (:type %)) (:text %))
                                    (:content result)))
            add-msg    (fn [msgs]
                         (-> msgs
                             (conj {:role :user :text text})
                             (conj {:role :assistant :text (or final-text "(no response)")})))]
        (swap! tui-state-atom
               #(-> %
                    (update :messages add-msg)
                    (assoc :streaming-text nil
                           :phase          :idle
                           :error          error-text))))
      (catch Exception e
        (swap! tui-state-atom assoc
               :phase          :idle
               :streaming-text nil
               :error          (ex-message e))))
    (tui/swap-tui-children-in! tui-ctx
                               (build-tui-children @tui-state-atom @editor-atom))
    (tui/request-render-in! tui-ctx)))

(defn run-tui-session
  "Create a session and run it as a full-screen TUI.
  Blocks until the user exits (Escape or Ctrl+C while idle)."
  [model-key]
  (let [ai-model (resolve-model model-key)
        ai-ctx   nil
        ctx      (session/create-context
                  {:initial-session {:model {:provider (name (:provider ai-model))
                                             :id       (:id ai-model)
                                             :reasoning (:supports-reasoning ai-model)}}})
        _        (session/register-resolvers!)
        _        (agent/set-tools-in! (:agent-ctx ctx) tools/all-tool-schemas)
        _        (agent/set-system-prompt-in!
                  (:agent-ctx ctx)
                  (str "You are ψ (Psi), a helpful AI coding assistant.\n"
                       "Working directory: " (System/getProperty "user.dir") "\n"
                       "You have access to tools: read, bash, edit, write.\n"
                       "Use them to help with coding tasks."))

        ;; Shared state
        tui-state-atom (atom (make-tui-state))
        stop-atom      (atom false)
        editor-atom    (atom (comp/create-editor {:padding-x 0}))

        ;; Build initial children
        initial-children (build-tui-children @tui-state-atom @editor-atom)

        ;; Create real terminal — try to detect dimensions via stty
        [cols rows] (try
                      (let [p   (-> (ProcessBuilder. ["stty" "size"])
                                    (.redirectInput (java.io.File. "/dev/tty"))
                                    (.start))
                            out (slurp (.getInputStream p))
                            _   (.waitFor p)
                            [r c] (map #(Integer/parseInt %) (str/split (str/trim out) #" "))]
                        [c r])
                      (catch Exception _ [80 24]))
        term (terminal/create-process-terminal {:cols cols :rows rows})
        tui-ctx (tui/create-context term initial-children)]

    ;; Focus the editor
    (tui/set-focus-in! tui-ctx @editor-atom)

    ;; Wire editor submit → run agent; escape/ctrl+c → quit
    ;; We override handle-input by watching the tui state instead of subclassing.
    ;; We use a custom input hook: patch the terminal's on-input to intercept
    ;; submit and quit before routing to the TUI.
    ;;
    ;; Strategy: wrap start-tui-in! with a custom on-input callback that
    ;; checks for enter (submit) and escape/ctrl+c (quit) at the session level.
    ;; We achieve this by replacing the terminal's input callback after start.

    (let [render-thread (start-render-loop! tui-ctx stop-atom)]

      ;; Custom input handler wrapping the normal TUI input routing.
      (letfn [(on-input [raw]
                (cond
                  ;; Quit signals (when idle)
                  (and (= :idle (:phase @tui-state-atom))
                       (or (= raw "escape")
                           (= raw "\u0003")))  ; Ctrl+C
                  (reset! stop-atom true)

                  ;; Editor submit — enter key when idle
                  (and (= :idle (:phase @tui-state-atom))
                       (= raw "enter")
                       (not (str/blank? (str/join "\n" (:lines @editor-atom)))))
                  (let [text (str/join "\n" (:lines @editor-atom))]
                    ;; Reset editor
                    (reset! editor-atom (comp/create-editor {:padding-x 0}))
                    ;; Update TUI children to show reset editor
                    (tui/swap-tui-children-in! tui-ctx
                                               (build-tui-children @tui-state-atom @editor-atom))
                    (tui/set-focus-in! tui-ctx @editor-atom)
                    (tui/request-render-in! tui-ctx)
                    ;; Run agent in background
                    (run-agent-async! ctx ai-ctx ai-model text
                                      tui-ctx tui-state-atom editor-atom))

                  ;; All other keys — route through TUI normally,
                  ;; updating editor-atom with the new component state.
                  :else
                  (do
                    (tui/handle-input-in! tui-ctx raw)
                    ;; Sync editor-atom from tui focused component
                    (when-let [focused (tui/focused-in tui-ctx)]
                      (reset! editor-atom focused))
                    (tui/request-render-in! tui-ctx))))]

        ;; Start terminal with our custom on-input handler
        (let [orig-on-resize (fn [_c _r] (tui/request-render-in! tui-ctx))]
          (proto/start! term on-input orig-on-resize))
        ;; Hide cursor and do first render
        (.print System/out "\u001b[?25l")
        (.flush System/out)
        (tui/request-render-in! tui-ctx)
        (tui/tick-in! tui-ctx))

      ;; Block until stop-atom is set
      (loop []
        (when-not @stop-atom
          (Thread/sleep 50)
          (recur)))

      ;; Tear down
      (tui/stop-tui-in! tui-ctx)
      (.interrupt render-thread))))

;; ============================================================
;; -main
;; ============================================================

(defn -main
  "Entry point. Accepts optional --model <key>, --log-level <LEVEL>, and --tui flags."
  [& args]
  (set-log-level! (log-level-from-args args))
  (let [model-key (model-key-from-args args)
        tui?      (some #(= "--tui" %) args)]
    (if tui?
      (run-tui-session model-key)
      (run-session model-key)))
  ;; clj-http (Apache HttpClient) parks a non-daemon connection-eviction thread.
  ;; Explicitly exit so the JVM does not hang after /quit.
  (System/exit 0))
