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
   [psi.rpc.session.emit :as emit]
   [psi.rpc.session.login :as login]
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
      (emit/emit-command-result! emit! {:type (if (= :new-session result-type)
                                                "new_session"
                                                (name result-type))
                                        :message (str (:message cmd-result))})

      :login-start
      (emit/emit-command-result! emit! {:type "login_start"
                                        :message (str "Login: " (get-in cmd-result [:provider :name])
                                                      " — open URL: " (:url cmd-result))
                                        :provider-name (get-in cmd-result [:provider :name])
                                        :login-url (:url cmd-result)
                                        :uses-callback-server (boolean (:uses-callback-server cmd-result))})

      :quit
      (emit/emit-command-result! emit! {:type "quit"})

      :resume
      (emit/emit-command-result! emit! {:type "text"
                                        :message "[/resume requires frontend action handling]"})

      :tree-open
      (emit/emit-command-result! emit! {:type "text"
                                        :message "[/tree requires frontend action handling]"})

      :tree-switch
      (emit/emit-command-result! emit! {:type "session_switch"
                                        :session-id (:session-id cmd-result)})

      :session-switch
      (emit/emit-command-result! emit! {:type "session_switch"
                                   :session-id (:session-id cmd-result)})

      :frontend-action
      (emit/emit-frontend-action-request! emit! request-id cmd-result)

      :extension-cmd
      (let [output (try
                     (let [out (with-out-str
                                 ((:handler cmd-result) (:args cmd-result)))]
                       (if (str/blank? out)
                         "[extension command returned no output]"
                         out))
                     (catch Throwable e
                       (str "[extension command error: " (ex-message e) "]")))]
        (emit/emit-command-result! emit! {:type "text" :message output}))

      ;; Unknown result type — emit a fallback
      (emit/emit-command-result! emit! {:type "text"
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
                        (when state (events/focus-session-id state))
                        (default-session-id-in ctx))]
     (binding [*request-session-id* session-id]
       (f ctx)))))

