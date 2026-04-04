(ns psi.ai.providers.openai.common
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json])
  (:import [java.util Base64 UUID]))

(def thinking-level->effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(def reasoning-part-types
  #{"reasoning"
    "reasoning_text"
    "reasoning_content"
    "reasoning_summary"
    "summary"
    "summary_text"})

(defn reasoning-effort
  "Return provider reasoning effort string for MODEL/OPTIONS, or nil when disabled."
  [model options]
  (when (:supports-reasoning model)
    (get thinking-level->effort
         (:thinking-level options)
         "medium")))

(defn normalize-part-type
  [part]
  (let [t (:type part)]
    (cond
      (keyword? t) (name t)
      (string? t) t
      :else nil)))

(defn join-parts
  [parts]
  (when (seq parts)
    (str/join "" parts)))

(defn string-fragment
  "Normalize provider text fragments to a plain string."
  [x]
  (letfn [(->text [v]
            (cond
              (nil? v) nil
              (string? v) v
              (number? v) (str v)
              (keyword? v) (name v)
              (sequential? v) (join-parts (keep ->text v))
              (map? v) (or (->text (:text v))
                           (->text (:content v))
                           (->text (:delta v))
                           (->text (:summary v))
                           (->text (:value v)))
              :else nil))]
    (->text x)))

(defn normalize-tool-arguments
  "Normalize streamed function arguments into a string payload."
  [args]
  (cond
    (nil? args) nil
    (string? args) args
    (map? args) (json/generate-string args)
    (sequential? args) (or (join-parts (keep string-fragment args))
                           (str args))
    :else (str args)))

(defn accumulate-tool-arguments
  "Merge incoming tool-argument chunk into current buffer."
  [current incoming]
  (let [cur (or current "")
        inc (or incoming "")]
    (cond
      (not (seq inc))
      {:buffer cur :delta nil}

      (str/starts-with? inc cur)
      (let [delta (subs inc (count cur))]
        {:buffer inc
         :delta  (when (seq delta) delta)})

      (str/starts-with? cur inc)
      {:buffer cur :delta nil}

      :else
      {:buffer (str cur inc)
       :delta  inc})))

