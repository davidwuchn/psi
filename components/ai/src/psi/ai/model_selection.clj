(ns psi.ai.model-selection
  "Shared model-selection surfaces.

   v1 establishes:
   - the resolver-facing catalog view
   - request shapes and role defaults

   Later steps will add policy composition, filtering, ranking, and trace
   production on top of these data structures."
  (:require
   [psi.ai.model-registry :as model-registry]))

(def ^:private default-role
  :interactive)

(def ^:private role-defaults-map
  {:interactive
   {:role                :interactive
    :required            []
    :strong-preferences  []
    :weak-preferences    []}

   :helper
   {:role                :helper
    :required            [{:criterion :supports-text
                           :match     :true}]
    :strong-preferences  [{:criterion :input-cost
                           :prefer    :lower}
                          {:criterion :output-cost
                           :prefer    :lower}]
    :weak-preferences    []}

   :auto-session-name
   {:role                :auto-session-name
    :required            [{:criterion :supports-text
                           :match     :true}]
    :strong-preferences  [{:criterion :input-cost
                           :prefer    :lower}
                          {:criterion :output-cost
                           :prefer    :lower}]
    :weak-preferences    [{:criterion :same-provider-as-session
                           :prefer    :context-match}]}})

(defn role-defaults
  "Return role defaults for `role`, or nil when unknown."
  [role]
  (get role-defaults-map role))

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

(defn normalize-request
  "Normalize a model-selection request.

   Request shape:
   {:mode :explicit | :inherit-session | :resolve
    :role keyword?
    :required [criterion*]
    :strong-preferences [criterion*]
    :weak-preferences [criterion*]
    :context map?
    :model {:provider kw|string :id string}?}

   v1 responsibilities:
   - provide default mode/role
   - normalize missing vectors/maps
   - normalize explicit model provider to keyword
   - merge role defaults underneath caller-supplied fields

   This does not yet implement multi-layer policy composition."
  [request]
  (let [mode          (or (:mode request) :resolve)
        role          (or (:role request) default-role)
        role-default* (role-defaults role)
        model         (when-let [m (:model request)]
                        (cond-> {:provider (:provider m)
                                 :id       (:id m)}
                          (string? (:provider m))
                          (update :provider keyword)))
        normalized    (cond-> {}
                        (contains? request :required)
                        (assoc :required (vec (or (:required request) [])))

                        (contains? request :strong-preferences)
                        (assoc :strong-preferences
                               (vec (or (:strong-preferences request) [])))

                        (contains? request :weak-preferences)
                        (assoc :weak-preferences
                               (vec (or (:weak-preferences request) [])))

                        (contains? request :context)
                        (assoc :context (or (:context request) {}))

                        (contains? request :model)
                        (assoc :model model))]
    (merge {:mode                mode
            :role                role
            :required            []
            :strong-preferences  []
            :weak-preferences    []
            :context             {}
            :model               nil}
           role-default*
           normalized)))
