(ns psi.agent-session.executor-test
  "Integration tests for the executor with per-turn statechart.

  Uses with-redefs on private vars to inject stub provider/tool behavior
  (no HTTP calls, no API keys required)."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.turn-statechart :as turn-sc])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- stub-text-stream
  [text]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :text-delta :delta text})
    (consume-fn {:type :done :reason :stop})))

(defn- stub-error-stream
  [err-msg]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :error :error-message err-msg})))

(defn- setup-agent-ctx!
  []
  (let [ctx (agent/create-context)]
    (agent/create-agent-in! ctx {:system-prompt "test prompt"
                                 :tools []})
    ctx))

(defn- setup-session-ctx!
  [agent-ctx]
  (test-support/make-session-ctx {:agent-ctx agent-ctx}))

(def ^:private stub-model
  {:provider "stub" :id "stub-model"})

(deftest text-only-response-test
  (let [agent-ctx    (setup-agent-ctx!)
        session-ctx  (setup-session-ctx! agent-ctx)
        user-msg     {:role "user" :content [{:type :text :text "hello"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "Hello! I'm here to help.")]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg])]
        (is (= "assistant" (:role result)))
        (is (= :stop (:stop-reason result)))
        (is (= "Hello! I'm here to help."
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))
        (let [turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx (ss/active-session-id-in session-ctx)))]
          (is (some? turn-ctx))
          (is (= :done (turn-sc/turn-phase turn-ctx))))))))

(deftest error-response-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hello"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-error-stream "Connection refused")]
      (let [result   (executor/run-agent-loop!
                      nil session-ctx agent-ctx stub-model [user-msg])
            turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx (ss/active-session-id-in session-ctx)))]
        (is (= :error (:stop-reason result)))
        (is (= :error (turn-sc/turn-phase turn-ctx)))
        (is (= "Connection refused"
               (:error-message (turn-sc/get-turn-data turn-ctx))))))))

(deftest agent-core-lifecycle-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "response")]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])
      (is (= :idle (agent/sc-phase-in agent-ctx)))
      (let [msgs (:messages (agent/get-data-in agent-ctx))]
        (is (>= (count msgs) 2))
        (is (= "user" (:role (first msgs))))
        (is (= "assistant" (:role (second msgs))))))))

(deftest turn-ctx-atom-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "ok")]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg])]
        (is (= "assistant" (:role result))))))

  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "hello world")]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])
      (let [turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx (ss/active-session-id-in session-ctx)))
            td       (turn-sc/get-turn-data turn-ctx)]
        (is (= "hello world" (:text-buffer td)))
        (is (some? (:final-message td)))))))

(deftest multiple-text-deltas-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "Hello"})
                      (consume-fn {:type :text-delta :delta " there"})
                      (consume-fn {:type :text-delta :delta "!"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg])]
        (is (= "Hello there!"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest thinking-delta-emits-progress-event-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        q           (LinkedBlockingQueue.)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                {:progress-queue q})
      (let [events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            thinking (some #(when (= :thinking-delta (:event-kind %)) %) events)
            td       (turn-sc/get-turn-data (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx (ss/active-session-id-in session-ctx))))]
        (is (some? thinking))
        (is (= "plan" (:text thinking)))
        (is (= :done (get-in td [:last-provider-event :type])))
        (is (= :thinking (get-in td [:content-blocks 0 :kind])))
        (is (= 1 (get-in td [:content-blocks 0 :delta-count])))))))

(deftest thinking-delta-cumulative-snapshot-normalised-test
  "Anthropic sends thinking_delta events as cumulative snapshots (each event
  contains the full thinking text so far). Verify that the executor normalises
  these into non-duplicating accumulated text so consumers can safely replace."
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        q           (LinkedBlockingQueue.)
        ;; Simulate Anthropic cumulative snapshots: each delta = full text so far
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "Now"})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "Now I see"})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "Now I see the flow"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                {:progress-queue q})
      (let [events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            thinking-events (filterv #(= :thinking-delta (:event-kind %)) events)
            last-thinking   (last thinking-events)]
        ;; Each emitted event should carry the full accumulated text (replace semantics)
        (is (= 3 (count thinking-events)))
        (is (= "Now"              (:text (nth thinking-events 0))))
        (is (= "Now I see"        (:text (nth thinking-events 1))))
        (is (= "Now I see the flow" (:text last-thinking)))))))

