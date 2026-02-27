(ns extensions.subagent-widget-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.subagent-widget :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(def expected-tool-names
  #{"subagent_create" "subagent_continue" "subagent_remove" "subagent_list"})

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
             (-> @state :notifications last :text))))))

(deftest tool-validation-test
  (testing "subagent_create validates empty task"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent_create" :execute])]
        (is (= {:content "Error: task is required." :is-error true}
               (execute {}))))))

  (testing "subagent_list returns empty-state summary"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent_list" :execute])]
        (is (= {:content "No active subagents." :is-error false}
               (execute {})))))))
