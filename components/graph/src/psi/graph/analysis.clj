(ns psi.graph.analysis
  "Graph analysis functions moved from psi.introspection.graph to break circular dependency.
   
   Pure Step 7 capability-graph derivation helpers.

   The functions in this namespace operate on operation metadata and return
   deterministic graph shapes for introspection resolvers.

   This is a capability graph, not a full dependency graph:
   - nodes represent operations and per-domain capabilities
   - edges represent operation -> capability membership
   - edge :attribute is annotation metadata, not an endpoint node

   Node types in Step 7 are restricted to:
   - :resolver
   - :mutation
   - :capability

   Edge :attribute values may be:
   - keyword attr
   - join map attr
   - nil, for operations with no IO attrs

   Mutation side effects are deferred in Step 7 and are represented as nil.
  "
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]))

(def required-domains
  "Required Step 7 domains for normalized graph coverage.

   These domains are always present in :psi.graph/domain-coverage, even when
   they currently have zero operations. They are not guaranteed to appear in
   :psi.graph/capabilities, which reports only domains present in the graph."
  [:ai :history :agent-session :introspection])

(defn classify-domain
  "Classify an operation symbol into a Step 7 domain keyword."
  [op-sym]
  (let [n (namespace op-sym)]
    (cond
      (str/starts-with? n "psi.ai") :ai
      (str/starts-with? n "psi.history") :history
      (str/starts-with? n "psi.introspection") :introspection
      (str/starts-with? n "psi.memory") :memory
      (str/starts-with? n "psi.recursion") :recursion
      (or (str/starts-with? n "psi.agent-session")
          (str/starts-with? n "psi.extension")) :agent-session
      :else :unknown)))

(defn operation->metadata
  "Extract normalized metadata from a Pathom operation.

   Returns map keys:
   - :op-type   (:resolver | :mutation)
   - :symbol    qualified symbol
   - :input     vector
   - :output    vector
   - :params    vector"
  [op-type operation]
  (let [cfg (pco/operation-config operation)]
    {:op-type op-type
     :symbol  (::pco/op-name cfg)
     :input   (vec (or (::pco/input cfg) []))
     :output  (vec (or (::pco/output cfg) []))
     :params  (vec (or (::pco/params cfg) []))}))

(defn operation-node-id
  [{:keys [op-type symbol]}]
  (str (name op-type) ":" symbol))

(defn capability-node-id
  [domain]
  (str "capability:" (name domain)))

(defn domain-operation
  "Build a normalized domain operation record from operation metadata."
  [{:keys [op-type symbol input output params] :as op}]
  (let [domain      (classify-domain symbol)
        io-attrs    (vec (distinct (concat input output params)))
        operation-id (operation-node-id op)]
    {:id          operation-id
     :symbol      symbol
     :domain      domain
     :type        op-type
     :io          {:input input :output output :params params}
     :attributes  io-attrs
     :sideEffects (when (= op-type :mutation) nil)}))

(defn derive-domain-operations
  "Derive sorted domain operation records from resolver/mutation metadata."
  [{:keys [resolver-ops mutation-ops]}]
  (->> (concat resolver-ops mutation-ops)
       (map domain-operation)
       (sort-by (comp str :symbol))
       vec))

