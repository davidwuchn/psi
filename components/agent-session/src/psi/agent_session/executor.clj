(ns psi.agent-session.executor
  "Bridges the ai streaming layer into the agent-core loop protocol.

   The executor is the missing link between:
     ai/stream-response  — delivers LLM token events via callback
     agent-core          — owns statechart + data model, expects callers
                           to drive begin-stream-in! / end-stream-in! etc.
     tools               — execute tool calls, return results

   One call to `run-turn!` drives a single LLM turn:
     1. Streams the LLM response via a per-turn statechart.
     2. On completion, checks for tool calls.
     3. For each tool call: executes the tool, records the result.
     4. If tools were called, recurses for the next turn.
     5. Returns when the LLM produces a stop response (no tools).

   Per-turn statechart (Step 6)
   ────────────────────────────
   Each streaming turn is driven by a statechart:
     :idle → :text-accumulating ⇄ :tool-accumulating → :done | :error

   Provider events are translated to statechart events.
   Accumulated text, tool calls, and final message are stored in a
   :turn-data atom — queryable via EQL from nREPL at any time.

   Message format translation
   ──────────────────────────
   ai/conversation uses the ai.schemas.Message shape (role, content block).
   agent-core uses plain maps ({:role string :content [...]}).
   The executor builds agent-core messages directly and keeps a parallel
   ai/conversation for the LLM call — the two are kept in sync."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [cheshire.core :as json]
   [psi.ai.core :as ai]
   [psi.ai.conversation :as conv]
   [psi.agent-core.core :as agent]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.tools :as tools]
   [psi.agent-session.turn-statechart :as turn-sc]))

;; ============================================================
;; ai conversation ↔ agent-core message translation
;; ============================================================

(defn- parse-args
  "Parse JSON tool arguments string into a map.
   Always returns a map — if the parsed value is not a map (e.g. string, array,
   nil) or parsing fails, returns {} so tool_use.input is always a valid dict."
  [arguments]
  (try
    (let [parsed (json/parse-string arguments)]
      (if (map? parsed) parsed {}))
    (catch Exception _ {})))

(defn- parse-tool-parameters
  "Parse tool parameters from pr-str'd string to a map, or return as-is if already a map."
  [params]
  (if (string? params) (edn/read-string params) params))

(defn- agent-messages->ai-conversation
  "Rebuild an ai/conversation from agent-core message history.
  Used to reconstruct the conversation context before each LLM call.
  Includes tool definitions so the Anthropic API can offer tool_use.

  Messages with :custom-type are extension transcript markers (e.g. PSL status
  messages) and are excluded — they must not be sent to the LLM because they
  can produce consecutive same-role messages that cause provider 400 errors."
  [system-prompt messages agent-tools]
  (let [conv (reduce
              (fn [conv msg]
                (if (:custom-type msg)
                  conv ;; skip extension transcript markers
                  (case (:role msg)
                    "user"
                    (conv/add-user-message
                     conv
                     (or (some #(when (= :text (:type %)) (:text %))
                               (:content msg))
                         (str (:content msg))))

                    "assistant"
                    (let [text-parts (keep #(when (= :text (:type %)) (:text %))
                                           (:content msg))
                          tool-calls (filter #(= :tool-call (:type %))
                                             (:content msg))
                          text       (str/join "\n" text-parts)]
                      (if (seq tool-calls)
                      ;; Structured content with tool calls
                        (conv/add-assistant-message
                         conv
                         {:content
                          {:kind   :structured
                           :blocks (vec
                                    (concat
                                     (when (seq text)
                                       [{:kind :text :text text}])
                                     (map (fn [tc]
                                            {:kind  :tool-call
                                             :id    (:id tc)
                                             :name  (:name tc)
                                             :input (parse-args (:arguments tc))})
                                          tool-calls)))}})
                      ;; Text only
                        (conv/add-assistant-message
                         conv
                         {:content {:kind :text :text text}})))

                    "toolResult"
                    (let [text (or (some #(when (= :text (:type %)) (:text %))
                                         (:content msg))
                                   "")]
                      (conv/add-tool-result conv
                                            (:tool-call-id msg)
                                            (:tool-name msg)
                                            {:kind :text :text text}
                                            (boolean (:is-error msg))))

                  ;; unknown roles — skip
                    conv)))
              (conv/create system-prompt)
              messages)]
    ;; Add agent tools to conversation so the provider includes them in the request
    (reduce (fn [c tool]
              (conv/add-tool c {:name        (:name tool)
                                :description (:description tool)
                                :parameters  (parse-tool-parameters (:parameters tool))}))
            conv
            agent-tools)))

