(ns extensions.workflow-loader
  "Workflow Loader — unified workflow definition discovery and delegate tool.

   Replaces the `agent` and `agent-chain` extensions with a single surface.
   Discovers `.psi/workflows/*.md` files, parses/compiles them into canonical
   workflow definitions, registers them with the deterministic workflow runtime,
   and exposes a `delegate` tool and `/delegate` command.

   File format: YAML frontmatter + optional EDN config + body text.
   Single-step profiles and multi-step orchestrations use the same format.

   Tool: delegate(action, workflow, prompt, ...)
   Command: /delegate <workflow> <prompt>"
  (:require
   [clojure.string :as str]
   [psi.agent-session.workflow-file-loader :as loader]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

;;; Extension state

(def ^:private state (atom nil))

(def ^:private prompt-contribution-id "workflow-loader-workflows")

;;; Helpers

(defn- query-fn [] (:query-fn @state))
(defn- mutate! [sym params] ((:mutate-fn @state) sym params))
(defn- log! [msg] ((:log-fn @state) msg))
(defn- notify! [msg level] ((:notify-fn @state) msg level))

(defn- worktree-path []
  (when-let [qf (query-fn)]
    (:psi.agent-session/worktree-path
     (qf [:psi.agent-session/worktree-path]))))

(defn- current-session-id []
  (when-let [qf (query-fn)]
    (:psi.agent-session/session-id
     (qf [:psi.agent-session/session-id]))))

;;; Definition loading and registration

(defn- register-definitions!
  "Load workflow files from disk and register them with the canonical workflow runtime.
   Returns {:registered-count ... :errors [...] :warnings [...]}."
  []
  (let [wtp (worktree-path)
        {:keys [definitions errors warnings]} (loader/load-workflow-definitions wtp)]
    ;; Register each definition via mutation
    (doseq [[_name definition] definitions]
      (mutate! 'psi.workflow/register-definition {:definition definition}))
    ;; Store loaded definitions in extension state for prompt contribution
    (swap! state assoc :loaded-definitions definitions)
    {:registered-count (count definitions)
     :definition-names (sort (keys definitions))
     :errors errors
     :warnings warnings}))

(defn- build-prompt-contribution
  "Build the prompt contribution text listing available workflows."
  []
  (let [definitions (:loaded-definitions @state)]
    (if (empty? definitions)
      "tool: delegate\nNo workflows available."
      (str "tool: delegate\navailable workflows:\n"
           (str/join "\n"
                     (for [[name defn-map] (sort-by key definitions)]
                       (str "- " name ": " (or (:summary defn-map) (:description defn-map) ""))))))))

(defn- register-prompt-contribution! []
  (when-let [register-fn (:register-prompt-contribution @state)]
    (register-fn {:id prompt-contribution-id
                  :section "Extension Capabilities"
                  :content (build-prompt-contribution)})))

(defn- reload-definitions!
  "Reload workflow definitions from disk and update prompt contribution."
  []
  (let [result (register-definitions!)]
    (register-prompt-contribution!)
    result))

;;; Delegate tool implementation

(defn- parse-mode [mode-str]
  (case (some-> mode-str str str/lower-case str/trim)
    ("sync" "synchronous") :sync
    ("async" "asynchronous" nil) :async
    ::invalid))

(defn- available-workflows-text
  "Return human-readable list of available workflows."
  []
  (let [definitions (:loaded-definitions @state)]
    (if (empty? definitions)
      "No workflows loaded."
      (str/join "\n"
                (for [[name defn-map] (sort-by key definitions)]
                  (let [step-count (count (:step-order defn-map))]
                    (str "  " name " — " (or (:summary defn-map) "")
                         (when (> step-count 1)
                           (str " (" step-count " steps)")))))))))

(defn- active-runs-text
  "Return human-readable list of active/recent workflow runs."
  []
  (let [result (mutate! 'psi.workflow/list-runs {})
        runs (:psi.workflow/runs result)]
    (if (empty? runs)
      "No active runs."
      (str/join "\n"
                (for [{:keys [run-id status source-definition-id]} runs]
                  (str "  " run-id " — " (name status)
                       (when source-definition-id
                         (str " (" source-definition-id ")"))))))))

(defn- delegate-list
  "Handle action=list: list available workflows and active runs."
  []
  (str "Available workflows:\n" (available-workflows-text)
       "\n\nActive runs:\n" (active-runs-text)))

(defn- delegate-run
  "Handle action=run: resolve workflow, create + execute canonical workflow run."
  [{:keys [workflow prompt name mode fork_session include_result_in_context timeout_ms]}]
  (let [workflow-name (some-> workflow str str/trim not-empty)
        prompt-text   (some-> prompt str str/trim not-empty)
        mode*         (parse-mode mode)]
    (cond
      (nil? workflow-name)
      {:error "workflow is required"}

      (nil? prompt-text)
      {:error "prompt is required"}

      (= ::invalid mode*)
      {:error "mode must be one of: sync, async"}

      (nil? (get (:loaded-definitions @state) workflow-name))
      {:error (str "Unknown workflow '" workflow-name "'. Use action=list to see available workflows.")}

      :else
      (let [run-name    (or name (str workflow-name "-" (System/currentTimeMillis)))
            session-id  (current-session-id)
            workflow-input {:input prompt-text
                            :original prompt-text}
            ;; Create the canonical workflow run
            create-result (mutate! 'psi.workflow/create-run
                                   {:definition-id workflow-name
                                    :workflow-input workflow-input
                                    :run-id run-name})
            run-id (:psi.workflow/run-id create-result)]
        (if-not run-id
          {:error (or (:psi.workflow/error create-result)
                      "Failed to create workflow run")}
          ;; Execute
          (let [exec-result (mutate! 'psi.workflow/execute-run
                                     {:run-id run-id
                                      :session-id session-id})]
            (if (:psi.workflow/error exec-result)
              {:error (:psi.workflow/error exec-result)
               :run-id run-id}
              {:ok true
               :run-id run-id
               :status (:psi.workflow/status exec-result)
               :result (:psi.workflow/result exec-result)})))))))

(defn- delegate-continue
  "Handle action=continue: resume a stopped/blocked run with new prompt."
  [{:keys [id prompt include_result_in_context]}]
  (let [run-id (some-> id str str/trim not-empty)
        prompt-text (some-> prompt str str/trim not-empty)]
    (cond
      (nil? run-id)
      {:error "id is required for continue"}

      (nil? prompt-text)
      {:error "prompt is required for continue"}

      :else
      (let [result (mutate! 'psi.workflow/resume-run
                            {:run-id run-id
                             :session-id (current-session-id)})]
        (if (:psi.workflow/error result)
          {:error (:psi.workflow/error result)}
          {:ok true
           :run-id run-id
           :status (:psi.workflow/status result)})))))

(defn- delegate-remove
  "Handle action=remove: remove a run by id."
  [{:keys [id]}]
  (let [run-id (some-> id str str/trim not-empty)]
    (if (nil? run-id)
      {:error "id is required for remove"}
      (let [result (mutate! 'psi.workflow/cancel-run {:run-id run-id})]
        (if (:psi.workflow/error result)
          {:error (:psi.workflow/error result)}
          {:ok true :run-id run-id})))))

(defn- execute-delegate-tool
  "Main delegate tool execution dispatcher."
  [args _opts]
  (let [action (or (some-> (:action args) str str/lower-case str/trim) "run")]
    (case action
      "list"     (delegate-list)
      "run"      (let [result (delegate-run args)]
                   (if (:error result)
                     (str "Error: " (:error result))
                     (str "Workflow run " (:run-id result) " — status: " (name (:status result))
                          (when (:result result)
                            (str "\n\n" (:result result))))))
      "continue" (let [result (delegate-continue args)]
                   (if (:error result)
                     (str "Error: " (:error result))
                     (str "Resumed run " (:run-id result) " — status: " (name (:status result)))))
      "remove"   (let [result (delegate-remove args)]
                   (if (:error result)
                     (str "Error: " (:error result))
                     (str "Removed run " (:run-id result))))
      (str "Unknown action: " action ". Use: run, list, continue, remove"))))

;;; Delegate command

(defn- parse-delegate-command
  "Parse `/delegate <workflow> <prompt>` args."
  [args-str]
  (let [trimmed (str/trim (or args-str ""))]
    (when-not (str/blank? trimmed)
      (let [space-idx (str/index-of trimmed " ")]
        (if space-idx
          {:workflow (subs trimmed 0 space-idx)
           :prompt (str/trim (subs trimmed (inc space-idx)))}
          {:workflow trimmed})))))

;;; Extension init

(defn init [api]
  (swap! state assoc
         :api api
         :query-fn (:query api)
         :mutate-fn (:mutate api)
         :log-fn (or (:log api) println)
         :notify-fn (or (:notify api) (fn [m _] (println m)))
         :register-prompt-contribution
         (when-let [rpc (:register-prompt-contribution api)]
           rpc)
         :loaded-definitions {})

  ;; Load and register all workflow definitions
  (let [{:keys [registered-count errors]} (reload-definitions!)]
    (when (seq errors)
      (notify! (str "Workflow loader: " (count errors) " error(s) loading definitions") :warn))
    (notify! (str "workflow-loader: " registered-count " workflows loaded") :info))

  ;; Register delegate tool
  ((:register-tool api)
   {:name        "delegate"
    :label       "Delegate"
    :description "Run, list, continue, or remove workflow-based delegations. Covers single-step agent profiles and multi-step orchestrations."
    :parameters  {:type       "object"
                  :properties {"action"                   {:type "string"
                                                           :enum ["run" "list" "continue" "remove"]
                                                           :description "Operation: run (default), list, continue, remove"}
                               "workflow"                 {:type "string"
                                                           :description "Workflow name to run (action=run)"}
                               "prompt"                   {:type "string"
                                                           :description "Input/request text (action=run, action=continue)"}
                               "name"                     {:type "string"
                                                           :description "Optional label for this run (action=run)"}
                               "id"                       {:type "string"
                                                           :description "Run id (action=continue, action=remove)"}
                               "mode"                     {:type "string"
                                                           :enum ["sync" "async"]
                                                           :description "Execution mode (default async)"}
                               "fork_session"             {:type "boolean"
                                                           :description "When true, child session starts from a fork of the parent conversation"}
                               "include_result_in_context" {:type "boolean"
                                                            :description "When true, inject result into parent context"}
                               "timeout_ms"               {:type "integer"
                                                           :description "Sync mode timeout in milliseconds (default 300000)"}}
                  :required   ["action"]}
    :execute     (fn
                   ([args] (execute-delegate-tool args nil))
                   ([args opts] (execute-delegate-tool args opts)))})

  ;; Register /delegate command
  ((:register-command api) "delegate"
                           {:description "Delegate to a workflow: /delegate <workflow> <prompt>"
                            :handler (fn [args]
                                       (let [{:keys [workflow prompt]} (parse-delegate-command args)]
                                         (cond
                                           (nil? workflow)
                                           (log! (str "Available workflows:\n" (available-workflows-text)))

                                           (nil? prompt)
                                           (log! (str "Usage: /delegate " workflow " <prompt>"))

                                           :else
                                           (let [result (delegate-run {:workflow workflow
                                                                       :prompt prompt
                                                                       :mode "async"})]
                                             (if (:error result)
                                               (log! (str "Error: " (:error result)))
                                               (log! (str "Delegated to " workflow " — run " (:run-id result))))))))})

  ;; Register /delegate-reload command
  ((:register-command api) "delegate-reload"
                           {:description "Reload workflow definitions from disk"
                            :handler (fn [_args]
                                       (let [{:keys [registered-count errors]} (reload-definitions!)]
                                         (log! (str "Reloaded: " registered-count " workflows"
                                                    (when (seq errors)
                                                      (str ", " (count errors) " errors"))))))})

  ;; Session lifecycle cleanup
  ((:on api) "session_switch"
             (fn [_event]
               (reload-definitions!))))
