(ns psi.agent-session.prompt-runtime
  "Runtime prompt execution scaffold.

   This namespace is the effectful boundary for executing prepared requests.
   It now keeps only the prepared-request execution path and turn abort entry."
  (:require
   [psi.ai.models :as models]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-stream :as prompt-stream]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.turn-accumulator :as accum]
   [psi.agent-session.turn-statechart :as turn-sc]))

(defn do-stream!
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (prompt-stream/do-stream! ai-ctx ai-conv ai-model ai-options consume-fn))

(defn abort-active-turn-in!
  "Abort the currently active prepared-request turn for `session-id`, if any.
   Cancels the stream handle and forces an aborted terminal turn result." 
  [ctx session-id]
  (when-let [turn-ctx (sa/turn-context-in ctx session-id)]
    (prompt-stream/abort-turn! turn-ctx)
    true))

(defn capture-aware-ai-options
  "Wrap provider request/response callbacks so captures are recorded with the
   current `turn-id` while preserving any caller-supplied callbacks."
  [ctx session-id turn-id base-ai-options]
  (let [opts (or base-ai-options {})]
    (-> opts
        (assoc :on-provider-request
               (prompt-stream/chain-callbacks
                (:on-provider-request opts)
                (fn [capture]
                  (sa/append-provider-request-capture-in!
                   ctx session-id (assoc capture :turn-id turn-id)))))
        (assoc :on-provider-response
               (prompt-stream/chain-callbacks
                (:on-provider-response opts)
                (fn [capture]
                  (sa/append-provider-reply-capture-in!
                   ctx session-id (assoc capture :turn-id turn-id))))))))

(defn create-live-turn-context
  "Create and install the canonical live turn execution state used by prompt
   execution paths. Returns the working state needed to drive one provider
   stream to completion."
  [ctx session-id agent-ctx ai-model progress-queue turn-id]
  (let [done-p           (promise)
        thinking-buffers (atom {})
        actions-fn       (accum/make-turn-actions ctx session-id agent-ctx done-p progress-queue
                                                  ai-model thinking-buffers)
        turn-ctx         (turn-sc/create-turn-context actions-fn)
        _                (swap! (:turn-data turn-ctx) assoc :turn-id turn-id)
        last-progress-ms (atom (prompt-stream/now-ms))
        timed-out?       (atom false)]
    (sa/set-turn-context-in! ctx session-id turn-ctx)
    (turn-sc/send-event! turn-ctx :turn/start)
    {:done-p done-p
     :actions-fn actions-fn
     :turn-ctx turn-ctx
     :last-progress-ms last-progress-ms
     :timed-out? timed-out?}))

(defn- classify-execution-result
  [assistant-msg]
  (prompt-recording/classify-assistant-message assistant-msg))

(defn make-provider-event-consumer
  "Build the canonical provider stream event consumer used by prompt execution
   paths. Handles timestamp refresh, accumulation callbacks, statechart events,
   and optional cancellation checks."
  [turn-ctx actions-fn last-progress-ms timed-out? {:keys [cancelled-pred now-fn]}]
  (let [cancelled?    (or cancelled-pred (constantly false))
        now*          (or now-fn prompt-stream/now-ms)
        call-action!  (fn [action-key extra]
                        (actions-fn action-key (merge {:turn-data (:turn-data turn-ctx)} extra)))]
    (fn [event]
      (when-not (or @timed-out? (cancelled?))
        (reset! last-progress-ms (now*))
        (case (:type event)
          :start                    nil
          :text-start               (call-action! :on-text-start
                                                  {:content-index (:content-index event)})
          :text-delta               (turn-sc/send-event! turn-ctx :turn/text-delta
                                                         {:content-index (:content-index event)
                                                          :delta         (:delta event)})
          :text-end                 (call-action! :on-text-end
                                                  {:content-index (:content-index event)})
          :thinking-start           (call-action! :on-thinking-start
                                                  {:content-index (:content-index event)
                                                   :thinking      (:thinking event)
                                                   :signature     (:signature event)})
          :thinking-delta           (call-action! :on-thinking-delta
                                                  {:content-index (:content-index event)
                                                   :delta         (:delta event)})
          :thinking-signature-delta (call-action! :on-thinking-signature-delta
                                                  {:content-index (:content-index event)
                                                   :signature     (:signature event)})
          :thinking-end             (call-action! :on-thinking-end
                                                  {:content-index (:content-index event)})
          :toolcall-start           (turn-sc/send-event! turn-ctx :turn/toolcall-start
                                                         {:content-index (:content-index event)
                                                          :tool-id       (:id event)
                                                          :tool-name     (:name event)})
          :toolcall-delta           (turn-sc/send-event! turn-ctx :turn/toolcall-delta
                                                         {:content-index (:content-index event)
                                                          :delta         (:delta event)})
          :toolcall-end             (turn-sc/send-event! turn-ctx :turn/toolcall-end
                                                         {:content-index (:content-index event)})
          :done                     (turn-sc/send-event! turn-ctx :turn/done
                                                         {:reason (:reason event)
                                                          :usage  (:usage event)})
          :error                    (turn-sc/send-event! turn-ctx :turn/error
                                                         (cond-> {:error-message (:error-message event)}
                                                           (:http-status event) (assoc :http-status (:http-status event))))
          nil)))))

