(ns psi.rpc.session
  "Session-bound RPC op routing, event emission, and async request handling."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.ai.models :as ai-models]
   [psi.rpc.events :as events]
   [psi.rpc.session.commands :as rpc.commands]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.session.frontend-actions :as frontend-actions]
   [psi.rpc.session.login :as login]
   [psi.rpc.session.navigation :as navigation]
   [psi.rpc.session.ops :as ops]
   [psi.rpc.session.streams :as streams]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [*request-session-id* default-session-id-in error-frame protocol-version response-frame supported-rpc-ops targetable-rpc-ops]]))

(defn- start-daemon-thread!
  "Start a daemon thread running f with an optional name. Returns the Thread."
  ([f] (start-daemon-thread! f nil))
  ([f thread-name]
   (doto (Thread. ^Runnable f)
     (.setDaemon true)
     (cond-> thread-name (.setName thread-name))
     (.start))))

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
  (let [active (get-in (events/ui-snapshot ctx) [:active-dialog])]
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
  ([ctx session-deps state]
   (current-ai-model ctx session-deps state nil))
  ([ctx {:keys [rpc-ai-model]} state session-id]
   (let [session-id* (or session-id
                         *request-session-id*
                         (events/focused-session-id ctx state))
         sd          (when session-id*
                       (ss/get-session-data-in ctx session-id*))]
     (or (when-let [provider (get-in sd [:model :provider])]
           (when-let [model-id (get-in sd [:model :id])]
             (resolve-model provider model-id)))
         rpc-ai-model))))

(defn- effective-sync-on-git-head-change?
  [{:keys [run-agent-loop-fn sync-on-git-head-change?]}]
  (if (= ::default sync-on-git-head-change?)
    (not (some? run-agent-loop-fn))
    (boolean sync-on-git-head-change?)))

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

(defn- register-rpc-extension-run-fn!
  "Re-register the extension run-fn with an emit-frame!-aware implementation.

   The default run-fn registered in main.clj has no progress-queue, so
   extension-initiated agent runs (e.g. PSL) produce no streaming events
   visible to the RPC client. This version creates a progress-queue, polls
   it in a background loop, and routes events to emit-frame! — giving the
   PSL response the same streaming visibility as a normal user prompt.

   Called once from the subscribe handler. Guard via RPC-local state so it is
   only set up once per session connection."
  [ctx emit-frame! state session-deps]
  (when-not (rpc.state/rpc-run-fn-registered? state)
    (rpc.state/mark-rpc-run-fn-registered! state)
    (let [session-id  (events/focused-session-id ctx state)
          ai-model-fn (fn [sid] (current-ai-model ctx session-deps state sid))
          run-fn      (fn [text _source]
                        (try
                          (loop [attempt 0]
                            (if (ss/idle-in? ctx session-id)
                              (let [{:keys [user-message]} (runtime/prepare-user-message-in! ctx session-id text nil)
                                    ai-model  (ai-model-fn session-id)
                                    api-key   (runtime/resolve-api-key-in ctx session-id ai-model)
                                    emit!     (emit/make-request-emitter emit-frame! state nil)
                                    progress-q (java.util.concurrent.LinkedBlockingQueue.)
                                    {:keys [stop? thread] :as progress-loop}
                                    (streams/start-progress-loop!
                                     {:start-daemon-thread! start-daemon-thread!
                                      :ctx ctx
                                      :state state
                                      :session-id session-id
                                      :emit! emit!
                                      :progress-q progress-q
                                      :thread-name "rpc-poll-loop"})
                                    result    (runtime/run-agent-loop-in!
                                               ctx session-id nil ai-model [user-message]
                                               {:api-key        api-key
                                                :progress-queue progress-q
                                                :sync-on-git-head-change? true})]
                                (streams/stop-progress-loop! {:stop? stop?
                                                              :thread thread
                                                              :progress-q progress-q
                                                              :emit! emit!})
                                (emit/emit-assistant-message! emit! result)
                                (emit/emit-session-snapshots! emit! ctx state session-id))
                              (when (< attempt 1200)
                                (Thread/sleep 250)
                                (recur (inc attempt)))))
                          (catch Exception e
                            (events/emit-event! emit-frame! state
                                         {:event "error"
                                          :data  {:error-code    "runtime/failed"
                                                  :error-message (or (ex-message e) "extension run failed")}}))))]
      (ext-rt/set-extension-run-fn-in! ctx session-id run-fn))))

