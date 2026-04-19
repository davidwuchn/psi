(ns psi.agent-session.runtime-refresh-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.runtime-refresh :as sut]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest refresh-runtime-preserves-state-identity-and-rebuilds-dispatch-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        state* (:state* ctx)
        before-log (dispatch/event-log-entries)
        _ (dispatch/clear-handlers!)
        _ (dispatch/register-handler! :test/stale (fn [_ _] :stale))
        result (sut/refresh-runtime! {:ctx ctx :session-id session-id})]
    (testing "refresh preserves ctx/state identity and event log"
      (is (identical? state* (:state* ctx)))
      (is (= before-log (dispatch/event-log-entries)))
      (is (false? (get-in result [:psi.runtime-refresh/details :recreated-ctx?])))
      (is (false? (get-in result [:psi.runtime-refresh/details :recreated-state*?]))))

    (testing "dispatch handlers are re-registered and stale test handler is removed"
      (is (contains? (dispatch/registered-event-types) :session/set-model))
      (is (not (contains? (dispatch/registered-event-types) :test/stale)))
      (is (= :ok (get-in result [:psi.runtime-refresh/steps 1 :status]))))

    (testing "current first-slice refresh completes without replacing preserved runtime state"
      (is (= :ok (get-in result [:psi.runtime-refresh/steps 0 :status])))
      (is (= :ok (get-in result [:psi.runtime-refresh/steps 2 :status])))
      (is (= :ok (get-in result [:psi.runtime-refresh/steps 3 :status]))))))

(deftest refresh-runtime-reinstalls-background-job-ui-hook-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        _ (reset! (:background-job-ui-refresh-fn ctx) nil)
        result (sut/refresh-runtime! {:ctx ctx :session-id session-id})]
    (is (fn? @(:background-job-ui-refresh-fn ctx)))
    (is (= :ok (get-in result [:psi.runtime-refresh/steps 3 :status])))
    (is (= [:background-job-ui-refresh-fn]
           (get-in result [:psi.runtime-refresh/steps 3 :details :reinstalled])))))

(deftest refresh-runtime-reinstalls-extension-run-fn-when-session-model-is-present-test
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :session-defaults {:model {:provider "anthropic"
                                                                             :id "claude-sonnet"
                                                                             :reasoning true}}})
        original-fn (fn [_ _] :old)
        _ (reset! (:extension-run-fn-atom ctx) original-fn)
        result (sut/refresh-runtime! {:ctx ctx :session-id session-id})]
    (is (fn? @(:extension-run-fn-atom ctx)))
    (is (not (identical? original-fn @(:extension-run-fn-atom ctx))))
    (is (= :ok (:psi.runtime-refresh/status result)))
    (is (= :ok (get-in result [:psi.runtime-refresh/steps 3 :status])))
    (is (= [:background-job-ui-refresh-fn :extension-run-fn]
           (get-in result [:psi.runtime-refresh/steps 3 :details :reinstalled])))
    (is (= [] (get-in result [:psi.runtime-refresh/limitations])))))

(deftest refresh-runtime-reports-extension-run-fn-limitation-when-session-id-missing-test
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :session-defaults {:model {:provider "anthropic"
                                                                             :id "claude-sonnet"
                                                                             :reasoning true}}})
        _ session-id
        _ (runtime/register-extension-run-fn-in! ctx session-id nil {:provider :anthropic :id "claude-sonnet" :supports-reasoning true})
        result (sut/refresh-runtime! {:ctx ctx})]
    (is (= :partial (:psi.runtime-refresh/status result)))
    (is (= :partial (get-in result [:psi.runtime-refresh/steps 3 :status])))
    (is (= [:extension-run-fn]
           (get-in result [:psi.runtime-refresh/steps 3 :details :pending])))
    (is (= :missing-session-id
           (get-in result [:psi.runtime-refresh/steps 3 :details :extension-run-fn])))
    (is (= :extension-run-fn
           (get-in result [:psi.runtime-refresh/limitations 0 :boundary])))))

(deftest refresh-runtime-reports-extension-run-fn-limitation-when-session-model-missing-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        _ (runtime/register-extension-run-fn-in! ctx session-id nil {:provider :anthropic :id "stub"})
        result (sut/refresh-runtime! {:ctx ctx :session-id session-id})]
    (is (= :partial (:psi.runtime-refresh/status result)))
    (is (= :partial (get-in result [:psi.runtime-refresh/steps 3 :status])))
    (is (= [:extension-run-fn]
           (get-in result [:psi.runtime-refresh/steps 3 :details :pending])))
    (is (= :missing-model
           (get-in result [:psi.runtime-refresh/steps 3 :details :extension-run-fn])))
    (is (= :extension-run-fn
           (get-in result [:psi.runtime-refresh/limitations 0 :boundary])))
    (is (= "Registered extension run fn could not be reinstalled because the target session has no usable model selection."
           (get-in result [:psi.runtime-refresh/limitations 0 :reason])))))

(deftest refresh-runtime-refreshes-extensions-via-canonical-path-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        calls (atom [])]
    (with-redefs [psi.agent-session.extension-runtime/reload-extensions-in!
                  (fn [ctx* session-id* configured-paths cwd]
                    (swap! calls conj {:ctx ctx* :session-id session-id* :configured-paths configured-paths :cwd cwd})
                    {:loaded ["/ext/a"] :errors []})]
      (let [result (sut/refresh-runtime! {:ctx ctx
                                          :session-id session-id
                                          :worktree-path "/tmp/runtime-refresh-test"
                                          :extension-refresh? true})]
        (is (= [{:ctx ctx :session-id session-id :configured-paths [] :cwd "/tmp/runtime-refresh-test"}]
               @calls))
        (is (= :ok (get-in result [:psi.runtime-refresh/steps 2 :status])))
        (is (= ["/ext/a"]
               (get-in result [:psi.runtime-refresh/steps 2 :details :loaded])))))))
