(ns psi.ai.core-test
  "Tests for psi.ai.core.

  Uses core/create-context (Nullable pattern) so streaming tests get their
  own isolated provider registry — no global-state mutations."
  (:require [clojure.test :refer [deftest testing is]]
            [psi.ai.core :as core]
            [psi.ai.models :as models]
            [psi.ai.schemas :as schemas]
            [psi.query.core :as query]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Stub provider — no HTTP calls; emits canned events synchronously
;; ─────────────────────────────────────────────────────────────────────────────

(defn- stub-provider
  "Return a provider that emits a single text-delta then done."
  [text]
  {:name   :stub
   :stream (fn [_conversation _model _options consume-fn]
             (consume-fn {:type :start})
             (consume-fn {:type :text-delta :content-index 0 :delta text})
             (consume-fn {:type :done :reason :stop
                          :usage {:input-tokens 1 :output-tokens 1
                                  :total-tokens 2 :cost {:total 0.0}}}))})

;; ─────────────────────────────────────────────────────────────────────────────
;; Model tests — pure logic
;; ─────────────────────────────────────────────────────────────────────────────

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
      (is (= :openai-completions (:api model)))))

  (testing "GPT-5 Codex family models are registered"
    (doseq [k [:gpt-5.2-codex :gpt-5.3-codex :gpt-5.3-codex-spark :gpt-5.4]]
      (let [model (models/get-model k)]
        (is (schemas/valid? schemas/Model model) (str "valid model schema for " k))
        (is (= :openai (:provider model)) (str "openai provider for " k))
        (is (= :openai-codex-responses (:api model))
            (str "codex api for " k))))))

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

;; ─────────────────────────────────────────────────────────────────────────────
;; Streaming — callback API (isolated context, stub provider)
;; ─────────────────────────────────────────────────────────────────────────────

(deftest test-stream-response-callback
  (testing "stream-response-in delivers events via consume-fn on a background thread"
    (let [conversation (-> (core/create-conversation "assistant")
                           (core/send-message "hi"))
          model        (models/get-model :claude-3-5-sonnet)
          options      {:temperature 0.5}
          events       (atom [])
          provider     (stub-provider "hello")
          ctx          (core/create-context {:providers {:anthropic provider}})
          {bg :future
           session :session}
          (core/stream-response-in ctx conversation model options
                                   (fn [ev] (swap! events conj ev)))]
      @bg
      (is (= :completed (:status @session)))
      (is (some #(= :text-delta (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (= "hello" (:delta (first (filter #(= :text-delta (:type %)) @events))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Streaming — lazy-seq API (isolated context, stub provider)
;; ─────────────────────────────────────────────────────────────────────────────

(deftest test-stream-response-seq
  (testing "stream-response-seq-in returns a lazy sequence of events"
    (let [conversation (-> (core/create-conversation "assistant")
                           (core/send-message "hi"))
          model        (models/get-model :claude-3-5-sonnet)
          options      {:temperature 0.5}
          provider     (stub-provider "world")
          ctx          (core/create-context {:providers {:anthropic provider}})
          {events :events
           session :session} (core/stream-response-seq-in ctx conversation model options)
          all-events         (doall events)]
      (is (some #(= :text-delta (:type %)) all-events))
      (is (some #(= :done (:type %)) all-events))
      (is (= :completed (:status @session)))))

  (testing "stream-response-seq-in ends when the provider returns without a terminal event"
    (let [conversation (-> (core/create-conversation "assistant")
                           (core/send-message "hi"))
          model        (models/get-model :claude-3-5-sonnet)
          options      {:temperature 0.5}
          provider     {:name   :stub
                        :stream (fn [_conversation _model _options consume-fn]
                                  (consume-fn {:type :start})
                                  (consume-fn {:type :text-delta :content-index 0 :delta "partial"}))}
          ctx          (core/create-context {:providers {:anthropic provider}})
          {events :events
           session :session} (core/stream-response-seq-in ctx conversation model options)
          all-events         (doall events)]
      (is (= [:start :text-delta] (mapv :type all-events)))
      (is (= :completed (:status @session))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Query integration — resolvers registered in EQL graph
;; ─────────────────────────────────────────────────────────────────────────────

(deftest test-query-resolvers
  (testing "AI resolvers are queryable via EQL after register-resolvers-in!"
    ;; Use an isolated query context so the global graph is untouched
    (let [qctx (query/create-query-context)]
      (core/register-resolvers-in! qctx)

      (testing "ai/all-models resolver"
        (let [result (query/query-in qctx {} [:ai/all-models])]
          (is (map? (:ai/all-models result)))
          (is (contains? (:ai/all-models result) :claude-3-5-sonnet))))

      (testing "ai.model/data resolver"
        (let [result (query/query-in qctx {:ai.model/key :gpt-4o} [:ai.model/data])]
          (is (= :openai (get-in result [:ai.model/data :provider])))))

      (testing "ai/provider-models resolver"
        (let [result (query/query-in qctx {:ai/provider :anthropic} [:ai/provider-models])]
          (is (map? (:ai/provider-models result)))
          (is (every? #(= :anthropic (:provider %))
                      (vals (:ai/provider-models result))))))

      (testing "ai/registered-providers resolver returns a set of provider keys"
        ;; This resolver reads the global provider-registry atom; we verify
        ;; structure and presence of the default providers.
        (let [result (query/query-in qctx {} [:ai/registered-providers])]
          (is (set? (:ai/registered-providers result)))
          (is (contains? (:ai/registered-providers result) :anthropic))
          (is (contains? (:ai/registered-providers result) :openai)))))))
