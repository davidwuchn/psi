(ns psi.app-runtime.context-summary-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime.context-summary :as context-summary]))

(deftest session-tree-line-label-prefers-display-name-and-includes-time-and-worktree-test
  (let [label (context-summary/session-tree-line-label
               {:id "abc123456789"
                :display-name "main"
                :created-at "2026-03-16T09:00:00Z"
                :updated-at "2026-03-16T09:15:00Z"
                :worktree-path "/repo/main"})]
    (is (re-find #"main \[abc12345\] — [0-9]{2}:[0-9]{2} / [0-9]{2}:[0-9]{2} — /repo/main" label))))

(deftest session-tree-widget-lines-build-actions-and_streaming_badges_test
  (let [lines (context-summary/session-tree-widget-lines
               [{:id "s1" :name "main" :is-active true}
                {:id "s2" :name "child" :parent-session-id "s1" :is-streaming true}
                {:id "e1" :item-kind "fork-point" :display-name "Branch from here" :entry-id "e1" :parent-session-id "s1"}]
               "s1")]
    (is (= {:text "main [s1] ← active"}
           (first lines)))
    (is (= "/tree s2"
           (get-in (second lines) [:action :command])))
    (is (re-find #"\[streaming\]" (:text (second lines))))
    (is (= "/fork e1"
           (get-in (nth lines 2) [:action :command])))))

(deftest context-widget-visible-only-for_multiple_sessions_test
  (let [one (context-summary/context-widget {:active-session-id "s1"
                                            :sessions [{:id "s1" :name "main"}]})
        many (context-summary/context-widget {:active-session-id "s1"
                                             :sessions [{:id "s1" :name "main"}
                                                        {:id "s2" :name "child"}]})]
    (is (false? (:widget/visible? one)))
    (is (true? (:widget/visible? many)))))
