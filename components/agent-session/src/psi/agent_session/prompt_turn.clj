(ns psi.agent-session.prompt-turn
  "Single-turn prompt execution helpers.

   Canonical home for provider-stream execution, turn accumulation, and
   recursive tool-use turn progression in shared-session prompt paths."
  (:require
   [psi.agent-session.conversation :as conv-translate]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-stream :as prompt-stream]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.turn-accumulator :as accum]
   [psi.agent-session.turn-statechart :as turn-sc])
  (:import
   (java.util.concurrent Callable ExecutorService Future)
   (java.util.concurrent ConcurrentHashMap)
   (java.util.concurrent.locks ReentrantLock)))

(defn session-messages
  "Derive LLM conversation messages from the persistence journal."
  [ctx session-id]
  (prompt-request/session->provider-messages ctx session-id))

(defn session-tool-defs [session-data]
  (or (:tool-defs session-data)
      []))

(def ^:dynamic llm-stream-idle-timeout-ms prompt-stream/llm-stream-idle-timeout-ms)
(def ^:dynamic llm-stream-wait-poll-ms prompt-stream/llm-stream-wait-poll-ms)

(defn- now-ms []
  (prompt-stream/now-ms))

(defn do-stream!
  "Invoke the appropriate streaming fn depending on whether ai-ctx is provided."
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (prompt-stream/do-stream! ai-ctx ai-conv ai-model ai-options consume-fn))

(defn- chain-callbacks
  [& callbacks]
  (apply prompt-stream/chain-callbacks callbacks))

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
        ai-conv          (conv-translate/agent-messages->ai-conversation
                          (prompt-request/effective-system-prompt sd)
                          (session-messages ctx session-id)
                          (session-tool-defs sd)
                          {:cache-breakpoints (:cache-breakpoints sd)})
        base-ai-options  (or extra-ai-options {})
        ai-options       (-> base-ai-options
                             (assoc :on-provider-request
                                    (chain-callbacks
                                     (:on-provider-request base-ai-options)
                                     (fn [capture]
                                       (sa/append-provider-request-capture-in!
                                        ctx session-id (assoc capture :turn-id turn-id)))))
                             (assoc :on-provider-response
                                    (chain-callbacks
                                     (:on-provider-response base-ai-options)
                                     (fn [capture]
                                       (sa/append-provider-reply-capture-in!
                                        ctx session-id (assoc capture :turn-id turn-id))))))
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
    (let [call-action! (fn [action-key extra]
                         (actions-fn action-key (merge {:turn-data (:turn-data turn-ctx)} extra)))]
      (do-stream! ai-ctx ai-conv ai-model ai-options
                  (fn [event]
                    (when-not @timed-out?
                      (reset! last-progress-ms (now-ms))
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

(defn- extract-tool-calls [assistant-msg]
  (filter #(= :tool-call (:type %)) (:content assistant-msg)))

(defn- classify-turn-outcome
  "Classify a completed streamed message into :stop, :tool-use, or :error."
  [assistant-msg]
  (let [tool-calls (vec (extract-tool-calls assistant-msg))]
    (cond
      (= :error (:stop-reason assistant-msg))
      {:turn/outcome :turn.outcome/error :assistant-message assistant-msg}

      (seq tool-calls)
      {:turn/outcome :turn.outcome/tool-use :assistant-message assistant-msg :tool-calls tool-calls}

      :else
      {:turn/outcome :turn.outcome/stop :assistant-message assistant-msg})))

(defn- run-tool-call!
  "Dispatch one tool call through the runtime-effect boundary."
  [ctx session-id tool-call progress-queue]
  (dispatch/dispatch! ctx :session/tool-run
                      {:session-id     session-id
                       :tool-call      tool-call
                       :parsed-args    (or (:parsed-args tool-call)
                                           (conv-translate/parse-args (:arguments tool-call)))
                       :progress-queue progress-queue}
                      {:origin :core}))

(defn- tool-batch-executor [ctx]
  (or (:tool-batch-executor ctx)
      (throw (ex-info "No tool batch executor configured on ctx"
                      {:missing :tool-batch-executor}))))

(defn- tool-call-file-key
  "Return a canonical file-path key for a tool call, or nil if the call has no
   file argument. Checks the most common argument spellings across built-in
   tools (path, file, file_path) in both string and keyword forms."
  [tool-call]
  (let [args (or (:parsed-args tool-call)
                 (conv-translate/parse-args (:arguments tool-call)))]
    (some (fn [k] (when-let [v (get args k)]
                    (when (string? v) (not-empty v))))
          ["path" "file" "file_path" :path :file :file_path])))

(defn- acquire-file-lock!
  "Acquire the ReentrantLock for `file-key` from `lock-map`, creating one if absent."
  ^ReentrantLock [^ConcurrentHashMap lock-map file-key]
  (let [lock (or (.get lock-map file-key)
                 (let [new-lock (ReentrantLock.)]
                   (or (.putIfAbsent lock-map file-key new-lock) new-lock)))]
    (.lock lock)
    lock))

(defn- make-tool-call-task
  "Build a Callable for one tool call. When `file-key` is non-nil the task
   acquires the corresponding per-file lock before executing and releases it
   afterwards, serialising concurrent calls that target the same file."
  [ctx session-id tool-call progress-queue file-key lock-map]
  ^Callable
  (fn []
    (let [parsed-args (or (:parsed-args tool-call)
                          (conv-translate/parse-args (:arguments tool-call)))]
      (if file-key
        (let [^ReentrantLock lk (acquire-file-lock! lock-map file-key)]
          (try
            (dispatch/dispatch! ctx :session/tool-execute-prepared
                                {:session-id     session-id
                                 :tool-call      tool-call
                                 :parsed-args    parsed-args
                                 :progress-queue progress-queue}
                                {:origin :core})
            (finally (.unlock lk))))
        (dispatch/dispatch! ctx :session/tool-execute-prepared
                            {:session-id     session-id
                             :tool-call      tool-call
                             :parsed-args    parsed-args
                             :progress-queue progress-queue}
                            {:origin :core})))))

(defn- run-tool-calls!
  "Execute a batch of tool calls and return tool results in tool-call order.

   Tool calls that target the same file path are serialised via per-file locks
   so that concurrent reads/writes to the same file cannot interleave. Calls
   targeting different files (or no file at all) still execute in parallel up
   to the configured batch parallelism limit."
  [ctx session-id tool-calls progress-queue]
  (let [tool-calls* (vec tool-calls)
        task-count  (count tool-calls*)]
    (cond
      (zero? task-count)
      []

      (= 1 task-count)
      [(run-tool-call! ctx session-id (first tool-calls*) progress-queue)]

      :else
      (let [executor  ^ExecutorService (tool-batch-executor ctx)
            lock-map  (ConcurrentHashMap.)
            tasks     (mapv (fn [tc]
                              (make-tool-call-task ctx session-id tc progress-queue
                                                   (tool-call-file-key tc) lock-map))
                            tool-calls*)
            futures   (.invokeAll executor ^java.util.Collection tasks)]
        (mapv (fn [^Future future]
                (let [shaped-result (.get future)]
                  (dispatch/dispatch! ctx :session/tool-record-result
                                      {:session-id     session-id
                                       :shaped-result  shaped-result
                                       :progress-queue progress-queue}
                                      {:origin :core})))
              futures)))))

(defn execute-tool-calls!
  "Execute all tool calls from a tool-use outcome. Returns tool results."
  [ctx session-id outcome progress-queue]
  (run-tool-calls! ctx session-id (:tool-calls outcome) progress-queue))

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
