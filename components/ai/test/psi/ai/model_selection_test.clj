(ns psi.ai.model-selection-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.ai.model-registry :as registry]
   [psi.ai.model-selection :as sut]))

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (registry/init! {})))))

(defn- write-temp-models! [config]
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (spit tmp (pr-str config))
    (.getAbsolutePath tmp)))

(def local-provider-config
  {:version   1
   :providers {"local"
               {:base-url "http://localhost:8080/v1"
                :api      :openai-completions
                :auth     {:api-key "test-key" :auth-header? false}
                :models   [{:id   "test-model"
                            :name "Test Model"
                            :context-window 32768}]}}})

(def no-auth-provider-config
  {:version   1
   :providers {"public"
               {:base-url "https://example.com/v1"
                :api      :openai-completions
                :models   [{:id "public-model"}]}}})

(deftest catalog-view-built-ins-test
  (registry/init! {})
  (let [catalog (:candidates (sut/catalog-view))
        candidate (sut/find-candidate (sut/catalog-view) :anthropic "claude-sonnet-4-6")]
    (testing "catalog view exposes deterministic candidates"
      (is (seq catalog))
      (is (= catalog (sort-by (juxt :provider :id) catalog))))

    (testing "built-in candidate exposes facts, estimates, and configured reference"
      (is (some? candidate))
      (is (= :anthropic (:provider candidate)))
      (is (= "claude-sonnet-4-6" (:id candidate)))
      (is (= :anthropic-messages (:api candidate)))
      (is (= true (get-in candidate [:facts :supports-text])))
      (is (= true (get-in candidate [:facts :supports-reasoning])))
      (is (pos-int? (get-in candidate [:facts :context-window])))
      (is (number? (get-in candidate [:estimates :input-cost])))
      (is (true? (get-in candidate [:reference :configured?]))))))

(deftest catalog-view-custom-models-test
  (let [path (write-temp-models! local-provider-config)]
    (try
      (registry/init! {:user-models-path path})
      (let [catalog (sut/catalog-view)
            candidate (sut/find-candidate catalog :local "test-model")]
        (testing "custom candidate is present"
          (is (some? candidate))
          (is (= "Test Model" (:name candidate)))
          (is (= :openai-completions (:api candidate)))
          (is (= 32768 (get-in candidate [:facts :context-window]))))

        (testing "custom provider auth contributes to reference viability"
          (is (true? (get-in candidate [:reference :configured?])))))
      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest catalog-view-unconfigured-provider-test
  (let [path (write-temp-models! no-auth-provider-config)]
    (try
      (registry/init! {:user-models-path path})
      (let [catalog (sut/catalog-view)
            candidate (sut/find-candidate catalog :public "public-model")]
        (testing "provider without auth remains visible"
          (is (some? candidate))
          (is (= "public-model" (:id candidate))))

        (testing "missing auth marks reference as not configured"
          (is (false? (get-in candidate [:reference :configured?])))))
      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest role-defaults-test
  (testing "known roles expose default bundles"
    (is (= :auto-session-name (:role (sut/role-defaults :auto-session-name))))
    (is (= [{:criterion :supports-text :match :true}]
           (:required (sut/role-defaults :auto-session-name))))
    (is (= [{:criterion :same-provider-as-session :prefer :context-match}]
           (:weak-preferences (sut/role-defaults :auto-session-name)))))

  (testing "unknown roles return nil"
    (is (nil? (sut/role-defaults :does-not-exist)))))

(deftest normalize-request-test
  (testing "defaults to resolve/interactive with normalized collections"
    (let [request (sut/normalize-request {})]
      (is (= :resolve (:mode request)))
      (is (= :interactive (:role request)))
      (is (= [] (:required request)))
      (is (= [] (:strong-preferences request)))
      (is (= [] (:weak-preferences request)))
      (is (= {} (:context request)))))

  (testing "role defaults are merged underneath caller request"
    (let [request (sut/normalize-request {:role :auto-session-name})]
      (is (= :auto-session-name (:role request)))
      (is (= [{:criterion :supports-text :match :true}]
             (:required request)))
      (is (= [{:criterion :input-cost :prefer :lower}
              {:criterion :output-cost :prefer :lower}]
             (:strong-preferences request)))
      (is (= [{:criterion :same-provider-as-session :prefer :context-match}]
             (:weak-preferences request)))))

  (testing "caller-supplied criteria replace role defaults for the same fields in v1"
    (let [request (sut/normalize-request {:role :auto-session-name
                                          :required [{:criterion :supports-reasoning
                                                      :match :true}]
                                          :weak-preferences [{:criterion :same-model-as-session
                                                              :prefer :context-match}]
                                          :context {:session-provider :anthropic}})]
      (is (= [{:criterion :supports-reasoning :match :true}]
             (:required request)))
      (is (= [{:criterion :same-model-as-session :prefer :context-match}]
             (:weak-preferences request)))
      (is (= {:session-provider :anthropic}
             (:context request)))))

  (testing "explicit model request normalizes provider to keyword"
    (let [request (sut/normalize-request {:mode :explicit
                                          :model {:provider "openai"
                                                  :id "gpt-5-mini"}})]
      (is (= :explicit (:mode request)))
      (is (= {:provider :openai :id "gpt-5-mini"}
             (:model request))))))
