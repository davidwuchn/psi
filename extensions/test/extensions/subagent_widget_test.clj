(ns extensions.subagent-widget-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.subagent-widget :as sut]
   [psi.agent-session.executor :as executor]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(def expected-tool-names
  #{"subagent"})

(def expected-command-names
  #{"sub" "subcont" "subrm" "subclear" "sublist"})

(deftest init-registers-surface-test
  (testing "subagent widget registers workflow type, tools, commands, and lifecycle handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (is (contains? (:workflow-types @state) :subagent))
      (is (= expected-tool-names
             (set (keys (:tools @state)))))
      (is (= expected-command-names
             (set (keys (:commands @state)))))
      (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
      (is (= "subagent-widget loaded (workflow runtime)"
             (-> @state :notifications last :text)))
      (is (= 1 (count (:prompt-contributions @state))))
      (is (contains? (:prompt-contributions @state)
                     ["/test/subagent_widget.clj" "subagent-widget-capabilities"])))))

(deftest tool-validation-test
  (testing "subagent validates missing action"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: action must be one of create, continue, remove, list."
                :is-error true}
               (execute {}))))))

  (testing "subagent create validates empty task"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: task is required." :is-error true}
               (execute {"action" "create"}))))))

  (testing "subagent list returns empty-state summary"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "No active subagents." :is-error false}
               (execute {"action" "list"})))))))

(deftest run-subagent-job-executor-arg-order-test
  (testing "run-subagent-job passes executor args in run-agent-loop order"
    (let [captured          (atom nil)
          fake-agent-ctx    {:fake :agent-ctx}
          fake-session-ctx  {:agent-ctx fake-agent-ctx
                             :session-data-atom (atom {:tool-output-overrides {}})
                             :tool-output-stats-atom (atom {:calls []
                                                            :aggregates {:total-context-bytes 0
                                                                         :by-tool {}
                                                                         :limit-hits-by-tool {}}})}
          fake-model        {:provider "anthropic" :id "sonnet"}]
      (with-redefs [sut/resolve-active-model (fn [_] fake-model)
                    executor/run-agent-loop! (fn [& args]
                                               (reset! captured args)
                                               {:role "assistant"
                                                :stop-reason :stop
                                                :content [{:type :text :text "ok"}]})]
        (let [result (#'sut/run-subagent-job
                      {:agent-ctx      fake-agent-ctx
                       :session-ctx    fake-session-ctx
                       :prompt         "do thing"
                       :query-fn       (fn [_] nil)
                       :get-api-key-fn (fn [_] "test-api-key")})]
          (is (:ok? result))
          (is (= 6 (count @captured)))
          (is (nil? (nth @captured 0)))
          (is (= fake-session-ctx (nth @captured 1)))
          (is (= fake-agent-ctx (nth @captured 2)))
          (is (= fake-model (nth @captured 3)))
          (is (= "user" (get-in (nth @captured 4) [0 :role])))
          (is (= "do thing" (get-in (nth @captured 4) [0 :content 0 :text])))
          (is (= {:api-key "test-api-key"} (nth @captured 5))))))))

(deftest run-subagent-job-falls-back-to-created-session-ctx-test
  (testing "run-subagent-job creates a session ctx when missing"
    (let [captured          (atom nil)
          created           (atom nil)
          fake-agent-ctx    {:fake :agent-ctx}
          fake-session-ctx  {:agent-ctx fake-agent-ctx
                             :session-data-atom (atom {:tool-output-overrides {}})
                             :tool-output-stats-atom (atom {:calls []
                                                            :aggregates {:total-context-bytes 0
                                                                         :by-tool {}
                                                                         :limit-hits-by-tool {}}})}
          fake-model        {:provider "anthropic" :id "sonnet"}
          qf                (fn [_] nil)]
      (with-redefs [sut/resolve-active-model (fn [_] fake-model)
                    sut/create-sub-session-ctx (fn [agent-ctx query-fn]
                                                 (reset! created [agent-ctx query-fn])
                                                 fake-session-ctx)
                    executor/run-agent-loop! (fn [& args]
                                               (reset! captured args)
                                               {:role "assistant"
                                                :stop-reason :stop
                                                :content [{:type :text :text "ok"}]})]
        (let [result (#'sut/run-subagent-job
                      {:agent-ctx      fake-agent-ctx
                       :prompt         "fallback"
                       :query-fn       qf
                       :get-api-key-fn (fn [_] nil)})]
          (is (:ok? result))
          (is (= [fake-agent-ctx qf] @created))
          (is (= fake-session-ctx (nth @captured 1))))))))
