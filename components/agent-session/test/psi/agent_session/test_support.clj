(ns psi.agent-session.test-support
  "Helpers for canonical-root-backed agent-session test contexts."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-handlers :as dispatch-handlers]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as session-sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.workflows :as wf]
   [psi.ui.state :as ui-state]))

(def ^:private session-scoped-keys
  "Keys that are stored per-session and require a session id."
  #{:session-data :provider-error-replies
    :journal :flush-state :turn-ctx
    :tool-output-stats :tool-call-attempts :tool-lifecycle-events
    :provider-requests :provider-replies})

(defn- resolve-state-path
  "Resolve the state path for key k, using the first context session id for
   session-scoped keys."
  [ctx k]
  (if (session-scoped-keys k)
    (let [session-id (some-> (ss/list-context-sessions-in ctx) first :session-id)]
      (ss/state-path k session-id))
    (ss/state-path k)))

(defn set-state!
  "Test-only canonical root-state setter.
   Keeps low-level state mutation localized to test support rather than test bodies."
  [ctx k value]
  (ss/assoc-state-value-in! ctx (resolve-state-path ctx k) value)
  ctx)

(defn update-state!
  "Test-only canonical root-state updater.
   Keeps low-level state mutation localized to test support rather than test bodies."
  [ctx k f & args]
  (apply ss/update-state-value-in! ctx (resolve-state-path ctx k) f args)
  ctx)

(defn make-session-ctx
  "Create a minimal canonical-root-backed session-like context for tests.
   Returns [ctx session-id] where session-id is the initial session id,
   matching the convention of session/create-context.
   Accepts overrides:
   - :state map merged into canonical root
   - :session-data map merged into [:agent-session :data]
   - :agent-ctx custom agent ctx"
  [{:keys [state session-data agent-ctx]}]
  (let [agent-ctx*    (or agent-ctx (agent/create-context))
        sc-session-id (java.util.UUID/randomUUID)
        initial-sd    (merge (assoc (session-data/initial-session {})
                                    :provider-error-replies []
                                    :ephemeral-seed? true)
                             (or session-data {}))
        sid           (:session-id initial-sd)
        base-state    {:agent-session {:sessions {sid {:data          initial-sd
                                                       :agent-ctx     agent-ctx*
                                                       :sc-session-id sc-session-id
                                                       :telemetry {:tool-output-stats {:calls []
                                                                                       :aggregates {:total-context-bytes 0
                                                                                                    :by-tool {}
                                                                                                    :limit-hits-by-tool {}}}
                                                                   :tool-call-attempts []
                                                                   :tool-lifecycle-events []
                                                                   :provider-requests []
                                                                   :provider-replies []}
                                                       :persistence {:journal []
                                                                     :flush-state {:flushed? false :session-file nil}}
                                                       :turn {:ctx nil}}}}
                       :background-jobs {:store (bg-jobs/empty-state)}
                       :ui {:extension-ui @(ui-state/create-ui-state)}}
        state*               (atom (merge base-state (or state {})))
        ext-reg       (ext/create-registry)
        wf-reg        (wf/create-registry)
        sc-env        (session-sc/create-sc-env)
        dispatch-statechart-event-fn dispatch-handlers/dispatch-statechart-event-in!
        run-tool-call-fn (fn [ctx {:keys [session-id tool-call parsed-args progress-queue]}]
                           (executor/run-tool-call-through-runtime-effect!
                            ctx session-id tool-call parsed-args progress-queue))
        ctx           {:state*                       state*
                       :sc-env                       sc-env
                       :config                       {}
                       :session-defaults             (or session-data {})
                       :extension-registry           ext-reg
                       :workflow-registry            wf-reg
                       :extension-run-fn-atom        (atom nil)
                       :apply-root-state-update-fn   ss/apply-root-state-update-in!
                       :read-session-state-fn        ss/get-state-value-in
                       :execute-dispatch-effect-fn   (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
                       :dispatch-statechart-event-fn dispatch-statechart-event-fn
                       :runtime-tool-executor-fn     tool-plan/default-execute-runtime-tool-in!
                       :execute-tool-runtime-fn      #'tool-plan/execute-tool-runtime-in!
                       :run-tool-call-fn             run-tool-call-fn
                       :persist?                     false
                       :send-extension-message-fn    (fn
                                                       ([ctx role content custom-type]
                                                        (let [msg {:role      role
                                                                   :content   [{:type :text :text (str content)}]
                                                                   :timestamp (java.time.Instant/now)}
                                                              msg (cond-> msg
                                                                    custom-type (assoc :custom-type custom-type))
                                                              session-id (some-> (ss/list-context-sessions-in ctx) first :session-id)]
                                                          (dispatch/dispatch! ctx
                                                                              :session/send-extension-message
                                                                              {:session-id session-id :message msg}
                                                                              {:origin :core})
                                                          msg))
                                                       ([ctx session-id role content custom-type]
                                                        (let [msg {:role      role
                                                                   :content   [{:type :text :text (str content)}]
                                                                   :timestamp (java.time.Instant/now)}
                                                              msg (cond-> msg
                                                                    custom-type (assoc :custom-type custom-type))]
                                                          (dispatch/dispatch! ctx
                                                                              :session/send-extension-message
                                                                              {:session-id session-id :message msg}
                                                                              {:origin :core})
                                                          msg)))
                       :mark-workflow-jobs-terminal-fn bg-rt/maybe-mark-workflow-jobs-terminal!
                       :emit-background-job-terminal-messages-fn bg-rt/maybe-emit-background-job-terminal-messages!
                       :reconcile-and-emit-background-job-terminals-fn bg-rt/reconcile-and-emit-background-job-terminals-in!
                       :daemon-thread-fn             (fn [f] (doto (Thread. ^Runnable f) (.setDaemon true) (.start)))
                       :effective-cwd-fn             (fn [ctx session-id] (ss/effective-cwd-in ctx session-id))
                       :journal-append-fn            (fn [_ctx _session-id _entry] nil)}
        _             (dispatch-handlers/register-all! ctx)
        actions-fn     (dispatch-handlers/make-actions-fn ctx)
        ctx            (assoc ctx :session-actions-fn actions-fn)]
    (session-sc/start-session! sc-env sc-session-id
                               {:ctx        ctx
                                :session-id sid
                                :actions-fn actions-fn
                                :config     {}})
    [ctx sid]))

(defn create-test-session
  "Create a full session context with a real (non-ephemeral) first session.
   Returns [ctx session-id] — same shape as create-context but the session-id
   is a real session created via new-session-in!, not an ephemeral seed.

   Accepts the same options as session/create-context. The :initial-session
   overrides flow through :session-defaults into the first real session."
  ([] (create-test-session {:persist? false}))
  ([opts]
   (let [[ctx _] (session-core/create-context opts)
         sd      (session-core/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))
