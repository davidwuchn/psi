(ns psi.ai.model-selection
  "Shared model-selection surfaces.

   v1 establishes:
   - the resolver-facing catalog view
   - request shapes and role defaults
   - policy layering and effective-request composition
   - resolver stage 1 filtering

   Later steps will add ranking and trace production on top of these data
   structures."
  (:require
   [psi.ai.model-registry :as model-registry]))

(def ^:private default-role
  :interactive)

(def ^:private policy-scope-order
  [:role-defaults :system :user :project :request])

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

(defn criterion-identity
  "Return the stable identity key for a criterion.

   v1 identity is the compared attribute/criterion itself. This supports:
   - replacement by higher-precedence policy layers
   - deterministic deduplication within a preference tier

   Future richer criterion shapes may extend this to include comparator/target
   dimensions when one attribute supports multiple distinct identities."
  [criterion]
  (or (:identity criterion)
      (:criterion criterion)
      (:attribute criterion)))

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

(defn- normalize-policy-layer
  [layer]
  (let [normalized (normalize-request layer)]
    (select-keys normalized [:required :strong-preferences :weak-preferences :context :model])))

(defn- merge-required-constraints
  [layers]
  (reduce
   (fn [acc layer]
     (reduce
      (fn [req-acc constraint]
        (let [id (criterion-identity constraint)]
          (if (some #(= id (criterion-identity %)) req-acc)
            req-acc
            (conj req-acc constraint))))
      acc
      (:required layer)))
   []
   layers))

(defn- merge-preference-tier
  [layers tier]
  (reduce
   (fn [acc layer]
     (reduce
      (fn [tier-acc criterion]
        (let [id (criterion-identity criterion)]
          (if-let [idx (first (keep-indexed (fn [i existing]
                                              (when (= id (criterion-identity existing))
                                                i))
                                            tier-acc))]
            (assoc tier-acc idx criterion)
            (conj tier-acc criterion))))
      acc
      (get layer tier [])))
   []
   layers))

(defn compose-effective-request
  "Compose role defaults, policy layers, and caller request into one effective request.

   Input shape:
   {:system  request-like-map?
    :user    request-like-map?
    :project request-like-map?
    :request request-like-map?}

   Precedence is fixed by `policy-scope-order`:
   role-defaults < system < user < project < request

   Merge rules in v1:
   - required constraints union in precedence order
   - strong/weak preferences dedupe by `criterion-identity`
   - higher-precedence duplicates replace lower-precedence instances in place
   - new criteria append after inherited criteria within a tier
   - context maps merge shallowly, later scopes win per key
   - explicit model on the highest-precedence supplying scope wins

   Returns a normalized effective request."
  [{:keys [system user project request]}]
  (let [request*       (normalize-request (or request {}))
        role           (:role request*)
        layers-by-scope {:role-defaults (normalize-policy-layer (role-defaults role))
                         :system        (normalize-policy-layer (or system {}))
                         :user          (normalize-policy-layer (or user {}))
                         :project       (normalize-policy-layer (or project {}))
                         :request       (normalize-policy-layer request*)}
        ordered-layers (mapv layers-by-scope policy-scope-order)]
    {:mode                (:mode request*)
     :role                role
     :required            (merge-required-constraints ordered-layers)
     :strong-preferences  (merge-preference-tier ordered-layers :strong-preferences)
     :weak-preferences    (merge-preference-tier ordered-layers :weak-preferences)
     :context             (apply merge {} (map :context ordered-layers))
     :model               (some :model (reverse ordered-layers))}))

(defn- candidate-attribute
  [candidate criterion]
  (let [k (or (:criterion criterion) (:attribute criterion))]
    (or (get-in candidate [:facts k])
        (get-in candidate [:estimates k])
        (get-in candidate [:reference k])
        (get candidate k))))

(defn- required-constraint-satisfied?
  [candidate constraint]
  (let [value (candidate-attribute candidate constraint)]
    (cond
      (contains? constraint :match)
      (= value (case (:match constraint)
                 :true true
                 :false false
                 (:match constraint)))

      (contains? constraint :at-least)
      (and (number? value)
           (>= value (:at-least constraint)))

      (contains? constraint :at-most)
      (and (number? value)
           (<= value (:at-most constraint)))

      (contains? constraint :equals)
      (= value (:equals constraint))

      :else
      (boolean value))))

(defn- explicit-request-candidates
  [catalog request]
  (if-let [{:keys [provider id]} (:model request)]
    (if-let [candidate (find-candidate catalog provider id)]
      [candidate]
      [])
    []))

(defn- inherit-session-candidates
  [catalog request]
  (let [provider (or (get-in request [:context :session-model :provider])
                     (get-in request [:context :session-provider]))
        id       (or (get-in request [:context :session-model :id])
                     (get-in request [:context :session-model-id]))]
    (if (and provider id)
      (if-let [candidate (find-candidate catalog provider id)]
        [candidate]
        [])
      [])))

(defn candidate-pool
  "Return the candidate pool implied by request mode before required filtering."
  [catalog request]
  (case (:mode request)
    :explicit        (explicit-request-candidates catalog request)
    :inherit-session (inherit-session-candidates catalog request)
    :resolve         (:candidates catalog)
    (:candidates catalog)))

(defn filter-candidates
  "Apply stage-1 resolver filtering.

   Returns:
   {:pool [...]
    :survivors [...]
    :eliminated [{:candidate c :reasons [constraint*]} ...]}

   v1 filtering includes:
   - mode-specific candidate pool restriction
   - required constraint checks over that pool

   This does not yet rank survivors or produce the final resolver result."
  [catalog request]
  (let [pool      (vec (candidate-pool catalog request))
        required  (:required request)
        results   (mapv (fn [candidate]
                          (let [failed (->> required
                                            (remove #(required-constraint-satisfied? candidate %))
                                            vec)]
                            {:candidate candidate
                             :failed    failed}))
                        pool)]
    {:pool       pool
     :survivors  (->> results
                      (filter #(empty? (:failed %)))
                      (mapv :candidate))
     :eliminated (->> results
                      (remove #(empty? (:failed %)))
                      (mapv (fn [{:keys [candidate failed]}]
                              {:candidate candidate
                               :reasons   failed})))}))
