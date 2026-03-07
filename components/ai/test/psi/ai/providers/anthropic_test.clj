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

;; ── SSE parser — thinking block routing ─────────────────────────────────────

(defn- run-stream [sse-str model options]
  (let [events (atom [])
        convo  (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (with-redefs [http/post (fn [_url req]
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
