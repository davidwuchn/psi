(ns psi.rpc.events
  "RPC event topics and payload projection helpers."
  (:require
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as ss]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [default-session-id-in event-frame protocol-version]]))

(def ^:private ui-state-path (ss/state-path :ui-state))

(defn- ui-state-map
  [ctx]
  (ss/get-state-value-in ctx ui-state-path))

(defn- visible-notifications
  ([ui-state] (visible-notifications ui-state 3))
  ([ui-state max-visible]
   (->> (:notifications ui-state)
        (remove :dismissed?)
        (take-last max-visible)
        vec)))

(defn ui-snapshot
  [ctx]
  (when-let [s (ui-state-map ctx)]
    {:dialog-queue-empty?    (and (nil? (get-in s [:dialog-queue :active]))
                                  (empty? (get-in s [:dialog-queue :pending])))
     :active-dialog          (when-let [d (get-in s [:dialog-queue :active])]
                               (dissoc d :promise))
     :pending-dialog-count   (count (get-in s [:dialog-queue :pending]))
     :widgets                (vec (vals (:widgets s)))
     :widget-specs           (vec (vals (:widget-specs s)))
     :statuses               (vec (vals (:statuses s)))
     :visible-notifications  (visible-notifications s)
     :tool-renderers         (mapv #(dissoc % :render-call-fn :render-result-fn)
                                   (vals (:tool-renderers s)))
     :message-renderers      (mapv #(dissoc % :render-fn)
                                   (vals (:message-renderers s)))}))


(defn session->handshake-server-info
  ([ctx]
   (session->handshake-server-info ctx (default-session-id-in ctx)))
  ([ctx session-id]
   (let [sd (ss/get-session-data-in ctx session-id)]
     {:protocol-version protocol-version
      :features         ["eql-graph" "eql-memory"]
      :session-id       (:session-id sd)
      :model-id         (get-in sd [:model :id])
      :thinking-level   (some-> (:thinking-level sd) name)})))

(def event-topics
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
  (let [subs (rpc.state/subscribed-topics state)]
    (or (empty? subs)
        (contains? subs topic))))

(defn- next-event-seq!
  [state]
  (rpc.state/next-event-seq! state))

