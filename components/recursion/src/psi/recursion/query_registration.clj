(ns psi.recursion.query-registration)

(defn register-resolvers-in!
  [qctx rebuild?]
  (let [resolvers (requiring-resolve 'psi.recursion.resolvers/all-resolvers)
        register-fn (requiring-resolve 'psi.query.core/register-resolver-in!)
        rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env-in!)]
    (doseq [r @resolvers]
      (register-fn qctx r))
    (when rebuild?
      (rebuild-fn qctx))
    :ok))

(defn register-mutations-in!
  [qctx rebuild?]
  (let [mutations (requiring-resolve 'psi.recursion.resolvers/all-mutations)
        register-fn (requiring-resolve 'psi.query.core/register-mutation-in!)
        rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env-in!)]
    (doseq [m @mutations]
      (register-fn qctx m))
    (when rebuild?
      (rebuild-fn qctx))
    :ok))

(defn register-resolvers!
  []
  (let [resolvers (requiring-resolve 'psi.recursion.resolvers/all-resolvers)
        mutations (requiring-resolve 'psi.recursion.resolvers/all-mutations)
        register-resolver-fn (requiring-resolve 'psi.query.core/register-resolver!)
        register-mutation-fn (requiring-resolve 'psi.query.core/register-mutation!)
        rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env!)]
    (doseq [r @resolvers]
      (register-resolver-fn r))
    (doseq [m @mutations]
      (register-mutation-fn m))
    (rebuild-fn)
    :ok))
