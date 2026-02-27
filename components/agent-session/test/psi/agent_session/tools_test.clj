(ns psi.agent-session.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.tools :as tools]))

(deftest eql-query-tool-schema-test
  (testing "eql_query tool schema is well-formed"
    (is (= "eql_query" (:name tools/eql-query-tool)))
    (is (string? (:description tools/eql-query-tool)))
    (is (string? (:parameters tools/eql-query-tool)))))

(deftest eql-query-tool-in-all-schemas-test
  (testing "eql_query appears in all-tool-schemas"
    (is (some #(= "eql_query" (:name %)) tools/all-tool-schemas))))

(deftest eql-query-tool-not-in-all-tools-test
  (testing "eql_query is excluded from all-tools (requires context)"
    (is (not (some #(= "eql_query" (:name %)) tools/all-tools)))))

(deftest make-eql-query-tool-valid-query-test
  (testing "valid EQL query returns EDN result"
    (let [query-fn (fn [q]
                     (is (= [:foo/bar] q))
                     {:foo/bar 42})
          tool     (tools/make-eql-query-tool query-fn)
          result   ((:execute tool) {"query" "[:foo/bar]"})]
      (is (false? (:is-error result)))
      (is (= {:foo/bar 42} (read-string (:content result)))))))

(deftest make-eql-query-tool-nested-query-test
  (testing "nested EQL query with joins"
    (let [query-fn (fn [_q]
                     {:psi.agent-session/stats {:total-messages 5}})
          tool     (tools/make-eql-query-tool query-fn)
          result   ((:execute tool)
                    {"query" "[{:psi.agent-session/stats [:total-messages]}]"})]
      (is (false? (:is-error result)))
      (is (string? (:content result))))))

(deftest make-eql-query-tool-invalid-edn-test
  (testing "invalid EDN returns error"
    (let [tool   (tools/make-eql-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" "[not valid edn!!!"})]
      (is (true? (:is-error result)))
      (is (re-find #"EQL query error" (:content result))))))

(deftest make-eql-query-tool-not-a-vector-test
  (testing "non-vector EDN returns error"
    (let [tool   (tools/make-eql-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" ":just-a-keyword"})]
      (is (true? (:is-error result)))
      (is (re-find #"must be an EDN vector" (:content result))))))

(deftest make-eql-query-tool-query-fn-throws-test
  (testing "query-fn exception is caught and returned as error"
    (let [tool   (tools/make-eql-query-tool
                  (fn [_q] (throw (ex-info "resolver failed" {}))))
          result ((:execute tool) {"query" "[:foo]"})]
      (is (true? (:is-error result)))
      (is (re-find #"resolver failed" (:content result))))))

(deftest make-eql-query-tool-read-eval-disabled-test
  (testing "read-eval is disabled for safety"
    (let [tool   (tools/make-eql-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" "#=(+ 1 2)"})]
      (is (true? (:is-error result))))))

(deftest execute-tool-dispatch-test
  (testing "built-in dispatch does not handle eql_query"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
          (tools/execute-tool "eql_query" {"query" "[:foo]"})))))

(deftest eql-query-tool-integration-test
  (testing "eql_query tool works with a real session context"
    (let [ctx      (session/create-context {:persist? false})
          _        (session/new-session-in! ctx)
          tool     (tools/make-eql-query-tool (fn [q] (session/query-in ctx q)))
          exec     (:execute tool)
          ;; Query session phase
          result   (exec {"query" "[:psi.agent-session/phase]"})
          parsed   (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :idle (:psi.agent-session/phase parsed)))))

  (testing "eql_query tool returns session-id from live context"
    (let [ctx      (session/create-context {:persist? false})
          _        (session/new-session-in! ctx)
          tool     (tools/make-eql-query-tool (fn [q] (session/query-in ctx q)))
          exec     (:execute tool)
          result   (exec {"query" "[:psi.agent-session/session-id]"})
          parsed   (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (string? (:psi.agent-session/session-id parsed))))))
