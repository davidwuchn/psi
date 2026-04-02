(ns psi.agent-session.executor
  "Orchestrates the agent turn loop — stream one LLM turn, execute tool calls,
   recurse until terminal.

   Delegates to:
   - psi.agent-session.conversation   — agent-core ↔ ai/conversation translation
   - psi.agent-session.turn-accumulator — streaming accumulation + turn actions
   - psi.agent-session.tool-execution  — tool call execution pipeline

   Public API:
   - run-agent-loop!                   — run the full loop from current state
   - run-tool-call-through-runtime-effect! — runtime-effect boundary for tool dispatch"
  (:require
   [psi.ai.core :as ai]
   [psi.agent-session.conversation :as conv-translate]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.tool-execution :as tool-exec]
   [psi.agent-session.turn-accumulator :as accum]
   [psi.agent-session.turn-statechart :as turn-sc]))

;; Re-export public tool-execution entry point so callers keep a single require.
(def run-tool-call-through-runtime-effect!
  tool-exec/run-tool-call-through-runtime-effect!)

;; ============================================================
;; Session-data reads
;; ============================================================

(defn- session-messages
  "Derive LLM conversation messages from the persistence journal."
  [ctx session-id]
  (into []
        (keep (fn [entry]
                (when (= :message (:kind entry))
                  (get-in entry [:data :message]))))
        (sa/journal-state-in ctx session-id)))

(defn- session-tool-schemas [session-data]
  (or (:tool-schemas session-data) []))

;; ============================================================
;; Single LLM turn
;; ============================================================

(defn- do-stream!
  "Invoke the appropriate streaming fn depending on whether ai-ctx is provided."
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (if ai-ctx
    (ai/stream-response-in ai-ctx ai-conv ai-model ai-options consume-fn)
    (ai/stream-response ai-conv ai-model ai-options consume-fn)))

(def ^:private llm-stream-idle-timeout-ms 600000)
(def ^:private llm-stream-wait-poll-ms 250)

(defn- now-ms [] (System/currentTimeMillis))

(defn- chain-callbacks
  [& callbacks]
  (let [callbacks* (vec (keep #(when (fn? %) %) callbacks))]
    (when (seq callbacks*)
      (fn [payload]
        (doseq [cb callbacks*]
          (try (cb payload) (catch Exception _ nil)))))))

(defn- wait-for-turn-result
  "Wait for `done-p` with an idle timeout that resets on any stream progress."
  [done-p last-progress-ms {:keys [idle-timeout-ms wait-poll-ms]}]
  (let [poll-ms    (max 1 (long (or wait-poll-ms llm-stream-wait-poll-ms)))
        timeout-ms (max 1 (long (or idle-timeout-ms llm-stream-idle-timeout-ms)))]
    (loop []
      (let [result (deref done-p poll-ms ::pending)]
        (cond
          (not= ::pending result) result
          (>= (- (now-ms) @last-progress-ms) timeout-ms) ::timeout
          :else (recur))))))

(defn- stream-turn!
  "Stream one LLM response into agent-core via the per-turn statechart.
   Blocks until the statechart reaches :done or :error.
   Stores turn context in canonical state for nREPL introspection."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [sd               (session/get-session-data-in ctx session-id)
        turn-id          (str (java.util.UUID/randomUUID))
        ai-conv          (conv-translate/agent-messages->ai-conversation
                          (:system-prompt sd)
                          (session-messages ctx session-id)
                          (session-tool-schemas sd)
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
        ;; Per-content-index thinking buffers for merge-stream-text accumulation.
        thinking-buffers (atom {})
        actions-fn       (accum/make-turn-actions ctx session-id agent-ctx done-p progress-queue
                                                  ai-model thinking-buffers)
        turn-ctx         (turn-sc/create-turn-context actions-fn)
        _                (swap! (:turn-data turn-ctx) assoc :turn-id turn-id)
        last-progress-ms (atom (now-ms))
        timed-out?       (atom false)]
    (sa/set-turn-context-in! ctx session-id turn-ctx)
    (turn-sc/send-event! turn-ctx :turn/start)
    ;; State-transition events → turn-sc/send-event!
    ;; Accumulation-only events → actions-fn directly (statechart has no transitions for them)
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
                                        :wait-poll-ms    (:llm-stream-wait-poll-ms ai-options)})]
      (if (= result ::timeout)
        (do (reset! timed-out? true)
            (turn-sc/send-event! turn-ctx :turn/error {:error-message "Timeout waiting for LLM response"})
            {:role          "assistant"
             :content       [{:type :error :text "Timeout waiting for LLM response"}]
             :stop-reason   :error
             :error-message "Timeout waiting for LLM response"
             :timestamp     (java.time.Instant/now)})
        result))))

