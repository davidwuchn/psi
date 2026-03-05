(ns psi.memory.datalevin
  "Datalevin-backed persistent memory store provider.

   Uses Datalevin KV sub-databases to persist memory artifacts while keeping
   memory-layer ranking/query semantics in `psi.memory.core`."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [datalevin.core :as d]
   [psi.memory.store :as store])
  (:import
   (java.time Instant)))

(def ^:const +provider-id+
  "Default Datalevin memory provider id."
  "datalevin")

(def ^:const +dbi-records+ "memory-record")
(def ^:const +dbi-graph-snapshots+ "graph-snapshot")
(def ^:const +dbi-graph-deltas+ "graph-delta")
(def ^:const +dbi-recoveries+ "recovery-run")
(def ^:const +dbi-capability-history+ "capability-history")
(def ^:const +dbi-meta+ "meta")

(def ^:const +schema-version-key+
  "KV key storing datalevin schema version."
  "schema-version")

(def ^:const +default-schema-version+
  "Runtime schema version expected by this provider implementation."
  1)

(def ^:private all-dbis
  [+dbi-records+
   +dbi-graph-snapshots+
   +dbi-graph-deltas+
   +dbi-recoveries+
   +dbi-capability-history+
   +dbi-meta+])

(defn- now []
  (Instant/now))

(defn- user-home []
  (System/getProperty "user.home"))

(defn- default-store-root []
  (str (io/file (user-home) ".psi" "agent" "memory")))

