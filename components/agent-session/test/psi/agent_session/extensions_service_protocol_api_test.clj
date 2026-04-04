(ns psi.agent-session.extensions-service-protocol-api-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.extensions :as ext]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest create-extension-api-registers-service-protocol-via-mutation-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/ext/test.clj"})
        reg (ext/create-registry)
        runtime-fns {:query-fn (:query api)
                     :mutate-fn (:mutate api)
                     :get-api-key-fn (:get-api-key api)
                     :ui-type-fn (fn [] :console)
                     :ui-context-fn (fn [_] (:ui api))
                     :log-fn (fn [_] nil)}
        ext-api (ext/create-extension-api reg "/ext/test.clj" runtime-fns)]
    ((:service-request ext-api)
     {:key [:lsp "/repo"]
      :request-id "r1"
      :payload {"jsonrpc" "2.0" "id" "r1"}
      :timeout-ms 123})
    ((:service-notify ext-api)
     {:key [:lsp "/repo"]
      :payload {"jsonrpc" "2.0" "method" "initialized"}})
    (is (= [{:ext-path "/ext/test.clj"
             :key [:lsp "/repo"]
             :request-id "r1"
             :payload {"jsonrpc" "2.0" "id" "r1"}
             :timeout-ms 123}]
           (:service-requests @state)))
    (is (= [{:ext-path "/ext/test.clj"
             :key [:lsp "/repo"]
             :payload {"jsonrpc" "2.0" "method" "initialized"}}]
           (:service-notifications @state)))))
