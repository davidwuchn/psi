(ns psi.agent-session.main
  "Entry point: run an interactive agent session on the terminal.

   Usage:
     clojure -M:run
     clojure -M:run --model sonnet-4.6
     clojure -M:run --log-level DEBUG
     clojure -M:run --tui
     clojure -M:run --rpc-edn         # EDN-lines RPC on stdin/stdout
     clojure -M:run --nrepl            # random port
     clojure -M:run --nrepl 7888       # specific port
     clojure -M:run --tui --nrepl      # TUI + nREPL
     clojure -M:run --memory-store in-memory
     clojure -M:run --memory-store-fallback off --memory-retention-snapshots 500

   What it does:
     1. Creates an agent session (statechart + agent-core + extension registry)
     2. Wires the ai provider (Anthropic by default) into the executor
     3. Registers the four built-in tools: read, bash, edit, write
     4. Optionally starts an nREPL server for live introspection
     5. Drops into a REPL-style prompt loop — type a message, get a response
        OR (with --tui) renders an interactive TUI session
     6. /quit or EOF exits (plain mode); TUI uses Escape interrupt/cancel,
        Ctrl+C clear-then-quit, Ctrl+D exit-when-empty

   Environment variables:
     ANTHROPIC_API_KEY    — required for Anthropic models
     OPENAI_API_KEY       — required for OpenAI models
     PSI_MODEL            — model key override (e.g. claude-3-5-haiku, gpt-4o, gpt-5.4)
     PSI_DEVELOPER_PROMPT — optional developer instruction text
     PSI_MEMORY_STORE     — optional memory provider (in-memory)
     PSI_MEMORY_STORE_AUTO_FALLBACK
     PSI_MEMORY_HISTORY_COMMIT_LIMIT
     PSI_MEMORY_RETENTION_SNAPSHOTS
     PSI_MEMORY_RETENTION_DELTAS

   nREPL introspection (from a connected REPL):
     @psi.agent-session.main/session-state  — live session context
     (require '[psi.agent-session.core :as s])
     (s/query-in (:ctx @psi.agent-session.main/session-state)
       [:psi.agent-session/phase :psi.agent-session/session-id])

   Introspection (plain mode only):
     /status  — print session diagnostics via EQL
     /history — print message history
     /help    — print available commands"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.bootstrap :as session-bootstrap]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.service :as service]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.config-resolution :as config-res]
   [psi.agent-session.rpc :as rpc]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.agent-session.tools :as tools]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.system-bootstrap.core :as bootstrap]
   [psi.memory.runtime :as memory-runtime]
   [psi.recursion.core :as recursion])
   ;; [psi.tui.app :as tui-app]  ; Removed - circular dependency fix

  (:gen-class))

;; ============================================================
;; Live session state — accessible from nREPL
;; ============================================================

(defonce session-state
  (atom nil))

(defonce nrepl-runtime
  (atom nil))

;; ============================================================
;; nREPL server (started conditionally via --nrepl)
;; ============================================================