;; ============================================================
;; Turn actions — agent-core integration on top of accumulation
;; ============================================================

(defn- emit-progress!
  "Emit a progress event to the progress queue (if provided).
   Events are maps with :type :agent-event and :event-kind."
  [progress-queue event]
  (when progress-queue
    (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
            (assoc event :type :agent-event))))

(defn- common-prefix-length
  "Return length of common prefix shared by strings `a` and `b`."
  [a b]
  (let [a*    (or a "")
        b*    (or b "")
        limit (min (count a*) (count b*))]
    (loop [idx 0]
      (if (and (< idx limit)
               (= (.charAt ^String a* idx)
                  (.charAt ^String b* idx)))
        (recur (inc idx))
        idx))))

(defn- merge-stream-text
  "Merge current streamed assistant text with incoming provider chunk.

Supports both provider styles:
- incremental deltas (append)
- cumulative snapshots (replace when incoming extends current)

Also tolerates cumulative snapshots that differ near previous tail
(e.g. trailing newline churn while total text grows)."
  [current incoming]
  (let [current*  (or current "")
        incoming* (or incoming "")]
    (cond
      (empty? incoming*) current*
      (empty? current*) incoming*
      (str/starts-with? incoming* current*)
      incoming*

      :else
      (let [cur-len    (count current*)
            in-len     (count incoming*)
            cp         (common-prefix-length current* incoming*)
            tail-close (and (> in-len cur-len)
                            (>= cp (max 1 (dec cur-len))))]
        (if tail-close
          incoming*
          (str current* incoming*))))))

(defn- complete-tool-calls
  [tool-calls]
  (->> tool-calls
       (sort-by key)
       (mapv (fn [[idx tc]]
               (cond-> tc
                 (nil? (:content-index tc)) (assoc :content-index idx))))))

(defn- parse-args-strict
  "Parse tool args strictly, preserving parse validity.
   Returns {:ok? true :value <map>} when JSON parses to a map,
   otherwise {:ok? false :value nil}."
  [arguments]
  (try
    (let [parsed (json/parse-string arguments)]
      (if (map? parsed)
        {:ok? true :value parsed}
        {:ok? false :value nil}))
    (catch Exception _
      {:ok? false :value nil})))

(defn- invalid-tool-call
  [tc]
  (let [id  (:id tc)
        nm  (:name tc)
        raw (:arguments tc)
        {:keys [ok?]} (parse-args-strict raw)]
    (cond
      (or (nil? id) (str/blank? (str id)))
      {:reason :missing-call-id
       :message "Tool call missing call_id; cannot execute reliably"
       :tool-call tc}

      (or (nil? nm) (str/blank? (str nm)))
      {:reason :missing-tool-name
       :message "Tool call missing name; cannot execute reliably"
       :tool-call tc}

      (not ok?)
      {:reason :invalid-arguments
       :message "Tool call arguments invalid; expected JSON object"
       :tool-call tc}

      :else nil)))

