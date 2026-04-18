(ns psi.ai.model-selection
  "Shared model-selection catalog surface.

   v1 establishes the resolver-facing catalog view used by forthcoming
   request/resolver/trace logic. The catalog view is deliberately thin and only
   exposes attributes that already exist in the runtime catalog or adjacent auth
   registry. Richer policy metadata can be added later without changing caller
   intent."
  (:require
   [psi.ai.model-registry :as model-registry]))

(defn catalog-view
  "Return the merged resolver-facing catalog view.

   Result shape:
   {:candidates [{:provider kw
                  :id string
                  :name string?
                  :api keyword?
                  :base-url string?
                  :facts {...}
                  :estimates {...}
                  :reference {:configured? boolean?}}
                 ...]}

   v1 intentionally exposes only queryable metadata that already exists:
   - facts: provider/api/capability/context/token attrs
   - estimates: currently raw cost attributes
   - reference: provider auth/config availability

   No implicit locality or policy labels are invented here."
  []
  {:candidates
   (->> (model-registry/all-models)
        (map (fn [[[provider id] model]]
               (let [auth (model-registry/get-auth provider)]
                 {:provider  provider
                  :id        id
                  :name      (:name model)
                  :api       (:api model)
                  :base-url  (:base-url model)
                  :facts     {:provider            provider
                              :id                  id
                              :api                 (:api model)
                              :supports-text       (boolean (:supports-text model))
                              :supports-images     (boolean (:supports-images model))
                              :supports-reasoning  (boolean (:supports-reasoning model))
                              :context-window      (:context-window model)
                              :max-tokens          (:max-tokens model)}
                  :estimates {:input-cost         (:input-cost model)
                              :output-cost        (:output-cost model)
                              :cache-read-cost    (:cache-read-cost model)
                              :cache-write-cost   (:cache-write-cost model)}
                  :reference {:configured? (boolean (or (nil? auth)
                                                       (:api-key auth)
                                                       (seq (:headers auth))
                                                       (false? (:auth-header? auth))))}})))
        (sort-by (juxt :provider :id))
        vec)})

(defn find-candidate
  "Find a resolver-facing candidate by provider/id."
  [catalog provider id]
  (some (fn [candidate]
          (when (and (= provider (:provider candidate))
                     (= id (:id candidate)))
            candidate))
        (:candidates catalog)))
