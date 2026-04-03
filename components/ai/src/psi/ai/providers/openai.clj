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

(def ^:private thinking-level->effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(defn- reasoning-effort
  "Return provider reasoning effort string for MODEL/OPTIONS, or nil when disabled."
  [model options]
  (when (:supports-reasoning model)
    (get thinking-level->effort
         (:thinking-level options)
         "medium")))

(defn- normalize-part-type
  [part]
  (let [t (:type part)]
    (cond
      (keyword? t) (name t)
      (string? t) t
      :else nil)))

(defn- join-parts
  [parts]
  (when (seq parts)
    (str/join "" parts)))

(def ^:private reasoning-part-types
  #{"reasoning"
    "reasoning_text"
    "reasoning_content"
    "reasoning_summary"
    "summary"
    "summary_text"})

(defn- string-fragment
  "Normalize provider text fragments to a plain string.

   Handles string leaves as well as nested maps/vectors often used in
   OpenAI reasoning payloads. Returns nil when no textual content is found."
  [x]
  (letfn [(->text [v]
            (cond
              (nil? v) nil
              (string? v) v
              (number? v) (str v)
              (keyword? v) (name v)

              (sequential? v)
              (join-parts (keep ->text v))

              (map? v)
              (or (->text (:text v))
                  (->text (:content v))
                  (->text (:delta v))
                  (->text (:summary v))
                  (->text (:value v)))

              :else nil))]
    (->text x)))

(defn- extract-reasoning-delta
  [delta]
  ;; Multi-branch `or` intentionally normalises across observed provider response
  ;; shapes: flat keys, nested maps, sequential content blocks, and legacy fields.
  ;; Each branch handles a distinct variant seen in the wild — not dead code.
  (let [reasoning (:reasoning delta)]
    (or
     (string-fragment (get-in delta [:reasoning :content]))
     (string-fragment (get-in delta [:reasoning :summary]))
     (string-fragment (:reasoning_content delta))

     (when (string? reasoning)
       reasoning)

     (when (map? reasoning)
       (or (string-fragment (:content reasoning))
           (string-fragment (:summary reasoning))
           (string-fragment (:text reasoning))
           (string-fragment (:delta reasoning))))

     (when (sequential? reasoning)
       (join-parts
        (keep (fn [part]
                (when (map? part)
                  (let [ptype (normalize-part-type part)]
                    (when (or (contains? reasoning-part-types ptype)
                              (contains? part :reasoning)
                              (contains? part :summary))
                      (or (string-fragment (:text part))
                          (string-fragment (:content part))
                          (string-fragment (:delta part))
                          (string-fragment (:reasoning part))
                          (string-fragment (:summary part)))))))
              reasoning)))

     (when (sequential? (:content delta))
       (join-parts
        (keep (fn [part]
                (when (map? part)
                  (let [ptype (normalize-part-type part)]
                    (when (contains? reasoning-part-types ptype)
                      (or (string-fragment (:text part))
                          (string-fragment (:content part))
                          (string-fragment (:delta part))
                          (string-fragment (:summary part)))))))
              (:content delta)))))))

