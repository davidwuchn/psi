(ns psi.query.core-test
  "Tests for the EQL query core: registration, execution, introspection"
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.query.core :as query]
   [psi.query.registry :as registry]))

;;; Sample resolvers

(pco/defresolver greeting-resolver [{:keys [user/name]}]
  {::pco/input  [:user/name]
   ::pco/output [:user/greeting]}
  {:user/greeting (str "Hello, " name "!")})

(pco/defresolver full-user-resolver [{:keys [user/id]}]
  {::pco/input  [:user/id]
   ::pco/output [:user/name :user/role]}
  {:user/name (str "user-" id)
   :user/role :agent})

(pco/defresolver role-label-resolver [{:keys [user/role]}]
  {::pco/input  [:user/role]
   ::pco/output [:user/role-label]}
  {:user/role-label (name role)})

;;; Helper macro — resets registry and env around each block

(defmacro with-clean-query [& body]
  `(do
     (registry/reset-registry!)
     (query/rebuild-env!)
     (try
       ~@body
       (finally
         (registry/reset-registry!)
         (query/rebuild-env!)))))

;;; Registration tests

(deftest register-resolver-test
  ;; Verifies that register-resolver! delegates to registry and is retrievable
  (testing "register-resolver!"
    (with-clean-query
      (testing "adds resolver to registry"
        (query/register-resolver! greeting-resolver)
        (is (contains? (query/resolver-syms)
                       (-> greeting-resolver pco/operation-config ::pco/op-name)))))))

;;; Query tests

(deftest basic-query-test
  ;; Exercises the EQL query path with a single-hop resolver
  (testing "query"
    (with-clean-query
      (query/register-resolver! greeting-resolver)
      (query/rebuild-env!)

      (testing "resolves attribute from seed input"
        (let [result (query/query {:user/name "ψ"} [:user/greeting])]
          (is (= "Hello, ψ!" (:user/greeting result)))))

      (testing "returns map with requested keys"
        (let [result (query/query {:user/name "world"} [:user/greeting])]
          (is (map? result))
          (is (contains? result :user/greeting)))))))

(deftest chained-query-test
  ;; Exercises resolver chaining: id → name+role → role-label
  (testing "chained resolvers"
    (with-clean-query
      (query/register-resolver! full-user-resolver)
      (query/register-resolver! role-label-resolver)
      (query/rebuild-env!)

      (testing "chains through multiple resolvers"
        (let [result (query/query {:user/id 1} [:user/name :user/role-label])]
          (is (= "user-1" (:user/name result)))
          (is (= "agent" (:user/role-label result))))))))

(deftest query-one-test
  ;; query-one returns a single attribute value
  (testing "query-one"
    (with-clean-query
      (query/register-resolver! greeting-resolver)
      (query/rebuild-env!)

      (testing "returns the attribute value directly"
        (let [result (query/query-one {:user/name "ψ"} :user/greeting)]
          (is (= "Hello, ψ!" result)))))))

;;; Introspection tests

(deftest graph-summary-test
  ;; graph-summary describes the current query graph state
  (testing "graph-summary"
    (with-clean-query
      (testing "reports zero counts on fresh registry"
        (let [summary (query/graph-summary)]
          (is (= 0 (:resolver-count summary)))
          (is (= 0 (:mutation-count summary)))))

      (testing "counts increase after registration"
        (query/register-resolver! greeting-resolver)
        (let [summary (query/graph-summary)]
          (is (= 1 (:resolver-count summary)))
          (is (set? (:resolvers summary)))))

      (testing "env-built? reflects rebuild state"
        (query/rebuild-env!)
        (is (:env-built? (query/graph-summary)))))))

;;; Macro tests

(deftest defresolver-macro-test
  ;; defresolver registers and the resolver is queryable immediately after rebuild
  (testing "defresolver macro"
    (with-clean-query
      (query/defresolver macro-test-resolver [{:keys [item/id]}]
        {::pco/input  [:item/id]
         ::pco/output [:item/label]}
        {:item/label (str "item-" id)})

      (query/rebuild-env!)

      (testing "resolver is registered"
        (is (contains? (query/resolver-syms)
                       'psi.query.core-test/macro-test-resolver)))

      (testing "resolver resolves attributes"
        (let [result (query/query {:item/id 99} [:item/label])]
          (is (= "item-99" (:item/label result))))))))
