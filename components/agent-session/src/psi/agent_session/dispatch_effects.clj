(ns psi.agent-session.dispatch-effects
  "Effect executor for the dispatch pipeline.
   Dispatches on :effect/type via defmulti.

   All back-references to core.clj private helpers are routed through ctx
   keys (the same callback-on-ctx pattern already used for :run-tool-call-fn).
   This avoids a circular ns dependency between dispatch-effects and core."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.user-config :as user-cfg]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as sc]))

(defmulti execute-effect!
  "Execute one dispatch effect description.
   Effect maps have shape {:effect/type kw ...payload-keys}.
   Returns nil for unknown effect types."
  (fn [_ctx effect] (:effect/type effect)))

(defmethod execute-effect! :default [_ctx _effect] nil)

;;; runtime/agent-* — delegate to agent-core (skipped for child sessions without agent-ctx)

(defmethod execute-effect! :runtime/agent-abort [ctx _effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/abort-in! ac)))

(defmethod execute-effect! :runtime/agent-queue-steering [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/queue-steering-in! ac (:message effect))))

(defmethod execute-effect! :runtime/agent-queue-follow-up [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/queue-follow-up-in! ac (:message effect))))

(defmethod execute-effect! :runtime/agent-clear-steering-queue [ctx _effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/clear-steering-queue-in! ac)))

(defmethod execute-effect! :runtime/agent-clear-follow-up-queue [ctx _effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/clear-follow-up-queue-in! ac)))

(defmethod execute-effect! :runtime/agent-start-loop [ctx _effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/start-loop-in! ac [])))

(defmethod execute-effect! :runtime/agent-start-loop-with-messages [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/start-loop-in! ac (vec (:messages effect)))))

(defmethod execute-effect! :runtime/agent-set-model [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/set-model-in! ac (:model effect))))

(defmethod execute-effect! :runtime/agent-set-thinking-level [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/set-thinking-level-in! ac (:level effect))))

(defmethod execute-effect! :runtime/agent-set-system-prompt [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/set-system-prompt-in! ac (:prompt effect))))

(defmethod execute-effect! :runtime/agent-set-tools [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/set-tools-in! ac (:tool-maps effect))))

(defmethod execute-effect! :runtime/agent-reset [ctx _effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/reset-agent-in! ac)))

(defmethod execute-effect! :runtime/agent-replace-messages [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/replace-messages-in! ac (vec (:messages effect)))))

(defmethod execute-effect! :runtime/agent-append-message [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/append-message-in! ac (:message effect))))

(defmethod execute-effect! :runtime/agent-emit [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/emit-in! ac (:event effect))))

(defmethod execute-effect! :runtime/agent-emit-tool-start [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/emit-tool-start-in! ac (:tool-call effect))))

(defmethod execute-effect! :runtime/agent-emit-tool-end [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)]
    (agent/emit-tool-end-in! ac (:tool-call effect) (:result effect) (:is-error? effect))))

(defmethod execute-effect! :runtime/agent-record-tool-result [ctx effect]
  (when-let [ac (ss/agent-ctx-in ctx)] (agent/record-tool-result-in! ac (:tool-result-msg effect))))

;;; runtime/tool-* — tool execution boundary

(defmethod execute-effect! :runtime/tool-execute [ctx effect]
  (try
    ((:execute-tool-runtime-fn ctx) ctx
                                    (:tool-name effect)
                                    (:args effect)
                                    (:opts effect))
    (catch Exception e
      {:content  (str "Error: " (ex-message e))
       :is-error true})))

(defmethod execute-effect! :runtime/tool-run [ctx effect]
  ((or (:run-tool-call-fn ctx)
       (fn [_ _]
         (throw (ex-info "No runtime tool runner configured"
                         {:effect/type :runtime/tool-run}))))
   ctx effect))

;;; Background job effects — via ctx callbacks

(defmethod execute-effect! :runtime/mark-workflow-jobs-terminal [ctx _effect]
  ((:mark-workflow-jobs-terminal-fn ctx) ctx))

(defmethod execute-effect! :runtime/emit-background-job-terminal-messages [ctx _effect]
  ((:emit-background-job-terminal-messages-fn ctx) ctx))

(defmethod execute-effect! :runtime/reconcile-and-emit-background-job-terminals [ctx _effect]
  ((:reconcile-and-emit-background-job-terminals-fn ctx) ctx))

;;; Event queue

(defmethod execute-effect! :runtime/event-queue-offer [ctx effect]
  (when-let [q (:event-queue ctx)]
    (.offer ^java.util.concurrent.LinkedBlockingQueue q (:event effect))))

;;; System prompt — via ctx callback

(defmethod execute-effect! :runtime/refresh-system-prompt [ctx _effect]
  ((:refresh-system-prompt-fn ctx) ctx))

;;; Scheduling

(defmethod execute-effect! :runtime/schedule-thread-sleep-send-event [ctx effect]
  ((:daemon-thread-fn ctx)
   (fn []
     (Thread/sleep ^long (:delay-ms effect))
     (sc/send-event! (:sc-env ctx) (ss/sc-session-id-in ctx) (:event effect)))))

;;; Statechart

(defmethod execute-effect! :statechart/send-event [ctx effect]
  (sc/send-event! (:sc-env ctx) (ss/sc-session-id-in ctx) (:event effect)))

;;; persist/* — journal and preferences

(defmethod execute-effect! :persist/journal-append-model-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (persist/model-entry (:provider effect) (:model-id effect))))

(defmethod execute-effect! :persist/journal-append-message-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (persist/message-entry (:message effect))))

(defmethod execute-effect! :persist/journal-append-thinking-level-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (persist/thinking-level-entry (:level effect))))

(defmethod execute-effect! :persist/journal-append-session-info-entry [ctx effect]
  ((:journal-append-fn ctx) ctx (persist/session-info-entry (:name effect))))

(defmethod execute-effect! :persist/project-prefs-update [ctx effect]
  (try
    (project-prefs/update-agent-session!
     ((:effective-cwd-fn ctx) ctx)
     (:prefs effect))
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
  (let [reason      (:reason effect)
        will-retry? (boolean (:will-retry? effect))
        continue?   (atom false)
        reg         (:extension-registry ctx)]
    (ext/dispatch-in reg "auto_compaction_start" {:reason reason})
    (when will-retry?
      ((:drop-trailing-overflow-error-fn ctx) ctx))
    ((:daemon-thread-fn ctx)
     (fn []
       (try
         (let [result ((:execute-compaction-fn ctx) ctx nil)]
           (if result
             (do
               (ext/dispatch-in reg "auto_compaction_end"
                                {:result     result
                                 :aborted    false
                                 :will-retry will-retry?})
               (let [sd (ss/get-session-data-in ctx)]
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
           (sc/send-event! (:sc-env ctx) (ss/sc-session-id-in ctx)
                           :session/compact-done)
           (when @continue?
             (sc/send-event! (:sc-env ctx) (ss/sc-session-id-in ctx) :session/prompt)
             (agent/start-loop-in! (ss/agent-ctx-in ctx) []))))))))

(defmethod execute-effect! :runtime/auto-compact-workflow [ctx effect]
  (execute-auto-compact-workflow! ctx effect))