(defn- build-final-content
  [text-buffer tool-calls]
  (let [invalids     (keep invalid-tool-call tool-calls)
        valid-calls  (->> tool-calls (remove invalid-tool-call))
        text-blocks  (cond-> []
                       (seq text-buffer) (conj {:type :text :text text-buffer}))
        error-blocks (mapv (fn [invalid]
                             {:type :error :text (:message invalid)})
                           invalids)
        tool-blocks  (mapv (fn [tc]
                             {:type      :tool-call
                              :id        (:id tc)
                              :name      (:name tc)
                              :arguments (:arguments tc)})
                           valid-calls)]
    (-> text-blocks
        (into error-blocks)
        (into tool-blocks))))

(defn- emit-tool-ready-progress!
  [progress-queue tool-calls]
  (doseq [tc (->> tool-calls (remove invalid-tool-call))]
    (emit-progress! progress-queue
                    {:event-kind :tool-start
                     :tool-id    (:id tc)
                     :tool-name  (:name tc)
                     :arguments  (:arguments tc)
                     :parsed-args (:value (parse-args-strict (:arguments tc)))})))

(defn- emit-tool-assembly-errors!
  [progress-queue tool-calls]
  (doseq [invalid (keep invalid-tool-call tool-calls)]
    (emit-progress! progress-queue
                    {:event-kind :error
                     :error      (:message invalid)
                     :detail     (:reason invalid)
                     :error-code "tool-call/assembly-failed"})))

(defn- make-turn-actions
  "Create the actions-fn for the per-turn statechart.
   Handles both data accumulation (in turn-data atom) and agent-core
   lifecycle calls (begin-stream, update-stream, end-stream).
   When progress-queue is non-nil, emits :agent-event messages for TUI.

   Tool-call UI semantics are terminal-boundary only:
   - stream-time toolcall deltas/ends are accumulated but not emitted
   - when :on-done fires, validated calls emit a single logical :tool-start
   - invalid assembled tool-calls surface visible :error progress events"
  [agent-ctx done-p progress-queue]
  (fn [action-key data]
    (let [td (:turn-data data)]
      (case action-key
        :on-stream-start
        (agent/begin-stream-in! agent-ctx
                                {:role      "assistant"
                                 :content   [{:type :text :text ""}]
                                 :timestamp (java.time.Instant/now)})

        :on-text-delta
        (do (swap! td update :text-buffer merge-stream-text (:delta data))
            (emit-progress! progress-queue
                            {:event-kind :text-delta
                             :text       (:text-buffer @td)})
            (agent/update-stream-in! agent-ctx
                                     {:role      "assistant"
                                      :content   [{:type :text :text (:text-buffer @td)}]
                                      :timestamp (java.time.Instant/now)}))

        :on-toolcall-start
        (let [idx     (:content-index data)
              tc-id   (:tool-id data)
              tc-name (:tool-name data)]
          (swap! td update-in [:tool-calls idx]
                 (fn [cur]
                   (merge {:id nil :name nil :arguments "" :content-index idx}
                          cur
                          {:id (or tc-id (:id cur))
                           :name (or tc-name (:name cur))
                           :content-index idx}))))

        :on-toolcall-delta
        (let [idx   (:content-index data)
              delta (:delta data)]
          (swap! td update-in [:tool-calls idx]
                 (fn [cur]
                   (let [current-args (or (:arguments cur) "")]
                     (merge {:id nil :name nil :arguments "" :content-index idx}
                            cur
                            {:arguments (str current-args (or delta ""))
                             :content-index idx})))))

        :on-toolcall-end nil

        :on-done
        (let [{:keys [text-buffer tool-calls]} @td
              completed (complete-tool-calls tool-calls)
              content   (build-final-content text-buffer completed)
              usage     (:usage data)
              final     (cond-> {:role        "assistant"
                                 :content     content
                                 :stop-reason (or (:reason data) :stop)
                                 :timestamp   (java.time.Instant/now)}
                          (map? usage) (assoc :usage usage))]
          (emit-tool-ready-progress! progress-queue completed)
          (emit-tool-assembly-errors! progress-queue completed)
          (swap! td assoc :final-message final)
          (agent/end-stream-in! agent-ctx final)
          (deliver done-p final))

        :on-error
        (let [{:keys [text-buffer]} @td
              err-msg (:error-message data)
              content (cond-> []
                        (seq text-buffer) (conj {:type :text :text text-buffer})
                        :always           (conj {:type :error :text err-msg}))
              final (cond-> {:role          "assistant"
                             :content       content
                             :stop-reason   :error
                             :error-message err-msg
                             :timestamp     (java.time.Instant/now)}
                      (:http-status data) (assoc :http-status (:http-status data)))]
          (swap! td assoc :final-message final :error-message err-msg)
          (agent/end-stream-in! agent-ctx final)
          (deliver done-p final))

        :on-reset
        (reset! td (turn-sc/create-turn-data))

        ;; unknown — ignore
        nil))))