(defn user-message-text
  [msg]
  (let [content (:content msg)]
    (cond
      (string? content) content
      (and (map? content) (= :text (:kind content)))
      (or (:text content) (get content "text") "")
      (and (map? content) (= :structured (:kind content)))
      (->> (:blocks content)
           (keep #(when (= :text (:kind %)) (:text %)))
           (str/join "\n"))
      (map? content)
      (or (:text content) (get content "text") "")
      :else
      (str content))))

(defn safe-call!
  [f payload]
  (when (fn? f)
    (try
      (f payload)
      (catch Exception _
        nil))))

(defn stream-response
  [url request]
  (http/post url (merge request {:as :stream :cookie-policy :none})))

(defn redact-authorization
  [value]
  (when (string? value)
    (str "Bearer ***REDACTED***"
         (when (> (count value) 20)
           (str " (len=" (count value) ")")))))

(defn mask-chatgpt-account-id
  [value]
  (when (string? value)
    (str (subs value 0 (min 6 (count value))) "...")))

(defn redact-request-headers
  [headers]
  (cond-> headers
    (contains? headers "Authorization")
    (assoc "Authorization"
           (redact-authorization (get headers "Authorization")))

    (contains? headers "chatgpt-account-id")
    (assoc "chatgpt-account-id"
           (mask-chatgpt-account-id (get headers "chatgpt-account-id")))))

(defn parse-json-body-safe
  [body]
  (try
    (json/parse-string (str (or body "")) true)
    (catch Exception _
      (str (or body "")))))

(defn capture-request!
  [options api url request]
  (safe-call! (:on-provider-request options)
              {:provider :openai
               :api api
               :url url
               :request {:headers (redact-request-headers (:headers request))
                         :body (parse-json-body-safe (:body request))}}))

(defn capture-response!
  [options api url event]
  (safe-call! (:on-provider-response options)
              {:provider :openai
               :api api
               :url url
               :event event}))

(defn parse-sse-line
  "Parse a Server-Sent Events `data:` line; returns nil for non-data lines."
  [line]
  (when (and line (.startsWith ^String line "data: "))
    (let [data (.substring ^String line 6)]
      (when (not= data "[DONE]")
        (try (json/parse-string data true)
             (catch Exception _ nil))))))

(defn body->text
  [body]
  (try
    (cond
      (nil? body) nil
      (string? body) body
      (instance? java.io.InputStream body) (slurp body)
      :else (str body))
    (catch Exception _ nil)))

(defn parse-json-text-safe
  [text]
  (when (seq text)
    (try
      (json/parse-string text true)
      (catch Exception _
        nil))))

(defn parsed-error-message
  [parsed-body]
  (or (get-in parsed-body [:error :message])
      (get-in parsed-body [:message])))

(defn parse-error-message
  [body-text]
  (when (seq body-text)
    (or (some-> body-text
                parse-json-text-safe
                parsed-error-message)
        body-text)))

(defn request-id-from-headers
  [headers]
  (or (get headers "x-request-id")
      (get headers "x-oai-request-id")
      (get headers "x-openai-request-id")
      (get headers "X-Request-ID")
      (get headers "X-OAI-Request-ID")
      (get headers "X-OpenAI-Request-ID")))

(defn meaningful-error-message?
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (contains? #{"Error" "error" "Exception"} s))))

(defn fallback-status-message
  [status]
  (case status
    400 "OpenAI rejected the request"
    401 "OpenAI authentication failed"
    403 "OpenAI authorization failed"
    404 "OpenAI endpoint not found"
    429 "OpenAI rate limit exceeded"
    500 "OpenAI server error"
    502 "OpenAI gateway error"
    503 "OpenAI service unavailable"
    "OpenAI request failed"))

(defn base-error-message
  [{:keys [status fallback-message parsed-body]}]
  (or (when-let [parsed-msg (some-> parsed-body parsed-error-message)]
        (when (meaningful-error-message? parsed-msg)
          parsed-msg))
      (when (meaningful-error-message? fallback-message)
        fallback-message)
      (fallback-status-message status)))

(defn error-message
  [{:keys [status headers fallback-message parsed-body]}]
  (str (base-error-message {:status status
                            :fallback-message fallback-message
                            :parsed-body parsed-body})
       (when status
         (str " (status " status ")"))
       (when-let [req-id (request-id-from-headers headers)]
         (str " [request-id " req-id "]"))))

(defn error-from-response-data
  [{:keys [status headers body-text fallback-message]}]
  (let [parsed-body (parse-json-text-safe body-text)]
    (cond-> {:type :error
             :error-message (error-message {:status status
                                            :headers headers
                                            :fallback-message fallback-message
                                            :parsed-body parsed-body})}
      status          (assoc :http-status status)
      headers         (assoc :headers headers)
      (seq body-text) (assoc :body-text body-text)
      parsed-body     (assoc :body parsed-body))))

(defn exception->error
  [e]
  (let [data (ex-data e)]
    (error-from-response-data {:status (:status data)
                               :headers (:headers data)
                               :body-text (body->text (:body data))
                               :fallback-message (or (ex-message e) (str e))})))

(defn new-msg-id [] (str "msg_" (UUID/randomUUID)))
(defn new-call-id [] (str "call_" (UUID/randomUUID)))

(def default-codex-base-url "https://chatgpt.com/backend-api")
(def codex-beta-header "responses=experimental")

(defn pad-base64url
  "Pad base64url string to a multiple of 4 chars."
  [s]
  (let [m (mod (count s) 4)]
    (case m
      0 s
      2 (str s "==")
      3 (str s "=")
      1 (str s "===")
      s)))

(defn extract-chatgpt-account-id
  "Extract chatgpt_account_id from OAuth JWT access token."
  [token]
  (try
    (let [[_ payload _] (str/split (or token "") #"\." 3)
          decoded       (String. (.decode (Base64/getUrlDecoder)
                                          (pad-base64url payload))
                                 "UTF-8")
          json-map      (json/parse-string decoded false)]
      (get-in json-map ["https://api.openai.com/auth" "chatgpt_account_id"]))
    (catch Exception _
      nil)))