(deftest thinking-delta-resets-after-toolcall-start-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        q           (LinkedBlockingQueue.)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan-1"})
                      (consume-fn {:type :toolcall-start :content-index 0 :id "t1" :name "read"})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan-2"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                {:progress-queue q})
      (let [events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            thinking-events (filterv #(= :thinking-delta (:event-kind %)) events)]
        (is (= ["plan-1" "plan-2"] (mapv :text thinking-events)))))))

(deftest cross-provider-thinking-is-not-replayed-into-anthropic-request-test
  (testing "OpenAI thinking deltas remain transient and are not included in later Anthropic messages"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data (ss/active-session-id-in session-ctx))
                                                 assoc :thinking-level :high)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}
          openai-model {:provider "openai" :id "gpt-5.4"}
          openai-turn (fn [_ai-ctx _conv _model _opts consume-fn]
                        (consume-fn {:type :start})
                        (consume-fn {:type :thinking-delta :content-index 0 :delta "Plan step"})
                        (consume-fn {:type :text-delta :content-index 1 :delta "Done"})
                        (consume-fn {:type :done :reason :stop}))]
      (with-redefs [psi.agent-session.executor/do-stream! openai-turn]
        (let [result (executor/run-agent-loop! nil session-ctx agent-ctx openai-model [user-msg])]
          (is (= :stop (:stop-reason result)))
          (is (= "Done"
                 (some #(when (= :text (:type %)) (:text %))
                       (:content result))))))
      (let [messages   (:messages (agent/get-data-in agent-ctx))
            assistant  (last messages)
            anthropic-model (models/get-model :sonnet-4.6)
            conv       (#'executor/agent-messages->ai-conversation
                        "sys" messages [] {:cache-breakpoints #{:system}})
            body       (json/parse-string
                        (:body (#'anthropic/build-request conv anthropic-model {:api-key "test-key"
                                                                                :thinking-level :high}))
                        true)]
        (is (= "assistant" (:role assistant)))
        (is (= [{:type :text :text "Done"}]
               (:content assistant))
            "final persisted assistant message should exclude transient thinking")
        (is (not (re-find #"Plan step" (pr-str body)))
            "OpenAI thinking must not be replayed into Anthropic request body")
        (is (= ["user" "assistant"]
               (mapv :role (:messages body))))
        (is (= "Done"
               (get-in body [:messages 1 :content 0 :text])))))))

(deftest anthropic-thinking-blocks-roundtrip-into-follow-up-request-test
  (testing "Anthropic thinking blocks with signatures are preserved for the next Anthropic request"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data (ss/active-session-id-in session-ctx))
                                                 assoc :thinking-level :high)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}
          anthropic-model {:provider "anthropic" :id "claude-sonnet-4-6"}
          stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                        (consume-fn {:type :start})
                        (consume-fn {:type :thinking-start :content-index 0 :thinking "" :signature ""})
                        (consume-fn {:type :thinking-delta :content-index 0 :delta "Plan"})
                        (consume-fn {:type :thinking-signature-delta :content-index 0 :signature "sig-1"})
                        (consume-fn {:type :toolcall-start :content-index 1 :id "call_1" :name "read"})
                        (consume-fn {:type :toolcall-delta :content-index 1 :delta "{\"path\":\"README.md\"}"})
                        (consume-fn {:type :toolcall-end :content-index 1})
                        (consume-fn {:type :done :reason :tool_use}))]
      (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
        (agent/start-loop-in! agent-ctx [user-msg])
        (let [result (#'executor/stream-turn! nil session-ctx agent-ctx anthropic-model nil nil)
              conv   (#'executor/agent-messages->ai-conversation
                      "sys" (:messages (agent/get-data-in agent-ctx)) [] {:cache-breakpoints #{:system}})
              body   (json/parse-string
                      (:body (#'anthropic/build-request conv (models/get-model :sonnet-4.6)
                                                        {:api-key "test-key"
                                                         :thinking-level :high}))
                      true)
              assistant-blocks (get-in body [:messages 1 :content])]
          (is (= :tool_use (:stop-reason result)))
          (is (= "Plan" (some #(when (= :thinking (:type %)) (:text %))
                              (:content result))))
          (is (= "thinking" (get-in assistant-blocks [0 :type])))
          (is (= "Plan" (get-in assistant-blocks [0 :thinking])))
          (is (= "sig-1" (get-in assistant-blocks [0 :signature])))
          (is (= "tool_use" (get-in assistant-blocks [1 :type])))
          (is (= "read" (get-in assistant-blocks [1 :name])))
          (is (str/includes? (pr-str body) "sig-1")))))))

