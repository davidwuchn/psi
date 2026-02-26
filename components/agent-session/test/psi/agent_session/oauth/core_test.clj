(ns psi.agent-session.oauth.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.agent-session.oauth.core :as oauth]
            [psi.agent-session.oauth.store :as store]))

(deftest login-test
  ;; Login stores credentials via the stub provider
  (testing "login stores oauth credential and returns it"
    (let [ctx  (oauth/create-null-context)
          cred (oauth/login! ctx :anthropic
                             {:on-auth     (fn [_])
                              :on-prompt   (fn [_] "fake-code")
                              :on-progress (fn [_])})]
      (is (= :oauth (:type cred)))
      (is (string? (:access cred)))
      (is (string? (:refresh cred)))
      (is (number? (:expires cred)))
      (is (oauth/has-auth? ctx :anthropic))))

  (testing "login with unknown provider throws"
    (let [ctx (oauth/create-null-context)]
      (is (thrown-with-msg? Exception #"Unknown OAuth provider"
            (oauth/login! ctx :nonexistent {:on-auth (fn [_]) :on-prompt (fn [_] "")}))))))

(deftest logout-test
  (testing "logout removes credential"
    (let [ctx (oauth/create-null-context)]
      (oauth/login! ctx :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (oauth/has-auth? ctx :anthropic))
      (oauth/logout! ctx :anthropic)
      (is (not (oauth/has-auth? ctx :anthropic))))))

(deftest get-api-key-test
  (testing "returns access token after login"
    (let [ctx (oauth/create-null-context)]
      (oauth/login! ctx :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (= "test-access" (oauth/get-api-key ctx :anthropic)))))

  (testing "auto-refreshes expired token"
    (let [ctx (oauth/create-null-context
               {:credentials {:anthropic {:type :oauth :refresh "old" :access "old"
                                           :expires 1000}}})
          key (oauth/get-api-key ctx :anthropic)]
      (is (= "refreshed-access" key))
      ;; Store should have the refreshed credential
      (is (= "refreshed-access"
             (:access (store/get-credential (:store ctx) :anthropic))))))

  (testing "returns nil when no auth configured"
    (let [ctx (oauth/create-null-context)]
      (is (nil? (oauth/get-api-key ctx :nonexistent))))))

(deftest refresh-token-test
  (testing "refresh updates expired credential"
    (let [ctx       (oauth/create-null-context
                     {:credentials {:anthropic {:type :oauth :refresh "old" :access "old"
                                                 :expires 1000}}})
          refreshed (oauth/refresh-token! ctx :anthropic)]
      (is (= "refreshed-access" (:access refreshed)))
      (is (= "refreshed-access"
             (:access (store/get-credential (:store ctx) :anthropic))))))

  (testing "refresh with no credential throws"
    (let [ctx (oauth/create-null-context)]
      (is (thrown-with-msg? Exception #"No OAuth credential"
            (oauth/refresh-token! ctx :anthropic))))))

(deftest available-providers-test
  (testing "lists registered providers"
    (let [ctx (oauth/create-null-context)]
      (is (= 1 (count (oauth/available-providers ctx))))
      (is (= :anthropic (:id (first (oauth/available-providers ctx))))))))

(deftest logged-in-providers-test
  (testing "returns only providers with oauth credentials"
    (let [ctx (oauth/create-null-context)]
      (is (empty? (oauth/logged-in-providers ctx)))
      (oauth/login! ctx :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (= [:anthropic] (map :id (oauth/logged-in-providers ctx)))))))

(deftest context-isolation-test
  ;; Two null contexts are independent
  (testing "contexts do not share state"
    (let [a (oauth/create-null-context)
          b (oauth/create-null-context)]
      (oauth/login! a :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (oauth/has-auth? a :anthropic))
      (is (not (oauth/has-auth? b :anthropic))))))

(deftest custom-provider-test
  ;; Tests can inject custom providers
  (testing "custom provider login and api-key"
    (let [ctx       (oauth/create-null-context
                     {:providers [{:id          :custom
                                   :name        "Custom Provider"
                                   :login       (fn [callbacks]
                                                  ((:on-auth callbacks) {:url "https://custom.example.com"})
                                                  {:type :oauth :access "custom-tok" :refresh "custom-ref"
                                                   :expires (+ (System/currentTimeMillis) 3600000)})
                                   :refresh-token (fn [_] {:type :oauth :access "new-tok" :refresh "new-ref"
                                                            :expires (+ (System/currentTimeMillis) 3600000)})
                                   :get-api-key :access}]})
          auth-urls (atom [])]
      (oauth/login! ctx :custom
                    {:on-auth   (fn [info] (swap! auth-urls conj (:url info)))
                     :on-prompt (fn [_] "x")})
      (is (= ["https://custom.example.com"] @auth-urls))
      (is (= "custom-tok" (oauth/get-api-key ctx :custom))))))
