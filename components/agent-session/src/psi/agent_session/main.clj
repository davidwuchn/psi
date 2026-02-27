(ns psi.agent-session.main
  "Entry point: run an interactive agent session on the terminal.

   Usage:
     clojure -M:run
     clojure -M:run --model sonnet-4.6
     clojure -M:run --log-level DEBUG
     clojure -M:run --tui
     clojure -M:run --nrepl            # random port
     clojure -M:run --nrepl 7888       # specific port
     clojure -M:run --tui --nrepl      # TUI + nREPL

   What it does:
     1. Creates an agent session (statechart + agent-core + extension registry)
     2. Wires the ai provider (Anthropic by default) into the executor
     3. Registers the four built-in tools: read, bash, edit, write
     4. Optionally starts an nREPL server for live introspection
     5. Drops into a REPL-style prompt loop — type a message, get a response
        OR (with --tui) renders an interactive TUI session
     6. /quit or EOF exits (plain mode); Escape or Ctrl+C exits (TUI mode)

   Environment variables:
     ANTHROPIC_API_KEY   — required for Anthropic models
     OPENAI_API_KEY      — required for OpenAI models
     PSI_MODEL           — model key override (e.g. claude-3-5-haiku, gpt-4o, gpt-5.3-codex)

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
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.core :as session]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.agent-session.tools :as tools]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.tui.app :as tui-app])
  (:gen-class))

;; ============================================================
;; Live session state — accessible from nREPL
;; ============================================================

(defonce session-state
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
        port-file    (java.io.File. ".nrepl-port")]
    (spit port-file (str (:port server)))
    (.deleteOnExit port-file)
    (println (str "  nREPL : localhost:" (:port server)
                  " (connect with your editor)"))
    server))

