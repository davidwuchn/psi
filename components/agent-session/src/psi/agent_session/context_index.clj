(ns psi.agent-session.context-index
  "In-process context session index.

   Tracks sessions known to a running psi process context and the active
   default-routing session id. This is intentionally runtime-local and
   in-memory only.")

(defn- now [] (java.time.Instant/now))

(defn empty-index []
  {:active-session-id nil
   :sessions {}})

(defn create-index
  "Create a context session index seeded with optional initial session data map.
   `session-data` should include at least :session-id when present."
  ([] (empty-index))
  ([session-data]
   (if-let [sid (:session-id session-data)]
     (-> (empty-index)
         (assoc :active-session-id sid)
         (assoc-in [:sessions sid]
                   {:session-id sid
                    :session-file (:session-file session-data)
                    :session-name (:session-name session-data)
                    :worktree-path (:worktree-path session-data)
                    :parent-session-id (:parent-session-id session-data)
                    :parent-session-path (:parent-session-path session-data)
                    :created-at (or (:created-at session-data) (now))
                    :updated-at (now)}))
     (empty-index))))

(defn upsert-session
  "Insert/update context-session metadata for a session from session-data map."
  [index session-data]
  (if-let [sid (:session-id session-data)]
    (let [existing (get-in index [:sessions sid])
          created  (or (:created-at existing)
                       (:created-at session-data)
                       (now))]
      (assoc-in index [:sessions sid]
                {:session-id sid
                 :session-file (:session-file session-data)
                 :session-name (:session-name session-data)
                 :worktree-path (:worktree-path session-data)
                 :parent-session-id (:parent-session-id session-data)
                 :parent-session-path (:parent-session-path session-data)
                 :created-at created
                 :updated-at (now)}))
    index))

(defn set-active-session
  "Set active session id when it exists in the context index. No-op otherwise."
  [index session-id]
  (if (contains? (:sessions index) session-id)
    (assoc index :active-session-id session-id)
    index))

(defn session-ids [index]
  (-> index :sessions keys vec))

(defn session-count [index]
  (count (:sessions index)))

(defn sessions
  "Return context-session metadata entries."
  [index]
  (->> (:sessions index)
       vals
       (sort-by :updated-at)
       vec))
