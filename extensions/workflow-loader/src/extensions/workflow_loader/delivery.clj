(ns extensions.workflow-loader.delivery)

(defn session-last-role
  "Get the last user/assistant message role for an explicit session id.
   Falls back to ambient query when explicit session-targeting APIs are absent."
  [{:keys [query-fn query-session-fn]} session-id]
  (let [query-result (cond
                       (and session-id query-session-fn)
                       (query-session-fn session-id
                                         [{:psi.agent-session/session-entries
                                           [:psi.session-entry/kind
                                            :psi.session-entry/data]}])

                       query-fn
                       (query-fn [{:psi.agent-session/session-entries
                                   [:psi.session-entry/kind
                                    :psi.session-entry/data]}])

                       :else nil)]
    (->> (:psi.agent-session/session-entries query-result)
         (map :psi.session-entry/data)
         (remove :custom-type)
         (map :role)
         (filter #{"user" "assistant"})
         last)))

(defn append-message-in-session!
  [{:keys [mutate-fn mutate-session-fn]} session-id role content]
  (try
    (cond
      (and session-id mutate-session-fn)
      (mutate-session-fn session-id 'psi.extension/append-message
                         {:role role :content content})

      mutate-fn
      (mutate-fn 'psi.extension/append-message
                 {:role role :content content})

      :else nil)
    (catch Exception _ nil)))

(defn inject-result-into-context!
  "Inject workflow result text into the parent session context as user+assistant messages.
   Maintains strict user/assistant alternation and explicitly targets the
   originating parent session when session-targeting APIs are available."
  [deps parent-session-id run-id result-text]
  (let [last-role (session-last-role deps parent-session-id)
        user-content (str "Workflow run " run-id " result:")
        asst-content (or result-text "")]
    (when (= "user" last-role)
      (append-message-in-session! deps parent-session-id "assistant" "(workflow context bridge)"))
    (append-message-in-session! deps parent-session-id "user" user-content)
    (append-message-in-session! deps parent-session-id "assistant" asst-content)))
