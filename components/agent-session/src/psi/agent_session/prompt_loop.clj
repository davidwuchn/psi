(ns psi.agent-session.prompt-loop
  "Agent loop lifecycle helpers.

   Canonical home for shared-session prompt loop lifecycle orchestration and
   terminal session-statechart completion."
  (:require
   [psi.agent-session.prompt-turn :as prompt-turn]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.statechart :as sc]))

(defn- session-thinking-level [ctx session-id]
  (:thinking-level (session/get-session-data-in ctx session-id)))

(defn- session-llm-stream-idle-timeout-ms [ctx]
  (let [v (get-in ctx [:config :llm-stream-idle-timeout-ms])]
    (when (and (number? v) (pos? v)) (long v))))

(defn agent-loop-options
  "Build effective AI options from session state and runtime opts."
  [ctx session-id {:keys [api-key]}]
  (let [thinking-level  (session-thinking-level ctx session-id)
        idle-timeout-ms (session-llm-stream-idle-timeout-ms ctx)]
    (cond-> {}
      api-key                   (assoc :api-key api-key)
      (keyword? thinking-level) (assoc :thinking-level thinking-level)
      idle-timeout-ms           (assoc :llm-stream-idle-timeout-ms idle-timeout-ms))))

(defn run-agent-loop-body!
  "Execute the turn loop, converting uncaught exceptions to error messages."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (try
    (prompt-turn/run-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)
    (catch Throwable e
      (cond-> {:role          "assistant"
               :content       []
               :stop-reason   :error
               :error-message (or (ex-message e) (.getMessage e) (str e))
               :timestamp     (java.time.Instant/now)}
        (:status (ex-data e)) (assoc :http-status (:status (ex-data e)))))))

(defn finish-agent-loop!
  "Send :agent-end to the session statechart (skipped for child sessions)."
  [ctx session-id _agent-ctx result]
  (when (not= :agent (:spawn-mode (session/get-session-data-in ctx session-id)))
    (let [sc-env (:sc-env ctx)
          sc-sid (session/sc-session-id-in ctx session-id)]
      (when (and sc-env sc-sid)
        (sc/send-event! sc-env sc-sid
                        :session/agent-event
                        {:pending-agent-event {:type     :agent-end
                                               :messages (prompt-turn/session-messages ctx session-id)}}))))
  result)

(defn run-agent-loop!
  "Run a complete agent loop from current session state.

   Callers are responsible for journaling `new-messages` before calling this
   function. Drives turns until terminal, then finalizes the session statechart.

   Options (optional map):
     :api-key        — OAuth API key passed through to the provider
     :progress-queue — LinkedBlockingQueue for TUI progress events

   Returns the final assistant message."
  ([ai-ctx ctx session-id agent-ctx ai-model new-messages]
   (run-agent-loop! ai-ctx ctx session-id agent-ctx ai-model new-messages nil))
  ([ai-ctx ctx session-id agent-ctx ai-model _new-messages opts]
   (let [extra-ai-options (agent-loop-options ctx session-id opts)
         result           (run-agent-loop-body! ai-ctx ctx session-id agent-ctx ai-model
                                                extra-ai-options (:progress-queue opts))]
     (finish-agent-loop! ctx session-id agent-ctx result))))
