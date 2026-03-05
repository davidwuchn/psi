(ns psi.memory.runtime
  "Runtime hooks for feeding live system activity into the memory layer.

   This namespace bridges runtime operations (session bootstrap, prompts,
   streaming messages) with psi.memory.core lifecycle functions."
  (:require
   [clojure.string :as str]
   [psi.history.git :as git]
   [psi.introspection.graph :as graph]
   [psi.memory.core :as memory]
   [psi.memory.datalevin :as datalevin]
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

(defn- record-source
  [record]
  (or (get-in record [:provenance :source])
      (get-in record [:provenance :source-type])))

(defn- history-record?
  [record]
  (contains? #{:history :git} (record-source record)))

(defn- existing-history-shas
  [memory-ctx]
  (->> (memory/get-state-in memory-ctx)
       :records
       (keep (fn [record]
               (when (history-record? record)
                 (or (get-in record [:provenance :commitSha])
                     (get-in record [:provenance :sha])))))
       set))

(defn- parse-commit-date
  [v]
  (try
    (some-> v java.time.Instant/parse)
    (catch Exception _
      nil)))

(defn- commit->tags
  [commit]
  (let [symbols (set (or (:git.commit/symbols commit) #{}))]
    (cond-> [:history :git :commit]
      (contains? symbols "λ") (conj :learning)
      (contains? symbols "Δ") (conj :delta))))

(defn- commit->content
  [{:git.commit/keys [sha date author email subject symbols]}]
  (let [symbols-text (->> symbols sort (str/join " "))]
    (str (or subject "")
         "\nsha: " (or sha "")
         "\ndate: " (or date "")
         "\nauthor: " (str/trim (str (or author "") " <" (or email "") ">"))
         (when-not (str/blank? symbols-text)
           (str "\nsymbols: " symbols-text)))))

(defn ingest-git-history-in!
  "Ingest recent git commits from `git-ctx` into isolated `memory-ctx`.

   Records are stored as :git-commit entries with :source :history and
   deduplicated by commit SHA in provenance (:commitSha).

   Options:
   - :n — max commits to ingest from git log (default 200)

   Returns a summary map with import/skip counts."
  ([memory-ctx git-ctx]
   (ingest-git-history-in! memory-ctx git-ctx {}))
  ([memory-ctx git-ctx {:keys [n] :or {n 200}}]
   (try
     (let [commits (vec (git/log git-ctx {:n n}))
           now     (java.time.Instant/now)
           result  (reduce (fn [{:keys [seen] :as acc} commit]
                             (let [sha (:git.commit/sha commit)]
                               (cond
                                 (str/blank? sha)
                                 (update acc :skipped-count inc)

                                 (contains? seen sha)
                                 (update acc :skipped-count inc)

                                 :else
                                 (let [remember-result
                                       (memory/remember-in! memory-ctx
                                                           {:content-type :git-commit
                                                            :content      (commit->content commit)
                                                            :tags         (commit->tags commit)
                                                            :timestamp    (or (parse-commit-date (:git.commit/date commit))
                                                                              now)
                                                            :provenance   {:source :history
                                                                           :source-type :git
                                                                           :commitSha sha
                                                                           :author (:git.commit/author commit)
                                                                           :email (:git.commit/email commit)}})]
                                   (if (:ok? remember-result)
                                     (-> acc
                                         (update :seen conj sha)
                                         (update :imported-count inc))
                                     (update acc :error-count inc))))))
                           {:seen (existing-history-shas memory-ctx)
                            :imported-count 0
                            :skipped-count 0
                            :error-count 0}
                           commits)]
       (-> result
           (dissoc :seen)
           (assoc :ok? true
                  :seen-count (count commits)
                  :memory-entry-count (get-in (memory/get-state-in memory-ctx)
                                              [:index-stats :entry-count]))))
     (catch Exception e
       {:ok? false
        :error :git-history-ingest-failed
        :message (ex-message e)}))))

(defn- build-global-query-context
  []
  (let [qctx (query/create-query-context)]
    (doseq [r (registry/all-resolvers)]
      (query/register-resolver-in! qctx r))
    (doseq [m (registry/all-mutations)]
      (query/register-mutation-in! qctx m))
    (query/rebuild-env-in! qctx)
    qctx))

(defn- parse-store-provider
  [store-provider]
  (cond
    (keyword? store-provider) store-provider
    (string? store-provider) (keyword (str/lower-case (str/trim store-provider)))
    :else nil))

(defn- maybe-register-store-provider!
  [memory-ctx {:keys [store-provider cwd store-root store-db-dir]}]
  (let [provider (or (parse-store-provider store-provider)
                     (parse-store-provider (System/getenv "PSI_MEMORY_STORE")))]
    (case provider
      :datalevin
      (try
        (datalevin/register-in-memory-context! memory-ctx
                                               {:cwd cwd
                                                :store-root store-root
                                                :db-dir store-db-dir
                                                :select? true
                                                :open? true})
        {:ok? true
         :provider :datalevin
         :store-summary (memory/store-summary-in memory-ctx)}
        (catch Exception e
          {:ok? false
           :provider :datalevin
           :error :store-provider-registration-failed
           :message (ex-message e)
           :store-summary (memory/store-summary-in memory-ctx)}))

      {:ok? true
       :provider :in-memory
       :store-summary (memory/store-summary-in memory-ctx)})))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]
        :export true}
  sync-memory-layer!
  "Run memory lifecycle sync against current runtime graph.

   - optionally registers/selects configured memory store provider
   - activates memory layer with global query registry snapshot
   - captures capability graph snapshot/delta when graph is stable
   - ingests git history commit summaries into memory records

   Options:
   - :cwd — repository root used for git-history activation gate
   - :history-commit-limit — max git commits imported into memory (default 200)
   - :store-provider — :datalevin or :in-memory (optional)
   - :store-root — provider-specific store root (datalevin)
   - :store-db-dir — explicit provider db dir override (datalevin)

   Returns {:store-registration ... :activation ... :capture ... :history-sync ... :capability-graph ...}."
  ([]
   (sync-memory-layer! {}))
  ([{:keys [cwd history-commit-limit store-provider store-root store-db-dir]
     :or   {history-commit-limit 200}}]
   (let [capability-graph   (current-capability-graph)
         query-ctx          (build-global-query-context)
         git-ctx            (git/create-context (or cwd (System/getProperty "user.dir")))
         memory-ctx         (memory/global-context)
         store-registration (maybe-register-store-provider! memory-ctx
                                                            {:store-provider store-provider
                                                             :cwd cwd
                                                             :store-root store-root
                                                             :store-db-dir store-db-dir})
         activation         (memory/activate! {:query-ctx query-ctx
                                               :git-ctx git-ctx
                                               :capability-graph-status (:status capability-graph)})
         capture            (if (contains? #{:stable :expanding} (:status capability-graph))
                              (memory/capture-graph-change! capability-graph)
                              {:ok? false
                               :changed? false
                               :error :graph-not-ready
                               :graph-status (:status capability-graph)})
         history-sync       (if (:ready? activation)
                              (ingest-git-history-in! memory-ctx git-ctx {:n history-commit-limit})
                              {:ok? false
                               :skipped? true
                               :reason :memory-not-ready})]
     {:store-registration store-registration
      :activation activation
      :capture capture
      :history-sync history-sync
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

