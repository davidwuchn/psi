(ns psi.agent-session.session-host
  "In-process session-host registry.

   Tracks sessions known to a running psi process context and the active
   default-routing session id. This is intentionally runtime-local and
   in-memory only.")

(defn- now [] (java.time.Instant/now))

(defn empty-host []
  {:active-session-id nil
   :sessions {}})

(defn create-host
  "Create a host seeded with optional initial session data map.
   `session-data` should include at least :session-id when present."
  ([] (empty-host))
  ([session-data]
   (if-let [sid (:session-id session-data)]
     (-> (empty-host)
         (assoc :active-session-id sid)
         (assoc-in [:sessions sid]
                   {:session-id sid
                    :session-file (:session-file session-data)
                    :session-name (:session-name session-data)
                    :parent-session-id (:parent-session-id session-data)
                    :parent-session-path (:parent-session-path session-data)
                    :created-at (now)
                    :updated-at (now)}))
     (empty-host))))

(defn upsert-session
  "Insert/update host metadata for a session from session-data map." 
  [host session-data]
  (if-let [sid (:session-id session-data)]
    (let [existing (get-in host [:sessions sid])
          created  (or (:created-at existing) (now))]
      (assoc-in host [:sessions sid]
                {:session-id sid
                 :session-file (:session-file session-data)
                 :session-name (:session-name session-data)
                 :parent-session-id (:parent-session-id session-data)
                 :parent-session-path (:parent-session-path session-data)
                 :created-at created
                 :updated-at (now)}))
    host))

(defn set-active-session
  "Set active session id when it exists in registry. No-op otherwise." 
  [host session-id]
  (if (contains? (:sessions host) session-id)
    (assoc host :active-session-id session-id)
    host))

(defn session-ids [host]
  (-> host :sessions keys vec))

(defn session-count [host]
  (count (:sessions host)))

(defn sessions
  "Return host session metadata entries." 
  [host]
  (->> (:sessions host)
       vals
       (sort-by :updated-at)
       vec))
