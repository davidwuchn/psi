(ns psi.ai.providers.openai
  "OpenAI provider implementation"
  (:require [clojure.core.async :as async]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.models :as models])
  (:import [java.util UUID]))

(defn transform-messages
  "Transform conversation messages to OpenAI format"
  [conversation]
  (map (fn [msg]
         (case (:role msg)
           :user {:role "user"
                  :content (if (string? (:content msg))
                            (:content msg)
                            (get-in msg [:content :text]))}
           :assistant {:role "assistant"
                      :content (if (string? (:content msg))
                                (:content msg)
                                ;; Handle structured content
                                (get-in msg [:content :text] ""))}
           :tool-result {:role "tool"
                        :tool_call_id (:tool-call-id msg)
                        :content (:content msg)}))
       (:messages conversation)))

(defn build-request
  "Build OpenAI API request"
  [conversation model options]
  (let [request-body {:model (:id model)
                      :messages (transform-messages conversation)
                      :stream true
                      :stream_options {:include_usage true}}]
    {:headers {"Content-Type" "application/json"
               "Authorization" (str "Bearer " (or (:api-key options) 
                                                 (System/getenv "OPENAI_API_KEY")))}
     :body (json/generate-string
             (merge request-body
                   (when (:temperature options)
                     {:temperature (:temperature options)})
                   (when (:max-tokens options)
                     {:max_tokens (:max-tokens options)})
                   (when (:system-prompt conversation)
                     {:messages (cons {:role "system" 
                                      :content (:system-prompt conversation)}
                                     (:messages request-body))})))}))

(defn parse-sse-line
  "Parse Server-Sent Events line"
  [line]
  (when (and line (.startsWith line "data: "))
    (let [data (.substring line 6)]
      (when (not= data "[DONE]")
        (try
          (json/parse-string data true)
          (catch Exception _ nil))))))

(defn stream-openai
  "Stream response from OpenAI API"  
  [conversation model options]
  (let [event-chan (async/chan 100)
        request (build-request conversation model options)]
    
    (async/go
      (try
        (let [response (http/post (str (:base-url model) "/chat/completions")
                                 (merge request {:as :stream}))]
          (with-open [reader (clojure.java.io/reader (:body response))]
            (doseq [line (line-seq reader)]
              (when-let [chunk (parse-sse-line line)]
                (let [choice (first (:choices chunk))
                      delta (:delta choice)]
                  (cond
                    ;; Start of response
                    (and choice (= (:role delta) "assistant"))
                    (async/>! event-chan {:type :start})
                    
                    ;; Content delta
                    (:content delta)
                    (async/>! event-chan {:type :text-delta
                                         :content-index 0
                                         :delta (:content delta)})
                    
                    ;; Reasoning content (o1 models)
                    (get-in delta [:reasoning :content])
                    (async/>! event-chan {:type :thinking-delta
                                         :content-index 0
                                         :delta (get-in delta [:reasoning :content])})
                    
                    ;; Tool calls
                    (:tool_calls delta)
                    (doseq [tool-call (:tool_calls delta)]
                      (async/>! event-chan {:type :toolcall-delta
                                           :content-index (:index tool-call)
                                           :delta (:function tool-call)}))
                    
                    ;; Completion with usage
                    (:usage chunk)
                    (let [usage (:usage chunk)]
                      (async/>! event-chan {:type :done
                                           :reason (keyword (get-in choice [:finish_reason] :stop))
                                           :usage {:input-tokens (:prompt_tokens usage)
                                                  :output-tokens (:completion_tokens usage) 
                                                  :total-tokens (:total_tokens usage)
                                                  :cost (models/calculate-cost model usage)}}))
                    
                    ;; Finish without usage
                    (:finish_reason choice)
                    (async/>! event-chan {:type :done
                                         :reason (keyword (:finish_reason choice))})))))))
        
        (catch Exception e
          (async/>! event-chan {:type :error
                               :error-message (str e)})))
      
      (async/close! event-chan))
    
    event-chan))

;; Provider implementation
(def provider
  {:name :openai
   :stream stream-openai})