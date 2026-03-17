(ns psi.ai.providers.anthropic
  "Anthropic provider implementation.

   The provider exposes a single `:stream` fn that accepts a conversation,
   model, options, and a `consume-fn` callback. Events are delivered
   synchronously on the current thread so callers are not forced to use
   core.async."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.models :as models])
  (:import [java.io InputStream]
           [java.util UUID]))

(def ^:private anthropic-tool-id-pattern
  "Anthropic requires tool_use.id to match ^[a-zA-Z0-9_-]+$."
  #"^[a-zA-Z0-9_-]+$")

(def ^:private anthropic-version "2023-06-01")
(def ^:private interleaved-thinking-beta "interleaved-thinking-2025-05-14")
(def ^:private prompt-caching-beta "prompt-caching-2024-07-31")
(def ^:private prompt-caching-scope-beta "prompt-caching-scope-2026-01-05")
(def ^:private context-management-beta "context-management-2025-06-27")

(defn- valid-anthropic-tool-id?
  [id]
  (and (string? id)
       (boolean (re-matches anthropic-tool-id-pattern id))))

(defn- fallback-anthropic-tool-id
  []
  (str "tool_" (UUID/randomUUID)))

(defn- ensure-anthropic-tool-id
  "Return an Anthropic-safe tool id (alnum, underscore, hyphen only).
   Generates a fallback when id is nil/blank/invalid."
  [id]
  (let [s (str (or id ""))
        sanitized (-> s
                      (str/replace #"[^a-zA-Z0-9_-]" "_")
                      (str/replace #"_+" "_")
                      (str/replace #"-+" "-")
                      (str/replace #"^[_-]+|[_-]+$" ""))]
    (or (when (valid-anthropic-tool-id? s)
          s)
        (when (valid-anthropic-tool-id? sanitized)
          sanitized)
        (fallback-anthropic-tool-id))))

(defn- canonical-tool-id-fn
  []
  (let [tool-id-map (atom {})]
    (fn [raw-id]
      (let [key (str (or raw-id ""))]
        (or (get @tool-id-map key)
            (let [canonical-id (ensure-anthropic-tool-id raw-id)]
              (swap! tool-id-map assoc key canonical-id)
              canonical-id))))))

(defn- anthropic-cache-control
  [cache-control]
  (when (= :ephemeral (:type cache-control))
    {:type "ephemeral"}))

(defn- with-cache-control
  [payload cache-control]
  (if-let [cache-control* (anthropic-cache-control cache-control)]
    (assoc payload :cache_control cache-control*)
    payload))

(defn- user-content
  [msg]
  [{:type "text"
    :text (get-in msg [:content :text]
                  (str (:content msg)))}])

(defn- assistant-block
  [canonical-id block]
  (case (:kind block)
    :thinking
    (cond-> {:type     "thinking"
             :thinking (or (:text block) "")}
      (some? (:signature block)) (assoc :signature (:signature block)))

    :text
    (with-cache-control {:type "text"
                         :text (:text block)}
      (:cache-control block))

    :tool-call
    (with-cache-control {:type  "tool_use"
                         :id    (canonical-id (:id block))
                         :name  (:name block)
                         :input (if (map? (:input block))
                                  (:input block)
                                  {})}
      (:cache-control block))

    {:type "text"
     :text (str block)}))

(defn- assistant-content
  [msg canonical-id]
  (if (= :structured (get-in msg [:content :kind]))
    (mapv (partial assistant-block canonical-id)
          (get-in msg [:content :blocks]))
    [{:type "text"
      :text (get-in msg [:content :text] "")}]))

(defn- tool-result-block
  [msg canonical-id]
  (let [text (if (map? (:content msg))
               (get-in msg [:content :text] "")
               (str (:content msg)))]
    (cond-> {:type        "tool_result"
             :tool_use_id (canonical-id (:tool-call-id msg))
             :content     text}
      (:is-error msg) (assoc :is_error true))))

(defn- append-tool-result
  [acc block]
  (let [last-msg (peek acc)]
    (if (and (= "user" (:role last-msg))
             (every? #(= "tool_result" (:type %))
                     (:content last-msg)))
      (conj (pop acc) (update last-msg :content conj block))
      (conj acc {:role "user" :content [block]}))))

(defn- transform-message
  [canonical-id acc msg]
  (case (:role msg)
    :user
    (conj acc {:role "user"
               :content (user-content msg)})

    :assistant
    (conj acc {:role "assistant"
               :content (assistant-content msg canonical-id)})

    :tool-result
    (append-tool-result acc (tool-result-block msg canonical-id))

    acc))

(defn transform-messages
  "Transform conversation messages to Anthropic API format.
   Handles user, assistant (with optional tool_use), and tool-result messages.
   Consecutive tool-result messages are merged into a single user message
   (Anthropic requires all tool_result blocks in one user message).

   Also normalizes tool IDs to Anthropic's required pattern.
   See: messages.*.content.*.tool_use.id must match ^[a-zA-Z0-9_-]+$."
  [conversation]
  (let [canonical-id (canonical-tool-id-fn)]
    (reduce (partial transform-message canonical-id)
            []
            (:messages conversation))))

(def ^:private thinking-level->budget
  "Map thinking-level keyword to Anthropic extended-thinking budget_tokens.
   nil means thinking is disabled."
  {:off     nil
   :minimal 1024
   :low     2048
   :medium  8000
   :high    16000
   :xhigh   32000})

(defn- thinking-param
  "Return the Anthropic `thinking` request param map for OPTIONS, or nil when disabled."
  [model options]
  (when (:supports-reasoning model)
    (when-let [budget (get thinking-level->budget (:thinking-level options))]
      {:type          "enabled"
       :budget_tokens budget})))

(defn- tool-definitions
  [conversation]
  (when (seq (:tools conversation))
    (mapv (fn [tool]
            (with-cache-control
              {:name         (:name tool)
               :description  (:description tool)
               :input_schema (:parameters tool)}
              (:cache-control tool)))
          (:tools conversation))))

(defn- oauth-api-key?
  [api-key]
  (and api-key (str/includes? api-key "sk-ant-oat")))

(defn- cache-control-present?
  [x]
  (cond
    (map? x)
    (or (contains? x :cache-control)
        (some cache-control-present? (vals x)))

    (sequential? x)
    (boolean (some cache-control-present? x))

    :else
    false))

(defn- prompt-caching?
  [conversation]
  (or (cache-control-present? (:system-prompt-blocks conversation))
      (cache-control-present? (:tools conversation))
      (cache-control-present? (:messages conversation))))

(defn- beta-header
  [oauth? thinking prompt-caching?]
  (let [betas (cond-> []
                oauth?          (into ["claude-code-20250219"
                                       "oauth-2025-04-20"
                                       context-management-beta
                                       prompt-caching-scope-beta])
                thinking        (conj interleaved-thinking-beta)
                prompt-caching? (conj prompt-caching-beta))]
    (when (seq betas)
      (->> betas
           distinct
           (str/join ",")))))

(defn- request-headers
  [api-key thinking prompt-caching?]
  (let [oauth?  (oauth-api-key? api-key)
        headers (if oauth?
                  {"Content-Type"      "application/json"
                   "Authorization"     (str "Bearer " api-key)
                   "anthropic-version" anthropic-version}
                  {"Content-Type"      "application/json"
                   "x-api-key"         api-key
                   "anthropic-version" anthropic-version})
        beta    (beta-header oauth? thinking prompt-caching?)]
    (cond-> headers
      beta (assoc "anthropic-beta" beta))))

(defn- system-prompt-body
  [conversation]
  (let [blocks (:system-prompt-blocks conversation)]
    (cond
      (seq blocks)
      (mapv (fn [block]
              (with-cache-control {:type "text"
                                   :text (:text block)}
                (:cache-control block)))
            blocks)

      (some? (:system-prompt conversation))
      (:system-prompt conversation)

      :else
      nil)))

(defn build-request
  "Build Anthropic API request map.
   Includes tools from conversation when present.
   When the model supports reasoning and thinking-level is set (non-:off),
   adds the extended-thinking param and the required interleaved-thinking beta header.
   When prompt cache directives are present, also adds the Anthropic prompt-caching beta header."
  [conversation model options]
  (let [thinking        (thinking-param model options)
        api-key         (or (:api-key options) (System/getenv "ANTHROPIC_API_KEY"))
        tool-defs       (tool-definitions conversation)
        system-body     (system-prompt-body conversation)
        prompt-caching? (prompt-caching? conversation)
        body            (cond-> {:model      (:id model)
                                 :max_tokens (or (:max-tokens options) (:max-tokens model))
                                 :messages   (transform-messages conversation)
                                 :stream     true}
                          (some? system-body) (assoc :system system-body)
                          ;; temperature is incompatible with extended thinking
                          (not thinking)      (assoc :temperature (or (:temperature options) 0.7))
                          thinking            (assoc :thinking thinking)
                          (seq tool-defs)     (assoc :tools tool-defs))]
    {:headers (request-headers api-key thinking prompt-caching?)
     :body    (json/generate-string body)}))

(defn- safe-call!
  [f payload]
  (when (fn? f)
    (try
      (f payload)
      (catch Exception _
        nil))))

(defn- redact-secret
  [value]
  (when (string? value)
    (str "***REDACTED***"
         (when (> (count value) 20)
           (str " (len=" (count value) ")")))))

(defn- redact-authorization
  [value]
  (when (string? value)
    (str "Bearer "
         (redact-secret (str/replace value #"^Bearer\s+" "")))))

(defn- redact-request-headers
  [headers]
  (cond-> headers
    (contains? headers "Authorization")
    (assoc "Authorization"
           (redact-authorization (get headers "Authorization")))

    (contains? headers "x-api-key")
    (assoc "x-api-key"
           (redact-secret (get headers "x-api-key")))))

(defn- parse-json-body-safe
  [body]
  (try
    (json/parse-string (str (or body "")) true)
    (catch Exception _
      (str (or body "")))))

(defn- capture-request!
  [options url request]
  (safe-call! (:on-provider-request options)
              {:provider :anthropic
               :api :anthropic-messages
               :url url
               :request {:headers (redact-request-headers (:headers request))
                         :body (parse-json-body-safe (:body request))}}))

(defn- capture-response!
  [options url event]
  (safe-call! (:on-provider-response options)
              {:provider :anthropic
               :api :anthropic-messages
               :url url
               :event event}))

(defn parse-sse-line
  "Parse a Server-Sent Events `data:` line; returns nil for non-data lines."
  [line]
  (when (str/starts-with? (or line "") "data: ")
    (let [data (subs line 6)]
      (when (not= data "[DONE]")
        (try
          (json/parse-string data true)
          (catch Exception _
            nil))))))

(defn- update-start-usage!
  [usage-acc usage]
  (when usage
    (swap! usage-acc assoc
           :input-tokens       (or (:input_tokens usage) 0)
           :cache-read-tokens  (or (:cache_read_input_tokens usage) 0)
           :cache-write-tokens (or (:cache_creation_input_tokens usage) 0))))

(defn- update-output-usage!
  [usage-acc usage]
  (when usage
    (swap! usage-acc assoc
           :output-tokens (or (:output_tokens usage) 0))))

(defn- usage-with-cost
  [model usage-acc]
  (let [usage @usage-acc
        usage (assoc usage :total-tokens (+ (:input-tokens usage)
                                            (:output-tokens usage)
                                            (:cache-read-tokens usage)
                                            (:cache-write-tokens usage)))]
    (assoc usage :cost (models/calculate-cost model usage))))

(defn- content-block-start-event
  [idx block]
  (case (:type block)
    "tool_use"
    {:type          :toolcall-start
     :content-index idx
     :id            (:id block)
     :name          (:name block)}

    "thinking"
    {:type          :thinking-start
     :content-index idx
     :thinking      (:thinking block)
     :signature     (:signature block)}

    {:type          :text-start
     :content-index idx}))

(defn- content-block-delta-event
  [btype idx delta]
  (case btype
    "tool_use"
    (when-let [json-delta (:partial_json delta)]
      {:type          :toolcall-delta
       :content-index idx
       :delta         json-delta})

    "thinking"
    (cond
      (some? (:signature delta))
      {:type          :thinking-signature-delta
       :content-index idx
       :signature     (:signature delta)}

      :else
      (when-let [text (or (:thinking delta) (:text delta))]
        {:type          :thinking-delta
         :content-index idx
         :delta         text}))

    (when-let [text (:text delta)]
      {:type          :text-delta
       :content-index idx
       :delta         text})))

(defn- content-block-stop-event
  [btype idx]
  {:type          (if (= "tool_use" btype)
                    :toolcall-end
                    :text-end)
   :content-index idx})

(defn- consume-event!
  [consume-fn event]
  (when event
    (consume-fn event)))

(defn- body->text
  [body]
  (try
    (cond
      (nil? body) nil
      (string? body) body
      (instance? InputStream body) (slurp (io/reader body))
      :else (str body))
    (catch Exception _ nil)))

(defn- parse-error-message
  [body-text]
  (when (seq body-text)
    (try
      (let [m (json/parse-string body-text true)]
        (or (get-in m [:error :message])
            (get-in m [:message])
            body-text))
      (catch Exception _
        body-text))))

(defn- request-id-from-headers
  [headers]
  (or (get headers "request-id")
      (get headers "Request-Id")
      (get headers "x-request-id")
      (get headers "X-Request-Id")
      (get headers "X-Request-ID")))

(defn- exception->error
  [e]
  (let [data       (ex-data e)
        status     (:status data)
        body-text  (body->text (:body data))
        parsed-msg (parse-error-message body-text)
        base-msg   (or parsed-msg (ex-message e) (str e))
        req-id     (request-id-from-headers (:headers data))
        full-msg   (str base-msg
                        (when status
                          (str " (status " status ")"))
                        (when req-id
                          (str " [request-id " req-id "]")))]
    (cond-> {:type :error :error-message full-msg}
      status (assoc :http-status status))))

(defn stream-anthropic
  "Stream response from Anthropic API.

   Calls `consume-fn` with each event map on the current thread (blocking).
   Returns when streaming is complete or an error occurs.

   Event types emitted:
     {:type :start}
     {:type :text-start      :content-index n}
     {:type :text-delta      :content-index n  :delta \"...\"}
     {:type :text-end        :content-index n}
     {:type :thinking-start  :content-index n  :thinking \"\" :signature \"\"}
     {:type :thinking-delta  :content-index n  :delta \"...\"}
     {:type :thinking-signature-delta :content-index n :signature \"...\"}
     {:type :toolcall-start  :content-index n :id \"toolu_...\" :name \"tool-name\"}
     {:type :toolcall-delta  :content-index n :delta \"partial-json\"}
     {:type :toolcall-end    :content-index n}
     {:type :done            :reason kw  :usage {...}}
     {:type :error           :error-message \"...\"}
   "
  [conversation model options consume-fn]
  (let [url         (str (:base-url model) "/v1/messages")
        request     (build-request conversation model options)
        block-types (atom {})
        usage-acc   (atom {:input-tokens       0
                           :output-tokens      0
                           :cache-read-tokens  0
                           :cache-write-tokens 0})
        done?       (atom false)]
    (try
      (capture-request! options url request)
      (let [response (http/post url
                                (merge request {:as :stream}))]
        (with-open [reader (io/reader (:body response))]
          (doseq [line (line-seq reader)]
            (when-let [event-data (parse-sse-line line)]
              (capture-response! options url event-data)
              (case (:type event-data)
                "message_start"
                (do
                  (update-start-usage! usage-acc (get-in event-data [:message :usage]))
                  (consume-fn {:type :start}))

                "content_block_start"
                (let [idx   (:index event-data)
                      block (:content_block event-data)]
                  (swap! block-types assoc idx (:type block))
                  (consume-fn (content-block-start-event idx block)))

                "content_block_delta"
                (consume-event! consume-fn
                                (content-block-delta-event (get @block-types (:index event-data))
                                                           (:index event-data)
                                                           (:delta event-data)))

                "content_block_stop"
                (consume-fn (content-block-stop-event (get @block-types (:index event-data))
                                                      (:index event-data)))

                "message_delta"
                (do
                  (update-output-usage! usage-acc (:usage event-data))
                  (when-let [reason (get-in event-data [:delta :stop_reason])]
                    (reset! done? true)
                    (consume-fn {:type   :done
                                 :reason (keyword reason)
                                 :usage  (usage-with-cost model usage-acc)})))

                "message_stop"
                (when-not @done?
                  (consume-fn {:type :done :reason :stop}))

                nil)))))
      (catch Exception e
        (let [err (exception->error e)]
          (capture-response! options url err)
          (consume-fn err))))))

(def provider
  {:name   :anthropic
   :stream stream-anthropic})
