(ns psi.memory.runtime
  "Runtime hooks for feeding live system activity into the memory layer.

   This namespace bridges runtime operations (session bootstrap, prompts,
   streaming messages) with psi.memory.core lifecycle functions."
  (:require
   [clojure.string :as str]
   [psi.history.git :as git]
   [psi.introspection.graph :as graph]
   [psi.memory.core :as memory]
   [psi.query.core :as query]
   [psi.query.registry :as registry]))

(defn- operation-metadata
  []
  {:resolver-ops (mapv #(graph/operation->metadata :resolver %) (registry/all-resolvers))
   :mutation-ops (mapv #(graph/operation->metadata :mutation %) (registry/all-mutations))})

(defn- fingerprint-input
  [cgraph]
  {:node-ids          (->> (:nodes cgraph)
                           (map :id)
                           (sort)
                           (vec))
   :edge-links        (->> (:edges cgraph)
                           (map (fn [{:keys [from to attribute]}]
                                  [from to (str attribute)]))
                           (sort)
                           (vec))
   :capability-ids    (->> (:capabilities cgraph)
                           (map :id)
                           (sort)
                           (vec))
   :operation-symbols (->> (:capabilities cgraph)
                           (mapcat :operation-symbols)
                           (map str)
                           (sort)
                           (vec))})

(defn current-capability-graph
  "Return current capability graph enriched with fingerprint and status.

   Status semantics:
   - :stable when graph has at least one node and one edge
   - :emerging otherwise"
  []
  (let [cgraph      (graph/derive-capability-graph (operation-metadata))
        node-count  (count (:nodes cgraph))
        edge-count  (count (:edges cgraph))
        status      (if (and (pos? node-count) (pos? edge-count))
                      :stable
                      :emerging)
        fingerprint (str "cg-" (Math/abs (long (hash (fingerprint-input cgraph)))))]
    {:fingerprint     fingerprint
     :status          status
     :nodes           (:nodes cgraph)
     :edges           (:edges cgraph)
     :node-count      node-count
     :edge-count      edge-count
     :capability-ids  (->> (:capabilities cgraph)
                           (map :id)
                           (sort)
                           (vec))
     :operations      (->> (:capabilities cgraph)
                           (mapcat :operation-symbols)
                           (map str)
                           (sort)
                           (vec))
     :domain-coverage (:domain-coverage cgraph)}))

(defn- build-global-query-context
  []
  (let [qctx (query/create-query-context)]
    (doseq [r (registry/all-resolvers)]
      (query/register-resolver-in! qctx r))
    (doseq [m (registry/all-mutations)]
      (query/register-mutation-in! qctx m))
    (query/rebuild-env-in! qctx)
    qctx))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]
        :export true}
  sync-memory-layer!
  "Run memory lifecycle sync against current runtime graph.

   - activates memory layer with global query registry snapshot
   - captures capability graph snapshot/delta when graph is stable

   Options:
   - :cwd — repository root used for git-history activation gate

   Returns {:activation ... :capture ... :capability-graph ...}."
  ([]
   (sync-memory-layer! {}))
  ([{:keys [cwd]}]
   (let [capability-graph (current-capability-graph)
         query-ctx        (build-global-query-context)
         git-ctx          (git/create-context (or cwd (System/getProperty "user.dir")))
         activation       (memory/activate! {:query-ctx query-ctx
                                             :git-ctx git-ctx
                                             :capability-graph-status (:status capability-graph)})
         capture          (if (contains? #{:stable :expanding} (:status capability-graph))
                            (memory/capture-graph-change! capability-graph)
                            {:ok? false
                             :changed? false
                             :error :graph-not-ready
                             :graph-status (:status capability-graph)})]
     {:activation activation
      :capture capture
      :capability-graph capability-graph})))

(defn- block->text
  [block]
  (case (:type block)
    :text (:text block)
    :error (:text block)
    :tool-call (str "[tool-call " (:name block) "]")
    nil))

(defn- message->text
  [message]
  (let [content (:content message)]
    (cond
      (string? content)
      content

      (sequential? content)
      (->> content
           (keep block->text)
           (str/join "\n"))

      :else
      "")))

(defn- role->content-type
  [role]
  (case role
    "user" :session-user-message
    "assistant" :session-assistant-message
    "toolResult" :session-tool-result
    "custom" :session-custom-message
    :session-message))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]
        :export true}
  remember-session-message!
  "Remember a runtime session message into memory.

   opts:
   - :session-id  optional session id for provenance
   - :extra-tags  optional vector of additional tags"
  ([message]
   (remember-session-message! message {}))
  ([message {:keys [session-id extra-tags]}]
   (let [role             (:role message)
         content          (or (not-empty (str/trim (message->text message)))
                              (pr-str (select-keys message [:role :content :tool-call-id :tool-name :custom-type :is-error])))
         tags             (->> (concat [:session :message (keyword (or role "unknown"))]
                                       (when (:is-error message) [:error])
                                       (when-let [ct (:custom-type message)]
                                         [(str "custom-type:" ct)])
                                       extra-tags)
                               (remove nil?)
                               (distinct)
                               (vec))
         capability-graph (current-capability-graph)]
     (memory/remember! {:content-type      (role->content-type role)
                        :content           content
                        :tags              tags
                        :provenance        {:source :session
                                            :sessionId session-id}
                        :timestamp         (or (:timestamp message)
                                               (java.time.Instant/now))
                        :capability-graph  capability-graph}))))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]
        :export true}
  recover-for-query!
  "Run memory recovery for user query text and store search/recovery attrs.

   opts keys (all optional):
   :limit :tags :capability-ids :content-types :since :ranking-weights :now"
  ([query-text]
   (recover-for-query! query-text {}))
  ([query-text opts]
   (let [q (some-> query-text str str/trim)]
     (when-not (str/blank? q)
       (let [capability-graph (current-capability-graph)]
         (when (contains? #{:stable :expanding} (:status capability-graph))
           (memory/capture-graph-change! capability-graph))
         (memory/recover!
          (merge {:query-text q
                  :sources [:session :history :graph]}
                 (select-keys opts
                              [:limit :tags :capability-ids :content-types :since :ranking-weights :now]))))))))