(defn- maybe-start-ui-watch-loop!
  [ctx emit-frame! state]
  (when (and (some events/extension-ui-topic? (rpc.state/subscribed-topics state))
             (nil? (rpc.state/ui-watch-loop state)))
    (let [watch-loop (future
                       (binding [*out* (rpc.state/err-writer state)
                                 *err* (rpc.state/err-writer state)]
                         (loop [last-snap (or (events/ui-snapshot ctx) {})]
                           (let [current (or (events/ui-snapshot ctx) {})]
                             (events/emit-ui-snapshot-events! emit-frame! state last-snap current)
                             (Thread/sleep 50)
                             (recur current)))))]
      (rpc.state/set-ui-watch-loop! state watch-loop))))

(defn- maybe-start-external-event-loop!
  [ctx emit-frame! state]
  (when (and (:event-queue ctx)
             (nil? (rpc.state/external-event-loop state)))
    (let [session-id  (events/focused-session-id ctx state)
          event-queue (:event-queue ctx)
          loop-fut    (future
                        (binding [*out* (rpc.state/err-writer state)
                                  *err* (rpc.state/err-writer state)]
                          (loop []
                            (when-let [evt (.poll ^java.util.concurrent.LinkedBlockingQueue
                                                  event-queue
                                                  20
                                                  java.util.concurrent.TimeUnit/MILLISECONDS)]
                              (when (= :external-message (:type evt))
                                (let [message (:message evt)]
                                  (events/emit-event! emit-frame! state
                                                      {:event "assistant/message"
                                                       :data  (events/external-message->assistant-payload message)})
                                  (events/emit-event! emit-frame! state
                                                      {:event "session/updated"
                                                       :data  (events/session-updated-payload ctx session-id)})
                                  (events/emit-event! emit-frame! state
                                                      {:event "footer/updated"
                                                       :data  (events/footer-updated-payload ctx session-id)}))))
                            (recur))))]
      (rpc.state/set-external-event-loop! state loop-fut)
      ;; Emit an immediate footer snapshot after starting the loop so subscribers
      ;; always observe footer state even if the loop is stopped immediately after
      ;; a single external assistant message is processed.
      (events/emit-event! emit-frame! state
                          {:event "footer/updated"
                           :data  (events/footer-updated-payload ctx session-id)}))))

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
                        (when state (events/focus-session-id state))
                        (default-session-id-in ctx))]
     (binding [*request-session-id* session-id]
       (f ctx)))))

