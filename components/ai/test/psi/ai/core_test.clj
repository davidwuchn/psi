(ns psi.ai.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [psi.ai.core :as core]
            [psi.ai.models :as models]
            [psi.ai.providers.anthropic :as anthropic]
            [psi.ai.schemas :as schemas]
            [psi.query.core :as query]
            [psi.query.registry :as registry]))

;; ───────────────────────────────────────────────────────────────────────────
;; Helpers
;; ───────────────────────────────────────────────────────────────────────────

(defmacro with-clean-query [& body]
  `(do
     (registry/reset-registry!)
     (query/rebuild-env!)
     (try
       ~@body
       (finally
         (registry/reset-registry!)
         (query/rebuild-env!)))))

;; Stub provider — no HTTP calls; delivers canned events via consume-fn.
(defn stub-provider
  "A provider stub that emits a single text delta then done."
  [text]
  {:name   :stub
   :stream (fn [_conversation _model _options consume-fn]
             (consume-fn {:type :start})
             (consume-fn {:type :text-delta :content-index 0 :delta text})
             (consume-fn {:type :done :reason :stop
                          :usage {:input-tokens 1 :output-tokens 1
                                  :total-tokens 2 :cost {:total 0.0}}}))})

;; ───────────────────────────────────────────────────────────────────────────
;; Conversation tests
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-conversation-creation
  (testing "Creating a conversation with system prompt"
    (let [conversation (core/create-conversation "You are a helpful assistant")]
      (is (schemas/valid? schemas/Conversation conversation))
      (is (= "You are a helpful assistant" (:system-prompt conversation)))
      (is (= :active (:status conversation)))
      (is (empty? (:messages conversation)))
      (is (empty? (:tools conversation)))))

  (testing "Creating a conversation without system prompt"
    (let [conversation (core/create-conversation)]
      (is (schemas/valid? schemas/Conversation conversation))
      (is (nil? (:system-prompt conversation)))
      (is (= :active (:status conversation))))))

(deftest test-message-handling
  (testing "Adding user message"
    (let [conversation (core/create-conversation "Test assistant")
          updated      (core/send-message conversation "Hello!")]
      (is (schemas/valid? schemas/Conversation updated))
      (is (= 1 (count (:messages updated))))
      (is (= :user (:role (first (:messages updated)))))
      (is (= "Hello!" (get-in (first (:messages updated)) [:content :text])))
      (is (= :text (get-in (first (:messages updated)) [:content :kind]))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Model tests
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-model-validation
  (testing "Claude model validation"
    (let [model (models/get-model :claude-3-5-sonnet)]
      (is (schemas/valid? schemas/Model model))
      (is (= :anthropic (:provider model)))
      (is (= :anthropic-messages (:api model)))))

  (testing "OpenAI model validation"
    (let [model (models/get-model :gpt-4o)]
      (is (schemas/valid? schemas/Model model))
      (is (= :openai (:provider model)))
      (is (= :openai-completions (:api model))))))

(deftest test-stream-options-validation
  (testing "Valid stream options"
    (let [options {:temperature 0.7
                   :max-tokens 1000
                   :cache-retention :short}]
      (is (schemas/valid? schemas/StreamOptions options))))

  (testing "Invalid stream options"
    (let [options {:temperature 3.0   ; Too high
                   :max-tokens  -1}]  ; Negative
      (is (not (schemas/valid? schemas/StreamOptions options))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Usage / cost calculation tests
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-usage-calculation
  (testing "Total usage calculation"
    (let [conversation (-> (core/create-conversation)
                           (core/send-message "Test"))
          ;; Inject a mock assistant message with usage
          updated      (update conversation :messages conj
                                {:id          "test-id"
                                 :role        :assistant
                                 :content     {:kind :text :text "Response"}
                                 :timestamp   (java.time.Instant/now)
                                 :usage       {:input-tokens  10
                                               :output-tokens 20
                                               :total-tokens  30
                                               :cost          {:input 0.01 :output 0.02
                                                               :cache-read 0.0
                                                               :cache-write 0.0
                                                               :total 0.03}}})]
      (is (= 30 (:total-tokens (core/get-conversation-usage updated))))
      (is (= 0.03 (:total (core/get-conversation-cost updated)))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Streaming — callback API
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-stream-response-callback
  (testing "stream-response delivers events via consume-fn on a background thread"
    (let [conversation (-> (core/create-conversation "assistant")
                           (core/send-message "hi"))
          model        (models/get-model :claude-3-5-sonnet)
          options      {:temperature 0.5}
          events       (atom [])
          provider     (stub-provider "hello")]

      ;; Temporarily register the stub provider
      (core/register-provider! :anthropic provider)

      (let [{:keys [future session]}
            (core/stream-response conversation model options
                                   (fn [ev] (swap! events conj ev)))]
        ;; Wait for background thread to finish
        @future
        (is (= :completed (:status @session)))
        (is (some #(= :text-delta (:type %)) @events))
        (is (some #(= :done (:type %)) @events))
        (let [delta-event (first (filter #(= :text-delta (:type %)) @events))]
          (is (= "hello" (:delta delta-event)))))

      ;; Restore real provider
      (core/register-provider! :anthropic anthropic/provider))))

;; ───────────────────────────────────────────────────────────────────────────
;; Streaming — lazy-seq API
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-stream-response-seq
  (testing "stream-response-seq returns a lazy sequence of events"
    (let [conversation (-> (core/create-conversation "assistant")
                           (core/send-message "hi"))
          model        (models/get-model :claude-3-5-sonnet)
          options      {:temperature 0.5}
          provider     (stub-provider "world")]

      (core/register-provider! :anthropic provider)

      (let [{:keys [events session]}
            (core/stream-response-seq conversation model options)
            all-events (doall events)]
        (is (some #(= :text-delta (:type %)) all-events))
        (is (some #(= :done (:type %)) all-events))
        (is (= :completed (:status @session))))

      (core/register-provider! :anthropic anthropic/provider))))

;; ───────────────────────────────────────────────────────────────────────────
;; Query integration — resolvers registered in EQL graph
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-query-resolvers
  (testing "AI resolvers are queryable via EQL after register-resolvers!"
    (with-clean-query
      (core/register-resolvers!)

      (testing "ai/all-models resolver"
        (let [result (query/query {} [:ai/all-models])]
          (is (map? (:ai/all-models result)))
          (is (contains? (:ai/all-models result) :claude-3-5-sonnet))))

      (testing "ai.model/data resolver"
        (let [result (query/query {:ai.model/key :gpt-4o} [:ai.model/data])]
          (is (= :openai (get-in result [:ai.model/data :provider])))))

      (testing "ai/provider-models resolver"
        (let [result (query/query {:ai/provider :anthropic} [:ai/provider-models])]
          (is (map? (:ai/provider-models result)))
          (is (every? #(= :anthropic (:provider %))
                      (vals (:ai/provider-models result))))))

      (testing "ai/registered-providers resolver"
        (let [result (query/query {} [:ai/registered-providers])]
          (is (set? (:ai/registered-providers result)))
          (is (contains? (:ai/registered-providers result) :anthropic))
          (is (contains? (:ai/registered-providers result) :openai)))))))
