(ns psi.rpc.session.message-source
  "Backward-compatible alias for the canonical app-runtime transcript source helpers."
  (:require
   [psi.app-runtime.messages :as app-messages]))

(defn session-messages
  [ctx session-id]
  (app-messages/session-messages ctx session-id))