;; ============================================================
;; Single LLM turn
;; ============================================================

(defn- do-stream!
  "Invoke the appropriate streaming fn depending on whether ai-ctx is provided."
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (if ai-ctx
    (ai/stream-response-in ai-ctx ai-conv ai-model ai-options consume-fn)
    (ai/stream-response ai-conv ai-model ai-options consume-fn)))

(def ^:private llm-stream-idle-timeout-ms 120000)
(def ^:private llm-stream-wait-poll-ms 250)

(defn- now-ms []
  (System/currentTimeMillis))

(def ^:private provider-request-capture-limit 20)
(def ^:private provider-reply-capture-limit 400)

(defn- append-capped!
  [target-atom entry limit]
  (swap! target-atom
         (fn [entries]
           (let [entries* (conj (vec (or entries [])) entry)
                 n        (count entries*)]
             (if (> n limit)
               (subvec entries* (- n limit))
               entries*)))))

(defn- append-tool-call-attempt!
  [agent-session-ctx attempt]
  (when-let [attempts-atom (:tool-call-attempts-atom agent-session-ctx)]
    (swap! attempts-atom conj
           (assoc attempt :timestamp (java.time.Instant/now)))))

(defn- append-provider-request-capture!
  [agent-session-ctx capture]
  (let [entry (assoc capture :timestamp (java.time.Instant/now))]
    (if-let [requests-atom (:provider-requests-atom agent-session-ctx)]
      (append-capped! requests-atom entry provider-request-capture-limit)
      (when-let [session-data-atom (:session-data-atom agent-session-ctx)]
        (swap! session-data-atom
               update :provider-requests
               (fn [entries]
                 (let [entries* (conj (vec (or entries [])) entry)
                       n        (count entries*)]
                   (if (> n provider-request-capture-limit)
                     (subvec entries* (- n provider-request-capture-limit))
                     entries*))))))))

(defn- append-provider-reply-capture!
  [agent-session-ctx capture]
  (let [entry (assoc capture :timestamp (java.time.Instant/now))]
    (if-let [replies-atom (:provider-replies-atom agent-session-ctx)]
      (append-capped! replies-atom entry provider-reply-capture-limit)
      (when-let [session-data-atom (:session-data-atom agent-session-ctx)]
        (swap! session-data-atom
               update :provider-replies
               (fn [entries]
                 (let [entries* (conj (vec (or entries [])) entry)
                       n        (count entries*)]
                   (if (> n provider-reply-capture-limit)
                     (subvec entries* (- n provider-reply-capture-limit))
                     entries*))))))))

