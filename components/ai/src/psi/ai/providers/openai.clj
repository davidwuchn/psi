(ns psi.ai.providers.openai
  "OpenAI provider implementation.

   The provider exposes a single `:stream` fn that accepts a conversation,
   model, options, and a `consume-fn` callback.  Events are delivered
   synchronously on a dedicated thread so callers are not forced to use
   core.async."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.models :as models]))

(defn transform-messages
  "Transform conversation messages to OpenAI chat completions format.
   Handles user, assistant (with optional tool_calls), and tool-result messages."
  [conversation]
  (->> (:messages conversation)
       (reduce
        (fn [acc msg]
          (case (:role msg)
            :user
            (conj acc {:role    "user"
                       :content (get-in msg [:content :text] (str (:content msg)))})

            :assistant
            (let [kind (get-in msg [:content :kind])]
              (if (= :structured kind)
                (let [blocks     (get-in msg [:content :blocks])
                      text-parts (keep #(when (= :text (:kind %)) (:text %)) blocks)
                      tool-parts (filter #(= :tool-call (:kind %)) blocks)
                      text       (str/join "\n" text-parts)
                      base       (cond-> {:role "assistant"}
                                   (seq text) (assoc :content text))]
                  (conj acc
                        (if (seq tool-parts)
                          (assoc base :tool_calls
                                 (mapv (fn [tc]
                                         {:id       (:id tc)
                                          :type     "function"
                                          :function {:name      (:name tc)
                                                     :arguments (json/generate-string
                                                                 (:input tc))}})
                                       tool-parts))
                          base)))
                ;; :text or default
                (conj acc {:role    "assistant"
                           :content (get-in msg [:content :text] "")})))

            :tool-result
            (let [text (if (map? (:content msg))
                         (get-in msg [:content :text] "")
                         (str (:content msg)))]
              (conj acc {:role         "tool"
                         :tool_call_id (:tool-call-id msg)
                         :content      text}))

            ;; unknown — skip
            acc))
        [])))

(defn build-request
  "Build OpenAI API request map.
   Includes tools from conversation when present."
  [conversation model options]
  (let [base-messages (transform-messages conversation)
        messages      (if (:system-prompt conversation)
                        (cons {:role    "system"
                               :content (:system-prompt conversation)}
                              base-messages)
                        base-messages)
        tool-defs     (when (seq (:tools conversation))
                        (mapv (fn [t]
                                {:type     "function"
                                 :function {:name        (:name t)
                                            :description (:description t)
                                            :parameters  (:parameters t)}})
                              (:tools conversation)))
        body          (cond-> {:model          (:id model)
                               :messages       (vec messages)
                               :stream         true
                               :stream_options {:include_usage true}}
                        (:temperature options) (assoc :temperature (:temperature options))
                        (:max-tokens options)  (assoc :max_tokens  (:max-tokens options))
                        (seq tool-defs)        (assoc :tools tool-defs))]
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
     {:type :text-delta       :content-index 0  :delta \"...\"}
     {:type :thinking-delta   :content-index 0  :delta \"...\"}
     {:type :toolcall-start   :content-index n  :id \"call_...\" :name \"tool-name\"}
     {:type :toolcall-delta   :content-index n  :delta \"partial-json\"}
     {:type :toolcall-end     :content-index n}
     {:type :done             :reason kw  :usage {...}}
     {:type :error            :error-message \"...\"}
   "
  [conversation model options consume-fn]
  (let [request       (build-request conversation model options)
        ;; Track which tool-call indices have been started
        started-tools (atom #{})
        done?         (atom false)]
    (try
      (let [response (http/post (str (:base-url model) "/chat/completions")
                                (merge request {:as :stream :cookie-policy :none}))]
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

                  ;; Tool calls — first chunk per index has :id + :name → start
                  (:tool_calls delta)
                  (doseq [tool-call (:tool_calls delta)]
                    (let [idx (:index tool-call)]
                      ;; Emit toolcall-start on first appearance of this index
                      (when (and (:id tool-call)
                                 (not (contains? @started-tools idx)))
                        (swap! started-tools conj idx)
                        (consume-fn {:type          :toolcall-start
                                     :content-index idx
                                     :id            (:id tool-call)
                                     :name          (get-in tool-call
                                                            [:function :name])}))
                      ;; Argument fragments → string delta
                      (when-let [args (get-in tool-call [:function :arguments])]
                        (when (seq args)
                          (consume-fn {:type          :toolcall-delta
                                       :content-index idx
                                       :delta         args})))))

                  ;; Completion with usage (final chunk)
                  (:usage chunk)
                  (let [usage (:usage chunk)]
                    ;; Close any open tool calls
                    (doseq [idx @started-tools]
                      (consume-fn {:type :toolcall-end :content-index idx}))
                    (reset! started-tools #{})
                    (when-not @done?
                      (reset! done? true)
                      (consume-fn {:type   :done
                                   :reason (keyword (get-in choice
                                                            [:finish_reason]
                                                            "stop"))
                                   :usage  {:input-tokens  (:prompt_tokens usage)
                                            :output-tokens (:completion_tokens usage)
                                            :total-tokens  (:total_tokens usage)
                                            :cost          (models/calculate-cost
                                                            model usage)}})))

                  ;; Finish without usage
                  (:finish_reason choice)
                  (do
                    ;; Close any open tool calls
                    (doseq [idx @started-tools]
                      (consume-fn {:type :toolcall-end :content-index idx}))
                    (reset! started-tools #{})
                    (when-not @done?
                      (reset! done? true)
                      (consume-fn {:type   :done
                                   :reason (keyword (:finish_reason choice))})))))))))
      (catch Exception e
        (consume-fn {:type :error :error-message (str e)})))))

;; Provider implementation
(def provider
  {:name   :openai
   :stream stream-openai})