(defn- extract-text-delta
  [delta]
  (cond
    (string? (:content delta))
    (:content delta)

    (sequential? (:content delta))
    (join-parts
     (keep (fn [part]
             (when (map? part)
               (let [ptype (normalize-part-type part)]
                 (when (contains? #{"text" "output_text"} ptype)
                   (or (string-fragment (:text part))
                       (string-fragment (:content part)))))))
           (:content delta)))

    :else nil))

(defn- normalize-tool-arguments
  "Normalize streamed function arguments into a string payload."
  [args]
  (cond
    (nil? args) nil
    (string? args) args
    (map? args) (json/generate-string args)
    (sequential? args) (or (join-parts (keep string-fragment args))
                           (str args))
    :else (str args)))

(defn- accumulate-tool-arguments
  "Merge incoming tool-argument chunk into current buffer.

   Handles both streaming styles:
   - true deltas (append incoming)
   - cumulative snapshots (emit only unseen suffix)."
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

(defn- extract-tool-call-fragments
  "Extract tool-call fragments from a chat completions choice.

   Supports:
   - delta.tool_calls (current format)
   - delta.function_call (legacy format)
   - message.tool_calls (final fallback)
   - message.function_call (legacy final fallback)"
  [choice delta]
  (let [delta-tool-calls   (or (:tool_calls delta) [])
        delta-function     (:function_call delta)
        message-tool-calls (or (get-in choice [:message :tool_calls]) [])
        message-function   (get-in choice [:message :function_call])]
    (vec
     (concat
      delta-tool-calls

      (when (map? delta-function)
        [{:index 0
          :function delta-function}])

      message-tool-calls

      (when (map? message-function)
        [{:index 0
          :function message-function}])))))

(defn- user-message-text
  [msg]
  (let [content (:content msg)]
    (cond
      (string? content)
      content

      (and (map? content)
           (= :text (:kind content)))
      (or (:text content) (get content "text") "")

      (and (map? content)
           (= :structured (:kind content)))
      (->> (:blocks content)
           (keep #(when (= :text (:kind %)) (:text %)))
           (str/join "\n"))

      (map? content)
      (or (:text content) (get content "text") "")

      :else
      (str content))))

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
                       :content (user-message-text msg)})

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
   Includes tools from conversation when present.
   For reasoning-capable models, forwards `reasoning_effort` unless thinking is off."
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
        effort        (reasoning-effort model options)
        temperature   (or (:temperature options) 0)
        body          (cond-> {:model          (:id model)
                               :messages       (vec messages)
                               :stream         true
                               :stream_options {:include_usage true}
                               :temperature    temperature}
                        (:max-tokens options)  (assoc :max_tokens  (:max-tokens options))
                        (seq tool-defs)        (assoc :tools tool-defs)
                        effort                 (assoc :reasoning_effort effort))]
    {:headers {"Content-Type"  "application/json"
               "Authorization" (str "Bearer " (or (:api-key options)
                                                   (System/getenv "OPENAI_API_KEY")))}
     :body    (json/generate-string body)}))

(defn- safe-call!
  [f payload]
  (when (fn? f)
    (try
      (f payload)
      (catch Exception _
        nil))))

(defn- redact-authorization
  [value]
  (when (string? value)
    (str "Bearer ***REDACTED***"
         (when (> (count value) 20)
           (str " (len=" (count value) ")")))))

(defn- mask-chatgpt-account-id
  [value]
  (when (string? value)
    (str (subs value 0 (min 6 (count value))) "...")))

(defn- redact-request-headers
  [headers]
  (cond-> headers
    (contains? headers "Authorization")
    (assoc "Authorization"
           (redact-authorization (get headers "Authorization")))

    (contains? headers "chatgpt-account-id")
    (assoc "chatgpt-account-id"
           (mask-chatgpt-account-id (get headers "chatgpt-account-id")))))

(defn- parse-json-body-safe
  [body]
  (try
    (json/parse-string (str (or body "")) true)
    (catch Exception _
      (str (or body "")))))

(defn- capture-request!
  [options api url request]
  (safe-call! (:on-provider-request options)
              {:provider :openai
               :api api
               :url url
               :request {:headers (redact-request-headers (:headers request))
                         :body (parse-json-body-safe (:body request))}}))

