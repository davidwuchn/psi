(ns psi.ai.providers.openai
  "OpenAI provider implementation.

   The provider exposes a single `:stream` fn that accepts a conversation,
   model, options, and a `consume-fn` callback.  Events are delivered
   synchronously on a dedicated thread so callers are not forced to use
   core.async."
  (:require [clojure.java.io :as io]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.models :as models]))

(defn transform-messages
  "Transform conversation messages to OpenAI format."
  [conversation]
  (map (fn [msg]
         (case (:role msg)
           :user        {:role    "user"
                         :content (get-in msg [:content :text] (str (:content msg)))}
           :assistant   {:role    "assistant"
                         :content (get-in msg [:content :text] "")}
           :tool-result {:role         "tool"
                         :tool_call_id (:tool-call-id msg)
                         :content      (:content msg)}))
       (:messages conversation)))

(defn build-request
  "Build OpenAI API request map."
  [conversation model options]
  (let [base-messages (transform-messages conversation)
        messages      (if (:system-prompt conversation)
                        (cons {:role    "system"
                               :content (:system-prompt conversation)}
                              base-messages)
                        base-messages)
        body          (cond-> {:model          (:id model)
                               :messages       (vec messages)
                               :stream         true
                               :stream_options {:include_usage true}}
                        (:temperature options) (assoc :temperature (:temperature options))
                        (:max-tokens options)  (assoc :max_tokens  (:max-tokens options)))]
    {:headers {"Content-Type"  "application/json"
               "Authorization" (str "Bearer " (or (:api-key options)
                                                   (System/getenv "OPENAI_API_KEY")))}
     :body    (json/generate-string body)}))

(defn parse-sse-line
  "Parse a Server-Sent Events `data:` line; returns nil for non-data lines."
  [line]
  (when (and line (.startsWith ^String line "data: "))
    (let [data (.substring ^String line 6)]
      (when (not= data "[DONE]")
        (try (json/parse-string data true)
             (catch Exception _ nil))))))

(defn stream-openai
  "Stream response from OpenAI API.

   Calls `consume-fn` with each event map on the current thread (blocking).
   Returns when streaming is complete or an error occurs.

   Event types emitted:
     {:type :start}
     {:type :text-delta      :content-index 0  :delta \"...\"}
     {:type :thinking-delta  :content-index 0  :delta \"...\"}
     {:type :toolcall-delta  :content-index n  :delta {...}}
     {:type :done            :reason kw  :usage {...}}
     {:type :error           :error-message \"...\"}
   "
  [conversation model options consume-fn]
  (let [request (build-request conversation model options)]
    (try
      (let [response (http/post (str (:base-url model) "/chat/completions")
                                (merge request {:as :stream}))]
        (with-open [reader (io/reader (:body response))]
          (doseq [line (line-seq reader)]
            (when-let [chunk (parse-sse-line line)]
              (let [choice (first (:choices chunk))
                    delta  (:delta choice)]
                (cond
                  ;; Start of response
                  (and choice (= (:role delta) "assistant"))
                  (consume-fn {:type :start})

                  ;; Content delta
                  (:content delta)
                  (consume-fn {:type          :text-delta
                               :content-index 0
                               :delta         (:content delta)})

                  ;; Reasoning content (o1 models)
                  (get-in delta [:reasoning :content])
                  (consume-fn {:type          :thinking-delta
                               :content-index 0
                               :delta         (get-in delta [:reasoning :content])})

                  ;; Tool calls
                  (:tool_calls delta)
                  (doseq [tool-call (:tool_calls delta)]
                    (consume-fn {:type          :toolcall-delta
                                 :content-index (:index tool-call)
                                 :delta         (:function tool-call)}))

                  ;; Completion with usage
                  (:usage chunk)
                  (let [usage (:usage chunk)]
                    (consume-fn {:type   :done
                                 :reason (keyword (get-in choice [:finish_reason] "stop"))
                                 :usage  {:input-tokens  (:prompt_tokens usage)
                                          :output-tokens (:completion_tokens usage)
                                          :total-tokens  (:total_tokens usage)
                                          :cost          (models/calculate-cost model usage)}}))

                  ;; Finish without usage
                  (:finish_reason choice)
                  (consume-fn {:type   :done
                               :reason (keyword (:finish_reason choice))})))))))
      (catch Exception e
        (consume-fn {:type :error :error-message (str e)})))))

;; Provider implementation
(def provider
  {:name   :openai
   :stream stream-openai})
