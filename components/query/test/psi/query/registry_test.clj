(ns psi.query.registry-test
  "Tests for the resolver/mutation registry"
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.query.registry :as registry]))

;;; Sample operations

(pco/defresolver sample-name-resolver [{:keys [user/id]}]
  {::pco/input  [:user/id]
   ::pco/output [:user/name]}
  {:user/name (str "user-" id)})

(pco/defresolver sample-age-resolver [{:keys [user/id]}]
  {::pco/input  [:user/id]
   ::pco/output [:user/age]}
  {:user/age 42})

;;; Helper — each test block resets and restores so tests are independent

(defmacro with-clean-registry [& body]
  `(do
     (registry/reset-registry!)
     (try
       ~@body
       (finally
         (registry/reset-registry!)))))

;;; Tests

(deftest register-resolver-test
  ;; Registers a resolver and verifies it appears in the registry
  (testing "register-resolver!"
    (testing "stores resolver and returns entry"
      (with-clean-registry
        (let [entry (registry/register-resolver! sample-name-resolver)]
          (is (qualified-symbol? (:resolver-sym entry)))
          (is (inst? (:registered-at entry)))
          (is (= sample-name-resolver (:resolver entry))))))

    (testing "increments resolver count"
      (with-clean-registry
        (is (= 0 (registry/resolver-count)))
        (registry/register-resolver! sample-name-resolver)
        (is (= 1 (registry/resolver-count)))
        (registry/register-resolver! sample-age-resolver)
        (is (= 2 (registry/resolver-count)))))

    (testing "all-resolvers returns registered objects"
      (with-clean-registry
        (registry/register-resolver! sample-name-resolver)
        (is (= [sample-name-resolver] (registry/all-resolvers)))))

    (testing "registered-resolver-syms contains sym"
      (with-clean-registry
        (registry/register-resolver! sample-name-resolver)
        (let [syms (registry/registered-resolver-syms)]
          (is (set? syms))
          (is (= 1 (count syms))))))))

(deftest register-mutation-test
  ;; Registers a mutation and verifies it appears in the registry
  (pco/defmutation sample-mutation [{:keys [user/name]}]
    {::pco/params [:user/name]}
    {:result/ok true})

  (testing "register-mutation!"
    (testing "stores mutation and returns entry"
      (with-clean-registry
        (let [entry (registry/register-mutation! sample-mutation)]
          (is (qualified-symbol? (:mutation-sym entry)))
          (is (inst? (:registered-at entry))))))

    (testing "increments mutation count"
      (with-clean-registry
        (is (= 0 (registry/mutation-count)))
        (registry/register-mutation! sample-mutation)
        (is (= 1 (registry/mutation-count)))))))

(deftest build-indexes-test
  ;; build-indexes compiles registered resolvers into a Pathom index
  (testing "build-indexes"
    (with-clean-registry
      (registry/register-resolver! sample-name-resolver)
      (let [idx (registry/build-indexes)]
        (is (map? idx))
        (is (contains? idx :com.wsscode.pathom3.connect.indexes/index-oir))))))

(deftest reset-registry-test
  ;; reset-registry! clears all state
  (testing "reset-registry!"
    (with-clean-registry
      (registry/register-resolver! sample-name-resolver)
      (is (= 1 (registry/resolver-count)))
      (registry/reset-registry!)
      (is (= 0 (registry/resolver-count)))
      (is (= 0 (registry/mutation-count))))))
