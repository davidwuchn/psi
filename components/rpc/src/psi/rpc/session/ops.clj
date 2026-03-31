(ns psi.rpc.session.ops
  "Small op handlers and router delegates for RPC session workflows."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [error-frame protocol-version response-frame supported-rpc-ops]]))

(defn handle-ping
  [request]
  (response-frame (:id request) "ping" true {:pong true :protocol-version protocol-version}))

(defn handle-query-eql
  [{:keys [ctx request params parse-query-edn!]}]
  (let [query-str  (get params :query)
        _          (when-not (and (string? query-str) (not (str/blank? query-str)))
                     (throw (ex-info "invalid request parameter :query: non-empty EDN string"
                                     {:error-code "request/invalid-params"})))
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
    (response-frame (:id request) (:op request) true {:result result})))

(defn handle-prompt-while-streaming
  [{:keys [ctx request params state]}]
  (let [message   (get params :message)
        _         (when-not (and (string? message) (not (str/blank? message)))
                    (throw (ex-info "invalid request parameter :message: non-empty string"
                                    {:error-code "request/invalid-params"})))
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
        (response-frame (:id request) (:op request) true {:accepted accepted?
                                                          :behavior (name behavior)}))

      "queue"
      (let [{:keys [accepted? behavior]} (session/queue-while-streaming-in! ctx sid message :queue)]
        (response-frame (:id request) (:op request) true {:accepted accepted?
                                                          :behavior (name behavior)}))

      (throw (ex-info "prompt_while_streaming :behavior must be \"steer\" or \"queue\""
                      {:error-code "request/invalid-params"})))))

(defn handle-steer
  [{:keys [ctx request params state]}]
  (let [message (get params :message)
        _       (when-not (and (string? message) (not (str/blank? message)))
                  (throw (ex-info "invalid request parameter :message: non-empty string"
                                  {:error-code "request/invalid-params"})))
        sid     (events/focused-session-id ctx state)]
    (session/steer-in! ctx sid message)
    (response-frame (:id request) (:op request) true {:accepted true})))

(defn handle-follow-up
  [{:keys [ctx request params state]}]
  (let [message (get params :message)
        _       (when-not (and (string? message) (not (str/blank? message)))
                  (throw (ex-info "invalid request parameter :message: non-empty string"
                                  {:error-code "request/invalid-params"})))
        sid     (events/focused-session-id ctx state)]
    (session/follow-up-in! ctx sid message)
    (response-frame (:id request) (:op request) true {:accepted true})))

(defn handle-abort
  [{:keys [ctx request state]}]
  (let [sid (events/focused-session-id ctx state)]
    (session/abort-in! ctx sid)
    (response-frame (:id request) (:op request) true {:accepted true})))

(defn handle-interrupt
  [{:keys [ctx request state]}]
  (let [sid (events/focused-session-id ctx state)
        {:keys [accepted? pending? dropped-steering-text]}
        (session/request-interrupt-in! ctx sid)]
    (response-frame (:id request) (:op request) true
                    {:accepted accepted?
                     :pending pending?
                     :dropped-steering-text (or dropped-steering-text "")})))

(defn handle-list-sessions
  [{:keys [ctx request state]}]
  (response-frame (:id request) (:op request) true {:active-session-id (events/focused-session-id ctx state)
                                                    :sessions (ss/list-context-sessions-in ctx)}))

(defn handle-set-session-name
  [{:keys [ctx request params state]}]
  (let [name   (get params :name)
        _      (when-not (and (string? name) (not (str/blank? name)))
                 (throw (ex-info "invalid request parameter :name: non-empty string"
                                 {:error-code "request/invalid-params"})))
        sid    (events/focused-session-id ctx state)
        result (session/set-session-name-in! ctx sid name)]
    (response-frame (:id request) (:op request) true {:session-name (:session-name result)})))

(defn handle-set-model
  [{:keys [ctx request params state resolve-model]}]
  (let [provider (get params :provider)
        _        (when-not (or (keyword? provider) (string? provider))
                   (throw (ex-info "invalid request parameter :provider: string or keyword"
                                   {:error-code "request/invalid-params"})))
        model-id (get params :model-id)
        _        (when-not (and (string? model-id) (not (str/blank? model-id)))
                   (throw (ex-info "invalid request parameter :model-id: non-empty string"
                                   {:error-code "request/invalid-params"})))
        resolved (resolve-model provider model-id)]
    (when-not resolved
      (throw (ex-info "unknown model"
                      {:error-code "request/unknown-model"})))
    (let [provider-str (name (:provider resolved))
          sid          (events/focused-session-id ctx state)
          model        {:provider provider-str
                        :id (:id resolved)
                        :reasoning (:supports-reasoning resolved)}
          result       (session/set-model-in! ctx sid model)]
      (response-frame (:id request) (:op request) true {:model {:provider (:provider (:model result))
                                                                :id (:id (:model result))}}))))

(defn handle-cycle-model
  [{:keys [ctx request params state]}]
  (let [direction (case (:direction params)
                    "prev" :backward
                    "next" :forward
                    :backward :backward
                    :forward :forward
                    :forward)
        sid       (events/focused-session-id ctx state)
        sd        (session/cycle-model-in! ctx sid direction)]
    (response-frame (:id request) (:op request) true {:model (some-> (:model sd)
                                                                     (select-keys [:provider :id]))})))

