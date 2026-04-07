(ns psi.app-runtime.messages
  "Canonical transcript source helpers shared across adapters."
  (:require
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.state-accessors :as sa]))

(defn session-messages
  "Return canonical transcript messages for `session-id` from loaded ctx state.

   Uses the in-memory journal in the ctx session map as source of truth and
   rebuilds messages through the same journal-aware compaction logic used by
   session loading."
  [ctx session-id]
  (compaction/rebuild-messages-from-journal-entries
   (vec (or (sa/journal-state-in ctx session-id) []))))
