(ns psi.agent-session.resolvers.services
  "Pathom3 resolvers for managed services, post-tool processors, and telemetry."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.services :as services]))

(def ^:private service-output
  [:psi.service/id
   :psi.service/key
   :psi.service/type
   :psi.service/status
   :psi.service/command
   :psi.service/cwd
   :psi.service/transport
   :psi.service/ext-path
   :psi.service/pid
   :psi.service/started-at
   :psi.service/stopped-at
   :psi.service/restart-count
   :psi.service/last-error])

(def ^:private processor-output
  [:psi.post-tool-processor/name
   :psi.post-tool-processor/ext-path
   :psi.post-tool-processor/tools
   :psi.post-tool-processor/timeout-ms])

(def ^:private telemetry-output
  [:psi.post-tool/tool-call-id
   :psi.post-tool/tool-name
   :psi.post-tool/processor-name
   :psi.post-tool/status
   :psi.post-tool/duration-ms
   :psi.post-tool/timestamp])

(defn- service->eql [svc]
  {:psi.service/id            (:id svc)
   :psi.service/key           (:key svc)
   :psi.service/type          (:type svc)
   :psi.service/status        (:status svc)
   :psi.service/command       (:command svc)
   :psi.service/cwd           (:cwd svc)
   :psi.service/transport     (:transport svc)
   :psi.service/ext-path      (:ext-path svc)
   :psi.service/pid           (:pid svc)
   :psi.service/started-at    (:started-at svc)
   :psi.service/stopped-at    (:stopped-at svc)
   :psi.service/restart-count (:restart-count svc)
   :psi.service/last-error    (:last-error svc)})

(defn- processor->eql [p]
  {:psi.post-tool-processor/name       (:name p)
   :psi.post-tool-processor/ext-path   (:ext-path p)
   :psi.post-tool-processor/tools      (vec (:tools p))
   :psi.post-tool-processor/timeout-ms (:timeout-ms p)})

(defn- telemetry->eql [e]
  {:psi.post-tool/tool-call-id   (:tool-call-id e)
   :psi.post-tool/tool-name      (:tool-name e)
   :psi.post-tool/processor-name (:processor-name e)
   :psi.post-tool/status         (:status e)
   :psi.post-tool/duration-ms    (:duration-ms e)
   :psi.post-tool/timestamp      (:timestamp e)})

(pco/defresolver services-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.service/count
                 :psi.service/keys
                 {:psi.service/services service-output}]}
  (let [ctx (:psi/agent-session-ctx entity)]
    {:psi.service/count    (services/service-count-in ctx)
     :psi.service/keys     (vec (services/service-keys-in ctx))
     :psi.service/services (mapv service->eql (services/projected-services-in ctx))}))

(pco/defresolver post-tool-processors-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.post-tool-processor/count
                 {:psi.post-tool-processors processor-output}]}
  (let [ctx (:psi/agent-session-ctx entity)]
    {:psi.post-tool-processor/count (post-tool/processor-count-in ctx)
     :psi.post-tool-processors      (mapv processor->eql (post-tool/projected-processors-in ctx))}))

(pco/defresolver post-tool-telemetry-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.post-tool/processed-count
                 :psi.post-tool/timeout-count
                 :psi.post-tool/error-count
                 {:psi.post-tool/recent-events telemetry-output}]}
  (let [ctx    (:psi/agent-session-ctx entity)
        counts (post-tool/telemetry-counts-in ctx)]
    {:psi.post-tool/processed-count (:processed-count counts)
     :psi.post-tool/timeout-count   (:timeout-count counts)
     :psi.post-tool/error-count     (:error-count counts)
     :psi.post-tool/recent-events   (mapv telemetry->eql (post-tool/recent-telemetry-in ctx))}))

(def resolvers
  [services-resolver
   post-tool-processors-resolver
   post-tool-telemetry-resolver])