(defn- encode-cwd-segment
  [cwd]
  (let [cwd* (or cwd "")
        stripped (str/replace cwd* #"^/" "")
        encoded (-> stripped
                    (str/replace #"[/:\\]" "-"))]
    (if (str/blank? encoded)
      "default"
      encoded)))

(defn default-db-dir
  "Compute default datalevin path from cwd.

   Creates per-project isolation under ~/.psi/agent/memory by default."
  [{:keys [cwd store-root]}]
  (str (io/file (or store-root (default-store-root))
                (str "--" (encode-cwd-segment cwd) "--")
                "memory.dtlv")))

(defn- ensure-parent-dir!
  [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))
    path))

(defn- kv-conn-open?
  [conn]
  (and conn (not (d/closed-kv? conn))))

(defn- status->health
  [status]
  (case status
    :ready :healthy
    :degraded :degraded
    :opening :degraded
    :migrating :degraded
    :closing :degraded
    :closed :unavailable
    :error :unavailable
    :unavailable
    :unavailable))

(defn- id-for-entity
  [entity-type payload]
  (case entity-type
    :memory-record (or (:record-id payload)
                       (:id payload)
                       (str (random-uuid)))
    :graph-snapshot (or (:snapshot-id payload)
                        (:fingerprint payload)
                        (str (random-uuid)))
    :graph-delta (or (:delta-id payload)
                     (str (random-uuid)))
    :recovery-run (or (:recovery-id payload)
                      (str (random-uuid)))
    :capability-history (or (:event-id payload)
                            (str (random-uuid)))
    (str (random-uuid))))

(defn- entity-type->dbi
  [entity-type]
  (case entity-type
    :memory-record +dbi-records+
    :graph-snapshot +dbi-graph-snapshots+
    :graph-delta +dbi-graph-deltas+
    :recovery-run +dbi-recoveries+
    :capability-history +dbi-capability-history+
    nil))

(defn- kv-range-values
  [conn dbi]
  (->> (d/get-range conn dbi [:all])
       (mapv second)))

(defn- update-index-stats
  [index-stats {:keys [content-type tags provenance]}]
  (let [source (or (:source provenance)
                   (:source-type provenance)
                   :unknown)]
    (-> index-stats
        (update :entry-count (fnil inc 0))
        (update-in [:by-type content-type] (fnil inc 0))
        (update-in [:by-source source] (fnil inc 0))
        ((fn [stats]
           (reduce (fn [acc tag]
                     (update-in acc [:by-tag tag] (fnil inc 0)))
                   stats
                   tags))))))

(defn- build-index-stats
  [records]
  (reduce update-index-stats
          {:entry-count 0
           :by-type {}
           :by-tag {}
           :by-source {}}
          (or records [])))

(defn- text-includes?
  [query-text record]
  (let [q (some-> query-text str str/lower-case str/trim)]
    (or (str/blank? q)
        (let [haystack (str/lower-case
                        (str (:content record) " " (or (:content-type record) "") " " (str/join " " (:tags record))))]
          (str/includes? haystack q)))))

(defn- filter-record?
  [{:keys [tags capability-ids content-types since query-text]} record]
  (let [record-tags      (set (:tags record))
        requested-tags   (set (or tags []))
        record-caps      (set (or (get-in record [:provenance :capabilityIds]) []))
        requested-caps   (set (or capability-ids []))
        requested-types  (set (or content-types []))
        timestamp        (:timestamp record)]
    (and
     (or (empty? requested-tags)
         (not-empty (set/intersection requested-tags record-tags)))
     (or (empty? requested-caps)
         (not-empty (set/intersection requested-caps record-caps)))
     (or (empty? requested-types)
         (contains? requested-types (:content-type record)))
     (or (nil? since)
         (and (instance? Instant timestamp)
              (not (neg? (compare timestamp since)))))
     (text-includes? query-text record))))

(defn- target-schema-version
  [config]
  (let [v (:schema-version config)]
    (if (and (integer? v) (pos? v))
      v
      +default-schema-version+)))

(defn- read-schema-version
  [conn]
  (let [v (d/get-value conn +dbi-meta+ +schema-version-key+)]
    (when (number? v)
      (int v))))

(defn- write-schema-version!
  [conn version]
  (d/transact-kv conn [[:put +dbi-meta+ +schema-version-key+ version]])
  version)

(defn- run-migration-hook!
  [hook {:keys [conn db-dir provider-id from-version to-version]}]
  (hook {:conn conn
         :db-dir db-dir
         :provider-id provider-id
         :from-version from-version
         :to-version to-version})
  :ok)

(defn- ensure-schema-version!
  [conn {:keys [db-dir id config]}]
  (let [target         (target-schema-version config)
        migration-hooks (:migration-hooks config)
        current        (read-schema-version conn)]
    (cond
      (nil? current)
      (do
        (write-schema-version! conn target)
        {:ok? true
         :from-version nil
         :to-version target
         :migrated? false})

      (= current target)
      {:ok? true
       :from-version current
       :to-version target
       :migrated? false}

      (> current target)
      {:ok? false
       :error :schema-version-ahead-of-runtime
       :message (str "Datalevin schema version " current
                     " is newer than runtime target " target)}

      :else
      (loop [from current]
        (if (= from target)
          {:ok? true
           :from-version current
           :to-version target
           :migrated? true}
          (let [hook (get migration-hooks from)]
            (if-not (fn? hook)
              {:ok? false
               :error :missing-migration-hook
               :message (str "Missing migration hook for schema version " from
                             " -> " (inc from)
                             " (target " target ")")}
              (let [to (inc from)]
                (run-migration-hook! hook {:conn conn
                                           :db-dir db-dir
                                           :provider-id id
                                           :from-version from
                                           :to-version to})
                (write-schema-version! conn to)
                (recur to)))))))))

(defrecord DatalevinProvider [id status-atom health-atom kv-atom config]
  store/StoreProvider
  (provider-id [_] id)

  (provider-capabilities [_]
    {:durability :persistent
     :supports-restart-recovery? true
     :supports-retention-compaction? true
     :supports-capability-history-query? true
     :query-mode :indexed})

  (open-provider! [provider _opts]
    (try
      (if (kv-conn-open? (:conn @kv-atom))
        (do
          (reset! status-atom :ready)
          (reset! health-atom {:status :healthy
                               :checked-at (now)
                               :details nil})
          provider)
        (let [db-dir  (ensure-parent-dir! (or (:db-dir config)
                                              (default-db-dir config)))
              _       (.mkdirs (io/file db-dir))
              conn    (d/open-kv db-dir {:max-dbs 32})]
          (try
            (reset! status-atom :opening)
            (doseq [dbi all-dbis]
              (d/open-dbi conn dbi))
            (reset! status-atom :migrating)
            (let [migration (ensure-schema-version! conn {:db-dir db-dir
                                                          :id id
                                                          :config config})]
              (when-not (:ok? migration)
                (throw (ex-info (or (:message migration) "Datalevin schema migration failed")
                                migration)))
              (reset! kv-atom {:conn conn
                               :db-dir db-dir})
              (reset! status-atom :ready)
              (reset! health-atom {:status :healthy
                                   :checked-at (now)
                                   :details nil})
              provider)
            (catch Exception e
              (when (kv-conn-open? conn)
                (d/close-kv conn))
              (throw e)))))
      (catch Exception e
        (reset! status-atom :error)
        (reset! health-atom {:status :unavailable
                             :checked-at (now)
                             :details (ex-message e)})
        provider)))

  (close-provider! [provider]
    (try
      (when-let [conn (:conn @kv-atom)]
        (when (kv-conn-open? conn)
          (d/close-kv conn)))
      (reset! kv-atom {})
      (reset! status-atom :closed)
      (reset! health-atom {:status :unavailable
                           :checked-at (now)
                           :details nil})
      provider
      (catch Exception e
        (reset! status-atom :error)
        (reset! health-atom {:status :unavailable
                             :checked-at (now)
                             :details (ex-message e)})
        provider)))

  (provider-status [_]
    @status-atom)

  (provider-health [_]
    (let [h @health-atom]
      (if (map? h)
        h
        {:status (status->health @status-atom)
         :checked-at (now)
         :details nil})))

  (provider-write! [_ entity-type payload]
    (if-let [dbi (entity-type->dbi entity-type)]
      (if-let [conn (:conn @kv-atom)]
        (try
          (let [entity-id (id-for-entity entity-type payload)
                stored-payload (assoc payload
                                      (case entity-type
                                        :memory-record :record-id
                                        :graph-snapshot :snapshot-id
                                        :graph-delta :delta-id
                                        :recovery-run :recovery-id
                                        :capability-history :event-id
                                        :id)
                                      entity-id)]
            (d/transact-kv conn [[:put dbi entity-id stored-payload]])
            (reset! health-atom {:status :healthy
                                 :checked-at (now)
                                 :details nil})
            {:ok? true
             :entity-type entity-type
             :entity-id entity-id
             :idempotent-replay? false})
          (catch Exception e
            (reset! status-atom :degraded)
            (reset! health-atom {:status :degraded
                                 :checked-at (now)
                                 :details (ex-message e)})
            {:ok? false
             :error :provider-write-failed
             :message (ex-message e)
             :entity-type entity-type}))
        {:ok? false
         :error :provider-not-open
         :entity-type entity-type})
      {:ok? false
       :error :unsupported-entity-type
       :entity-type entity-type}))

  (provider-query! [_ {:keys [tags capability-ids content-types since query-text limit]
                       :or   {limit 50}}]
    (if-let [conn (:conn @kv-atom)]
      (try
        (let [records (kv-range-values conn +dbi-records+)
              filtered (filter (partial filter-record? {:tags tags
                                                        :capability-ids capability-ids
                                                        :content-types content-types
                                                        :since since
                                                        :query-text query-text})
                               records)
              results (->> filtered
                           (take (max 0 limit))
                           vec)]
          (reset! health-atom {:status :healthy
                               :checked-at (now)
                               :details nil})
          {:ok? true
           :results results})
        (catch Exception e
          (reset! status-atom :degraded)
          (reset! health-atom {:status :degraded
                               :checked-at (now)
                               :details (ex-message e)})
          {:ok? false
           :error :provider-query-failed
           :message (ex-message e)
           :results []}))
      {:ok? false
       :error :provider-not-open
       :results []}))

  (provider-load-state [_]
    (if-let [conn (:conn @kv-atom)]
      (try
        (let [records            (kv-range-values conn +dbi-records+)
              graph-snapshots    (kv-range-values conn +dbi-graph-snapshots+)
              graph-deltas       (kv-range-values conn +dbi-graph-deltas+)
              recoveries         (kv-range-values conn +dbi-recoveries+)
              capability-history (kv-range-values conn +dbi-capability-history+)]
          (reset! health-atom {:status :healthy
                               :checked-at (now)
                               :details nil})
          {:ok? true
           :records records
           :graph-snapshots graph-snapshots
           :graph-deltas graph-deltas
           :recoveries recoveries
           :capability-history capability-history
           :index-stats (build-index-stats records)})
        (catch Exception e
          (reset! status-atom :degraded)
          (reset! health-atom {:status :degraded
                               :checked-at (now)
                               :details (ex-message e)})
          {:ok? false
           :error :provider-load-state-failed
           :message (ex-message e)}))
      {:ok? false
       :error :provider-not-open})))

(defn create-provider
  "Create Datalevin provider instance.

   Options:
   - :id               provider id (default \"datalevin\")
   - :cwd              cwd used to derive default db path
   - :store-root       base directory (default ~/.psi/agent/memory)
   - :db-dir           explicit Datalevin DB directory path (overrides cwd/store-root)
   - :schema-version   runtime target schema version (default 1)
   - :migration-hooks  map of from-version -> migration fn"
  ([]
   (create-provider {}))
  ([{:keys [id] :as opts}]
   (->DatalevinProvider (or id +provider-id+)
                        (atom :registering)
                        (atom {:status :degraded
                               :checked-at (now)
                               :details "not-open"})
                        (atom {})
                        opts)))

(defn register-in-memory-context!
  "Register Datalevin provider in a memory context and optionally select it.

   Options passed through to create-provider.
   Extra options:
   - :select?          select provider as active after registration (default true)
   - :open?            open provider when registering (default true)
   - :auto-fallback?   optional override passed to provider selection"
  [memory-ctx {:keys [select? open? auto-fallback?]
               :or   {select? true
                      open? true}
               :as   opts}]
  (let [provider    (create-provider opts)
        register!   (requiring-resolve 'psi.memory.core/register-store-provider-in!)
        select!     (requiring-resolve 'psi.memory.core/select-store-provider-in!)
        summarize!  (requiring-resolve 'psi.memory.core/store-summary-in)]
    (register! memory-ctx provider {:open? open?})
    (if select?
      (if (some? auto-fallback?)
        (select! memory-ctx (store/provider-id provider)
                 {:auto-fallback? auto-fallback?})
        (select! memory-ctx (store/provider-id provider)))
      (summarize! memory-ctx))))
