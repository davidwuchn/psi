(ns psi.agent-session.commands
  "Central command dispatcher for /commands.

   All commands return data maps — no side effects, no printing.
   REPL and TUI are renderers of command results.

   Result types:
     :text          — {:type :text :message string}
     :new-session   — {:type :new-session :message string :rehydrate {:messages [...] :tool-calls {...} :tool-order [...]}}
     :quit          — {:type :quit}
     :resume        — {:type :resume}
     :login-start   — {:type :login-start :provider map :url string :login-state map
                        :uses-callback-server bool}
     :login-error   — {:type :login-error :message string}
     :logout        — {:type :logout :message string}
     :extension-cmd — {:type :extension-cmd :name string :args string :handler fn}
     nil            — not a command (pass through to agent)"
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as ai-models]
   [psi.memory.core :as memory]))

;; ============================================================
;; Formatting helpers (pure — return strings, no side effects)
;; ============================================================

(defn format-status
  "Return a status string for the current session."
  [ctx]
  (let [d (session/query-in ctx
                            [:psi.agent-session/phase
                             :psi.agent-session/session-id
                             :psi.agent-session/model
                             :psi.agent-session/thinking-level
                             :psi.agent-session/extension-summary
                             :psi.agent-session/session-entry-count
                             :psi.agent-session/context-tokens
                             :psi.agent-session/context-window
                             :psi.agent-session/context-fraction
                             :psi.agent-session/git-worktree-current
                             :psi.graph/root-seeds])]
    (str "── Session status ─────────────────────\n"
         "  Phase   : " (:psi.agent-session/phase d) "\n"
         "  ID      : " (:psi.agent-session/session-id d) "\n"
         "  Model   : " (get-in d [:psi.agent-session/model :id] "none") "\n"
         "  Entries : " (:psi.agent-session/session-entry-count d)
         (when-let [frac (:psi.agent-session/context-fraction d)]
           (str "\n  Context : " (int (* 100 frac)) "%"))
         (when-let [wt-path (get-in d [:psi.agent-session/git-worktree-current :git.worktree/path])]
           (str "\n  Worktree: " wt-path))
         (when-let [root-seeds (:psi.graph/root-seeds d)]
           (str "\n  Roots   : " (str/join ", " (map name root-seeds))))
         "\n───────────────────────────────────────")))

(defn format-history
  "Return a history string for the current message list."
  [ctx]
  (let [msgs (:messages (agent/get-data-in (:agent-ctx ctx)))]
    (str "── Message history ────────────────────\n"
         (if (empty? msgs)
           "  (empty)"
           (str/join "\n"
                     (map (fn [msg]
                            (let [role (:role msg)
                                  text (or (some #(when (= :text (:type %)) (:text %))
                                                 (:content msg))
                                           (str "[" role "]"))]
                              (str "  [" role "] "
                                   (subs text 0 (min 120 (count text))))))
                          msgs)))
         "\n───────────────────────────────────────")))

(defn format-help
  "Return a help string listing all available commands."
  [ctx]
  (let [sd            (session/get-session-data-in ctx)
        templates     (:prompt-templates sd)
        loaded-skills (:skills sd)
        ext-cmds      (ext/all-commands-in (:extension-registry ctx))]
    (str "── Commands ───────────────────────────\n"
         "  /quit    — exit the session\n"
         "  /status  — show session diagnostics\n"
         "  /history — show message history\n"
         "  /prompts — list available prompt templates\n"
         "  /skills  — list available skills\n"
         "  /new     — start a fresh session\n"
         "  /resume  — resume a previous session\n"
         "  /login [provider] — login with an OAuth provider\n"
         "  /logout  — logout from an OAuth provider\n"
         "  /model [provider model-id] — show current model or set model\n"
         "  /thinking [level] — show current thinking level or set level\n"
         "  /remember [text] — capture a memory note for future ψ\n"
         "  /worktree — show git worktree context\n"
         "  /help    — show this help\n"
         "  /skill:name — invoke a skill (loads full content)"
         (when (seq templates)
           (str "\n  ── Prompt Templates ─────────────────\n"
                (str/join "\n"
                          (map (fn [t]
                                 (str "  /" (:name t) " — " (:description t)))
                               templates))))
         (when (seq loaded-skills)
           (str "\n  ── Skills ───────────────────────────\n"
                (str/join "\n"
                          (map (fn [s]
                                 (str "  /skill:" (:name s)
                                      (when (:disable-model-invocation s) " [hidden]")
                                      " — " (:description s)))
                               loaded-skills))))
         (when (seq ext-cmds)
           (str "\n  ── Extension Commands ───────────────\n"
                (str/join "\n"
                          (map (fn [c]
                                 (str "  /" (:name c)
                                      (when (:description c)
                                        (str " — " (:description c)))))
                               ext-cmds))))
         "\n  (anything else is sent to the agent)\n"
         "───────────────────────────────────────")))