(deftest idle-timeout-resets-on-stream-progress-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (future
                        (consume-fn {:type :start})
                        (Thread/sleep 120)
                        (consume-fn {:type :thinking-delta :delta "plan-1"})
                        (Thread/sleep 120)
                        (consume-fn {:type :thinking-delta :delta "plan-2"})
                        (Thread/sleep 120)
                        (consume-fn {:type :done :reason :stop})))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn
                  psi.agent-session.executor/llm-stream-idle-timeout-ms 200
                  psi.agent-session.executor/llm-stream-wait-poll-ms 20]
      (let [result (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])]
        (is (= :stop (:stop-reason result)))))))

(deftest idle-timeout-errors-when-stream-stalls-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (future
                        (consume-fn {:type :start})
                        (Thread/sleep 260)
                        (consume-fn {:type :done :reason :stop})))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn
                  psi.agent-session.executor/llm-stream-idle-timeout-ms 120
                  psi.agent-session.executor/llm-stream-wait-poll-ms 20]
      (let [result   (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])
            turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx (ss/active-session-id-in session-ctx)))
            td       (turn-sc/get-turn-data turn-ctx)]
        (is (= :error (:stop-reason result)))
        (is (= "Timeout waiting for LLM response" (:error-message result)))
        (is (= :error (get-in td [:last-provider-event :type])))
        (is (= "Timeout waiting for LLM response"
               (get-in td [:last-provider-event :error-message])))))))

(deftest thinking-level-is-forwarded-to-ai-options-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data (ss/active-session-id-in session-ctx))
                                               assoc :thinking-level :high)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        seen-opts   (atom nil)
        stream-fn   (fn [_ai-ctx _conv _model opts consume-fn]
                      (reset! seen-opts opts)
                      (consume-fn {:type :start})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])
      (is (= :high (:thinking-level @seen-opts))))))

(deftest session-idle-timeout-config-is-forwarded-to-ai-options-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (assoc (setup-session-ctx! agent-ctx)
                           :config {:llm-stream-idle-timeout-ms 777})
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        seen-opts   (atom nil)
        stream-fn   (fn [_ai-ctx _conv _model opts consume-fn]
                      (reset! seen-opts opts)
                      (consume-fn {:type :start})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])
      (is (= 777 (:llm-stream-idle-timeout-ms @seen-opts))))))

(deftest text-boundary-events-are-recorded-in-turn-data-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-start :content-index 0})
                      (consume-fn {:type :text-delta :content-index 0 :delta "Hello"})
                      (consume-fn {:type :text-end :content-index 0})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (let [result   (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])
            turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx (ss/active-session-id-in session-ctx)))
            td       (turn-sc/get-turn-data turn-ctx)]
        (is (= "Hello"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))
        (is (= :done (get-in td [:last-provider-event :type])))
        (is (= :text (get-in td [:content-blocks 0 :kind])))
        (is (= :closed (get-in td [:content-blocks 0 :status])))
        (is (= 1 (get-in td [:content-blocks 0 :delta-count])))))))

