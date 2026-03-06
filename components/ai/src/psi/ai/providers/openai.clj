(ns psi.ai.providers.openai
  "OpenAI provider implementation.

   Supports two API variants behind the same :openai provider key:
   - :openai-completions      → OpenAI Chat Completions API
   - :openai-codex-responses  → ChatGPT Codex Responses endpoint

   The provider exposes a single `:stream` fn that accepts a conversation,
   model, options, and a `consume-fn` callback. Events are delivered
   synchronously on a dedicated thread so callers are not forced to use
   core.async."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.models :as models])
  (:import [java.util Base64 UUID]))

;; ───────────────────────────────────────────────────────────────────────────
;; OpenAI Chat Completions API
;; ───────────────────────────────────────────────────────────────────────────

(defn transform-messages
  "Transform conversation messages to OpenAI chat completions format.
   Handles user, assistant (with optional tool_calls), and tool-result messages."
  [conversation]
  (->> (:messages conversation)
       (reduce
        (fn [acc msg]
          (case (:role msg)
            :user
            (conj acc {:role    "user"
                       :content (get-in msg [:content :text] (str (:content msg)))})

            :assistant
            (let [kind (get-in msg [:content :kind])]
              (if (= :structured kind)
                (let [blocks     (get-in msg [:content :blocks])
                      text-parts (keep #(when (= :text (:kind %)) (:text %)) blocks)
                      tool-parts (filter #(= :tool-call (:kind %)) blocks)
                      text       (str/join "\n" text-parts)
                      base       (cond-> {:role "assistant"}
                                   (seq text) (assoc :content text))]
                  (conj acc
                        (if (seq tool-parts)
                          (assoc base :tool_calls
                                 (mapv (fn [tc]
                                         {:id       (:id tc)
                                          :type     "function"
                                          :function {:name      (:name tc)
                                                     :arguments (json/generate-string
                                                                 (:input tc))}})
                                       tool-parts))
                          base)))
                ;; :text or default
                (conj acc {:role    "assistant"
                           :content (get-in msg [:content :text] "")})))

            :tool-result
            (let [text (if (map? (:content msg))
                         (get-in msg [:content :text] "")
                         (str (:content msg)))]
              (conj acc {:role         "tool"
                         :tool_call_id (:tool-call-id msg)
                         :content      text}))

            ;; unknown — skip
            acc))
        [])))

(defn build-request
  "Build OpenAI Chat Completions API request map.
   Includes tools from conversation when present." 
  [conversation model options]
  (let [base-messages (transform-messages conversation)
        messages      (if (:system-prompt conversation)
                        (cons {:role    "system"
                               :content (:system-prompt conversation)}
                              base-messages)
                        base-messages)
        tool-defs     (when (seq (:tools conversation))
                        (mapv (fn [t]
                                {:type     "function"
                                 :function {:name        (:name t)
                                            :description (:description t)
                                            :parameters  (:parameters t)}})
                              (:tools conversation)))
        body          (cond-> {:model          (:id model)
                               :messages       (vec messages)
                               :stream         true
                               :stream_options {:include_usage true}}
                        (:temperature options) (assoc :temperature (:temperature options))
                        (:max-tokens options)  (assoc :max_tokens  (:max-tokens options))
                        (seq tool-defs)        (assoc :tools tool-defs))]
    {:headers {"Content-Type"  "application/json"
               "Authorization" (str "Bearer " (or (:api-key options)
                                                    (System/getenv "OPENAI_API_KEY")))}
     :body    (json/generate-string body)}))

(defn parse-sse-line
  "Parse a Server-Sent Events `data:` line; returns nil for non-data lines."
  [line]
  (when (and line (.startsWith ^String line "data: "))
    (let [data (.substring ^String line 6)]
      (when (not= data "[DONE]")
        (try (json/parse-string data true)
             (catch Exception _ nil))))))

(defn- body->text
  [body]
  (try
    (cond
      (nil? body) nil
      (string? body) body
      (instance? java.io.InputStream body) (slurp body)
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
  (or (get headers "x-request-id")
      (get headers "x-oai-request-id")
      (get headers "x-openai-request-id")
      (get headers "X-Request-ID")
      (get headers "X-OAI-Request-ID")
      (get headers "X-OpenAI-Request-ID")))

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

(defn stream-openai
  "Stream response from OpenAI Chat Completions API.

   Calls `consume-fn` with each event map on the current thread (blocking).
   Returns when streaming is complete or an error occurs.

   Event types emitted:
     {:type :start}
     {:type :text-delta       :content-index 0  :delta ...}
     {:type :thinking-delta   :content-index 0  :delta ...}
     {:type :toolcall-start   :content-index n  :id call_... :name tool-name}
     {:type :toolcall-delta   :content-index n  :delta partial-json}
     {:type :toolcall-end     :content-index n}
     {:type :done             :reason kw  :usage {...}}
     {:type :error            :error-message ...}
   "
  [conversation model options consume-fn]
  (let [request       (build-request conversation model options)
        ;; Track which tool-call indices have been started
        started-tools (atom #{})
        done?         (atom false)]
    (try
      (let [response (http/post (str (:base-url model) "/chat/completions")
                                (merge request {:as :stream :cookie-policy :none}))]
        (with-open [reader (io/reader (:body response))]
          (doseq [line (line-seq reader)]
            (when-let [chunk (parse-sse-line line)]
              (let [choice (first (:choices chunk))
                    delta  (:delta choice)]

                ;; Start of response — fires once, does NOT gate other fields.
                ;; OpenAI packs role + tool_calls in the same first chunk.
                (when (and choice (= (:role delta) "assistant"))
                  (consume-fn {:type :start}))

                (cond
                  ;; Content delta
                  (:content delta)
                  (consume-fn {:type          :text-delta
                               :content-index 0
                               :delta         (:content delta)})

                  ;; Reasoning content (o1 models)
                  (get-in delta [:reasoning :content])
                  (consume-fn {:type          :thinking-delta
                               :content-index 0
                               :delta         (get-in delta [:reasoning :content])})

                  ;; Tool calls — first chunk per index has :id + :name → start
                  (:tool_calls delta)
                  (doseq [tool-call (:tool_calls delta)]
                    (let [idx (:index tool-call)]
                      ;; Emit toolcall-start on first appearance of this index
                      (when (and (:id tool-call)
                                 (not (contains? @started-tools idx)))
                        (swap! started-tools conj idx)
                        (consume-fn {:type          :toolcall-start
                                     :content-index idx
                                     :id            (:id tool-call)
                                     :name          (get-in tool-call
                                                            [:function :name])}))
                      ;; Argument fragments → string delta
                      (when-let [args (get-in tool-call [:function :arguments])]
                        (when (seq args)
                          (consume-fn {:type          :toolcall-delta
                                       :content-index idx
                                       :delta         args})))))

                  ;; Completion with usage (final chunk)
                  (:usage chunk)
                  (let [usage (:usage chunk)]
                    ;; Close any open tool calls
                    (doseq [idx @started-tools]
                      (consume-fn {:type :toolcall-end :content-index idx}))
                    (reset! started-tools #{})
                    (when-not @done?
                      (reset! done? true)
                      (consume-fn {:type   :done
                                   :reason (keyword (get-in choice
                                                            [:finish_reason]
                                                            "stop"))
                                   :usage  {:input-tokens  (:prompt_tokens usage)
                                            :output-tokens (:completion_tokens usage)
                                            :total-tokens  (:total_tokens usage)
                                            :cost          (models/calculate-cost
                                                            model usage)}})))

                  ;; Finish without usage
                  (:finish_reason choice)
                  (do
                    ;; Close any open tool calls
                    (doseq [idx @started-tools]
                      (consume-fn {:type :toolcall-end :content-index idx}))
                    (reset! started-tools #{})
                    (when-not @done?
                      (reset! done? true)
                      (consume-fn {:type   :done
                                   :reason (keyword (:finish_reason choice))})))))))))
      (catch Exception e
        (consume-fn (exception->error e))))))

;; ───────────────────────────────────────────────────────────────────────────
;; OpenAI Codex Responses API (ChatGPT backend)
;; ───────────────────────────────────────────────────────────────────────────

(def ^:private default-codex-base-url "https://chatgpt.com/backend-api")
(def ^:private codex-beta-header "responses=experimental")

(defn- resolve-codex-url
  "Resolve base URL to the Codex responses endpoint."
  [base-url]
  (let [raw        (if (str/blank? base-url) default-codex-base-url base-url)
        normalized (str/replace raw #"/+$" "")]
    (cond
      (str/ends-with? normalized "/codex/responses") normalized
      (str/ends-with? normalized "/codex")           (str normalized "/responses")
      :else                                            (str normalized "/codex/responses"))))

(defn- pad-base64url
  "Pad base64url string to a multiple of 4 chars."
  [s]
  (let [m (mod (count s) 4)]
    (case m
      0 s
      2 (str s "==")
      3 (str s "=")
      1 (str s "===")
      s)))

(defn- extract-chatgpt-account-id
  "Extract chatgpt_account_id from OAuth JWT access token.
   Returns nil when extraction fails." 
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

(defn- user-text
  [msg]
  (let [content (:content msg)]
    (cond
      (string? content) content
      (map? content)    (or (:text content) (get content "text") "")
      :else             (str content))))

(defn- assistant-content->codex-items
  "Convert one assistant message to Responses API input items."
  [msg]
  (let [kind (get-in msg [:content :kind])]
    (if (= :structured kind)
      (let [blocks      (get-in msg [:content :blocks])
            text-parts  (keep #(when (= :text (:kind %)) (:text %)) blocks)
            tool-parts  (filter #(= :tool-call (:kind %)) blocks)
            text        (str/join "\n" text-parts)
            text-item   (when (seq text)
                          {"type"    "message"
                           "role"    "assistant"
                           "status"  "completed"
                           "id"      (str "msg_" (UUID/randomUUID))
                           "content" [{"type" "output_text"
                                        "text" text
                                        "annotations" []}]})
            tool-items  (map (fn [tc]
                               (let [raw-id          (or (:id tc) (str "call_" (UUID/randomUUID)))
                                     [call-id item-id] (if (str/includes? raw-id "|")
                                                         (str/split raw-id #"\|" 2)
                                                         [raw-id nil])]
                                 (cond-> {"type"      "function_call"
                                          "call_id"   call-id
                                          "name"      (or (:name tc) "tool")
                                          "arguments" (json/generate-string (or (:input tc) {}))}
                                   (seq item-id) (assoc "id" item-id))))
                             tool-parts)]
        (vec (concat (when text-item [text-item]) tool-items)))
      ;; plain text assistant message
      (let [text (get-in msg [:content :text] "")]
        (if (seq text)
          [{"type"    "message"
            "role"    "assistant"
            "status"  "completed"
            "id"      (str "msg_" (UUID/randomUUID))
            "content" [{"type" "output_text"
                         "text" text
                         "annotations" []}]}]
          [])))))

(defn- tool-result->codex-item
  [msg]
  (let [raw-id  (or (:tool-call-id msg) "")
        call-id (or (first (str/split raw-id #"\|" 2))
                    (str "call_" (UUID/randomUUID)))
        text    (if (map? (:content msg))
                  (or (get-in msg [:content :text]) "")
                  (str (:content msg)))]
    {"type"    "function_call_output"
     "call_id" call-id
     "output"  text}))

(defn- codex-input-messages
  "Convert conversation message history to Responses API input format."
  [conversation]
  (->> (:messages conversation)
       (mapcat (fn [msg]
                 (case (:role msg)
                   :user
                   [{"role"    "user"
                     "content" [{"type" "input_text"
                                  "text" (user-text msg)}]}]

                   :assistant
                   (assistant-content->codex-items msg)

                   :tool-result
                   [(tool-result->codex-item msg)]

                   ;; unknown
                   [])))
       vec))

(defn- codex-tools
  [conversation]
  (when (seq (:tools conversation))
    (mapv (fn [t]
            {"type"        "function"
             "name"        (:name t)
             "description" (:description t)
             "parameters"  (:parameters t)
             ;; Matches pi-mono's Codex provider behavior (strict null)
             "strict"      nil})
          (:tools conversation))))

(def ^:private thinking-level->effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(defn- codex-reasoning
  [model options]
  (when (:supports-reasoning model)
    (let [effort (get thinking-level->effort
                      (:thinking-level options)
                      "medium")]
      (when effort
        {"effort" effort
         "summary" "auto"}))))

(defn- codex-usage->usage-map
  [usage]
  (let [input-tokens  (or (:input_tokens usage) 0)
        output-tokens (or (:output_tokens usage) 0)
        cached        (or (get-in usage [:input_tokens_details :cached_tokens]) 0)
        total         (or (:total_tokens usage) (+ input-tokens output-tokens))]
    {:input-tokens       (max 0 (- input-tokens cached))
     :output-tokens      output-tokens
     :cache-read-tokens  cached
     :cache-write-tokens 0
     :total-tokens       total}))

(defn- codex-status->reason
  [status]
  (case status
    "incomplete" :length
    "failed"     :error
    "cancelled"  :error
    :stop))

(defn- build-codex-request
  "Build OpenAI Codex Responses API request map.
   Requires an OAuth access token that includes chatgpt_account_id." 
  [conversation model options]
  (let [api-key    (or (:api-key options)
                       (System/getenv "OPENAI_API_KEY"))
        account-id (extract-chatgpt-account-id api-key)]
    (when-not (seq api-key)
      (throw (ex-info "OpenAI API key is required"
                      {:provider :openai :api :openai-codex-responses})))
    (when-not (seq account-id)
      (throw (ex-info "OpenAI Codex requires ChatGPT OAuth access token (missing chatgpt_account_id)"
                      {:provider :openai :api :openai-codex-responses})))
    (let [headers (cond-> {"Content-Type"       "application/json"
                           "Authorization"      (str "Bearer " api-key)
                           "accept"             "text/event-stream"
                           "OpenAI-Beta"        codex-beta-header
                           "originator"         "psi"
                           "chatgpt-account-id" account-id}
                    (:session-id options)
                    (assoc "session_id"      (:session-id options)
                           "conversation_id" (:session-id options)))
          body    (cond-> {"model"               (:id model)
                           "store"               false
                           "stream"              true
                           "instructions"        (:system-prompt conversation)
                           "input"               (codex-input-messages conversation)
                           "text"                {"verbosity" "medium"}
                           "include"             ["reasoning.encrypted_content"]
                           "tool_choice"         "auto"
                           "parallel_tool_calls" true}
                    (:temperature options) (assoc "temperature" (:temperature options))
                    (:session-id options)  (assoc "prompt_cache_key" (:session-id options))
                    (seq (codex-tools conversation))
                    (assoc "tools" (codex-tools conversation))
                    (codex-reasoning model options)
                    (assoc "reasoning" (codex-reasoning model options)))]
      {:headers headers
       :body    (json/generate-string body)})))

(defn stream-openai-codex
  "Stream response from OpenAI Codex Responses API (ChatGPT backend).

   Endpoint: <base-url>/codex/responses
   Event source: SSE JSON events with `type` fields (response.*).

   Emits normalized events expected by the executor:
     :start, :text-delta, :thinking-delta,
     :toolcall-start, :toolcall-delta, :toolcall-end,
     :done, :error" 
  [conversation model options consume-fn]
  (let [started?             (atom false)
        done?                (atom false)
        next-tool-index      (atom 0)
        tool-by-item-id      (atom {})
        tool-by-output-index (atom {})
        open-tool-indexes    (atom #{})]

    (letfn [(emit-start! []
              (when (compare-and-set! started? false true)
                (consume-fn {:type :start})))

            (register-tool-index! [event item]
              (let [output-idx (:output_index event)
                    item-id    (:id item)
                    idx        (cond
                                 (and (number? output-idx)
                                      (contains? @tool-by-output-index output-idx))
                                 (get @tool-by-output-index output-idx)

                                 (and (string? item-id)
                                      (contains? @tool-by-item-id item-id))
                                 (get @tool-by-item-id item-id)

                                 (number? output-idx)
                                 output-idx

                                 :else
                                 (let [i @next-tool-index]
                                   (swap! next-tool-index inc)
                                   i))]
                (when (number? output-idx)
                  (swap! tool-by-output-index assoc output-idx idx))
                (when (string? item-id)
                  (swap! tool-by-item-id assoc item-id idx))
                idx))

            (resolve-tool-index [event]
              (or
               (let [output-idx (:output_index event)]
                 (when (number? output-idx)
                   (or (get @tool-by-output-index output-idx)
                       output-idx)))
               (let [item-id (or (:item_id event)
                                 (get-in event [:item :id]))]
                 (when (string? item-id)
                   (get @tool-by-item-id item-id)))))

            (emit-done! [event]
              (when-not @done?
                (reset! done? true)
                ;; Close any still-open tool calls
                (doseq [idx @open-tool-indexes]
                  (consume-fn {:type :toolcall-end :content-index idx}))
                (reset! open-tool-indexes #{})
                (let [resp      (:response event)
                      status    (:status resp)
                      usage     (:usage resp)
                      usage-map (when usage
                                  (let [u (codex-usage->usage-map usage)]
                                    (assoc u :cost (models/calculate-cost model u))))]
                  (consume-fn (cond-> {:type :done
                                       :reason (codex-status->reason status)}
                                usage-map (assoc :usage usage-map))))))

            (emit-error!
              ([msg]
               (emit-error! msg nil))
              ([msg http-status]
               (when-not @done?
                 (reset! done? true)
                 (consume-fn (cond-> {:type :error :error-message msg}
                               http-status (assoc :http-status http-status))))))]

      (try
        (let [request  (build-codex-request conversation model options)
              response (http/post (resolve-codex-url (:base-url model))
                                  (merge request {:as :stream :cookie-policy :none}))]
          (with-open [reader (io/reader (:body response))]
            (doseq [line (line-seq reader)]
              (when-let [event (parse-sse-line line)]
                (let [etype (:type event)]
                  (case etype
                    "response.output_item.added"
                    (let [item      (:item event)
                          item-type (:type item)]
                      (case item-type
                        "message"
                        (emit-start!)

                        "reasoning"
                        (emit-start!)

                        "function_call"
                        (let [idx        (register-tool-index! event item)
                              call-id    (or (:call_id item)
                                             (str "call_" (UUID/randomUUID)))
                              item-id    (:id item)
                              tool-id    (if (seq item-id)
                                           (str call-id "|" item-id)
                                           call-id)
                              tool-name  (or (:name item) "tool")]
                          (emit-start!)
                          (swap! open-tool-indexes conj idx)
                          (consume-fn {:type          :toolcall-start
                                       :content-index idx
                                       :id            tool-id
                                       :name          tool-name})
                          (when-let [args (:arguments item)]
                            (when (seq args)
                              (consume-fn {:type          :toolcall-delta
                                           :content-index idx
                                           :delta         args}))))

                        nil))

                    "response.function_call_arguments.delta"
                    (let [idx   (resolve-tool-index event)
                          delta (:delta event)]
                      (when (and (number? idx) (seq delta))
                        (emit-start!)
                        (consume-fn {:type          :toolcall-delta
                                     :content-index idx
                                     :delta         delta})))

                    "response.output_item.done"
                    (let [item      (:item event)
                          item-type (:type item)]
                      (when (= "function_call" item-type)
                        (let [idx (or (resolve-tool-index event)
                                      (register-tool-index! event item))]
                          (when (number? idx)
                            (when (contains? @open-tool-indexes idx)
                              (swap! open-tool-indexes disj idx)
                              (consume-fn {:type :toolcall-end
                                           :content-index idx}))))))

                    "response.output_text.delta"
                    (when-let [delta (:delta event)]
                      (emit-start!)
                      (consume-fn {:type :text-delta
                                   :content-index 0
                                   :delta delta}))

                    "response.reasoning_summary_text.delta"
                    (when-let [delta (:delta event)]
                      (emit-start!)
                      (consume-fn {:type :thinking-delta
                                   :content-index 0
                                   :delta delta}))

                    "response.reasoning_text.delta"
                    (when-let [delta (:delta event)]
                      (emit-start!)
                      (consume-fn {:type :thinking-delta
                                   :content-index 0
                                   :delta delta}))

                    "response.reasoning_summary.delta"
                    (when-let [delta (:delta event)]
                      (emit-start!)
                      (consume-fn {:type :thinking-delta
                                   :content-index 0
                                   :delta delta}))

                    "response.reasoning.delta"
                    (when-let [delta (:delta event)]
                      (emit-start!)
                      (consume-fn {:type :thinking-delta
                                   :content-index 0
                                   :delta delta}))

                    "response.completed"
                    (do (emit-start!)
                        (emit-done! event))

                    "response.done"
                    (do (emit-start!)
                        (emit-done! event))

                    "response.failed"
                    (emit-error! (or (get-in event [:response :error :message])
                                     "Codex response failed"))

                    "error"
                    (emit-error! (or (:message event)
                                     (:error event)
                                     "Codex stream error"))

                    ;; ignore all other event types
                    nil)))))
          ;; If stream ended without terminal event, emit done once.
          (when-not @done?
            (emit-start!)
            (emit-done! {:response {:status "completed"}})))
        (catch Exception e
          (let [{:keys [error-message http-status]} (exception->error e)]
            (emit-error! error-message http-status)))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Provider implementation (dispatch by model :api)
;; ───────────────────────────────────────────────────────────────────────────

(defn stream-openai-dispatch
  [conversation model options consume-fn]
  (if (= :openai-codex-responses (:api model))
    (stream-openai-codex conversation model options consume-fn)
    (stream-openai conversation model options consume-fn)))

(def provider
  {:name   :openai
   :stream stream-openai-dispatch})