(defn format-prompts
  "Return a string listing available prompt templates."
  [ctx]
  (let [templates (:prompt-templates (session/get-session-data-in ctx))]
    (str "── Prompt Templates ───────────────────\n"
         (if (empty? templates)
           "  (none discovered)"
           (str/join "\n"
                     (map (fn [t]
                            (let [placeholders
                                  (if (pt/has-placeholders? (:content t))
                                    (str " [$" (pt/placeholder-count (:content t)) " args]")
                                    "")]
                              (str "  /" (:name t) placeholders " — " (:description t)
                                   " [" (name (:source t)) "]")))
                          templates)))
         "\n───────────────────────────────────────")))

(defn format-skills
  "Return a string listing available skills."
  [ctx]
  (let [loaded-skills (:skills (session/get-session-data-in ctx))]
    (str "── Skills ─────────────────────────────\n"
         (if (empty? loaded-skills)
           "  (none discovered)"
           (str/join "\n"
                     (map (fn [s]
                            (str "  /skill:" (:name s)
                                 (when (:disable-model-invocation s) " [hidden]")
                                 " — " (:description s)
                                 " [" (name (:source s)) "]"))
                          loaded-skills)))
         "\n───────────────────────────────────────")))

(defn format-worktree
  "Return a deterministic git worktree status string for the session cwd." 
  [ctx]
  (let [d (session/query-in ctx
                            [:psi.agent-session/cwd
                             :psi.agent-session/git-branch
                             :git.worktree/inside-repo?
                             :git.worktree/list
                             :git.worktree/current
                             :git.worktree/count])
        inside?   (boolean (:git.worktree/inside-repo? d))
        cwd       (:psi.agent-session/cwd d)
        branch    (:psi.agent-session/git-branch d)
        current   (:git.worktree/current d)
        worktrees (vec (or (:git.worktree/list d) []))
        count*    (or (:git.worktree/count d) (count worktrees))]
    (if-not inside?
      (str "── Git worktrees ─────────────────────\n"
           "  cwd      : " cwd "\n"
           "  inside   : false\n"
           "  worktrees: 0\n"
           "───────────────────────────────────────")
      (str "── Git worktrees ─────────────────────\n"
           "  cwd      : " cwd "\n"
           "  branch   : " (or branch "(none)") "\n"
           "  current  : " (or (:git.worktree/path current) "(unknown)") "\n"
           "  worktrees: " count* "\n"
           (when (seq worktrees)
             (str "\n"
                  (str/join "\n"
                            (map (fn [wt]
                                   (let [cur?    (:git.worktree/current? wt)
                                         det?    (:git.worktree/detached? wt)
                                         path    (:git.worktree/path wt)
                                         bname   (:git.worktree/branch-name wt)
                                         marker  (if cur? "*" "-")
                                         branch* (if det?
                                                   "detached"
                                                   (or bname "(no-branch)"))]
                                     (str "  " marker " " path " [" branch* "]")))
                                 worktrees))))
           "\n───────────────────────────────────────"))))

;; ============================================================
;; Provider env var hint (for login error messages)
;; ============================================================

(def ^:private provider-env-vars
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"
   :google    "GOOGLE_API_KEY"})

(defn- memory-ready?
  [ctx]
  (= :ready (:psi.memory/status (session/query-in ctx [:psi.memory/status]))))

(defn- remember-provenance
  [ctx]
  (let [session-id (get-in (session/get-session-data-in ctx) [:session-id])
        cwd        (:cwd ctx)
        git-branch (try
                     (:psi.agent-session/git-branch
                      (session/query-in ctx [:psi.agent-session/git-branch]))
                     (catch Exception _
                       nil))]
    {:source :remember
     :sessionId session-id
     :cwd cwd
     :gitBranch git-branch}))

