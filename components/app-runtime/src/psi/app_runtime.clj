(ns psi.app-runtime
  "Agent session application runtime for console and TUI execution.

   This namespace owns live session startup/state for interactive runtimes.
   RPC transport concerns live in `psi.rpc`.

   Usage:
     clojure -M:run
     clojure -M:run --model sonnet-4.6
     clojure -M:run --log-level DEBUG
     clojure -M:run --tui
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
     @psi.app-runtime/session-state  — live session context
     (require '[psi.agent-session.core :as s])
     (s/query-in (:ctx @psi.app-runtime/session-state)
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

   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.config-resolution :as config-res]
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

(defn- default-session-id-in
  [ctx]
  (some-> (ss/list-context-sessions-in ctx) first :session-id))

(defn- active-session-id-in-session-state
  []
  (let [{:keys [ctx focus-session-id* tui-focus*]} @session-state]
    (or (some-> focus-session-id* deref)
        (some-> tui-focus* deref)
        (default-session-id-in ctx))))

;; ============================================================
;; nREPL server (started conditionally via --nrepl)
;; ============================================================

(defn start-nrepl!
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
      (when-let [session-id (active-session-id-in-session-state)]
        (psi.agent-session.state-accessors/set-nrepl-runtime-in!
         ctx
         session-id
         {:host host
          :port (:port server)
          :endpoint (str host ":" (:port server))})))
    (spit port-file (str (:port server)))
    (.deleteOnExit port-file)
    (.println System/err (str "  nREPL : " host ":" (:port server)
                              " (connect with your editor)"))
    server))