(defn- start-nrepl!
  "Start an nREPL server on `port` (0 = random). Returns the server.
   Writes the bound port to .nrepl-port for editor auto-discovery."
  [port]
  (let [start-server (requiring-resolve 'nrepl.server/start-server)
        server       (start-server :port port)
        host         "localhost"
        port-file    (java.io.File. ".nrepl-port")]
    (reset! nrepl-runtime {:host host
                           :port (:port server)
                           :endpoint (str host ":" (:port server))})
    (when-let [ctx (:ctx @session-state)]
      (psi.agent-session.state-accessors/set-nrepl-runtime-in!
       ctx
       {:host host
        :port (:port server)
        :endpoint (str host ":" (:port server))}))
    (spit port-file (str (:port server)))
    (.deleteOnExit port-file)
    (.println System/err (str "  nREPL : " host ":" (:port server)
                              " (connect with your editor)"))
    server))

(defn- stop-nrepl! [server]
  (when server
    (let [stop-server (requiring-resolve 'nrepl.server/stop-server)
          port-file   (java.io.File. ".nrepl-port")]
      (reset! nrepl-runtime nil)
      (when-let [ctx (:ctx @session-state)]
        (psi.agent-session.state-accessors/set-nrepl-runtime-in! ctx nil))
      (stop-server server)
      (when (.exists port-file)
        (when (= (str/trim (slurp port-file)) (str (:port server)))
          (.delete port-file))))))

(defn- nrepl-port-from-args
  "If --nrepl is present, return the port (next arg if numeric, else 0).
   Returns nil if --nrepl is absent."
  [args]
  (when (some #(= "--nrepl" %) args)
    (let [after (second (drop-while #(not= "--nrepl" %) args))]
      (if (and after (re-matches #"\d+" after))
        (Integer/parseInt after)
        0))))

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

(def ^:private default-model-key :sonnet-4.6)

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

(defn- resolve-model-by-provider+id
  "Find a runtime model map by provider string + model-id string."
  [provider model-id]
  (let [provider* (some-> provider keyword)]
    (some (fn [[_ model]]
            (when (and (= provider* (:provider model))
                       (= model-id (:id model)))
              model))
          models/all-models)))

(defn- arg-value
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- rpc-trace-file-from-args
  "Extract optional --rpc-trace-file <path> value.
   Blank values are treated as nil."
  [args]
  (let [v (arg-value args "--rpc-trace-file")]
    (when-not (str/blank? v)
      v)))

(defn- parse-bool-arg
  [v]
  (when (string? v)
    (let [x (str/lower-case (str/trim v))]
      (cond
        (contains? #{"1" "true" "yes" "y" "on"} x) true
        (contains? #{"0" "false" "no" "n" "off"} x) false
        :else nil))))

(defn- parse-positive-int-arg
  [v]
  (when (string? v)
    (try
      (let [n (Integer/parseInt (str/trim v))]
        (when (pos? n) n))
      (catch Exception _
        nil))))

(defn- memory-runtime-opts-from-args
  "Extract optional memory runtime config flags from CLI args.

   CLI flags:
   - --memory-store <in-memory>
   - --memory-store-fallback <on|off|true|false>
   - --memory-history-limit <positive-int>
   - --memory-retention-snapshots <positive-int>
   - --memory-retention-deltas <positive-int>"
  [args]
  (let [store-provider      (arg-value args "--memory-store")
        fallback            (parse-bool-arg (arg-value args "--memory-store-fallback"))
        history-limit       (parse-positive-int-arg (arg-value args "--memory-history-limit"))
        retention-snapshots (parse-positive-int-arg (arg-value args "--memory-retention-snapshots"))
        retention-deltas    (parse-positive-int-arg (arg-value args "--memory-retention-deltas"))]
    (cond-> {}
      (some? store-provider)      (assoc :store-provider store-provider)
      (some? fallback)            (assoc :auto-store-fallback? fallback)
      (some? history-limit)       (assoc :history-commit-limit history-limit)
      (some? retention-snapshots) (assoc :retention-snapshots retention-snapshots)
      (some? retention-deltas)    (assoc :retention-deltas retention-deltas))))

(defn- llm-idle-timeout-ms-from-env
  "Parse optional LLM idle timeout from PSI_LLM_IDLE_TIMEOUT_MS env var."
  []
  (parse-positive-int-arg (System/getenv "PSI_LLM_IDLE_TIMEOUT_MS")))

(defn- session-runtime-config-from-args
  "Extract optional session/runtime config flags from CLI args.

   CLI flags:
   - --llm-idle-timeout-ms <positive-int>

   Env fallback:
   - PSI_LLM_IDLE_TIMEOUT_MS <positive-int>

   Precedence:
   CLI flag > env var > default config."
  [args]
  (let [idle-timeout-arg-raw (arg-value args "--llm-idle-timeout-ms")
        idle-timeout-arg     (parse-positive-int-arg idle-timeout-arg-raw)
        idle-timeout-ms      (if (some? idle-timeout-arg-raw)
                               idle-timeout-arg
                               (llm-idle-timeout-ms-from-env))]
    (cond-> {}
      (some? idle-timeout-ms) (assoc :llm-stream-idle-timeout-ms idle-timeout-ms))))

(defn- developer-prompt-from-env
  "Return optional developer prompt text from PSI_DEVELOPER_PROMPT.
   Blank values are treated as nil."
  []
  (let [v (System/getenv "PSI_DEVELOPER_PROMPT")]
    (when-not (str/blank? v)
      v)))

;; ============================================================
;; Output helpers
;; ============================================================

(defn- print-banner [model templates loaded-skills ctx]
  (println)
  (println "╔══════════════════════════════════════╗")
  (println "║  ψ  Psi Agent Session                ║")
  (println "╚══════════════════════════════════════╝")
  (println (str "  Model   : " (:name model)))
  (println (str "  Tools   : " (str/join ", " (map :name tools/all-tool-schemas))))
  (when (seq templates)
    (println (str "  Prompts : " (count templates) " loaded"))
    (doseq [t templates]
      (println (str "    /" (:name t) " — " (:description t)))))
  (when (seq loaded-skills)
    (let [visible (skills/visible-skills loaded-skills)
          hidden  (skills/hidden-skills loaded-skills)]
      (println (str "  Skills  : " (count visible) " available"
                    (when (seq hidden) (str ", " (count hidden) " hidden"))))))
  (let [ext-details (ext/extension-details-in (:extension-registry ctx))]
    (when (seq ext-details)
      (println (str "  Exts    : " (count ext-details) " loaded"))
      (doseq [d ext-details]
        (let [parts (cond-> []
                      (pos? (:tool-count d))    (conj (str (:tool-count d) " tools"))
                      (pos? (:command-count d)) (conj (str (:command-count d) " cmds"))
                      (pos? (:handler-count d)) (conj (str (:handler-count d) " handlers")))
              suffix (when (seq parts) (str " (" (str/join ", " parts) ")"))]
          (println (str "    " (.getName (java.io.File. ^String (:path d))) suffix))))))
  (println "  /help for commands, /quit to exit")
  (println))

;; print-status, print-history, print-help, print-prompts, print-skills
;; moved to psi.agent-session.commands as format-* functions

;; ============================================================
;; Response printing — streams to stdout as tokens arrive
;; ============================================================

(defn- print-assistant-message
  "Print the text content of an assistant message map.
  If the message carries an error, print it clearly instead of (or after) any partial text."
  [msg]
  (let [text   (some->> (message-text/content-text-parts (:content msg))
                        seq
                        (str/join ""))
        errors (message-text/content-error-parts (:content msg))]
    (when (seq text)
      (println (str "\nψ: " text "\n")))
    (doseq [err errors]
      (println (str "\n[Provider error: " err "]\n")))
    (when (and (empty? (or text "")) (empty? errors))
      (println "\nψ: (no response)\n"))))

(defn- print-initial-transcript!
  [rehydrate]
  (doseq [m (:messages rehydrate)]
    (case (:role m)
      :user (println (str "刀(startup): " (:text m)))
      :assistant (println (str "ψ(startup): " (:text m)))
      nil)))

(defn- message->display-text
  "Extract display text from an agent-core message map.
   Includes :text blocks and :error blocks."
  [msg]
  (or (message-text/content-display-text (:content msg))
      ""))

(defn- ->kw
  [x]
  (cond
    (keyword? x) x
    (string? x)  (keyword x)
    :else        nil))

(defn- assistant-tool-call-blocks
  [content]
  (letfn [(tool-call? [block]
            (= :tool-call (->kw (or (:type block)
                                    (get block "type")
                                    (:kind block)
                                    (get block "kind")))))]
    (cond
      (sequential? content)
      (->> content
           (filter map?)
           (filter tool-call?)
           (mapv (fn [block]
                   {:id        (or (:id block) (get block "id"))
                    :name      (or (:name block) (get block "name"))
                    :arguments (or (:arguments block)
                                   (get block "arguments")
                                   (some-> (or (:input block)
                                               (get block "input"))
                                           pr-str)
                                   "")})))

      (and (map? content)
           (= :structured (->kw (or (:kind content) (get content "kind")))))
      (assistant-tool-call-blocks (or (:blocks content) (get content "blocks")))

      :else
      [])))

(defn- tool-result->display-text
  "Best-effort display text for a toolResult message content vector."
  [msg]
  (let [text (message->display-text msg)]
    (when-not (str/blank? text)
      text)))

(defn- agent-messages->tui-resume-state
  "Convert agent-core history to TUI resume state.
   Returns {:messages [...], :tool-calls {...}, :tool-order [...]}.
   Tool rows are reconstructed by correlating assistant tool-call blocks
   with toolResult messages by tool-call-id."
  [messages]
  (reduce
   (fn [acc msg]
     (case (:role msg)
       "user"
       (let [text (message->display-text msg)]
         (update acc :messages conj {:role :user
                                     :text (if (str/blank? text) "[user]" text)}))

       "assistant"
       (let [text        (message->display-text msg)
             tool-blocks (assistant-tool-call-blocks (:content msg))
             acc'        (if (str/blank? text)
                           acc
                           (update acc :messages conj {:role :assistant :text text}))]
         (reduce
          (fn [a block]
            (let [id (:id block)
                  tc {:name      (:name block)
                      :args      (or (:arguments block) "")
                      :status    :pending
                      :result    nil
                      :is-error  false
                      :expanded? false}]
              (-> a
                  (update :tool-calls #(if (contains? % id) % (assoc % id tc)))
                  (update :tool-order #(if (some #{id} %) % (conj % id))))))
          acc'
          tool-blocks))

       "toolResult"
       (let [id      (:tool-call-id msg)
             text    (tool-result->display-text msg)
             content (:content msg)
             details (:details msg)
             err?    (boolean (:is-error msg))
             fallback {:name      (:tool-name msg)
                       :args      ""
                       :status    (if err? :error :success)
                       :result    text
                       :content   content
                       :details   details
                       :is-error  err?
                       :expanded? false}]
         (-> acc
             (update :tool-calls
                     (fn [m]
                       (if-let [tc (get m id)]
                         (assoc m id
                                (-> tc
                                    (assoc :status (if err? :error :success)
                                           :content content
                                           :details details
                                           :is-error err?)
                                    (cond-> text (assoc :result text))))
                         (assoc m id fallback))))
             (update :tool-order #(if (some #{id} %) % (conj % id)))))

       ;; unknown roles — ignore
       acc))
   {:messages [] :tool-calls {} :tool-order []}
   messages))

;; select-login-provider moved to psi.agent-session.commands

;; ============================================================
;; Core: one prompt → response cycle
;; ============================================================

(defn- print-expansion-banner!
  [expansion]
  (when expansion
    (case (:kind expansion)
      :skill
      (println (str "[Skill: " (:name expansion) "]\n"))

      :template
      (println (str "[Template: " (:name expansion) "]\n"))

      nil)))

(defn- run-prompt!
  "Send `text` to the agent and block until done, printing the response.
   Uses shared runtime prompt preparation for parity with RPC/TUI."
  [ctx ai-ctx ai-model text]
  (let [{:keys [user-message expansion]} (runtime/prepare-user-message-in! ctx text)
        _       (print-expansion-banner! expansion)
        api-key (runtime/resolve-api-key-in ctx ai-model)
        result  (runtime/run-agent-loop-in! ctx ai-ctx ai-model [user-message]
                                            {:api-key api-key
                                             :sync-on-git-head-change? true})]
    (print-assistant-message result)))

(defn- graph-capabilities-in
  "Best-effort read of current capability summaries from the live graph."
  [ctx]
  (try
    (or (:psi.graph/capabilities (session/query-in ctx [:psi.graph/capabilities]))
        [])
    (catch Exception e
      (timbre/warn e "Unable to query :psi.graph/capabilities for system prompt enrichment")
      [])))

(defn- startup-rehydrate-from-current-session!
  "Run startup prompts in the current session and return rehydrate payload.

   Returns map:
   {:agent-messages [...]
    :messages [...]
    :tool-calls {...}
    :tool-order [...]}"
  [ctx ai-ctx ai-model]
  (try
    (runtime/run-startup-prompts-in! ctx {:ai-ctx ai-ctx :ai-model ai-model :spawn-mode :new-root})
    (catch Throwable t
      (timbre/warn t "Startup prompts failed; continuing with empty startup transcript")))
  (let [agent-messages (:messages (agent/get-data-in (ss/agent-ctx-in ctx)))
        tui-state      (agent-messages->tui-resume-state agent-messages)]
    (assoc tui-state :agent-messages agent-messages)))

(defn- start-new-session-with-startup!
  "Create a fresh session branch and run configured startup prompts.

   Returns map:
   {:session-id     string
    :agent-messages [...]
    :messages [...]
    :tool-calls {...}
    :tool-order [...]}"
  [ctx ai-ctx ai-model]
  (let [sd  (session/new-session-in! ctx)
        sid (:session-id sd)
        ctx (assoc ctx :target-session-id sid)]
    (assoc (startup-rehydrate-from-current-session! ctx ai-ctx ai-model)
           :session-id sid)))

(defn- create-runtime-session-context
  "Create a live session context with runtime/session state prepared, but not bootstrapped.

   Options:
   - :event-queue optional TUI/RPC event queue
   - :session-config optional session config overrides (merged with defaults)
   - :cwd optional cwd override (primarily for tests)
   - :ui-type runtime UI type hint (:console | :tui | :emacs)"
  [ai-model {:keys [event-queue session-config cwd ui-type]}]
  (let [oauth-ctx                (oauth/create-context)
        cwd                      (or cwd (System/getProperty "user.dir"))
        cfg                      (config-res/resolve-config cwd)
        effective-model          (if-let [{:keys [provider id]} (config-res/resolved-model cfg)]
                                   (or (resolve-model-by-provider+id provider id) ai-model)
                                   ai-model)
        effective-thinking-level (session-data/clamp-thinking-level
                                  (config-res/resolved-thinking-level cfg)
                                  {:reasoning (:supports-reasoning effective-model)})
        effective-prompt-mode    (config-res/resolved-prompt-mode cfg)
        nucleus-prelude-override (config-res/resolved-nucleus-prelude-override cfg)
        ctx                      (session/create-context
                                  {:initial-session {:model {:provider  (name (:provider effective-model))
                                                             :id        (:id effective-model)
                                                             :reasoning (:supports-reasoning effective-model)}
                                                     :thinking-level effective-thinking-level
                                                     :prompt-mode              effective-prompt-mode
                                                     :nucleus-prelude-override nucleus-prelude-override
                                                     :ui-type                  (or ui-type :console)}
                                   :config session-config
                                   :event-queue event-queue
                                   :oauth-ctx oauth-ctx
                                   :nrepl-runtime-atom nrepl-runtime
                                   :ui-type ui-type
                                   :mutations mutations/all-mutations})
        _                        (when-not (sa/recursion-state-in ctx)
                                   (sa/set-recursion-state-in! ctx
                                                               (recursion/initial-state)))
        recursion-ctx            (recursion/create-hosted-context ctx (ss/state-path :recursion))
        ctx                      (assoc ctx :recursion-ctx recursion-ctx)]
    (service/initialize! ctx)
    (let [sd  (session/new-session-in! ctx)
          ctx (assoc ctx :target-session-id (:session-id sd))]
      {:ctx       ctx
       :oauth-ctx oauth-ctx
       :cwd       cwd})))

(defn- bootstrap-runtime-session!
  "Bootstrap a live session context shared by CLI/TUI/RPC modes.

   Calling forms:
   - (bootstrap-runtime-session! ai-model opts)
       creates a fresh runtime session context, bootstraps it, and returns result map
   - (bootstrap-runtime-session! ctx ai-model)
       bootstraps an existing ctx with default opts
   - (bootstrap-runtime-session! ctx ai-model opts)
       bootstraps an existing ctx with explicit opts

   Options:
   - :memory-runtime-opts optional memory/runtime sync opts
   - :cwd optional cwd override (primarily for tests)"
  ([x y]
   (if (:state* x)
     (bootstrap-runtime-session! x y {})
     (let [ai-model x
           opts     y
           {:keys [ctx oauth-ctx cwd]} (create-runtime-session-context ai-model {:cwd (:cwd opts)
                                                                                 :session-config (:session-config opts)
                                                                                 :ui-type (or (:ui-type opts) :console)})
           result (bootstrap-runtime-session! ctx ai-model opts)]
       (assoc result :ctx ctx :oauth-ctx oauth-ctx :cwd cwd))))
  ([ctx ai-model {:keys [memory-runtime-opts cwd]}]
   (let [templates        (pt/discover-templates)
         {:keys [skills diagnostics]} (skills/discover-skills)
         _                (doseq [d diagnostics]
                            (timbre/warn "Skill" (:type d) ":" (:message d) (:path d)))
         cwd              (or cwd (System/getProperty "user.dir"))
         ctx-files        (sys-prompt/discover-context-files cwd)
         sd               (ss/get-session-data-in ctx)
         prompt-mode      (or (:prompt-mode sd) :lambda)
         prelude-override (:nucleus-prelude-override sd)
         base-prompt-opts {:cwd                      cwd
                           :session-instant          (:started-at ctx)
                           :prompt-mode              prompt-mode
                           :nucleus-prelude-override prelude-override
                           :context-files            ctx-files
                           :skills                   skills}
         base-prompt      (sys-prompt/build-system-prompt base-prompt-opts)
         developer-prompt (developer-prompt-from-env)
         _                (dispatch/dispatch! ctx :session/set-system-prompt {:prompt base-prompt} {:origin :core})
         ext-paths        (ext/discover-extension-paths [] cwd)
         app-query-tool   (tools/make-app-query-tool (fn [q] (session/query-in ctx q)))
         summary          (session-bootstrap/bootstrap-in!
                           ctx {:register-global-query? false
                                :base-tools             (conj (vec tools/all-tools) app-query-tool)
                                :system-prompt          base-prompt
                                :developer-prompt       developer-prompt
                                :developer-prompt-source (if developer-prompt :env :fallback)
                                :templates              templates
                                :skills                 skills
                                :extension-paths        ext-paths})
         _                (bootstrap/register-all-domains!)
         graph-caps       (graph-capabilities-in ctx)
         build-opts       (assoc base-prompt-opts :graph-capabilities graph-caps)
         system-prompt    (sys-prompt/build-system-prompt build-opts)
         _                (dispatch/dispatch! ctx :session/set-system-prompt {:prompt system-prompt} {:origin :core})
         _                (dispatch/dispatch! ctx :session/set-system-prompt-build-opts
                                              {:opts (dissoc build-opts :prompt-mode)}
                                              {:origin :core})
         _                (memory-runtime/sync-memory-layer! (merge {:cwd cwd}
                                                                    (or memory-runtime-opts {})))
         startup-rehydrate (startup-rehydrate-from-current-session! ctx nil ai-model)]
     (doseq [{:keys [path error]} (:extension-errors summary)]
       (timbre/warn "Extension error:" path error))
     (when (pos? (:extension-loaded-count summary))
       (timbre/debug "Extensions loaded:" (:extension-loaded-count summary)))
    ;; Register extension run-fn so extension-initiated prompts (e.g. PSL)
    ;; actually invoke the LLM instead of orphaning a user message in agent-core.
     (runtime/register-extension-run-fn-in! ctx nil ai-model)
     {:ctx               ctx
      :templates         templates
      :skills            skills
      :summary           summary
      :startup-rehydrate startup-rehydrate
      :cwd               cwd})))

;; ============================================================
;; Main prompt loop
;; ============================================================

(defn run-session
  "Create a session and enter the interactive prompt loop.
  Returns when the user exits."
  ([model-key]
   (run-session model-key {} {}))
  ([model-key memory-runtime-opts]
   (run-session model-key memory-runtime-opts {}))
  ([model-key memory-runtime-opts session-config]
   (let [ai-model  (resolve-model model-key)
         ai-ctx    nil
         {:keys [ctx oauth-ctx]}
         (create-runtime-session-context ai-model {:session-config session-config
                                                   :ui-type :console})
         {:keys [templates skills startup-rehydrate]}
         (bootstrap-runtime-session! ctx ai-model {:memory-runtime-opts memory-runtime-opts})]
     (let [cli-focus* (atom (:target-session-id ctx))
           focus-ctx  (fn [] (assoc ctx :target-session-id @cli-focus*))]
       (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                              :oauth-ctx oauth-ctx
                              :nrepl-runtime-atom nrepl-runtime})
       (print-banner ai-model templates skills ctx)
       (print-initial-transcript! startup-rehydrate)
       (let [cmd-opts {:oauth-ctx oauth-ctx
                       :ai-model ai-model
                       :supports-session-tree? false
                       :on-new-session! (fn []
                                          (let [result (start-new-session-with-startup! (focus-ctx) ai-ctx ai-model)]
                                            (reset! cli-focus* (:session-id result))
                                            result))}]
         (loop []
           (print "刀: ")
           (flush)
           (when-let [line (try (read-line) (catch Exception _ nil))]
             (let [trimmed (str/trim line)
                   fctx    (focus-ctx)
                   result  (when-not (str/blank? trimmed)
                             (commands/dispatch fctx trimmed cmd-opts))]
               (when result
                 (runtime/journal-user-message-in! fctx trimmed))
               (cond
                 (nil? result)
                 (if (str/blank? trimmed)
                   (recur)
                   (do
                     (try
                       (run-prompt! (focus-ctx) ai-ctx ai-model trimmed)
                       (catch Exception e
                         (println (str "\n[Error: " (ex-message e) "]\n"))))
                     (recur)))

                 (= :quit (:type result))
                 (println "\nψ: Goodbye.\n")

                 (= :resume (:type result))
                 (do
                   (println "\n  /resume is only available in TUI mode (--tui).\n")
                   (recur))

                 (#{:text :new-session :logout} (:type result))
                 (do
                   (when (= :new-session (:type result))
                     (print-initial-transcript! (:rehydrate result)))
                   (println (str "\n" (:message result) "\n"))
                   (recur))

                 (= :login-start (:type result))
                 (do
                   (println "\n── OAuth Login ────────────────────────")
                   (let [{:keys [provider url login-state uses-callback-server]} result]
                     (println (str "  Open: " url "\n"))
                     (if uses-callback-server
                       (do
                         (println "  Waiting for browser callback…")
                         (try
                           (oauth/complete-login! oauth-ctx (:id provider) nil login-state)
                           (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
                           (catch Exception e
                             (println (str "\n  ✗ Login failed: " (ex-message e) "\n")))))
                       (do
                         (print "  Paste authorization code: ")
                         (flush)
                         (when-let [code (try (read-line) (catch Exception _ nil))]
                           (try
                             (oauth/complete-login! oauth-ctx (:id provider) (str/trim code) login-state)
                             (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
                             (catch Exception e
                               (println (str "\n  ✗ Login failed: " (ex-message e) "\n"))))))))
                   (recur))

                 (= :login-error (:type result))
                 (do
                   (println (str "\n  " (:message result) "\n"))
                   (recur))

                 (= :extension-cmd (:type result))
                 (do
                   (try
                     (when-let [handler (:handler result)]
                       (let [captured (with-out-str (handler (:args result)))]
                         (when-not (str/blank? captured)
                           (println (str "\n" (str/trimr captured) "\n")))))
                     (catch Exception e
                       (println (str "\n[Command error: " (ex-message e) "]\n"))))
                   (recur))

                 :else
                 (do
                   (println (str "\n" result "\n"))
                   (recur)))))))))))

;; TUI session (charm.clj Elm Architecture)
;; ============================================================

(defn new-session-with-startup-in!
  "Public helper for runtimes/tests: create new session and run startup prompts.
   Returns rehydrate payload map with :agent-messages + TUI projection."
  [ctx ai-ctx ai-model]
  (start-new-session-with-startup! ctx ai-ctx ai-model))

(defn run-tui-session-with-interface!
  "Create a session and run it with a provided TUI interface function.
  This breaks the circular dependency by accepting the TUI interface via dependency injection."
  ([tui-start-fn! args-or-model]
   (if (sequential? args-or-model)
     (let [model-key            (model-key-from-args args-or-model)
           memory-runtime-opts  (memory-runtime-opts-from-args args-or-model)
           session-runtime-opts (session-runtime-config-from-args args-or-model)]
       (run-tui-session-with-interface! tui-start-fn! model-key memory-runtime-opts session-runtime-opts))
     (run-tui-session-with-interface! tui-start-fn! args-or-model {} {})))
  ([tui-start-fn! model-key memory-runtime-opts session-config]
   (let [ai-model    (resolve-model model-key)
         ai-ctx      nil
         event-queue (java.util.concurrent.LinkedBlockingQueue.)
         {:keys [ctx oauth-ctx cwd]}
         (create-runtime-session-context ai-model {:event-queue event-queue
                                                   :session-config session-config
                                                   :ui-type :tui})
         {:keys [startup-rehydrate]}
         (bootstrap-runtime-session! ctx ai-model {:memory-runtime-opts memory-runtime-opts
                                                   :cwd cwd})

         ;; TUI-local focus atom — all closures read this to scope ctx
         tui-focus* (atom (:target-session-id ctx))
         focus-ctx  (fn [] (assoc ctx :target-session-id @tui-focus*))

         ;; Expose state for nREPL introspection
         _         (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                                          :oauth-ctx oauth-ctx
                                          :nrepl-runtime-atom nrepl-runtime
                                          :tui-focus* tui-focus*})

         ;; Helper: put an immediate assistant message on the TUI queue.
         reply!    (fn [^java.util.concurrent.LinkedBlockingQueue queue text]
                     (.put queue {:kind :done
                                  :result {:role    "assistant"
                                           :content [{:type :text :text text}]}}))

         ;; Resume callback used by the TUI /resume selector.
         ;; Returns TUI resume state maps to display immediately after loading.
         resume-fn! (fn [session-path]
                      (try
                        (let [sd   (session/resume-session-in! (focus-ctx) session-path)
                              _    (reset! tui-focus* (:session-id sd))
                              msgs (:messages (agent/get-data-in (ss/agent-ctx-in (focus-ctx))))]
                          (agent-messages->tui-resume-state msgs))
                        (catch Exception e
                          (timbre/error e "Resume failed:" session-path)
                          {:messages [{:role :assistant
                                       :text (str "✗ Resume failed: " (ex-message e))}]
                           :tool-calls {}
                           :tool-order []})))

         switch-session-fn! (fn [session-id]
                              (try
                                (let [sd   (session/ensure-session-loaded-in! (focus-ctx) session-id)
                                      _    (reset! tui-focus* (:session-id sd))
                                      msgs (:messages (agent/get-data-in (ss/agent-ctx-in (focus-ctx))))]
                                  (agent-messages->tui-resume-state msgs))
                                (catch Exception e
                                  (timbre/error e "Session switch failed:" session-id)
                                  {:messages [{:role :assistant
                                               :text (str "✗ Session switch failed: " (ex-message e))}]
                                   :tool-calls {}
                                   :tool-order []})))

         cmd-opts  {:oauth-ctx oauth-ctx
                    :ai-model ai-model
                    :supports-session-tree? true
                    :on-new-session! (fn []
                                       (let [result (start-new-session-with-startup! (focus-ctx) ai-ctx ai-model)]
                                         (reset! tui-focus* (:session-id result))
                                         result))}

         ;; dispatch-fn — called synchronously by the TUI on submit.
         ;; Returns a command result map, or nil if not a command.
         ;; When a login is pending (waiting for auth code), returns nil
         ;; so the input falls through to run-agent-fn! which handles it.
         dispatch-fn (fn [text]
                       (if (:pending-login @session-state)
                         nil  ;; fall through to run-agent-fn! for login code
                         (let [fctx   (focus-ctx)
                               result (commands/dispatch fctx text cmd-opts)]
                           (when result
                             ;; Keep command inputs in the session journal for parity with RPC/CLI.
                             (runtime/journal-user-message-in! fctx text))
                           (when (= :login-start (:type result))
                             (if (:uses-callback-server result)
                               nil
                               (swap! session-state assoc :pending-login
                                      {:provider-id   (get-in result [:provider :id])
                                       :provider-name (get-in result [:provider :name])
                                       :login-state   (:login-state result)})))
                           result)))

         ;; Called by TUI Escape during active work.
         on-interrupt-fn! (fn [_state]
                            (session/abort-in! (focus-ctx))
                            {:queued-text (session/consume-queued-input-text-in! (focus-ctx))
                             :message "Interrupted active work."})

         ;; run-agent-fn! — called by the TUI for non-command input.
         ;; Handles pending-login step 2 and agent execution.
         run-agent-fn! (fn [text ^java.util.concurrent.LinkedBlockingQueue queue]
                         (let [trimmed (str/trim text)
                               pending (:pending-login @session-state)]
                           (cond
                             ;; Step 2: pending login — this input IS the auth code
                             pending
                             (future
                               (try
                                 (let [{:keys [provider-id provider-name login-state]} pending]
                                   (swap! session-state dissoc :pending-login)
                                   (oauth/complete-login! oauth-ctx provider-id trimmed login-state)
                                   (reply! queue (str "✓ Logged in to " provider-name)))
                                 (catch Exception e
                                   (swap! session-state dissoc :pending-login)
                                   (reply! queue (str "✗ Login failed: " (ex-message e))))))

                             ;; Everything else — send to agent
                             :else
                             (future
                               (try
                                 (let [fctx (focus-ctx)
                                       {:keys [user-message]} (runtime/prepare-user-message-in! fctx text)
                                       api-key  (runtime/resolve-api-key-in fctx ai-model)
                                       result   (runtime/run-agent-loop-in!
                                                 fctx ai-ctx ai-model [user-message]
                                                 {:api-key api-key
                                                  :progress-queue queue
                                                  :sync-on-git-head-change? true})]
                                   (.put queue {:kind :done :result result}))
                                 (catch Exception e
                                   (.put queue {:kind :error :message (ex-message e)})))))))]

     (tui-start-fn! (:name ai-model) run-agent-fn!
                    {:query-fn             (fn [q] (session/query-in (focus-ctx) q))
                     :ui-state*        (ss/atom-view-in ctx (ss/state-path :ui-state))
                     :dispatch-fn          dispatch-fn
                     :on-interrupt-fn!     on-interrupt-fn!
                     :on-queue-input-fn!   (fn [text _state]
                                             (let [fctx (focus-ctx)]
                                               (if (= :streaming (ss/sc-phase-in fctx))
                                                 (do
                                                   (session/steer-in! fctx text)
                                                   {:message "Queued steering message."})
                                                 (do
                                                   (session/follow-up-in! fctx text)
                                                   {:message "Queued follow-up message."}))))
                     :double-press-window-ms 500
                     :double-escape-action :none
                     :cwd                  cwd
                     :focus-session-id     @tui-focus*
                     :current-session-file (:session-file (ss/get-session-data-in (focus-ctx)))
                     :initial-messages     (vec (or (:messages startup-rehydrate) []))
                     :initial-tool-calls   (or (:tool-calls startup-rehydrate) {})
                     :initial-tool-order   (vec (or (:tool-order startup-rehydrate) []))
                     :resume-fn!           resume-fn!
                     :switch-session-fn!   switch-session-fn!
                     :event-queue          event-queue
                     :alt-screen           false}))))

;; ============================================================
;; -main
;; ============================================================

(defn- run-rpc-edn-session!
  "Run RPC EDN transport bound to a live AgentSession context.

   Uses the same session bootstrap path as console/TUI so RPC prompts have
   system prompt, tools, skills, templates, and extensions loaded.

   In rpc-edn mode, reserve stdout strictly for protocol frames and route
   incidental println/log output to stderr."
  ([model-key]
   (run-rpc-edn-session! model-key {} {} {}))
  ([model-key memory-runtime-opts]
   (run-rpc-edn-session! model-key memory-runtime-opts {} {}))
  ([model-key memory-runtime-opts session-config]
   (run-rpc-edn-session! model-key memory-runtime-opts session-config {}))
  ([model-key memory-runtime-opts session-config {:keys [rpc-trace-file]}]
   (let [protocol-out       *out*
         original-systemout System/out]
     (try
       ;; In rpc-edn mode, stdout is protocol-only. Force any direct System/out
       ;; writes (including background threads and library logging) onto stderr.
       (System/setOut (java.io.PrintStream. System/err true))
       (binding [*out* *err*]
         (let [ai-model      (resolve-model model-key)
               event-queue   (java.util.concurrent.LinkedBlockingQueue.)
               {:keys [ctx oauth-ctx cwd]}
               (create-runtime-session-context ai-model {:event-queue event-queue
                                                         :session-config session-config
                                                         :ui-type :emacs})
               _             (bootstrap-runtime-session! ctx ai-model {:memory-runtime-opts memory-runtime-opts
                                                                       :cwd cwd})
               trace-file*   (when-not (str/blank? rpc-trace-file)
                               rpc-trace-file)
               _             (dispatch/dispatch! ctx :session/set-rpc-trace {:enabled? (boolean trace-file*) :file trace-file*} {:origin :core})
               trace-lock    (Object.)
               trace-fn      (fn [{:keys [dir raw frame parse-error]}]
                               (try
                                 (let [cfg      (or (sa/rpc-trace-state-in ctx) {})
                                       enabled? (boolean (:enabled? cfg))
                                       path     (:file cfg)]
                                   (when (and enabled?
                                              (string? path)
                                              (not (str/blank? path)))
                                     (io/make-parents path)
                                     (let [entry (cond-> {:ts (str (java.time.Instant/now))
                                                          :dir dir
                                                          :raw raw}
                                                   (map? frame) (assoc :frame frame)
                                                   (and (string? parse-error)
                                                        (not (str/blank? parse-error)))
                                                   (assoc :parse-error parse-error))]
                                       (locking trace-lock
                                         (spit path (str (pr-str entry) "\n") :append true)))))
                                 (catch Throwable t
                                   (timbre/warn t "Failed to write rpc trace event"))))
               focus-atom    (atom (:target-session-id ctx))
               focus-ctx     (fn [] (assoc ctx :target-session-id @focus-atom))
               state         (atom {:handshake-server-info-fn (fn [] (assoc (rpc/session->handshake-server-info (focus-ctx))
                                                                            :ui-type :emacs))
                                    :handshake-context-updated-payload-fn (fn [] {:active-session-id @focus-atom
                                                                                  :sessions []})
                                    :focus-session-id* focus-atom
                                    :subscribed-topics #{}
                                    :rpc-ai-model ai-model
                                    :on-new-session! (fn []
                                                       (let [result (start-new-session-with-startup! (focus-ctx) nil ai-model)]
                                                         (reset! focus-atom (:session-id result))
                                                         result))})
               request-handler (rpc/make-session-request-handler ctx)]
           (reset! session-state {:ctx ctx
                                  :ai-model ai-model
                                  :oauth-ctx oauth-ctx
                                  :nrepl-runtime-atom nrepl-runtime})
           (rpc/run-stdio-loop! {:request-handler request-handler
                                 :state state
                                 :out protocol-out
                                 :trace-fn trace-fn})))
       (finally
         (System/setOut original-systemout))))))

;; ============================================================
;; Interface-specific entry points (for psi-app launcher)
;; ============================================================

(defn run-console-session!
  "Run console session with command line args"
  [args]
  (let [model-key            (model-key-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        nrepl-port           (nrepl-port-from-args args)]
    (when nrepl-port
      (start-nrepl! nrepl-port))
    (try
      (run-session model-key memory-runtime-opts session-runtime-opts)
      (finally
        (when nrepl-port (stop-nrepl! nil))))))

(defn run-rpc-interface!
  "Run RPC EDN session with command line args"
  [args]
  (let [model-key            (model-key-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        rpc-trace-file       (rpc-trace-file-from-args args)
        nrepl-port           (nrepl-port-from-args args)]
    (when nrepl-port
      ;; Keep rpc stdout protocol-clean
      (binding [*out* *err*] (start-nrepl! nrepl-port)))
    (try
      (run-rpc-edn-session! model-key memory-runtime-opts session-runtime-opts
                            {:rpc-trace-file rpc-trace-file})
      (finally
        (when nrepl-port (stop-nrepl! nil))))))

(defn -main
  "Entry point.

   Supported flags:
   --model <key>
   --log-level <LEVEL>
   --tui
   --rpc-edn
   --rpc-trace-file <path>
   --nrepl [port]
   --memory-store <in-memory>
   --memory-store-fallback <on|off>
   --memory-history-limit <n>
   --memory-retention-snapshots <n>
   --memory-retention-deltas <n>
   --llm-idle-timeout-ms <n>

   Env:
   PSI_LLM_IDLE_TIMEOUT_MS=<n>"
  [& args]
  (set-log-level! (log-level-from-args args))
  (let [model-key            (model-key-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        rpc-trace-file       (rpc-trace-file-from-args args)
        tui?                 (some #(= "--tui" %) args)
        rpc-edn?             (some #(= "--rpc-edn" %) args)
        nrepl-port           (nrepl-port-from-args args)
        nrepl-srv            (when nrepl-port
                               (if rpc-edn?
                                 ;; Keep rpc stdout protocol-clean.
                                 (binding [*out* *err*]
                                   (start-nrepl! nrepl-port))
                                 (start-nrepl! nrepl-port)))]
    (try
      (cond
        rpc-edn?
        (run-rpc-edn-session! model-key
                              memory-runtime-opts
                              session-runtime-opts
                              {:rpc-trace-file rpc-trace-file})

        tui?
        ;; TUI session now handled by psi-app launcher
        (throw (ex-info "TUI session should be launched via psi-app, not agent-session directly" {}))

        :else
        (run-session model-key memory-runtime-opts session-runtime-opts))
      (finally
        (stop-nrepl! nrepl-srv)))))
