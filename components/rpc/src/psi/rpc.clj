(ns psi.rpc
  "Public RPC facade.

   Keeps the stable `psi.rpc` API while implementation lives in smaller
   transport/session namespaces."
  (:require
   [psi.rpc.events :as rpc.events]
   [psi.rpc.runtime :as rpc.runtime]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.transport :as rpc.transport]))

(def protocol-version rpc.transport/protocol-version)

(def response-frame rpc.transport/response-frame)
(def error-frame rpc.transport/error-frame)
(def event-frame rpc.transport/event-frame)
(def run-stdio-loop! rpc.transport/run-stdio-loop!)

(def footer-query rpc.events/footer-query)
(def footer-updated-payload rpc.events/footer-updated-payload)
(def session-updated-payload rpc.events/session-updated-payload)
(def progress-event->rpc-event rpc.events/progress-event->rpc-event)
(def session->handshake-server-info rpc.events/session->handshake-server-info)
(def make-session-request-handler rpc.session/make-session-request-handler)

(def start-runtime! rpc.runtime/start-runtime!)
