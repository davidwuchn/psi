(ns psi.rpc.session.commands
  "Slash command/result helpers for RPC session workflows."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]
   [psi.ai.models :as ai-models]
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.session.message-source :as message-source]
   [psi.rpc.transport :refer [response-frame]]))

(defn handle-prompt-command-result!
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
              :content [{:type :text :text (str "[command result: " result-type "]")}]})
      nil)))

(defn- extension-command-output
  [cmd-result]
  (try
    (let [out (with-out-str
                ((:handler cmd-result) (:args cmd-result)))]
      (if (str/blank? out)
        "[extension command returned no output]"
        out))
    (catch Throwable e
      (str "[extension command error: " (ex-message e) "]"))))

(defn- emit-text-command-result!
  [emit! message]
  (emit/emit-command-result! emit! {:type "text" :message message}))

(defn handle-command-result!
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
      (emit-text-command-result! emit! "[/resume requires frontend action handling]")

      :tree-open
      (emit-text-command-result! emit! "[/tree requires frontend action handling]")

      :tree-rename
      (emit/emit-command-result! emit! {:type "text"
                                        :message (str "Renamed session " (:session-id cmd-result)
                                                      " to " (pr-str (:session-name cmd-result)))})

      (:tree-switch :session-switch)
      (emit/emit-command-result! emit! {:type "session_switch"
                                        :session-id (:session-id cmd-result)})

      :frontend-action
      (emit/emit-frontend-action-request! emit! request-id cmd-result)

      :extension-cmd
      (emit-text-command-result! emit! (extension-command-output cmd-result))

      (emit-text-command-result! emit! (str "[command result: " result-type "]"))
      nil)))

