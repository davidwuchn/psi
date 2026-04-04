(ns psi.agent-session.service-protocol
  "Shared stdio request/response and JSON-RPC helper semantics for managed services."
  (:require [clojure.edn :as edn]
            [psi.agent-session.services :as services]))

(defn send-service-request!
  "Send a correlated request to a managed service.
   v1 is transport-abstract and expects the service map to carry a writable
   :send-fn for tests / protocol adapters.

   If the service exposes :request-fn, it is used as a synchronous request/
   response shim and its return value is surfaced as :response for higher-level
   protocol adapters." 
  [ctx service-key {:keys [request-id payload timeout-ms]}]
  (let [svc      (services/service-in ctx service-key)
        response (when-let [request-fn (:request-fn svc)]
                   (request-fn {:request-id request-id
                                :payload payload
                                :timeout-ms timeout-ms}))]
    (when-let [send-fn (:send-fn svc)]
      (send-fn payload))
    {:service-key service-key
     :request-id request-id
     :payload payload
     :timeout-ms timeout-ms
     :response response}))

(defn send-service-notification!
  [ctx service-key payload]
  (let [svc (services/service-in ctx service-key)]
    (when-let [send-fn (:send-fn svc)]
      (send-fn payload))
    {:service-key service-key
     :payload payload}))

(defn jsonrpc-request!
  [ctx service-key {:keys [id method params]} {:keys [timeout-ms] :or {timeout-ms 500}}]
  (send-service-request!
   ctx service-key
   {:request-id id
    :payload {"jsonrpc" "2.0"
              "id" id
              "method" method
              "params" params}
    :timeout-ms timeout-ms}))

(defn jsonrpc-notify!
  [ctx service-key {:keys [method params]}]
  (send-service-notification!
   ctx service-key
   {"jsonrpc" "2.0"
    "method" method
    "params" params}))
