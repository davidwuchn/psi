(ns extensions.auto-session-name-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.auto-session-name :as sut]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- create-runtime-session []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations})
        sd  (session/new-session-in! ctx nil {:session-name "initial"})]
    [ctx (:session-id sd)]))

(defn- create-ext-api [ctx session-id ext-path]
  (let [reg         (:extension-registry ctx)
        runtime-fs  (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
        base-mutate (:mutate-fn runtime-fs)
        runtime-fs' (assoc runtime-fs
                           :mutate-fn
                           (fn [op params]
                             (case op
                               psi.extension/create-child-session
                               {:psi.agent-session/session-id "child-1"}

                               psi.extension/run-agent-loop-in-session
                               {:psi.agent-session/agent-run-ok? true
                                :psi.agent-session/agent-run-text "Fix footer rendering"}

                               (base-mutate op params))))]
    (ext/register-extension-in! reg ext-path)
    (ext/create-extension-api reg ext-path runtime-fs')))

(deftest checkpoint-runtime-path-renames-source-session-test
  (testing "real runtime api path renames source session from checkpoint handler"
    (let [[ctx session-id] (create-runtime-session)
          ext-path         "/test/auto_session_name.clj"
          api              (create-ext-api ctx session-id ext-path)]
      (reset! @#'sut/state {:turn-counts {}
                            :helper-session-ids #{}
                            :turn-interval 2
                            :delay-ms 250
                            :log-fn nil
                            :ui nil})
      (ss/journal-append-in! ctx session-id
                             {:id "m1"
                              :kind :message
                              :timestamp (java.time.Instant/now)
                              :data {:message {:role "user"
                                               :content [{:type :text :text "Fix footer rendering"}]}}})
      (ss/journal-append-in! ctx session-id
                             {:id "m2"
                              :kind :message
                              :timestamp (java.time.Instant/now)
                              :data {:message {:role "assistant"
                                               :content [{:type :text :text "I will inspect the selector path."}]}}})
      (sut/init api)
      (let [handler (first (get-in @(:state (:extension-registry ctx)) [:extensions ext-path :handlers "auto_session_name/rename_checkpoint"]))]
        ;; registry stores {:extension-path ... :handler f}; extract the fn
        ((:handler handler) {:session-id session-id :turn-count 2}))
      (is (= "Fix footer rendering"
             (:session-name (ss/get-session-data-in ctx session-id)))))))
