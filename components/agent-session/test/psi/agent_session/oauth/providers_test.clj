(ns psi.agent-session.oauth.providers-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clj-http.client :as http]
   [psi.agent-session.oauth.callback-server :as cb]
   [psi.agent-session.oauth.providers :as providers]))

(deftest default-registry-includes-openai-test
  (testing "register-default-providers! registers both anthropic and openai"
    (providers/register-default-providers!)
    (is (some? (providers/get-provider :anthropic)))
    (is (some? (providers/get-provider :openai)))
    (is (true? (:uses-callback-server (providers/get-provider :openai))))))

(deftest openai-complete-login-parses-full-url-test
  (testing "openai complete-login accepts full redirect URL input"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url opts]
                                (reset! captured {:url url :opts opts})
                                {:body {:access_token "oa-access"
                                        :refresh_token "oa-refresh"
                                        :expires_in 3600}})]
        (let [cred (#'psi.agent-session.oauth.providers/openai-complete-login
                    "http://localhost:1455/auth/callback?code=test-code&state=test-state"
                    {:verifier "test-verifier"
                     :state    "test-state"
                     :redirect-uri "http://localhost:1455/auth/callback"})]
          (is (= :oauth (:type cred)))
          (is (= "oa-access" (:access cred)))
          (is (= "oa-refresh" (:refresh cred)))
          (is (number? (:expires cred)))
          (is (= "test-code"
                 (get-in @captured [:opts :form-params "code"]))))))))

(deftest openai-complete-login-uses-callback-server-test
  (testing "openai complete-login waits for callback when input is blank"
    (let [wait-called (atom nil)
          closed?     (atom false)
          captured    (atom nil)
          callback    {:wait-for-code (fn [timeout-ms]
                                        (reset! wait-called timeout-ms)
                                        {:code "cb-code" :state "cb-state"})
                       :close (fn [] (reset! closed? true))}]
      (with-redefs [http/post (fn [_url opts]
                                (reset! captured opts)
                                {:body {:access_token "oa-access"
                                        :refresh_token "oa-refresh"
                                        :expires_in 3600}})]
        (let [cred (#'psi.agent-session.oauth.providers/openai-complete-login
                    nil
                    {:verifier "v"
                     :state "cb-state"
                     :callback-server callback
                     :redirect-uri "http://localhost:1455/auth/callback"})]
          (is (= :oauth (:type cred)))
          (is (= 180000 @wait-called))
          (is @closed?)
          (is (= "cb-code" (get-in @captured [:form-params "code"]))))))))

(deftest openai-complete-login-state-mismatch-test
  (testing "state mismatch throws"
    (with-redefs [http/post (fn [_url _opts]
                              {:body {:access_token "x" :refresh_token "y" :expires_in 3600}})]
      (is (thrown-with-msg? Exception #"State mismatch"
            (#'psi.agent-session.oauth.providers/openai-complete-login
             "code#wrong-state"
             {:verifier "v" :state "expected"}))))))

(deftest openai-begin-login-starts-callback-server-test
  (testing "openai begin-login includes callback server in login-state"
    (with-redefs [cb/start-server
                  (fn [_opts]
                    {:port 1455
                     :wait-for-code (fn [_] nil)
                     :close (fn [])})
                  providers/open-browser!
                  (fn [_url] nil)]
      (let [{:keys [url login-state]}
            (#'psi.agent-session.oauth.providers/openai-begin-login)]
        (is (str/includes? url "auth.openai.com/oauth/authorize"))
        (is (str/includes? url "redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
        (is (string? (:verifier login-state)))
        (is (string? (:state login-state)))
        (is (= "http://localhost:1455/auth/callback" (:redirect-uri login-state)))
        (is (map? (:callback-server login-state)))))))
