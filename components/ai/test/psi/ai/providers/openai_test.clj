(ns psi.ai.providers.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai])
  (:import [java.io ByteArrayInputStream]
           [java.util Base64]))

(defn- jwt-with-account-id
  [account-id]
  (let [payload-json (json/generate-string
                      {"https://api.openai.com/auth"
                       {"chatgpt_account_id" account-id}})
        payload      (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                      (.getBytes payload-json "UTF-8"))]
    (str "aaa." payload ".bbb")))

(defn- stream-body
  [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest codex-streaming-test
  (testing "codex model streams via chatgpt backend and emits normalized events"
    (let [model      (models/get-model :gpt-5.3-codex)
          token      (jwt-with-account-id "acc_test")
          convo      (-> (conv/create "You are a helpful assistant")
                         (conv/add-user-message "Say hello"))
          events     (atom [])
          captured   (atom nil)
          sse        (str
                      "data: " (json/generate-string
                                 {:type "response.output_item.added"
                                  :item {:type "message"
                                         :id "msg_1"
                                         :role "assistant"
                                         :status "in_progress"
                                         :content []}}) "\n\n"
                      "data: " (json/generate-string
                                 {:type "response.content_part.added"
                                  :part {:type "output_text" :text ""}}) "\n\n"
                      "data: " (json/generate-string
                                 {:type "response.output_text.delta"
                                  :delta "Hello"}) "\n\n"
                      "data: " (json/generate-string
                                 {:type "response.output_item.done"
                                  :item {:type "message"
                                         :id "msg_1"
                                         :role "assistant"
                                         :status "completed"
                                         :content [{:type "output_text" :text "Hello"}]}}) "\n\n"
                      "data: " (json/generate-string
                                 {:type "response.completed"
                                  :response {:status "completed"
                                             :usage {:input_tokens 5
                                                     :output_tokens 3
                                                     :total_tokens 8
                                                     :input_tokens_details {:cached_tokens 0}}}}) "\n\n")]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))

      (is (= "https://chatgpt.com/backend-api/codex/responses"
             (:url @captured)))
      (is (= (str "Bearer " token)
             (get-in @captured [:req :headers "Authorization"])))
      (is (= "acc_test"
             (get-in @captured [:req :headers "chatgpt-account-id"])))

      (let [body (json/parse-string (get-in @captured [:req :body]) true)]
        (is (= "gpt-5.3-codex" (:model body)))
        (is (= "You are a helpful assistant" (:instructions body)))
        (is (= true (:stream body)))
        (is (= {:effort "medium" :summary "auto"}
               (:reasoning body))))

      (is (some #(= :start (:type %)) @events))
      (is (some #(and (= :text-delta (:type %)) (= "Hello" (:delta %))) @events))
      (is (some #(= :done (:type %)) @events)))))

(deftest codex-requires-chatgpt-token-test
  (testing "non-ChatGPT token emits an error event (missing chatgpt_account_id)"
    (let [model  (models/get-model :gpt-5.3-codex)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])]
      ((:stream openai/provider)
       convo model {:api-key "not-a-jwt-token"}
       (fn [ev] (swap! events conj ev)))

      (is (= :error (:type (first @events))))
      (is (re-find #"chatgpt_account_id"
                   (:error-message (first @events)))))))

(deftest codex-reasoning-text-delta-maps-to-thinking-delta-test
  (testing "response.reasoning_text.delta is bridged as :thinking-delta"
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "You are a helpful assistant")
                       (conv/add-user-message "Think then answer"))
          events   (atom [])
          sse      (str
                    "data: " (json/generate-string
                               {:type "response.output_item.added"
                                :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                    "data: " (json/generate-string
                               {:type "response.reasoning_text.delta"
                                :delta "Plan step"}) "\n\n"
                    "data: " (json/generate-string
                               {:type "response.completed"
                                :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))

      (is (some #(= :start (:type %)) @events))
      (is (some #(and (= :thinking-delta (:type %))
                      (= "Plan step" (:delta %)))
                @events))
      (is (some #(= :done (:type %)) @events)))))

(deftest codex-reasoning-map-delta-normalized-to-string-test
  (testing "non-string reasoning delta payloads are normalized to text"
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "sys") (conv/add-user-message "think"))
          events   (atom [])
          sse      (str
                    "data: " (json/generate-string
                               {:type "response.output_item.added"
                                :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                    "data: " (json/generate-string
                               {:type "response.reasoning_summary.delta"
                                :delta {:text "Plan chunk"}}) "\n\n"
                    "data: " (json/generate-string
                               {:type "response.completed"
                                :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))

      (is (some #(and (= :thinking-delta (:type %))
                      (= "Plan chunk" (:delta %)))
                @events)))))

(deftest codex-thinking-level-maps-to-reasoning-effort-test
  (let [model (models/get-model :gpt-5.3-codex)]
    (is (= {"effort" "high" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :high})))
    (is (= {"effort" "minimal" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :minimal})))
    (is (nil? (#'openai/codex-reasoning model {:thinking-level :off})))
    (is (= {"effort" "medium" "summary" "auto"}
           (#'openai/codex-reasoning model {})))))

(deftest codex-tool-call-id-roundtrip-test
  (testing "tool call ids split into call_id + item id (not single-char prefixes)"
    (let [call-id "call_abc123"
          item-id "fc_456def"
          full-id (str call-id "|" item-id)
          convo   (-> (conv/create "sys")
                      (conv/add-user-message "ls")
                      (conv/add-assistant-message
                       {:content
                        {:kind :structured
                         :blocks [{:kind  :tool-call
                                   :id    full-id
                                   :name  "bash"
                                   :input {"command" "ls"}}]}})
                      (conv/add-tool-result full-id "bash" {:kind :text :text "ok"} false))
          input   ((deref #'openai/codex-input-messages) convo)
          call    (second input)
          result  (nth input 2)]
      (is (= "function_call" (get call "type")))
      (is (= call-id (get call "call_id")))
      (is (= item-id (get call "id")))
      (is (= "function_call_output" (get result "type")))
      (is (= call-id (get result "call_id"))))))

(deftest completions-thinking-level-maps-to-reasoning-effort-test
  (let [model (models/get-model :gpt-5)
        convo (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (testing "default reasoning effort is medium for reasoning-capable models"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"})
            body (json/parse-string (:body req) true)]
        (is (= "medium" (:reasoning_effort body)))))

    (testing "explicit thinking level maps to expected reasoning effort"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"
                                                      :thinking-level :high})
            body (json/parse-string (:body req) true)]
        (is (= "high" (:reasoning_effort body)))))

    (testing "thinking off omits reasoning effort"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"
                                                      :thinking-level :off})
            body (json/parse-string (:body req) true)]
        (is (nil? (:reasoning_effort body)))))))

(deftest completions-reasoning-delta-shapes-map-to-thinking-delta-test
  (testing "chat completions reasoning delta variants are emitted as :thinking-delta"
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys") (conv/add-user-message "think"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                             {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                             {:choices [{:delta {:reasoning_content "A"}}]}) "\n\n"
                  "data: " (json/generate-string
                             {:choices [{:delta {:reasoning [{:type "reasoning_text" :text "B"}]}}]}) "\n\n"
                  "data: " (json/generate-string
                             {:choices [{:delta {:content [{:type "reasoning" :text "C"}]}}]}) "\n\n"
                  "data: " (json/generate-string
                             {:choices [{:delta {:reasoning {:content [{:type "reasoning_text"
                                                                       :text "D"}]}}}]}) "\n\n"
                  "data: " (json/generate-string
                             {:choices [{:delta {:reasoning {:summary [{:type "summary_text"
                                                                       :text "E"}]}}}]}) "\n\n"
                  "data: " (json/generate-string
                             {:choices [{:finish_reason "stop"}]
                              :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))

      (is (some #(= :start (:type %)) @events))
      (is (= ["A" "B" "C" "D" "E"]
             (->> @events
                  (filter #(= :thinking-delta (:type %)))
                  (mapv :delta))))
      (is (some #(= :done (:type %)) @events)))))
