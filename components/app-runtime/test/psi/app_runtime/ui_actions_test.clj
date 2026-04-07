(ns psi.app-runtime.ui-actions-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.app-runtime.ui-actions :as ui-actions]))

(deftest context-session-action-preserves-selector-order-and-action-values-test
  (let [action (ui-actions/context-session-action
                {:selector/prompt "Select a live session"
                 :selector/items [{:item/id [:session "s1"]
                                   :item/kind :session
                                   :item/default-label "Main"
                                   :item/action {:action/kind :switch-session
                                                 :action/session-id "s1"}}
                                  {:item/id [:fork-point "e1"]
                                   :item/kind :fork-point
                                   :item/default-label "⎇ Branch from here"
                                   :item/action {:action/kind :fork-session
                                                 :action/session-id "s1"
                                                 :action/entry-id "e1"}}]})]
    (is (= :select (:ui/action-kind action)))
    (is (= :select-session (:ui/action-name action)))
    (is (= :preserve (:ui/order action)))
    (is (= ["Main" "⎇ Branch from here"]
           (mapv :ui.item/label (:ui/items action))))
    (is (= {:action/kind :fork-session
            :action/session-id "s1"
            :action/entry-id "e1"}
           (:ui.item/value (second (:ui/items action)))))
    (is (= "context-session-selector"
           (get-in action [:ui/legacy :action-name])))))

(deftest resume-session-action-wraps-query-result-test
  (let [action (ui-actions/resume-session-action
                {:psi.session/list [{:psi.session-info/path "/tmp/a.ndedn"
                                     :psi.session-info/name "Alpha"}]})]
    (is (= :select-resume-session (:ui/action-name action)))
    (is (= "/tmp/a.ndedn" (get-in action [:ui/items 0 :ui.item/value])))
    (is (= "resume-selector" (get-in action [:ui/legacy :action-name])))))

(deftest model-and-thinking-actions-produce_canonical_submit_contracts_test
  (let [model-action (ui-actions/model-picker-action [{:provider "openai"
                                                       :id "gpt-5.3-codex"
                                                       :reasoning true}])
        thinking-action (ui-actions/thinking-picker-action)]
    (is (= :set-model (get-in model-action [:ui/on-submit :submit/kind])))
    (is (= :select-model (:ui/action-name model-action)))
    (is (= :set-thinking-level (get-in thinking-action [:ui/on-submit :submit/kind])))
    (is (= :select-thinking-level (:ui/action-name thinking-action)))
    (is (= ["off" "minimal" "low" "medium" "high" "xhigh"]
           (mapv :ui.item/value (:ui/items thinking-action))))))
