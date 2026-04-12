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
   [psi.agent-session.tool-batch :as tool-batch]))

(defn- now-ms []
  (prompt-stream/now-ms))

(defn stream-turn!
  "Stream one LLM response into agent-core via the per-turn statechart.
   Blocks until the statechart reaches :done or :error.
   Stores turn context in canonical state for nREPL introspection."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [sd               (session/get-session-data-in ctx session-id)
        turn-id          (str (java.util.UUID/randomUUID))
        ai-conv          (prompt-request/build-provider-conversation
                          sd
                          (prompt-request/session->provider-messages ctx session-id))
        base-ai-options  (or extra-ai-options {})
        {:keys [done-p actions-fn turn-ctx last-progress-ms timed-out?]}
        (prompt-runtime/create-live-turn-context ctx session-id agent-ctx ai-model progress-queue turn-id)
        ai-options       (prompt-runtime/capture-aware-ai-options ctx session-id turn-id base-ai-options)]
    (prompt-runtime/do-stream! ai-ctx ai-conv ai-model ai-options
                               (prompt-runtime/make-provider-event-consumer
                                turn-ctx actions-fn last-progress-ms timed-out?
                                {:now-fn now-ms}))
    (let [assistant-msg (prompt-runtime/await-assistant-message!
                         turn-ctx done-p last-progress-ms timed-out?
                         {:idle-timeout-ms (:llm-stream-idle-timeout-ms ai-options)
                          :wait-poll-ms    (:llm-stream-wait-poll-ms ai-options)})]
      (session/journal-append-in! ctx session-id (persist/message-entry assistant-msg))
      assistant-msg)))

(defn execute-one-turn!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [assistant-msg (stream-turn! ai-ctx ctx session-id agent-ctx ai-model
                                    extra-ai-options progress-queue)]
    {:assistant-message assistant-msg
     :outcome           (prompt-recording/classify-assistant-message assistant-msg)}))

(defn run-turn-loop!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [{:keys [assistant-message outcome]}
        (execute-one-turn! ai-ctx ctx session-id agent-ctx ai-model
                           extra-ai-options progress-queue)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do (tool-batch/execute-tool-calls! ctx session-id outcome progress-queue)
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
