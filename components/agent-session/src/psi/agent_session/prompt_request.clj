(ns psi.agent-session.prompt-request
  "Pure prompt/request projection helpers.

   This namespace is the architectural home for request preparation:
   canonical session state + journal + prompt layers -> prepared request.

   Initial scaffold only. Behavior is intentionally minimal and compatible with
   the current executor-owned runtime path."
  (:require
   [psi.agent-session.conversation :as conv]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.session-state :as ss]))

(defn journal->provider-messages
  "Project persisted journal entries into agent/provider message maps.
   Initial scaffold mirrors executor/session-messages behavior."
  [journal]
  (into []
        (keep (fn [entry]
                (when (= :message (:kind entry))
                  (get-in entry [:data :message]))))
        journal))

(defn- resolve-api-key
  "Resolve API key: prefer explicit runtime-opts, fall back to session-stored
   key from prior turn, fall back to oauth context."
  [ctx session-data runtime-opts]
  (or (:api-key runtime-opts)
      (:runtime-api-key session-data)
      (when-let [oauth-ctx (:oauth-ctx ctx)]
        (when-let [provider (:provider (:model session-data))]
          (oauth/get-api-key oauth-ctx provider)))))

(defn session->request-options
  "Build request/runtime options from canonical session data.
   Initial scaffold only returns the currently relevant keys."
  [ctx session-data runtime-opts]
  (let [api-key (resolve-api-key ctx session-data runtime-opts)]
    (cond-> {}
      (contains? session-data :thinking-level)
      (assoc :thinking-level (:thinking-level session-data))

      (some? api-key)
      (assoc :api-key api-key)

      (contains? runtime-opts :llm-stream-idle-timeout-ms)
      (assoc :llm-stream-idle-timeout-ms (:llm-stream-idle-timeout-ms runtime-opts)))))

(defn build-prompt-layers
  "Return prompt layers for the prepared request.
   Initial scaffold projects the already-composed effective system prompt as a
   single stable system layer."
  [session-data _opts]
  [{:id      :system/base
    :kind    :system
    :stable? true
    :content (:system-prompt session-data)}])

(defn build-prepared-request
  "Build a minimal prepared-request artifact from canonical session state.
   This first-pass scaffold intentionally consumes the already-composed
   effective system prompt from session data.

   Input opts:
   - :turn-id
   - :user-message
   - :runtime-opts"
  [ctx session-id {:keys [turn-id user-message runtime-opts] :as opts}]
  (let [session-data   (ss/get-session-data-in ctx session-id)
        journal        (or (ss/get-state-value-in ctx [:agent-session :sessions session-id :persistence :journal]) [])
        messages       (journal->provider-messages journal)
        tool-schemas   (or (:tool-schemas session-data) [])
        ai-options     (session->request-options ctx session-data (or runtime-opts {}))
        cache-bps      (set (or (:cache-breakpoints session-data) #{}))
        system-prompt  (:system-prompt session-data)
        prompt-layers  (build-prompt-layers session-data opts)
        provider-conv  (conv/agent-messages->ai-conversation
                        system-prompt
                        messages
                        tool-schemas
                        {:cache-breakpoints cache-bps})]
    {:prepared-request/id                  turn-id
     :prepared-request/session-id          session-id
     :prepared-request/user-message        user-message
     :prepared-request/session-snapshot    {:model             (:model session-data)
                                            :thinking-level    (:thinking-level session-data)
                                            :prompt-mode       (:prompt-mode session-data)
                                            :cache-breakpoints cache-bps
                                            :active-tools      (:active-tools session-data)}
     :prepared-request/prompt-layers       prompt-layers
     :prepared-request/system-prompt       system-prompt
     :prepared-request/system-prompt-blocks (:system-prompt-blocks provider-conv)
     :prepared-request/messages            (:messages provider-conv)
     :prepared-request/tools               (:tools provider-conv)
     :prepared-request/model               (:model session-data)
     :prepared-request/ai-options          ai-options
     :prepared-request/cache-projection    {:cache-breakpoints cache-bps
                                            :system-cached?    (contains? cache-bps :system)
                                            :tools-cached?     (contains? cache-bps :tools)
                                            :message-breakpoint-count
                                            (count (filter #(= :user (:role %))
                                                           (:messages provider-conv)))}
     :prepared-request/provider-conversation provider-conv}))
