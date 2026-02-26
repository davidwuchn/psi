(ns psi.agent-session.executor
  "Bridges the ai streaming layer into the agent-core loop protocol.

   The executor is the missing link between:
     ai/stream-response  — delivers LLM token events via callback
     agent-core          — owns statechart + data model, expects callers
                           to drive begin-stream-in! / end-stream-in! etc.
     tools               — execute tool calls, return results

   One call to `run-turn!` drives a single LLM turn:
     1. Streams the LLM response, feeding agent-core's streaming state.
     2. On completion, checks for tool calls.
     3. For each tool call: executes the tool, records the result.
     4. If tools were called, recurses for the next turn.
     5. Returns when the LLM produces a stop response (no tools).

   The executor is stateless — all state lives in agent-core's context.
   It receives an :ai-ctx (for provider access) and an :agent-ctx.

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
   [psi.agent-session.tools :as tools]))

;; ============================================================
;; ai conversation ↔ agent-core message translation
;; ============================================================

(defn- parse-args
  "Parse JSON tool arguments string into a map."
  [arguments]
  (try
    (json/parse-string arguments)
    (catch Exception _ {})))

(defn- parse-tool-parameters
  "Parse tool parameters from pr-str'd string to a map, or return as-is if already a map."
  [params]
  (if (string? params) (edn/read-string params) params))