(defn- capture-response!
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

(defn- new-msg-id  [] (str "msg_"  (UUID/randomUUID)))
(defn- new-call-id [] (str "call_" (UUID/randomUUID)))

(defn- completions-usage-map
  [model usage]
  (let [usage-map {:input-tokens       (or (:prompt_tokens usage) 0)
                   :output-tokens      (or (:completion_tokens usage) 0)
                   :cache-read-tokens  0
                   :cache-write-tokens 0
                   :total-tokens       (or (:total_tokens usage)
                                           (+ (or (:prompt_tokens usage) 0)
                                              (or (:completion_tokens usage) 0)))}]
    (assoc usage-map :cost (models/calculate-cost model usage-map))))

(defn- emit-chat-completion-finish!
  [consume-fn stream-started? done? reason usage]
  (when-not @done?
    (reset! done? true)
    (when (compare-and-set! stream-started? false true)
      (consume-fn {:type :start}))
    (consume-fn (cond-> {:type :done
                         :reason reason}
                  usage (assoc :usage usage)))))

(defn- update-tool-index! [tool-index-by-id next-tool-index call-id idx]
  (when (seq call-id)
    (swap! tool-index-by-id assoc call-id idx))
  (swap! next-tool-index #(max % (inc idx)))
  idx)

(defn- make-chat-stream-state
  []
  {:stream-started?  (atom false)
   :done?            (atom false)
   :next-tool-index  (atom 0)
   :tool-index-by-id (atom {})
   :tool-state       (atom {})})

(defn- emit-stream-start!
  [consume-fn stream-started?]
  (when (compare-and-set! stream-started? false true)
    (consume-fn {:type :start})))

(defn- emit-started-event!
  [consume-fn stream-started? event]
  (emit-stream-start! consume-fn stream-started?)
  (consume-fn event))

(defn- resolve-chat-tool-index
  [{:keys [tool-index-by-id next-tool-index]} tool-call fallback-idx]
  (let [idx     (:index tool-call)
        call-id (:id tool-call)]
    (cond
      (number? idx)
      (update-tool-index! tool-index-by-id next-tool-index call-id idx)

      (and (seq call-id)
           (contains? @tool-index-by-id call-id))
      (get @tool-index-by-id call-id)

      :else
      (update-tool-index! tool-index-by-id next-tool-index call-id
                          (or fallback-idx @next-tool-index)))))

(defn- ensure-chat-tool-entry!
  [{:keys [tool-state]} idx]
  (swap! tool-state update idx
         (fn [s]
           (merge {:id nil
                   :name nil
                   :started? false
                   :args-buffer ""}
                  s))))

(defn- start-chat-tool-if-ready!
  [{:keys [tool-state stream-started?]} consume-fn idx force?]
  (let [{:keys [id name started? args-buffer]} (get @tool-state idx)
        id* (or id (when force? (new-call-id)))]
    (when (and (not started?) (seq name) (seq id*))
      (swap! tool-state assoc idx
             {:id id*
              :name name
              :started? true
              :args-buffer (or args-buffer "")})
      (emit-started-event! consume-fn stream-started?
                           {:type :toolcall-start
                            :content-index idx
                            :id id*
                            :name name})
      (when (seq args-buffer)
        (consume-fn {:type :toolcall-delta
                     :content-index idx
                     :delta args-buffer})))))

(defn- process-chat-tool-call!
  [stream-state consume-fn idx tool-call]
  (let [{:keys [tool-state tool-index-by-id stream-started?]} stream-state
        call-id   (:id tool-call)
        call-name (get-in tool-call [:function :name])
        args      (normalize-tool-arguments
                   (get-in tool-call [:function :arguments]))]
    (ensure-chat-tool-entry! stream-state idx)
    (when (seq call-id)
      (swap! tool-state assoc-in [idx :id] call-id)
      (swap! tool-index-by-id assoc call-id idx))
    (when (seq call-name)
      (swap! tool-state assoc-in [idx :name] call-name))
    (start-chat-tool-if-ready! stream-state consume-fn idx false)
    (when (seq args)
      (let [current-buffer (get-in @tool-state [idx :args-buffer] "")
            {:keys [buffer delta]} (accumulate-tool-arguments current-buffer args)]
        (swap! tool-state assoc-in [idx :args-buffer] buffer)
        (when (and (get-in @tool-state [idx :started?])
                   (seq delta))
          (emit-started-event! consume-fn stream-started?
                               {:type :toolcall-delta
                                :content-index idx
                                :delta delta}))))
    (start-chat-tool-if-ready! stream-state consume-fn idx false)))

(defn- force-start-pending-chat-tools!
  [stream-state consume-fn]
  (doseq [idx (sort (keys @(-> stream-state :tool-state)))]
    (start-chat-tool-if-ready! stream-state consume-fn idx true)))

(defn- emit-chat-tool-ends!
  [{:keys [tool-state]} consume-fn]
  (doseq [[idx {:keys [started?]}] (sort-by key @tool-state)]
    (when started?
      (consume-fn {:type :toolcall-end
                   :content-index idx})))
  (reset! tool-state {}))

(defn- emit-chat-chunk!
  [stream-state consume-fn choice delta]
  (let [{:keys [stream-started?]} stream-state
        text-delta      (extract-text-delta delta)
        reasoning-delta (extract-reasoning-delta delta)]
    (when (and choice (= (:role delta) "assistant"))
      (emit-stream-start! consume-fn stream-started?))
    (when (seq text-delta)
      (emit-started-event! consume-fn stream-started?
                           {:type :text-delta
                            :content-index 0
                            :delta text-delta}))
    (when (seq reasoning-delta)
      (emit-started-event! consume-fn stream-started?
                           {:type :thinking-delta
                            :content-index 0
                            :delta reasoning-delta}))
    (doseq [[fallback-idx tool-call]
            (map-indexed vector (extract-tool-call-fragments choice delta))]
      (process-chat-tool-call! stream-state consume-fn
                               (resolve-chat-tool-index stream-state tool-call fallback-idx)
                               tool-call))))

(defn- finish-chat-chunk!
  [stream-state consume-fn model chunk choice]
  (let [{:keys [stream-started? done?]} stream-state]
    (cond
      (:usage chunk)
      (do
        (force-start-pending-chat-tools! stream-state consume-fn)
        (emit-chat-tool-ends! stream-state consume-fn)
        (emit-chat-completion-finish! consume-fn
                                      stream-started?
                                      done?
                                      (keyword (get-in choice [:finish_reason] "stop"))
                                      (completions-usage-map model (:usage chunk))))

      (:finish_reason choice)
      (do
        (force-start-pending-chat-tools! stream-state consume-fn)
        (emit-chat-tool-ends! stream-state consume-fn)
        (emit-chat-completion-finish! consume-fn
                                      stream-started?
                                      done?
                                      (keyword (:finish_reason choice))
                                      nil)))))

(defn- process-chat-sse-line!
  [stream-state consume-fn model options url line]
  (when-let [chunk (parse-sse-line line)]
    (capture-response! options :openai-completions url chunk)
    (let [choice (first (:choices chunk))
          delta  (:delta choice)]
      (emit-chat-chunk! stream-state consume-fn choice delta)
      (finish-chat-chunk! stream-state consume-fn model chunk choice))))

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
  (let [url          (str (:base-url model) "/chat/completions")
        request      (build-request conversation model options)
        stream-state (make-chat-stream-state)]
    (try
      (capture-request! options :openai-completions url request)
      (let [response (http/post url
                                (merge request {:as :stream :cookie-policy :none}))]
        (with-open [reader (io/reader (:body response))]
          (doseq [line (line-seq reader)]
            (process-chat-sse-line! stream-state consume-fn model options url line))))
      (catch Exception e
        (let [err (exception->error e)]
          (capture-response! options :openai-completions url err)
          (consume-fn err))))))

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
                           "id"      (new-msg-id)
                           "content" [{"type" "output_text"
                                       "text" text
                                       "annotations" []}]})
            tool-items  (map (fn [tc]
                               (let [raw-id          (or (:id tc) (new-call-id))
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
            "id"      (new-msg-id)
            "content" [{"type" "output_text"
                        "text" text
                        "annotations" []}]}]
          [])))))

