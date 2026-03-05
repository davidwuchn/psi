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
   [psi.recursion.core :as recursion]))

;; ============================================================
;; Formatting helpers (pure — return strings, no side effects)
;; ============================================================

(defn format-status
  "Return a status string for the current session."
  [ctx]
  (let [base-query [:psi.agent-session/phase
                    :psi.agent-session/session-id
                    :psi.agent-session/model
                    :psi.agent-session/thinking-level
                    :psi.agent-session/extension-summary
                    :psi.agent-session/session-entry-count
                    :psi.agent-session/context-tokens
                    :psi.agent-session/context-window
                    :psi.agent-session/context-fraction]
        recursion-query [:psi.recursion/status
                         :psi.recursion/paused?
                         :psi.recursion/current-cycle]
        d (session/query-in ctx
                            (if (:recursion-ctx ctx)
                              (into base-query recursion-query)
                              base-query))
        recursion-status (:psi.recursion/status d)
        current-cycle (:psi.recursion/current-cycle d)
        proposal (:proposal current-cycle)
        actions (or (:actions proposal) [])]
    (str "── Session status ─────────────────────\n"
         "  Phase   : " (:psi.agent-session/phase d) "\n"
         "  ID      : " (:psi.agent-session/session-id d) "\n"
         "  Model   : " (get-in d [:psi.agent-session/model :id] "none") "\n"
         "  Entries : " (:psi.agent-session/session-entry-count d)
         (when-let [frac (:psi.agent-session/context-fraction d)]
           (str "\n  Context : " (int (* 100 frac)) "%"))
         (when recursion-status
           (str "\n  Recurs. : " (name recursion-status)
                (when (:psi.recursion/paused? d) " (paused)")))
         (when (and (= :awaiting-approval recursion-status)
                    current-cycle)
           (str "\n  FF Cycle : " (:cycle-id current-cycle)
                "\n  Approve : pending"
                (when-let [risk (:risk proposal)]
                  (str " (risk=" (name risk) ")"))
                "\n  Actions : " (count actions)
                (when-let [first-title (:title (first actions))]
                  (str "\n    - " first-title))
                (when (> (count actions) 1)
                  (str "\n    + " (dec (count actions)) " more"))
                "\n  Next    : /feed-forward approve " (:cycle-id current-cycle) " [notes]"
                "\n            /feed-forward reject " (:cycle-id current-cycle) " [notes]"))
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
         "  /feed-forward [reason] — trigger a manual feed-forward cycle\n"
         "  /feed-forward approve [cycle-id] [notes] — approve pending cycle\n"
         "  /feed-forward reject [cycle-id] [notes] — reject pending cycle\n"
         "  /feed-forward continue [cycle-id] — continue an approved cycle\n"
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

;; ============================================================
;; Provider env var hint (for login error messages)
;; ============================================================

(def ^:private provider-env-vars
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"
   :google    "GOOGLE_API_KEY"})

(def ^:private default-feed-forward-readiness
  {:query-ready true
   :graph-ready true
   :introspection-ready true
   :memory-ready true})

(defn- runtime-feed-forward-inputs
  "Derive live runtime readiness + observe snapshots from session query state.
   Falls back to safe defaults when attrs are unavailable."
  [ctx]
  (let [snapshot (session/query-in ctx
                                   [:psi.graph/nodes
                                    :psi.graph/edges
                                    :psi.memory/status
                                    :psi.memory/entry-count])
        node-count (count (:psi.graph/nodes snapshot))
        edge-count (count (:psi.graph/edges snapshot))
        memory-status (:psi.memory/status snapshot)
        memory-entry-count (or (:psi.memory/entry-count snapshot) 0)
        memory-ready? (= :ready memory-status)
        graph-ready? (and (pos? node-count)
                          (pos? edge-count))
        readiness (merge default-feed-forward-readiness
                         {:graph-ready graph-ready?
                          :memory-ready memory-ready?})
        graph-state {:node-count node-count
                     :capability-count edge-count
                     :status (if graph-ready? :stable :emerging)}
        memory-state {:entry-count memory-entry-count
                      :status (if memory-ready? :ready :initializing)
                      :recovery-count 0}]
    {:readiness readiness
     :graph-state graph-state
     :memory-state memory-state}))

(defn- feed-forward-trigger-signal
  [reason]
  (recursion/manual-trigger-signal reason {:source :runtime-command}))

(defn- format-feed-forward-trigger-result
  [trigger-result orchestration]
  (let [phase (:phase orchestration)]
    (case (:result trigger-result)
      :accepted
      (str "✓ Feed-forward trigger accepted"
           "\n  cycle-id: " (:cycle-id trigger-result)
           "\n  prompt: " recursion/feed-forward-manual-trigger-prompt-name
           (case phase
             :awaiting-approval "\n  status: awaiting approval"
             :completed "\n  status: cycle completed"
             :trigger "\n  status: trigger accepted"
             (str "\n  phase: " (name phase))))

      :blocked
      (str "‖ Feed-forward trigger blocked"
           "\n  cycle-id: " (:cycle-id trigger-result)
           "\n  reason: recursion_prerequisites_not_ready")

      :ignored
      "… Feed-forward trigger ignored (manual hook disabled)."

      :rejected
      (str "✗ Feed-forward trigger rejected"
           "\n  reason: " (name (:reason trigger-result)))

      (str trigger-result))))

(def ^:private feed-forward-cycle-id-pattern
  #"^cycle-[0-9a-fA-F-]+$")

(def ^:private feed-forward-action-labels
  {:approve {:past "approved" :present "approve"}
   :reject {:past "rejected" :present "reject"}
   :continue {:past "continued" :present "continue"}})

(defn- cycle-id-token?
  [s]
  (boolean (and (string? s)
                (re-matches feed-forward-cycle-id-pattern s))))

(defn- parse-feed-forward-op-args
  [arg-text]
  (let [trimmed (str/trim (or arg-text ""))
        tokens (if (str/blank? trimmed) [] (str/split trimmed #"\s+"))
        first-token (first tokens)]
    (if (cycle-id-token? first-token)
      {:cycle-id first-token
       :notes (some->> (rest tokens) (str/join " ") str/trim not-empty)}
      {:cycle-id nil
       :notes (not-empty trimmed)})))

(defn- parse-feed-forward-command
  [trimmed]
  (let [args (-> (str/replace trimmed #"^/feed-forward\s*" "")
                 str/trim)]
    (if (str/blank? args)
      {:op :trigger :reason nil}
      (let [[_ first-token rest-text] (re-matches #"^(\S+)(?:\s+(.*))?$" args)
            op (some-> first-token str/lower-case)]
        (case op
          "approve" (assoc (parse-feed-forward-op-args rest-text) :op :approve)
          "reject" (assoc (parse-feed-forward-op-args rest-text) :op :reject)
          "continue" (assoc (parse-feed-forward-op-args rest-text) :op :continue)
          {:op :trigger :reason args})))))

(defn- active-cycle-id
  [ctx]
  (get-in (session/query-in ctx [:psi.recursion/current-cycle])
          [:psi.recursion/current-cycle :cycle-id]))

(defn- cycle-by-id
  [recursion-ctx cycle-id]
  (some #(when (= cycle-id (:cycle-id %)) %)
        (:cycles (recursion/get-state-in recursion-ctx))))

(defn- format-feed-forward-cycle-step-result
  [action cycle-id step-result recursion-ctx]
  (let [{:keys [past present]} (get feed-forward-action-labels action)]
    (if (:ok? step-result)
      (let [cycle (cycle-by-id recursion-ctx cycle-id)
            cycle-status (:status cycle)
            outcome-status (get-in cycle [:outcome :status])]
        (str "✓ Feed-forward cycle " past
             "\n  cycle-id: " cycle-id
             (when-let [phase (:phase step-result)]
               (str "\n  phase: " (name phase)))
             (when cycle-status
               (str "\n  cycle-status: " (name cycle-status)))
             (when outcome-status
               (str "\n  outcome: " (name outcome-status)))))
      (str "✗ Feed-forward cycle " present " failed"
           "\n  cycle-id: " cycle-id
           (when-let [phase (:phase step-result)]
             (str "\n  phase: " (name phase)))
           (when-let [reason (:error step-result)]
             (str "\n  reason: " (name reason)))))))

(defn- no-active-feed-forward-cycle-message
  []
  "✗ No active feed-forward cycle. Use /feed-forward [reason] first.")

(defn- continue-feed-forward-cycle-in!
  [recursion-ctx cycle-id opts]
  (let [continue-fn (requiring-resolve 'psi.recursion.core/continue-cycle-in!)]
    (continue-fn recursion-ctx cycle-id opts)))

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

      ;; Feed-forward trigger + approval lifecycle
      (or (= trimmed "/feed-forward")
          (str/starts-with? trimmed "/feed-forward "))
      (if-let [recursion-ctx (:recursion-ctx ctx)]
        (let [{:keys [op reason cycle-id notes]} (parse-feed-forward-command trimmed)]
          (case op
            :trigger
            (let [{:keys [readiness graph-state memory-state]}
                  (runtime-feed-forward-inputs ctx)
                  orchestration
                  (recursion/orchestrate-manual-trigger-in!
                   recursion-ctx
                   (feed-forward-trigger-signal reason)
                   {:system-state readiness
                    :graph-state graph-state
                    :memory-state memory-state
                    :memory-ctx (:memory-ctx ctx)})
                  trigger-result (:trigger-result orchestration)]
              {:type :text
               :message (format-feed-forward-trigger-result trigger-result orchestration)})

            :approve
            (let [resolved-cycle-id (or cycle-id (active-cycle-id ctx))]
              (if-not resolved-cycle-id
                {:type :text
                 :message (no-active-feed-forward-cycle-message)}
                (let [approval-result (recursion/approve-proposal-in!
                                       recursion-ctx
                                       resolved-cycle-id
                                       "operator"
                                       (or notes ""))
                      continue-result (when (:ok? approval-result)
                                        (continue-feed-forward-cycle-in!
                                         recursion-ctx
                                         resolved-cycle-id
                                         {:memory-ctx (:memory-ctx ctx)}))
                      step-result (if (:ok? approval-result)
                                    (or continue-result {:ok? true})
                                    approval-result)]
                  {:type :text
                   :message (format-feed-forward-cycle-step-result
                             :approve
                             resolved-cycle-id
                             step-result
                             recursion-ctx)})))

            :reject
            (let [resolved-cycle-id (or cycle-id (active-cycle-id ctx))]
              (if-not resolved-cycle-id
                {:type :text
                 :message (no-active-feed-forward-cycle-message)}
                (let [reject-result (recursion/reject-proposal-in!
                                     recursion-ctx
                                     resolved-cycle-id
                                     "operator"
                                     (or notes ""))
                      continue-result (when (:ok? reject-result)
                                        (continue-feed-forward-cycle-in!
                                         recursion-ctx
                                         resolved-cycle-id
                                         {:memory-ctx (:memory-ctx ctx)}))
                      step-result (if (:ok? reject-result)
                                    (or continue-result {:ok? true})
                                    reject-result)]
                  {:type :text
                   :message (format-feed-forward-cycle-step-result
                             :reject
                             resolved-cycle-id
                             step-result
                             recursion-ctx)})))

            :continue
            (let [resolved-cycle-id (or cycle-id (active-cycle-id ctx))]
              (if-not resolved-cycle-id
                {:type :text
                 :message (no-active-feed-forward-cycle-message)}
                (let [continue-result (continue-feed-forward-cycle-in!
                                       recursion-ctx
                                       resolved-cycle-id
                                       {:memory-ctx (:memory-ctx ctx)})]
                  {:type :text
                   :message (format-feed-forward-cycle-step-result
                             :continue
                             resolved-cycle-id
                             continue-result
                             recursion-ctx)})))))
        {:type :text
         :message "✗ Feed-forward recursion context not configured."})

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

