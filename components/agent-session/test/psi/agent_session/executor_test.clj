(ns psi.agent-session.executor-test
  "Integration tests for the executor with per-turn statechart.

  Uses with-redefs on private vars to inject stub provider/tool behavior
  (no HTTP calls, no API keys required)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.executor :as executor]
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
  {:agent-ctx agent-ctx
   :session-data-atom (atom {:tool-output-overrides {}
                             :thinking-level :off})
   :tool-output-stats-atom (atom {:calls []
                                  :aggregates {:total-context-bytes 0
                                               :by-tool {}
                                               :limit-hits-by-tool {}}})})

(def ^:private stub-model
  {:provider "stub" :id "stub-model"})

(deftest text-only-response-test
  (let [agent-ctx    (setup-agent-ctx!)
        session-ctx  (setup-session-ctx! agent-ctx)
        turn-atom    (atom nil)
        user-msg     {:role "user" :content [{:type :text :text "hello"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "Hello! I'm here to help.")]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg]
                    {:turn-ctx-atom turn-atom})]
        (is (= "assistant" (:role result)))
        (is (= :stop (:stop-reason result)))
        (is (= "Hello! I'm here to help."
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))
        (is (some? @turn-atom))
        (is (= :done (turn-sc/turn-phase @turn-atom)))))))

(deftest error-response-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hello"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-error-stream "Connection refused")]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg]
                    {:turn-ctx-atom turn-atom})]
        (is (= :error (:stop-reason result)))
        (is (= :error (turn-sc/turn-phase @turn-atom)))
        (is (= "Connection refused"
               (:error-message (turn-sc/get-turn-data @turn-atom))))))))

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
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "hello world")]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                {:turn-ctx-atom turn-atom})
      (let [td (turn-sc/get-turn-data @turn-atom)]
        (is (= "hello world" (:text-buffer td)))
        (is (some? (:final-message td)))))))

(deftest multiple-text-deltas-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "Hello"})
                      (consume-fn {:type :text-delta :delta " there"})
                      (consume-fn {:type :text-delta :delta "!"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg]
                    {:turn-ctx-atom turn-atom})]
        (is (= "Hello there!"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest thinking-delta-emits-progress-event-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        q           (LinkedBlockingQueue.)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                {:turn-ctx-atom turn-atom
                                 :progress-queue q})
      (let [events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            thinking (some #(when (= :thinking-delta (:event-kind %)) %) events)
            td       (turn-sc/get-turn-data @turn-atom)]
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
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (future
                        (consume-fn {:type :start})
                        (Thread/sleep 260)
                        (consume-fn {:type :done :reason :stop})))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn
                  psi.agent-session.executor/llm-stream-idle-timeout-ms 120
                  psi.agent-session.executor/llm-stream-wait-poll-ms 20]
      (let [result (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                             {:turn-ctx-atom turn-atom})
            td     (turn-sc/get-turn-data @turn-atom)]
        (is (= :error (:stop-reason result)))
        (is (= "Timeout waiting for LLM response" (:error-message result)))
        (is (= :error (get-in td [:last-provider-event :type])))
        (is (= "Timeout waiting for LLM response"
               (get-in td [:last-provider-event :error-message])))))))

(deftest thinking-level-is-forwarded-to-ai-options-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (assoc (setup-session-ctx! agent-ctx)
                           :session-data-atom (atom {:tool-output-overrides {}
                                                     :thinking-level :high}))
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
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-start :content-index 0})
                      (consume-fn {:type :text-delta :content-index 0 :delta "Hello"})
                      (consume-fn {:type :text-end :content-index 0})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (let [result (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                             {:turn-ctx-atom turn-atom})
            td     (turn-sc/get-turn-data @turn-atom)]
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
                "sys" messages [])
          roles (mapv :role (:messages conv))]
      (is (= [:user :assistant :user] roles)
          "custom-type assistant message is excluded; no consecutive assistant messages")
      (is (not-any? :custom-type (:messages conv))
          "no custom-type keys in LLM conversation messages")))

  (testing "non-custom-type messages are all included"
    (let [messages
          [{:role "user"      :content [{:type :text :text "q"}]}
           {:role "assistant" :content [{:type :text :text "a"}]}
           {:role "user"      :content [{:type :text :text "q2"}]}]
          conv (#'psi.agent-session.executor/agent-messages->ai-conversation
                "sys" messages [])
          roles (mapv :role (:messages conv))]
      (is (= [:user :assistant :user] roles)))))

(deftest tool-output-accounting-test
  (testing "captures per-call stats and aggregates, including limit-hit"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-1" :name "bash" :arguments "{}"}]
      (with-redefs [psi.agent-session.executor/execute-tool-with-registry
                    (fn [_ _ _ _]
                      {:content "trimmed"
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc nil)
        (let [stats @(:tool-output-stats-atom session-ctx)
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
      (with-redefs [psi.agent-session.executor/execute-tool-with-registry
                    (fn [_ _ _ _]
                      {:content shaped
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc nil)
        (let [call (first (:calls @(:tool-output-stats-atom session-ctx)))]
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
      (with-redefs [psi.agent-session.executor/execute-tool-with-registry
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content blocks
                       :is-error false
                       :details {:truncation {:truncated false}}})
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
