(ns psi.agent-session.prompt-turn
  "Direct-path prompt turn orchestration.

   Owns recursive tool-use progression while delegating canonical request
   preparation, live turn execution, and assistant-message journaling to the
   shared prompt runtime path."
  (:require
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.tool-batch :as tool-batch]))

(defn stream-turn!
  "Stream one LLM response into agent-core via the per-turn statechart.
   Blocks until the statechart reaches :done or :error.
   Stores turn context in canonical state for nREPL introspection."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (:execution-result/assistant-message
   (prompt-runtime/execute-prepared-request-and-journal!
    ai-ctx ctx session-id
    (prompt-request/build-prepared-request
     ctx session-id
     {:turn-id       (str (java.util.UUID/randomUUID))
      :user-message  nil
      :runtime-opts  extra-ai-options
      :runtime-model ai-model})
    progress-queue)))

(defn run-turn-loop!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [assistant-message (stream-turn! ai-ctx ctx session-id agent-ctx ai-model
                                        extra-ai-options progress-queue)
        outcome           (prompt-recording/classify-assistant-message assistant-message)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do (tool-batch/run-tool-calls! ctx session-id (:tool-calls outcome) progress-queue)
          (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model
                          extra-ai-options progress-queue))

      assistant-message)))

