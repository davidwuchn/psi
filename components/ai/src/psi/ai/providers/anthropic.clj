(ns psi.ai.providers.anthropic
  "Anthropic provider implementation.

   The provider exposes a single `:stream` fn that accepts a conversation,
   model, options, and a `consume-fn` callback.  Events are delivered
   synchronously on a dedicated thread so callers are not forced to use
   core.async."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(defn transform-messages
  "Transform conversation messages to Anthropic API format.
   Handles user, assistant (with optional tool_use), and tool-result messages.
   Consecutive tool-result messages are merged into a single user message
   (Anthropic requires all tool_result blocks in one user message)."
  [conversation]
  (->> (:messages conversation)
       (reduce
        (fn [acc msg]
          (case (:role msg)
            :user
            (conj acc {:role    "user"
                       :content [{:type "text"
                                  :text (get-in msg [:content :text]
                                                (str (:content msg)))}]})

            :assistant
            (let [content
                  (case (get-in msg [:content :kind])
                    :structured
                    (mapv (fn [block]
                            (case (:kind block)
                              :text
                              {:type "text" :text (:text block)}

                              :tool-call
                              {:type  "tool_use"
                               :id    (:id block)
                               :name  (:name block)
                               :input (:input block)}

                              ;; fallback
                              {:type "text" :text (str block)}))
                          (get-in msg [:content :blocks]))

                    ;; :text or default
                    [{:type "text"
                      :text (get-in msg [:content :text] "")}])]
              (conj acc {:role "assistant" :content content}))

            :tool-result
            (let [text  (if (map? (:content msg))
                          (get-in msg [:content :text] "")
                          (str (:content msg)))
                  block (cond-> {:type        "tool_result"
                                 :tool_use_id (:tool-call-id msg)
                                 :content     text}
                          (:is-error msg) (assoc :is_error true))
                  last-msg (peek acc)]
              (if (and last-msg
                       (= "user" (:role last-msg))
                       (every? #(= "tool_result" (:type %))
                               (:content last-msg)))
                ;; Merge into existing tool-result user message
                (conj (pop acc) (update last-msg :content conj block))
                ;; Start new tool-result user message
                (conj acc {:role "user" :content [block]})))

            ;; unknown role — skip
            acc))
        [])))

(def ^:private thinking-level->budget
  "Map thinking-level keyword to Anthropic extended-thinking budget_tokens.
   nil means thinking is disabled."
  {:off     nil
   :minimal 1024
   :low     2048
   :medium  8000
   :high    16000
   :xhigh   32000})

(defn- thinking-param
  "Return the Anthropic `thinking` request param map for OPTIONS, or nil when disabled."
  [model options]
  (when (:supports-reasoning model)
    (let [level  (:thinking-level options)
          budget (get thinking-level->budget level)]
      (when budget
        {:type          "enabled"
         :budget_tokens budget}))))

(defn build-request
  "Build Anthropic API request map.
   Includes tools from conversation when present.
   When the model supports reasoning and thinking-level is set (non-:off),
   adds the extended-thinking param and the required interleaved-thinking beta header."
  [conversation model options]
  (let [tool-defs (when (seq (:tools conversation))
                    (mapv (fn [t]
                            {:name         (:name t)
                             :description  (:description t)
                             :input_schema (:parameters t)})
                          (:tools conversation)))
        thinking  (thinking-param model options)
        api-key   (or (:api-key options) (System/getenv "ANTHROPIC_API_KEY"))
        oauth?    (and api-key (str/includes? api-key "sk-ant-oat"))
        base-beta (if oauth?
                    "claude-code-20250219,oauth-2025-04-20"
                    nil)
        beta      (cond
                    (and thinking base-beta)
                    (str base-beta ",interleaved-thinking-2025-05-14")
                    thinking
                    "interleaved-thinking-2025-05-14"
                    :else
                    base-beta)
        headers   (cond-> (if oauth?
                            {"Content-Type"      "application/json"
                             "Authorization"     (str "Bearer " api-key)
                             "anthropic-version" "2023-06-01"}
                            {"Content-Type"      "application/json"
                             "x-api-key"         api-key
                             "anthropic-version" "2023-06-01"})
                    beta (assoc "anthropic-beta" beta))]
    {:headers headers
     :body    (json/generate-string
               (cond-> {:model      (:id model)
                        :max_tokens (or (:max-tokens options) (:max-tokens model))
                        :system     (:system-prompt conversation)
                        :messages   (transform-messages conversation)
                        :stream     true}
                 ;; temperature is incompatible with extended thinking
                 (not thinking) (assoc :temperature (or (:temperature options) 0.7))
                 thinking       (assoc :thinking thinking)
                 (seq tool-defs) (assoc :tools tool-defs)))}))