(defn derive-capabilities
  "Derive rich capability summaries grouped by domain.

   Reports only domains present in the graph. Each summary includes operation
   counts plus extra detail such as operation symbols and IO attributes."
  [domain-ops]
  (->> domain-ops
       (group-by :domain)
       (map (fn [[domain ops]]
              {:id               (capability-node-id domain)
               :domain           domain
               :operation-count  (count ops)
               :resolver-count   (count (filter #(= :resolver (:type %)) ops))
               :mutation-count   (count (filter #(= :mutation (:type %)) ops))
               :operation-symbols (vec (map :symbol ops))
               :attributes       (vec (distinct (mapcat :attributes ops)))}))
       (sort-by (comp str :domain))
       vec))

(defn derive-nodes
  "Derive graph nodes (resolver/mutation/capability only)."
  [domain-ops capabilities]
  (vec
   (concat
    (map (fn [{:keys [id symbol domain type sideEffects]}]
           (cond-> {:id id :type type :symbol symbol :domain domain}
             (= type :mutation) (assoc :sideEffects sideEffects)))
         domain-ops)
    (map (fn [{:keys [id domain operation-count]}]
           {:id id :type :capability :domain domain :operation-count operation-count})
         capabilities))))

(defn derive-edges
  "Derive operation→capability edges with :attribute annotation metadata.

   These edges do not represent resolver dependency flow or attr reachability.
   They encode that an operation belongs to a capability/domain, annotated by
   each IO attr exposed by that operation.

   For operations with no IO attrs, emits one edge with nil :attribute so
   graph linkage is still explicit."
  [domain-ops]
  (->> domain-ops
       (mapcat (fn [{:keys [id domain attributes]}]
                 (let [cap-id (capability-node-id domain)
                       attrs  (seq attributes)]
                   (if attrs
                     (map (fn [attr]
                            {:from id :to cap-id :attribute attr})
                          attrs)
                     [{:from id :to cap-id :attribute nil}]))))
       vec))

(defn derive-domain-coverage
  "Derive deterministic normalized domain coverage summary.

   Includes required Step 7 domains even when they have zero operations.
   This differs from derive-capabilities, which reports only domains present in
   the graph and includes richer per-domain detail."
  [domain-ops]
  (let [grouped        (group-by :domain domain-ops)
        present-domains (set (keys grouped))
        ordered-domains (vec (concat required-domains
                                     (sort (remove (set required-domains)
                                                   present-domains))))]
    (mapv (fn [domain]
            (let [ops (get grouped domain [])]
              {:domain          domain
               :resolver-count  (count (filter #(= :resolver (:type %)) ops))
               :mutation-count  (count (filter #(= :mutation (:type %)) ops))
               :operation-count (count ops)}))
          ordered-domains)))

(defn- root-io-attrs
  "Return only top-level attrs from Pathom IO metadata.

   For root queryability we care about attrs directly reachable from the root
   entity. Nested attrs inside joins are intentionally excluded.

   Examples:
   - [:a :b]                    => [:a :b]
   - [{:x [:y :z]} :a]          => [:x :a]
   - [{:x [{:y [:z]}]}]         => [:x]"
  [xs]
  (keep (fn [x]
          (cond
            (keyword? x)
            x

            (map? x)
            (first (keys x))

            :else
            nil))
        xs))

(defn derive-root-queryable-attrs
  "Compute attrs reachable from an initial seed set using resolver IO metadata.

   This is a fixed-point reachability pass over resolver operations only:
   if all resolver inputs are already known, all its outputs become known.

   Contract:
   - only resolvers contribute to reachability; mutations do not
   - seed attrs are not included in the returned result
   - only root-resolvable attrs are returned; attrs with additional non-root
     inputs are excluded unless those inputs are themselves reachable from roots
   - only keyword attrs are returned
   - result is sorted for deterministic discovery

   Returns a sorted vector of reachable attrs (excluding the seed attrs)."
  ([resolver-ops]
   (derive-root-queryable-attrs resolver-ops #{}))
  ([resolver-ops seed-attrs]
   (let [seed-attrs (set seed-attrs)
         ops        (mapv (fn [{:keys [input output] :as op}]
                            (assoc op
                                   :input*  (vec (root-io-attrs input))
                                   :output* (vec (root-io-attrs output))))
                          resolver-ops)]
     (loop [known seed-attrs
            attr->inputs {}]
       (let [{known' :known attr->inputs' :attr->inputs}
             (reduce (fn [{:keys [known attr->inputs] :as acc}
                          {:keys [input* output*]}]
                       (if (every? known input*)
                         {:known        (into known output*)
                          :attr->inputs (reduce (fn [m attr]
                                                  (update m attr
                                                          (fn [cur]
                                                            (let [cur (or cur #{})
                                                                  new (set input*)]
                                                              (if (or (empty? cur)
                                                                      (< (count new) (count cur)))
                                                                new
                                                                cur)))))
                                                attr->inputs
                                                output*)}
                         acc))
                     {:known known :attr->inputs attr->inputs}
                     ops)]
         (if (= known known')
           (->> known'
                (remove seed-attrs)
                (filter keyword?)
                (filter (fn [attr]
                          (let [required-inputs (get attr->inputs' attr #{})]
                            (every? known' required-inputs))))
                (sort-by str)
                vec)
           (recur known' attr->inputs')))))))

;; ── Resolver I/O surface ────────────────────────────────

(def ^:private internal-seed-keys
  "Pathom seed keys injected into the entity map that are not user-facing attrs."
  #{:psi/agent-session-ctx
    :psi/memory-ctx
    :psi/recursion-ctx
    :psi/engine-ctx})

(defn- psi-attr?
  "True for keyword attrs in a psi.* namespace (not internal seed keys)."
  [x]
  (and (keyword? x)
       (not (contains? internal-seed-keys x))
       (when-let [ns (namespace x)]
         (str/starts-with? ns "psi."))))

(defn- filter-psi-io
  "Filter a Pathom I/O vector to psi.* attrs only, preserving join map structure.
   Join maps whose key is a psi.* attr have their child vectors filtered too.
   Internal seed keys and non-psi attrs are removed."
  [io-vec]
  (into []
        (keep (fn [x]
                (cond
                  (keyword? x)
                  (when (psi-attr? x) x)

                  (map? x)
                  (let [filtered (into {}
                                       (keep (fn [[k v]]
                                               (when (psi-attr? k)
                                                 [k (filterv psi-attr? v)]))
                                             x))]
                    (when (seq filtered) filtered))

                  :else nil)))
        io-vec))

(defn derive-resolver-index
  "Derive a sorted vector of resolver I/O descriptors filtered to psi.* attrs.

   Each entry:
   {:psi.resolver/sym    <qualified-symbol>
    :psi.resolver/input  [<psi-attrs>]
    :psi.resolver/output [<psi-attrs-and-join-maps>]}

   Sorted by sym for deterministic output."
  [resolver-ops]
  (->> resolver-ops
       (map (fn [{:keys [symbol input output]}]
              {:psi.resolver/sym    symbol
               :psi.resolver/input  (filter-psi-io input)
               :psi.resolver/output (filter-psi-io output)}))
       (sort-by (comp str :psi.resolver/sym))
       vec))

(defn- output-entries
  "Expand a filtered output vector into [attr join-key-or-nil] pairs.
   Flat attrs produce [attr nil]. Join map entries produce [child-attr join-key]
   for each child attr."
  [output sym]
  (mapcat (fn [x]
            (cond
              (keyword? x)
              [[x {:produced-by sym :reachable-via nil}]]

              (map? x)
              (mapcat (fn [[join-key children]]
                        (map (fn [child]
                               [child {:produced-by sym :reachable-via join-key}])
                             children))
                      x)

              :else nil))
          output))

(defn derive-attr-index
  "Derive an attr→resolver index from a resolver-index.

   Each entry maps a psi.* attr keyword to:
   {:psi.attr/produced-by  [<resolver-syms>]
    :psi.attr/reachable-via {<join-key> [<resolver-syms>]}}

   :psi.attr/reachable-via is a map of join-key → syms for attrs that are
   only reachable inside a join. Flat attrs have an empty reachable-via map.

   Sorted by attr for deterministic output."
  [resolver-index]
  (let [raw (reduce (fn [acc {:keys [psi.resolver/sym psi.resolver/output]}]
                      (reduce (fn [acc [attr {:keys [produced-by reachable-via]}]]
                                (-> acc
                                    (update-in [attr :produced-by] (fnil conj []) produced-by)
                                    (cond-> reachable-via
                                      (update-in [attr :reachable-via reachable-via]
                                                 (fnil conj []) produced-by))))
                              acc
                              (output-entries output sym)))
                    {}
                    resolver-index)]
    (into (sorted-map)
          (map (fn [[attr {:keys [produced-by reachable-via]}]]
                 [attr {:psi.attr/produced-by   (vec (distinct produced-by))
                        :psi.attr/reachable-via (or reachable-via {})}]))
          raw)))

(defn derive-capability-graph
  "Derive complete Step 7 capability graph structure from operation metadata.

   Input map:
   {:resolver-ops [{:op-type :resolver ...}]
    :mutation-ops [{:op-type :mutation ...}]}

   Returns:
   {:domain-operations [...]
    :nodes [...]
    :edges [...]
    :capabilities [...]
    :domain-coverage [...]}"
  [{:keys [resolver-ops mutation-ops] :as operation-metadata}]
  (let [domain-ops    (derive-domain-operations operation-metadata)
        capabilities  (derive-capabilities domain-ops)]
    {:domain-operations domain-ops
     :nodes             (derive-nodes domain-ops capabilities)
     :edges             (derive-edges domain-ops)
     :capabilities      capabilities
     :domain-coverage   (derive-domain-coverage domain-ops)
     :operation-count   (+ (count resolver-ops) (count mutation-ops))}))