(defn- remember-text!
  [ctx text]
  (let [reason (or (not-empty (some-> text str/trim)) "manual /remember")
        result (memory/remember-in!
                (:memory-ctx ctx)
                {:content-type :note
                 :content reason
                 :tags [:remember :manual]
                 :provenance (remember-provenance ctx)})
        store-result (:store result)]
    (if (:ok? result)
      (if (false? (:ok? store-result))
        (str "⚠ Remembered with store fallback"
             "\n  record-id: " (get-in result [:record :record-id])
             (when-let [provider-id (:provider-id store-result)]
               (str "\n  provider: " provider-id))
             (when-let [store-error (:error store-result)]
               (str "\n  store-error: " (name store-error)))
             (when-let [message (:message store-result)]
               (str "\n  detail: " message)))
        (str "✓ Remembered"
             "\n  record-id: " (get-in result [:record :record-id])))
      (str "✗ Remember failed"
           "\n  reason: " (name (:error result))))))
(def ^:private canonical-thinking-levels
  [:off :minimal :low :medium :high :xhigh])

(defn- known-thinking-level?
  [level]
  (contains? (set canonical-thinking-levels) level))

(defn- normalize-thinking-level
  [s]
  (some-> s str/trim str/lower-case keyword))

(defn- unknown-thinking-level-message
  [input]
  (str "Unknown thinking level: " input
       ". Allowed: "
       (str/join ", " (map name canonical-thinking-levels))))

(defn- resolve-runtime-model
  [provider model-id]
  (let [provider* (some-> provider keyword)]
    (some (fn [[_ model]]
            (when (and (= provider* (:provider model))
                       (= model-id (:id model)))
              model))
          ai-models/all-models)))

;; ============================================================
;; Login provider selection (pure — returns data)
;; ============================================================

(defn select-login-provider
  "Select OAuth provider for /login.

   Returns one of:
     {:provider provider-map}
     {:error message-string}"
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
        {:error (str "OAuth login is not available for model provider "
                     (name active-id) ". "
                     (when-let [env-var (get provider-env-vars active-id)]
                       (str "Set " env-var " for this model. "))
                     (when (seq available)
                       (str "Available OAuth providers: "
                            (str/join ", " available) ".")))}))))

;; ============================================================
;; Central command dispatch
;; ============================================================