(defn- chain-callbacks
  [& callbacks]
  (let [callbacks* (vec (keep #(when (fn? %) %) callbacks))]
    (when (seq callbacks*)
      (fn [payload]
        (doseq [cb callbacks*]
          (try
            (cb payload)
            (catch Exception _
              nil)))))))

(defn- wait-for-turn-result
  "Wait for `done-p` with an idle timeout.

   The timeout window resets whenever `last-progress-ms` is updated by
   incoming provider events (text/thinking/tool/done/error).

   Optional opts:
   - :idle-timeout-ms
   - :wait-poll-ms"
  [done-p last-progress-ms {:keys [idle-timeout-ms wait-poll-ms]}]
  (let [poll-ms    (max 1 (long (or wait-poll-ms llm-stream-wait-poll-ms 250)))
        timeout-ms (max 1 (long (or idle-timeout-ms llm-stream-idle-timeout-ms 120000)))]
    (loop []
      (let [result (deref done-p poll-ms ::pending)]
        (cond
          (not= ::pending result)
          result

          (>= (- (now-ms) @last-progress-ms) timeout-ms)
          ::timeout

          :else
          (recur))))))

(defn- stream-turn!
  "Stream one LLM response into agent-core via the per-turn statechart.

   Creates a turn context (statechart + turn-data atom), sends :turn/start,
   then translates each provider event into a statechart event.  Blocks until
   the statechart reaches :done or :error.

   If `turn-ctx-atom` is non-nil, stores the turn context there so it can be
   queried live from nREPL.

   `extra-ai-options` — merged into the ai-options map sent to the provider
                        (e.g. {:api-key \"...\"})"
  [ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom extra-ai-options progress-queue]
  (let [data             (agent/get-data-in agent-ctx)
        turn-id          (str (java.util.UUID/randomUUID))
        system-prompt    (:system-prompt data)
        messages         (:messages data)
        agent-tools      (:tools data)
        ai-conv          (agent-messages->ai-conversation system-prompt messages agent-tools)
        base-ai-options  (or extra-ai-options {})
        ai-options       (-> base-ai-options
                             (assoc :on-provider-request
                                    (chain-callbacks
                                     (:on-provider-request base-ai-options)
                                     (fn [capture]
                                       (append-provider-request-capture!
                                        agent-session-ctx
                                        (assoc capture :turn-id turn-id)))))
                             (assoc :on-provider-response
                                    (chain-callbacks
                                     (:on-provider-response base-ai-options)
                                     (fn [capture]
                                       (append-provider-reply-capture!
                                        agent-session-ctx
                                        (assoc capture :turn-id turn-id))))))
        done-p           (promise)
        actions-fn       (make-turn-actions agent-ctx done-p progress-queue)
        turn-ctx         (turn-sc/create-turn-context actions-fn)
        last-progress-ms (atom (now-ms))]
    ;; Expose turn context for nREPL introspection
    (when turn-ctx-atom
      (reset! turn-ctx-atom turn-ctx))
    ;; Transition: :idle → :text-accumulating (calls begin-stream-in!)
    (turn-sc/send-event! turn-ctx :turn/start)
    ;; Start provider stream — callback translates events to statechart events
    (do-stream! ai-ctx ai-conv ai-model ai-options
                (fn [event]
                  (reset! last-progress-ms (now-ms))
                  (case (:type event)
                    :start nil ;; already transitioned via :turn/start

                    :text-delta
                    (turn-sc/send-event! turn-ctx :turn/text-delta
                                         {:delta (:delta event)})

                    :thinking-delta
                    (emit-progress! progress-queue
                                    {:event-kind :thinking-delta
                                     :text       (let [d (:delta event)]
                                                   (if (string? d)
                                                     d
                                                     (str (or d ""))))})

                    :toolcall-start
                    (do
                      (append-tool-call-attempt!
                       agent-session-ctx
                       {:turn-id       turn-id
                        :event-kind    :toolcall-start
                        :content-index (:content-index event)
                        :id            (:id event)
                        :name          (:name event)})
                      (turn-sc/send-event! turn-ctx :turn/toolcall-start
                                           {:content-index (:content-index event)
                                            :tool-id       (:id event)
                                            :tool-name     (:name event)}))

                    :toolcall-delta
                    (do
                      (append-tool-call-attempt!
                       agent-session-ctx
                       {:turn-id       turn-id
                        :event-kind    :toolcall-delta
                        :content-index (:content-index event)
                        :delta         (:delta event)})
                      (turn-sc/send-event! turn-ctx :turn/toolcall-delta
                                           {:content-index (:content-index event)
                                            :delta         (:delta event)}))

                    :toolcall-end
                    (do
                      (append-tool-call-attempt!
                       agent-session-ctx
                       {:turn-id       turn-id
                        :event-kind    :toolcall-end
                        :content-index (:content-index event)})
                      (turn-sc/send-event! turn-ctx :turn/toolcall-end
                                           {:content-index (:content-index event)}))

                    :done
                    (turn-sc/send-event! turn-ctx :turn/done
                                         {:reason (:reason event)
                                          :usage  (:usage event)})

                    :error
                    (turn-sc/send-event! turn-ctx :turn/error
                                         (cond-> {:error-message (:error-message event)}
                                           (:http-status event) (assoc :http-status (:http-status event))))

                    ;; :text-start :text-end :thinking-* — ignore
                    nil)))
    ;; Block until streaming completes (idle timeout resets on stream progress)
    (let [result (wait-for-turn-result
                  done-p
                  last-progress-ms
                  {:idle-timeout-ms (:llm-stream-idle-timeout-ms ai-options)
                   :wait-poll-ms    (:llm-stream-wait-poll-ms ai-options)})]
      (if (= result ::timeout)
        (let [timeout-msg {:role          "assistant"
                           :content       [{:type  :error
                                            :text  "Timeout waiting for LLM response"}]
                           :stop-reason   :error
                           :error-message "Timeout waiting for LLM response"
                           :timestamp     (java.time.Instant/now)}]
          ;; Drive statechart to :error so it's observable
          (turn-sc/send-event! turn-ctx :turn/error
                               {:error-message "Timeout waiting for LLM response"})
          timeout-msg)
        result))))

;; ============================================================
;; Tool execution for one turn
;; ============================================================

(defn- extract-tool-calls
  "Extract tool call specs from an assistant message's content."
  [assistant-msg]
  (filter #(= :tool-call (:type %)) (:content assistant-msg)))

(defn- effective-tool-output-policy
  [agent-session-ctx tool-name]
  (tool-output/effective-policy
   (or (:tool-output-overrides @(:session-data-atom agent-session-ctx)) {})
   tool-name))

(defn- utf8-bytes
  [s]
  (count (.getBytes (str (or s "")) "UTF-8")))

(defn- tool-content->text
  [content]
  (cond
    (string? content)
    content

    (sequential? content)
    (->> content
         (keep (fn [block]
                 (when (= :text (:type block))
                   (:text block))))
         (str/join "\n"))

    :else
    (str (or content ""))))

(defn- normalize-tool-content
  [content]
  (cond
    (sequential? content)
    (vec content)

    (string? content)
    [{:type :text :text content}]

    (nil? content)
    [{:type :text :text ""}]

    :else
    [{:type :text :text (str content)}]))

(defn- record-tool-output-stat!
  [agent-session-ctx {:keys [tool-call-id tool-name content details effective-policy]}]
  (let [truncation          (:truncation details)
        limit-hit?          (boolean (:truncated truncation))
        truncated-by        (or (:truncated-by truncation) :none)
        content-text        (tool-content->text content)
        output-bytes        (utf8-bytes content-text)
        context-bytes-added output-bytes
        stat                {:tool-call-id         tool-call-id
                             :tool-name            tool-name
                             :timestamp            (java.time.Instant/now)
                             :limit-hit            limit-hit?
                             :truncated-by         truncated-by
                             :effective-max-lines  (:max-lines effective-policy)
                             :effective-max-bytes  (:max-bytes effective-policy)
                             :output-bytes         output-bytes
                             :context-bytes-added  context-bytes-added}]
    (swap! (:tool-output-stats-atom agent-session-ctx)
           (fn [state]
             (-> state
                 (update :calls (fnil conj []) stat)
                 (update-in [:aggregates :total-context-bytes] (fnil + 0) context-bytes-added)
                 (update-in [:aggregates :by-tool tool-name] (fnil + 0) context-bytes-added)
                 (update-in [:aggregates :limit-hits-by-tool tool-name] (fnil + 0)
                            (if limit-hit? 1 0)))))))

(defn- execute-tool-with-registry
  "Execute a tool by name, preferring an :execute fn from the current
   agent tool registry when present. Falls back to built-in tools.

   Tool contract:
   - preferred: (fn [args-map opts-map] -> {:content string|blocks :is-error boolean})
   - legacy:    (fn [args-map] -> {:content string|blocks :is-error boolean})"
  [agent-ctx tool-name args opts]
  (let [tool-def   (some #(when (= tool-name (:name %)) %) (:tools (agent/get-data-in agent-ctx)))
        execute-fn (:execute tool-def)]
    (if (fn? execute-fn)
      (try
        (execute-fn args opts)
        (catch clojure.lang.ArityException _
          (execute-fn args)))
      (tools/execute-tool tool-name args opts))))

(defn- run-tool-call!
  "Execute one tool call, record the result in agent-core, return the result map."
  [agent-session-ctx tool-call progress-queue]
  (let [agent-ctx (:agent-ctx agent-session-ctx)
        call-id  (:id tool-call)
        name     (:name tool-call)
        args     (parse-args (:arguments tool-call))
        opts     {:cwd         (:cwd agent-session-ctx)
                  :overrides   (:tool-output-overrides @(:session-data-atom agent-session-ctx))
                  :tool-call-id call-id
                  :on-update   (fn [{:keys [content details is-error]}]
                                 (let [content-blocks (normalize-tool-content content)
                                       text-fallback  (tool-content->text content)]
                                   (emit-progress! progress-queue
                                                   {:event-kind   :tool-execution-update
                                                    :tool-id      call-id
                                                    :tool-name    name
                                                    :content      content-blocks
                                                    :result-text  text-fallback
                                                    :details      details
                                                    :is-error     (boolean is-error)})))}]
    (agent/emit-tool-start-in! agent-ctx tool-call)
    (emit-progress! progress-queue
                    {:event-kind  :tool-executing
                     :tool-id     call-id
                     :tool-name   name
                     :arguments   (:arguments tool-call)
                     :parsed-args args})
    (let [{:keys [content is-error details] :as tool-result}
          (try
            (execute-tool-with-registry agent-ctx name args opts)
            (catch Exception e
              {:content  (str "Error: " (ex-message e))
               :is-error true}))
          content-blocks (normalize-tool-content content)
          text-fallback  (tool-content->text content)
          policy         (effective-tool-output-policy agent-session-ctx name)
          result-msg     {:role         "toolResult"
                          :tool-call-id call-id
                          :tool-name    name
                          :content      content-blocks
                          :is-error     is-error
                          :details      details
                          :result-text  text-fallback
                          :timestamp    (java.time.Instant/now)}]
      (emit-progress! progress-queue
                      {:event-kind  :tool-result
                       :tool-id     call-id
                       :tool-name   name
                       :content     content-blocks
                       :result-text text-fallback
                       :details     details
                       :is-error    is-error})
      (record-tool-output-stat!
       agent-session-ctx
       {:tool-call-id     call-id
        :tool-name        name
        :content          content
        :details          details
        :effective-policy policy})
      (agent/emit-tool-end-in! agent-ctx tool-call tool-result is-error)
      (agent/record-tool-result-in! agent-ctx result-msg)
      result-msg)))

;; ============================================================
;; Public: run a full agent turn (recursive for tool loops)
;; ============================================================

(defn run-turn!
  "Drive one complete agent interaction loop:
     stream → check tools → execute tools → stream again (recursive).

   `ai-ctx`            — ai component context (has :provider-registry)
   `agent-session-ctx` — agent session context
   `agent-ctx`         — agent-core context
   `ai-model`          — ai.schemas.Model map
   `turn-ctx-atom`     — optional atom, stores current turn context for introspection
   `extra-ai-options`  — extra options merged into ai-options (e.g. {:api-key \"...\"})
   `progress-queue`    — optional LinkedBlockingQueue for TUI progress events

   Returns the final (non-tool) assistant message map.
   Emits agent-core events throughout."
  ([ai-ctx agent-session-ctx agent-ctx ai-model]
   (run-turn! ai-ctx agent-session-ctx agent-ctx ai-model nil nil nil))
  ([ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom]
   (run-turn! ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom nil nil))
  ([ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom extra-ai-options]
   (run-turn! ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom extra-ai-options nil))
  ([ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom extra-ai-options progress-queue]
   (agent/emit-in! agent-ctx {:type :turn-start})
   (let [assistant-msg (stream-turn! ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom
                                     extra-ai-options progress-queue)
         tool-calls    (extract-tool-calls assistant-msg)]
     (agent/emit-turn-end-in! agent-ctx assistant-msg [])
     (if (and (seq tool-calls) (not= :error (:stop-reason assistant-msg)))
       ;; Tool calls requested — execute them all then recurse
       (do
         (doseq [tc tool-calls]
           (run-tool-call! agent-session-ctx tc progress-queue))
         (run-turn! ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom
                    extra-ai-options progress-queue))
       ;; No tool calls (or error) — we're done
       assistant-msg))))

(defn- session-thinking-level
  [agent-session-ctx]
  (get @(:session-data-atom agent-session-ctx) :thinking-level))

(defn- session-llm-stream-idle-timeout-ms
  [agent-session-ctx]
  (let [v (get-in agent-session-ctx [:config :llm-stream-idle-timeout-ms])]
    (when (and (number? v) (pos? v))
      (long v))))

(defn run-agent-loop!
  "Run a complete agent loop starting from the current agent-core state.

   Sends :session/prompt to the session statechart externally (caller's job).
   This fn drives the internal turn loop and returns when the LLM stops
   requesting tool calls.

   Options (optional 5th arg map):
     :turn-ctx-atom  — atom to store the current turn context for EQL introspection
     :api-key        — API key to pass through to the provider (from OAuth store)
     :progress-queue — LinkedBlockingQueue for TUI progress events

   Timeout behavior:
     Idle timeout defaults to 120s and resets on any stream progress event.
     Session config key `:llm-stream-idle-timeout-ms` overrides that default.

   Returns the final assistant message."
  ([ai-ctx agent-session-ctx agent-ctx ai-model new-messages]
   (run-agent-loop! ai-ctx agent-session-ctx agent-ctx ai-model new-messages nil))
  ([ai-ctx agent-session-ctx agent-ctx ai-model new-messages {:keys [turn-ctx-atom api-key progress-queue]}]
   (agent/start-loop-in! agent-ctx new-messages)
   (let [thinking-level       (session-thinking-level agent-session-ctx)
         idle-timeout-ms      (session-llm-stream-idle-timeout-ms agent-session-ctx)
         extra-ai-options     (cond-> {}
                                api-key (assoc :api-key api-key)
                                (keyword? thinking-level) (assoc :thinking-level thinking-level)
                                idle-timeout-ms (assoc :llm-stream-idle-timeout-ms idle-timeout-ms))
         result (try
                  (run-turn! ai-ctx agent-session-ctx agent-ctx ai-model turn-ctx-atom
                             extra-ai-options progress-queue)
                  (catch Exception e
                    (cond-> {:role "assistant" :content []
                             :stop-reason :error
                             :error-message (ex-message e)
                             :timestamp (java.time.Instant/now)}
                      (:status (ex-data e)) (assoc :http-status (:status (ex-data e))))))]
     (if (= :error (:stop-reason result))
       (agent/end-loop-on-error-in! agent-ctx (:error-message result))
       (agent/end-loop-in! agent-ctx))
     result)))