(defn emit-event!
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

(defn session-updated-payload
  ([ctx]
   (session-updated-payload ctx (default-session-id-in ctx)))
  ([ctx session-id]
   (let [sd                   (ss/get-session-data-in ctx session-id)
         model                (:model sd)
         thinking-level       (:thinking-level sd)
         effective-effort     (effective-reasoning-effort model thinking-level)
         journal-messages     (persist/messages-from-entries-in ctx session-id)
         session-display-name (message-text/session-display-name (:session-name sd) journal-messages)]
     {:session-id                  (:session-id sd)
      :session-file                (:session-file sd)
      :session-name                (:session-name sd)
      :session-display-name        session-display-name
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

(defn focus-session-id
  [state]
  (rpc.state/focus-session-id state))

(defn set-focus-session-id!
  [state session-id]
  (rpc.state/set-focus-session-id! state session-id))

(defn context-updated-payload
  "Build the `context/updated` event payload from the current context session snapshot.

   Includes a synthetic single-session snapshot before the runtime context index
   has any real entries, so handshake/bootstrap surfaces still reflect the
   current live session.

   Each session slot includes :id :name :worktree-path :is-streaming :is-active
   :parent-session-id, :created-at, and :updated-at.
   Sessions are ordered by updated-at ascending (oldest first → stable tree order)."
  [ctx state session-id]
  (let [active-id        (focus-session-id state)
        sd               (ss/get-session-data-in ctx session-id)
        current-id       (:session-id sd)
        indexed-sessions (or (seq (ss/list-context-sessions-in ctx)) [])
        sessions*        (if (seq indexed-sessions)
                           (->> indexed-sessions
                                (sort-by (juxt :updated-at :session-id))
                                vec)
                           [(select-keys sd [:session-id :session-name :worktree-path :parent-session-id :created-at :updated-at])])
        current-display-name (message-text/session-display-name
                              (:session-name sd)
                              (:messages (agent/get-data-in (ss/agent-ctx-in ctx session-id))))
        active-id*       (or active-id current-id)
        slots            (mapv (fn [m]
                                 {:id                (:session-id m)
                                  :name              (:session-name m)
                                  :display-name      (or (:display-name m)
                                                         (if (= (:session-id m) current-id)
                                                           current-display-name
                                                           (message-text/short-display-text (:session-name m))) )
                                  :worktree-path     (:worktree-path m)
                                  :is-streaming      (boolean
                                                      (and (= (:session-id m) current-id)
                                                           (:is-streaming sd)))
                                  :is-active         (= (:session-id m) active-id*)
                                  :parent-session-id (:parent-session-id m)
                                  :created-at        (:created-at m)
                                  :updated-at        (:updated-at m)})
                               sessions*)]
    {:active-session-id active-id*
     :sessions          slots}))

(def footer-query
  [:psi.agent-session/cwd
   :psi.agent-session/git-branch
   :psi.agent-session/session-name
   :psi.agent-session/session-display-name
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
  [ctx session-id d]
  (let [cwd                  (or (:psi.agent-session/cwd d)
                                 (ss/effective-cwd-in ctx session-id)
                                 "")
        git-branch           (:psi.agent-session/git-branch d)
        session-display-name (or (:psi.agent-session/session-display-name d)
                                 (:psi.agent-session/session-name d))
        path0                (replace-home-with-tilde cwd)
        path1                (if (seq git-branch)
                               (str path0 " (" git-branch ")")
                               path0)]
    (if (seq session-display-name)
      (str path1 " • " session-display-name)
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

(defn footer-updated-payload
  ([ctx]
   (let [session-id (default-session-id-in ctx)
         d          (footer-data ctx)]
     {:path-line   (footer-path-line ctx session-id d)
      :stats-line  (footer-stats-line d)
      :status-line (footer-status-line (:psi.ui/statuses d))}))
  ([ctx session-id]
   (let [d (footer-data ctx session-id)]
     {:path-line   (footer-path-line ctx session-id d)
      :stats-line  (footer-stats-line d)
      :status-line (footer-status-line (:psi.ui/statuses d))})))

(defn progress-event->rpc-event
  [progress-event]
  (let [k (:event-kind progress-event)]
    (case k
      :text-delta
      {:event "assistant/delta"
       :data  {:session-id (:session-id progress-event)
               :text       (or (:text progress-event) "")}}

      :thinking-delta
      {:event "assistant/thinking-delta"
       :data  {:session-id (:session-id progress-event)
               :text       (or (:text progress-event) "")}}

      :tool-start
      {:event "tool/start"
       :data  (cond-> {:session-id (:session-id progress-event)
                       :tool-id    (:tool-id progress-event)
                       :tool-name  (:tool-name progress-event)}
                (some? (:arguments progress-event))   (assoc :arguments (:arguments progress-event))
                (some? (:parsed-args progress-event)) (assoc :parsed-args (:parsed-args progress-event)))}

      :tool-executing
      {:event "tool/executing"
       :data  (cond-> {:session-id (:session-id progress-event)
                       :tool-id    (:tool-id progress-event)
                       :tool-name  (:tool-name progress-event)}
                (some? (:arguments progress-event))   (assoc :arguments (:arguments progress-event))
                (some? (:parsed-args progress-event)) (assoc :parsed-args (:parsed-args progress-event)))}

      :tool-execution-update
      {:event "tool/update"
       :data  {:session-id  (:session-id progress-event)
               :tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      :tool-result
      {:event "tool/result"
       :data  {:session-id  (:session-id progress-event)
               :tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      nil)))

(defn emit-progress-queue!
  [progress-q emit!]
  (loop []
    (when-let [evt (.poll progress-q)]
      (when-let [{:keys [event data]} (progress-event->rpc-event evt)]
        (emit! event data))
      (recur))))

(defn ui-snapshot->events
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

(defn extension-ui-topic?
  [topic]
  (contains? extension-ui-topics topic))

(defn emit-ui-snapshot-events!
  [emit-frame! state previous current]
  (doseq [{:keys [event data]} (ui-snapshot->events (or previous {}) (or current {}))]
    (emit-event! emit-frame! state {:event event :data data})))

(defn assistant-content-text
  [content]
  (or (message-text/content-display-text content)
      (message-text/content-text content)))

(defn external-message->assistant-payload
  [session-id message]
  (let [content (or (:content message) [])
        text    (or (:text message)
                    (assistant-content-text content))]
    (cond-> {:session-id session-id
             :role       (or (:role message) "assistant")
             :content    content}
      (and (string? text) (not (str/blank? text))) (assoc :text text)
      (contains? message :custom-type) (assoc :custom-type (:custom-type message))
      (contains? message :stop-reason) (assoc :stop-reason (:stop-reason message))
      (contains? message :error-message) (assoc :error-message (:error-message message))
      (contains? message :usage) (assoc :usage (:usage message)))))