(defn dispatch
  "Dispatch a /command string. Returns a result map, or nil if not a command.

   Arguments:
     ctx       — session context
     text      — raw user input (trimmed)
     opts      — {:oauth-ctx   oauth context
                  :ai-model    resolved model map}

   Result types — see ns docstring."
  [ctx text {:keys [oauth-ctx ai-model on-new-session!]}]
  (let [trimmed (str/trim text)]
    (cond
      ;; Quit
      (or (= trimmed "/quit") (= trimmed "/exit"))
      {:type :quit}

      ;; New session
      (= trimmed "/new")
      (let [rehydrate (if on-new-session!
                        (on-new-session!)
                        (do
                          (session/new-session-in! ctx)
                          {:messages [] :tool-calls {} :tool-order []}))]
        {:type :new-session
         :message "[New session started]"
         :rehydrate rehydrate})

      ;; Resume
      (= trimmed "/resume")
      {:type :resume}

      ;; Status
      (= trimmed "/status")
      {:type :text :message (format-status ctx)}

      ;; History
      (= trimmed "/history")
      {:type :text :message (format-history ctx)}

      ;; Help
      (or (= trimmed "/help") (= trimmed "/?"))
      {:type :text :message (format-help ctx)}

      ;; Prompts
      (= trimmed "/prompts")
      {:type :text :message (format-prompts ctx)}

      ;; Skills
      (= trimmed "/skills")
      {:type :text :message (format-skills ctx)}

      ;; Worktree
      (= trimmed "/worktree")
      {:type :text :message (format-worktree ctx)}

      ;; Remember
      (or (= trimmed "/remember")
          (str/starts-with? trimmed "/remember "))
      (if-let [memory-ctx (:memory-ctx ctx)]
        (if-not (memory-ready? ctx)
          {:type :text
           :message (str "✗ Remember blocked"
                         "\n  reason: memory_capture_prerequisites_not_ready"
                         "\n  detail: :psi.memory/status must be :ready")}
          (let [reason (-> (str/replace trimmed #"^/remember\s*" "") str/trim not-empty)]
            {:type :text
             :message (remember-text! (assoc ctx :memory-ctx memory-ctx) reason)}))
        {:type :text
         :message "✗ Memory context not configured."})

      ;; Model
      (or (= trimmed "/model")
          (str/starts-with? trimmed "/model "))
      (let [args (-> (str/replace trimmed #"^/model\s*" "")
                     str/trim)]
        (if (str/blank? args)
          (let [m (:model (session/get-session-data-in ctx))]
            {:type :text
             :message (if m
                        (str "Current model: " (:provider m) " " (:id m))
                        "No model selected.")})
          (let [tokens (str/split args #"\s+")]
            (if (not= 2 (count tokens))
              {:type :text
               :message "Usage: /model OR /model <provider> <model-id>"}
              (let [[provider model-id] tokens
                    resolved (resolve-runtime-model provider model-id)]
                (if-not resolved
                  {:type :text
                   :message (str "Unknown model: " provider " " model-id)}
                  (let [provider-str (name (:provider resolved))
                        model {:provider provider-str
                               :id (:id resolved)
                               :reasoning (:supports-reasoning resolved)}
                        sd (session/set-model-in! ctx model)]
                    {:type :text
                     :message (str "✓ Model set to "
                                   (get-in sd [:model :provider]) " "
                                   (get-in sd [:model :id]))})))))))

      ;; Thinking level
      (or (= trimmed "/thinking")
          (str/starts-with? trimmed "/thinking "))
      (let [args (-> (str/replace trimmed #"^/thinking\s*" "")
                     str/trim)]
        (if (str/blank? args)
          (let [level (:thinking-level (session/get-session-data-in ctx))]
            {:type :text
             :message (str "Current thinking level: " (name (or level :off)))})
          (let [tokens (str/split args #"\s+")]
            (if (not= 1 (count tokens))
              {:type :text
               :message "Usage: /thinking OR /thinking <level>"}
              (let [level-input (first tokens)
                    level (normalize-thinking-level level-input)]
                (if-not (known-thinking-level? level)
                  {:type :text
                   :message (unknown-thinking-level-message level-input)}
                  (let [sd (session/set-thinking-level-in! ctx level)]
                    {:type :text
                     :message (str "✓ Thinking level set to "
                                   (name (:thinking-level sd)))})))))))

      ;; Login
      (or (= trimmed "/login")
          (str/starts-with? trimmed "/login "))
      (if-not oauth-ctx
        {:type :login-error :message "OAuth not available."}
        (let [provider-arg (second (str/split trimmed #"\s+" 2))
              providers    (oauth/available-providers oauth-ctx)
              {:keys [provider error]}
              (select-login-provider providers (:provider ai-model) provider-arg)]
          (if error
            {:type :login-error :message (str "✗ " error)}
            (try
              (let [{:keys [url login-state]}
                    (oauth/begin-login! oauth-ctx (:id provider))]
                {:type                 :login-start
                 :provider             provider
                 :url                  url
                 :login-state          login-state
                 :uses-callback-server (and (:uses-callback-server provider)
                                            (:callback-server login-state))})
              (catch Exception e
                {:type :login-error
                 :message (str "✗ Login failed: " (ex-message e))})))))

      ;; Logout
      (= trimmed "/logout")
      (if-not oauth-ctx
        {:type :text :message "OAuth not available."}
        (let [logged-in (oauth/logged-in-providers oauth-ctx)]
          (if (empty? logged-in)
            {:type :text :message "No OAuth providers logged in. Use /login first."}
            (do (doseq [p logged-in]
                  (oauth/logout! oauth-ctx (:id p)))
                {:type    :logout
                 :message (str "✓ Logged out of: "
                               (str/join ", " (map :name logged-in)))}))))

      ;; Extension command: /name args
      (and (str/starts-with? trimmed "/")
           (let [cmd-name (first (str/split (subs trimmed 1) #"\s" 2))]
             (ext/get-command-in (:extension-registry ctx) cmd-name)))
      (let [parts    (str/split (subs trimmed 1) #"\s" 2)
            cmd-name (first parts)
            args-str (or (second parts) "")
            cmd      (ext/get-command-in (:extension-registry ctx) cmd-name)]
        {:type :extension-cmd :name cmd-name :args args-str :handler (:handler cmd)})

      ;; Not a command
      :else
      nil)))

