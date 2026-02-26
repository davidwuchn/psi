(ns psi.ai.providers.anthropic
  "Anthropic provider implementation.

   The provider exposes a single `:stream` fn that accepts a conversation,
   model, options, and a `consume-fn` callback.  Events are delivered
   synchronously on a dedicated thread so callers are not forced to use
   core.async."
  (:require [clojure.java.io :as io]
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

(defn build-request
  "Build Anthropic API request map.
   Includes tools from conversation when present."
  [conversation model options]
  (let [tool-defs (when (seq (:tools conversation))
                    (mapv (fn [t]
                            {:name         (:name t)
                             :description  (:description t)
                             :input_schema (:parameters t)})
                          (:tools conversation)))]
    {:headers {"Content-Type"      "application/json"
               "x-api-key"         (or (:api-key options)
                                       (System/getenv "ANTHROPIC_API_KEY"))
               "anthropic-version" "2023-06-01"}
     :body    (json/generate-string
               (cond-> {:model       (:id model)
                        :max_tokens  (or (:max-tokens options) (:max-tokens model))
                        :temperature (or (:temperature options) 0.7)
                        :system      (:system-prompt conversation)
                        :messages    (transform-messages conversation)
                        :stream      true}
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
     {:type :text-start    :content-index n}
     {:type :text-delta    :content-index n  :delta \"...\"}
     {:type :text-end      :content-index n}
     {:type :toolcall-start :content-index n :id \"toolu_...\" :name \"tool-name\"}
     {:type :toolcall-delta :content-index n :delta \"partial-json\"}
     {:type :toolcall-end   :content-index n}
     {:type :done          :reason kw  :usage {...}}
     {:type :error         :error-message \"...\"}
   "
  [conversation model options consume-fn]
  (let [request (build-request conversation model options)
        ;; Track content block types by index to route deltas correctly
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
                  (if (= "tool_use" btype)
                    (consume-fn {:type          :toolcall-start
                                 :content-index idx
                                 :id            (:id block)
                                 :name          (:name block)})
                    (consume-fn {:type          :text-start
                                 :content-index idx})))

                "content_block_delta"
                (let [idx   (:index event-data)
                      btype (get @block-types idx)
                      delta (:delta event-data)]
                  (if (= "tool_use" btype)
                    (when-let [json-delta (:partial_json delta)]
                      (consume-fn {:type          :toolcall-delta
                                   :content-index idx
                                   :delta         json-delta}))
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
        (consume-fn (cond-> {:type :error :error-message (str e)}
                      (:status (ex-data e)) (assoc :http-status (:status (ex-data e)))))))))

;; Provider implementation
(def provider
  {:name   :anthropic
   :stream stream-anthropic})
