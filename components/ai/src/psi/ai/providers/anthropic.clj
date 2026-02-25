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
  "Transform conversation messages to Anthropic format."
  [conversation]
  (map (fn [msg]
         (case (:role msg)
           :user        {:role    "user"
                         :content (get-in msg [:content :text] (str (:content msg)))}
           :assistant   {:role    "assistant"
                         :content (get-in msg [:content :text] "")}
           :tool-result {:role    "user"
                         :content (str "Tool result: " (:content msg))}))
       (:messages conversation)))

(defn build-request
  "Build Anthropic API request map."
  [conversation model options]
  {:headers {"Content-Type"    "application/json"
             "x-api-key"       (or (:api-key options)
                                   (System/getenv "ANTHROPIC_API_KEY"))
             "anthropic-version" "2023-06-01"}
   :body    (json/generate-string
             {:model       (:id model)
              :max_tokens  (or (:max-tokens options) (:max-tokens model))
              :temperature (or (:temperature options) 0.7)
              :system      (:system-prompt conversation)
              :messages    (transform-messages conversation)
              :stream      true})})

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
     {:type :text-start  :content-index n}
     {:type :text-delta  :content-index n  :delta \"...\"}
     {:type :text-end    :content-index n}
     {:type :done        :reason kw  :usage {...}}
     {:type :error       :error-message \"...\"}
   "
  [conversation model options consume-fn]
  (let [request (build-request conversation model options)]
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
                (consume-fn {:type          :text-start
                             :content-index (:index event-data)})

                "content_block_delta"
                (when-let [delta (get-in event-data [:delta :text])]
                  (consume-fn {:type          :text-delta
                               :content-index (:index event-data)
                               :delta         delta}))

                "content_block_stop"
                (consume-fn {:type          :text-end
                             :content-index (:index event-data)})

                "message_delta"
                (when (:stop_reason event-data)
                  (consume-fn {:type   :done
                               :reason (keyword (:stop_reason event-data))
                               :usage  {:input-tokens  0
                                        :output-tokens 0
                                        :total-tokens  0
                                        :cost          {:total 0.0}}}))

                "message_stop"
                (consume-fn {:type :done :reason :stop})

                ;; Unknown event — ignore
                nil)))))
      (catch Exception e
        (consume-fn {:type :error :error-message (str e)})))))

;; Provider implementation
(def provider
  {:name   :anthropic
   :stream stream-anthropic})
