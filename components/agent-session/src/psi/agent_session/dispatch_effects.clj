(ns psi.agent-session.dispatch-effects
  "Effect executor for the dispatch pipeline.
   Dispatches on :effect/type via defmulti.

   All back-references to core.clj private helpers are routed through ctx
   keys. This avoids a circular ns dependency between dispatch-effects and core."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.user-config :as user-cfg]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.tool-defs :as tool-defs]))

(defmulti execute-effect!
  "Execute one dispatch effect description.
   Effect maps have shape {:effect/type kw ...payload-keys}.
   Returns nil for unknown effect types."
  (fn [_ctx effect] (:effect/type effect)))

(defmethod execute-effect! :default [_ctx _effect] nil)

(defn- effect-session-id
  "Resolve the session-id for an effect.  Effects carry :session-id
   explicitly — the effect interceptor in dispatch.clj injects it from
   the event when the handler didn't set it."
  [_ctx effect]
  (:session-id effect))

(defn- effect-agent-ctx
  [ctx effect]
  (ss/agent-ctx-in ctx (effect-session-id ctx effect)))

(defn- effect-sc-session-id
  [ctx effect]
  (ss/sc-session-id-in ctx (effect-session-id ctx effect)))

(defn drop-trailing-overflow-error!
  "Remove a trailing overflow-error assistant message from the agent context.
   Used by the auto-compaction effect path before retrying after overflow."
  [ctx session-id]
  (let [ac          (ss/agent-ctx-in ctx session-id)
        messages    (:messages (agent/get-data-in ac))
        last-msg    (last messages)
        stop-reason (or (:stop-reason last-msg) (:stopReason last-msg))
        err         (str (or (:error-message last-msg) ""))]
    (when (and (map? last-msg)
               (or (= :error stop-reason) (= "error" stop-reason))
               (session-data/context-overflow-error? err))
      (agent/replace-messages-in! ac (vec (butlast messages))))))

;;; runtime/agent-* — delegate to agent-core (skipped for child sessions without agent-ctx)

(defmethod execute-effect! :runtime/agent-abort [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/abort-in! ac)))

(defmethod execute-effect! :runtime/agent-queue-steering [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/queue-steering-in! ac (:message effect))))

(defmethod execute-effect! :runtime/agent-queue-follow-up [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/queue-follow-up-in! ac (:message effect))))

(defmethod execute-effect! :runtime/agent-clear-steering-queue [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/clear-steering-queue-in! ac)))

(defmethod execute-effect! :runtime/agent-clear-follow-up-queue [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/clear-follow-up-queue-in! ac)))

(defmethod execute-effect! :runtime/agent-drain-follow-up-queue [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)]
    (agent/drain-follow-up-in! ac (:messages effect))))

(defmethod execute-effect! :runtime/agent-start-loop [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/start-loop-in! ac [])))

(defmethod execute-effect! :runtime/agent-start-loop-with-messages [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/start-loop-in! ac (vec (:messages effect)))))

(defmethod execute-effect! :runtime/agent-set-model [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/set-model-in! ac (:model effect))))

(defmethod execute-effect! :runtime/agent-set-thinking-level [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/set-thinking-level-in! ac (:level effect))))

(defmethod execute-effect! :runtime/agent-set-system-prompt [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/set-system-prompt-in! ac (:prompt effect))))

(defmethod execute-effect! :runtime/agent-set-tools [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)]
    (agent/set-tools-in! ac (tool-defs/agent-core-tools (:tool-maps effect)))))

(defmethod execute-effect! :runtime/agent-reset [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/reset-agent-in! ac)))

(defmethod execute-effect! :runtime/agent-replace-messages [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/replace-messages-in! ac (vec (:messages effect)))))

(defmethod execute-effect! :runtime/agent-append-message [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/append-message-in! ac (:message effect))))

(defmethod execute-effect! :runtime/agent-emit [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/emit-in! ac (:event effect))))

(defmethod execute-effect! :runtime/agent-emit-tool-start [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/emit-tool-start-in! ac (:tool-call effect))))

(defmethod execute-effect! :runtime/agent-emit-tool-end [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)]
    (agent/emit-tool-end-in! ac (:tool-call effect) (:result effect) (:is-error? effect))))

(defmethod execute-effect! :runtime/agent-record-tool-result [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/record-tool-result-in! ac (:tool-result-msg effect))))

;;; runtime/tool-* — tool execution boundary

(defmethod execute-effect! :runtime/tool-execute [ctx effect]
  (try
    (try
      ((:execute-tool-runtime-fn ctx) ctx
                                      (:session-id effect)
                                      (:tool-name effect)
                                      (:args effect)
                                      (:opts effect))
      (catch clojure.lang.ArityException _
        ((:execute-tool-runtime-fn ctx) ctx
                                        (:tool-name effect)
                                        (:args effect)
                                        (:opts effect))))
    (catch Exception e
      {:content  (str "Error: " (ex-message e))
       :is-error true})))

(defmethod execute-effect! :runtime/prompt-execute-and-record [ctx effect]
  (let [session-id       (effect-session-id ctx effect)
        prepared-request (:prepared-request effect)
        progress-queue   (:progress-queue effect)
        agent-ctx        (effect-agent-ctx ctx effect)
        execution-result ((:execute-prepared-request-fn ctx)
                          (:ai-ctx ctx)
                          ctx
                          session-id
                          agent-ctx
                          prepared-request
                          progress-queue)]
    (dispatch/dispatch! ctx
                        :session/prompt-record-response
                        {:session-id       session-id
                         :execution-result execution-result
                         :progress-queue   progress-queue}
                        {:origin :core})
    execution-result))