(defn- run-prompt-async!
  [ctx request emit-frame! state session-deps]
  (let [message      (get-in request [:params :message])
        images       (get-in request [:params :images])
        request-id   (:id request)
        session-id   (events/focused-session-id ctx state)
        _            (when-not session-id
                       (throw (ex-info "no target session available for prompt"
                                       {:error-code "request/not-found"})))
        run-loop-fn  (or (:run-agent-loop-fn session-deps) executor/run-agent-loop!)
        sync-on-git-head-change? (effective-sync-on-git-head-change? session-deps)
        on-new-session! (:on-new-session! session-deps)
        progress-q   (java.util.concurrent.LinkedBlockingQueue.)
        worker       (start-daemon-thread!
                      (fn []
                        (binding [*out* (rpc.state/err-writer state)
                                  *err* (rpc.state/err-writer state)]
                          (let [emit! (emit/make-request-emitter emit-frame! state request-id)
                                {:keys [stop? thread] :as progress-loop}
                                (streams/start-progress-loop!
                                 {:start-daemon-thread! start-daemon-thread!
                                  :ctx ctx
                                  :state state
                                  :session-id session-id
                                  :emit! emit!
                                  :progress-q progress-q
                                  :thread-name "rpc-progress-loop"})]
                            (try
                              (let [ai-model      (current-ai-model ctx session-deps state session-id)
                                    _             (when-not ai-model
                                                    (throw (ex-info "session model is not configured"
                                                                    {:error-code "request/invalid-params"})))
                                    oauth-ctx     (:oauth-ctx ctx)
                                    pending-login (login/pending-login-state ctx)
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
                                    (login/complete-pending-login! {:ctx ctx :state state :message message :emit! emit!})
                                    (emit/emit-session-snapshots! emit! ctx state session-id))

                                  (some? cmd-result)
                                 ;; Slash command matched — journal raw input and skip agent loop.
                                  (do
                                    (runtime/journal-user-message-in! ctx session-id message images)
                                    (if (= :login-start (:type cmd-result))
                                      (login/handle-login-start-command! {:ctx ctx :state state :emit-frame! emit-frame! :request-id request-id :cmd-result cmd-result :emit! emit! :start-daemon-thread! start-daemon-thread!})
                                      (do
                                        (when (= :new-session (:type cmd-result))
                                          (let [rehydrate (:rehydrate cmd-result)
                                                ;; Get new session-id from rehydrate (lifecycle or callback)
                                                new-sid (:session-id rehydrate)
                                                _       (when new-sid (events/set-focus-session-id! state new-sid))
                                                sd      (when new-sid (ss/get-session-data-in ctx new-sid))
                                                msgs    (or (:agent-messages rehydrate)
                                                            (when new-sid
                                                              (:messages (agent/get-data-in (ss/agent-ctx-in ctx new-sid)))))]
                                            (emit/emit-session-rehydration!
                                             emit!
                                             {:session-id (:session-id sd)
                                              :session-file (:session-file sd)
                                              :message-count (count msgs)
                                              :messages msgs
                                              :tool-calls (or (:tool-calls rehydrate) {})
                                              :tool-order (or (:tool-order rehydrate) [])})))
                                        (rpc.commands/handle-prompt-command-result! cmd-result emit!)))
                                    (emit/emit-session-snapshots! emit! ctx state session-id))

                                  :else
                                 ;; Not a command — run normal agent loop via shared runtime path.
                                  (let [_        (emit/emit-session-snapshots! emit! ctx state session-id)
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
                                    (streams/stop-progress-loop! {:stop? stop?
                                                                  :thread thread
                                                                  :progress-q progress-q
                                                                  :emit! emit!})
                                    (emit/emit-assistant-message! emit! result)
                                    (emit/emit-session-snapshots! emit! ctx state session-id))))
                              (catch Throwable t
                                (emit! "error" {:error-code "runtime/failed"
                                                :error-message (or (ex-message t) "prompt execution failed")
                                                :id request-id
                                                :op "prompt"})
                                (emit/emit-session-snapshots! emit! ctx state session-id))
                              (finally
                                (reset! stop? true)
                                (.join ^Thread thread 200)))))))]
    (rpc.state/add-inflight-future! state worker)
    (response-frame (:id request) "prompt" true {:accepted true})))

