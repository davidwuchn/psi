(ns psi.query.core
  "EQL query surface for the Psi system.

   This is the public face of the query component.  Everything the rest of
   the system needs lives here:

     register-resolver!  — add a resolver to the graph
     register-mutation!  — add a mutation to the graph
     query               — run an EQL query against the live graph
     query-one           — run an EQL query, return a single attribute
     rebuild-env!        — rebuild the environment after new registrations

   Example:

     (pco/defresolver greeting [{:keys [user/name]}]
       {::pco/input  [:user/name]
        ::pco/output [:user/greeting]}
       {:user/greeting (str \"Hello, \" name \"!\")})

     (query/register-resolver! greeting)
     (query/rebuild-env!)
     (query/query {:user/name \"ψ\"} [:user/greeting])
     ;; => {:user/greeting \"Hello, ψ!\"}
   "
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.query.env :as env]
   [psi.query.registry :as registry]))

;;; State

(defonce ^:private current-env (atom nil))

;;; Isolated query context (Nullable pattern)

(defrecord QueryContext [reg env-atom])

(defn create-query-context
  "Create an isolated query context backed by its own registry and env atom.
  Use in tests to avoid touching global state.

  Returns a QueryContext that can be passed to the *-in context-aware fns."
  []
  (->QueryContext (registry/create-registry) (atom nil)))

;;; Context-aware core functions

(defn register-resolver-in!
  "Register a resolver into an isolated `ctx` query context."
  [ctx resolver]
  (registry/register-resolver-in! (:reg ctx) resolver))

(defn register-mutation-in!
  "Register a mutation into an isolated `ctx` query context."
  [ctx mutation]
  (registry/register-mutation-in! (:reg ctx) mutation))

(defn rebuild-env-in!
  "Rebuild the Pathom environment for `ctx` from its registry."
  ([ctx] (rebuild-env-in! ctx {}))
  ([ctx opts]
   (let [indexes (registry/build-indexes-in (:reg ctx))
         new-env (env/build-env (assoc opts :indexes indexes))]
     (reset! (:env-atom ctx) new-env)
     new-env)))

(defn ensure-env-in!
  "Return the environment for `ctx`, building it first if necessary."
  [ctx]
  (or @(:env-atom ctx) (rebuild-env-in! ctx)))

(defn query-in
  "Execute an EQL `q` against `ctx`'s live graph."
  [ctx input q]
  (let [e (ensure-env-in! ctx)]
    (env/process e input q)))

(defn query-one-in
  "Execute an EQL query against `ctx` and return a single `attr` value."
  [ctx input attr]
  (get (query-in ctx input [attr]) attr))

(defn resolver-syms-in
  "Return set of registered resolver syms in `ctx`."
  [ctx]
  (registry/registered-resolver-syms-in (:reg ctx)))

(defn mutation-syms-in
  "Return set of registered mutation syms in `ctx`."
  [ctx]
  (registry/registered-mutation-syms-in (:reg ctx)))

(defn graph-summary-in
  "Return a summary map describing the query graph state in `ctx`."
  [ctx]
  {:resolver-count (registry/resolver-count-in (:reg ctx))
   :mutation-count (registry/mutation-count-in (:reg ctx))
   :env-built?     (some? @(:env-atom ctx))
   :resolvers      (resolver-syms-in ctx)
   :mutations      (mutation-syms-in ctx)})

;;; Environment lifecycle (global)

(defn rebuild-env!
  "Rebuild the Pathom environment from the current resolver/mutation registry.
   Must be called after any new registrations for them to take effect."
  ([]
   (rebuild-env! {}))
  ([opts]
   (let [new-env (env/build-env opts)]
     (reset! current-env new-env)
     new-env)))

(defn ensure-env!
  "Return the current environment, building it first if necessary."
  []
  (or @current-env (rebuild-env!)))

;;; Registration (delegating to registry)

(defn register-resolver!
  "Register a resolver and mark the environment as stale.
   Call rebuild-env! to apply the new resolver."
  [resolver]
  (registry/register-resolver! resolver))

(defn register-mutation!
  "Register a mutation and mark the environment as stale.
   Call rebuild-env! to apply the new mutation."
  [mutation]
  (registry/register-mutation! mutation))

;;; Query execution

(defn query
  "Execute an EQL `q` against the live graph.

   `input` — seed entity map, e.g. {:user/id 42}
   `q`     — EQL vector,  e.g. [:user/name {:user/address [:address/city]}]

   Uses the current environment, building one if necessary."
  [input q]
  (let [e (ensure-env!)]
    (env/process e input q)))

(defn query-one
  "Execute an EQL query and return the value of a single `attr`.

   Convenience wrapper around `query`."
  [input attr]
  (get (query input [attr]) attr))

;;; Introspection

(defn resolver-syms
  "Return the set of registered resolver qualified symbols."
  []
  (registry/registered-resolver-syms))

(defn mutation-syms
  "Return the set of registered mutation qualified symbols."
  []
  (registry/registered-mutation-syms))

(defn graph-summary
  "Return a summary map describing the current query graph state."
  []
  {:resolver-count (registry/resolver-count)
   :mutation-count (registry/mutation-count)
   :env-built?     (some? @current-env)
   :resolvers      (resolver-syms)
   :mutations      (mutation-syms)})

;;; Convenience macro for inline resolver registration

(defmacro ^:export defresolver
  "Define and immediately register a Pathom resolver.
   Wraps pco/defresolver and calls register-resolver!.

   Example:
     (defresolver user-name
       [{:keys [user/id]}]
       {::pco/input  [:user/id]
        ::pco/output [:user/name]}
       {:user/name (lookup-name id)})"
  [sym & body]
  `(do
     (pco/defresolver ~sym ~@body)
     (register-resolver! ~sym)
     (var ~sym)))

(defmacro ^:export defmutation
  "Define and immediately register a Pathom mutation.
   Wraps pco/defmutation and calls register-mutation!.

   Example:
     (defmutation update-name
       [{:keys [user/id user/name]}]
       {::pco/params [:user/id :user/name]}
       (save-name! id name)
       {:user/name name})"
  [sym & body]
  `(do
     (pco/defmutation ~sym ~@body)
     (register-mutation! ~sym)
     (var ~sym)))
