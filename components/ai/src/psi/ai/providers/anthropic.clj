(ns psi.ai.providers.anthropic
  "Anthropic provider implementation"
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(defn transform-messages
  "Transform conversation messages to Anthropic format"
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
           :tool-result {:role "user"  ;; Anthropic uses user role for tool results
                         :content (str "Tool result: " (:content msg))}))
       (:messages conversation)))

(defn build-request
  "Build Anthropic API request"
  [conversation model options]
  {:headers {"Content-Type" "application/json"
             "x-api-key" (or (:api-key options) (System/getenv "ANTHROPIC_API_KEY"))
             "anthropic-version" "2023-06-01"}
   :body (json/generate-string
          {:model (:id model)
           :max_tokens (or (:max-tokens options) (:max-tokens model))
           :temperature (or (:temperature options) 0.7)
           :system (:system-prompt conversation)
           :messages (transform-messages conversation)
           :stream true})})

(defn parse-sse-line
  "Parse Server-Sent Events line"
  [line]
  (when (and line (.startsWith line "data: "))
    (let [data (.substring line 6)]
      (when (not= data "[DONE]")
        (try
          (json/parse-string data true)
          (catch Exception _ nil))))))

(defn stream-anthropic
  "Stream response from Anthropic API"
  [conversation model options]
  (let [event-chan (async/chan 100)
        request (build-request conversation model options)]

    (async/go
      (try
        (let [response (http/get (str (:base-url model) "/v1/messages")
                                 (merge request {:as :stream}))]
          (with-open [reader (io/reader (:body response))]
            (doseq [line (line-seq reader)]
              (when-let [event-data (parse-sse-line line)]
                (case (:type event-data)
                  "message_start"
                  (async/>! event-chan {:type :start})

                  "content_block_start"
                  (async/>! event-chan {:type :text-start}
                            :content-index (:index event-data))

                  "content_block_delta"
                  (when-let [delta (get-in event-data [:delta :text])]
                    (async/>! event-chan {:type :text-delta}
                              :content-index (:index event-data)
                              :delta delta))

                  "content_block_stop"
                  (async/>! event-chan {:type :text-end}
                            :content-index (:index event-data))

                  "message_delta"
                  (when (:stop_reason event-data)
                    (async/>! event-chan {:type :done}
                              :reason (keyword (:stop_reason event-data))
                              :usage {:input-tokens 0}  ;; TODO: extract from response
                              :output-tokens 0
                              :total-tokens 0
                              :cost {:total 0.0}))

                  "message_stop"
                  (async/>! event-chan {:type :done}
                            :reason :stop)

                  ;; Default case
                  nil)))))

        (catch Exception e
          (async/>! event-chan {:type :error}
                    :error-message (str e))))

      (async/close! event-chan))

    event-chan))

;; Provider implementation
(def provider
  {:name :anthropic
   :stream stream-anthropic})