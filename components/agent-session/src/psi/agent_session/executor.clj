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
   [clojure.string :as str]
   [cheshire.core :as json]
   [psi.ai.core :as ai]
   [psi.ai.conversation :as conv]
   [psi.agent-core.core :as agent]
   [psi.agent-session.tools :as tools]))

;; ============================================================
;; ai conversation ↔ agent-core message translation
;; ============================================================

(defn- agent-messages->ai-conversation
  "Rebuild an ai/conversation from agent-core message history.
  Used to reconstruct the conversation context before each LLM call."
  [system-prompt messages]
  (reduce
   (fn [conv msg]
     (case (:role msg)
       "user"
       (conv/add-user-message conv
                              (or (some #(when (= :text (:type %)) (:text %))
                                        (:content msg))
                                  (str (:content msg))))
       "assistant"
       ;; assistant messages: extract text content
       (let [text (str/join "\n"
                            (keep #(when (= :text (:type %)) (:text %))
                                  (:content msg)))]
         (conv/add-assistant-message conv {:content {:kind :text :text text}}))

       "toolResult"
       (conv/add-tool-result conv
                             (:tool-call-id msg)
                             (:tool-name msg)
                             (or (some #(when (= :text (:type %)) (:text %))
                                       (:content msg))
                                 "")
                             (boolean (:is-error msg)))
       ;; unknown roles — skip
       conv))
   (conv/create system-prompt)
   messages))

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
  Calls agent-core lifecycle fns: begin-stream-in!, update-stream-in!, end-stream-in!."
  [ai-ctx agent-ctx ai-model]
  (let [data          (agent/get-data-in agent-ctx)
        system-prompt (:system-prompt data)
        messages      (:messages data)
        ai-conv       (agent-messages->ai-conversation system-prompt messages)
        ai-options    {}
        text-buf      (StringBuilder.)
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
                    :done
                    (let [final {:role        "assistant"
                                 :content     [{:type :text :text (str text-buf)}]
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
                    ;; :start :text-start :text-end — ignore
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

(defn- parse-args
  "Parse JSON tool arguments string into a map."
  [arguments]
  (try
    (json/parse-string arguments)
    (catch Exception _ {})))

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