(defn- stop-nrepl! [server]
  (when server
    (let [stop-server (requiring-resolve 'nrepl.server/stop-server)
          port-file   (java.io.File. ".nrepl-port")]
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

(defn- print-help [ctx]
  (println "\n── Commands ───────────────────────────")
  (println "  /quit    — exit the session")
  (println "  /status  — show session diagnostics")
  (println "  /history — show message history")
  (println "  /prompts — list available prompt templates")
  (println "  /skills  — list available skills")
  (println "  /new     — start a fresh session")
  (println "  /login [provider] — login with an OAuth provider")
  (println "  /logout  — logout from an OAuth provider")
  (println "  /help    — show this help")
  (println "  /skill:name — invoke a skill (loads full content)")
  (let [templates (:prompt-templates (session/get-session-data-in ctx))]
    (when (seq templates)
      (println "  ── Prompt Templates ─────────────────")
      (doseq [t templates]
        (println (str "  /" (:name t) " — " (:description t))))))
  (let [loaded-skills (:skills (session/get-session-data-in ctx))]
    (when (seq loaded-skills)
      (println "  ── Skills ───────────────────────────")
      (doseq [s loaded-skills]
        (println (str "  /skill:" (:name s)
                      (when (:disable-model-invocation s) " [hidden]")
                      " — " (:description s))))))
  (let [ext-cmds (ext/all-commands-in (:extension-registry ctx))]
    (when (seq ext-cmds)
      (println "  ── Extension Commands ───────────────")
      (doseq [c ext-cmds]
        (println (str "  /" (:name c)
                      (when (:description c) (str " — " (:description c))))))))
  (println "  (anything else is sent to the agent)")
  (println "───────────────────────────────────────\n"))

(defn- print-prompts [ctx]
  (let [templates (:prompt-templates (session/get-session-data-in ctx))]
    (println "\n── Prompt Templates ───────────────────")
    (if (empty? templates)
      (println "  (none discovered)")
      (doseq [t templates]
        (let [placeholders (if (pt/has-placeholders? (:content t))
                             (str " [$" (pt/placeholder-count (:content t)) " args]")
                             "")]
          (println (str "  /" (:name t) placeholders " — " (:description t)
                        " [" (name (:source t)) "]")))))
    (println "───────────────────────────────────────\n")))

(defn- print-skills [ctx]
  (let [loaded-skills (:skills (session/get-session-data-in ctx))]
    (println "\n── Skills ─────────────────────────────")
    (if (empty? loaded-skills)
      (println "  (none discovered)")
      (doseq [s loaded-skills]
        (println (str "  /skill:" (:name s)
                      (when (:disable-model-invocation s) " [hidden]")
                      " — " (:description s)
                      " [" (name (:source s)) "]"))))
    (println "───────────────────────────────────────\n")))

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

(defn- message->display-text
  "Extract display text from an agent-core message map.
   Includes :text blocks and :error blocks."  
  [msg]
  (let [content (:content msg)]
    (cond
      (string? content)
      content

      (sequential? content)
      (->> content
           (keep (fn [block]
                   (case (:type block)
                     :text  (:text block)
                     :error (str "[error] " (:text block))
                     nil)))
           (str/join "\n"))

      :else
      "")))

(defn- agent-messages->tui-messages
  "Convert agent-core history to TUI display messages.
   Keeps user/assistant roles, skips toolResult messages."  
  [messages]
  (->> messages
       (keep (fn [msg]
               (when-let [role (case (:role msg)
                                 "user"      :user
                                 "assistant" :assistant
                                 nil)]
                 (let [text (message->display-text msg)]
                   {:role role
                    :text (if (str/blank? text)
                            (str "[" (:role msg) "]")
                            text)}))))
       vec))

;; ============================================================
;; OAuth login provider selection
;; ============================================================

(def ^:private provider-env-vars
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"})

(defn select-login-provider
  "Select OAuth provider for /login.

   Inputs:
     providers         — seq of provider maps with :id
     active-provider   — provider keyword for current model (e.g. :openai)
     requested-provider — optional string from `/login <provider>`

   Returns one of:
     {:provider provider-map}
     {:error :message-string}

   Behavior:
   - If requested-provider is supplied, it must match a registered OAuth provider.
   - Otherwise, choose the provider matching active-provider.
   - If no match exists, return a helpful error (no silent fallback to another provider)."
  [providers active-provider requested-provider]
  (let [by-id        (into {} (map (juxt :id identity) providers))
        available    (->> (keys by-id) (map name) sort)
        requested-id (some-> requested-provider str/trim not-empty keyword)
        active-id    (keyword active-provider)]
    (cond
      requested-id
      (if-let [provider (get by-id requested-id)]
        {:provider provider}
        {:error (str "Unknown OAuth provider: " (name requested-id)
                     (when (seq available)
                       (str ". Available: " (str/join ", " available))))})

      :else
      (if-let [provider (get by-id active-id)]
        {:provider provider}
        {:error (str "OAuth login is not available for model provider " (name active-id) ". "
                     (when-let [env-var (get provider-env-vars active-id)]
                       (str "Set " env-var " for this model. "))
                     (when (seq available)
                       (str "Available OAuth providers: " (str/join ", " available) ".")))}))))

;; ============================================================
;; Prompt template expansion
;; ============================================================

(defn- expand-input
  "Expand user input through skills (/skill:name) or prompt templates (/name).
   Returns the (possibly expanded) text to send to the agent.
   Extension commands are excluded — they are handled by the command dispatch."
  [ctx text]
  (let [sd            (session/get-session-data-in ctx)
        loaded-skills (:skills sd)
        templates     (:prompt-templates sd)
        commands      (ext/command-names-in (:extension-registry ctx))]
    ;; Try skill expansion first (/skill:name)
    (if-let [skill-result (skills/invoke-skill loaded-skills text)]
      (do
        (println (str "[Skill: " (:skill-name skill-result) "]\n"))
        (:content skill-result))
      ;; Then try prompt template expansion (/name)
      (if-let [tpl-result (pt/invoke-template templates commands text)]
        (do
          (println (str "[Template: " (:source-template tpl-result) "]\n"))
          (:content tpl-result))
        text))))

;; ============================================================
;; Core: one prompt → response cycle
;; ============================================================

(defn- resolve-api-key
  "Resolve the API key for the current model's provider via OAuth context.
   Returns the key string, or nil (provider falls back to env var)."
  [oauth-ctx ai-model]
  (when oauth-ctx
    (oauth/get-api-key oauth-ctx (:provider ai-model))))

(defn- usage->context-tokens
  "Best-effort context token count from an assistant usage map.
   Returns nil when usage is missing or unknown."
  [usage]
  (when (map? usage)
    (let [input  (or (:input-tokens usage) 0)
          output (or (:output-tokens usage) 0)
          read   (or (:cache-read-tokens usage) 0)
          write  (or (:cache-write-tokens usage) 0)
          total  (or (:total-tokens usage)
                     (+ input output read write))]
      (when (and (number? total) (pos? total))
        total))))

(defn- update-context-usage-from-result!
  "Update session context usage from a completed assistant result when usage is available.
   Keeps context tokens nil when providers do not return reliable usage."
  [ctx ai-model result]
  (let [tokens (usage->context-tokens (:usage result))
        window (:context-window ai-model)]
    (when (and (some? tokens) (number? window) (pos? window))
      (session/update-context-usage-in! ctx tokens window))))

(defn- run-prompt!
  "Send `text` to the agent and block until done, printing the response.
   Expands prompt templates before sending."
  [ctx ai-ctx ai-model oauth-ctx text]
  (let [expanded (expand-input ctx text)
        user-msg {:role      "user"
                  :content   [{:type :text :text expanded}]
                  :timestamp (java.time.Instant/now)}
        api-key  (resolve-api-key oauth-ctx ai-model)
        result   (executor/run-agent-loop! ai-ctx (:agent-ctx ctx) ai-model [user-msg]
                                           {:turn-ctx-atom (:turn-ctx-atom ctx)
                                            :api-key       api-key})]
    (update-context-usage-from-result! ctx ai-model result)
    (print-assistant-message result)))

;; ============================================================
;; Main prompt loop
;; ============================================================

(defn run-session
  "Create a session and enter the interactive prompt loop.
  Returns when the user exits."
  [model-key]
  (let [ai-model  (resolve-model model-key)
        ;; ai context: nil signals the executor to use the public ai/stream-response API
        ai-ctx    nil
        ;; OAuth credential store (file-backed, auto-refresh)
        oauth-ctx (oauth/create-context)
        ;; Discover prompt templates from global + project dirs
        templates (pt/discover-templates)
        ;; Discover skills from global + project dirs
        {:keys [skills diagnostics]} (skills/discover-skills)
        _         (doseq [d diagnostics]
                    (timbre/warn "Skill" (:type d) ":" (:message d) (:path d)))
        ;; Build system prompt with context files and skills
        cwd       (System/getProperty "user.dir")
        ctx-files (sys-prompt/discover-context-files cwd)
        system-prompt (sys-prompt/build-system-prompt
                       {:cwd           cwd
                        :context-files ctx-files
                        :skills        skills})
        ;; session context — agent-core + statechart + extension registry
        ctx      (session/create-context
                  {:initial-session {:model {:provider (name (:provider ai-model))
                                             :id       (:id ai-model)
                                             :reasoning (:supports-reasoning ai-model)}
                                     :prompt-templates templates
                                     :skills          skills
                                     :system-prompt   system-prompt}})]
    ;; Wire agent-session resolvers into the global query graph so
    ;; :psi.agent-session/* attributes are queryable.
    (session/register-resolvers!)
    ;; Register built-in tools into agent-core
    (agent/set-tools-in! (:agent-ctx ctx) tools/all-tool-schemas)
    ;; Set the assembled system prompt (introspectable)
    (agent/set-system-prompt-in! (:agent-ctx ctx) system-prompt)
    ;; Discover and load extensions
    (let [{:keys [loaded errors]} (session/load-extensions-in! ctx)]
      (doseq [{:keys [path error]} errors]
        (timbre/warn "Extension error:" path error))
      (when (seq loaded)
        (timbre/debug "Extensions loaded:" (count loaded))))
    ;; Register extension tools alongside built-ins
    (let [ext-tools (ext/all-tools-in (:extension-registry ctx))]
      (when (seq ext-tools)
        (agent/set-tools-in! (:agent-ctx ctx)
                             (into (vec tools/all-tool-schemas) ext-tools))))
    ;; Expose state for nREPL introspection
    (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                           :oauth-ctx oauth-ctx})
    (print-banner ai-model templates skills ctx)
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

            (= trimmed "/prompts")
            (do (print-prompts ctx) (recur))

            (= trimmed "/skills")
            (do (print-skills ctx) (recur))

            (= trimmed "/new")
            (do (session/new-session-in! ctx)
                (println "\n[New session started]\n")
                (recur))

            (or (= trimmed "/help") (= trimmed "/?"))
            (do (print-help ctx) (recur))

            ;; /login — OAuth login flow
            (= trimmed "/login")
            (do (println "\n── OAuth Login ────────────────────────")
                (let [providers (oauth/available-providers oauth-ctx)]
                  (doseq [[i p] (map-indexed vector providers)]
                    (let [logged-in? (some #(= (:id p) (:id %))
                                           (oauth/logged-in-providers oauth-ctx))]
                      (println (str "  " (inc i) ". " (:name p)
                                    (when logged-in? " ✓ logged in")))))
                  (print "  Select provider (number): ")
                  (flush)
                  (when-let [choice (try (read-line) (catch Exception _ nil))]
                    (let [idx (try (dec (Integer/parseInt (str/trim choice)))
                                   (catch Exception _ -1))]
                      (if-let [provider (nth providers idx nil)]
                        (try
                          (oauth/login! oauth-ctx (:id provider)
                                        {:on-auth     (fn [{:keys [url]}]
                                                        (println (str "\n  Open: " url "\n")))
                                         :on-prompt   (fn [{:keys [message]}]
                                                        (println (str "  " message))
                                                        (print "  > ")
                                                        (flush)
                                                        (or (read-line) ""))
                                         :on-progress (fn [msg] (println (str "  " msg)))})
                          (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
                          (catch Exception e
                            (println (str "\n  ✗ Login failed: " (ex-message e) "\n"))))
                        (println "\n  Invalid selection.\n")))))
                (recur))

            ;; /logout — OAuth logout
            (= trimmed "/logout")
            (do (let [logged-in (oauth/logged-in-providers oauth-ctx)]
                  (if (empty? logged-in)
                    (println "\n  No OAuth providers logged in. Use /login first.\n")
                    (do
                      (println "\n── OAuth Logout ───────────────────────")
                      (doseq [[i p] (map-indexed vector logged-in)]
                        (println (str "  " (inc i) ". " (:name p))))
                      (print "  Select provider (number): ")
                      (flush)
                      (when-let [choice (try (read-line) (catch Exception _ nil))]
                        (let [idx (try (dec (Integer/parseInt (str/trim choice)))
                                       (catch Exception _ -1))]
                          (if-let [provider (nth logged-in idx nil)]
                            (do (oauth/logout! oauth-ctx (:id provider))
                                (println (str "\n  ✓ Logged out of " (:name provider) "\n")))
                            (println "\n  Invalid selection.\n")))))))
                (recur))

            ;; Extension command: /name args
            (and (str/starts-with? trimmed "/")
                 (let [cmd-name (first (str/split (subs trimmed 1) #"\s" 2))]
                   (ext/get-command-in (:extension-registry ctx) cmd-name)))
            (let [parts    (str/split (subs trimmed 1) #"\s" 2)
                  cmd-name (first parts)
                  args-str (or (second parts) "")
                  cmd      (ext/get-command-in (:extension-registry ctx) cmd-name)]
              (try
                (when-let [handler (:handler cmd)]
                  (handler args-str))
                (catch Exception e
                  (println (str "\n[Command error: " (ex-message e) "]\n"))))
              (recur))

            (str/blank? trimmed)
            (recur)

            :else
            (do
              (try
                (run-prompt! ctx ai-ctx ai-model oauth-ctx trimmed)
                (catch Exception e
                  (println (str "\n[Error: " (ex-message e) "]\n"))))
              (recur))))))))

;; ============================================================
;; TUI session (charm.clj Elm Architecture)
;; ============================================================

(defn run-tui-session
  "Create a session and run it as a full-screen TUI via charm.clj.
  Blocks until the user exits (Escape or Ctrl+C while idle)."
  [model-key]
  (let [ai-model  (resolve-model model-key)
        ai-ctx    nil
        oauth-ctx (oauth/create-context)
        templates (pt/discover-templates)
        {:keys [skills diagnostics]} (skills/discover-skills)
        _         (doseq [d diagnostics]
                    (timbre/warn "Skill" (:type d) ":" (:message d) (:path d)))
        cwd       (System/getProperty "user.dir")
        ctx-files (sys-prompt/discover-context-files cwd)
        system-prompt (sys-prompt/build-system-prompt
                       {:cwd           cwd
                        :context-files ctx-files
                        :skills        skills})
        ctx       (session/create-context
                   {:initial-session {:model {:provider (name (:provider ai-model))
                                              :id       (:id ai-model)
                                              :reasoning (:supports-reasoning ai-model)}
                                      :prompt-templates templates
                                      :skills          skills
                                      :system-prompt   system-prompt}})
        _         (session/register-resolvers!)
        _         (agent/set-tools-in! (:agent-ctx ctx) tools/all-tool-schemas)
        _         (agent/set-system-prompt-in! (:agent-ctx ctx) system-prompt)

        ;; Discover and load extensions
        _         (let [{:keys [loaded errors]} (session/load-extensions-in! ctx)]
                    (doseq [{:keys [path error]} errors]
                      (timbre/warn "Extension error:" path error))
                    (when (seq loaded)
                      (timbre/debug "Extensions loaded:" (count loaded))))
        ;; Register extension tools alongside built-ins
        _         (let [ext-tools (ext/all-tools-in (:extension-registry ctx))]
                    (when (seq ext-tools)
                      (agent/set-tools-in! (:agent-ctx ctx)
                                           (into (vec tools/all-tool-schemas) ext-tools))))

        ;; Expose state for nREPL introspection
        _         (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                                         :oauth-ctx oauth-ctx})

        ;; Helper: put an immediate assistant message on the TUI queue.
        reply!    (fn [^java.util.concurrent.LinkedBlockingQueue queue text]
                    (.put queue {:kind :done
                                 :result {:role    "assistant"
                                          :content [{:type :text :text text}]}}))

        ;; Resume callback used by the TUI /resume selector.
        ;; Returns TUI message maps to display immediately after loading.
        resume-fn! (fn [session-path]
                     (try
                       (session/resume-session-in! ctx session-path)
                       (let [messages (:messages (agent/get-data-in (:agent-ctx ctx)))]
                         (agent-messages->tui-messages messages))
                       (catch Exception e
                         (timbre/error e "Resume failed:" session-path)
                         [{:role :assistant
                           :text (str "✗ Resume failed: " (ex-message e))}])))

        ;; run-agent-fn! — called by the TUI update fn on submit.
        ;; Two-step login: /login shows URL → next input is the auth code.
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

                            ;; Step 1: /login [provider] — generate URL, store state, wait for code
                            (or (= trimmed "/login")
                                (str/starts-with? trimmed "/login "))
                            (try
                              (let [provider-arg (second (str/split trimmed #"\s+" 2))
                                    providers    (oauth/available-providers oauth-ctx)
                                    {:keys [provider error]}
                                    (select-login-provider providers (:provider ai-model) provider-arg)]
                                (if error
                                  (reply! queue (str "✗ " error))
                                  (let [{:keys [url login-state]} (oauth/begin-login! oauth-ctx (:id provider))]
                                    (if (and (:uses-callback-server provider)
                                             (:callback-server login-state))
                                      (do
                                        ;; Callback-server providers (e.g. OpenAI):
                                        ;; emit progress text, then auto-complete when callback arrives.
                                        (swap! session-state dissoc :pending-login)
                                        (.put queue {:type       :agent-event
                                                     :event-kind :text-delta
                                                     :text       (str "🔑 Login to " (:name provider)
                                                                      "\n\nOpen this URL in your browser:\n" url
                                                                      "\n\nWaiting for browser callback…")})
                                        (future
                                          (try
                                            (oauth/complete-login! oauth-ctx (:id provider) nil login-state)
                                            (reply! queue (str "✓ Logged in to " (:name provider)))
                                            (catch Exception e
                                              (reply! queue (str "✗ Login failed: " (ex-message e)))))))
                                      ;; Manual-code providers (or callback server unavailable):
                                      ;; store state and wait for pasted code on next input.
                                      (do
                                        (swap! session-state assoc :pending-login
                                               {:provider-id   (:id provider)
                                                :provider-name (:name provider)
                                                :login-state   login-state})
                                        (reply! queue (str "🔑 Login to " (:name provider)
                                                           "\n\nOpen this URL in your browser:\n" url
                                                           (if (:uses-callback-server provider)
                                                             "\n\n(Local callback server unavailable; paste full redirect URL or code below ↓)"
                                                             "\n\nPaste the authorization code below ↓"))))))))
                              (catch Exception e
                                (reply! queue (str "✗ Login failed: " (ex-message e)))))

                            ;; /logout — remove OAuth credentials
                            (= trimmed "/logout")
                            (let [logged-in (oauth/logged-in-providers oauth-ctx)]
                              (if (empty? logged-in)
                                (reply! queue "No OAuth providers logged in. Use /login first.")
                                (do (doseq [p logged-in]
                                      (oauth/logout! oauth-ctx (:id p)))
                                    (reply! queue (str "✓ Logged out of: "
                                                       (str/join ", " (map :name logged-in)))))))

                            ;; Everything else — send to agent
                            :else
                            (future
                              (try
                                (let [expanded (expand-input ctx text)
                                      user-msg {:role      "user"
                                                :content   [{:type :text :text expanded}]
                                                :timestamp (java.time.Instant/now)}
                                      api-key  (resolve-api-key oauth-ctx ai-model)
                                      result   (executor/run-agent-loop!
                                                ai-ctx (:agent-ctx ctx)
                                                ai-model [user-msg]
                                                {:turn-ctx-atom  (:turn-ctx-atom ctx)
                                                 :api-key        api-key
                                                 :progress-queue queue})]
                                  (update-context-usage-from-result! ctx ai-model result)
                                  (.put queue {:kind :done :result result}))
                                (catch Exception e
                                  (.put queue {:kind :error :message (ex-message e)})))))))]

    (tui-app/start! (:name ai-model) run-agent-fn!
                    {:query-fn             (fn [q] (session/query-in ctx q))
                     :ui-state-atom        (:ui-state-atom ctx)
                     :cwd                  cwd
                     :current-session-file (:session-file (session/get-session-data-in ctx))
                     :resume-fn!           resume-fn!})))

;; ============================================================
;; -main
;; ============================================================

(defn -main
  "Entry point. Accepts optional --model <key>, --log-level <LEVEL>, --tui, --nrepl [port]."
  [& args]
  (set-log-level! (log-level-from-args args))
  (let [model-key  (model-key-from-args args)
        tui?       (some #(= "--tui" %) args)
        nrepl-port (nrepl-port-from-args args)
        nrepl-srv  (when nrepl-port (start-nrepl! nrepl-port))]
    (try
      (if tui?
        (run-tui-session model-key)
        (run-session model-key))
      (finally
        (stop-nrepl! nrepl-srv)))
    ;; clj-http (Apache HttpClient) parks a non-daemon connection-eviction thread.
    ;; Explicitly exit so the JVM does not hang after /quit.
    (System/exit 0)))