(defn handle-set-thinking-level
  [{:keys [ctx request params state]}]
  (let [level   (:level params)
        _       (when-not (some? level)
                  (throw (ex-info "invalid request parameter :level: keyword, string, or integer"
                                  {:error-code "request/invalid-params"})))
        sid     (events/focused-session-id ctx state)
        level*  (cond
                  (keyword? level) level
                  (string? level)  (keyword level)
                  :else            level)
        result  (session/set-thinking-level-in! ctx sid level*)]
    (response-frame (:id request) (:op request) true {:thinking-level (:thinking-level result)})))

(defn handle-cycle-thinking-level
  [{:keys [ctx request state]}]
  (let [sid (events/focused-session-id ctx state)
        sd  (session/cycle-thinking-level-in! ctx sid)]
    (response-frame (:id request) (:op request) true {:thinking-level (:thinking-level sd)})))

(defn handle-compact
  [{:keys [ctx request params state]}]
  (let [sid    (events/focused-session-id ctx state)
        result (session/manual-compact-in! ctx sid (:custom-instructions params))]
    (response-frame (:id request) (:op request) true {:compacted (boolean result)
                                                      :summary result})))

(defn handle-set-auto-compaction
  [{:keys [ctx request params state]}]
  (let [enabled (:enabled params)
        _       (when-not (boolean? enabled)
                  (throw (ex-info "invalid request parameter :enabled: boolean"
                                  {:error-code "request/invalid-params"})))
        sid     (events/focused-session-id ctx state)
        result  (session/set-auto-compaction-in! ctx sid enabled)]
    (response-frame (:id request) (:op request) true {:enabled (:auto-compaction-enabled result)})))

(defn handle-set-auto-retry
  [{:keys [ctx request params state]}]
  (let [enabled (:enabled params)
        _       (when-not (boolean? enabled)
                  (throw (ex-info "invalid request parameter :enabled: boolean"
                                  {:error-code "request/invalid-params"})))
        sid     (events/focused-session-id ctx state)
        result  (session/set-auto-retry-in! ctx sid enabled)]
    (response-frame (:id request) (:op request) true {:enabled (:auto-retry-enabled result)})))

(defn handle-get-state
  [{:keys [ctx request state]}]
  (let [sid (events/focused-session-id ctx state)]
    (response-frame (:id request) (:op request) true {:state (ss/get-session-data-in ctx sid)})))

(defn handle-get-messages
  [{:keys [ctx request state]}]
  (let [sid (events/focused-session-id ctx state)]
    (response-frame (:id request) (:op request) true {:messages (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))})))

(defn handle-get-session-stats
  [{:keys [ctx request state]}]
  (let [sid (events/focused-session-id ctx state)]
    (response-frame (:id request) (:op request) true {:stats (session/diagnostics-in ctx sid)})))

(defn handle-subscribe
  [{:keys [ctx request params state session-deps maybe-start-external-event-loop! register-rpc-extension-run-fn! maybe-start-ui-watch-loop!]}]
  (let [topics            (or (:topics params) [])
        _                 (when-not (sequential? topics)
                            (throw (ex-info "subscribe :topics must be sequential"
                                            {:error-code "request/invalid-params"})))
        topics*           (->> topics (filter #(contains? events/event-topics %)) set)
        ui-topic-request? (some events/extension-ui-topic? topics*)]
    (rpc.state/subscribe-topics! state topics*)
    (when (or (empty? (rpc.state/subscribed-topics state))
              (contains? (rpc.state/subscribed-topics state) "assistant/message"))
      (maybe-start-external-event-loop! ctx (:emit-frame! request) state))
    (register-rpc-extension-run-fn! ctx (:emit-frame! request) state session-deps)
    (when ui-topic-request?
      (maybe-start-ui-watch-loop! ctx (:emit-frame! request) state)
      (events/emit-ui-snapshot-events! (:emit-frame! request)
                                       state
                                       {}
                                       (or (events/ui-snapshot ctx) {})))
    (events/emit-event! (:emit-frame! request) state {:event "session/updated"
                                                      :id (:id request)
                                                      :data (events/session-updated-payload ctx (events/focused-session-id ctx state))})
    (events/emit-event! (:emit-frame! request) state {:event "footer/updated"
                                                      :id (:id request)
                                                      :data (events/footer-updated-payload ctx (events/focused-session-id ctx state))})
    (events/emit-event! (:emit-frame! request) state {:event "context/updated"
                                                      :id (:id request)
                                                      :data (events/context-updated-payload ctx state)})
    (response-frame (:id request) (:op request) true {:subscribed (->> (rpc.state/subscribed-topics state) sort vec)})))

(defn handle-unsubscribe
  [{:keys [request params state]}]
  (let [topics  (or (:topics params) [])
        _       (when-not (sequential? topics)
                  (throw (ex-info "unsubscribe :topics must be sequential"
                                  {:error-code "request/invalid-params"})))
        topics* (->> topics (filter string?) set)]
    (if (seq topics*)
      (rpc.state/unsubscribe-topics! state topics*)
      (rpc.state/clear-subscriptions! state))
    (response-frame (:id request) (:op request) true {:subscribed (->> (rpc.state/subscribed-topics state) sort vec)})))

(defn handle-op-not-supported
  [request]
  (error-frame {:id            (:id request)
                :op            (:op request)
                :error-code    "request/op-not-supported"
                :error-message (str "unsupported op: " (:op request))
                :data          {:supported-ops supported-rpc-ops}}))
