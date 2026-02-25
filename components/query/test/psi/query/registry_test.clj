(ns psi.query.registry-test
  "Tests for the resolver/mutation registry.

  Uses registry/create-registry (Nullable pattern) so every test gets its
  own isolated registry — no global-state resets needed."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.query.registry :as registry]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Sample operations
;; ─────────────────────────────────────────────────────────────────────────────

(pco/defresolver sample-name-resolver [{user-id :user/id}]
  {::pco/input  [:user/id]
   ::pco/output [:user/name]}
  {:user/name (str "user-" user-id)})

(pco/defresolver sample-age-resolver [_]
  {::pco/input  [:user/id]
   ::pco/output [:user/age]}
  {:user/age 42})

(pco/defmutation sample-mutation [_]
  {::pco/params [:user/name]}
  {:result/ok true})

;; ─────────────────────────────────────────────────────────────────────────────
;; Register resolver tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest register-resolver-test
  (testing "register-resolver-in!"
    (testing "stores resolver and returns entry"
      (let [reg   (registry/create-registry)
            entry (registry/register-resolver-in! reg sample-name-resolver)]
        (is (qualified-symbol? (:resolver-sym entry)))
        (is (inst? (:registered-at entry)))
        (is (= sample-name-resolver (:resolver entry)))))

    (testing "increments resolver count"
      (let [reg (registry/create-registry)]
        (is (= 0 (registry/resolver-count-in reg)))
        (registry/register-resolver-in! reg sample-name-resolver)
        (is (= 1 (registry/resolver-count-in reg)))
        (registry/register-resolver-in! reg sample-age-resolver)
        (is (= 2 (registry/resolver-count-in reg)))))

    (testing "all-resolvers-in returns registered objects"
      (let [reg (registry/create-registry)]
        (registry/register-resolver-in! reg sample-name-resolver)
        (is (= [sample-name-resolver] (registry/all-resolvers-in reg)))))

    (testing "registered-resolver-syms-in contains sym"
      (let [reg (registry/create-registry)]
        (registry/register-resolver-in! reg sample-name-resolver)
        (let [syms (registry/registered-resolver-syms-in reg)]
          (is (set? syms))
          (is (= 1 (count syms))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Register mutation tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest register-mutation-test
  (testing "register-mutation-in!"
    (testing "stores mutation and returns entry"
      (let [reg   (registry/create-registry)
            entry (registry/register-mutation-in! reg sample-mutation)]
        (is (qualified-symbol? (:mutation-sym entry)))
        (is (inst? (:registered-at entry)))))

    (testing "increments mutation count"
      (let [reg (registry/create-registry)]
        (is (= 0 (registry/mutation-count-in reg)))
        (registry/register-mutation-in! reg sample-mutation)
        (is (= 1 (registry/mutation-count-in reg)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; build-indexes-in tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-indexes-test
  (testing "build-indexes-in compiles registered resolvers into a Pathom index"
    (let [reg (registry/create-registry)]
      (registry/register-resolver-in! reg sample-name-resolver)
      (let [idx (registry/build-indexes-in reg)]
        (is (map? idx))
        (is (contains? idx :com.wsscode.pathom3.connect.indexes/index-oir))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Isolation test — registries are independent
;; ─────────────────────────────────────────────────────────────────────────────

(deftest registry-isolation-test
  (testing "two registries are independent"
    (let [reg-a (registry/create-registry)
          reg-b (registry/create-registry)]
      (registry/register-resolver-in! reg-a sample-name-resolver)
      (is (= 1 (registry/resolver-count-in reg-a)))
      (is (= 0 (registry/resolver-count-in reg-b))
          "reg-b should be unaffected by reg-a registration"))))
