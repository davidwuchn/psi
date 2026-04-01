(ns psi.agent-session.resolvers.support
  "Shared helpers for agent-session resolver namespaces.

   Provides session-id resolution, data access, and EQL projection utilities
   that all domain-specific resolver namespaces depend on."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]))

(def ^:dynamic *session-id*
  "When bound, overrides session-id resolution for the current query scope."
  nil)

(defn resolver-session-id
  "Resolve the session-id for resolver/helper execution.
   Prefers the query-bound explicit session-id, then the first context session.
   This preserves non-query helper/test behavior while removing ctx targeting."
  [agent-session-ctx]
  (or *session-id*
      (some-> (ss/list-context-sessions-in agent-session-ctx) first :session-id)))

(defn session-data
  "Get session data for the resolved session-id."
  [agent-session-ctx]
  (session/get-session-data-in agent-session-ctx (resolver-session-id agent-session-ctx)))

(defn agent-data
  "Get agent-core data for the resolved session-id."
  [agent-session-ctx]
  (agent/get-data-in (ss/agent-ctx-in agent-session-ctx (resolver-session-id agent-session-ctx))))

(defn agent-core-messages
  "Extract the message vec from agent-core inside a session context."
  [agent-session-ctx]
  (:messages (agent-data agent-session-ctx)))
