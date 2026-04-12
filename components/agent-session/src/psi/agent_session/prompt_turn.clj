(ns psi.agent-session.prompt-turn
  "Single-turn prompt execution helpers.

   Canonical home for provider-stream execution, turn accumulation, and
   recursive tool-use turn progression in shared-session prompt paths."
  (:require
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.prompt-stream :as prompt-stream]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.tool-batch :as tool-batch]
   [psi.agent-session.turn-accumulator :as accum]
   [psi.agent-session.turn-statechart :as turn-sc]))

(defn session-messages
  "Derive LLM conversation messages from the persistence journal."
  [ctx session-id]
  (prompt-request/session->provider-messages ctx session-id))

(def ^:dynamic llm-stream-idle-timeout-ms prompt-stream/llm-stream-idle-timeout-ms)
(def ^:dynamic llm-stream-wait-poll-ms prompt-stream/llm-stream-wait-poll-ms)

(defn- now-ms []
  (prompt-stream/now-ms))

(defn do-stream!
  "Invoke the appropriate streaming fn depending on whether ai-ctx is provided."
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (prompt-stream/do-stream! ai-ctx ai-conv ai-model ai-options consume-fn))

(defn- wait-for-turn-result
  "Wait for `done-p` with an idle timeout that resets on any stream progress."
  [done-p last-progress-ms {:keys [idle-timeout-ms wait-poll-ms abort-pred]}]
  (let [opts   (cond-> {:idle-timeout-ms llm-stream-idle-timeout-ms
                        :wait-poll-ms    llm-stream-wait-poll-ms}
                 idle-timeout-ms (assoc :idle-timeout-ms idle-timeout-ms)
                 wait-poll-ms    (assoc :wait-poll-ms wait-poll-ms)
                 abort-pred      (assoc :abort-pred abort-pred))
        result (prompt-stream/wait-for-turn-result done-p last-progress-ms opts)]
    (case result
      ::prompt-stream/timeout ::timeout
      ::prompt-stream/aborted ::aborted
      result)))

(defn stream-turn!
  "Stream one LLM response into agent-core via the per-turn statechart.
   Blocks until the statechart reaches :done or :error.
   Stores turn context in canonical state for nREPL introspection."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [sd               (session/get-session-data-in ctx session-id)
        turn-id          (str (java.util.UUID/randomUUID))
        ai-conv          (prompt-request/build-provider-conversation
                          sd
                          (session-messages ctx session-id))
        base-ai-options  (or extra-ai-options {})
        ai-options       (prompt-runtime/capture-aware-ai-options ctx session-id turn-id base-ai-options)
        done-p           (promise)
        thinking-buffers (atom {})
        actions-fn       (accum/make-turn-actions ctx session-id agent-ctx done-p progress-queue
                                                  ai-model thinking-buffers)
        turn-ctx         (turn-sc/create-turn-context actions-fn)
        _                (swap! (:turn-data turn-ctx) assoc :turn-id turn-id)
        last-progress-ms (atom (now-ms))
        timed-out?       (atom false)]
    (sa/set-turn-context-in! ctx session-id turn-ctx)
    (turn-sc/send-event! turn-ctx :turn/start)
    (do-stream! ai-ctx ai-conv ai-model ai-options
                (prompt-runtime/make-provider-event-consumer
                 turn-ctx actions-fn last-progress-ms timed-out?
                 {:now-fn now-ms}))
    (let [result (wait-for-turn-result done-p last-progress-ms
                                       {:idle-timeout-ms (:llm-stream-idle-timeout-ms ai-options)
                                        :wait-poll-ms    (:llm-stream-wait-poll-ms ai-options)})
          assistant-msg (if (= result ::timeout)
                          (do (reset! timed-out? true)
                              (turn-sc/send-event! turn-ctx :turn/error {:error-message "Timeout waiting for LLM response"})
                              {:role          "assistant"
                               :content       [{:type :error :text "Timeout waiting for LLM response"}]
                               :stop-reason   :error
                               :error-message "Timeout waiting for LLM response"
                               :timestamp     (java.time.Instant/now)})
                          result)]
      (session/journal-append-in! ctx session-id (persist/message-entry assistant-msg))
      assistant-msg)))

(defn- classify-turn-outcome
  "Classify a completed streamed message into :stop, :tool-use, or :error."
  [assistant-msg]
  (prompt-recording/classify-assistant-message assistant-msg))

(defn execute-tool-calls!
  "Execute all tool calls from a tool-use outcome. Returns tool results."
  [ctx session-id outcome progress-queue]
  (tool-batch/execute-tool-calls! ctx session-id outcome progress-queue))

(defn execute-one-turn!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [assistant-msg (stream-turn! ai-ctx ctx session-id agent-ctx ai-model
                                    extra-ai-options progress-queue)]
    {:assistant-message assistant-msg
     :outcome           (classify-turn-outcome assistant-msg)}))

(defn run-turn-loop!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [{:keys [assistant-message outcome]}
        (execute-one-turn! ai-ctx ctx session-id agent-ctx ai-model
                           extra-ai-options progress-queue)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do (execute-tool-calls! ctx session-id outcome progress-queue)
          (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model
                          extra-ai-options progress-queue))

      assistant-message)))

(defn run-turn!
  "Drive one complete agent interaction loop until the LLM produces a terminal response."
  ([ai-ctx ctx session-id agent-ctx ai-model]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model nil nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
   (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)))
