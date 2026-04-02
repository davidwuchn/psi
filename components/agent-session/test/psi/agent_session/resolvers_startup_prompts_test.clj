(ns psi.agent-session.resolvers-startup-prompts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest startup-prompt-attrs-queryable-test
  (let [[ctx _] (test-support/make-session-ctx
                 {:session-data {:startup-prompts [{:id "engage-nucleus"
                                                    :source :project
                                                    :phase :system-bootstrap
                                                    :priority 100}]
                                 :startup-bootstrap-completed? true
                                 :startup-bootstrap-started-at (java.time.Instant/now)
                                 :startup-bootstrap-completed-at (java.time.Instant/now)
                                 :startup-message-ids ["m1" "m2"]}})
        r   (session/query-in ctx
                              [:psi.agent-session/startup-prompts
                               :psi.agent-session/startup-bootstrap-completed?
                               :psi.agent-session/startup-bootstrap-started-at
                               :psi.agent-session/startup-bootstrap-completed-at
                               :psi.agent-session/startup-message-ids])]
    (is (vector? (:psi.agent-session/startup-prompts r)))
    (is (true? (:psi.agent-session/startup-bootstrap-completed? r)))
    (is (instance? java.time.Instant (:psi.agent-session/startup-bootstrap-started-at r)))
    (is (instance? java.time.Instant (:psi.agent-session/startup-bootstrap-completed-at r)))
    (is (= ["m1" "m2"] (:psi.agent-session/startup-message-ids r)))))

(deftest startup-prompt-attrs-discoverable-in-graph-bridge-test
  (testing "startup attrs appear in graph edge metadata and resolver symbols"
    (let [[ctx _] (create-session-context {:persist? false})
          r   (session/query-in ctx [:psi.graph/resolver-syms :psi.graph/edges :psi.graph/domain-coverage])
          attrs (set (map :attribute (:psi.graph/edges r)))
          syms  (:psi.graph/resolver-syms r)
          domains (set (map :domain (:psi.graph/domain-coverage r)))]
      (is (contains? attrs :psi.agent-session/startup-prompts))
      (is (contains? attrs :psi.agent-session/startup-bootstrap-completed?))
      (is (contains? attrs :psi.agent-session/startup-bootstrap-started-at))
      (is (contains? attrs :psi.agent-session/startup-bootstrap-completed-at))
      (is (contains? attrs :psi.agent-session/startup-message-ids))
      (is (set? syms))
      (is (contains? domains :agent-session)))))
