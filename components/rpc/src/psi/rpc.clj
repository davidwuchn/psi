(ns psi.rpc
  "EDN-lines RPC transport runtime helpers.

   Transport guarantees in this namespace:
   - one top-level EDN map per input line
   - canonical outbound frame envelopes
   - serialized outbound frame writing
   - protocol-only stdout (handler diagnostics are rebound to stderr)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.message-text :as message-text]
   [psi.ai.models :as ai-models]))

(def protocol-version "1.0")

(defn- start-daemon-thread!
  "Start a daemon thread running f with an optional name. Returns the Thread."
  ([f] (start-daemon-thread! f nil))
  ([f thread-name]
   (doto (Thread. ^Runnable f)
     (.setDaemon true)
     (cond-> thread-name (.setName thread-name))
     (.start))))

(defn- stop-managed-thread!
  [state k]
  (when-let [x (get @state k)]
    (cond
      (instance? java.util.concurrent.Future x)
      (try (future-cancel x) (catch Throwable _ nil))

      (instance? Thread x)
      (try (.interrupt ^Thread x) (catch Throwable _ nil))

      :else nil)
    (swap! state dissoc k)
    true))

(defn- stop-all-managed-threads!
  [state]
  ;; Stop long-lived transport-owned loops. Do not interrupt in-flight prompt
  ;; workers here: existing RPC contract/tests expect accepted prompt work to
  ;; continue emitting events briefly after input EOF in harness scenarios.
  (doseq [k [:ui-watch-loop :external-event-loop]]
    (stop-managed-thread! state k)))
(def ^:private default-max-pending-requests 64)

