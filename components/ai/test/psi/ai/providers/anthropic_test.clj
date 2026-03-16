(ns psi.ai.providers.anthropic-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic])
  (:import [java.io ByteArrayInputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

;; ── build-request ───────────────────────────────────────────────────────────

(deftest build-request-no-thinking-test
  (testing "no thinking param when thinking-level is :off"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :off
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (nil? (:thinking body)))
      (is (some? (:temperature body)) "temperature present when thinking off")))

  (testing "no thinking param when model does not support reasoning"
    (let [model   (assoc (models/get-model :claude-3-5-haiku) :supports-reasoning false)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (nil? (:thinking body))))))

(deftest build-request-with-thinking-test
  (testing "thinking param present when level is non-:off and model supports reasoning"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= "enabled" (get-in body [:thinking :type])))
      (is (pos? (get-in body [:thinking :budget_tokens])))
      (is (nil? (:temperature body)) "temperature must be absent with extended thinking")
      (is (some? (re-find #"interleaved-thinking" (get headers "anthropic-beta")))
          "interleaved-thinking beta header required"))))

(deftest build-request-with-cache-breakpoints-test
  (testing "system prompt blocks and tools emit Anthropic cache_control when marked ephemeral"
    (let [model (models/get-model :sonnet-4.6)
          convo (-> (conv/create {:system-prompt "sys"
                                  :system-prompt-blocks [{:kind :text
                                                          :text "sys"
                                                          :cache-control {:type :ephemeral}}]})
                    (conv/add-tool {:name "read"
                                    :description "Read a file"
                                    :parameters {:type "object"}
                                    :cache-control {:type :ephemeral}}))
          req   (#'anthropic/build-request convo model {:api-key "test-key"})
          body  (json/parse-string (:body req) true)]
      (is (= [{:type "text"
               :text "sys"
               :cache_control {:type "ephemeral"}}]
             (:system body)))
      (is (= {:type "ephemeral"}
             (get-in body [:tools 0 :cache_control]))))))

;; ── SSE parser — thinking block routing ─────────────────────────────────────

(defn- run-stream [sse-str model options]
  (let [events (atom [])
        convo  (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse-str)})]
      (anthropic/stream-anthropic convo model options
                                  (fn [e] (swap! events conj e))))
    @events))

(deftest thinking-block-emits-thinking-delta-test
  (testing "thinking content block deltas are routed as :thinking-delta events"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "thinking"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "thinking_delta" :thinking "I think"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "thinking_delta" :thinking " therefore"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 1
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 1
                                :delta {:type "text_delta" :text "Hello"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 1})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (some #(= :thinking-delta (:type %)) events)
          "should emit at least one :thinking-delta")
      (is (= ["I think" " therefore"]
             (->> events
                  (filter #(= :thinking-delta (:type %)))
                  (mapv :delta)))
          "thinking deltas carry incremental text")
      (is (some #(= :text-delta (:type %)) events)
          "text block after thinking block still emits :text-delta")
      (is (not-any? #(and (= :text-delta (:type %))
                          (some-> (:delta %) (.contains "I think")))
                    events)
          "thinking text must not bleed into :text-delta events"))))

(deftest text-block-not-misrouted-as-thinking-test
  (testing "plain text blocks are not emitted as :thinking-delta"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hello"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (not-any? #(= :thinking-delta (:type %)) events))
      (is (some #(= :text-delta (:type %)) events)))))

(deftest usage-captured-from-sse-events-test
  (testing "usage tokens are read from message_start and message_delta SSE events"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start"
                               {:type    "message_start"
                                :message {:usage {:input_tokens                  100
                                                  :cache_read_input_tokens        20
                                                  :cache_creation_input_tokens    10}}})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hi"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "message_delta"
                               {:type  "message_delta"
                                :delta {:stop_reason "end_turn"}
                                :usage {:output_tokens 50}})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})
          done   (first (filter #(= :done (:type %)) events))
          usage  (:usage done)]
      (is (some? done) "should emit a :done event")
      (is (= 100 (:input-tokens usage))  "input-tokens from message_start")
      (is (= 50  (:output-tokens usage)) "output-tokens from message_delta")
      (is (= 20  (:cache-read-tokens usage))  "cache-read-tokens from message_start")
      (is (= 10  (:cache-write-tokens usage)) "cache-write-tokens from message_start")
      (is (= 180 (:total-tokens usage)) "total = input + output + cache-read + cache-write")
      (is (map? (:cost usage)) "cost map present"))))

(deftest transform-messages-normalizes-invalid-tool-ids-test
  (testing "assistant tool_use ids and tool_result tool_use_id are normalized to Anthropic-safe ids"
    (let [convo  {:messages [{:role :assistant
                              :content {:kind :structured
                                        :blocks [{:kind :tool-call
                                                  :id "call_abc|fc_123"
                                                  :name "read"
                                                  :input {"path" "README.md"}}]}}
                             {:role :tool-result
                              :tool-call-id "call_abc|fc_123"
                              :tool-name "read"
                              :content {:kind :text :text "ok"}
                              :is-error false}]}
          out    (anthropic/transform-messages convo)
          asst   (first out)
          user   (second out)
          use-id (get-in asst [:content 0 :id])
          res-id (get-in user [:content 0 :tool_use_id])]
      (is (= "assistant" (:role asst)))
      (is (= "tool_use" (get-in asst [:content 0 :type])))
      (is (= "user" (:role user)))
      (is (= "tool_result" (get-in user [:content 0 :type])))
      (is (= use-id res-id) "tool_result must reference normalized tool_use id")
      (is (re-matches #"^[a-zA-Z0-9_-]+$" use-id)
          "normalized id must satisfy Anthropic regex"))))
