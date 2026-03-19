(ns psi.graph.core
  "Shared graph analysis and introspection utilities.
   
   This component contains the minimal shared functionality needed by both:
   - introspection component (for graph analysis)  
   - agent-session component (for capability introspection)
   
   Extracted to break circular dependency: introspection ↔ agent-session"
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   ;; [com.wsscode.pathom3.connect.indexes :as pci]
   [clojure.set :as set]))

;; ────────────────────────────────────────────────────────────────────────────
;; Operation metadata extraction
;; ────────────────────────────────────────────────────────────────────────────

(defn operation->metadata
  "Extract metadata from a Pathom3 operation (resolver or mutation).
   
   Returns a map with:
   - :symbol - operation symbol  
   - :type   - :resolver or :mutation
   - :inputs - set of input attributes
   - :outputs - set of output attributes"
  [operation-type operation]
  (let [config (pco/operation-config operation)
        op-name (::pco/op-name config)]
    {:symbol  op-name
     :type    operation-type
     :inputs  (set (::pco/input config))
     :outputs (set (::pco/output config))}))

;; ────────────────────────────────────────────────────────────────────────────  
;; Capability graph derivation
;; ────────────────────────────────────────────────────────────────────────────

(defn derive-capability-graph
  "Derive capability graph from operation metadata.
   
   Input: {:resolver-ops [...] :mutation-ops [...]}
   Output: {:nodes [...] :edges [...] :capabilities {...} :domain-coverage {...}}"
  [op-meta]
  ;; Simplified implementation - can be enhanced later
  (let [all-ops (concat (:resolver-ops op-meta) (:mutation-ops op-meta))
        nodes   (mapv (fn [op]
                        {:id (:symbol op)
                         :type (:type op)
                         :inputs (:inputs op)
                         :outputs (:outputs op)})
                      all-ops)
        edges   [] ;; TODO: Implement edge derivation if needed
        capabilities {} ;; TODO: Implement capability analysis if needed  
        domain-coverage {}] ;; TODO: Implement domain coverage if needed
    {:nodes nodes
     :edges edges
     :capabilities capabilities
     :domain-coverage domain-coverage}))

;; ────────────────────────────────────────────────────────────────────────────
;; Root queryable attributes derivation  
;; ────────────────────────────────────────────────────────────────────────────

(defn derive-root-queryable-attrs
  "Derive root queryable attributes from resolver operations and root seeds.
   
   Returns vector of attributes reachable from root seeds via resolvers."
  [resolver-ops root-seeds]
  ;; Simplified implementation - traverse from root seeds through resolver outputs
  (let [seed-set (set root-seeds)
        resolver-outputs (mapcat :outputs resolver-ops)]
    (vec (set/union seed-set (set resolver-outputs)))))