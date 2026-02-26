(ns psi.agent-session.executor-test
  "Integration tests for the executor with per-turn statechart.

  Uses with-redefs on the private do-stream! to inject stub provider
  responses — no HTTP calls, no API keys required."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.turn-statechart :as turn-sc]))

;; ── Stub helpers ────────────────────────────────────────────

(defn- stub-text-stream
  "Return a do-stream! replacement that delivers a text-only response."
  [text]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :text-delta :delta text})
    (consume-fn {:type :done :reason :stop})))

(defn- stub-error-stream
  "Return a do-stream! replacement that delivers an error."
  [err-msg]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :error :error-message err-msg})))

(defn- setup-agent-ctx!
  "Create and initialise an agent-core context for testing."
  []
  (let [ctx (agent/create-context)]
    (agent/create-agent-in! ctx {:system-prompt "test prompt"
                                 :tools []})
    ctx))

(def ^:private stub-model
  {:provider "stub" :id "stub-model"})

;; ── Text-only response ──────────────────────────────────────

(deftest text-only-response-test
  (testing "run-agent-loop! produces text-only assistant message"
    (let [agent-ctx    (setup-agent-ctx!)
          turn-atom    (atom nil)
          user-msg     {:role    "user"
                        :content [{:type :text :text "hello"}]}]
      (with-redefs [psi.agent-session.executor/do-stream!
                    (stub-text-stream "Hello! I'm here to help.")]
        (let [result (executor/run-agent-loop!
                      nil agent-ctx stub-model [user-msg]
                      {:turn-ctx-atom turn-atom})]
          (is (= "assistant" (:role result)))
          (is (= :stop (:stop-reason result)))
          (let [text (some #(when (= :text (:type %)) (:text %))
                           (:content result))]
            (is (= "Hello! I'm here to help." text)))
          ;; Turn context was stored for introspection
          (is (some? @turn-atom))
          ;; Turn statechart reached :done
          (is (= :done (turn-sc/turn-phase @turn-atom))))))))

;; ── Error response ──────────────────────────────────────────

(deftest error-response-test
  (testing "run-agent-loop! handles provider error"
    (let [agent-ctx (setup-agent-ctx!)
          turn-atom (atom nil)
          user-msg  {:role "user" :content [{:type :text :text "hello"}]}]
      (with-redefs [psi.agent-session.executor/do-stream!
                    (stub-error-stream "Connection refused")]
        (let [result (executor/run-agent-loop!
                      nil agent-ctx stub-model [user-msg]
                      {:turn-ctx-atom turn-atom})]
          (is (= :error (:stop-reason result)))
          ;; Turn statechart reached :error
          (is (= :error (turn-sc/turn-phase @turn-atom)))
          (is (= "Connection refused"
                 (:error-message (turn-sc/get-turn-data @turn-atom)))))))))

;; ── Agent-core lifecycle ────────────────────────────────────

(deftest agent-core-lifecycle-test
  (testing "executor calls begin-stream, update-stream, end-stream"
    (let [agent-ctx (setup-agent-ctx!)
          user-msg  {:role "user" :content [{:type :text :text "hi"}]}]
      (with-redefs [psi.agent-session.executor/do-stream!
                    (stub-text-stream "response")]
        (executor/run-agent-loop! nil agent-ctx stub-model [user-msg])
        ;; Agent should be back in :idle
        (is (= :idle (agent/sc-phase-in agent-ctx)))
        ;; Messages should include user + assistant
        (let [msgs (:messages (agent/get-data-in agent-ctx))]
          (is (>= (count msgs) 2))
          (is (= "user" (:role (first msgs))))
          (is (= "assistant" (:role (second msgs)))))))))

;; ── Turn context atom ───────────────────────────────────────

(deftest turn-ctx-atom-test
  (testing "turn-ctx-atom is nil when not provided"
    (let [agent-ctx (setup-agent-ctx!)
          user-msg  {:role "user" :content [{:type :text :text "hi"}]}]
      (with-redefs [psi.agent-session.executor/do-stream!
                    (stub-text-stream "ok")]
        ;; Should not throw when turn-ctx-atom is nil
        (let [result (executor/run-agent-loop!
                      nil agent-ctx stub-model [user-msg])]
          (is (= "assistant" (:role result)))))))

  (testing "turn-ctx-atom reflects turn data"
    (let [agent-ctx (setup-agent-ctx!)
          turn-atom (atom nil)
          user-msg  {:role "user" :content [{:type :text :text "hi"}]}]
      (with-redefs [psi.agent-session.executor/do-stream!
                    (stub-text-stream "hello world")]
        (executor/run-agent-loop! nil agent-ctx stub-model [user-msg]
                                  {:turn-ctx-atom turn-atom})
        (let [td (turn-sc/get-turn-data @turn-atom)]
          (is (= "hello world" (:text-buffer td)))
          (is (some? (:final-message td))))))))

;; ── Multiple text deltas ────────────────────────────────────

(deftest multiple-text-deltas-test
  (testing "multiple text deltas are accumulated correctly"
    (let [agent-ctx (setup-agent-ctx!)
          turn-atom (atom nil)
          user-msg  {:role "user" :content [{:type :text :text "hi"}]}
          stream-fn (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "Hello"})
                      (consume-fn {:type :text-delta :delta " there"})
                      (consume-fn {:type :text-delta :delta "!"})
                      (consume-fn {:type :done :reason :stop}))]
      (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
        (let [result (executor/run-agent-loop!
                      nil agent-ctx stub-model [user-msg]
                      {:turn-ctx-atom turn-atom})]
          (is (= "Hello there!"
                 (some #(when (= :text (:type %)) (:text %))
                       (:content result)))))))))
