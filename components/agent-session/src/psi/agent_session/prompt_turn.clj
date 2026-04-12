(ns psi.agent-session.prompt-turn
  "Single-turn prompt execution helpers.

   Canonical home for provider-stream execution, turn accumulation, and
   recursive tool-use turn progression in shared-session prompt paths."
  (:require
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.tool-batch :as tool-batch]))

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
        assistant-msg    (:assistant-message
                          (prompt-runtime/execute-live-turn!
                           ai-ctx ctx session-id agent-ctx
                           {:ai-conv         ai-conv
                            :ai-model        ai-model
                            :base-ai-options base-ai-options
                            :progress-queue  progress-queue
                            :turn-id         turn-id}))]
    (session/journal-append-in! ctx session-id (persist/message-entry assistant-msg))
    assistant-msg))

(defn run-turn-loop!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [assistant-message (stream-turn! ai-ctx ctx session-id agent-ctx ai-model
                                        extra-ai-options progress-queue)
        outcome           (prompt-recording/classify-assistant-message assistant-message)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do (tool-batch/execute-tool-calls! ctx session-id outcome progress-queue)
          (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model
                          extra-ai-options progress-queue))

      assistant-message)))