(defn stop-nrepl! [server]
  (when server
    (let [stop-server (requiring-resolve 'nrepl.server/stop-server)
          port-file   (java.io.File. ".nrepl-port")]
      (reset! nrepl-runtime nil)
      (when-let [ctx (:ctx @session-state)]
        (when-let [session-id (active-session-id-in-session-state)]
          (psi.agent-session.state-accessors/set-nrepl-runtime-in! ctx session-id nil)))
      (stop-server server)
      (when (.exists port-file)
        (when (= (str/trim (slurp port-file)) (str (:port server)))
          (.delete port-file))))))

(defn- developer-prompt-from-env
  "Return optional developer prompt text from PSI_DEVELOPER_PROMPT.
   Blank values are treated as nil."
  []
  (let [v (System/getenv "PSI_DEVELOPER_PROMPT")]
    (when-not (str/blank? v)
      v)))

(defn resolve-model
  "Return an ai.schemas.Model map for `model-key` keyword."
  [model-key]
  (or (get models/all-models model-key)
      (throw (ex-info (str "Unknown model: " model-key
                           "
Available: " (str/join ", " (map name (keys models/all-models))))
                      {:model-key model-key}))))

(defn- resolve-model-by-provider+id
  "Find a runtime model map by provider string + model-id string."
  [provider model-id]
  (let [provider* (some-> provider keyword)]
    (some (fn [[_ model]]
            (when (and (= provider* (:provider model))
                       (= model-id (:id model)))
              model))
          models/all-models)))

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
  "Send `text` to `session-id` and block until done, printing the response.
   Uses shared runtime prompt preparation for parity with RPC/TUI."
  [ctx session-id ai-ctx ai-model text]
  (let [{:keys [user-message expansion]} (runtime/prepare-user-message-in! ctx session-id text nil)
        _       (print-expansion-banner! expansion)
        api-key (runtime/resolve-api-key-in ctx session-id ai-model)
        result  (runtime/run-agent-loop-in! ctx session-id ai-ctx ai-model [user-message]
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
  "Run startup prompts in `session-id` and return rehydrate payload.

   Returns map:
   {:agent-messages [...]
    :messages [...]
    :tool-calls {...}
    :tool-order [...]}"
  [ctx session-id ai-ctx ai-model]
  (try
    (runtime/run-startup-prompts-in! ctx session-id {:ai-ctx ai-ctx :ai-model ai-model :spawn-mode :new-root})
    (catch Throwable t
      (timbre/warn t "Startup prompts failed; continuing with empty startup transcript")))
  (let [agent-messages (:messages (agent/get-data-in (ss/agent-ctx-in ctx session-id)))
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
  [ctx source-session-id ai-ctx ai-model]
  (let [sd  (session/new-session-in! ctx source-session-id {})
        sid (:session-id sd)]
    (assoc (startup-rehydrate-from-current-session! ctx sid ai-ctx ai-model)
           :session-id sid)))

(defn create-runtime-session-context
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
        ctx                    (session/create-context
                                  {:session-defaults {:model {:provider  (name (:provider effective-model))
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
        recursion-ctx            (recursion/create-hosted-context ctx (ss/state-path :recursion))
        ctx                      (assoc ctx :recursion-ctx recursion-ctx)
        _                        (when-not (sa/recursion-state-in ctx)
                                   (sa/set-recursion-state-in! ctx nil (recursion/initial-state)))
        sd                       (session/new-session-in! ctx nil {})
        session-id               (:session-id sd)]
    {:ctx        ctx
     :oauth-ctx  oauth-ctx
     :cwd        cwd
     :session-id session-id}))

(defn bootstrap-runtime-session!
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
     (bootstrap-runtime-session! x nil y {})
     (let [ai-model x
           opts     y
           {:keys [ctx oauth-ctx cwd session-id]} (create-runtime-session-context ai-model {:cwd (:cwd opts)
                                                                                            :session-config (:session-config opts)
                                                                                            :ui-type (or (:ui-type opts) :console)})
           result (bootstrap-runtime-session! ctx session-id ai-model opts)]
       (assoc result :ctx ctx :oauth-ctx oauth-ctx :cwd cwd :session-id session-id))))
  ([ctx session-id ai-model {:keys [memory-runtime-opts cwd]}]
   (let [templates        (pt/discover-templates)
         {:keys [skills diagnostics]} (skills/discover-skills)
         _                (doseq [d diagnostics]
                            (timbre/warn "Skill" (:type d) ":" (:message d) (:path d)))
         cwd              (or cwd (System/getProperty "user.dir"))
         ctx-files        (sys-prompt/discover-context-files cwd)
         sd               (ss/get-session-data-in ctx session-id)
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
         _                (dispatch/dispatch! ctx :session/set-system-prompt {:session-id session-id :prompt base-prompt} {:origin :core})
         ext-paths        (ext/discover-extension-paths [] cwd)
         app-query-tool   (tools/make-app-query-tool (fn [q] (session/query-in ctx session-id q)))
         summary          (session-bootstrap/bootstrap-in!
                           ctx session-id
                           {:register-global-query? false
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
         _                (dispatch/dispatch! ctx :session/set-system-prompt {:session-id session-id :prompt system-prompt} {:origin :core})
         _                (dispatch/dispatch! ctx :session/set-system-prompt-build-opts
                                              {:session-id session-id :opts (dissoc build-opts :prompt-mode)}
                                              {:origin :core})
         _                (memory-runtime/sync-memory-layer! (merge {:cwd cwd}
                                                                    (or memory-runtime-opts {})))
         startup-rehydrate (startup-rehydrate-from-current-session! ctx session-id nil ai-model)]
     (doseq [{:keys [path error]} (:extension-errors summary)]
       (timbre/warn "Extension error:" path error))
     (when (pos? (:extension-loaded-count summary))
       (timbre/debug "Extensions loaded:" (:extension-loaded-count summary)))
    ;; Register extension run-fn so extension-initiated prompts (e.g. PSL)
    ;; actually invoke the LLM instead of orphaning a user message in agent-core.
     (runtime/register-extension-run-fn-in! ctx session-id nil ai-model)
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
   (let [ai-model   (resolve-model model-key)
         ai-ctx     nil
         {:keys [ctx oauth-ctx session-id]}
         (create-runtime-session-context ai-model {:session-config session-config
                                                   :ui-type :console})
         {:keys [templates skills startup-rehydrate]}
         (bootstrap-runtime-session! ctx session-id ai-model {:memory-runtime-opts memory-runtime-opts})
         cli-focus* (atom session-id)]
     (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                            :oauth-ctx oauth-ctx
                            :nrepl-runtime-atom nrepl-runtime})
     (print-banner ai-model templates skills ctx)
     (print-initial-transcript! startup-rehydrate)
     (let [cmd-opts {:oauth-ctx oauth-ctx
                     :ai-model ai-model
                     :supports-session-tree? false
                     :on-new-session! (fn [_source-session-id]
                                        (let [source-session-id @cli-focus*
                                              result             (start-new-session-with-startup! ctx source-session-id ai-ctx ai-model)]
                                          (reset! cli-focus* (:session-id result))
                                          result))}]
       (loop []
         (print "刀: ")
         (flush)
         (when-let [line (try (read-line) (catch Exception _ nil))]
           (let [trimmed (str/trim line)
                 sid     @cli-focus*
                 result  (when-not (str/blank? trimmed)
                           (commands/dispatch-in ctx sid trimmed cmd-opts))]
             (when result
               (runtime/journal-user-message-in! ctx sid trimmed nil))
             (cond
               (nil? result)
               (if (str/blank? trimmed)
                 (recur)
                 (do
                   (try
                     (run-prompt! ctx sid ai-ctx ai-model trimmed)
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
                 (recur))))))))))

;; TUI session (charm.clj Elm Architecture)
;; ============================================================

(defn new-session-with-startup-in!
  "Public helper for runtimes/tests: create new session and run startup prompts.
   Returns rehydrate payload map with :agent-messages + TUI projection."
  [ctx source-session-id ai-ctx ai-model]
  (start-new-session-with-startup! ctx source-session-id ai-ctx ai-model))

(defn start-tui-runtime!
  "Create a session and run it with a provided TUI interface function.
   The caller supplies resolved runtime config; this namespace stays CLI-free."
  ([tui-start-fn! model-key]
   (start-tui-runtime! tui-start-fn! model-key {} {}))
  ([tui-start-fn! model-key memory-runtime-opts]
   (start-tui-runtime! tui-start-fn! model-key memory-runtime-opts {}))
  ([tui-start-fn! model-key memory-runtime-opts session-config]
   (let [ai-model    (resolve-model model-key)
         ai-ctx      nil
         event-queue (java.util.concurrent.LinkedBlockingQueue.)
         {:keys [ctx oauth-ctx cwd session-id]}
         (create-runtime-session-context ai-model {:event-queue event-queue
                                                   :session-config session-config
                                                   :ui-type :tui})
         {:keys [startup-rehydrate]}
         (bootstrap-runtime-session! ctx session-id ai-model {:memory-runtime-opts memory-runtime-opts
                                                              :cwd cwd})

         ;; TUI-local focus atom — tracks active session-id
         tui-focus* (atom session-id)

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
                        (let [current-sid @tui-focus*
                              sd          (session/resume-session-in! ctx current-sid session-path)
                              sid         (:session-id sd)
                              _           (reset! tui-focus* sid)
                              msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                          (agent-messages->tui-resume-state msgs))
                        (catch Exception e
                          (timbre/error e "Resume failed:" session-path)
                          {:messages [{:role :assistant
                                       :text (str "✗ Resume failed: " (ex-message e))}]
                           :tool-calls {}
                           :tool-order []})))

         switch-session-fn! (fn [session-id]
                              (try
                                (let [source-session-id @tui-focus*
                                      sd                 (session/ensure-session-loaded-in! ctx source-session-id session-id)
                                      sid                (:session-id sd)
                                      _                  (reset! tui-focus* sid)
                                      msgs               (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
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
                    :on-new-session! (fn [_source-session-id]
                                       (let [source-session-id @tui-focus*
                                             result             (start-new-session-with-startup! ctx source-session-id ai-ctx ai-model)]
                                         (reset! tui-focus* (:session-id result))
                                         result))}

         ;; dispatch-fn — called synchronously by the TUI on submit.
         ;; Returns a command result map, or nil if not a command.
         ;; When a login is pending (waiting for auth code), returns nil
         ;; so the input falls through to run-agent-fn! which handles it.
         dispatch-fn (fn [text]
                       (if (:pending-login @session-state)
                         nil  ;; fall through to run-agent-fn! for login code
                         (let [sid    @tui-focus*
                               result (commands/dispatch-in ctx sid text cmd-opts)]
                           (when result
                             ;; Keep command inputs in the session journal for parity with RPC/CLI.
                             (runtime/journal-user-message-in! ctx sid text nil))
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
                            (let [sid @tui-focus*]
                              (session/abort-in! ctx sid)
                              {:queued-text (session/consume-queued-input-text-in! ctx sid)
                               :message "Interrupted active work."}))

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
                                 (let [sid      @tui-focus*
                                       {:keys [user-message]} (runtime/prepare-user-message-in! ctx sid text nil)
                                       api-key  (runtime/resolve-api-key-in ctx sid ai-model)
                                       result   (runtime/run-agent-loop-in!
                                                 ctx sid ai-ctx ai-model [user-message]
                                                 {:api-key api-key
                                                  :progress-queue queue
                                                  :sync-on-git-head-change? true})]
                                   (.put queue {:kind :done :result result}))
                                 (catch Exception e
                                   (.put queue {:kind :error :message (ex-message e)})))))))]

     (tui-start-fn! (:name ai-model) run-agent-fn!
                    {:query-fn             (fn [q] (session/query-in ctx @tui-focus* q))
                     :ui-read-fn       (fn [] (ss/get-state-value-in ctx (ss/state-path :ui-state)))
                     :ui-dispatch-fn   (fn [event-type payload]
                                         (dispatch/dispatch! ctx event-type payload {:origin :tui}))
                     :dispatch-fn          dispatch-fn
                     :on-interrupt-fn!     on-interrupt-fn!
                     :on-queue-input-fn!   (fn [text _state]
                                             (let [sid @tui-focus*]
                                               (if (= :streaming (ss/sc-phase-in ctx sid))
                                                 (do
                                                   (session/queue-while-streaming-in! ctx sid text :steer)
                                                   {:message "Queued steering message."})
                                                 (do
                                                   (session/queue-while-streaming-in! ctx sid text :queue)
                                                   {:message "Queued follow-up message."}))))
                     :double-press-window-ms 500
                     :double-escape-action :none
                     :cwd                  cwd
                     :focus-session-id     @tui-focus*
                     :current-session-file (:session-file (ss/get-session-data-in ctx @tui-focus*))
                     :initial-messages     (vec (or (:messages startup-rehydrate) []))
                     :initial-tool-calls   (or (:tool-calls startup-rehydrate) {})
                     :initial-tool-order   (vec (or (:tool-order startup-rehydrate) []))
                     :resume-fn!           resume-fn!
                     :switch-session-fn!   switch-session-fn!
                     :event-queue          event-queue
                     :alt-screen           false}))))

;; RPC runtime moved to psi.rpc.
