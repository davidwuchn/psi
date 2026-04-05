(ns extensions.lsp-service-spec-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.lsp :as sut]))

(deftest service-spec-declares-jsonrpc-protocol-test
  (testing "lsp managed service spec opts into stdio json-rpc runtime attachment"
    (is (= {:command ["clojure-lsp"]
            :cwd "/repo"
            :transport :stdio
            :protocol :json-rpc
            :env nil}
           (sut/service-spec "/repo" nil)))))
