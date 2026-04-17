(ns psi.memory.runtime
  "Runtime hooks for feeding live system activity into the memory layer.

   This namespace bridges runtime operations (session bootstrap, prompts,
   streaming messages) with psi.memory.core lifecycle functions."
  (:require
   [clojure.string :as str]
   [psi.graph.analysis :as graph]
   [psi.history.git :as git]
   [psi.memory.core :as memory]
   [psi.memory.store :as store]
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

(defonce ^:private git-head-cache
  (atom {}))

(defn- classify-reflog-subject
  [subject]
  (let [s (some-> subject str/trim str/lower-case)]
    (cond
      (str/blank? s) :unknown
      (str/starts-with? s "commit (amend):") :amend
      (str/starts-with? s "commit:") :commit-created
      (str/starts-with? s "merge") :merge
      (str/starts-with? s "rebase") :rebase
      (str/starts-with? s "reset") :reset
      (str/starts-with? s "checkout") :checkout
      (str/starts-with? s "cherry-pick") :cherry-pick
      :else :unknown)))

(defn- classify-head-change
  [git-ctx head]
  (let [op-state        (git/operation-state git-ctx)
        reflog-entry    (git/head-reflog-latest git-ctx)
        reflog-subject  (:subject reflog-entry)
        reflog-kind     (classify-reflog-subject reflog-subject)
        parent-count    (git/commit-parent-count git-ctx head)
        kind            (cond
                          (:transient? op-state) :transient
                          (and (= :commit-created reflog-kind)
                               (= 1 parent-count)) :commit-created
                          (and (= :commit-created reflog-kind)
                               (number? parent-count)
                               (> parent-count 1)) :merge-commit
                          :else reflog-kind)]
    {:kind kind
     :notify-extensions? (= :commit-created kind)
     :reflog-subject reflog-subject
     :reflog-selector (:selector reflog-entry)
     :parent-count parent-count
     :operation-state op-state
     :transient? (true? (:transient? op-state))}))

(defn- cached-head-for-cwd
  [cwd]
  (get @git-head-cache cwd))

(defn- cache-head-for-cwd!
  [cwd head]
  (when (and (string? cwd)
             (not (str/blank? cwd))
             (string? head)
             (not (str/blank? head)))
    (swap! git-head-cache assoc cwd head)
    head))

(defn- current-head
  [git-ctx]
  (try
    (let [sha (some-> (git/current-commit git-ctx) str/trim)]
      (when-not (str/blank? sha)
        sha))
    (catch Exception _
      nil)))

(defn- normalize-cwd
  [{:keys [cwd git-ctx]}]
  (or (some-> cwd str str/trim not-empty)
      (some-> (:repo-dir git-ctx) str str/trim not-empty)
      (System/getProperty "user.dir")))

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

(defn- getenv
  [k]
  (System/getenv k))

(defn- parse-store-provider
  [store-provider]
  (cond
    (keyword? store-provider) store-provider
    (string? store-provider) (keyword (str/lower-case (str/trim store-provider)))
    :else nil))

(defn- parse-bool-value
  [v]
  (cond
    (boolean? v) v
    (string? v)  (let [x (str/lower-case (str/trim v))]
                   (cond
                     (contains? #{"1" "true" "yes" "y" "on"} x) true
                     (contains? #{"0" "false" "no" "n" "off"} x) false
                     :else nil))
    :else nil))

(defn- parse-positive-int
  [v]
  (cond
    (and (integer? v) (pos? v)) v
    (and (number? v) (pos? v)) (int v)
    (string? v) (try
                  (let [n (Integer/parseInt (str/trim v))]
                    (when (pos? n) n))
                  (catch Exception _
                    nil))
    :else nil))

(defn- resolve-runtime-config
  [{:keys [cwd
           history-commit-limit
           store-provider
           auto-store-fallback?
           retention-snapshots
           retention-deltas]}]
  (let [store-provider*        (or (parse-store-provider store-provider)
                                   (parse-store-provider (getenv "PSI_MEMORY_STORE"))
                                   :in-memory)
        explicit-auto-fallback (parse-bool-value auto-store-fallback?)
        env-auto-fallback      (parse-bool-value (getenv "PSI_MEMORY_STORE_AUTO_FALLBACK"))
        auto-fallback*         (cond
                                 (some? explicit-auto-fallback) explicit-auto-fallback
                                 (some? env-auto-fallback) env-auto-fallback
                                 :else true)
        history-limit*         (or (parse-positive-int history-commit-limit)
                                   (parse-positive-int (getenv "PSI_MEMORY_HISTORY_COMMIT_LIMIT"))
                                   200)
        retention-snapshots*   (or (parse-positive-int retention-snapshots)
                                   (parse-positive-int (getenv "PSI_MEMORY_RETENTION_SNAPSHOTS")))
        retention-deltas*      (or (parse-positive-int retention-deltas)
                                   (parse-positive-int (getenv "PSI_MEMORY_RETENTION_DELTAS")))]
    (cond-> {:cwd cwd
             :history-commit-limit history-limit*
             :auto-store-fallback? auto-fallback*}
      store-provider*              (assoc :store-provider store-provider*)
      (some? retention-snapshots*) (assoc :retention-snapshots retention-snapshots*)
      (some? retention-deltas*)    (assoc :retention-deltas retention-deltas*))))

(defn- maybe-register-store-provider!
  [memory-ctx {:keys [store-provider auto-store-fallback?]}]
  (let [requested-provider-id (some-> store-provider name)]
    (case store-provider
      :in-memory
      {:ok? true
       :provider :in-memory
       :store-summary (memory/select-store-provider-in!
                       memory-ctx
                       store/+default-provider-id+
                       {:auto-fallback? auto-store-fallback?})}

      nil
      {:ok? true
       :provider (keyword (:active-provider-id (memory/store-summary-in memory-ctx)))
       :store-summary (memory/store-summary-in memory-ctx)}

      (let [summary (memory/select-store-provider-in!
                     memory-ctx
                     requested-provider-id
                     {:auto-fallback? auto-store-fallback?})]
        {:ok? false
         :provider store-provider
         :error :unknown-store-provider
         :available-providers (mapv :id (:providers summary))
         :store-summary summary}))))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]
        :export true}
  sync-memory-layer!
  "Run memory lifecycle sync against current runtime graph.

   - resolves runtime memory config from explicit opts + env vars
   - optionally registers/selects configured memory store provider
   - applies runtime retention overrides for graph snapshot/delta compaction
   - activates memory layer with global query registry snapshot
   - captures capability graph snapshot/delta when graph is stable
   - ingests git history commit summaries into memory records

   Options (explicit opts override env):
   - :cwd — repository root used for git-history activation gate
   - :history-commit-limit — max git commits imported into memory (default 200)
   - :store-provider — :in-memory (optional)
   - :auto-store-fallback? — fallback to in-memory if selected provider unavailable
   - :retention-snapshots — snapshot retention limit override
   - :retention-deltas — delta retention limit override

   Advanced/embedding overrides (primarily for tests/hooks):
   - :memory-ctx — explicit memory context (default memory/global-context)
   - :git-ctx — explicit git context (default git/create-context cwd)
   - :query-ctx — explicit query context (default build-global-query-context)

   Env vars:
   - PSI_MEMORY_STORE
   - PSI_MEMORY_STORE_AUTO_FALLBACK
   - PSI_MEMORY_HISTORY_COMMIT_LIMIT
   - PSI_MEMORY_RETENTION_SNAPSHOTS
   - PSI_MEMORY_RETENTION_DELTAS

   Returns {:runtime-config ... :store-registration ... :activation ...
            :capture ... :history-sync ... :capability-graph ...}."
  ([]
   (sync-memory-layer! {}))
  ([opts]
   (let [cwd*             (normalize-cwd opts)
         runtime-config   (resolve-runtime-config (assoc opts :cwd cwd*))
         runtime-config*  (assoc runtime-config :cwd cwd*)
         capability-graph (current-capability-graph)
         query-ctx        (or (:query-ctx opts) (build-global-query-context))
         git-ctx          (or (:git-ctx opts) (git/create-context cwd*))
         memory-ctx       (or (:memory-ctx opts) (memory/global-context))
         git-head         (current-head git-ctx)
         _                (cache-head-for-cwd! cwd* git-head)
         _                (when (or (some? (:retention-snapshots runtime-config*))
                                    (some? (:retention-deltas runtime-config*)))
                            (memory/set-retention-in! memory-ctx
                                                      {:snapshots (:retention-snapshots runtime-config*)
                                                       :deltas (:retention-deltas runtime-config*)}))
         store-registration (maybe-register-store-provider! memory-ctx runtime-config*)
         activation       (memory/activate-in! memory-ctx
                                               {:query-ctx query-ctx
                                                :git-ctx git-ctx
                                                :capability-graph-status (:status capability-graph)})
         capture          (if (contains? #{:stable :expanding} (:status capability-graph))
                            (memory/capture-graph-change-in! memory-ctx capability-graph)
                            {:ok? false
                             :changed? false
                             :error :graph-not-ready
                             :graph-status (:status capability-graph)})
         history-sync     (if (:ready? activation)
                            (ingest-git-history-in! memory-ctx git-ctx
                                                    {:n (:history-commit-limit runtime-config*)})
                            {:ok? false
                             :skipped? true
                             :reason :memory-not-ready})]
     {:runtime-config (dissoc runtime-config* :store-migration-hooks)
      :store-registration store-registration
      :activation activation
      :capture capture
      :history-sync history-sync
      :capability-graph capability-graph
      :git-head git-head})))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]
        :export true}
  maybe-sync-on-git-head-change!
  "Maybe run memory sync when HEAD changed since last observed value for cwd.

   Behavior:
   - first observed HEAD for cwd initializes baseline only (no sync)
   - unchanged HEAD => no-op
   - changed HEAD => runs `sync-memory-layer!` and returns sync payload

   Options mirror `sync-memory-layer!`; additionally accepts:
   - :cwd
   - :git-ctx
   - :memory-ctx

   Returns:
   {:ok? bool :changed? bool :reason keyword
    :head string? :previous-head string? :sync map?}"
  ([]
   (maybe-sync-on-git-head-change! {}))
  ([{:keys [git-ctx memory-ctx] :as opts}]
   (let [cwd*      (normalize-cwd opts)
         git-ctx*  (or git-ctx (git/create-context cwd*))
         memory-ctx* (or memory-ctx (memory/global-context))
         head      (current-head git-ctx*)]
     (cond
       (str/blank? head)
       {:ok? false
        :changed? false
        :reason :git-head-unavailable
        :cwd cwd*}

       :else
       (let [previous-head (cached-head-for-cwd cwd*)]
         (cond
           (nil? previous-head)
           (do
             (cache-head-for-cwd! cwd* head)
             {:ok? true
              :changed? false
              :reason :head-baseline-established
              :head head
              :previous-head nil})

           (= previous-head head)
           {:ok? true
            :changed? false
            :reason :head-unchanged
            :head head
            :previous-head previous-head}

           :else
           (let [classification (classify-head-change git-ctx* head)
                 sync-result (sync-memory-layer! (-> opts
                                                     (assoc :cwd cwd*)
                                                     (assoc :git-ctx git-ctx*)
                                                     (assoc :memory-ctx memory-ctx*)))
                 synced-head (or (:git-head sync-result) head)]
             ;; keep cache forward-progress even if sync omitted git-head in future edits
             (cache-head-for-cwd! cwd* synced-head)
             {:ok? true
              :changed? true
              :reason :head-changed
              :head synced-head
              :previous-head previous-head
              :classification classification
              :sync sync-result})))))))

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

