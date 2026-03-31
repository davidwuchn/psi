(ns psi.rpc
  "Public RPC facade.

   Keeps the stable `psi.rpc` API while implementation lives in smaller
   transport/session namespaces.

   This namespace intentionally exposes only the high-level entrypoints for
   running the RPC runtime, transport loop, and session request routing. Lower-
   level frame constructors and event/payload helpers stay in their owning
   namespaces."
  (:require
   [psi.rpc.runtime :as rpc.runtime]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.transport :as rpc.transport]))

(defn run-stdio-loop!
  [opts]
  (rpc.transport/run-stdio-loop! opts))

(defn make-session-request-handler
  ([ctx]
   (rpc.session/make-session-request-handler ctx))
  ([ctx session-deps]
   (rpc.session/make-session-request-handler ctx session-deps)))

(defn start-runtime!
  [opts]
  (rpc.runtime/start-runtime! opts))