;; ============================================================
;; Turn loop
;; ============================================================

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

(defn- run-tool-calls!
  "Execute a batch of tool calls and return tool results in tool-call order."
  [ctx session-id tool-calls progress-queue]
  (mapv (fn [tc] (run-tool-call! ctx session-id tc progress-queue))
        tool-calls))

(defn- execute-tool-calls!
  "Execute all tool calls from a tool-use outcome. Returns tool results."
  [ctx session-id outcome progress-queue]
  (run-tool-calls! ctx session-id (:tool-calls outcome) progress-queue))

(defn- execute-one-turn!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [assistant-msg (stream-turn! ai-ctx ctx session-id agent-ctx ai-model
                                    extra-ai-options progress-queue)]
    {:assistant-message assistant-msg
     :outcome           (classify-turn-outcome assistant-msg)}))

(defn- run-turn-loop!
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [{:keys [assistant-message outcome]}
        (execute-one-turn! ai-ctx ctx session-id agent-ctx ai-model
                           extra-ai-options progress-queue)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do (execute-tool-calls! ctx session-id outcome progress-queue)
          (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model
                          extra-ai-options progress-queue))

      ;; :stop or :error — return terminal message
      assistant-message)))

(defn run-turn!
  "Drive one complete agent interaction loop until the LLM produces a terminal response.

   `ai-ctx`           — ai component context (has :provider-registry)
   `ctx`              — agent session context
   `session-id`       — target session id
   `agent-ctx`        — agent-core context
   `ai-model`         — ai.schemas.Model map
   `extra-ai-options` — extra options merged into ai-options (e.g. {:api-key \"...\"})
   `progress-queue`   — optional LinkedBlockingQueue for TUI progress events"
  ([ai-ctx ctx session-id agent-ctx ai-model]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model nil nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
   (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue)))

;; ============================================================
;; Agent loop lifecycle
;; ============================================================

(defn- session-thinking-level [ctx session-id]
  (:thinking-level (session/get-session-data-in ctx session-id)))

(defn- session-llm-stream-idle-timeout-ms [ctx]
  (let [v (get-in ctx [:config :llm-stream-idle-timeout-ms])]
    (when (and (number? v) (pos? v)) (long v))))

(defn- agent-loop-options
  "Build effective AI options from session state and runtime opts."
  [ctx session-id {:keys [api-key]}]
  (let [thinking-level  (session-thinking-level ctx session-id)
        idle-timeout-ms (session-llm-stream-idle-timeout-ms ctx)]
    (cond-> {}
      api-key                   (assoc :api-key api-key)
      (keyword? thinking-level) (assoc :thinking-level thinking-level)
      idle-timeout-ms           (assoc :llm-stream-idle-timeout-ms idle-timeout-ms))))

(defn- run-agent-loop-body!
  "Execute the turn loop, converting uncaught exceptions to error messages."
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

(defn- finish-agent-loop!
  "Send :agent-end to the session statechart (skipped for child sessions)."
  [ctx session-id _agent-ctx result]
  (when (not= :agent (:spawn-mode (session/get-session-data-in ctx session-id)))
    (let [sc-env (:sc-env ctx)
          sc-sid (session/sc-session-id-in ctx session-id)]
      (when (and sc-env sc-sid)
        (sc/send-event! sc-env sc-sid
                        :session/agent-event
                        {:pending-agent-event {:type     :agent-end
                                               :messages (session-messages ctx session-id)}}))))
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