(defn- tool-result->codex-item
  [msg]
  (let [raw-id  (or (:tool-call-id msg) "")
        call-id (or (first (str/split raw-id #"\|" 2))
                    (new-call-id))
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
                                 "text" (user-message-text msg)}]}]

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
     :cache-write-tokens 0  ;; Codex API does not expose cache write tokens
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
    (let [tools     (codex-tools conversation)
          reasoning (codex-reasoning model options)
          headers   (cond-> {"Content-Type"       "application/json"
                             "Authorization"      (str "Bearer " api-key)
                             "accept"             "text/event-stream"
                             "OpenAI-Beta"        codex-beta-header
                             "originator"         "psi"
                             "chatgpt-account-id" account-id}
                      (:session-id options)
                      (assoc "session_id"      (:session-id options)
                             "conversation_id" (:session-id options)))
          ;; ChatGPT Codex backend currently rejects top-level `temperature`
          ;; with 400 unsupported parameter, so we intentionally omit it.
          body      (cond-> {"model"               (:id model)
                             "store"               false
                             "stream"              true
                             "instructions"        (:system-prompt conversation)
                             "input"               (codex-input-messages conversation)
                             "text"                {"verbosity" "medium"}
                             "tool_choice"         "auto"
                             "parallel_tool_calls" true}
                      (:session-id options) (assoc "prompt_cache_key" (:session-id options))
                      (seq tools)           (assoc "tools" tools)
                      reasoning             (assoc "reasoning" reasoning))]
      {:headers headers
       :body    (json/generate-string body)})))

