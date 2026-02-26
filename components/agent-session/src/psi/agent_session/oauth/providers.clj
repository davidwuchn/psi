(ns psi.agent-session.oauth.providers
  "OAuth provider registry and provider implementations.

   Each provider is a map:
     :id                  — keyword identifier
     :name                — display name
     :uses-callback-server — true if login uses a local redirect server
     :login               — (fn [callbacks]) → credential map
     :refresh-token       — (fn [credential]) → updated credential map
     :get-api-key         — (fn [credential]) → api key string"
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [psi.agent-session.oauth.pkce :as pkce])
  (:import [java.net URLEncoder]))

;;; Provider registry

(defonce ^:private registry (atom {}))

(defn register-provider!
  "Register an OAuth provider."
  [provider]
  (swap! registry assoc (:id provider) provider))

(defn get-provider
  "Look up a provider by id keyword."
  [id]
  (get @registry (keyword id)))

(defn list-providers
  "Return all registered providers."
  []
  (vals @registry))

;;; URL encoding helper

(defn- url-encode ^String [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn- build-query-string
  "Build a URL query string from a map."
  [params]
  (->> params
       (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode (str v)))))
       (clojure.string/join "&")))

;;; Browser open

(defn- open-browser!
  "Attempt to open a URL in the default browser. Best-effort, no error on failure."
  [^String url]
  (try
    (let [os (System/getProperty "os.name")]
      (cond
        (.contains os "Mac")   (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["open" url]))
        (.contains os "Linux") (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["xdg-open" url]))
        (.contains os "Win")   (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["cmd" "/c" "start" url]))))
    (catch Exception _)))

;;; Anthropic OAuth provider

(def ^:private anthropic-client-id "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
(def ^:private anthropic-authorize-url "https://claude.ai/oauth/authorize")
(def ^:private anthropic-token-url "https://console.anthropic.com/v1/oauth/token")
(def ^:private anthropic-redirect-uri "https://console.anthropic.com/oauth/code/callback")
(def ^:private anthropic-scopes "org:create_api_key user:profile user:inference")

(defn- anthropic-login
  "Run Anthropic OAuth authorization code + PKCE flow.

   Callbacks map:
     :on-auth     — (fn [{:keys [url instructions]}]) show URL to user
     :on-prompt   — (fn [{:keys [message placeholder]}]) → user input string
     :on-progress — (fn [message]) optional progress updates
     :signal      — atom, set to true to cancel"
  [callbacks]
  (let [{:keys [verifier challenge]} (pkce/generate-pkce)
        auth-url (str anthropic-authorize-url "?"
                      (build-query-string
                       {"code"                  "true"
                        "client_id"             anthropic-client-id
                        "response_type"         "code"
                        "redirect_uri"          anthropic-redirect-uri
                        "scope"                 anthropic-scopes
                        "code_challenge"        challenge
                        "code_challenge_method" "S256"
                        "state"                 verifier}))]
    ;; Show URL to user
    ((:on-auth callbacks) {:url auth-url})
    (open-browser! auth-url)

    ;; Prompt for authorization code (format: code#state)
    (let [input     ((:on-prompt callbacks) {:message "Paste the authorization code:"})
          [code state] (str/split input #"#" 2)
          ;; Exchange code for tokens
          response  (http/post anthropic-token-url
                               {:content-type :json
                                :as           :json
                                :cookie-policy :none
                                :body         (json/generate-string
                                               {"grant_type"    "authorization_code"
                                                "client_id"     anthropic-client-id
                                                "code"          code
                                                "state"         state
                                                "redirect_uri"  anthropic-redirect-uri
                                                "code_verifier" verifier})})
          {:keys [access_token refresh_token expires_in]} (:body response)
          ;; 5-minute buffer before expiry
          expires-at (- (+ (System/currentTimeMillis) (* expires_in 1000))
                        (* 5 60 1000))]
      {:type    :oauth
       :refresh refresh_token
       :access  access_token
       :expires expires-at})))

(defn- anthropic-refresh
  "Refresh an Anthropic OAuth token."
  [credential]
  (let [response (http/post anthropic-token-url
                            {:content-type :json
                             :as           :json
                             :cookie-policy :none
                             :body         (json/generate-string
                                            {"grant_type"    "refresh_token"
                                             "client_id"     anthropic-client-id
                                             "refresh_token" (:refresh credential)})})
        {:keys [access_token refresh_token expires_in]} (:body response)]
    {:type    :oauth
     :refresh refresh_token
     :access  access_token
     :expires (- (+ (System/currentTimeMillis) (* expires_in 1000))
                 (* 5 60 1000))}))

(def anthropic-provider
  {:id                   :anthropic
   :name                 "Anthropic (Claude Pro/Max)"
   :uses-callback-server false
   :login                anthropic-login
   :refresh-token        anthropic-refresh
   :get-api-key          :access})

;;; Registration

(defn register-default-providers!
  "Register all built-in OAuth providers."
  []
  (register-provider! anthropic-provider))
