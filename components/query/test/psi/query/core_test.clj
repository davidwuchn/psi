(ns psi.query.core-test
  "Tests for the EQL query core: registration, execution, introspection.

  Uses query/create-query-context (Nullable pattern) so every test gets
  its own isolated registry + Pathom env — no global-state mutations."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.query.core :as query]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Sample resolvers
;; ─────────────────────────────────────────────────────────────────────────────

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

(pco/defresolver macro-test-resolver [{item-id :item/id}]
  {::pco/input  [:item/id]
   ::pco/output [:item/label]}
  {:item/label (str "item-" item-id)})

;; ─────────────────────────────────────────────────────────────────────────────
;; Registration tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest register-resolver-test
  (testing "register-resolver-in! adds resolver to isolated context"
    (let [ctx (query/create-query-context)]
      (query/register-resolver-in! ctx greeting-resolver)
      (is (contains? (query/resolver-syms-in ctx)
                     (-> greeting-resolver pco/operation-config ::pco/op-name))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Query tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest basic-query-test
  (testing "query-in resolves attribute from seed input"
    (let [ctx (query/create-query-context)]
      (query/register-resolver-in! ctx greeting-resolver)
      (query/rebuild-env-in! ctx)

      (testing "resolves single attribute"
        (let [result (query/query-in ctx {:user/name "ψ"} [:user/greeting])]
          (is (= "Hello, ψ!" (:user/greeting result)))))

      (testing "returns map with requested keys"
        (let [result (query/query-in ctx {:user/name "world"} [:user/greeting])]
          (is (map? result))
          (is (contains? result :user/greeting)))))))

(deftest chained-query-test
  (testing "chained resolvers: id → name+role → role-label"
    (let [ctx (query/create-query-context)]
      (query/register-resolver-in! ctx full-user-resolver)
      (query/register-resolver-in! ctx role-label-resolver)
      (query/rebuild-env-in! ctx)

      (let [result (query/query-in ctx {:user/id 1} [:user/name :user/role-label])]
        (is (= "user-1" (:user/name result)))
        (is (= "agent" (:user/role-label result)))))))

(deftest query-one-test
  (testing "query-one-in returns single attribute value directly"
    (let [ctx (query/create-query-context)]
      (query/register-resolver-in! ctx greeting-resolver)
      (query/rebuild-env-in! ctx)

      (let [result (query/query-one-in ctx {:user/name "ψ"} :user/greeting)]
        (is (= "Hello, ψ!" result))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Introspection tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest graph-summary-test
  (testing "graph-summary-in describes the current query graph state"
    (let [ctx (query/create-query-context)]

      (testing "fresh context reports zero counts"
        (let [summary (query/graph-summary-in ctx)]
          (is (= 0 (:resolver-count summary)))
          (is (= 0 (:mutation-count summary)))))

      (testing "counts increase after registration"
        (query/register-resolver-in! ctx greeting-resolver)
        (let [summary (query/graph-summary-in ctx)]
          (is (= 1 (:resolver-count summary)))
          (is (set? (:resolvers summary)))))

      (testing "env-built? reflects rebuild state"
        (query/rebuild-env-in! ctx)
        (is (:env-built? (query/graph-summary-in ctx)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Resolver registration and execution
;; ─────────────────────────────────────────────────────────────────────────────

(deftest resolver-registration-and-execution-test
  (testing "resolver defined at top level is registerable and queryable"
    (let [ctx (query/create-query-context)]
      (query/register-resolver-in! ctx macro-test-resolver)
      (query/rebuild-env-in! ctx)

      (testing "resolver is registered"
        (is (contains? (query/resolver-syms-in ctx)
                       'psi.query.core-test/macro-test-resolver)))

      (testing "resolver resolves attributes"
        (let [result (query/query-in ctx {:item/id 99} [:item/label])]
          (is (= "item-99" (:item/label result))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Isolation test — contexts are independent
;; ─────────────────────────────────────────────────────────────────────────────

(deftest context-isolation-test
  (testing "two query contexts are fully independent"
    (let [ctx-a (query/create-query-context)
          ctx-b (query/create-query-context)]
      (query/register-resolver-in! ctx-a greeting-resolver)
      (is (= 1 (:resolver-count (query/graph-summary-in ctx-a))))
      (is (= 0 (:resolver-count (query/graph-summary-in ctx-b)))
          "ctx-b should be unaffected by ctx-a registration"))))