(defmethod execute-effect! :runtime/prompt-continue-chain [ctx effect]
  ((:continue-prompt-chain-fn ctx)
   ctx
   (effect-session-id ctx effect)
   (:execution-result effect)
   (:progress-queue effect)))

(defmethod execute-effect! :runtime/dispatch-event [ctx effect]
  (dispatch/dispatch! ctx
                      (:event-type effect)
                      (or (:event-data effect) {})
                      {:origin (or (:origin effect) :core)}))

(defmethod execute-effect! :runtime/dispatch-event-with-effect-result [ctx effect]
  (dispatch/dispatch! ctx
                      (:event-type effect)
                      (or (:event-data effect) {})
                      {:origin (or (:origin effect) :core)}))

;;; Background job effects — via ctx callbacks

(defmethod execute-effect! :runtime/mark-workflow-jobs-terminal [ctx _effect]
  ((:mark-workflow-jobs-terminal-fn ctx) ctx))

(defmethod execute-effect! :runtime/emit-background-job-terminal-messages [ctx effect]
  ((:emit-background-job-terminal-messages-fn ctx) ctx (effect-session-id ctx effect)))

(defmethod execute-effect! :runtime/reconcile-and-emit-background-job-terminals [ctx effect]
  ((:reconcile-and-emit-background-job-terminals-fn ctx) ctx (effect-session-id ctx effect)))

;;; Event queue

(defmethod execute-effect! :runtime/event-queue-offer [ctx effect]
  (when-let [q (:event-queue ctx)]
    (.offer ^java.util.concurrent.LinkedBlockingQueue q (:event effect))))

;;; System prompt — via ctx callback

(defmethod execute-effect! :runtime/refresh-system-prompt [ctx effect]
  (let [session-id (effect-session-id ctx effect)]
    ((:refresh-system-prompt-fn ctx) ctx session-id)))

;;; Scheduling

(defmethod execute-effect! :runtime/schedule-thread-sleep-send-event [ctx effect]
  (let [sc-session-id (effect-sc-session-id ctx effect)]
    ((:daemon-thread-fn ctx)
     (fn []
       (Thread/sleep ^long (:delay-ms effect))
       (sc/send-event! (:sc-env ctx) sc-session-id (:event effect))))))

;;; Statechart

(defmethod execute-effect! :statechart/send-event [ctx effect]
  (sc/send-event! (:sc-env ctx) (effect-sc-session-id ctx effect) (:event effect)))

;;; persist/* — journal and preferences

(defmethod execute-effect! :persist/journal-append-model-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (effect-session-id ctx effect) (persist/model-entry (:provider effect) (:model-id effect))))

(defmethod execute-effect! :persist/journal-append-message-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (effect-session-id ctx effect) (persist/message-entry (:message effect))))

(defmethod execute-effect! :persist/journal-append-thinking-level-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (effect-session-id ctx effect) (persist/thinking-level-entry (:level effect))))

(defmethod execute-effect! :persist/journal-append-session-info-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (effect-session-id ctx effect) (persist/session-info-entry (:name effect))))

(defmethod execute-effect! :persist/project-prefs-update [ctx effect]
  (try
    (let [session-id (effect-session-id ctx effect)]
      (project-prefs/update-agent-session!
       ((:effective-cwd-fn ctx) ctx session-id)
       (:prefs effect)))
    (catch Exception _
      nil)))

(defmethod execute-effect! :persist/user-config-update [_ctx effect]
  (try
    (user-cfg/update-agent-session! (:prefs effect))
    (catch Exception _
      nil)))

;;; Extension dispatch

(defmethod execute-effect! :notify/extension-dispatch [ctx effect]
  (ext/dispatch-in (:extension-registry ctx)
                   (:event-name effect)
                   (:payload effect)))

;;; Auto-compaction — extracted to helper due to size

(defn- execute-auto-compact-workflow!
  [ctx effect]
  (let [session-id  (effect-session-id ctx effect)
        sc-sid      (effect-sc-session-id ctx effect)
        reason      (:reason effect)
        will-retry? (boolean (:will-retry? effect))
        continue?   (atom false)
        reg         (:extension-registry ctx)]
    (ext/dispatch-in reg "auto_compaction_start" {:reason reason})
    (when will-retry?
      ((:drop-trailing-overflow-error-fn ctx) ctx session-id))
    ((:daemon-thread-fn ctx)
     (fn []
       (try
         (let [result ((:execute-compaction-fn ctx) ctx session-id nil)]
           (if result
             (do
               (ext/dispatch-in reg "auto_compaction_end"
                                {:result     result
                                 :aborted    false
                                 :will-retry will-retry?})
               (let [sd (ss/get-session-data-in ctx session-id)]
                 (when (or will-retry?
                           (seq (:steering-messages sd))
                           (seq (:follow-up-messages sd)))
                   (reset! continue? true))))
             (ext/dispatch-in reg "auto_compaction_end"
                              {:result     nil
                               :aborted    true
                               :will-retry false})))
         (catch Exception e
           (ext/dispatch-in reg "auto_compaction_end"
                            {:result        nil
                             :aborted       false
                             :will-retry    false
                             :error-message (ex-message e)}))
         (finally
           (sc/send-event! (:sc-env ctx) sc-sid
                           :session/compact-done)
           (when @continue?
             (sc/send-event! (:sc-env ctx) sc-sid :session/prompt)
             (when-let [ac (ss/agent-ctx-in ctx session-id)]
               (agent/start-loop-in! ac [])))))))))

(defmethod execute-effect! :runtime/auto-compact-workflow [ctx effect]
  (execute-auto-compact-workflow! ctx effect))
