(ns psi.system-bootstrap.core
  "System-wide resolver registration - breaks circular dependencies via common dependency extraction.
   
   This component coordinates resolver registration from all domains without creating
   circular dependencies. Both introspection and agent-session depend on this component,
   but this component depends on all resolver domains.
   
   Dependency flow:
   - system-bootstrap → all resolver domains (ai, history, memory, recursion, agent-session, introspection)  
   - agent-session → system-bootstrap (for registration)
   - introspection → system-bootstrap (for registration)
   - Result: Acyclic dependency graph
   
   This is the proper architectural solution vs. dynamic loading."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.query.core :as query]
   [psi.query.registry :as registry]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Registration Protocol
;; ─────────────────────────────────────────────────────────────────────────────

(defn- register-resolver-if-missing!
  "Register a resolver only if it's not already registered."
  [resolver]
  (let [sym (-> resolver pco/operation-config ::pco/op-name)
        existing-resolvers (registry/registered-resolver-syms)]
    (when-not (contains? existing-resolvers sym)
      (query/register-resolver! resolver))))

(defn- register-mutation-if-missing!
  "Register a mutation only if it's not already registered."
  [mutation]
  (let [sym (-> mutation pco/operation-config ::pco/op-name)
        existing-mutations (registry/registered-mutation-syms)]
    (when-not (contains? existing-mutations sym)
      (query/register-mutation! mutation))))

(defn- load-and-register-domain!
  "Dynamically load a namespace and register its resolvers/mutations.
   
   Args:
     namespace-sym - symbol of the namespace to require
     resolvers-var - symbol for the all-resolvers var (e.g. 'all-resolvers)
     mutations-var - optional symbol for mutations var (e.g. 'all-mutations)"
  ([namespace-sym resolvers-var]
   (load-and-register-domain! namespace-sym resolvers-var nil))
  ([namespace-sym resolvers-var mutations-var]
   (try
     (require namespace-sym)
     (when-let [resolvers-var-resolved (resolve (symbol (str namespace-sym "/" resolvers-var)))]
       (doseq [r @resolvers-var-resolved]
         (register-resolver-if-missing! r)))
     (when mutations-var
       (when-let [mutations-var-resolved (resolve (symbol (str namespace-sym "/" mutations-var)))]
         (doseq [m @mutations-var-resolved]
           (register-mutation-if-missing! m))))
     (catch Exception e
       (println "Warning: Could not load resolvers from" namespace-sym ":" (.getMessage e))))))

(defn register-all-domains!
  "Register resolvers and mutations from all system domains using dynamic loading.
   
   This avoids loading all domains at namespace-load time, which was causing 
   slow test loading when kaocha loaded test namespaces that depend on this component.
   
   Domains:
   - AI resolvers
   - History resolvers + mutations
   - Introspection resolvers  
   - Memory resolvers
   - Recursion resolvers + mutations
   - Agent-session resolvers + mutations
   
   Idempotent: skips operations already present in the global registry."
  []
  ;; Load domains dynamically to avoid eager loading at require-time
  (load-and-register-domain! 'psi.ai.core 'all-resolvers)
  (load-and-register-domain! 'psi.history.resolvers 'all-resolvers 'all-mutations)
  (load-and-register-domain! 'psi.introspection.resolvers 'all-resolvers)
  (load-and-register-domain! 'psi.memory.resolvers 'all-resolvers)
  (load-and-register-domain! 'psi.recursion.resolvers 'all-resolvers 'all-mutations)
  (load-and-register-domain! 'psi.agent-session.resolvers 'all-resolvers)

  ;; Agent-session mutations are in the core namespace
  (try
    (require 'psi.agent-session.core)
    (when-let [mutations-var (resolve 'psi.agent-session.core/all-mutations)]
      (doseq [m @mutations-var]
        (register-mutation-if-missing! m)))
    (catch Exception e
      (println "Warning: Could not load agent-session mutations:" (.getMessage e))))

  ;; Single env rebuild after all operations are registered
  (query/rebuild-env!))

(defn register-domains-in!
  "Register resolvers and mutations into an isolated query context.
   
   For isolated testing contexts that need full resolver surfaces.
   
   Args:
     qctx - isolated query context from query/create-query-context
     session-ctx - optional agent-session context for session-specific resolvers"
  ([qctx]
   (register-domains-in! qctx nil))
  ([qctx session-ctx]
   (let [existing-resolvers (set (map #(-> % pco/operation-config ::pco/op-name)
                                      (registry/all-resolvers-in (:reg qctx))))
         existing-mutations (set (map #(-> % pco/operation-config ::pco/op-name)
                                      (registry/all-mutations-in (:reg qctx))))
         register-resolver-if-missing!
         (fn [resolver]
           (let [sym (-> resolver pco/operation-config ::pco/op-name)]
             (when-not (contains? existing-resolvers sym)
               (query/register-resolver-in! qctx resolver))))
         register-mutation-if-missing!
         (fn [mutation]
           (let [sym (-> mutation pco/operation-config ::pco/op-name)]
             (when-not (contains? existing-mutations sym)
               (query/register-mutation-in! qctx mutation))))]

     ;; Helper for isolated context registration
     (let [load-and-register-in!
           (fn [namespace-sym resolvers-var mutations-var]
             (try
               (require namespace-sym)
               (when-let [resolvers-var-resolved (resolve (symbol (str namespace-sym "/" resolvers-var)))]
                 (doseq [r @resolvers-var-resolved]
                   (register-resolver-if-missing! r)))
               (when mutations-var
                 (when-let [mutations-var-resolved (resolve (symbol (str namespace-sym "/" mutations-var)))]
                   (doseq [m @mutations-var-resolved]
                     (register-mutation-if-missing! m))))
               (catch Exception e
                 (println "Warning: Could not load resolvers from" namespace-sym "in isolated context:" (.getMessage e)))))]

       ;; AI resolvers (not part of agent-session surface)
       (load-and-register-in! 'psi.ai.core 'all-resolvers nil)

       ;; Introspection resolvers (component-specific)
       (load-and-register-in! 'psi.introspection.resolvers 'all-resolvers nil)

       (if session-ctx
         (do
           ;; Full session resolver surface when session context is present
           (load-and-register-in! 'psi.agent-session.resolvers 'all-resolvers nil)
           (load-and-register-in! 'psi.history.resolvers 'all-resolvers 'all-mutations)
           (load-and-register-in! 'psi.memory.resolvers 'all-resolvers nil)
           (load-and-register-in! 'psi.recursion.resolvers 'all-resolvers 'all-mutations)

           ;; Agent-session mutations live in the mutations namespace
           (load-and-register-in! 'psi.agent-session.mutations nil 'all-mutations))
         (do
           ;; Without session context, still expose core domains
           (load-and-register-in! 'psi.history.resolvers 'all-resolvers 'all-mutations)
           (load-and-register-in! 'psi.memory.resolvers 'all-resolvers nil)
           (load-and-register-in! 'psi.recursion.resolvers 'all-resolvers 'all-mutations))))

     ;; Single env rebuild after all operations are registered
     (query/rebuild-env-in! qctx))))