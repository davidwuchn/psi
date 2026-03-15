(ns psi.agent-session.runtime-startup-prompts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.startup-prompts]))

(defn- fake-runner
  [_ai-ctx _ctx _agent-ctx _ai-model _user-messages _opts]
  {:role "assistant"
   :content [{:type :text :text "ok"}]})

(deftest run-startup-prompts-in-persists-telemetry-test
  (let [ctx (session/create-context {:persist? false})
        calls (atom [])]
    (session/new-session-in! ctx)
    (with-redefs [psi.agent-session.startup-prompts/discover-rules
                  (fn [_]
                    [{:id "s1" :phase :system-bootstrap :priority 1 :source :project :text "one"}
                     {:id "s2" :phase :project-bootstrap :priority 2 :source :global :text "two"}])
                  runtime/run-agent-loop-in!
                  (fn [_ctx _ai-ctx _ai-model user-messages _opts]
                    (swap! calls conj user-messages)
                    {:role "assistant" :content [{:type :text :text "ok"}]})]
      (let [result (runtime/run-startup-prompts-in! ctx {:ai-ctx nil :ai-model {:provider :anthropic :id "m"}
                                                         :run-loop-fn fake-runner})
            sd (session/get-session-data-in ctx)]
        (testing "runs all discovered startup prompts"
          (is (= 2 (count (:rules result))))
          (is (= 2 (count @calls))))

        (testing "persists startup telemetry fields"
          (is (= [{:id "s1" :source :project :phase :system-bootstrap :priority 1}
                  {:id "s2" :source :global :phase :project-bootstrap :priority 2}]
                 (:startup-prompts sd)))
          (is (true? (:startup-bootstrap-completed? sd)))
          (is (instance? java.time.Instant (:startup-bootstrap-started-at sd)))
          (is (instance? java.time.Instant (:startup-bootstrap-completed-at sd))))

        (testing "successful run has no startup errors"
          (is (= [] (:errors result))))

        (testing "startup prompts are recorded as visible user entries in journal"
          (let [user-texts (->> @(:journal-atom ctx)
                                (filter #(= :message (:kind %)))
                                (map #(get-in % [:data :message]))
                                (filter #(= "user" (:role %)))
                                (map #(get-in % [:content 0 :text]))
                                vec)]
            (is (= ["one" "two"] user-texts))))))))

(deftest run-startup-prompts-in-continues-after-prompt-failure-by-default-test
  (let [ctx (session/create-context {:persist? false})
        calls (atom [])]
    (session/new-session-in! ctx)
    (with-redefs [psi.agent-session.startup-prompts/discover-rules
                  (fn [_]
                    [{:id "s1" :phase :system-bootstrap :priority 1 :source :project :text "one"}
                     {:id "s2" :phase :project-bootstrap :priority 2 :source :global :text "two"}])
                  runtime/run-agent-loop-in!
                  (fn [_ctx _ai-ctx _ai-model user-messages _opts]
                    (let [txt (get-in (first user-messages) [:content 0 :text])]
                      (swap! calls conj txt)
                      (if (= "one" txt)
                        (throw (ex-info "boom" {:rule "s1"}))
                        {:role "assistant" :content [{:type :text :text "ok"}]})))]
      (let [result (runtime/run-startup-prompts-in! ctx {:ai-ctx nil :ai-model {:provider :anthropic :id "m"}
                                                         :run-loop-fn fake-runner})
            sd (session/get-session-data-in ctx)
            by-id (into {} (map (juxt :id identity)) (:applied result))]
        (is (= ["one" "two"] @calls))
        (is (= 1 (count (:errors result))))
        (is (= :error (:status (get by-id "s1"))))
        (is (= :ok (:status (get by-id "s2"))))
        (is (true? (:startup-bootstrap-completed? sd)))))))

(deftest run-startup-prompts-in-fail-fast-stops-after-first-failure-test
  (let [ctx (session/create-context {:persist? false})
        calls (atom [])]
    (session/new-session-in! ctx)
    (with-redefs [psi.agent-session.startup-prompts/discover-rules
                  (fn [_]
                    [{:id "s1" :phase :system-bootstrap :priority 1 :source :project :text "one"}
                     {:id "s2" :phase :project-bootstrap :priority 2 :source :global :text "two"}])
                  runtime/run-agent-loop-in!
                  (fn [_ctx _ai-ctx _ai-model user-messages _opts]
                    (let [txt (get-in (first user-messages) [:content 0 :text])]
                      (swap! calls conj txt)
                      (if (= "one" txt)
                        (throw (ex-info "boom" {:rule "s1"}))
                        {:role "assistant" :content [{:type :text :text "ok"}]})))]
      (let [result (runtime/run-startup-prompts-in! ctx {:ai-ctx nil
                                                         :ai-model {:provider :anthropic :id "m"}
                                                         :run-loop-fn fake-runner
                                                         :fail-fast? true})
            by-id (into {} (map (juxt :id identity)) (:applied result))]
        (is (= ["one"] @calls))
        (is (= 1 (count (:errors result))))
        (is (= :error (:status (get by-id "s1"))))
        (is (nil? (get by-id "s2")))))))

(deftest run-startup-prompts-in-skips-fork-by-default-test
  (let [ctx (session/create-context {:persist? false})
        calls (atom [])]
    (session/new-session-in! ctx)
    (with-redefs [psi.agent-session.startup-prompts/discover-rules
                  (fn [_]
                    [{:id "s1" :phase :system-bootstrap :priority 1 :source :project :text "one"}])
                  runtime/run-agent-loop-in!
                  (fn [_ctx _ai-ctx _ai-model user-messages _opts]
                    (swap! calls conj user-messages)
                    {:role "assistant" :content [{:type :text :text "ok"}]})]
      (let [result (runtime/run-startup-prompts-in! ctx {:ai-ctx nil
                                                         :ai-model {:provider :anthropic :id "m"}
                                                         :spawn-mode :fork-head})
            sd (session/get-session-data-in ctx)]
        (is (= [] (:rules result)))
        (is (= [] @calls))
        (is (= [] (:startup-prompts sd)))
        (is (true? (:startup-bootstrap-completed? sd)))))))
