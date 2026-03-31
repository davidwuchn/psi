(ns psi.rpc
  "Public RPC facade.

   Keeps the stable `psi.rpc` API while implementation lives in smaller
   transport/session namespaces.

   Exported names here are curated compatibility aliases. Public qualified vars
   still need to exist in `psi.rpc`, so function exports use tiny forwarding
   wrappers rather than copying implementation fn values with `def`. This keeps
   the stable facade while allowing REPL reloads of implementation namespaces to
   be observed through the facade on the next call."
  (:require
   [psi.rpc.events :as rpc.events]
   [psi.rpc.runtime :as rpc.runtime]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.transport :as rpc.transport]))

(def protocol-version rpc.transport/protocol-version)

(defn response-frame
  ([id op ok]
   (rpc.transport/response-frame id op ok))
  ([id op ok data]
   (rpc.transport/response-frame id op ok data)))

(defn error-frame
  [frame]
  (rpc.transport/error-frame frame))

(defn event-frame
  [frame]
  (rpc.transport/event-frame frame))

(defn run-stdio-loop!
  [opts]
  (rpc.transport/run-stdio-loop! opts))

(defn footer-updated-payload
  ([ctx]
   (rpc.events/footer-updated-payload ctx))
  ([ctx session-id]
   (rpc.events/footer-updated-payload ctx session-id)))

(defn session-updated-payload
  ([ctx]
   (rpc.events/session-updated-payload ctx))
  ([ctx session-id]
   (rpc.events/session-updated-payload ctx session-id)))

(defn progress-event->rpc-event
  [progress-event]
  (rpc.events/progress-event->rpc-event progress-event))

(defn session->handshake-server-info
  ([ctx]
   (rpc.events/session->handshake-server-info ctx))
  ([ctx session-id]
   (rpc.events/session->handshake-server-info ctx session-id)))

(defn make-session-request-handler
  ([ctx]
   (rpc.session/make-session-request-handler ctx))
  ([ctx session-deps]
   (rpc.session/make-session-request-handler ctx session-deps)))

(defn start-runtime!
  [opts]
  (rpc.runtime/start-runtime! opts))
