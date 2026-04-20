(ns psi.agent-session.mutations-extension-installs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest reload-extension-installs-mutation-test
  (testing "reload-extension-installs returns effective install state attrs"
    (let [[ctx session-id] (create-session-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (with-redefs [session/reload-extension-installs-in!
                    (fn [_ctx _sid]
                      {:install-state
                       {:psi.extensions/last-apply {:status :restart-required
                                                    :restart-required? true
                                                    :summary "restart required"}
                        :psi.extensions/diagnostics [{:severity :info :category :restart-required :message "restart required"}]
                        :psi.extensions/effective {:entries-by-lib {'foo/ext {:status :restart-required}}}}})]
        (let [r (mutate 'psi.extension/reload-extension-installs {})]
          (is (= :restart-required (get-in r [:psi.extensions/last-apply :status])))
          (is (= 1 (count (:psi.extensions/diagnostics r))))
          (is (= :restart-required
                 (get-in r [:psi.extensions/effective :entries-by-lib 'foo/ext :status]))))))))