(defn parse-sse-line
  "Parse a Server-Sent Events `data:` line; returns nil for non-data lines."
  [line]
  (when (and line (.startsWith ^String line "data: "))
    (let [data (.substring ^String line 6)]
      (when (not= data "[DONE]")
        (try (json/parse-string data true)
             (catch Exception _ nil))))))

(defn stream-anthropic
  "Stream response from Anthropic API.

   Calls `consume-fn` with each event map on the current thread (blocking).
   Returns when streaming is complete or an error occurs.

   Event types emitted:
     {:type :start}
     {:type :text-start      :content-index n}
     {:type :text-delta      :content-index n  :delta \"...\"}
     {:type :text-end        :content-index n}
     {:type :thinking-delta  :content-index n  :delta \"...\"}
     {:type :toolcall-start  :content-index n :id \"toolu_...\" :name \"tool-name\"}
     {:type :toolcall-delta  :content-index n :delta \"partial-json\"}
     {:type :toolcall-end    :content-index n}
     {:type :done            :reason kw  :usage {...}}
     {:type :error           :error-message \"...\"}
   "
  [conversation model options consume-fn]
  (let [request (build-request conversation model options)
        ;; Track content block types by index to route deltas correctly
        ;; Values: \"text\" | \"thinking\" | \"tool_use\"
        block-types (atom {})
        done?       (atom false)]
    (try
      (let [response (http/post (str (:base-url model) "/v1/messages")
                                (merge request {:as :stream}))]
        (with-open [reader (io/reader (:body response))]
          (doseq [line (line-seq reader)]
            (when-let [event-data (parse-sse-line line)]
              (case (:type event-data)
                "message_start"
                (consume-fn {:type :start})

                "content_block_start"
                (let [idx   (:index event-data)
                      block (:content_block event-data)
                      btype (:type block)]
                  (swap! block-types assoc idx btype)
                  (case btype
                    "tool_use"
                    (consume-fn {:type          :toolcall-start
                                 :content-index idx
                                 :id            (:id block)
                                 :name          (:name block)})
                    ;; thinking and text both get a text-start marker
                    (consume-fn {:type          :text-start
                                 :content-index idx})))

                "content_block_delta"
                (let [idx   (:index event-data)
                      btype (get @block-types idx)
                      delta (:delta event-data)]
                  (case btype
                    "tool_use"
                    (when-let [json-delta (:partial_json delta)]
                      (consume-fn {:type          :toolcall-delta
                                   :content-index idx
                                   :delta         json-delta}))
                    "thinking"
                    ;; Anthropic sends delta.type="thinking_delta", delta.thinking=<str>
                    (when-let [text (or (:thinking delta) (:text delta))]
                      (consume-fn {:type          :thinking-delta
                                   :content-index idx
                                   :delta         text}))
                    ;; "text" and any unknown block type
                    (when-let [text (:text delta)]
                      (consume-fn {:type          :text-delta
                                   :content-index idx
                                   :delta         text}))))

                "content_block_stop"
                (let [idx   (:index event-data)
                      btype (get @block-types idx)]
                  (consume-fn {:type          (if (= "tool_use" btype)
                                                :toolcall-end
                                                :text-end)
                               :content-index idx}))

                "message_delta"
                (when-let [reason (get-in event-data [:delta :stop_reason])]
                  (reset! done? true)
                  (consume-fn {:type   :done
                               :reason (keyword reason)
                               :usage  {:input-tokens  0
                                        :output-tokens 0
                                        :total-tokens  0
                                        :cost          {:total 0.0}}}))

                "message_stop"
                (when-not @done?
                  (consume-fn {:type :done :reason :stop}))

                ;; Unknown event — ignore
                nil)))))
      (catch Exception e
        (let [data       (ex-data e)
              http-status (:status data)
              body-stream (:body data)
              api-error  (when (and body-stream (instance? java.io.InputStream body-stream))
                           (try
                             (let [body-str (slurp (io/reader body-stream))]
                               (or (get-in (json/parse-string body-str true) [:error :message])
                                   body-str))
                             (catch Exception _ nil)))
              msg        (or api-error (ex-message e) (str e))]
          (consume-fn (cond-> {:type :error :error-message msg}
                        http-status (assoc :http-status http-status))))))))

;; Provider implementation
(def provider
  {:name   :anthropic
   :stream stream-anthropic})
