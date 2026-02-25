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

;;; Isolated registry (Nullable pattern)

(defn create-registry
  "Create an isolated registry with its own atoms.
  Use in tests to avoid touching global state.

  Returns a registry map with the same shape as the global state but
  scoped to its own atoms.  Pass to the *-in context-aware variants."
  []
  {:resolvers (atom [])
   :mutations (atom [])})

;;; Internal helpers

(defn- now [] (java.time.Instant/now))

;;; Internal helpers (global singleton context)

(defn- global-registry
  "Return the global registry context map."
  []
  {:resolvers resolvers
   :mutations mutations})

;;; Context-aware core functions

(defn register-resolver-in!
  "Add a resolver to the isolated `reg` registry."
  [reg resolver]
  (let [sym   (-> resolver pco/operation-config ::pco/op-name)
        entry {:resolver-sym  sym
               :resolver      resolver
               :registered-at (now)}]
    (when-not (m/validate resolver-entry-schema entry)
      (throw (ex-info "Invalid resolver entry"
                      {:entry  entry
                       :errors (m/explain resolver-entry-schema entry)})))
    (swap! (:resolvers reg) conj entry)
    entry))

(defn register-mutation-in!
  "Add a mutation to the isolated `reg` registry."
  [reg mutation]
  (let [sym   (-> mutation pco/operation-config ::pco/op-name)
        entry {:mutation-sym  sym
               :mutation      mutation
               :registered-at (now)}]
    (when-not (m/validate mutation-entry-schema entry)
      (throw (ex-info "Invalid mutation entry"
                      {:entry  entry
                       :errors (m/explain mutation-entry-schema entry)})))
    (swap! (:mutations reg) conj entry)
    entry))

(defn all-resolvers-in
  "Return all registered resolver objects from `reg`."
  [reg]
  (mapv :resolver @(:resolvers reg)))

(defn all-mutations-in
  "Return all registered mutation objects from `reg`."
  [reg]
  (mapv :mutation @(:mutations reg)))

(defn registered-resolver-syms-in
  "Return set of registered resolver qualified symbols from `reg`."
  [reg]
  (into #{} (map :resolver-sym) @(:resolvers reg)))

(defn registered-mutation-syms-in
  "Return set of registered mutation qualified symbols from `reg`."
  [reg]
  (into #{} (map :mutation-sym) @(:mutations reg)))

(defn resolver-count-in [reg] (count @(:resolvers reg)))
(defn mutation-count-in [reg] (count @(:mutations reg)))

(defn build-indexes-in
  "Compile resolvers and mutations from `reg` into a Pathom index map."
  [reg]
  (pci/register (into (all-resolvers-in reg) (all-mutations-in reg))))

;;; Public API — global (singleton) wrappers

(defn register-resolver!
  "Add a resolver (created via pco/defresolver or pco/resolver) to the registry."
  [resolver]
  (register-resolver-in! (global-registry) resolver))

(defn register-mutation!
  "Add a mutation (created via pco/defmutation or pco/mutation) to the registry."
  [mutation]
  (register-mutation-in! (global-registry) mutation))

(defn all-resolvers
  "Return all registered resolver objects."
  []
  (all-resolvers-in (global-registry)))

(defn all-mutations
  "Return all registered mutation objects."
  []
  (all-mutations-in (global-registry)))

(defn registered-resolver-syms
  "Return set of registered resolver qualified symbols."
  []
  (registered-resolver-syms-in (global-registry)))

(defn registered-mutation-syms
  "Return set of registered mutation qualified symbols."
  []
  (registered-mutation-syms-in (global-registry)))

(defn resolver-count [] (resolver-count-in (global-registry)))
(defn mutation-count [] (mutation-count-in (global-registry)))

(defn build-indexes
  "Compile currently registered resolvers and mutations into a Pathom index map."
  []
  (build-indexes-in (global-registry)))

(defn reset-registry!
  "Clear all registered resolvers and mutations. Primarily for testing."
  []
  (reset! resolvers [])
  (reset! mutations []))
