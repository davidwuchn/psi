(ns psi.agent-session.resolvers.support
  "Shared helpers for agent-session resolver namespaces.

   Provides explicit session-scoped data access and EQL projection utilities
   that domain-specific resolver namespaces depend on."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]))

(defn session-data
  "Get session data for an explicit session-id."
  [agent-session-ctx session-id]
  (session/get-session-data-in agent-session-ctx session-id))

(defn agent-data
  "Get agent-core data for an explicit session-id."
  [agent-session-ctx session-id]
  (agent/get-data-in (ss/agent-ctx-in agent-session-ctx session-id)))

(defn agent-core-messages
  "Extract the message vec from agent-core inside a session context."
  [agent-session-ctx session-id]
  (:messages (agent-data agent-session-ctx session-id)))

(defn contribution->attrs
  "Project a prompt contribution map to :psi.extension.prompt-contribution/* attributes."
  [c]
  {:psi.extension.prompt-contribution/id         (:id c)
   :psi.extension.prompt-contribution/ext-path   (:ext-path c)
   :psi.extension.prompt-contribution/section    (:section c)
   :psi.extension.prompt-contribution/content    (:content c)
   :psi.extension.prompt-contribution/priority   (:priority c)
   :psi.extension.prompt-contribution/enabled    (:enabled c)
   :psi.extension.prompt-contribution/created-at (:created-at c)
   :psi.extension.prompt-contribution/updated-at (:updated-at c)})
