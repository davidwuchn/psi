(ns psi.query.env
  "Pathom environment construction and lifecycle.

   The environment wraps the resolver index with cross-cutting concerns:
   error handling, tracing (optional), and plugin hooks.  Environments
   are cheap to rebuild — just call `build-env` with the latest index."
  (:require
   [com.wsscode.pathom3.error :as p.error]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [psi.query.registry :as registry]))

;;; Plugin helpers

(defn- error-handler-plugin
  "Wrap resolver errors with contextual information."
  []
  {::p.error/lenient-mode? true})

;;; Environment construction

(defn build-env
  "Build a Pathom3 EQL processing environment from the current registry.

   Options:
     :indexes  — pre-built index map (default: registry/build-indexes)
     :plugins  — additional Pathom plugins (default: [])
     :lenient? — suppress resolver errors, return ::p.error/node-error
                 for individual attribute failures (default: true)"
  ([]
   (build-env {}))
  ([{:keys [indexes lenient?]
     :or   {lenient? true}}]
   (let [idx (or indexes (registry/build-indexes))
         env (merge idx (when lenient? (error-handler-plugin)))]
     env)))

(defn process
  "Execute an EQL query against the given environment.

   `env`   — Pathom environment (from build-env)
   `input` — seed entity map (may be empty)
   `query` — EQL vector, e.g. [:user/name {:user/address [:address/city]}]"
  [env input query]
  (p.eql/process env input query))