(defn make-session-request-handler
  "Create a canonical op router bound to an agent-session context.

   Returned fn signature matches `run-stdio-loop!` request-handler:
   (fn [request emit-frame! state] -> frame | [frame*] | nil)

   Runtime state mutations used by this handler:
   - subscribed topics / focus session / worker handles in RPC-local state

   OAuth pending-login for login_begin/login_complete and /login prompt flow
   is stored in the canonical session oauth projection, not in transport state.

   opts:
   - :rpc-ai-model fallback model when session model is absent
   - :on-new-session! optional callback used by /new command and new_session op
   - :run-agent-loop-fn optional executor override for prompt tests/runtime
   - :sync-on-git-head-change? optional policy flag; ::default => infer from custom run loop presence"
  ([ctx] (make-session-request-handler ctx {}))
  ([ctx session-deps]
   (let [session-deps (if (contains? session-deps :sync-on-git-head-change?)
                        session-deps
                        (assoc session-deps :sync-on-git-head-change? ::default))
         on-new-session! (:on-new-session! session-deps)]
     (fn [request emit-frame! state]
     (try
       (let [op     (:op request)
             params (params-map request)
             dispatch-op (fn [ctx]
                           (case op
                             "ping"
                             (ops/handle-ping request)

                             "query_eql"
                             (ops/handle-query-eql {:ctx ctx :request request :params params :parse-query-edn! parse-query-edn!})

                             "command"
                             (rpc.commands/run-command! {:ctx ctx
                                                         :request request
                                                         :emit-frame! emit-frame!
                                                         :state state
                                                         :session-deps session-deps
                                                         :current-ai-model current-ai-model
                                                         :start-daemon-thread! start-daemon-thread!
                                                         :login-handler login/handle-login-start-command!})

                             "frontend_action_result"
                             (frontend-actions/handle-frontend-action-result! {:ctx ctx :request (assoc request :emit-frame! emit-frame!) :params params :state state :resolve-model resolve-model})

                             "prompt"
                             (let [_message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")]
                               (run-prompt-async! ctx request emit-frame! state session-deps))

                             "prompt_while_streaming"
                             (ops/handle-prompt-while-streaming {:ctx ctx :request request :params params :state state})

                             "steer"
                             (ops/handle-steer {:ctx ctx :request request :params params :state state})

                             "follow_up"
                             (ops/handle-follow-up {:ctx ctx :request request :params params :state state})

                             "abort"
                             (ops/handle-abort {:ctx ctx :request request :state state})

                             "interrupt"
                             (ops/handle-interrupt {:ctx ctx :request request :state state})

                             "list_background_jobs"
                             (handle-list-background-jobs! ctx request params)

                             "inspect_background_job"
                             (handle-inspect-background-job! ctx request params)

                             "cancel_background_job"
                             (handle-cancel-background-job! ctx request params)

                             "login_begin"
                             (login/handle-login-begin! {:ctx ctx :request request :params params :state state :session-deps session-deps :current-ai-model current-ai-model})

                             "login_complete"
                             (login/handle-login-complete! {:ctx ctx :request request :params params :state state})

                             "new_session"
                             (navigation/handle-new-session! {:ctx ctx
                                                              :request (assoc request :emit-frame! emit-frame!)
                                                              :state state
                                                              :on-new-session! on-new-session!})

                             "switch_session"
                             (navigation/handle-switch-session! {:ctx ctx
                                                                 :request (assoc request :emit-frame! emit-frame!)
                                                                 :params params
                                                                 :state state})

                             "list_sessions"
                             (ops/handle-list-sessions {:ctx ctx :request request :state state})

                             "fork"
                             (navigation/handle-fork! {:ctx ctx
                                                       :request (assoc request :emit-frame! emit-frame!)
                                                       :params params
                                                       :state state})

                             "set_session_name"
                             (ops/handle-set-session-name {:ctx ctx :request request :params params :state state})

                             "set_model"
                             (ops/handle-set-model {:ctx ctx :request request :params params :state state :resolve-model resolve-model})

                             "cycle_model"
                             (ops/handle-cycle-model {:ctx ctx :request request :params params :state state})

                             "set_thinking_level"
                             (ops/handle-set-thinking-level {:ctx ctx :request request :params params :state state})

                             "cycle_thinking_level"
                             (ops/handle-cycle-thinking-level {:ctx ctx :request request :state state})

                             "compact"
                             (ops/handle-compact {:ctx ctx :request request :params params :state state})

                             "set_auto_compaction"
                             (ops/handle-set-auto-compaction {:ctx ctx :request request :params params :state state})

                             "set_auto_retry"
                             (ops/handle-set-auto-retry {:ctx ctx :request request :params params :state state})

                             "get_state"
                             (ops/handle-get-state {:ctx ctx :request request :state state})

                             "get_messages"
                             (ops/handle-get-messages {:ctx ctx :request request :state state})

                             "get_session_stats"
                             (ops/handle-get-session-stats {:ctx ctx :request request :state state})

                             "subscribe"
                             (ops/handle-subscribe {:ctx ctx :request (assoc request :emit-frame! emit-frame!) :params params :state state :session-deps session-deps :maybe-start-external-event-loop! maybe-start-external-event-loop! :register-rpc-extension-run-fn! register-rpc-extension-run-fn! :maybe-start-ui-watch-loop! maybe-start-ui-watch-loop!})

                             "unsubscribe"
                             (ops/handle-unsubscribe {:request request :params params :state state})

                             "resolve_dialog"
                             (handle-resolve-dialog! ctx request params)

                             "cancel_dialog"
                             (handle-cancel-dialog! ctx request params)

                             (ops/handle-op-not-supported request)))]
         (cond
           (contains? targetable-rpc-ops op)
           (with-request-session! ctx request state dispatch-op)

           :else
           (dispatch-op ctx)))
       (catch Throwable e
         (exception->error-frame request e)))))))
