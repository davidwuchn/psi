(ns psi.query.registry
  "Resolver and mutation registry — the living index of graph capabilities.

   Resolvers and mutations are registered here before being compiled into
   a Pathom environment.  Registration is additive: callers may add
   resolvers at any time; the compiled environment is rebuilt on demand."
  (:require
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [malli.core :as m]))

;;; Schema

(def resolver-entry-schema
  [:map
   [:resolver-sym  qualified-symbol?]
   [:resolver      any?]
   [:registered-at inst?]])

(def mutation-entry-schema
  [:map
   [:mutation-sym  qualified-symbol?]
   [:mutation      any?]
   [:registered-at inst?]])

;;; State

(defonce ^:private resolvers (atom []))
(defonce ^:private mutations (atom []))

;;; Internal helpers

(defn- now [] (java.time.Instant/now))

;;; Public API

(defn register-resolver!
  "Add a resolver (created via pco/defresolver or pco/resolver) to the registry."
  [resolver]
  (let [sym (-> resolver pco/operation-config ::pco/op-name)
        entry {:resolver-sym  sym
               :resolver      resolver
               :registered-at (now)}]
    (when-not (m/validate resolver-entry-schema entry)
      (throw (ex-info "Invalid resolver entry"
                      {:entry entry
                       :errors (m/explain resolver-entry-schema entry)})))
    (swap! resolvers conj entry)
    entry))

(defn register-mutation!
  "Add a mutation (created via pco/defmutation or pco/mutation) to the registry."
  [mutation]
  (let [sym (-> mutation pco/operation-config ::pco/op-name)
        entry {:mutation-sym  sym
               :mutation      mutation
               :registered-at (now)}]
    (when-not (m/validate mutation-entry-schema entry)
      (throw (ex-info "Invalid mutation entry"
                      {:entry entry
                       :errors (m/explain mutation-entry-schema entry)})))
    (swap! mutations conj entry)
    entry))

(defn all-resolvers
  "Return all registered resolver objects."
  []
  (mapv :resolver @resolvers))

(defn all-mutations
  "Return all registered mutation objects."
  []
  (mapv :mutation @mutations))

(defn registered-resolver-syms
  "Return set of registered resolver qualified symbols."
  []
  (into #{} (map :resolver-sym) @resolvers))

(defn registered-mutation-syms
  "Return set of registered mutation qualified symbols."
  []
  (into #{} (map :mutation-sym) @mutations))

(defn resolver-count [] (count @resolvers))
(defn mutation-count [] (count @mutations))

(defn build-indexes
  "Compile currently registered resolvers and mutations into a Pathom index map."
  []
  (pci/register (into (all-resolvers) (all-mutations))))

(defn reset-registry!
  "Clear all registered resolvers and mutations. Primarily for testing."
  []
  (reset! resolvers [])
  (reset! mutations []))