(def ^:private request-required-keys #{:id :kind :op})
(def ^:private request-allowed-keys  #{:id :kind :op :params})

(def ^:private response-allowed-keys [:id :kind :op :ok :data])
(def ^:private error-allowed-keys    [:kind :id :op :error-code :error-message :retryable :data])
(def ^:private event-allowed-keys    [:kind :event :data :id :seq :ts])

(def ^:private supported-rpc-ops
  ["handshake"
   "ping"
   "query_eql"
   "command"
   "frontend_action_result"
   "prompt"
   "prompt_while_streaming"
   "steer"
   "follow_up"
   "abort"
   "interrupt"
   "list_background_jobs"
   "inspect_background_job"
   "cancel_background_job"
   "login_begin"
   "login_complete"
   "new_session"
   "switch_session"
   "list_sessions"
   "fork"
   "set_session_name"
   "set_model"
   "cycle_model"
   "set_thinking_level"
   "cycle_thinking_level"
   "compact"
   "set_auto_compaction"
   "set_auto_retry"
   "get_state"
   "get_messages"
   "get_session_stats"
   "subscribe"
   "unsubscribe"
   "resolve_dialog"
   "cancel_dialog"])

(def ^:private targetable-rpc-ops
  #{"query_eql"
    "command"
    "frontend_action_result"
    "prompt"
    "prompt_while_streaming"
    "steer"
    "follow_up"
    "abort"
    "interrupt"
    "list_background_jobs"
    "inspect_background_job"
    "cancel_background_job"
    "fork"
    "set_session_name"
    "set_model"
    "cycle_model"
    "set_thinking_level"
    "cycle_thinking_level"
    "compact"
    "set_auto_compaction"
    "set_auto_retry"
    "get_state"
    "get_messages"
    "get_session_stats"})

(declare emit-event! assistant-content-text focused-session-id)

(def ^:dynamic *request-session-id* nil)

(defn- default-session-id-in
  [ctx]
  (some-> (ss/list-context-sessions-in ctx) first :session-id))

(defn response-frame
  ([id op ok]
   (response-frame id op ok nil))
  ([id op ok data]
   (cond-> (array-map :id id :kind :response :op op :ok ok)
     (some? data) (assoc :data data))))

(defn error-frame
  [{:keys [id op error-code error-message retryable data]}]
  (cond-> (array-map :kind :error
                     :error-code error-code
                     :error-message error-message)
    (some? id)        (assoc :id id)
    (some? op)        (assoc :op op)
    (some? retryable) (assoc :retryable retryable)
    (some? data)      (assoc :data data)))

(defn- normalize-ts [ts]
  (cond
    (nil? ts) nil
    (string? ts) ts
    :else (str ts)))

(defn event-frame
  [{:keys [event data id seq ts]}]
  (let [ts* (normalize-ts ts)]
    (cond-> (array-map :kind :event :event event :data data)
      (some? id)  (assoc :id id)
      (some? seq) (assoc :seq seq)
      (some? ts*) (assoc :ts ts*))))

(defn- normalize-kind [k]
  (cond
    (keyword? k) k
    (string? k)  (keyword k)
    :else        k))

(defn- canonicalize-outbound-frame [frame]
  (case (normalize-kind (:kind frame))
    :response (select-keys frame response-allowed-keys)
    :error    (select-keys frame error-allowed-keys)
    :event    (select-keys frame event-allowed-keys)
    (error-frame {:id            (:id frame)
                  :op            (:op frame)
                  :error-code    "protocol/invalid-envelope"
                  :error-message "unsupported outbound frame kind"
                  :data          {:kind (:kind frame)}})))

(defn- edn-wire-safe
  "Recursively coerce values to EDN-safe transport shapes.

   Notably converts java.time values to strings because `pr-str` renders
   java.time.Instant as `#object[...]`, which is not EDN-readable."
  [x]
  (cond
    (or (nil? x)
        (string? x)
        (number? x)
        (keyword? x)
        (symbol? x)
        (boolean? x)
        (char? x))
    x

    (instance? java.time.temporal.TemporalAccessor x)
    (str x)

    (map? x)
    (into (empty x)
          (map (fn [[k v]] [k (edn-wire-safe v)]))
          x)

    (vector? x)
    (mapv edn-wire-safe x)

    (set? x)
    (set (map edn-wire-safe x))

    (sequential? x)
    (mapv edn-wire-safe x)

    :else
    (str x)))

(defn- safe-trace!
  [trace-fn payload]
  (when trace-fn
    (try
      (trace-fn payload)
      (catch Throwable _
        nil))))

(defn make-frame-writer
  "Return a serialized frame emitter writing one EDN map per line to `out-writer`.

   Optional `trace-fn` receives transport trace payloads:
   {:dir :out :raw <wire-line> :frame <canonical-frame>}"
  ([^java.io.Writer out-writer]
   (make-frame-writer out-writer nil))
  ([^java.io.Writer out-writer trace-fn]
   (let [lock   (Object.)
         writer (java.io.BufferedWriter. out-writer)]
     (fn emit-frame! [frame]
       (let [canonical (edn-wire-safe (canonicalize-outbound-frame frame))
             line      (pr-str canonical)]
         (locking lock
           (.write writer (str line "\n"))
           (.flush writer))
         (safe-trace! trace-fn {:dir :out
                                :raw line
                                :frame canonical}))))))

(defn- invalid-envelope [frame-id frame-op message]
  (error-frame {:id            frame-id
                :op            frame-op
                :error-code    "protocol/invalid-envelope"
                :error-message message}))

(defn- parse-request-line [line]
  (try
    (let [frame (edn/read-string line)]
      (cond
        (not (map? frame))
        {:error (invalid-envelope nil nil "request frame must be an EDN map")}

        (not= :request (normalize-kind (:kind frame)))
        {:error (invalid-envelope (:id frame) (:op frame) "request frame :kind must be :request")}

        (not (every? #(contains? frame %) request-required-keys))
        {:error (invalid-envelope (:id frame) (:op frame) "request frame missing required keys")}

        (not (every? request-allowed-keys (keys frame)))
        {:error (invalid-envelope (:id frame) (:op frame) "request frame contains unsupported keys")}

        :else
        {:ok frame}))
    (catch Throwable _
      {:error (error-frame {:error-code    "transport/invalid-frame"
                            :error-message "unable to parse EDN request frame"})})))

(defn default-request-handler
  "Default request handler used before per-op routing is implemented."
  [request]
  (let [op (:op request)]
    (case op
      "ping"
      (response-frame (:id request) "ping" true {:pong true :protocol-version protocol-version})

      (error-frame {:id            (:id request)
                    :op            (:op request)
                    :error-code    "request/op-not-supported"
                    :error-message (str "unsupported op: " op)
                    :data          {:supported-ops supported-rpc-ops}}))))

(defn- valid-request-id? [id]
  (and (string? id) (not (str/blank? id))))

(defn- valid-request-op? [op]
  (and (string? op) (not (str/blank? op))))

(defn- terminal-frame? [frame]
  (contains? #{:response :error} (normalize-kind (:kind frame))))

(defn- clear-pending-if-terminal! [state frame]
  (when-let [id (:id frame)]
    (when (and (string? id) (terminal-frame? frame))
      (swap! state update :pending dissoc id))))

(defn- make-tracked-emitter [emit-frame! state]
  (fn emit-tracked! [frame]
    (emit-frame! frame)
    (clear-pending-if-terminal! state frame)))

(defn- request-error
  [request error-code error-message]
  (error-frame {:id            (:id request)
                :op            (:op request)
                :error-code    error-code
                :error-message error-message}))

(defn- protocol-major [version]
  (when (string? version)
    (some->> (re-find #"^(\d+)(?:\..*)?$" version)
             second
             Integer/parseInt)))

(defn- handle-handshake! [request emit-frame! state]
  (let [version (or (get-in request [:params :client-info :protocol-version])
                    (get-in request [:params :protocol-version]))
        major   (protocol-major version)]
    (cond
      (not (string? version))
      (request-error request
                     "request/invalid-params"
                     "handshake requires :params :client-info :protocol-version string")

      (not= 1 major)
      (request-error request
                     "protocol/unsupported-version"
                     (str "unsupported protocol major: " (or major "unknown")))

      :else
      (let [server-info-fn (or (:handshake-server-info-fn @state)
                               (fn [] {:protocol-version protocol-version}))
            context-payload-fn (:handshake-context-updated-payload-fn @state)
            server-info       (merge {:protocol-version protocol-version}
                                     (or (server-info-fn) {}))]
        (swap! state assoc
               :ready? true
               :negotiated-protocol-version protocol-version)
        ;; Optional bootstrap context snapshot event for frontends that need
        ;; immediate session-tree state before subscribe lifecycle completes.
        (when (fn? context-payload-fn)
          (emit-event! emit-frame! state {:event "context/updated"
                                          :id (:id request)
                                          :data (or (context-payload-fn)
                                                    {:active-session-id nil
                                                     :sessions []})}))
        (response-frame (:id request)
                        "handshake"
                        true
                        {:server-info server-info})))))

(defn- accept-request!
  "Register a request as pending when valid and capacity allows.
   Returns {:ok true} or {:error <canonical-error-frame>}"
  [request state]
  (let [id      (:id request)
        op      (:op request)
        pending (:pending @state)
        max-p   (long (or (:max-pending-requests @state)
                          default-max-pending-requests))]
    (cond
      (not (valid-request-id? id))
      {:error (request-error request "request/invalid-id" "request :id must be a non-empty string")}

      (not (valid-request-op? op))
      {:error (request-error request "request/invalid-op" "request :op must be a non-empty string")}

      (contains? pending id)
      {:error (request-error request "request/invalid-id" "duplicate request :id")}

      (>= (count pending) max-p)
      {:error (request-error request "transport/max-pending-exceeded" "maximum pending requests exceeded")}

      :else
      (do
        (swap! state update :pending assoc id op)
        {:ok true}))))

(defn- process-request!
  [request {:keys [state request-handler emit-tracked!]}]
  (let [op (:op request)
        ready? (:ready? @state)]
    (cond
      (and (not ready?) (not= "handshake" op))
      (emit-tracked! (request-error request
                                    "transport/not-ready"
                                    "handshake must complete before non-handshake requests"))

      (= "handshake" op)
      (if-let [error (:error (accept-request! request state))]
        (emit-tracked! error)
        (emit-tracked! (handle-handshake! request emit-tracked! state)))

      :else
      (if-let [error (:error (accept-request! request state))]
        (emit-tracked! error)
        (try
          (let [result (binding [*out* (:err @state)]
                         (request-handler request emit-tracked! state))]
            (cond
              (nil? result)
              nil

              (map? result)
              (emit-tracked! result)

              (sequential? result)
              (doseq [frame result]
                (emit-tracked! frame))

              :else
              (emit-tracked! (error-frame {:id            (:id request)
                                           :op            (:op request)
                                           :error-code    "runtime/failed"
                                           :error-message "request handler returned unsupported result type"}))))
          (catch Throwable t
            (emit-tracked! (error-frame {:id            (:id request)
                                         :op            (:op request)
                                         :error-code    "runtime/failed"
                                         :error-message (or (ex-message t)
                                                            "unhandled runtime exception")}))))))))

(defn- params-map
  [request]
  (let [params (:params request)]
    (when-not (or (nil? params) (map? params))
      (throw (ex-info "request :params must be a map"
                      {:error-code "request/invalid-params"})))
    (or params {})))

(defn- req-arg!
  [_request params k pred desc]
  (let [v (get params k)]
    (when-not (pred v)
      (throw (ex-info (str "invalid request parameter " k ": " desc)
                      {:error-code "request/invalid-params"})))
    v))

(defn- parse-query-edn!
  [query-str]
  (let [q (try
            (binding [*read-eval* false]
              (edn/read-string query-str))
            (catch Throwable _
              (throw (ex-info "query must be valid EDN"
                              {:error-code "request/invalid-query"}))))]
    (when-not (vector? q)
      (throw (ex-info "query must be an EDN vector"
                      {:error-code "request/invalid-query"})))
    q))

(defn- dialog-result-valid?
  [v]
  (or (nil? v) (boolean? v) (string? v)))

(def ^:private ui-state-path (ss/state-path :ui-state))

(defn- ui-state-map
  [ctx]
  (ss/get-state-value-in ctx ui-state-path))

(defn- active-ui-dialog
  [ctx]
  (get-in (ui-state-map ctx) [:dialog-queue :active]))

(defn- visible-notifications
  ([ui-state] (visible-notifications ui-state 3))
  ([ui-state max-visible]
   (->> (:notifications ui-state)
        (remove :dismissed?)
        (take-last max-visible)
        vec)))

(defn- ui-snapshot
  [ctx]
  (when-let [s (ui-state-map ctx)]
    {:dialog-queue-empty?    (and (nil? (get-in s [:dialog-queue :active]))
                                  (empty? (get-in s [:dialog-queue :pending])))
     :active-dialog          (when-let [d (get-in s [:dialog-queue :active])]
                               (dissoc d :promise))
     :pending-dialog-count   (count (get-in s [:dialog-queue :pending]))
     :widgets                (vec (vals (:widgets s)))
     :statuses               (vec (vals (:statuses s)))
     :visible-notifications  (visible-notifications s)
     :tool-renderers         (mapv #(dissoc % :render-call-fn :render-result-fn)
                                   (vals (:tool-renderers s)))
     :message-renderers      (mapv #(dissoc % :render-fn)
                                   (vals (:message-renderers s)))}))

(defn- resolve-active-dialog!
  [ctx dialog-id result]
  (let [session-id (or *request-session-id* (default-session-id-in ctx))]
    (sa/resolve-active-dialog-in! ctx session-id dialog-id result)))

(defn- cancel-active-dialog!
  [ctx]
  (let [session-id (or *request-session-id* (default-session-id-in ctx))]
    (sa/cancel-active-dialog-in! ctx session-id)))

(defn- active-dialog-or-error!
  [ctx]
  (let [active (active-ui-dialog ctx)]
    (when-not (map? active)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    active))

(defn- handle-resolve-dialog!
  [ctx request params]
  (let [dialog-id (req-arg! request params :dialog-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        result    (get params :result ::missing)
        _         (when (= ::missing result)
                    (throw (ex-info "invalid request parameter :result: boolean, string, or nil"
                                    {:error-code "request/invalid-params"})))
        _         (when-not (dialog-result-valid? result)
                    (throw (ex-info "invalid request parameter :result: boolean, string, or nil"
                                    {:error-code "request/invalid-params"})))
        active    (active-dialog-or-error! ctx)
        active-id (:id active)]
    (when-not (= dialog-id active-id)
      (throw (ex-info "dialog-id mismatch"
                      {:error-code "request/dialog-id-mismatch"})))
    (when-not (resolve-active-dialog! ctx dialog-id result)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    (response-frame (:id request) "resolve_dialog" true {:accepted true})))

(defn- handle-cancel-dialog!
  [ctx request params]
  (let [dialog-id (req-arg! request params :dialog-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        active    (active-dialog-or-error! ctx)
        active-id (:id active)]
    (when-not (= dialog-id active-id)
      (throw (ex-info "dialog-id mismatch"
                      {:error-code "request/dialog-id-mismatch"})))
    (when-not (cancel-active-dialog! ctx)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    (response-frame (:id request) "cancel_dialog" true {:accepted true})))

(defn- request-thread-id
  [ctx params]
  (let [target-id (:session-id params)
        sid       (or target-id *request-session-id* (default-session-id-in ctx))
        sd        (ss/get-session-data-in ctx sid)]
    (:session-id sd)))

(defn- normalize-statuses-param
  [statuses]
  (cond
    (nil? statuses) nil
    (sequential? statuses) (mapv (fn [s]
                                   (cond
                                     (keyword? s) s
                                     (string? s) (keyword s)
                                     :else s))
                                 statuses)
    :else ::invalid))

(defn- background-job->rpc-view
  [job]
  (-> job
      (select-keys [:job-id
                    :thread-id
                    :tool-call-id
                    :tool-name
                    :job-kind
                    :workflow-ext-path
                    :workflow-id
                    :job-seq
                    :started-at
                    :completed-at
                    :completed-seq
                    :status
                    :terminal-payload
                    :terminal-payload-file
                    :cancel-requested-at
                    :terminal-message-emitted
                    :terminal-message-emitted-at])
      (update :started-at str)
      (update :completed-at #(when % (str %)))
      (update :cancel-requested-at #(when % (str %)))
      (update :terminal-message-emitted-at #(when % (str %)))))

(defn- handle-list-background-jobs!
  [ctx request params]
  (let [statuses* (normalize-statuses-param (:statuses params))]
    (when (= ::invalid statuses*)
      (throw (ex-info "invalid request parameter :statuses: sequential of keywords/strings"
                      {:error-code "request/invalid-params"})))
    (let [thread-id (request-thread-id ctx params)
          jobs      (if statuses*
                      (bg-rt/list-background-jobs-in! ctx thread-id statuses*)
                      (bg-rt/list-background-jobs-in! ctx thread-id))]
      (response-frame (:id request) "list_background_jobs" true
                      {:jobs (mapv background-job->rpc-view jobs)}))))

(defn- handle-inspect-background-job!
  [ctx request params]
  (let [job-id    (req-arg! request params :job-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        thread-id (request-thread-id ctx params)
        job       (bg-rt/inspect-background-job-in! ctx thread-id job-id)]
    (response-frame (:id request) "inspect_background_job" true
                    {:job (background-job->rpc-view job)})))

(defn- handle-cancel-background-job!
  [ctx request params]
  (let [job-id    (req-arg! request params :job-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        thread-id (request-thread-id ctx params)
        job       (bg-rt/cancel-background-job-in! ctx thread-id job-id :user)]
    (response-frame (:id request) "cancel_background_job" true
                    {:accepted true
                     :job (background-job->rpc-view job)})))

(defn- normalize-provider [provider]
  (cond
    (keyword? provider) provider
    (string? provider)  (keyword provider)
    :else               nil))

(defn resolve-model
  [provider model-id]
  (let [provider* (normalize-provider provider)]
    (some (fn [[_ model]]
            (when (and (= provider* (:provider model))
                       (= model-id (:id model)))
              model))
          ai-models/all-models)))

(defn- current-ai-model
  ([ctx state]
   (current-ai-model ctx state nil))
  ([ctx state session-id]
   (let [session-id* (or session-id
                         *request-session-id*
                         (default-session-id-in ctx))
         sd          (when session-id*
                       (ss/get-session-data-in ctx session-id*))]
     (or (when-let [provider (get-in sd [:model :provider])]
           (when-let [model-id (get-in sd [:model :id])]
             (resolve-model provider model-id)))
         (:rpc-ai-model @state)))))

(defn- normalize-provider-param
  [v]
  (cond
    (nil? v) nil
    (keyword? v) (name v)
    (string? v)  (let [trimmed (str/trim v)]
                   (when-not (str/blank? trimmed)
                     trimmed))
    :else        ::invalid))

(defn- pending-login-state
  [ctx]
  (:pending-login (or (sa/oauth-projection-in ctx) {})))

(defn- handle-login-begin!
  [ctx request params state]
  (let [oauth-ctx (:oauth-ctx ctx)]
    (when-not oauth-ctx
      (throw (ex-info "OAuth not available."
                      {:error-code "request/invalid-params"})))
    (when (pending-login-state ctx)
      (throw (ex-info "login already in progress"
                      {:error-code "request/invalid-params"})))
    (let [provider-param (normalize-provider-param (get params :provider))
          _              (when (= ::invalid provider-param)
                           (throw (ex-info "invalid request parameter :provider: string or keyword"
                                           {:error-code "request/invalid-params"})))
          ai-model       (current-ai-model ctx state)
          _              (when (and (nil? provider-param) (nil? ai-model))
                           (throw (ex-info "provider is required when session model is not configured"
                                           {:error-code "request/invalid-params"})))
          providers      (oauth/available-providers oauth-ctx)
          {:keys [provider error]}
          (commands/select-login-provider providers
                                          (or (:provider ai-model) provider-param)
                                          provider-param)]
      (when error
        (throw (ex-info error
                        {:error-code "request/invalid-params"})))
      (let [{:keys [url login-state]} (oauth/begin-login! oauth-ctx (:id provider))
            callback? (boolean (:uses-callback-server provider))]
        (sa/set-oauth-pending-login-in! ctx
                                        {:provider-id   (:id provider)
                                         :provider-name (:name provider)
                                         :login-state   login-state})
        (response-frame (:id request) "login_begin" true
                        {:provider {:id (name (:id provider))
                                    :name (:name provider)}
                         :url url
                         :uses-callback-server callback?
                         :pending-login true})))))

(defn- handle-login-complete!
  [ctx request params _state]
  (let [oauth-ctx (:oauth-ctx ctx)]
    (when-not oauth-ctx
      (throw (ex-info "OAuth not available."
                      {:error-code "request/invalid-params"})))
    (when-not (or (nil? (:input params)) (string? (:input params)))
      (throw (ex-info "invalid request parameter :input: string or nil"
                      {:error-code "request/invalid-params"})))
    (let [{:keys [provider-id provider-name login-state]} (pending-login-state ctx)]
      (when-not provider-id
        (throw (ex-info "no pending login"
                        {:error-code "request/no-pending-login"})))
      (let [trimmed (some-> (:input params) str/trim)
            input   (when-not (str/blank? trimmed) trimmed)]
        (oauth/complete-login! oauth-ctx provider-id input login-state)
        (sa/complete-oauth-login-in! ctx provider-id)
        (response-frame (:id request) "login_complete" true
                        {:provider {:id (name provider-id)
                                    :name provider-name}
                         :logged-in true})))))

(defn session->handshake-server-info
  ([ctx]
   (session->handshake-server-info ctx (or *request-session-id* (default-session-id-in ctx))))
  ([ctx session-id]
   (let [sd (ss/get-session-data-in ctx session-id)]
     {:protocol-version protocol-version
      :features         ["eql-graph" "eql-memory"]
      :session-id       (:session-id sd)
      :model-id         (get-in sd [:model :id])
      :thinking-level   (some-> (:thinking-level sd) name)})))

(defn- exception->error-frame
  [request e]
  (let [code    (or (:error-code (ex-data e))
                    (when (= "Session is not idle" (ex-message e))
                      "request/session-not-idle")
                    "runtime/failed")
        message (or (ex-message e) "runtime request failed")
        conflict-data (when (= code "request/session-routing-conflict")
                        (select-keys (ex-data e)
                                     [:inflight-request-id :inflight-session-id :requested-session-id]))]
    (error-frame (cond-> {:id            (:id request)
                          :op            (:op request)
                          :error-code    code
                          :error-message message}
                   (seq conflict-data) (assoc :data conflict-data)))))

(def ^:private event-topics
  #{"session/updated"
    "session/resumed"
    "session/rehydrated"
    "context/updated"
    "assistant/delta"
    "assistant/thinking-delta"
    "assistant/message"
    "tool/start"
    "tool/executing"
    "tool/update"
    "tool/result"
    "ui/dialog-requested"
    "ui/frontend-action-requested"
    "ui/widgets-updated"
    "ui/widget-specs-updated"
    "ui/status-updated"
    "ui/notification"
    "footer/updated"
    "command-result"
    "error"})

(def ^:private required-event-payload-keys
  {"session/updated" #{:session-id :phase :is-streaming :is-compacting :pending-message-count :retry-attempt :interrupt-pending}
   "session/resumed" #{:session-id :session-file :message-count}
   "session/rehydrated" #{:messages :tool-calls :tool-order}
   "context/updated" #{:active-session-id :sessions}
   "assistant/delta" #{:text}
   "assistant/thinking-delta" #{:text}
   "assistant/message" #{:role :content}
   "tool/start" #{:tool-id :tool-name}
   "tool/executing" #{:tool-id :tool-name}
   "tool/update" #{:tool-id :tool-name :content :result-text :is-error}
   "tool/result" #{:tool-id :tool-name :content :result-text :is-error}
   "ui/dialog-requested" #{:dialog-id :kind :title}
   "ui/frontend-action-requested" #{:request-id :action-name}
   "ui/widgets-updated" #{:widgets}
   "ui/widget-specs-updated" #{}
   "ui/status-updated" #{:statuses}
   "ui/notification" #{:id :message :level}
   "footer/updated" #{:path-line :stats-line}
   "command-result" #{:type}
   "error" #{:error-code :error-message}})

(defn- topic-subscribed?
  [state topic]
  (let [subs (:subscribed-topics @state)]
    (or (empty? subs)
        (contains? subs topic))))

(defn- next-event-seq!
  [state]
  (-> (swap! state update :event-seq (fnil inc 0))
      :event-seq))

(defn- emit-event!
  [emit-frame! state {:keys [event data id]}]
  (when (and (contains? event-topics event)
             (topic-subscribed? state event))
    (let [required (get required-event-payload-keys event #{})
          payload  (or data {})
          missing  (seq (remove #(contains? payload %) required))]
      (if missing
        (emit-frame! (event-frame {:event "error"
                                   :id id
                                   :seq (next-event-seq! state)
                                   :ts (java.time.Instant/now)
                                   :data {:error-code "protocol/invalid-event-payload"
                                          :error-message "missing required event payload keys"
                                          :event event
                                          :missing-keys (vec missing)}}))
        (emit-frame! (event-frame {:event event
                                   :data payload
                                   :id id
                                   :seq (next-event-seq! state)
                                   :ts (java.time.Instant/now)}))))))

(defn- normalize-level [lvl]
  (cond
    (keyword? lvl) (name lvl)
    (string? lvl)  lvl
    :else          "info"))

(def ^:private thinking-level->reasoning-effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(defn- effective-reasoning-effort
  [model thinking-level]
  (when (:reasoning model)
    (get thinking-level->reasoning-effort thinking-level "medium")))

(defn- session-updated-payload
  ([ctx]
   (session-updated-payload ctx (or *request-session-id* (default-session-id-in ctx))))
  ([ctx session-id]
   (let [sd               (ss/get-session-data-in ctx session-id)
         model            (:model sd)
         thinking-level   (:thinking-level sd)
         effective-effort (effective-reasoning-effort model thinking-level)]
     {:session-id                  (:session-id sd)
      :session-file                (:session-file sd)
      :session-name                (:session-name sd)
      :phase                       (some-> (ss/sc-phase-in ctx (:session-id sd)) name)
      :is-streaming                (boolean (:is-streaming sd))
      :is-compacting               (boolean (:is-compacting sd))
      :pending-message-count       (+ (count (:steering-messages sd))
                                      (count (:follow-up-messages sd)))
      :retry-attempt               (or (:retry-attempt sd) 0)
      :interrupt-pending           (boolean (:interrupt-pending sd))
      :model-provider              (:provider model)
      :model-id                    (:id model)
      :model-reasoning             (boolean (:reasoning model))
      :thinking-level              (some-> thinking-level name)
      :effective-reasoning-effort  effective-effort})))

(defn- focus-session-id
  [state]
  (some-> @state :focus-session-id* deref))

(defn- set-focus-session-id!
  [state session-id]
  (if-let [a (:focus-session-id* @state)]
    (reset! a session-id)
    (swap! state assoc :focus-session-id* (atom session-id))))

(defn- focused-session-id
  [ctx state]
  (or *request-session-id*
      (focus-session-id state)
      (default-session-id-in ctx)))

(defn- focus-session-id
  [state]
  (some-> @state :focus-session-id* deref))

(defn- set-focus-session-id!
  [state session-id]
  (if-let [a (:focus-session-id* @state)]
    (reset! a session-id)
    (swap! state assoc :focus-session-id* (atom session-id))))

(defn- context-updated-payload
  "Build the `context/updated` event payload from the current context session snapshot.

   Includes a synthetic single-session snapshot before the runtime context index
   has any real entries, so handshake/bootstrap surfaces still reflect the
   current live session.

   Each session slot includes :id :name :worktree-path :is-streaming :is-active
   :parent-session-id and :created-at.
   Sessions are ordered by updated-at ascending (oldest first → stable tree order).
   Temporary 3-arity form reads a specific session-id explicitly."
  ([ctx state]
   (context-updated-payload ctx state (focused-session-id ctx state)))
  ([ctx state session-id]
   (let [active-id        (focus-session-id state)
         sd               (ss/get-session-data-in ctx session-id)
         current-id       (:session-id sd)
         indexed-sessions (or (seq (ss/list-context-sessions-in ctx)) [])
         sessions*        (if (seq indexed-sessions)
                            (->> indexed-sessions
                                 (sort-by :updated-at)
                                 vec)
                            [(select-keys sd [:session-id :session-name :worktree-path :parent-session-id :created-at])])
         active-id*       (or active-id current-id)
         slots            (mapv (fn [m]
                                  {:id                (:session-id m)
                                   :name              (:session-name m)
                                   :worktree-path     (:worktree-path m)
                                   :is-streaming      (boolean
                                                       (and (= (:session-id m) current-id)
                                                            (:is-streaming sd)))
                                   :is-active         (= (:session-id m) active-id*)
                                   :parent-session-id (:parent-session-id m)
                                   :created-at        (:created-at m)})
                                sessions*)]
     {:active-session-id active-id*
      :sessions          slots})))

(def ^:private footer-query
  [:psi.agent-session/cwd
   :psi.agent-session/git-branch
   :psi.agent-session/session-name
   :psi.agent-session/usage-input
   :psi.agent-session/usage-output
   :psi.agent-session/usage-cache-read
   :psi.agent-session/usage-cache-write
   :psi.agent-session/usage-cost-total
   :psi.agent-session/context-fraction
   :psi.agent-session/context-window
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/model-provider
   :psi.agent-session/model-id
   :psi.agent-session/model-reasoning
   :psi.agent-session/thinking-level
   :psi.agent-session/effective-reasoning-effort
   :psi.ui/statuses])

(defn- footer-data
  ([ctx]
   (try
     (or (session/query-in ctx footer-query) {})
     (catch Throwable _
       {})))
  ([ctx session-id]
   (try
     (or (if session-id
           (session/query-in ctx session-id footer-query)
           (session/query-in ctx footer-query))
         {})
     (catch Throwable _
       {}))))

(defn- format-token-count
  [n]
  (let [n (or n 0)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (format "%.1fk" (/ n 1000.0))
      (< n 1000000) (str (Math/round (double (/ n 1000.0))) "k")
      (< n 10000000) (format "%.1fM" (/ n 1000000.0))
      :else (str (Math/round (double (/ n 1000000.0))) "M"))))

(defn- replace-home-with-tilde
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and (string? path)
             (string? home)
             (str/starts-with? path home))
      (str "~" (subs path (count home)))
      (or path ""))))

(defn- positive-number?
  [n]
  (and (number? n) (pos? (double n))))

(defn- normalize-thinking-level
  [level]
  (cond
    (keyword? level) (name level)
    (string? level)  level
    :else            "off"))

(defn- footer-context-text
  [fraction context-window auto-compact?]
  (let [suffix (if auto-compact? " (auto)" "")
        window (format-token-count (or context-window 0))]
    (if (number? fraction)
      (str (format "%.1f" (* 100.0 fraction)) "%/" window suffix)
      (str "?/" window suffix))))

(defn- footer-path-line
  [ctx d]
  (let [session-id   (or *request-session-id* (default-session-id-in ctx))
        cwd          (or (:psi.agent-session/cwd d)
                         (ss/effective-cwd-in ctx session-id)
                         "")
        git-branch   (:psi.agent-session/git-branch d)
        session-name (:psi.agent-session/session-name d)
        path0        (replace-home-with-tilde cwd)
        path1        (if (seq git-branch)
                       (str path0 " (" git-branch ")")
                       path0)]
    (if (seq session-name)
      (str path1 " • " session-name)
      path1)))

(defn- footer-stats-line
  [d]
  (let [usage-input       (or (:psi.agent-session/usage-input d) 0)
        usage-output      (or (:psi.agent-session/usage-output d) 0)
        usage-cache-read  (or (:psi.agent-session/usage-cache-read d) 0)
        usage-cache-write (or (:psi.agent-session/usage-cache-write d) 0)
        usage-cost-total  (or (:psi.agent-session/usage-cost-total d) 0.0)
        context-fraction  (:psi.agent-session/context-fraction d)
        context-window    (:psi.agent-session/context-window d)
        auto-compact?     (boolean (:psi.agent-session/auto-compaction-enabled d))
        model-provider    (:psi.agent-session/model-provider d)
        model-id          (:psi.agent-session/model-id d)
        model-reasoning?  (boolean (:psi.agent-session/model-reasoning d))
        thinking-level    (:psi.agent-session/thinking-level d)
        effective-effort  (:psi.agent-session/effective-reasoning-effort d)

        left-parts
        (cond-> []
          (positive-number? usage-input)
          (conj (str "↑" (format-token-count usage-input)))

          (positive-number? usage-output)
          (conj (str "↓" (format-token-count usage-output)))

          (positive-number? usage-cache-read)
          (conj (str "CR" (format-token-count usage-cache-read)))

          (positive-number? usage-cache-write)
          (conj (str "CW" (format-token-count usage-cache-write)))

          (positive-number? usage-cost-total)
          (conj (format "$%.3f" (double usage-cost-total)))

          :always
          (conj (footer-context-text context-fraction context-window auto-compact?)))

        left (str/join " " left-parts)

        model-label     (or model-id "no-model")
        provider-label  (or model-provider "no-provider")
        thinking-label  (normalize-thinking-level thinking-level)
        effort-label    (or effective-effort
                            (when (not= "off" thinking-label)
                              thinking-label))
        right-base      (if model-reasoning?
                          (if (= "off" thinking-label)
                            (str model-label " • thinking off")
                            (str model-label " • thinking " effort-label))
                          model-label)
        right           (str "(" provider-label ") " right-base)]
    (if (str/blank? left)
      right
      (str left " " right))))

(defn- sanitize-status-text
  [text]
  (-> (or text "")
      (str/replace #"[\r\n\t]" " ")
      (str/replace #" +" " ")
      (str/trim)))

(defn- footer-status-line
  [statuses]
  (let [joined (->> (or statuses [])
                    (sort-by #(or (:extension-id %) (:extensionId %) ""))
                    (map #(sanitize-status-text (or (:text %) (:message %))))
                    (remove str/blank?)
                    (str/join " "))]
    (when (seq joined)
      joined)))

(defn- footer-updated-payload
  ([ctx]
   (let [d (footer-data ctx)]
     {:path-line   (footer-path-line ctx d)
      :stats-line  (footer-stats-line d)
      :status-line (footer-status-line (:psi.ui/statuses d))}))
  ([ctx session-id]
   (let [d (footer-data ctx session-id)]
     {:path-line   (footer-path-line ctx d)
      :stats-line  (footer-stats-line d)
      :status-line (footer-status-line (:psi.ui/statuses d))})))

(defn- progress-event->rpc-event
  [progress-event]
  (let [k (:event-kind progress-event)]
    (case k
      :text-delta
      {:event "assistant/delta"
       :data  {:text (or (:text progress-event) "")}}

      :thinking-delta
      {:event "assistant/thinking-delta"
       :data  {:text (or (:text progress-event) "")}}

      :tool-start
      {:event "tool/start"
       :data  (cond-> {:tool-id   (:tool-id progress-event)
                       :tool-name (:tool-name progress-event)}
                (some? (:arguments progress-event))  (assoc :arguments (:arguments progress-event))
                (some? (:parsed-args progress-event)) (assoc :parsed-args (:parsed-args progress-event)))}

      :tool-executing
      {:event "tool/executing"
       :data  (cond-> {:tool-id   (:tool-id progress-event)
                       :tool-name (:tool-name progress-event)}
                (some? (:arguments progress-event)) (assoc :arguments (:arguments progress-event))
                (some? (:parsed-args progress-event)) (assoc :parsed-args (:parsed-args progress-event)))}

      :tool-execution-update
      {:event "tool/update"
       :data  {:tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      :tool-result
      {:event "tool/result"
       :data  {:tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      nil)))

(defn- emit-progress-queue!
  [progress-q emit!]
  (loop []
    (when-let [evt (.poll progress-q)]
      (when-let [{:keys [event data]} (progress-event->rpc-event evt)]
        (emit! event data))
      (recur))))

(defn- ui-snapshot->events
  [previous current]
  (let [events []
        events (if (and (not= (:active-dialog previous) (:active-dialog current))
                        (map? (:active-dialog current)))
                 (conj events {:event "ui/dialog-requested"
                               :data (let [d (:active-dialog current)]
                                       (cond-> {:dialog-id (:id d)
                                                :kind      (some-> (:kind d) name)
                                                :title     (:title d)}
                                         (contains? d :message) (assoc :message (:message d))
                                         (contains? d :options) (assoc :options (:options d))
                                         (contains? d :placeholder) (assoc :placeholder (:placeholder d))))})
                 events)
        events (if (not= (:widgets previous) (:widgets current))
                 (conj events {:event "ui/widgets-updated"
                               :data  {:widgets (or (:widgets current) [])}})
                 events)
        events (if (not= (:widget-specs previous) (:widget-specs current))
                 (conj events {:event "ui/widget-specs-updated" :data {}})
                 events)
        events (if (not= (:statuses previous) (:statuses current))
                 (conj events {:event "ui/status-updated"
                               :data  {:statuses (or (:statuses current) [])}})
                 events)
        previous-notes (into {} (map (juxt :id identity) (or (:visible-notifications previous) [])))
        current-notes  (into {} (map (juxt :id identity) (or (:visible-notifications current) [])))
        new-notes      (remove #(contains? previous-notes (:id %)) (vals current-notes))]
    (reduce (fn [acc n]
              (conj acc {:event "ui/notification"
                         :data  {:id           (:id n)
                                 :extension-id (:extension-id n)
                                 :message      (:message n)
                                 :level        (normalize-level (:level n))}}))
            events
            new-notes)))

(def ^:private extension-ui-topics
  #{"ui/dialog-requested"
    "ui/widgets-updated"
    "ui/widget-specs-updated"
    "ui/status-updated"
    "ui/notification"})

(defn- extension-ui-topic?
  [topic]
  (contains? extension-ui-topics topic))

(defn- emit-ui-snapshot-events!
  [emit-frame! state previous current]
  (doseq [{:keys [event data]} (ui-snapshot->events (or previous {}) (or current {}))]
    (emit-event! emit-frame! state {:event event :data data})))

(defn- register-rpc-extension-run-fn!
  "Re-register the extension run-fn with an emit-frame!-aware implementation.

   The default run-fn registered in main.clj has no progress-queue, so
   extension-initiated agent runs (e.g. PSL) produce no streaming events
   visible to the RPC client. This version creates a progress-queue, polls
   it in a background loop, and routes events to emit-frame! — giving the
   PSL response the same streaming visibility as a normal user prompt.

   Called once from the subscribe handler. Guard via :rpc-run-fn-registered
   in state so it is only set up once per session connection."
  [ctx emit-frame! state]
  (when-not (:rpc-run-fn-registered @state)
    (swap! state assoc :rpc-run-fn-registered true)
    (let [session-id  (focused-session-id ctx state)
          ai-model-fn (fn [sid] (current-ai-model ctx state sid))
          run-fn      (fn [text _source]
                        (try
                          (loop [attempt 0]
                            (if (ss/idle-in? ctx session-id)
                              (let [{:keys [user-message]} (runtime/prepare-user-message-in! ctx session-id text nil)
                                    ai-model  (ai-model-fn session-id)
                                    api-key   (runtime/resolve-api-key-in ctx session-id ai-model)
                                    emit!     (fn [event payload]
                                                (emit-event! emit-frame! state {:event event :data payload}))
                                    progress-q (java.util.concurrent.LinkedBlockingQueue.)
                                    stop?      (atom false)
                                    poll-loop  (start-daemon-thread!
                                                (fn []
                                                  (loop []
                                                    (when-not @stop?
                                                      (when-let [evt (.poll progress-q 10
                                                                            java.util.concurrent.TimeUnit/MILLISECONDS)]
                                                        (when-let [{:keys [event data]} (progress-event->rpc-event evt)]
                                                          (emit! event data)
                                                          (when (= :tool-result (:event-kind evt))
                                                            (emit! "footer/updated" (footer-updated-payload ctx session-id))))
                                                        (loop []
                                                          (when-let [more (.poll progress-q)]
                                                            (when-let [{:keys [event data]} (progress-event->rpc-event more)]
                                                              (emit! event data)
                                                              (when (= :tool-result (:event-kind more))
                                                                (emit! "footer/updated" (footer-updated-payload ctx session-id))))
                                                            (recur))))
                                                      (recur))))
                                                "rpc-poll-loop")
                                    result    (runtime/run-agent-loop-in!
                                               ctx session-id nil ai-model [user-message]
                                               {:api-key        api-key
                                                :progress-queue progress-q
                                                :sync-on-git-head-change? true})]
                                (reset! stop? true)
                                (.join ^Thread poll-loop 200)
                                (emit-progress-queue! progress-q emit!)
                                (let [content (or (:content result) [])
                                      text    (assistant-content-text content)]
                                  (emit! "assistant/message"
                                         (cond-> {:role    (:role result)
                                                  :content content}
                                           (and (string? text) (not (str/blank? text)))
                                           (assoc :text text)
                                           (contains? result :stop-reason)   (assoc :stop-reason (:stop-reason result))
                                           (contains? result :error-message) (assoc :error-message (:error-message result))
                                           (contains? result :usage)         (assoc :usage (:usage result)))))
                                (emit! "session/updated" (session-updated-payload ctx session-id))
                                (emit! "footer/updated"  (footer-updated-payload ctx session-id)))
                              (when (< attempt 1200)
                                (Thread/sleep 250)
                                (recur (inc attempt)))))
                          (catch Exception e
                            (emit-event! emit-frame! state
                                         {:event "error"
                                          :data  {:error-code    "runtime/failed"
                                                  :error-message (or (ex-message e) "extension run failed")}}))))]
      (ext-rt/set-extension-run-fn-in! ctx session-id run-fn))))

(defn- maybe-start-ui-watch-loop!
  [ctx emit-frame! state]
  (when (and (some extension-ui-topic? (:subscribed-topics @state))
             (nil? (:ui-watch-loop @state)))
    (let [watch-loop (future
                       (binding [*out* (:err @state)
                                 *err* (:err @state)]
                         (loop [last-snap (or (ui-snapshot ctx) {})]
                           (let [current (or (ui-snapshot ctx) {})]
                             (emit-ui-snapshot-events! emit-frame! state last-snap current)
                             (Thread/sleep 50)
                             (recur current)))))]
      (swap! state assoc :ui-watch-loop watch-loop))))

(defn- assistant-content-text
  [content]
  (or (message-text/content-display-text content)
      (message-text/content-text content)))

(defn- external-message->assistant-payload
  [message]
  (let [content (or (:content message) [])
        text    (or (:text message)
                    (assistant-content-text content))]
    (cond-> {:role    (or (:role message) "assistant")
             :content content}
      (and (string? text) (not (str/blank? text))) (assoc :text text)
      (contains? message :custom-type) (assoc :custom-type (:custom-type message))
      (contains? message :stop-reason) (assoc :stop-reason (:stop-reason message))
      (contains? message :error-message) (assoc :error-message (:error-message message))
      (contains? message :usage) (assoc :usage (:usage message)))))

(defn- maybe-start-external-event-loop!
  [ctx emit-frame! state]
  (when (and (:event-queue ctx)
             (nil? (:external-event-loop @state)))
    (let [session-id  (focused-session-id ctx state)
          event-queue (:event-queue ctx)
          loop-fut    (future
                        (binding [*out* (:err @state)
                                  *err* (:err @state)]
                          (loop []
                            (when-let [evt (.poll ^java.util.concurrent.LinkedBlockingQueue
                                            event-queue
                                                  20
                                                  java.util.concurrent.TimeUnit/MILLISECONDS)]
                              (when (= :external-message (:type evt))
                                (let [message (:message evt)]
                                  (emit-event! emit-frame! state
                                               {:event "assistant/message"
                                                :data  (external-message->assistant-payload message)})
                                  (emit-event! emit-frame! state
                                               {:event "session/updated"
                                                :data  (session-updated-payload ctx session-id)})
                                  (emit-event! emit-frame! state
                                               {:event "footer/updated"
                                                :data  (footer-updated-payload ctx session-id)}))))
                            (recur))))]
      (swap! state assoc :external-event-loop loop-fut)
      ;; Emit an immediate footer snapshot after starting the loop so subscribers
      ;; always observe footer state even if the loop is stopped immediately after
      ;; a single external assistant message is processed.
      (emit-event! emit-frame! state
                   {:event "footer/updated"
                    :data  (footer-updated-payload ctx session-id)}))))

(defn- emit-assistant-text!
  [emit! text]
  (let [text* (str text)]
    (emit! "assistant/message"
           {:role    "assistant"
            :text    text*
            :content [{:type :text :text text*}]})))

(defn- complete-pending-login!
  [ctx _state message emit!]
  (when-let [{:keys [provider-id provider-name login-state]} (pending-login-state ctx)]
    (if-let [oauth-ctx (:oauth-ctx ctx)]
      (try
        (let [trimmed (some-> message str/trim)
              input   (when-not (str/blank? trimmed) trimmed)]
          (oauth/complete-login! oauth-ctx provider-id input login-state)
          (sa/complete-oauth-login-in! ctx provider-id)
          (emit-assistant-text! emit! (str "✓ Logged in to " (or provider-name
                                                                 (some-> provider-id name)
                                                                 "provider"))))
        (catch Throwable e
          (sa/complete-oauth-login-in! ctx provider-id)
          (emit-assistant-text! emit! (str "✗ Login failed: " (ex-message e)))))
      (emit-assistant-text! emit! "OAuth not available."))))

(defn- handle-login-start-command!
  [ctx state emit-frame! request-id cmd-result emit!]
  (let [session-id    (focused-session-id ctx state)
        provider-id   (get-in cmd-result [:provider :id])
        provider-name (or (get-in cmd-result [:provider :name])
                          (some-> provider-id name)
                          "provider")
        login-state   (:login-state cmd-result)
        callback?     (boolean (:uses-callback-server cmd-result))]
    (emit-assistant-text! emit!
                          (str "Login: " provider-name
                               " — open URL: " (:url cmd-result)))
    (if callback?
      (do
        (emit-assistant-text! emit! "Waiting for browser callback…")
        (if-let [oauth-ctx (:oauth-ctx ctx)]
          (let [worker (start-daemon-thread!
                        (fn []
                          (binding [*out* (:err @state)
                                    *err* (:err @state)]
                            (let [emit-login! (fn [event payload]
                                                (emit-event! emit-frame! state {:event event
                                                                                :data payload
                                                                                :id request-id}))]
                              (try
                                (oauth/complete-login! oauth-ctx provider-id nil login-state)
                                (emit-assistant-text! emit-login! (str "✓ Logged in to " provider-name))
                                (catch Throwable e
                                  (emit-assistant-text! emit-login! (str "✗ Login failed: " (ex-message e))))
                                (finally
                                  (emit-login! "session/updated" (session-updated-payload ctx session-id))
                                  (emit-login! "footer/updated" (footer-updated-payload ctx session-id)))))))
                        "rpc-oauth-worker")]
            (swap! state update :inflight-futures (fnil conj []) worker))
          (emit-assistant-text! emit! "OAuth not available.")))
      (do
        (sa/set-oauth-pending-login-in! ctx
                                        {:provider-id provider-id
                                         :provider-name provider-name
                                         :login-state login-state})
        (emit-assistant-text! emit! "Paste authorization code as your next prompt message.")))))

(defn- emit-command-result!
  [emit! payload]
  (emit! "command-result" payload))

(defn- emit-frontend-action-request!
  [emit! request-id cmd-result]
  (emit! "ui/frontend-action-requested"
         {:request-id request-id
          :action-name (some-> (:action-name cmd-result) name)
          :prompt (:prompt cmd-result)
          :payload (:payload cmd-result)}))

(defn- handle-prompt-command-result!
  "Legacy prompt-path slash command event mapping.
   Keeps existing prompt RPC behavior stable while the new `command` op
   uses `command-result` and `ui/frontend-action-requested`."
  [cmd-result emit!]
  (let [result-type (:type cmd-result)]
    (case result-type
      (:text :logout :login-error :new-session)
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text :text (str (:message cmd-result))}]})

      :login-start
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text (str "Login: " (get-in cmd-result [:provider :name])
                                    " — open URL: " (:url cmd-result))}]})

      :quit
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text "[/quit is not supported over RPC prompt — use the abort op or close the connection]"}]})

      :resume
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text "[/resume is not supported over RPC prompt — use switch_session op]"}]})

      :tree-open
      (emit! "assistant/message"
             {:role "assistant"
              :content [{:type :text
                         :text "[/tree is only available in TUI mode (--tui)]"}]})

      :tree-switch
      (emit! "assistant/message"
             {:role "assistant"
              :content [{:type :text
                         :text (str "[session switch requested: " (:session-id cmd-result) "]")}]})

      :extension-cmd
      (let [output (try
                     (let [out (with-out-str
                                 ((:handler cmd-result) (:args cmd-result)))]
                       (if (str/blank? out)
                         "[extension command returned no output]"
                         out))
                     (catch Throwable e
                       (str "[extension command error: " (ex-message e) "]")))]
        (emit! "assistant/message"
               {:role    "assistant"
                :content [{:type :text :text output}]}))

      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text :text (str "[command result: " result-type "]")}]}))))

(defn- handle-command-result!
  "Map a commands/dispatch result to canonical RPC event emissions.
   Emits command-result or ui/frontend-action-requested without invoking the agent loop."
  [request-id cmd-result emit!]
  (let [result-type (:type cmd-result)]
    (case result-type
      (:text :logout :login-error :new-session)
      (emit-command-result! emit! {:type (if (= :new-session result-type)
                                           "new_session"
                                           (name result-type))
                                   :message (str (:message cmd-result))})

      :login-start
      (emit-command-result! emit! {:type "login_start"
                                   :message (str "Login: " (get-in cmd-result [:provider :name])
                                                 " — open URL: " (:url cmd-result))
                                   :provider-name (get-in cmd-result [:provider :name])
                                   :login-url (:url cmd-result)
                                   :uses-callback-server (boolean (:uses-callback-server cmd-result))})

      :quit
      (emit-command-result! emit! {:type "quit"})

      :resume
      (emit-command-result! emit! {:type "text"
                                   :message "[/resume requires frontend action handling]"})

      :tree-open
      (emit-command-result! emit! {:type "text"
                                   :message "[/tree requires frontend action handling]"})

      :tree-switch
      (emit-command-result! emit! {:type "session_switch"
                                   :session-id (:session-id cmd-result)})

      :session-switch
      (emit-command-result! emit! {:type "session_switch"
                                   :session-id (:session-id cmd-result)})

      :frontend-action
      (emit-frontend-action-request! emit! request-id cmd-result)

      :extension-cmd
      (let [output (try
                     (let [out (with-out-str
                                 ((:handler cmd-result) (:args cmd-result)))]
                       (if (str/blank? out)
                         "[extension command returned no output]"
                         out))
                     (catch Throwable e
                       (str "[extension command error: " (ex-message e) "]")))]
        (emit-command-result! emit! {:type "text" :message output}))

      ;; Unknown result type — emit a fallback
      (emit-command-result! emit! {:type "text"
                                   :message (str "[command result: " result-type "]")}))))

(defn- valid-session-id-param!
  [session-id]
  (when session-id
    (when-not (string? session-id)
      (throw (ex-info "invalid request parameter :session-id: non-empty string"
                      {:error-code "request/invalid-params"})))
    (when (str/blank? session-id)
      (throw (ex-info "invalid request parameter :session-id: non-empty string"
                      {:error-code "request/invalid-params"}))))
  session-id)

(defn- with-request-session!
  "Run `f` against optional request session-id.

   Session resolution order:
   1. Explicit :session-id in request params (client-directed targeting)
   2. RPC-local focus session-id from transport state
   3. Unmodified ctx when no explicit target is available

   All sessions are resident in the atom — no loading required."
  ([ctx request f]
   (with-request-session! ctx request nil f))
  ([ctx request state f]
   (let [params     (params-map request)
         session-id (or (valid-session-id-param! (:session-id params))
                        (when state (focus-session-id state))
                        (default-session-id-in ctx))]
     (binding [*request-session-id* session-id]
       (f ctx)))))

(defn- run-command!
  [ctx request emit-frame! state]
  (let [text       (req-arg! request (params-map request) :text #(and (string? %) (not (str/blank? %))) "non-empty string")
        request-id (:id request)
        emit!      (fn [event payload]
                     (emit-event! emit-frame! state {:event event :data payload :id request-id}))
        ai-model   (current-ai-model ctx state)
        oauth-ctx  (:oauth-ctx ctx)
        trimmed    (str/trim text)
        session-id (focused-session-id ctx state)
        cmd-result (commands/dispatch-in ctx session-id text {:oauth-ctx oauth-ctx
                                                              :ai-model ai-model
                                                              :supports-session-tree? false
                                                              :on-new-session! (:on-new-session! @state)})]
    (runtime/journal-user-message-in! ctx session-id text nil)
    (cond
      (= trimmed "/resume")
      (emit! "ui/frontend-action-requested"
             {:request-id request-id
              :action-name "resume-selector"
              :prompt "Select a session to resume"
              :payload {:query (session/query-in ctx
                                                 [{:psi.session/list
                                                   [:psi.session-info/path
                                                    :psi.session-info/name
                                                    :psi.session-info/worktree-path
                                                    :psi.session-info/first-message
                                                    :psi.session-info/modified]}])}})

      (str/starts-with? trimmed "/resume ")
      (let [session-path (-> (str/replace trimmed #"^/resume\s+" "") str/trim)]
        (if-not (.exists (io/file session-path))
          (emit-command-result! emit! {:type "text"
                                       :message (str "Session file not found: " session-path)})
          (let [current-sid session-id
                sd          (session/resume-session-in! ctx current-sid session-path)
                sid         (:session-id sd)
                _           (set-focus-session-id! state sid)
                msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
            (emit! "session/resumed"
                   {:session-id   sid
                    :session-file (:session-file sd)
                    :message-count (count msgs)})
            (emit! "session/rehydrated"
                   {:messages msgs
                    :tool-calls {}
                    :tool-order []}))))

      (= trimmed "/tree")
      (let [active-id  (focus-session-id state)
            sessions0  (vec (or (ss/list-context-sessions-in ctx) []))
            sessions   (if (seq sessions0)
                         sessions0
                         [(select-keys (ss/get-session-data-in ctx session-id)
                                       [:session-id :session-name :worktree-path])])]
        (emit! "ui/frontend-action-requested"
               {:request-id request-id
                :action-name "context-session-selector"
                :prompt "Select a live session"
                :payload {:active-session-id active-id
                          :sessions sessions}}))

      (str/starts-with? trimmed "/tree ")
      (let [arg        (-> (str/replace trimmed #"^/tree\s+" "") str/trim)
            active-id  (focus-session-id state)
            sessions0  (vec (or (ss/list-context-sessions-in ctx) []))
            sessions   (if (seq sessions0)
                         sessions0
                         [(select-keys (ss/get-session-data-in ctx session-id)
                                       [:session-id :session-name :worktree-path])])
            match-by-id
            (some (fn [m]
                    (when (= arg (:session-id m))
                      m))
                  sessions)
            match-by-prefix
            (when-not match-by-id
              (let [matches (filterv (fn [m]
                                       (str/starts-with? (or (:session-id m) "") arg))
                                     sessions)]
                (when (= 1 (count matches))
                  (first matches))))
            chosen (or match-by-id match-by-prefix)
            sid    (:session-id chosen)]
        (cond
          (nil? chosen)
          (emit-command-result! emit! {:type "text"
                                       :message (str "Session not found in context: " arg)})

          (= sid active-id)
          (emit-command-result! emit! {:type "text"
                                       :message (str "Already active session: " sid)})

          :else
          (do
            (session/ensure-session-loaded-in! ctx active-id sid)
            (set-focus-session-id! state sid)
            (let [sd   (ss/get-session-data-in ctx sid)
                  msgs (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
              (emit! "session/resumed"
                     {:session-id   (:session-id sd)
                      :session-file (:session-file sd)
                      :message-count (count msgs)})
              (emit! "session/rehydrated"
                     {:messages msgs
                      :tool-calls {}
                      :tool-order []})))))

      (= trimmed "/model")
      (emit! "ui/frontend-action-requested"
             {:request-id request-id
              :action-name "model-picker"
              :prompt "Select a model"
              :payload {:models (->> ai-models/all-models
                                     vals
                                     (sort-by (juxt :provider :id))
                                     (mapv (fn [m]
                                             {:provider (name (:provider m))
                                              :id (:id m)
                                              :reasoning (boolean (:supports-reasoning m))})))}})

      (= trimmed "/thinking")
      (emit! "ui/frontend-action-requested"
             {:request-id request-id
              :action-name "thinking-picker"
              :prompt "Select a thinking level"
              :payload {:levels ["off" "minimal" "low" "medium" "high" "xhigh"]}})

      cmd-result
      (if (= :login-start (:type cmd-result))
        (handle-login-start-command! ctx state emit-frame! request-id cmd-result emit!)
        (do
          (when (= :new-session (:type cmd-result))
            (let [rehydrate (:rehydrate cmd-result)
                  ;; Get new session-id from rehydrate (lifecycle or callback)
                  new-sid   (:session-id rehydrate)
                  _         (when new-sid (set-focus-session-id! state new-sid))
                  sd        (when new-sid (ss/get-session-data-in ctx new-sid))
                  msgs      (or (:agent-messages rehydrate)
                                (when new-sid
                                  (:messages (agent/get-data-in (ss/agent-ctx-in ctx new-sid)))))]
              (emit! "session/resumed"
                     {:session-id   (:session-id sd)
                      :session-file (:session-file sd)
                      :message-count (count msgs)})
              (emit! "session/rehydrated"
                     {:messages   msgs
                      :tool-calls (or (:tool-calls rehydrate) {})
                      :tool-order (or (:tool-order rehydrate) [])})))
          (handle-command-result! request-id cmd-result emit!)))

      :else
      (emit-command-result! emit! {:type "text"
                                   :message (str "[not a command] " text)}))
    (emit! "session/updated" (session-updated-payload ctx session-id))
    (emit! "footer/updated" (footer-updated-payload ctx session-id))
    (emit! "context/updated" (context-updated-payload ctx state))
    (response-frame (:id request) "command" true {:accepted true
                                                  :handled true})))

(defn- handle-frontend-action-result!
  [ctx request emit-frame! state]
  (let [params      (params-map request)
        request-id  (req-arg! request params :request-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        action-name (req-arg! request params :action-name #(and (string? %) (not (str/blank? %))) "non-empty string")
        status      (req-arg! request params :status #(and (string? %) (not (str/blank? %))) "non-empty string")
        value       (:value params)
        emit!       (fn [event payload]
                      (emit-event! emit-frame! state {:event event :data payload :id (:id request)}))
        session-id  (focused-session-id ctx state)]
    (case status
      "cancelled"
      (do
        (emit-command-result! emit! {:type "text"
                                     :message (str "Cancelled " action-name ".")})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      "failed"
      (do
        (emit-command-result! emit! {:type "error"
                                     :message (or (:error-message params)
                                                  (str "Frontend action failed: " action-name))})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      (do
        (case action-name
          "resume-selector"
          (when (string? value)
            (when (.exists (io/file value))
              (let [current-sid session-id
                    sd          (session/resume-session-in! ctx current-sid value)
                    sid         (:session-id sd)
                    _           (set-focus-session-id! state sid)
                    msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                (emit-event! emit-frame! state {:event "session/resumed"
                                                :id (:id request)
                                                :data {:session-id sid
                                                       :session-file (:session-file sd)
                                                       :message-count (count msgs)}})
                (emit-event! emit-frame! state {:event "session/rehydrated"
                                                :id (:id request)
                                                :data {:messages msgs
                                                       :tool-calls {}
                                                       :tool-order []}})
                (emit! "context/updated" (context-updated-payload ctx state)))))

          "context-session-selector"
          (when (string? value)
            (session/ensure-session-loaded-in! ctx session-id value)
            (let [_    (set-focus-session-id! state value)
                  sd   (ss/get-session-data-in ctx value)
                  msgs (:messages (agent/get-data-in (ss/agent-ctx-in ctx value)))]
              (emit-event! emit-frame! state {:event "session/resumed"
                                              :id (:id request)
                                              :data {:session-id (:session-id sd)
                                                     :session-file (:session-file sd)
                                                     :message-count (count msgs)}})
              (emit-event! emit-frame! state {:event "session/rehydrated"
                                              :id (:id request)
                                              :data {:messages msgs
                                                     :tool-calls {}
                                                     :tool-order []}})
              (emit! "context/updated" (context-updated-payload ctx state))))

          "model-picker"
          (when (map? value)
            (let [provider (or (:provider value) (get value "provider"))
                  model-id (or (:id value) (get value "id"))
                  resolved (resolve-model provider model-id)]
              (when resolved
                (let [provider-str (name (:provider resolved))
                      model {:provider provider-str :id (:id resolved) :reasoning (:supports-reasoning resolved)}]
                  (session/set-model-in! ctx session-id model)
                  (emit-command-result! emit! {:type "text"
                                               :message (str "✓ Model set to " provider-str " " (:id resolved))})))))

          "thinking-picker"
          (when (string? value)
            (let [level  (keyword value)
                  result (session/set-thinking-level-in! ctx session-id level)]
              (emit-command-result! emit! {:type "text"
                                           :message (str "✓ Thinking level set to " (name (:thinking-level result)))})))

          nil)
        (emit! "session/updated" (session-updated-payload ctx session-id))
        (emit! "footer/updated" (footer-updated-payload ctx session-id))
        (response-frame (:id request)
                        "frontend_action_result"
                        true
                        {:accepted true
                         :request-id request-id})))))

(defn- run-prompt-async!
  [ctx request emit-frame! state]
  (let [message      (get-in request [:params :message])
        images       (get-in request [:params :images])
        request-id   (:id request)
        session-id   (focused-session-id ctx state)
        _            (when-not session-id
                       (throw (ex-info "no target session available for prompt"
                                       {:error-code "request/not-found"})))
        custom-run-loop? (contains? @state :run-agent-loop-fn)
        run-loop-fn  (or (:run-agent-loop-fn @state) executor/run-agent-loop!)
        sync-on-git-head-change?
        (if (contains? @state :sync-on-git-head-change?)
          (boolean (:sync-on-git-head-change? @state))
          (not custom-run-loop?))
        on-new-session! (:on-new-session! @state)
        progress-q   (java.util.concurrent.LinkedBlockingQueue.)
        worker       (start-daemon-thread!
                      (fn []
                        (binding [*out* (:err @state)
                                  *err* (:err @state)]
                          (let [emit! (fn [event payload]
                                        (emit-event! emit-frame! state {:event event :data payload :id request-id}))
                                progress-stop? (atom false)
                                progress-loop (start-daemon-thread!
                                               (fn []
                                                 (loop []
                                                   (when-not @progress-stop?
                                                     ;; Block up to 10ms waiting for the next event, then drain
                                                     ;; all immediately-available events without sleeping between
                                                     ;; them. This prevents batching: events from a new LLM turn
                                                     ;; that arrive while the loop was idle are emitted one-by-one
                                                     ;; in arrival order rather than as a burst after a 50ms gap.
                                                     (when-let [evt (.poll progress-q 10 java.util.concurrent.TimeUnit/MILLISECONDS)]
                                                       (when-let [{:keys [event data]} (progress-event->rpc-event evt)]
                                                         (emit! event data)
                                                         (when (= :tool-result (:event-kind evt))
                                                           (emit! "footer/updated" (footer-updated-payload ctx session-id))))
                                                       ;; Drain any additional events already in the queue
                                                       ;; without blocking, preserving arrival order.
                                                       (loop []
                                                         (when-let [more (.poll progress-q)]
                                                           (when-let [{:keys [event data]} (progress-event->rpc-event more)]
                                                             (emit! event data)
                                                             (when (= :tool-result (:event-kind more))
                                                               (emit! "footer/updated" (footer-updated-payload ctx session-id))))
                                                           (recur))))
                                                     (recur))))
                                               "rpc-progress-loop")]
                            (try
                              (let [ai-model      (current-ai-model ctx state session-id)
                                    _             (when-not ai-model
                                                    (throw (ex-info "session model is not configured"
                                                                    {:error-code "request/invalid-params"})))
                                    oauth-ctx     (:oauth-ctx ctx)
                                    pending-login (pending-login-state ctx)
                                    cmd-result    (when-not pending-login
                                                    (commands/dispatch-in ctx session-id message {:oauth-ctx oauth-ctx
                                                                                                  :ai-model  ai-model
                                                                                                  :supports-session-tree? false
                                                                                                  :on-new-session! on-new-session!}))]
                                (cond
                                  pending-login
                                 ;; Pending two-step OAuth login: next prompt input is the auth code/URL.
                                  (do
                                    (runtime/journal-user-message-in! ctx session-id message images)
                                    (complete-pending-login! ctx state message emit!)
                                    (emit! "session/updated" (session-updated-payload ctx session-id))
                                    (emit! "footer/updated" (footer-updated-payload ctx session-id)))

                                  (some? cmd-result)
                                 ;; Slash command matched — journal raw input and skip agent loop.
                                  (do
                                    (runtime/journal-user-message-in! ctx session-id message images)
                                    (if (= :login-start (:type cmd-result))
                                      (handle-login-start-command! ctx state emit-frame! request-id cmd-result emit!)
                                      (do
                                        (when (= :new-session (:type cmd-result))
                                          (let [rehydrate (:rehydrate cmd-result)
                                                ;; Get new session-id from rehydrate (lifecycle or callback)
                                                new-sid (:session-id rehydrate)
                                                _       (when new-sid (set-focus-session-id! state new-sid))
                                                sd      (when new-sid (ss/get-session-data-in ctx new-sid))
                                                msgs    (or (:agent-messages rehydrate)
                                                            (when new-sid
                                                              (:messages (agent/get-data-in (ss/agent-ctx-in ctx new-sid)))))]
                                            (emit! "session/resumed"
                                                   {:session-id (:session-id sd)
                                                    :session-file (:session-file sd)
                                                    :message-count (count msgs)})
                                            (emit! "session/rehydrated"
                                                   {:messages msgs
                                                    :tool-calls (or (:tool-calls rehydrate) {})
                                                    :tool-order (or (:tool-order rehydrate) [])})))
                                        (handle-prompt-command-result! cmd-result emit!)))
                                    (emit! "session/updated" (session-updated-payload ctx session-id))
                                    (emit! "footer/updated" (footer-updated-payload ctx session-id)))

                                  :else
                                 ;; Not a command — run normal agent loop via shared runtime path.
                                  (let [_        (emit! "session/updated" (session-updated-payload ctx session-id))
                                        _        (emit! "footer/updated" (footer-updated-payload ctx session-id))
                                        {:keys [user-message]} (runtime/prepare-user-message-in! ctx session-id message images)
                                        api-key  (runtime/resolve-api-key-in ctx session-id ai-model)
                                        result   (runtime/run-agent-loop-in!
                                                  ctx session-id nil ai-model [user-message]
                                                  {:run-loop-fn   run-loop-fn
                                                   :api-key       api-key
                                                   :progress-queue progress-q
                                                   :sync-on-git-head-change? sync-on-git-head-change?})]
                                   ;; Stop background progress polling, flush any
                                   ;; remaining events, then emit final message.
                                    (reset! progress-stop? true)
                                    (.join ^Thread progress-loop 200)
                                    (emit-progress-queue! progress-q emit!)
                                    (let [content (or (:content result) [])
                                          text    (assistant-content-text content)]
                                      (emit! "assistant/message"
                                             (cond-> {:role    (:role result)
                                                      :content content}
                                               (and (string? text) (not (str/blank? text)))
                                               (assoc :text text)
                                               (contains? result :stop-reason)   (assoc :stop-reason (:stop-reason result))
                                               (contains? result :error-message) (assoc :error-message (:error-message result))
                                               (contains? result :usage)         (assoc :usage (:usage result)))))
                                    (emit! "session/updated" (session-updated-payload ctx session-id))
                                    (emit! "footer/updated" (footer-updated-payload ctx session-id)))))
                              (catch Throwable t
                                (emit! "error" {:error-code "runtime/failed"
                                                :error-message (or (ex-message t) "prompt execution failed")
                                                :id request-id
                                                :op "prompt"})
                                (emit! "session/updated" (session-updated-payload ctx session-id))
                                (emit! "footer/updated" (footer-updated-payload ctx session-id)))
                              (finally
                                (reset! progress-stop? true)
                                (.join ^Thread progress-loop 200)))))))]
    (swap! state update :inflight-futures (fnil conj []) worker)
    (response-frame (:id request) "prompt" true {:accepted true})))

(defn make-session-request-handler
  "Create a canonical op router bound to an agent-session context.

   Returned fn signature matches `run-stdio-loop!` request-handler:
   (fn [request emit-frame! state] -> frame | [frame*] | nil)

   Runtime state mutations used by this handler:
   - :subscribed-topics (set of topic strings)

   OAuth pending-login for login_begin/login_complete and /login prompt flow
   is stored in the canonical session oauth projection, not in transport state.

   opts:
   - :on-new-session! optional callback used by /new command and new_session op.
     Expected to return {:messages [...], :tool-calls {...}, :tool-order [...]}"
  ([ctx] (make-session-request-handler ctx {}))
  ([ctx {:keys [on-new-session!]}]
   (fn [request emit-frame! state]
     (try
       (let [op     (:op request)
             params (params-map request)
             dispatch-op (fn [ctx]
                           (case op
                             "ping"
                             (response-frame (:id request) "ping" true {:pong true :protocol-version protocol-version})

                             "query_eql"
                             (let [query-str  (req-arg! request params :query #(and (string? %) (not (str/blank? %))) "non-empty EDN string")
                                   entity-str (:entity params)
                                   q          (parse-query-edn! query-str)
                                   extra      (when (and (string? entity-str) (not (str/blank? entity-str)))
                                                (try
                                                  (binding [*read-eval* false]
                                                    (let [m (edn/read-string entity-str)]
                                                      (when (map? m) m)))
                                                  (catch Throwable _ nil)))
                                   result     (try
                                                (session/query-in ctx q (or extra {}))
                                                (catch Throwable e
                                                  (throw (ex-info (or (ex-message e) "query execution failed")
                                                                  {:error-code "runtime/query-failed"}
                                                                  e))))]
                               (response-frame (:id request) op true {:result result}))

                             "command"
                             (run-command! ctx request emit-frame! state)

                             "frontend_action_result"
                             (handle-frontend-action-result! ctx request emit-frame! state)

                             "prompt"
                             (let [_message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")]
                               (run-prompt-async! ctx request emit-frame! state))

                             "prompt_while_streaming"
                             (let [message   (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   behavior* (let [behavior (:behavior params)]
                                               (cond
                                                 (nil? behavior)      "steer"
                                                 (keyword? behavior)  (name behavior)
                                                 (string? behavior)   behavior
                                                 :else                nil))
                                   sid       (focused-session-id ctx state)]
                               (case behavior*
                                 "steer"
                                 (let [{:keys [accepted? behavior]} (session/queue-while-streaming-in! ctx sid message :steer)]
                                   (response-frame (:id request) op true {:accepted accepted?
                                                                          :behavior (name behavior)}))

                                 "queue"
                                 (let [{:keys [accepted? behavior]} (session/queue-while-streaming-in! ctx sid message :queue)]
                                   (response-frame (:id request) op true {:accepted accepted?
                                                                          :behavior (name behavior)}))

                                 (throw (ex-info "prompt_while_streaming :behavior must be \"steer\" or \"queue\""
                                                 {:error-code "request/invalid-params"}))))

                             "steer"
                             (let [message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   sid     (focused-session-id ctx state)]
                               (session/steer-in! ctx sid message)
                               (response-frame (:id request) op true {:accepted true}))

                             "follow_up"
                             (let [message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   sid     (focused-session-id ctx state)]
                               (session/follow-up-in! ctx sid message)
                               (response-frame (:id request) op true {:accepted true}))

                             "abort"
                             (let [sid (focused-session-id ctx state)]
                               (session/abort-in! ctx sid)
                               (response-frame (:id request) op true {:accepted true}))

                             "interrupt"
                             (let [sid (focused-session-id ctx state)
                                   {:keys [accepted? pending? dropped-steering-text]}
                                   (session/request-interrupt-in! ctx sid)]
                               (response-frame (:id request) op true
                                               {:accepted accepted?
                                                :pending  pending?
                                                :dropped-steering-text (or dropped-steering-text "")}))

                             "list_background_jobs"
                             (handle-list-background-jobs! ctx request params)

                             "inspect_background_job"
                             (handle-inspect-background-job! ctx request params)

                             "cancel_background_job"
                             (handle-cancel-background-job! ctx request params)

                             "login_begin"
                             (handle-login-begin! ctx request params state)

                             "login_complete"
                             (handle-login-complete! ctx request params state)

                             "new_session"
                             (let [new-session-fn    (or on-new-session! (:on-new-session! @state))
                                   source-session-id (focused-session-id ctx state)
                                   [rehydrate new-sd]
                                   (if new-session-fn
                                     [(new-session-fn) nil]
                                     (let [sd (session/new-session-in! ctx source-session-id {})]
                                       [{:agent-messages [] :messages [] :tool-calls {} :tool-order []} sd]))
                                   ;; Get new session-id from lifecycle return or rehydrate callback
                                   new-sid   (or (:session-id new-sd) (:session-id rehydrate))
                                   _         (set-focus-session-id! state new-sid)
                                   sd        (ss/get-session-data-in ctx new-sid)
                                   msgs      (or (:agent-messages rehydrate)
                                                 (:messages (agent/get-data-in (ss/agent-ctx-in ctx new-sid))))]
                               (emit-event! emit-frame! state {:event "session/resumed"
                                                               :id (:id request)
                                                               :data {:session-id   (:session-id sd)
                                                                      :session-file (:session-file sd)
                                                                      :message-count (count msgs)}})
                               (emit-event! emit-frame! state {:event "session/rehydrated"
                                                               :id (:id request)
                                                               :data {:messages msgs
                                                                      :tool-calls (or (:tool-calls rehydrate) {})
                                                                      :tool-order (or (:tool-order rehydrate) [])}})
                               (emit-event! emit-frame! state {:event "session/updated"
                                                               :id (:id request)
                                                               :data (session-updated-payload ctx new-sid)})
                               (emit-event! emit-frame! state {:event "footer/updated"
                                                               :id (:id request)
                                                               :data (footer-updated-payload ctx new-sid)})
                               (emit-event! emit-frame! state {:event "context/updated"
                                                               :id (:id request)
                                                               :data (context-updated-payload ctx state)})
                               (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                      :session-file (:session-file sd)}))

                             "switch_session"
                             (if-let [sid (:session-id params)]
                               (do
                                 (when-not (and (string? sid) (not (str/blank? sid)))
                                   (throw (ex-info "invalid request parameter :session-id: non-empty string"
                                                   {:error-code "request/invalid-params"})))
                                 ;; ensure-session-loaded-in! updates shared ctx; re-scope to requested sid
                                 (let [source-session-id (focused-session-id ctx state)
                                       _    (session/ensure-session-loaded-in! ctx source-session-id sid)
                                       _    (set-focus-session-id! state sid)
                                       sd   (ss/get-session-data-in ctx sid)
                                       msgs (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                                   (emit-event! emit-frame! state {:event "session/resumed"
                                                                   :id (:id request)
                                                                   :data {:session-id   (:session-id sd)
                                                                          :session-file (:session-file sd)
                                                                          :message-count (count msgs)}})
                                   (emit-event! emit-frame! state {:event "session/rehydrated"
                                                                   :id (:id request)
                                                                   :data {:messages msgs
                                                                          :tool-calls {}
                                                                          :tool-order []}})
                                   (emit-event! emit-frame! state {:event "session/updated"
                                                                   :id (:id request)
                                                                   :data (session-updated-payload ctx sid)})
                                   (emit-event! emit-frame! state {:event "footer/updated"
                                                                   :id (:id request)
                                                                   :data (footer-updated-payload ctx sid)})
                                   (emit-event! emit-frame! state {:event "context/updated"
                                                                   :id (:id request)
                                                                   :data (context-updated-payload ctx state)})
                                   (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                          :session-file (:session-file sd)})))
                               (let [session-path (req-arg! request params :session-path #(and (string? %) (not (str/blank? %))) "non-empty path string")]
                                 (when-not (.exists (io/file session-path))
                                   (throw (ex-info "session file not found"
                                                   {:error-code "request/not-found"})))
                                 ;; resume-session-in! returns new session data; update focus explicitly
                                 (let [current-sid (focused-session-id ctx state)
                                       sd          (session/resume-session-in! ctx current-sid session-path)
                                       sid         (:session-id sd)
                                       _           (set-focus-session-id! state sid)
                                       msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                                   (emit-event! emit-frame! state {:event "session/resumed"
                                                                   :id (:id request)
                                                                   :data {:session-id   (:session-id sd)
                                                                          :session-file (:session-file sd)
                                                                          :message-count (count msgs)}})
                                   (emit-event! emit-frame! state {:event "session/rehydrated"
                                                                   :id (:id request)
                                                                   :data {:messages msgs
                                                                          :tool-calls {}
                                                                          :tool-order []}})
                                   (emit-event! emit-frame! state {:event "session/updated"
                                                                   :id (:id request)
                                                                   :data (session-updated-payload ctx sid)})
                                   (emit-event! emit-frame! state {:event "footer/updated"
                                                                   :id (:id request)
                                                                   :data (footer-updated-payload ctx sid)})
                                   (emit-event! emit-frame! state {:event "context/updated"
                                                                   :id (:id request)
                                                                   :data (context-updated-payload ctx state)})
                                   (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                          :session-file (:session-file sd)}))))

                             "list_sessions"
                             (response-frame (:id request) op true {:active-session-id (focused-session-id ctx state)
                                                                    :sessions (ss/list-context-sessions-in ctx)})

                             "fork"
                             (let [entry-id          (req-arg! request params :entry-id #(and (string? %) (not (str/blank? %))) "non-empty entry id")
                                   parent-session-id (focused-session-id ctx state)
                                   sd               (session/fork-session-in! ctx parent-session-id entry-id)
                                   sid              (:session-id sd)
                                   _                (set-focus-session-id! state sid)]
                               (emit-event! emit-frame! state {:event "context/updated"
                                                               :id (:id request)
                                                               :data (context-updated-payload ctx state sid)})
                               (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                      :session-file (:session-file sd)}))

                             "set_session_name"
                             (let [name   (req-arg! request params :name #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   sid    (focused-session-id ctx state)
                                   result (session/set-session-name-in! ctx sid name)]
                               (response-frame (:id request) op true {:session-name (:session-name result)}))

                             "set_model"
                             (let [provider (req-arg! request params :provider #(or (keyword? %) (string? %)) "string or keyword")
                                   model-id (req-arg! request params :model-id #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   resolved (resolve-model provider model-id)]
                               (when-not resolved
                                 (throw (ex-info "unknown model"
                                                 {:error-code "request/unknown-model"})))
                               (let [provider-str (name (:provider resolved))
                                     sid         (focused-session-id ctx state)
                                     model       {:provider provider-str
                                                  :id (:id resolved)
                                                  :reasoning (:supports-reasoning resolved)}
                                     result      (session/set-model-in! ctx sid model)]
                                 (response-frame (:id request) op true {:model {:provider (:provider (:model result))
                                                                                :id (:id (:model result))}})))

                             "cycle_model"
                             (let [direction (case (:direction params)
                                               "prev" :backward
                                               "next" :forward
                                               :backward :backward
                                               :forward :forward
                                               :forward)
                                   sid       (focused-session-id ctx state)
                                   sd        (session/cycle-model-in! ctx sid direction)]
                               (response-frame (:id request) op true {:model (some-> (:model sd)
                                                                                     (select-keys [:provider :id]))}))

                             "set_thinking_level"
                             (let [level (req-arg! request params :level some? "keyword, string, or integer")
                                   sid   (focused-session-id ctx state)
                                   level* (cond
                                            (keyword? level) level
                                            (string? level)  (keyword level)
                                            :else            level)
                                   result (session/set-thinking-level-in! ctx sid level*)]
                               (response-frame (:id request) op true {:thinking-level (:thinking-level result)}))

                             "cycle_thinking_level"
                             (let [sid (focused-session-id ctx state)
                                   sd  (session/cycle-thinking-level-in! ctx sid)]
                               (response-frame (:id request) op true {:thinking-level (:thinking-level sd)}))

                             "compact"
                             (let [sid    (focused-session-id ctx state)
                                   result (session/manual-compact-in! ctx sid (:custom-instructions params))]
                               (response-frame (:id request) op true {:compacted (boolean result)
                                                                      :summary   result}))

                             "set_auto_compaction"
                             (let [enabled (req-arg! request params :enabled boolean? "boolean")
                                   sid     (focused-session-id ctx state)
                                   result  (session/set-auto-compaction-in! ctx sid enabled)]
                               (response-frame (:id request) op true {:enabled (:auto-compaction-enabled result)}))

                             "set_auto_retry"
                             (let [enabled (req-arg! request params :enabled boolean? "boolean")
                                   sid     (focused-session-id ctx state)
                                   result  (session/set-auto-retry-in! ctx sid enabled)]
                               (response-frame (:id request) op true {:enabled (:auto-retry-enabled result)}))

                             "get_state"
                             (let [sid (focused-session-id ctx state)]
                               (response-frame (:id request) op true {:state (ss/get-session-data-in ctx sid)}))

                             "get_messages"
                             (let [sid (focused-session-id ctx state)]
                               (response-frame (:id request) op true {:messages (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))}))

                             "get_session_stats"
                             (let [sid (focused-session-id ctx state)]
                               (response-frame (:id request) op true {:stats (session/diagnostics-in ctx sid)}))

                             "subscribe"
                             (let [topics             (or (:topics params) [])
                                   _                  (when-not (sequential? topics)
                                                        (throw (ex-info "subscribe :topics must be sequential"
                                                                        {:error-code "request/invalid-params"})))
                                   topics*            (->> topics (filter #(contains? event-topics %)) set)
                                   ui-topic-request?  (some extension-ui-topic? topics*)]
                               (swap! state update :subscribed-topics (fnil into #{}) topics*)
                               (when (or (empty? (:subscribed-topics @state))
                                         (contains? (:subscribed-topics @state) "assistant/message"))
                                 (maybe-start-external-event-loop! ctx emit-frame! state))
             ;; Re-register extension run-fn with emit-frame! so extension-initiated
             ;; agent runs (e.g. PSL) stream deltas + final message to the RPC client.
                               (register-rpc-extension-run-fn! ctx emit-frame! state)
                               (when ui-topic-request?
                                 (maybe-start-ui-watch-loop! ctx emit-frame! state)
                                 (emit-ui-snapshot-events! emit-frame!
                                                           state
                                                           {}
                                                           (or (ui-snapshot ctx) {})))
             ;; Emit current session/footer/context snapshots immediately on subscription
             ;; so frontends render baseline status without waiting for prompt activity.
                               (emit-event! emit-frame! state {:event "session/updated"
                                                               :id (:id request)
                                                               :data (session-updated-payload ctx (focused-session-id ctx state))})
                               (emit-event! emit-frame! state {:event "footer/updated"
                                                               :id (:id request)
                                                               :data (footer-updated-payload ctx (focused-session-id ctx state))})
                               (emit-event! emit-frame! state {:event "context/updated"
                                                               :id (:id request)
                                                               :data (context-updated-payload ctx state)})
                               (response-frame (:id request) op true {:subscribed (->> (:subscribed-topics @state) sort vec)}))

                             "unsubscribe"
                             (let [topics  (or (:topics params) [])
                                   _       (when-not (sequential? topics)
                                             (throw (ex-info "unsubscribe :topics must be sequential"
                                                             {:error-code "request/invalid-params"})))
                                   topics* (->> topics (filter string?) set)]
                               (if (seq topics*)
                                 (swap! state update :subscribed-topics (fn [s] (apply disj (or s #{}) topics*)))
                                 (swap! state assoc :subscribed-topics #{}))
                               (response-frame (:id request) op true {:subscribed (->> (:subscribed-topics @state) sort vec)}))

                             "resolve_dialog"
                             (handle-resolve-dialog! ctx request params)

                             "cancel_dialog"
                             (handle-cancel-dialog! ctx request params)

                             (error-frame {:id            (:id request)
                                           :op            op
                                           :error-code    "request/op-not-supported"
                                           :error-message (str "unsupported op: " op)
                                           :data          {:supported-ops supported-rpc-ops}})))]
         (cond
           (contains? targetable-rpc-ops op)
           (with-request-session! ctx request state dispatch-op)

           :else
           (dispatch-op ctx)))
       (catch Throwable e
         (exception->error-frame request e))))))

(defn run-stdio-loop!
  "Run an EDN-lines RPC loop.

   Options:
   - :in               java.io.Reader (default *in*)
   - :out              java.io.Writer (default *out*)
   - :err              java.io.Writer (default *err*)
   - :request-handler  (fn [request emit-frame! state] -> frame | [frame*] | nil)
   - :state            mutable transport state passed to request-handler
   - :trace-fn         optional (fn [{:dir :in|:out :raw string :frame map :parse-error string?}])

   State keys:
   - :ready?                   handshake readiness gate (default false)
   - :pending                  map of request-id -> op for in-flight requests
   - :max-pending-requests     guard limit (default 64)"
  [{:keys [in out err request-handler state trace-fn]
    :or   {in *in*
           out *out*
           err *err*
           request-handler (fn [request _emit! _state]
                             (default-request-handler request))
           state (atom {})}}]
  (let [reader      (java.io.BufferedReader. in)
        emit-frame! (make-frame-writer out trace-fn)]
    (swap! state #(merge {:ready? false
                          :pending {}
                          :max-pending-requests default-max-pending-requests
                          :subscribed-topics #{}
                          :event-seq 0
                          :inflight-futures []}
                         %
                         {:err err}))
    (let [emit-tracked! (make-tracked-emitter emit-frame! state)
          emit-error!   (fn [error-code error-message]
                          (emit-frame! (error-frame {:error-code error-code
                                                     :error-message error-message})))]
      (try
        (doseq [line (line-seq reader)]
          (if (str/blank? line)
            (do
              (safe-trace! trace-fn {:dir :in
                                     :raw line
                                     :parse-error "empty frame"})
              (emit-error! "transport/invalid-frame" "empty frame"))
            (let [{:keys [ok error]} (parse-request-line line)]
              (if error
                (do
                  (safe-trace! trace-fn {:dir :in
                                         :raw line
                                         :frame error
                                         :parse-error (:error-message error)})
                  (emit-frame! error))
                (do
                  (safe-trace! trace-fn {:dir :in
                                         :raw line
                                         :frame ok})
                  (process-request! ok {:state           state
                                        :request-handler request-handler
                                        :emit-tracked!   emit-tracked!}))))))
        (finally
          (stop-all-managed-threads! state))))))

(defn start-runtime!
  "Run EDN-lines RPC using an already-bootstrapped agent-session runtime context.

   The caller provides:
   - `session-ctx-factory` => (fn [] {:ctx .. :oauth-ctx .. :cwd .. :session-id ..})
   - `bootstrap-fn!`       => (fn [ctx session-id] ...)
   - `on-new-session!`     => (fn [source-session-id] {:session-id .. :agent-messages .. :messages .. :tool-calls .. :tool-order ..})

   In rpc-edn mode, stdout is protocol-only. Direct System/out writes are rebound to stderr."
  [{:keys [model-key
           memory-runtime-opts
           session-config
           rpc-trace-file
           session-state*
           nrepl-runtime
           resolve-model
           session-ctx-factory
           bootstrap-fn!
           on-new-session!]
    :or {memory-runtime-opts {}
         session-config {}
         rpc-trace-file nil}}]
  (let [protocol-out       *out*
        original-systemout System/out]
    (try
      (System/setOut (java.io.PrintStream. System/err true))
      (binding [*out* *err*]
        (let [ai-model      (resolve-model model-key)
              {:keys [ctx oauth-ctx session-id]} (session-ctx-factory ai-model session-config)
              _             (bootstrap-fn! ctx session-id ai-model memory-runtime-opts)
              trace-file*   (when-not (str/blank? rpc-trace-file)
                              rpc-trace-file)
              _             (dispatch/dispatch! ctx :session/set-rpc-trace {:session-id session-id :enabled? (boolean trace-file*) :file trace-file*} {:origin :core})
              trace-lock    (Object.)
              trace-fn      (fn [{:keys [dir raw frame parse-error]}]
                              (try
                                (let [cfg      (or (sa/rpc-trace-state-in ctx) {})
                                      enabled? (boolean (:enabled? cfg))
                                      path     (:file cfg)]
                                  (when (and enabled?
                                             (string? path)
                                             (not (str/blank? path)))
                                    (io/make-parents path)
                                    (let [entry (cond-> {:ts (str (java.time.Instant/now))
                                                         :dir dir
                                                         :raw raw}
                                                  (map? frame) (assoc :frame frame)
                                                  (and (string? parse-error)
                                                       (not (str/blank? parse-error)))
                                                  (assoc :parse-error parse-error))]
                                      (locking trace-lock
                                        (spit path (str (pr-str entry) "
") :append true)))))
                                (catch Throwable _
                                  nil)))
              focus-atom    (atom session-id)
              state         (atom {:handshake-server-info-fn (fn [] (assoc (session->handshake-server-info ctx @focus-atom)
                                                                           :ui-type :emacs))
                                   :handshake-context-updated-payload-fn (fn [] {:active-session-id @focus-atom
                                                                                 :sessions []})
                                   :focus-session-id* focus-atom
                                   :subscribed-topics #{}
                                   :rpc-ai-model ai-model
                                   :on-new-session! (fn []
                                                      (let [source-session-id @focus-atom
                                                            result             (on-new-session! source-session-id)]
                                                        (reset! focus-atom (:session-id result))
                                                        result))})
              request-handler (make-session-request-handler ctx)]
          (reset! session-state* {:ctx ctx
                                  :ai-model ai-model
                                  :oauth-ctx oauth-ctx
                                  :nrepl-runtime-atom nrepl-runtime})
          (run-stdio-loop! {:request-handler request-handler
                            :state state
                            :out protocol-out
                            :trace-fn trace-fn})))
      (finally
        (System/setOut original-systemout)))))
