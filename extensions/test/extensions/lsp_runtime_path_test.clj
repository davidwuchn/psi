(ns extensions.lsp-runtime-path-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.lsp :as sut]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(def ^:private jsonrpc-echo-command
  [(or (System/getenv "BB") "bb")
   (str (.getCanonicalPath (java.io.File. "components/agent-session/test/psi/agent_session/jsonrpc_echo_bb.clj")))])

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- mutate-fn [ctx session-id]
  (let [qctx (query/create-query-context)]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (fn [op params]
      (get (query/query-in qctx
                           {:psi/agent-session-ctx ctx}
                           [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
           op))))

(deftest lsp-extension-service-spec-roundtrips-through-real-runtime-test
  (testing "extension service spec can be ensured through mutation path and gets live runtime fns"
    (let [[ctx session-id] (create-session-context)
          mutate! (mutate-fn ctx session-id)
          root (test-support/temp-cwd)
          spec (sut/service-spec root {:command jsonrpc-echo-command})]
      (try
        (mutate! 'psi.extension/ensure-service
                 {:ext-path "/ext/lsp.clj"
                  :key (sut/workspace-key root)
                  :type :subprocess
                  :spec spec})
        (let [svc (services/service-in ctx (sut/workspace-key root))]
          (is (= :json-rpc (:protocol svc)))
          (is (fn? (:send-fn svc)))
          (is (fn? (:await-response-fn svc))))
        (finally
          (when-let [close-fn (:close-fn (services/service-in ctx (sut/workspace-key root)))]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))
