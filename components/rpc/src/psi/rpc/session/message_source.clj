(ns psi.rpc.session.message-source
  "Canonical transcript source helpers for RPC session rehydration.

   Rehydration and `get_messages` should read from the canonical ctx journal
   for loaded sessions, not from agent-core's cached `:messages`, which may
   temporarily drift from journal state."
  (:require
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.state-accessors :as sa]))

(defn session-messages
  "Return canonical transcript messages for SESSION-ID from loaded ctx state.

   Uses the in-memory journal in the ctx session map as source of truth and
   rebuilds messages through the same journal-aware compaction logic used by
   session loading."
  [ctx session-id]
  (compaction/rebuild-messages-from-journal-entries
   (vec (or (sa/journal-state-in ctx session-id) []))))
