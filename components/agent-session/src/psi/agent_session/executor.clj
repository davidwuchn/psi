(ns psi.agent-session.executor
  "Compatibility façade for the legacy agent loop namespace.

   Prompt streaming and loop ownership now live in:
   - psi.agent-session.prompt-turn — single-turn streaming + recursive turn loop
   - psi.agent-session.prompt-loop — agent loop lifecycle orchestration

   This namespace remains only to preserve existing callers and test seams while
   the remaining executor-coupled tests migrate to the focused namespaces."
  (:require
   [psi.agent-session.prompt-loop :as prompt-loop]
   [psi.agent-session.prompt-stream :as prompt-stream]
   [psi.agent-session.prompt-turn :as prompt-turn]))

(def ^:dynamic llm-stream-idle-timeout-ms prompt-turn/llm-stream-idle-timeout-ms)
(def ^:dynamic llm-stream-wait-poll-ms prompt-turn/llm-stream-wait-poll-ms)

(defn do-stream!
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (prompt-stream/do-stream! ai-ctx ai-conv ai-model ai-options consume-fn))

(defn- with-prompt-turn-compat [f]
  (with-redefs [prompt-turn/do-stream! do-stream!]
    (binding [prompt-turn/llm-stream-idle-timeout-ms llm-stream-idle-timeout-ms
              prompt-turn/llm-stream-wait-poll-ms    llm-stream-wait-poll-ms]
      (f))))

(defn stream-turn!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (with-prompt-turn-compat
    #(prompt-turn/stream-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)))

(defn execute-tool-calls!
  [ctx session-id outcome progress-queue]
  (prompt-turn/execute-tool-calls! ctx session-id outcome progress-queue))

(defn execute-one-turn!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (with-prompt-turn-compat
    #(prompt-turn/execute-one-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)))

(defn run-turn-loop!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [{:keys [assistant-message outcome]}
        (execute-one-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do (execute-tool-calls! ctx session-id outcome progress-queue)
          (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue))

      assistant-message)))

(defn run-turn!
  ([ai-ctx ctx session-id agent-ctx ai-model]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model nil nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
   (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)))

(defn run-agent-loop-body!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (try
    (run-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)
    (catch Throwable e
      (cond-> {:role          "assistant"
               :content       []
               :stop-reason   :error
               :error-message (or (ex-message e) (.getMessage e) (str e))
               :timestamp     (java.time.Instant/now)}
        (:status (ex-data e)) (assoc :http-status (:status (ex-data e)))))))

(defn finish-agent-loop!
  [ctx session-id agent-ctx result]
  (prompt-loop/finish-agent-loop! ctx session-id agent-ctx result))

(defn run-agent-loop!
  ([ai-ctx ctx session-id agent-ctx ai-model new-messages]
   (run-agent-loop! ai-ctx ctx session-id agent-ctx ai-model new-messages nil))
  ([ai-ctx ctx session-id agent-ctx ai-model _new-messages opts]
   (let [extra-ai-options (prompt-loop/agent-loop-options ctx session-id opts)
         result           (run-agent-loop-body! ai-ctx ctx session-id agent-ctx ai-model
                                                extra-ai-options (:progress-queue opts))]
     (finish-agent-loop! ctx session-id agent-ctx result))))