(defn- make-codex-stream-state
  []
  {:started?             (atom false)
   :done?                (atom false)
   :next-tool-index      (atom 0)
   :tool-by-item-id      (atom {})
   :tool-by-output-index (atom {})
   :tool-args-by-index   (atom {})
   :open-tool-indexes    (atom #{})})

(defn- emit-codex-start!
  [consume-fn started?]
  (when (compare-and-set! started? false true)
    (consume-fn {:type :start})))

(defn- emit-codex-started-event!
  [consume-fn started? event]
  (emit-codex-start! consume-fn started?)
  (consume-fn event))

(defn- register-codex-tool-index!
  [{:keys [next-tool-index tool-by-item-id tool-by-output-index]} event item]
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

(defn- resolve-codex-tool-index
  [{:keys [tool-by-output-index tool-by-item-id]} event]
  (or
   (let [output-idx (:output_index event)]
     (when (number? output-idx)
       (or (get @tool-by-output-index output-idx)
           output-idx)))
   (let [item-id (or (:item_id event)
                     (get-in event [:item :id]))]
     (when (string? item-id)
       (get @tool-by-item-id item-id)))))

(defn- emit-codex-tool-delta!
  [{:keys [tool-args-by-index]} consume-fn idx args]
  (when (and (number? idx) (seq args))
    (swap! tool-args-by-index update idx (fnil str "") args)
    (consume-fn {:type          :toolcall-delta
                 :content-index idx
                 :delta         args})))

(defn- emit-codex-done!
  [{:keys [done? open-tool-indexes tool-args-by-index]} consume-fn model event]
  (when-not @done?
    (reset! done? true)
    (doseq [idx @open-tool-indexes]
      (consume-fn {:type :toolcall-end :content-index idx}))
    (reset! open-tool-indexes #{})
    (reset! tool-args-by-index {})
    (let [resp      (:response event)
          status    (:status resp)
          usage     (:usage resp)
          usage-map (when usage
                      (let [u (codex-usage->usage-map usage)]
                        (assoc u :cost (models/calculate-cost model u))))]
      (consume-fn (cond-> {:type :done
                           :reason (codex-status->reason status)}
                    usage-map (assoc :usage usage-map))))))

(defn- emit-codex-error!
  [{:keys [done?]} consume-fn options url msg http-status]
  (when-not @done?
    (reset! done? true)
    (let [err (cond-> {:type :error :error-message msg}
                http-status (assoc :http-status http-status))]
      (capture-response! options :openai-codex-responses url err)
      (consume-fn err))))

(defn- emit-codex-thinking-boundary!
  [stream-state consume-fn]
  (emit-codex-started-event! consume-fn (:started? stream-state)
                             {:type :thinking-start :content-index 0})
  (consume-fn {:type :thinking-end :content-index 0}))

(defn- emit-codex-thinking-delta!
  [stream-state consume-fn event]
  (when-let [delta (string-fragment (:delta event))]
    (emit-codex-started-event! consume-fn (:started? stream-state)
                               {:type :thinking-delta
                                :content-index 0
                                :delta delta})))

(def ^:private codex-thinking-delta-event-types
  #{"response.reasoning_summary_text.delta"
    "response.reasoning_text.delta"
    "response.reasoning_summary.delta"
    "response.reasoning.delta"})

(def ^:private codex-done-event-types
  #{"response.completed"
    "response.done"})

(defn- finish-codex-tool-call!
  [stream-state consume-fn event item]
  (let [{:keys [tool-args-by-index open-tool-indexes]} stream-state
        idx (or (resolve-codex-tool-index stream-state event)
                (register-codex-tool-index! stream-state event item))]
    (when (number? idx)
      (let [final-args (:arguments item)
            seen       (get @tool-args-by-index idx "")]
        (when (seq final-args)
          (cond
            (and (seq seen)
                 (str/starts-with? final-args seen))
            (let [remaining (subs final-args (count seen))]
              (when (seq remaining)
                (emit-codex-tool-delta! stream-state consume-fn idx remaining)))

            (not= final-args seen)
            (emit-codex-tool-delta! stream-state consume-fn idx final-args)))
        (swap! tool-args-by-index dissoc idx))
      (when (contains? @open-tool-indexes idx)
        (swap! open-tool-indexes disj idx)
        (consume-fn {:type :toolcall-end
                     :content-index idx})))))

(defn- handle-codex-output-item-added!
  [stream-state consume-fn event]
  (let [{:keys [started? open-tool-indexes]} stream-state
        item      (:item event)
        item-type (:type item)]
    (case item-type
      "message"
      (emit-codex-start! consume-fn started?)

      "reasoning"
      (emit-codex-start! consume-fn started?)

      "function_call"
      (let [idx       (register-codex-tool-index! stream-state event item)
            call-id   (or (:call_id item) (new-call-id))
            item-id   (:id item)
            tool-id   (if (seq item-id) (str call-id "|" item-id) call-id)
            tool-name (or (:name item) "tool")]
        (emit-codex-started-event! consume-fn started?
                                   {:type          :toolcall-start
                                    :content-index idx
                                    :id            tool-id
                                    :name          tool-name})
        (swap! open-tool-indexes conj idx)
        (when-let [args (:arguments item)]
          (emit-codex-tool-delta! stream-state consume-fn idx args)))

      nil)))

(defn- handle-codex-output-item-done!
  [stream-state consume-fn event]
  (let [item      (:item event)
        item-type (:type item)]
    (case item-type
      "function_call" (finish-codex-tool-call! stream-state consume-fn event item)
      "reasoning" (emit-codex-thinking-boundary! stream-state consume-fn)
      nil)))

(defn- handle-codex-event!
  [stream-state consume-fn model options url event]
  (capture-response! options :openai-codex-responses url event)
  (let [event-type (:type event)]
    (cond
      (= "response.output_item.added" event-type)
      (handle-codex-output-item-added! stream-state consume-fn event)

      (= "response.function_call_arguments.delta" event-type)
      (let [idx   (resolve-codex-tool-index stream-state event)
            delta (:delta event)]
        (when (and (number? idx) (seq delta))
          (emit-codex-start! consume-fn (:started? stream-state))
          (emit-codex-tool-delta! stream-state consume-fn idx delta)))

      (= "response.output_item.done" event-type)
      (handle-codex-output-item-done! stream-state consume-fn event)

      (= "response.output_text.delta" event-type)
      (when-let [delta (string-fragment (:delta event))]
        (emit-codex-started-event! consume-fn (:started? stream-state)
                                   {:type :text-delta
                                    :content-index 0
                                    :delta delta}))

      (contains? codex-thinking-delta-event-types event-type)
      (emit-codex-thinking-delta! stream-state consume-fn event)

      (contains? codex-done-event-types event-type)
      (do
        (emit-codex-start! consume-fn (:started? stream-state))
        (emit-codex-done! stream-state consume-fn model event))

      (= "response.failed" event-type)
      (emit-codex-error! stream-state consume-fn options url
                         (or (get-in event [:response :error :message])
                             "Codex response failed")
                         nil)

      (= "error" event-type)
      (emit-codex-error! stream-state consume-fn options url
                         (or (:message event)
                             (:error event)
                             "Codex stream error")
                         nil)

      :else nil)))

(defn stream-openai-codex
  "Stream response from OpenAI Codex Responses API (ChatGPT backend).

   Endpoint: <base-url>/codex/responses
   Event source: SSE JSON events with `type` fields (response.*).

   Emits normalized events expected by the executor:
     :start, :text-delta, :thinking-delta,
     :toolcall-start, :toolcall-delta, :toolcall-end,
     :done, :error"
  [conversation model options consume-fn]
  (let [url          (resolve-codex-url (:base-url model))
        stream-state (make-codex-stream-state)]
    (try
      (let [request  (build-codex-request conversation model options)
            _        (capture-request! options :openai-codex-responses url request)
            response (http/post url
                                (merge request {:as :stream :cookie-policy :none}))]
        (with-open [reader (io/reader (:body response))]
          (doseq [line (line-seq reader)]
            (when-let [event (parse-sse-line line)]
              (handle-codex-event! stream-state consume-fn model options url event))))
        (when-not @(-> stream-state :done?)
          (emit-codex-start! consume-fn (-> stream-state :started?))
          (emit-codex-done! stream-state consume-fn model {:response {:status "completed"}})))
      (catch Exception e
        (let [{:keys [error-message http-status]} (exception->error e)]
          (emit-codex-error! stream-state consume-fn options url error-message http-status))))))

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