(defn- tree-sort-sessions
  "Return sessions in parent/child tree order while preserving sibling input order."
  [sessions]
  (let [sessions           (vec sessions)
        by-id              (into {} (map (juxt :session-id identity) sessions))
        input-order        (into {} (map-indexed (fn [i s] [(:session-id s) i]) sessions))
        children-by-parent (reduce (fn [acc s]
                                     (let [parent-id      (:parent-session-id s)
                                           parent-exists? (and parent-id (contains? by-id parent-id))
                                           parent-key     (if parent-exists? parent-id ::root)]
                                       (update acc parent-key (fnil conj []) s)))
                                   {}
                                   sessions)
        children-by-parent (update-vals children-by-parent
                                        (fn [xs]
                                          (vec (sort-by #(get input-order (:session-id %) Integer/MAX_VALUE)
                                                        xs))))
        roots              (let [rs (get children-by-parent ::root [])]
                             (if (seq rs) rs sessions))
        emitted            (volatile! #{})]
    (letfn [(walk [node visited]
              (let [sid      (:session-id node)
                    cycle?   (contains? visited sid)
                    emitted? (contains? @emitted sid)]
                (if (or cycle? emitted?)
                  []
                  (let [visited' (conj visited sid)
                        _        (vswap! emitted conj sid)
                        children (get children-by-parent sid [])]
                    (reduce (fn [acc child]
                              (into acc (walk child visited')))
                            [node]
                            children)))))]
      (vec (mapcat #(walk % #{}) roots)))))

(defn- current-session-fork-points
  [ctx session-id]
  (->> (persist/all-entries-in ctx session-id)
       (keep (fn [entry]
               (let [msg  (get-in entry [:data :message])
                     text (message-text/user-message-display-text msg)]
                 (when (and (= :message (:kind entry))
                            (= "user" (:role msg))
                            (string? text)
                            (not (str/blank? text)))
                   {:session-id        session-id
                    :item-kind         "fork-point"
                    :entry-id          (:id entry)
                    :display-name      text
                    :created-at        (:timestamp entry)
                    :parent-session-id session-id}))))))

(defn- interleave-current-session-fork-points
  [session-items session-id fork-items]
  (if (or (str/blank? session-id) (empty? fork-items))
    session-items
    (loop [remaining session-items
           acc       []]
      (if-let [item (first remaining)]
        (let [acc'           (conj acc item)
              current-item?  (= session-id (:session-id item))
              children       (when current-item?
                               (take-while #(not= "session" (:item-kind % "session"))
                                           (rest remaining)))
              after-children (when current-item?
                               (drop (count children) (rest remaining)))]
          (if current-item?
            (recur after-children (into acc' (concat children fork-items)))
            (recur (rest remaining) acc')))
        acc))))

(defn- session-selector-payload
  [ctx session-id]
  (let [sessions0        (vec (or (ss/list-context-sessions-in ctx) []))
        current-session  (select-keys (ss/get-session-data-in ctx session-id)
                                      [:session-id :session-name :worktree-path
                                       :parent-session-id :created-at :updated-at])
        current-present? (some #(= session-id (:session-id %)) sessions0)
        sessions         (cond
                           (seq sessions0)
                           (if current-present?
                             sessions0
                             (into [current-session] sessions0))

                           :else
                           [current-session])
        session-items    (->> sessions
                              tree-sort-sessions
                              (mapv #(assoc % :item-kind "session")))
        fork-items       (vec (current-session-fork-points ctx session-id))]
    (interleave-current-session-fork-points session-items session-id fork-items)))

(defn- emit-session-rehydration-from-sid!
  [ctx state emit! sid]
  (let [sd   (ss/get-session-data-in ctx sid)
        msgs (message-source/session-messages ctx sid)]
    (events/set-focus-session-id! state sid)
    (emit/emit-session-rehydration!
     emit!
     {:session-id (:session-id sd)
      :session-file (:session-file sd)
      :message-count (count msgs)
      :messages msgs
      :tool-calls {}
      :tool-order []})))

(defn- maybe-match-session
  [sessions arg]
  (or (some #(when (= arg (:session-id %)) %) sessions)
      (let [matches (filterv #(str/starts-with? (or (:session-id %) "") arg) sessions)]
        (when (= 1 (count matches))
          (first matches)))))

(defn- handle-resume-command!
  [ctx state request-id emit! trimmed session-id]
  (if (= trimmed "/resume")
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
    (let [session-path (-> (str/replace trimmed #"^/resume\s+" "") str/trim)]
      (if-not (.exists (io/file session-path))
        (emit-text-command-result! emit! (str "Session file not found: " session-path))
        (let [sd  (session/resume-session-in! ctx session-id session-path)
              sid (:session-id sd)]
          (emit-session-rehydration-from-sid! ctx state emit! sid))))))

(defn- handle-tree-command!
  [ctx state request-id emit! trimmed session-id]
  (let [active-id (events/focus-session-id state)
        sessions  (session-selector-payload ctx session-id)]
    (if (= trimmed "/tree")
      (emit! "ui/frontend-action-requested"
             {:request-id request-id
              :action-name "context-session-selector"
              :prompt "Select a live session"
              :payload {:active-session-id active-id
                        :sessions sessions}})
      (let [arg (-> (str/replace trimmed #"^/tree\s+" "") str/trim)]
        (if (str/starts-with? arg "name ")
          (let [tail        (-> (subs arg 5) str/trim)
                [sid-part name-part] (let [[a b] (str/split tail #"\s+" 2)]
                                       [a (some-> b str/trim)])]
            (cond
              (str/blank? sid-part)
              (emit-text-command-result! emit! "Usage: /tree name <session-id|prefix> <name>")

              (str/blank? name-part)
              (emit-text-command-result! emit! "Usage: /tree name <session-id|prefix> <name>")

              :else
              (let [chosen (maybe-match-session sessions sid-part)
                    sid    (:session-id chosen)]
                (if-not chosen
                  (emit-text-command-result! emit! (str "Session not found in context: " sid-part))
                  (do
                    (session/set-session-name-in! ctx sid name-part)
                    (emit-text-command-result! emit! (str "Renamed session " sid " to " (pr-str name-part))))))))
          (let [chosen (maybe-match-session sessions arg)
                sid    (:session-id chosen)]
            (cond
              (nil? chosen)
              (emit-text-command-result! emit! (str "Session not found in context: " arg))

              (= sid active-id)
              (emit-text-command-result! emit! (str "Already active session: " sid))

              :else
              (do
                (session/ensure-session-loaded-in! ctx active-id sid)
                (emit-session-rehydration-from-sid! ctx state emit! sid)))))))))

(defn- handle-picker-command!
  [request-id emit! trimmed]
  (case trimmed
    "/model"
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

    "/thinking"
    (emit! "ui/frontend-action-requested"
           {:request-id request-id
            :action-name "thinking-picker"
            :prompt "Select a thinking level"
            :payload {:levels ["off" "minimal" "low" "medium" "high" "xhigh"]}})))

(defn- handle-dispatched-command!
  [ctx state emit-frame! request-id start-daemon-thread! login-handler cmd-result emit!]
  (if (= :login-start (:type cmd-result))
    (login-handler {:ctx ctx
                    :state state
                    :emit-frame! emit-frame!
                    :request-id request-id
                    :cmd-result cmd-result
                    :emit! emit!
                    :start-daemon-thread! start-daemon-thread!})
    (do
      (when (= :new-session (:type cmd-result))
        (let [rehydrate (:rehydrate cmd-result)
              new-sid   (:session-id rehydrate)
              _         (when new-sid (events/set-focus-session-id! state new-sid))
              sd        (when new-sid (ss/get-session-data-in ctx new-sid))
              msgs      (or (:agent-messages rehydrate)
                            (when new-sid
                              (message-source/session-messages ctx new-sid)))]
          (emit/emit-session-rehydration!
           emit!
           {:session-id (:session-id sd)
            :session-file (:session-file sd)
            :message-count (count msgs)
            :messages msgs
            :tool-calls (or (:tool-calls rehydrate) {})
            :tool-order (or (:tool-order rehydrate) [])})))
      (handle-command-result! request-id cmd-result emit!))))

(defn run-command!
  [{:keys [ctx request emit-frame! state session-id session-deps current-ai-model start-daemon-thread! login-handler]}]
  (let [text       (get-in request [:params :text])
        request-id (:id request)
        emit!      (emit/make-request-emitter emit-frame! state request-id)
        ai-model   (current-ai-model ctx session-deps session-id)
        oauth-ctx  (:oauth-ctx ctx)
        trimmed    (str/trim text)
        cmd-result (commands/dispatch-in ctx session-id text {:oauth-ctx oauth-ctx
                                                              :ai-model ai-model
                                                              :supports-session-tree? false
                                                              :on-new-session! (:on-new-session! session-deps)})]
    (runtime/journal-user-message-in! ctx session-id text nil)
    (cond
      (or (= trimmed "/resume") (str/starts-with? trimmed "/resume "))
      (handle-resume-command! ctx state request-id emit! trimmed session-id)

      (or (= trimmed "/tree") (str/starts-with? trimmed "/tree "))
      (handle-tree-command! ctx state request-id emit! trimmed session-id)

      (or (= trimmed "/model") (= trimmed "/thinking"))
      (handle-picker-command! request-id emit! trimmed)

      cmd-result
      (handle-dispatched-command! ctx state emit-frame! request-id start-daemon-thread! login-handler cmd-result emit!)

      :else
      (emit-text-command-result! emit! (str "[not a command] " text)))
    (emit/emit-session-snapshots! emit! ctx state session-id {:context? true})
    (response-frame (:id request) "command" true {:accepted true
                                                   :handled true})))