(deftest cumulative-snapshot-text-deltas-replace-instead-of-repeating-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      ;; Snapshot-style chunks that differ near tail (newline churn)
                      ;; should converge to the latest snapshot, not repeat cumulatively.
                      (consume-fn {:type :text-delta :delta "H\n"})
                      (consume-fn {:type :text-delta :delta "He\n"})
                      (consume-fn {:type :text-delta :delta "Hel\n"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (let [result (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])]
        (is (= "Hel\n"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest incremental-short-prefix-delta-does-not-shrink-streamed-text-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      ;; Reproduces the real regression where a short incremental delta
                      ;; ("`") replaced the whole in-progress text.
                      (consume-fn {:type :text-delta :delta "`deps.edn"})
                      (consume-fn {:type :text-delta :delta "`"})
                      (consume-fn {:type :text-delta :delta " contents:"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (let [result (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])]
        (is (= "`deps.edn` contents:"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest custom-type-messages-excluded-from-llm-conversation-test
  (testing "messages with :custom-type are filtered out before LLM call"
    ;; PSL extension appends assistant-role custom-type messages as transcript markers.
    ;; These must not reach the LLM — consecutive assistant messages cause Anthropic 400.
    (let [messages
          [{:role "user"    :content [{:type :text :text "hello"}]}
           {:role "assistant" :content [{:type :text :text "hi there"}]}
           ;; PSL send-message! — assistant role, custom-type marker
           {:role "assistant" :content [{:type :text :text "PSL sync start."}]
            :custom-type "plan-state-learning"}
           {:role "user"    :content [{:type :text :text "PSL follow-up"}]}]
          conv (#'psi.agent-session.executor/agent-messages->ai-conversation
                "sys" messages [] {})
          roles (mapv :role (:messages conv))]
      (is (= [:user :assistant :user] roles)
          "custom-type assistant message is excluded; no consecutive assistant messages")
      (is (not-any? :custom-type (:messages conv))
          "no custom-type keys in LLM conversation messages")))

  (testing "assistant messages with no text, thinking, or tool-call blocks are skipped"
    (let [messages
          [{:role "user"      :content [{:type :text :text "q"}]}
           {:role "assistant" :content [{:type :text :text ""}]}
           {:role "user"      :content [{:type :text :text "q2"}]}]
          conv (#'psi.agent-session.executor/agent-messages->ai-conversation
                "sys" messages [] {})
          roles (mapv :role (:messages conv))]
      (is (= [:user :user] roles)
          "empty assistant text message should not produce an empty text content block")))

  (testing "non-custom-type messages are all included"
    (let [messages
          [{:role "user"      :content [{:type :text :text "q"}]}
           {:role "assistant" :content [{:type :text :text "a"}]}
           {:role "user"      :content [{:type :text :text "q2"}]}]
          conv (#'psi.agent-session.executor/agent-messages->ai-conversation
                "sys" messages [] {})
          roles (mapv :role (:messages conv))]
      (is (= [:user :assistant :user] roles))))

  (testing "consecutive user messages remain separate conversation messages"
    (let [messages
          [{:role "user" :content [{:type :text :text "u1"}]}
           {:role "user" :content [{:type :text :text "u2"}]}
           {:role "assistant" :content [{:type :text :text "a"}]}]
          conv (#'psi.agent-session.executor/agent-messages->ai-conversation
                "sys" messages [] {})]
      (is (= [:user :user :assistant]
             (mapv :role (:messages conv))))
      (is (= {:kind :text :text "u1"}
             (:content (first (:messages conv)))))
      (is (= {:kind :text :text "u2"}
             (:content (second (:messages conv))))))))

(deftest cache-breakpoints-are-projected-into-ai-conversation-test
  (let [messages []
        tools    [{:name "read"
                   :description "Read file"
                   :parameters "{:type \"object\"}"}]
        prompt   (str "stable"
                      "\nCurrent date and time: Friday, March 13, 2026 at 11:00:00 am GMT-04:00"
                      "\nCurrent working directory: /tmp/main"
                      "\nCurrent worktree directory: /tmp/main")
        conv     (#'psi.agent-session.executor/agent-messages->ai-conversation
                  prompt messages tools {:cache-breakpoints #{:system :tools}})]
    (is (= [{:kind :text
             :text "stable"
             :cache-control {:type :ephemeral}}
            {:kind :text
             :text (str "\nCurrent date and time: Friday, March 13, 2026 at 11:00:00 am GMT-04:00"
                        "\nCurrent working directory: /tmp/main"
                        "\nCurrent worktree directory: /tmp/main")}]
           (:system-prompt-blocks conv)))
    (is (= {:type :ephemeral}
           (:cache-control (first (:tools conv)))))))

(deftest classify-turn-outcome-test
  (testing "text-only assistant message is terminal stop"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :text :text "done"}]
                         :stop-reason :stop}
          outcome (#'executor/classify-turn-outcome assistant-msg)]
      (is (= :turn.outcome/stop (:turn/outcome outcome)))
      (is (= assistant-msg (:assistant-message outcome)))
      (is (nil? (:tool-calls outcome)))))

  (testing "assistant message with tool-call content is a tool-use outcome"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :text :text "checking"}
                                   {:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                         :stop-reason :tool_use}
          outcome (#'executor/classify-turn-outcome assistant-msg)]
      (is (= :turn.outcome/tool-use (:turn/outcome outcome)))
      (is (= assistant-msg (:assistant-message outcome)))
      (is (= [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
             (:tool-calls outcome)))))

  (testing "error assistant message is terminal error even if malformed tool-call content is present"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :error :text "boom"}
                                   {:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                         :stop-reason :error}
          outcome (#'executor/classify-turn-outcome assistant-msg)]
      (is (= :turn.outcome/error (:turn/outcome outcome)))
      (is (= assistant-msg (:assistant-message outcome)))
      (is (nil? (:tool-calls outcome))))))

(deftest agent-loop-options-test
  (testing "builds effective AI options from api key, thinking level, and idle timeout"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (assoc (setup-session-ctx! agent-ctx)
                             :config {:llm-stream-idle-timeout-ms 777})
          _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data (ss/active-session-id-in session-ctx))
                                                 assoc :thinking-level :high)
          opts        (#'executor/agent-loop-options session-ctx {:api-key "secret"})]
      (is (= "secret" (:api-key opts)))
      (is (= :high (:thinking-level opts)))
      (is (= 777 (:llm-stream-idle-timeout-ms opts))))))

(deftest finish-agent-loop-test
  (testing "success path ends the loop normally"
    (let [agent-ctx (setup-agent-ctx!)
          result    {:role "assistant" :content [] :stop-reason :stop}
          calls     (atom [])]
      (with-redefs [agent/end-loop-in! (fn [_] (swap! calls conj :end))
                    agent/end-loop-on-error-in! (fn [_ _] (swap! calls conj :error-end))]
        (is (= result (#'executor/finish-agent-loop! agent-ctx result)))
        (is (= [:end] @calls)))))

  (testing "error path ends the loop with error"
    (let [agent-ctx (setup-agent-ctx!)
          result    {:role "assistant" :content [] :stop-reason :error :error-message "boom"}
          calls     (atom [])]
      (with-redefs [agent/end-loop-in! (fn [_] (swap! calls conj :end))
                    agent/end-loop-on-error-in! (fn [_ msg] (swap! calls conj [:error-end msg]))]
        (is (= result (#'executor/finish-agent-loop! agent-ctx result)))
        (is (= [[:error-end "boom"]] @calls))))))

(deftest run-agent-loop-lifecycle-test
  (testing "run-agent-loop! separates start, body execution, and finish"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}
          calls       (atom [])]
      (with-redefs [agent/start-loop-in! (fn [_ msgs] (swap! calls conj [:start msgs]))
                    psi.agent-session.executor/run-agent-loop-body!
                    (fn [_ _ _ _ extra-ai-options progress-queue]
                      (swap! calls conj [:body extra-ai-options progress-queue])
                      {:role "assistant" :content [{:type :text :text "done"}] :stop-reason :stop})
                    psi.agent-session.executor/finish-agent-loop!
                    (fn [_ result]
                      (swap! calls conj [:finish (:stop-reason result)])
                      result)]
        (let [result (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                               {:api-key "k"})]
          (is (= :stop (:stop-reason result)))
          (is (= :start (ffirst @calls)))
          (is (= :body (first (second @calls))))
          (is (= :finish (first (nth @calls 2)))))))))

(deftest execute-one-turn-test
  (testing "single-turn execution returns assistant message and explicit outcome"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
      (agent/start-loop-in! agent-ctx [user-msg])
      (with-redefs [psi.agent-session.executor/do-stream!
                    (stub-text-stream "one-turn")]
        (let [result (#'executor/execute-one-turn! nil session-ctx agent-ctx stub-model nil nil)]
          (is (= "assistant" (get-in result [:assistant-message :role])))
          (is (= :turn.outcome/stop (get-in result [:outcome :turn/outcome])))
          (is (= "one-turn"
                 (some #(when (= :text (:type %)) (:text %))
                       (get-in result [:assistant-message :content])))))))))

(deftest run-turn-loop-test
  (testing "multi-turn loop separates one-turn execution from recursive control"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          calls       (atom [])]
      (with-redefs [psi.agent-session.executor/execute-one-turn!
                    (fn [_ _ _ _ _ _]
                      (let [n (count @calls)]
                        (if (zero? n)
                          {:assistant-message {:role "assistant"
                                               :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                                               :stop-reason :tool_use}
                           :outcome {:turn/outcome :turn.outcome/tool-use
                                     :assistant-message {:role "assistant"
                                                         :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                                                         :stop-reason :tool_use}
                                     :tool-calls [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]}}
                          {:assistant-message {:role "assistant"
                                               :content [{:type :text :text "done"}]
                                               :stop-reason :stop}
                           :outcome {:turn/outcome :turn.outcome/stop
                                     :assistant-message {:role "assistant"
                                                         :content [{:type :text :text "done"}]
                                                         :stop-reason :stop}}})))
                    psi.agent-session.executor/continue-after-tool-use!
                    (fn [_ outcome _]
                      (swap! calls conj (:turn/outcome outcome))
                      {:turn/continuation :turn.continue/next-turn
                       :assistant-message (:assistant-message outcome)
                       :tool-results [{:tool-call-id "call-1"}]})]
        (let [result (#'executor/run-turn-loop! nil session-ctx agent-ctx stub-model nil nil)]
          (is (= [":turn.outcome/tool-use"] (mapv str @calls)))
          (is (= :stop (:stop-reason result)))
          (is (= "done"
                 (some #(when (= :text (:type %)) (:text %))
                       (:content result)))))))))

(deftest continue-after-tool-use-test
  (testing "tool-use continuation executes all tool calls and returns next-turn continuation"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          outcome     {:turn/outcome :turn.outcome/tool-use
                       :assistant-message {:role "assistant"
                                           :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                                                     {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]
                                           :stop-reason :tool_use}
                       :tool-calls [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                                    {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]}
          calls       (atom [])]
      (with-redefs [psi.agent-session.executor/run-tool-call!
                    (fn [_ tc _]
                      (swap! calls conj (:id tc))
                      {:role "toolResult"
                       :tool-call-id (:id tc)
                       :tool-name (:name tc)
                       :content [{:type :text :text (str "ok-" (:id tc))}]})]
        (let [result (#'executor/continue-after-tool-use! session-ctx outcome nil)]
          (is (= :turn.continue/next-turn (:turn/continuation result)))
          (is (= ["call-1" "call-2"] @calls))
          (is (= ["call-1" "call-2"] (mapv :tool-call-id (:tool-results result))))
          (is (= (:assistant-message outcome) (:assistant-message result))))))))

(deftest tool-lifecycle-progress-derived-from-canonical-event-test
  (testing "progress projection uses the same canonical lifecycle event shape"
    (let [q      (LinkedBlockingQueue.)
          event  {:event-kind :tool-result
                  :tool-id "call-proj"
                  :tool-name "read"
                  :content [{:type :text :text "ok"}]
                  :result-text "ok"
                  :details {:phase :done}
                  :is-error false}]
      (#'executor/emit-progress! q event)
      (let [projected (.poll q 5 TimeUnit/MILLISECONDS)]
        (is (= :agent-event (:type projected)))
        (is (= event (dissoc projected :type)))))))

(deftest dispatch-visible-tool-lifecycle-test
  (testing "tool lifecycle stages are appended through dispatch-visible session events"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-life" :name "read" :arguments "{}"}]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content "done"
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'executor/run-tool-call! session-ctx tc nil)
        (let [events (ss/get-state-value-in session-ctx (ss/state-path :tool-lifecycle-events (ss/active-session-id-in session-ctx)))
              lifecycle (filterv #(contains? #{:tool-start :tool-executing :tool-execution-update :tool-result}
                                             (:event-kind %))
                                 events)]
          (is (= [:tool-start :tool-executing :tool-execution-update :tool-result]
                 (mapv :event-kind lifecycle)))
          (is (= "call-life" (:tool-id (first lifecycle))))
          (is (= "read" (:tool-name (first lifecycle)))))))))

(deftest runtime-effect-tool-run-helper-test
  (testing "runtime tool-run helper owns the full tool-call transaction"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-effect" :name "read" :arguments "{}"}
          events      (atom [])]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "done"
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)
                    dispatch/dispatch!
                    (let [orig dispatch/dispatch!]
                      (fn [ctx event-type event-data opts]
                        (swap! events conj event-type)
                        (orig ctx event-type event-data opts)))]
        (let [result (executor/run-tool-call-through-runtime-effect! session-ctx tc {} nil)]
          (is (= "call-effect" (:tool-call-id result)))
          (is (some #{:session/tool-agent-start} @events))
          (is (some #{:session/tool-execute} @events))
          (is (some #{:session/tool-agent-end} @events))
          (is (some #{:session/tool-agent-record-result} @events)))))))

(deftest execute-tool-call-test
  (testing "tool execution is shaped before recording"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-x" :name "read" :arguments "{}"}
          q           (LinkedBlockingQueue.)]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content [{:type :text :text "hello"}]
                       :is-error false
                       :details {:truncation {:truncated false}}})]
        (let [result (#'executor/execute-tool-call! session-ctx tc q)]
          (is (= tc (:tool-call result)))
          (is (= "call-x" (get-in result [:result-message :tool-call-id])))
          (is (= [{:type :text :text "hello"}] (get-in result [:result-message :content])))
          (is (= false (get-in result [:tool-result :is-error])))
          (is (= 1000 (get-in result [:effective-policy :max-lines])))
          (is (= 25600 (get-in result [:effective-policy :max-bytes]))))))))

(deftest tool-run-dispatch-boundary-test
  (testing "tool execution now enters through one explicit session/tool-run dispatch boundary"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-dispatch" :name "read" :arguments "{}"}
          q           (LinkedBlockingQueue.)
          events      (atom [])]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "hello"
                       :is-error false})
                    dispatch/dispatch!
                    (let [orig dispatch/dispatch!]
                      (fn [ctx event-type event-data opts]
                        (swap! events conj event-type)
                        (orig ctx event-type event-data opts)))]
        (let [result (#'executor/run-tool-call! session-ctx tc q)]
          (is (= "call-dispatch" (:tool-call-id result)))
          (is (some #{:session/tool-run} @events))
          (is (some #{:session/tool-execute} @events)))))))

(deftest record-tool-call-result-test
  (testing "recording step emits progress, telemetry, and agent-core result from shaped execution"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          q           (LinkedBlockingQueue.)
          recorded    (atom nil)
          shaped      {:tool-call {:id "call-y" :name "bash" :arguments "{}"}
                       :tool-result {:content "trimmed"
                                     :is-error false
                                     :details {:truncation {:truncated true :truncated-by :bytes}}}
                       :result-message {:role "toolResult"
                                        :tool-call-id "call-y"
                                        :tool-name "bash"
                                        :content [{:type :text :text "trimmed"}]
                                        :is-error false
                                        :details {:truncation {:truncated true :truncated-by :bytes}}
                                        :result-text "trimmed"}
                       :effective-policy {:max-lines 10 :max-bytes 20}}]
      (with-redefs [agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ msg] (reset! recorded msg) nil)]
        (let [result (#'executor/record-tool-call-result! session-ctx shaped q)
              stats  (ss/get-state-value-in session-ctx (ss/state-path :tool-output-stats (ss/active-session-id-in session-ctx)))]
          (is (= "call-y" (:tool-call-id result)))
          (is (= "call-y" (:tool-call-id @recorded)))
          (is (= 1 (count (:calls stats))))
          (is (= 1 (get-in stats [:aggregates :limit-hits-by-tool "bash"]))))))))

(deftest tool-output-accounting-test
  (testing "captures per-call stats and aggregates, including limit-hit"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-1" :name "bash" :arguments "{}"}]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "trimmed"
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc nil)
        (let [stats (ss/get-state-value-in session-ctx (ss/state-path :tool-output-stats (ss/active-session-id-in session-ctx)))
              call  (first (:calls stats))]
          (is (= "call-1" (:tool-call-id call)))
          (is (= "bash" (:tool-name call)))
          (is (= true (:limit-hit call)))
          (is (= :bytes (:truncated-by call)))
          (is (number? (:effective-max-lines call)))
          (is (number? (:effective-max-bytes call)))
          (is (= (:output-bytes call) (:context-bytes-added call)))
          (is (= (:context-bytes-added call)
                 (get-in stats [:aggregates :total-context-bytes])))
          (is (= 1 (get-in stats [:aggregates :limit-hits-by-tool "bash"])))))))

  (testing "context-bytes-added reflects shaped content"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-2" :name "read" :arguments "{}"}
          raw         (apply str (repeat 1000 "x"))
          shaped      (subs raw 0 20)]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content shaped
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc nil)
        (let [call (first (:calls (ss/get-state-value-in session-ctx (ss/state-path :tool-output-stats (ss/active-session-id-in session-ctx)))))]
          (is (= (count (.getBytes shaped "UTF-8"))
                 (:context-bytes-added call)))
          (is (= (:context-bytes-added call) (:output-bytes call)))))))

  (testing "structured content blocks are preserved and progress events include rich payload"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-3" :name "read" :arguments "{}"}
          q           (LinkedBlockingQueue.)
          blocks      [{:type :text :text "hello"}
                       {:type :image :mime-type "image/png" :data "<base64>"}]
          results     (atom nil)]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content blocks
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in!
                    (fn [_ msg]
                      (reset! results msg)
                      nil)]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc q)
        (let [events   (loop [acc []]
                         (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                           (recur (conj acc e))
                           acc))
              update-e (some #(when (= :tool-execution-update (:event-kind %)) %) events)
              result-e (some #(when (= :tool-result (:event-kind %)) %) events)]
          (is (= blocks (:content @results)))
          (is (= "hello" (:result-text @results)))
          (is (= [{:type :text :text "partial"}] (:content update-e)))
          (is (= "partial" (:result-text update-e)))
          (is (= blocks (:content result-e)))
          (is (= "hello" (:result-text result-e))))))))
