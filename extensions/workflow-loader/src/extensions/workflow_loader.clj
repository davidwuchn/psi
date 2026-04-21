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
   [clojure.set :as set]
   [clojure.string :as str]
   [psi.agent-session.workflow-file-loader :as loader]
   [psi.agent-session.workflow-runtime :as workflow-runtime])
  (:import
   [java.util.concurrent Future]))

;;; Extension state

(def ^:private state (atom nil))

(def ^:private prompt-contribution-id "workflow-loader-workflows")

;; Track active async runs: {run-id {:future ... :session-id ... :workflow ...}}
(def ^:private active-runs (atom {}))

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

(defn- query-session-fn [] (:query-session-fn @state))
(defn- mutate-session-fn [] (:mutate-session-fn @state))

(defn- session-last-role
  "Get the last user/assistant message role for an explicit session id.
   Falls back to ambient query when explicit session-targeting APIs are absent."
  [session-id]
  (let [query-result (cond
                       (and session-id (query-session-fn))
                       ((query-session-fn) session-id
                        [{:psi.agent-session/session-entries
                          [:psi.session-entry/kind
                           :psi.session-entry/data]}])

                       (query-fn)
                       ((query-fn) [{:psi.agent-session/session-entries
                                     [:psi.session-entry/kind
                                      :psi.session-entry/data]}])

                       :else nil)]
    (->> (:psi.agent-session/session-entries query-result)
         (map :psi.session-entry/data)
         (remove :custom-type)
         (map :role)
         (filter #{"user" "assistant"})
         last)))

;;; Definition loading and registration

(defn- retire-removed-definitions!
  [old-definitions new-definitions]
  (let [old-ids (set (map (comp :definition-id val) old-definitions))
        new-ids (set (map (comp :definition-id val) new-definitions))
        retired-ids (sort (set/difference old-ids new-ids))]
    (doseq [definition-id retired-ids]
      (mutate! 'psi.workflow/remove-definition {:definition-id definition-id}))
    retired-ids))

(defn- register-definitions!
  "Load workflow files from disk and register them with the canonical workflow runtime.
   Returns {:registered-count ... :errors [...] :warnings [...] :retired-definition-ids [...]}"
  []
  (let [wtp (worktree-path)
        old-definitions (:loaded-definitions @state)
        {:keys [definitions errors warnings]} (loader/load-workflow-definitions wtp)
        retired-definition-ids (retire-removed-definitions! old-definitions definitions)]
    ;; Register each definition via mutation
    (doseq [[_name definition] definitions]
      (mutate! 'psi.workflow/register-definition {:definition definition}))
    ;; Store loaded definitions in extension state for prompt contribution
    (swap! state assoc :loaded-definitions definitions)
    {:registered-count (count definitions)
     :definition-names (sort (keys definitions))
     :retired-definition-ids retired-definition-ids
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

(declare refresh-widgets!)

;;; Result injection

(defn- append-message-in-session!
  [session-id role content]
  (try
    (cond
      (and session-id (mutate-session-fn))
      ((mutate-session-fn) session-id 'psi.extension/append-message
       {:role role :content content})

      (:mutate-fn @state)
      ((:mutate-fn @state) 'psi.extension/append-message
       {:role role :content content})

      :else nil)
    (catch Exception _ nil)))

(defn- inject-result-into-context!
  "Inject workflow result text into the parent session context as user+assistant messages.
   Maintains strict user/assistant alternation and explicitly targets the
   originating parent session when session-targeting APIs are available."
  [parent-session-id run-id result-text]
  (let [last-role (session-last-role parent-session-id)
        user-content (str "Workflow run " run-id " result:")
        asst-content (or result-text "")]
    ;; Bridge if last role is user
    (when (= "user" last-role)
      (append-message-in-session! parent-session-id "assistant" "(workflow context bridge)"))
    (append-message-in-session! parent-session-id "user" user-content)
    (append-message-in-session! parent-session-id "assistant" asst-content)))

;;; Async execution

(defn- workflow-run-terminal?
  "Check if a workflow run has reached terminal status."
  [run-id]
  (let [result (mutate! 'psi.workflow/list-runs {})
        runs (:psi.workflow/runs result)]
    (when-let [run (some #(when (= run-id (:run-id %)) %) runs)]
      (contains? #{:completed :failed :cancelled} (:status run)))))

(defn- workflow-run-result-text
  "Extract result text from a completed workflow run."
  [run-id]
  (let [result (mutate! 'psi.workflow/list-runs {})
        runs (:psi.workflow/runs result)]
    (when-let [run (some #(when (= run-id (:run-id %)) %) runs)]
      ;; The execute mutation returns result text, but for async we need to
      ;; re-query — the result is captured by the execution mutation
      (:result run))))

(defn- on-async-completion!
  "Handle async workflow completion — notify, inject results, clean up."
  [run-id workflow-name parent-session-id include-result? exec-result]
  (let [status (:psi.workflow/status exec-result)
        result-text (:psi.workflow/result exec-result)
        ok? (= :completed status)]
    ;; Inject result into parent context if requested
    (when (and include-result? result-text)
      (inject-result-into-context! parent-session-id run-id result-text))
    ;; Notify completion
    (notify! (str "Workflow '" workflow-name "' " (name (or status :unknown))
                  " (run " run-id ")")
             (if ok? :info :warn))
    ;; Emit result entry
    (when-let [mutate-fn (:mutate-fn @state)]
      (let [heading (str "Workflow '" workflow-name "' — " (name (or status :unknown))
                         " (run " run-id ")")
            content (if (and result-text (not include-result?))
                      (str heading "\n\nResult:\n"
                           (if (> (count result-text) 8000)
                             (str (subs result-text 0 8000) "\n\n... [truncated]")
                             result-text))
                      heading)]
        (when-not include-result?
          (try
            (mutate-fn 'psi.extension/append-entry
                       {:custom-type "delegate-result"
                        :data content})
            (catch Exception _ nil)))))
    ;; Clean up tracking and refresh widgets
    (swap! active-runs dissoc run-id)
    (refresh-widgets!)))

(defn- execute-async!
  "Launch workflow execution asynchronously on a separate thread.
   Returns immediately with the run-id."
  [run-id session-id workflow-name include-result?]
  (let [parent-session-id session-id
        fut (future
              (try
                (let [exec-result (mutate! 'psi.workflow/execute-run
                                           {:run-id run-id
                                            :session-id session-id})]
                  (on-async-completion! run-id workflow-name parent-session-id include-result? exec-result)
                  exec-result)
                (catch Exception e
                  (notify! (str "Workflow '" workflow-name "' failed: " (ex-message e)) :error)
                  (swap! active-runs dissoc run-id)
                  {:psi.workflow/error (ex-message e)})))]
    (swap! active-runs assoc run-id
           {:future fut
            :session-id session-id
            :parent-session-id parent-session-id
            :workflow workflow-name
            :started-at (System/currentTimeMillis)})
    (refresh-widgets!)
    run-id))

;;; Sync execution

(defn- await-run-completion
  "Block until a workflow run completes or timeout is reached.
   Returns the execution result."
  [run-id timeout-ms]
  (let [^Future fut (get-in @active-runs [run-id :future])]
    (if fut
      (try
        (.get fut timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (catch java.util.concurrent.TimeoutException _
          {:psi.workflow/status :timeout
           :psi.workflow/error (str "Timed out after " timeout-ms "ms")})
        (catch Exception e
          {:psi.workflow/status :failed
           :psi.workflow/error (ex-message e)}))
      ;; No future — must have been executed synchronously already
      {:psi.workflow/error "Run not found in active tracking"})))

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
        runs (:psi.workflow/runs result)
        tracked @active-runs]
    (if (and (empty? runs) (empty? tracked))
      "No active runs."
      (let [canonical (for [{:keys [run-id status source-definition-id]} runs]
                        (let [async? (contains? tracked run-id)]
                          (str "  " run-id " — " (name status)
                               (when source-definition-id
                                 (str " (" source-definition-id ")"))
                               (when async? " [async]"))))]
        (str/join "\n" canonical)))))

(defn- delegate-list
  "Handle action=list: list available workflows and active runs."
  []
  (str "Available workflows:\n" (available-workflows-text)
       "\n\nActive runs:\n" (active-runs-text)))

(defn- delegate-run
  "Handle action=run: resolve workflow, create + execute canonical workflow run.
   Supports async (default) and sync modes, fork_session, and include_result_in_context."
  [{:keys [workflow prompt name mode fork_session include_result_in_context timeout_ms]}]
  (let [workflow-name (some-> workflow str str/trim not-empty)
        prompt-text   (some-> prompt str str/trim not-empty)
        mode*         (parse-mode mode)
        fork?         (true? fork_session)
        include?      (true? include_result_in_context)
        timeout       (or (when (number? timeout_ms) (long timeout_ms)) 300000)]
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
            workflow-input (cond-> {:input prompt-text
                                    :original prompt-text}
                             fork? (assoc :fork-session true))
            ;; Create the canonical workflow run
            create-result (mutate! 'psi.workflow/create-run
                                   {:definition-id workflow-name
                                    :workflow-input workflow-input
                                    :run-id run-name})
            run-id (:psi.workflow/run-id create-result)]
        (if-not run-id
          {:error (or (:psi.workflow/error create-result)
                      "Failed to create workflow run")}
          ;; Execute based on mode
          (case mode*
            :async
            (do
              (execute-async! run-id session-id workflow-name include?)
              {:ok true
               :run-id run-id
               :mode :async
               :status :running})

            :sync
            (do
              ;; Launch async then await completion
              (execute-async! run-id session-id workflow-name include?)
              (let [exec-result (await-run-completion run-id timeout)]
                (cond
                  (= :timeout (:psi.workflow/status exec-result))
                  {:error (str "Timed out waiting for workflow '" workflow-name "' after " timeout "ms")
                   :run-id run-id
                   :mode :sync}

                  (:psi.workflow/error exec-result)
                  {:error (:psi.workflow/error exec-result)
                   :run-id run-id
                   :mode :sync}

                  :else
                  {:ok true
                   :run-id run-id
                   :mode :sync
                   :status (:psi.workflow/status exec-result)
                   :result (:psi.workflow/result exec-result)})))))))))

(defn- find-run-summary
  [run-id]
  (let [result (mutate! 'psi.workflow/list-runs {})
        runs (:psi.workflow/runs result)]
    (some #(when (= run-id (:run-id %)) %) runs)))

(defn- continue-workflow-input
  [prompt-text]
  {:input prompt-text
   :original prompt-text})

(defn- continue-terminal-run-async!
  [run-id session-id prompt-text include?]
  (let [{:keys [source-definition-id]} (find-run-summary run-id)]
    (when-not source-definition-id
      (throw (ex-info "Cannot continue run without source definition" {:run-id run-id})))
    (let [new-run-name (str source-definition-id "-continue-" (System/currentTimeMillis))
          create-result (mutate! 'psi.workflow/create-run
                                 {:definition-id source-definition-id
                                  :workflow-input (continue-workflow-input prompt-text)
                                  :run-id new-run-name})
          new-run-id (:psi.workflow/run-id create-result)]
      (when-not new-run-id
        (throw (ex-info (or (:psi.workflow/error create-result)
                            "Failed to create continuation run")
                        {:run-id run-id
                         :definition-id source-definition-id})))
      (execute-async! new-run-id session-id source-definition-id include?)
      {:ok true
       :run-id new-run-id
       :status :running
       :continued-from run-id})))

(defn- continue-blocked-run-async!
  [run-id session-id prompt-text include?]
  (let [parent-session-id session-id
        fut (future
              (try
                (let [result (mutate! 'psi.workflow/resume-run
                                      {:run-id run-id
                                       :session-id session-id
                                       :workflow-input (continue-workflow-input prompt-text)})]
                  (when-not (:psi.workflow/error result)
                    (on-async-completion! run-id (str "resume-" run-id) parent-session-id include? result))
                  result)
                (catch Exception e
                  (notify! (str "Resume of run '" run-id "' failed: " (ex-message e)) :error)
                  (swap! active-runs dissoc run-id)
                  {:psi.workflow/error (ex-message e)})))]
    (swap! active-runs assoc run-id
           {:future fut
            :session-id session-id
            :parent-session-id parent-session-id
            :workflow (str "resume-" run-id)
            :started-at (System/currentTimeMillis)})
    {:ok true
     :run-id run-id
     :status :resuming}))

(defn- delegate-continue
  "Handle action=continue: push a stopped run forward with new prompt.

   - blocked runs: update workflow input and resume the existing run
   - terminal runs: create a fresh run from the original definition and execute it"
  [{:keys [id prompt include_result_in_context]}]
  (let [run-id (some-> id str str/trim not-empty)
        prompt-text (some-> prompt str str/trim not-empty)
        include? (true? include_result_in_context)]
    (cond
      (nil? run-id)
      {:error "id is required for continue"}

      (nil? prompt-text)
      {:error "prompt is required for continue"}

      :else
      (let [session-id (current-session-id)
            run-summary (find-run-summary run-id)
            status (:status run-summary)]
        (cond
          (nil? run-summary)
          {:error (str "Unknown run '" run-id "'")}

          (= :blocked status)
          (continue-blocked-run-async! run-id session-id prompt-text include?)

          (contains? #{:completed :failed :cancelled} status)
          (continue-terminal-run-async! run-id session-id prompt-text include?)

          :else
          {:error (str "Run '" run-id "' is not stopped; current status is " (name (or status :unknown)))})))))

(defn- delegate-remove
  "Handle action=remove: remove a run by id."
  [{:keys [id]}]
  (let [run-id (some-> id str str/trim not-empty)]
    (if (nil? run-id)
      {:error "id is required for remove"}
      (let [result (mutate! 'psi.workflow/remove-run {:run-id run-id})]
        (if (:psi.workflow/error result)
          {:error (:psi.workflow/error result)}
          (do
            (swap! active-runs dissoc run-id)
            (refresh-widgets!)
            {:ok true :run-id run-id}))))))

(defn- execute-delegate-tool
  "Main delegate tool execution dispatcher."
  [args _opts]
  (let [action (or (some-> (:action args) str str/lower-case str/trim) "run")]
    (case action
      "list"     (delegate-list)
      "run"      (let [result (delegate-run args)]
                   (if (:error result)
                     (str "Error: " (:error result))
                     (case (:mode result)
                       :async
                       (str "Workflow run " (:run-id result) " started asynchronously. "
                            "Use action=list to check progress.")

                       :sync
                       (str "Workflow run " (:run-id result) " — " (name (:status result))
                            (when (:result result)
                              (str "\n\n" (:result result))))

                       ;; fallback
                       (str "Workflow run " (:run-id result) " — " (name (or (:status result) :unknown))
                            (when (:result result)
                              (str "\n\n" (:result result)))))))
      "continue" (let [result (delegate-continue args)]
                   (if (:error result)
                     (str "Error: " (:error result))
                     (str "Resuming run " (:run-id result) " asynchronously.")))
      "remove"   (let [result (delegate-remove args)]
                   (if (:error result)
                     (str "Error: " (:error result))
                     (str "Removed run " (:run-id result))))
      (str "Unknown action: " action ". Use: run, list, continue, remove"))))

;;; Widget

(def ^:private widget-placement :bottom)

(defn- run-status-icon [status]
  (case status
    :pending "○"
    :running "▸"
    :blocked "◆"
    :completed "✓"
    :failed "✗"
    :cancelled "⊘"
    "?"))

(defn- run-widget-lines
  "Build display lines for a single workflow run."
  [run-id {:keys [workflow started-at]} run-info]
  (let [status (or (:status run-info) :running)
        elapsed (when started-at
                  (quot (- (System/currentTimeMillis) (long started-at)) 1000))
        source (or (:source-definition-id run-info) workflow)
        top-line (str (run-status-icon status)
                      " " run-id
                      (when source (str " · @" source))
                      (when elapsed (str " · " elapsed "s")))]
    [top-line]))

(defn- refresh-widgets!
  "Update widgets for all tracked and canonical workflow runs."
  []
  (when-let [ui (:ui @state)]
    (let [tracked @active-runs
          ;; Query canonical runs
          runs-result (try
                        (mutate! 'psi.workflow/list-runs {})
                        (catch Exception _ {:psi.workflow/runs []}))
          canonical-runs (:psi.workflow/runs runs-result)
          ;; Build run info map from canonical runs
          run-info-by-id (into {} (map (fn [r] [(:run-id r) r]) canonical-runs))
          ;; All known run-ids (tracked + canonical)
          all-run-ids (into (set (keys tracked)) (map :run-id canonical-runs))
          ;; Current widget ids
          current-wids (into #{} (map #(str "delegate-" %)) all-run-ids)
          old-wids (or (:widget-ids @state) #{})]
      ;; Clear removed widgets
      (doseq [wid (set/difference old-wids current-wids)]
        ((:clear-widget ui) wid))
      ;; Set/update widgets for active runs
      (doseq [run-id all-run-ids]
        (let [tracking (get tracked run-id)
              run-info (get run-info-by-id run-id)
              wid (str "delegate-" run-id)
              lines (run-widget-lines run-id
                                      (or tracking {})
                                      (or run-info {:status :running}))]
          ((:set-widget ui) wid widget-placement lines)))
      (swap! state assoc :widget-ids current-wids))))

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
         :query-session-fn (:query-session api)
         :mutate-fn (:mutate api)
         :mutate-session-fn (:mutate-session api)
         :log-fn (or (:log api) println)
         :notify-fn (or (:notify api) (fn [m _] (println m)))
         :ui (:ui api)
         :register-prompt-contribution
         (when-let [rpc (:register-prompt-contribution api)]
           rpc)
         :loaded-definitions {}
         :widget-ids #{})

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