(defn- run-command!
  [ctx request emit-frame! state session-deps]
  (let [text       (req-arg! request (params-map request) :text #(and (string? %) (not (str/blank? %))) "non-empty string")
        request-id (:id request)
        emit!      (emit/make-request-emitter emit-frame! state request-id)
        ai-model   (current-ai-model ctx session-deps state)
        oauth-ctx  (:oauth-ctx ctx)
        trimmed    (str/trim text)
        session-id (events/focused-session-id ctx state)
        cmd-result (commands/dispatch-in ctx session-id text {:oauth-ctx oauth-ctx
                                                              :ai-model ai-model
                                                              :supports-session-tree? false
                                                              :on-new-session! (:on-new-session! session-deps)})]
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
          (emit/emit-command-result! emit! {:type "text"
                                            :message (str "Session file not found: " session-path)})
          (let [current-sid session-id
                sd          (session/resume-session-in! ctx current-sid session-path)
                sid         (:session-id sd)
                _           (events/set-focus-session-id! state sid)
                msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
            (emit/emit-session-rehydration!
             emit!
             {:session-id sid
              :session-file (:session-file sd)
              :message-count (count msgs)
              :messages msgs
              :tool-calls {}
              :tool-order []}))))

      (= trimmed "/tree")
      (let [active-id  (events/focus-session-id state)
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
            active-id  (events/focus-session-id state)
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
          (emit/emit-command-result! emit! {:type "text"
                                            :message (str "Session not found in context: " arg)})

          (= sid active-id)
          (emit/emit-command-result! emit! {:type "text"
                                            :message (str "Already active session: " sid)})

          :else
          (do
            (session/ensure-session-loaded-in! ctx active-id sid)
            (events/set-focus-session-id! state sid)
            (let [sd   (ss/get-session-data-in ctx sid)
                  msgs (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
              (emit/emit-session-rehydration!
               emit!
               {:session-id (:session-id sd)
                :session-file (:session-file sd)
                :message-count (count msgs)
                :messages msgs
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
        (login/handle-login-start-command! {:ctx ctx :state state :emit-frame! emit-frame! :request-id request-id :cmd-result cmd-result :emit! emit! :start-daemon-thread! start-daemon-thread!})
        (do
          (when (= :new-session (:type cmd-result))
            (let [rehydrate (:rehydrate cmd-result)
                  ;; Get new session-id from rehydrate (lifecycle or callback)
                  new-sid   (:session-id rehydrate)
                  _         (when new-sid (events/set-focus-session-id! state new-sid))
                  sd        (when new-sid (ss/get-session-data-in ctx new-sid))
                  msgs      (or (:agent-messages rehydrate)
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
          (handle-command-result! request-id cmd-result emit!)))

      :else
      (emit/emit-command-result! emit! {:type "text"
                                   :message (str "[not a command] " text)}))
    (emit/emit-session-snapshots! emit! ctx state session-id {:context? true})
    (response-frame (:id request) "command" true {:accepted true
                                                  :handled true})))

(defn- handle-frontend-action-result!
  [ctx request emit-frame! state]
  (let [params      (params-map request)
        request-id  (req-arg! request params :request-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        action-name (req-arg! request params :action-name #(and (string? %) (not (str/blank? %))) "non-empty string")
        status      (req-arg! request params :status #(and (string? %) (not (str/blank? %))) "non-empty string")
        value       (:value params)
        emit!       (emit/make-request-emitter emit-frame! state (:id request))
        session-id  (events/focused-session-id ctx state)]
    (cond
      (= status "cancelled")
      (do
        (emit/emit-command-result! emit! {:type "text"
                                          :message (str "Cancelled " action-name ".")})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      (= status "failed")
      (do
        (emit/emit-command-result! emit! {:type "error"
                                          :message (or (:error-message params)
                                                       (str "Frontend action failed: " action-name))})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      :else
      (do
        (case action-name
          "resume-selector"
          (when (string? value)
            (when (.exists (io/file value))
              (let [current-sid session-id
                    sd          (session/resume-session-in! ctx current-sid value)
                    sid         (:session-id sd)
                    _           (events/set-focus-session-id! state sid)
                    msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                (emit/emit-session-rehydration!
                 emit!
                 {:session-id sid
                  :session-file (:session-file sd)
                  :message-count (count msgs)
                  :messages msgs
                  :tool-calls {}
                  :tool-order []})
                (emit/emit-context-updated! emit! ctx state))))

          "context-session-selector"
          (when (string? value)
            (session/ensure-session-loaded-in! ctx session-id value)
            (let [_    (events/set-focus-session-id! state value)
                  sd   (ss/get-session-data-in ctx value)
                  msgs (:messages (agent/get-data-in (ss/agent-ctx-in ctx value)))]
              (emit/emit-session-rehydration!
               emit!
               {:session-id (:session-id sd)
                :session-file (:session-file sd)
                :message-count (count msgs)
                :messages msgs
                :tool-calls {}
                :tool-order []})
              (emit/emit-context-updated! emit! ctx state)))

          "model-picker"
          (when (map? value)
            (let [provider (or (:provider value) (get value "provider"))
                  model-id (or (:id value) (get value "id"))
                  resolved (resolve-model provider model-id)]
              (when resolved
                (let [provider-str (name (:provider resolved))
                      model {:provider provider-str :id (:id resolved) :reasoning (:supports-reasoning resolved)}]
                  (session/set-model-in! ctx session-id model)
                  (emit/emit-command-result! emit! {:type "text"
                                                    :message (str "✓ Model set to " provider-str " " (:id resolved))})))))

          "thinking-picker"
          (when (string? value)
            (let [level  (keyword value)
                  result (session/set-thinking-level-in! ctx session-id level)]
              (emit/emit-command-result! emit! {:type "text"
                                                :message (str "✓ Thinking level set to " (name (:thinking-level result)))})))

          nil)
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request)
                        "frontend_action_result"
                        true
                        {:accepted true
                         :request-id request-id})))))

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
                                        (handle-prompt-command-result! cmd-result emit!)))
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
                             (run-command! ctx request emit-frame! state session-deps)

                             "frontend_action_result"
                             (handle-frontend-action-result! ctx request emit-frame! state)

                             "prompt"
                             (let [_message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")]
                               (run-prompt-async! ctx request emit-frame! state session-deps))

                             "prompt_while_streaming"
                             (let [message   (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   behavior* (let [behavior (:behavior params)]
                                               (cond
                                                 (nil? behavior)      "steer"
                                                 (keyword? behavior)  (name behavior)
                                                 (string? behavior)   behavior
                                                 :else                nil))
                                   sid       (events/focused-session-id ctx state)]
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
                                   sid     (events/focused-session-id ctx state)]
                               (session/steer-in! ctx sid message)
                               (response-frame (:id request) op true {:accepted true}))

                             "follow_up"
                             (let [message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   sid     (events/focused-session-id ctx state)]
                               (session/follow-up-in! ctx sid message)
                               (response-frame (:id request) op true {:accepted true}))

                             "abort"
                             (let [sid (events/focused-session-id ctx state)]
                               (session/abort-in! ctx sid)
                               (response-frame (:id request) op true {:accepted true}))

                             "interrupt"
                             (let [sid (events/focused-session-id ctx state)
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
                             (login/handle-login-begin! {:ctx ctx :request request :params params :state state :session-deps session-deps :current-ai-model current-ai-model})

                             "login_complete"
                             (login/handle-login-complete! {:ctx ctx :request request :params params :state state})

                             "new_session"
                             (let [new-session-fn    on-new-session!
                                   source-session-id (events/focused-session-id ctx state)
                                   [rehydrate new-sd]
                                   (if new-session-fn
                                     [(new-session-fn source-session-id) nil]
                                     (let [sd (session/new-session-in! ctx source-session-id {})]
                                       [{:agent-messages [] :messages [] :tool-calls {} :tool-order []} sd]))
                                   ;; Get new session-id from lifecycle return or rehydrate callback
                                   new-sid   (or (:session-id new-sd) (:session-id rehydrate))
                                   _         (events/set-focus-session-id! state new-sid)
                                   sd        (ss/get-session-data-in ctx new-sid)
                                   msgs      (or (:agent-messages rehydrate)
                                                 (:messages (agent/get-data-in (ss/agent-ctx-in ctx new-sid))))]
                               (let [emit! (emit/make-request-emitter emit-frame! state (:id request))]
                                 (emit/emit-session-rehydration!
                                  emit!
                                  {:session-id (:session-id sd)
                                   :session-file (:session-file sd)
                                   :message-count (count msgs)
                                   :messages msgs
                                   :tool-calls (or (:tool-calls rehydrate) {})
                                   :tool-order (or (:tool-order rehydrate) [])})
                                 (emit/emit-session-snapshots! emit! ctx state new-sid {:context? true}))
                               (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                      :session-file (:session-file sd)}))

                             "switch_session"
                             (if-let [sid (:session-id params)]
                               (do
                                 (when-not (and (string? sid) (not (str/blank? sid)))
                                   (throw (ex-info "invalid request parameter :session-id: non-empty string"
                                                   {:error-code "request/invalid-params"})))
                                 ;; ensure-session-loaded-in! updates shared ctx; re-scope to requested sid
                                 (let [source-session-id (events/focused-session-id ctx state)
                                       _    (session/ensure-session-loaded-in! ctx source-session-id sid)
                                       _    (events/set-focus-session-id! state sid)
                                       sd   (ss/get-session-data-in ctx sid)
                                       msgs (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                                   (let [emit! (emit/make-request-emitter emit-frame! state (:id request))]
                                     (emit/emit-session-rehydration!
                                      emit!
                                      {:session-id (:session-id sd)
                                       :session-file (:session-file sd)
                                       :message-count (count msgs)
                                       :messages msgs
                                       :tool-calls {}
                                       :tool-order []})
                                     (emit/emit-session-snapshots! emit! ctx state sid {:context? true}))
                                   (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                          :session-file (:session-file sd)})))
                               (let [session-path (req-arg! request params :session-path #(and (string? %) (not (str/blank? %))) "non-empty path string")]
                                 (when-not (.exists (io/file session-path))
                                   (throw (ex-info "session file not found"
                                                   {:error-code "request/not-found"})))
                                 ;; resume-session-in! returns new session data; update focus explicitly
                                 (let [current-sid (events/focused-session-id ctx state)
                                       sd          (session/resume-session-in! ctx current-sid session-path)
                                       sid         (:session-id sd)
                                       _           (events/set-focus-session-id! state sid)
                                       msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                                   (let [emit! (emit/make-request-emitter emit-frame! state (:id request))]
                                     (emit/emit-session-rehydration!
                                      emit!
                                      {:session-id (:session-id sd)
                                       :session-file (:session-file sd)
                                       :message-count (count msgs)
                                       :messages msgs
                                       :tool-calls {}
                                       :tool-order []})
                                     (emit/emit-session-snapshots! emit! ctx state sid {:context? true}))
                                   (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                          :session-file (:session-file sd)}))))

                             "list_sessions"
                             (response-frame (:id request) op true {:active-session-id (events/focused-session-id ctx state)
                                                                    :sessions (ss/list-context-sessions-in ctx)})

                             "fork"
                             (let [entry-id          (req-arg! request params :entry-id #(and (string? %) (not (str/blank? %))) "non-empty entry id")
                                   parent-session-id (events/focused-session-id ctx state)
                                   sd               (session/fork-session-in! ctx parent-session-id entry-id)
                                   sid              (:session-id sd)
                                   _                (events/set-focus-session-id! state sid)]
                               (events/emit-event! emit-frame! state {:event "context/updated"
                                                               :id (:id request)
                                                               :data (events/context-updated-payload ctx state sid)})
                               (response-frame (:id request) op true {:session-id (:session-id sd)
                                                                      :session-file (:session-file sd)}))

                             "set_session_name"
                             (let [name   (req-arg! request params :name #(and (string? %) (not (str/blank? %))) "non-empty string")
                                   sid    (events/focused-session-id ctx state)
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
                                     sid         (events/focused-session-id ctx state)
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
                                   sid       (events/focused-session-id ctx state)
                                   sd        (session/cycle-model-in! ctx sid direction)]
                               (response-frame (:id request) op true {:model (some-> (:model sd)
                                                                                     (select-keys [:provider :id]))}))

                             "set_thinking_level"
                             (let [level (req-arg! request params :level some? "keyword, string, or integer")
                                   sid   (events/focused-session-id ctx state)
                                   level* (cond
                                            (keyword? level) level
                                            (string? level)  (keyword level)
                                            :else            level)
                                   result (session/set-thinking-level-in! ctx sid level*)]
                               (response-frame (:id request) op true {:thinking-level (:thinking-level result)}))

                             "cycle_thinking_level"
                             (let [sid (events/focused-session-id ctx state)
                                   sd  (session/cycle-thinking-level-in! ctx sid)]
                               (response-frame (:id request) op true {:thinking-level (:thinking-level sd)}))

                             "compact"
                             (let [sid    (events/focused-session-id ctx state)
                                   result (session/manual-compact-in! ctx sid (:custom-instructions params))]
                               (response-frame (:id request) op true {:compacted (boolean result)
                                                                      :summary   result}))

                             "set_auto_compaction"
                             (let [enabled (req-arg! request params :enabled boolean? "boolean")
                                   sid     (events/focused-session-id ctx state)
                                   result  (session/set-auto-compaction-in! ctx sid enabled)]
                               (response-frame (:id request) op true {:enabled (:auto-compaction-enabled result)}))

                             "set_auto_retry"
                             (let [enabled (req-arg! request params :enabled boolean? "boolean")
                                   sid     (events/focused-session-id ctx state)
                                   result  (session/set-auto-retry-in! ctx sid enabled)]
                               (response-frame (:id request) op true {:enabled (:auto-retry-enabled result)}))

                             "get_state"
                             (let [sid (events/focused-session-id ctx state)]
                               (response-frame (:id request) op true {:state (ss/get-session-data-in ctx sid)}))

                             "get_messages"
                             (let [sid (events/focused-session-id ctx state)]
                               (response-frame (:id request) op true {:messages (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))}))

                             "get_session_stats"
                             (let [sid (events/focused-session-id ctx state)]
                               (response-frame (:id request) op true {:stats (session/diagnostics-in ctx sid)}))

                             "subscribe"
                             (let [topics             (or (:topics params) [])
                                   _                  (when-not (sequential? topics)
                                                        (throw (ex-info "subscribe :topics must be sequential"
                                                                        {:error-code "request/invalid-params"})))
                                   topics*            (->> topics (filter #(contains? events/event-topics %)) set)
                                   ui-topic-request?  (some events/extension-ui-topic? topics*)]
                               (rpc.state/subscribe-topics! state topics*)
                               (when (or (empty? (rpc.state/subscribed-topics state))
                                         (contains? (rpc.state/subscribed-topics state) "assistant/message"))
                                 (maybe-start-external-event-loop! ctx emit-frame! state))
             ;; Re-register extension run-fn with emit-frame! so extension-initiated
             ;; agent runs (e.g. PSL) stream deltas + final message to the RPC client.
                               (register-rpc-extension-run-fn! ctx emit-frame! state session-deps)
                               (when ui-topic-request?
                                 (maybe-start-ui-watch-loop! ctx emit-frame! state)
                                 (events/emit-ui-snapshot-events! emit-frame!
                                                                   state
                                                                   {}
                                                                   (or (events/ui-snapshot ctx) {})))
             ;; Emit current session/footer/context snapshots immediately on subscription
             ;; so frontends render baseline status without waiting for prompt activity.
                               (events/emit-event! emit-frame! state {:event "session/updated"
                                                                       :id (:id request)
                                                                       :data (events/session-updated-payload ctx (events/focused-session-id ctx state))})
                               (events/emit-event! emit-frame! state {:event "footer/updated"
                                                                       :id (:id request)
                                                                       :data (events/footer-updated-payload ctx (events/focused-session-id ctx state))})
                               (events/emit-event! emit-frame! state {:event "context/updated"
                                                                       :id (:id request)
                                                                       :data (events/context-updated-payload ctx state)})
                               (response-frame (:id request) op true {:subscribed (->> (rpc.state/subscribed-topics state) sort vec)}))

                             "unsubscribe"
                             (let [topics  (or (:topics params) [])
                                   _       (when-not (sequential? topics)
                                             (throw (ex-info "unsubscribe :topics must be sequential"
                                                             {:error-code "request/invalid-params"})))
                                   topics* (->> topics (filter string?) set)]
                               (if (seq topics*)
                                 (rpc.state/unsubscribe-topics! state topics*)
                                 (rpc.state/clear-subscriptions! state))
                               (response-frame (:id request) op true {:subscribed (->> (rpc.state/subscribed-topics state) sort vec)}))

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
         (exception->error-frame request e)))))))
