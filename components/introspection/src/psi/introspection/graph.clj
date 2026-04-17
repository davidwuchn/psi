(ns psi.introspection.graph
  "Compatibility wrapper over `psi.graph.analysis`.

   Capability-graph derivation now lives in `psi.graph.analysis`.
   This namespace remains as a thin forwarding layer for existing callers/tests
   during convergence."
  (:require
   [psi.graph.analysis :as analysis]))

(def required-domains analysis/required-domains)
(def classify-domain analysis/classify-domain)
(def operation->metadata analysis/operation->metadata)
(def operation-node-id analysis/operation-node-id)
(def capability-node-id analysis/capability-node-id)
(def domain-operation analysis/domain-operation)
(def derive-domain-operations analysis/derive-domain-operations)
(def derive-capabilities analysis/derive-capabilities)
(def derive-nodes analysis/derive-nodes)
(def derive-edges analysis/derive-edges)
(def derive-domain-coverage analysis/derive-domain-coverage)
(def derive-root-queryable-attrs analysis/derive-root-queryable-attrs)
(def derive-capability-graph analysis/derive-capability-graph)