(defn- agent-messages->ai-conversation
  "Rebuild an ai/conversation from agent-core message history.
  Used to reconstruct the conversation context before each LLM call.
  Includes tool definitions so the Anthropic API can offer tool_use."
  [system-prompt messages agent-tools]
  (let [conv (reduce
              (fn [conv msg]
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
                  conv))
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
;; Single LLM turn
;; ============================================================

(defn- do-stream!
  "Invoke the appropriate streaming fn depending on whether ai-ctx is provided."
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (if ai-ctx
    (ai/stream-response-in ai-ctx ai-conv ai-model ai-options consume-fn)
    (ai/stream-response ai-conv ai-model ai-options consume-fn)))

(defn- stream-turn!
  "Stream one LLM response into agent-core, returning the final assistant message map.
  Captures both text and tool-call content from the provider stream.
  Calls agent-core lifecycle fns: begin-stream-in!, update-stream-in!, end-stream-in!."
  [ai-ctx agent-ctx ai-model]
  (let [data          (agent/get-data-in agent-ctx)
        system-prompt (:system-prompt data)
        messages      (:messages data)
        agent-tools   (:tools data)
        ai-conv       (agent-messages->ai-conversation system-prompt messages agent-tools)
        ai-options    {}
        text-buf      (StringBuilder.)
        ;; Track tool calls by content-index → {:id :name :input-buf}
        tool-calls-state (atom {})
        partial-msg   {:role      "assistant"
                       :content   [{:type :text :text ""}]
                       :timestamp (java.time.Instant/now)}
        _             (agent/begin-stream-in! agent-ctx partial-msg)
        done-p        (promise)]
    (do-stream! ai-ctx ai-conv ai-model ai-options
                (fn [event]
                  (case (:type event)
                    :text-delta
                    (do (.append text-buf ^String (:delta event))
                        (agent/update-stream-in!
                         agent-ctx
                         {:role      "assistant"
                          :content   [{:type :text :text (str text-buf)}]
                          :timestamp (java.time.Instant/now)}))

                    :toolcall-start
                    (swap! tool-calls-state assoc (:content-index event)
                           {:id (:id event) :name (:name event)
                            :input-buf (StringBuilder.)})

                    :toolcall-delta
                    (when-let [tc (get @tool-calls-state (:content-index event))]
                      (.append ^StringBuilder (:input-buf tc) ^String (:delta event)))

                    :toolcall-end nil ;; tool calls finalised in :done

                    :done
                    (let [tc-blocks (->> @tool-calls-state
                                         (sort-by key)
                                         (mapv (fn [[_ tc]]
                                                 {:type      :tool-call
                                                  :id        (:id tc)
                                                  :name      (:name tc)
                                                  :arguments (str (:input-buf tc))})))
                          text      (str text-buf)
                          content   (cond-> []
                                      (seq text)     (conj {:type :text :text text})
                                      :always        (into tc-blocks))
                          final     {:role        "assistant"
                                     :content     content
                                     :stop-reason (or (:reason event) :stop)
                                     :timestamp   (java.time.Instant/now)}]
                      (agent/end-stream-in! agent-ctx final)
                      (deliver done-p final))

                    :error
                    (let [err-msg (:error-message event)
                          partial (str text-buf)
                          content (cond-> []
                                    (seq partial) (conj {:type :text :text partial})
                                    :always       (conj {:type :error :text err-msg}))
                          final   {:role          "assistant"
                                   :content       content
                                   :stop-reason   :error
                                   :error-message err-msg
                                   :timestamp     (java.time.Instant/now)}]
                      (agent/end-stream-in! agent-ctx final)
                      (deliver done-p final))

                    ;; :start :text-start :text-end :thinking-* — ignore
                    nil)))
    ;; Block until streaming completes (future in ai layer)
    (deref done-p 120000 {:role          "assistant"
                          :content       [{:type  :error
                                           :text  "Timeout waiting for LLM response"}]
                          :stop-reason   :error
                          :error-message "Timeout waiting for LLM response"
                          :timestamp     (java.time.Instant/now)})))

;; ============================================================
;; Tool execution for one turn
;; ============================================================

(defn- extract-tool-calls
  "Extract tool call specs from an assistant message's content."
  [assistant-msg]
  (filter #(= :tool-call (:type %)) (:content assistant-msg)))

(defn- run-tool-call!
  "Execute one tool call, record the result in agent-core, return the result map."
  [agent-ctx tool-call]
  (let [call-id  (:id tool-call)
        name     (:name tool-call)
        args     (parse-args (:arguments tool-call))]
    (agent/emit-tool-start-in! agent-ctx tool-call)
    (let [{:keys [content is-error]}
          (try
            (tools/execute-tool name args)
            (catch Exception e
              {:content  (str "Error: " (ex-message e))
               :is-error true}))
          result-msg {:role         "toolResult"
                      :tool-call-id call-id
                      :tool-name    name
                      :content      [{:type :text :text content}]
                      :is-error     is-error
                      :timestamp    (java.time.Instant/now)}]
      (agent/emit-tool-end-in! agent-ctx tool-call {:content content} is-error)
      (agent/record-tool-result-in! agent-ctx result-msg)
      result-msg)))

;; ============================================================
;; Public: run a full agent turn (recursive for tool loops)
;; ============================================================

(defn run-turn!
  "Drive one complete agent interaction loop:
     stream → check tools → execute tools → stream again (recursive).

   `ai-ctx`    — ai component context (has :provider-registry)
   `agent-ctx` — agent-core context
   `ai-model`  — ai.schemas.Model map

   Returns the final (non-tool) assistant message map.
   Emits agent-core events throughout."
  [ai-ctx agent-ctx ai-model]
  (agent/emit-in! agent-ctx {:type :turn-start})
  (let [assistant-msg (stream-turn! ai-ctx agent-ctx ai-model)
        tool-calls    (extract-tool-calls assistant-msg)]
    (agent/emit-turn-end-in! agent-ctx assistant-msg [])
    (if (and (seq tool-calls) (not= :error (:stop-reason assistant-msg)))
      ;; Tool calls requested — execute them all then recurse
      (do
        (doseq [tc tool-calls]
          (run-tool-call! agent-ctx tc))
        (run-turn! ai-ctx agent-ctx ai-model))
      ;; No tool calls (or error) — we're done
      assistant-msg)))

(defn run-agent-loop!
  "Run a complete agent loop starting from the current agent-core state.

   Sends :session/prompt to the session statechart externally (caller's job).
   This fn drives the internal turn loop and returns when the LLM stops
   requesting tool calls.

   Returns the final assistant message."
  [ai-ctx agent-ctx ai-model new-messages]
  (agent/start-loop-in! agent-ctx new-messages)
  (let [result (try
                 (run-turn! ai-ctx agent-ctx ai-model)
                 (catch Exception e
                   {:role "assistant" :content []
                    :stop-reason :error
                    :error-message (ex-message e)
                    :timestamp (java.time.Instant/now)}))]
    (if (= :error (:stop-reason result))
      (agent/end-loop-on-error-in! agent-ctx (:error-message result))
      (agent/end-loop-in! agent-ctx))
    result))
