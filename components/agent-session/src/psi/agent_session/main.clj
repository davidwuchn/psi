(ns psi.agent-session.main
  "Entry point: run an interactive agent session on the terminal.

   Usage:
     clojure -M -m psi.agent-session.main
     clojure -M -m psi.agent-session.main --model claude-3-5-haiku
     clojure -M -m psi.agent-session.main --log-level DEBUG

   What it does:
     1. Creates an agent session (statechart + agent-core + extension registry)
     2. Wires the ai provider (Anthropic by default) into the executor
     3. Registers the four built-in tools: read, bash, edit, write
     4. Drops into a REPL-style prompt loop — type a message, get a response
     5. /quit or EOF exits

   Environment variables:
     ANTHROPIC_API_KEY   — required for Anthropic models
     OPENAI_API_KEY      — required for OpenAI models
     PSI_MODEL           — model key override (e.g. claude-3-5-haiku, gpt-4o)

   Introspection:
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
   [psi.ai.models :as models])
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
;; -main
;; ============================================================

(defn -main
  "Entry point. Accepts optional --model <key> and --log-level <LEVEL> flags."
  [& args]
  (set-log-level! (log-level-from-args args))
  (let [model-key (model-key-from-args args)]
    (run-session model-key)))
