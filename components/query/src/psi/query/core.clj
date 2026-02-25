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

;;; Environment lifecycle

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