(defn execute-prepared-request!
  "Execute one prepared request through the existing turn-streaming runtime.
   Returns a shaped execution-result map."
  [ai-ctx ctx session-id agent-ctx prepared-request progress-queue]
  (let [turn-id          (:prepared-request/id prepared-request)
        ai-conv          (:prepared-request/provider-conversation prepared-request)
        ai-model         (or (:prepared-request/model prepared-request)
                             (:model (ss/get-session-data-in ctx session-id))
                             (models/get-model :sonnet-4.6))
        base-ai-options  (or (:prepared-request/ai-options prepared-request) {})
        {:keys [done-p actions-fn turn-ctx last-progress-ms timed-out?]}
        (create-live-turn-context ctx session-id agent-ctx ai-model progress-queue turn-id)
        ai-options       (capture-aware-ai-options ctx session-id turn-id base-ai-options)]
    (let [stream-handle (prompt-stream/mark-turn-stream-handle!
                         turn-ctx
                         (do-stream! ai-ctx ai-conv ai-model ai-options
                                     (make-provider-event-consumer
                                      turn-ctx actions-fn last-progress-ms timed-out?
                                      {:cancelled-pred #(prompt-stream/cancelled-stream-handle?
                                                         (:stream-handle @(:turn-data turn-ctx)))
                                       :now-fn prompt-stream/now-ms})))]
      (let [assistant-msg (let [result (prompt-stream/wait-for-turn-result done-p last-progress-ms
                                                                           {:idle-timeout-ms (:llm-stream-idle-timeout-ms ai-options)
                                                                            :wait-poll-ms    (:llm-stream-wait-poll-ms ai-options)
                                                                            :abort-pred      #(prompt-stream/cancelled-stream-handle? (:stream-handle @(:turn-data turn-ctx)))})]
                            (cond
                              (= result ::prompt-stream/timeout)
                              (do (reset! timed-out? true)
                                  (turn-sc/send-event! turn-ctx :turn/error {:error-message "Timeout waiting for LLM response"})
                                  {:role          "assistant"
                                   :content       [{:type :error :text "Timeout waiting for LLM response"}]
                                   :stop-reason   :error
                                   :error-message "Timeout waiting for LLM response"
                                   :timestamp     (java.time.Instant/now)})

                              (= result ::prompt-stream/aborted)
                              (do
                                (prompt-stream/abort-turn! turn-ctx)
                                (:final-message @(:turn-data turn-ctx)))

                              :else
                              result))
            _ (swap! (:turn-data turn-ctx) dissoc :stream-handle)
            outcome       (classify-execution-result assistant-msg)]
        {:execution-result/turn-id             turn-id
         :execution-result/session-id          session-id
         :execution-result/prepared-request-id turn-id
         :execution-result/model               ai-model
         :execution-result/assistant-message   assistant-msg
         :execution-result/usage               (:usage assistant-msg)
         :execution-result/provider-captures   {:request-captures []
                                                :response-captures []}
         :execution-result/turn-outcome        (:turn/outcome outcome)
         :execution-result/tool-calls          (:tool-calls outcome)
         :execution-result/error-message       (:error-message assistant-msg)
         :execution-result/http-status         (:http-status assistant-msg)
         :execution-result/stop-reason         (:stop-reason assistant-msg)}))))
