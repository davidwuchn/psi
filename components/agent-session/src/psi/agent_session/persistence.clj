(ns psi.agent-session.persistence
  "Session entry journal — append-only in-memory log with optional disk write.

   In-scope now:
     - Append SessionEntry maps to an in-memory atom log
     - Make the log queryable (all entries, entries by kind, entries up to id)

   Disk I/O deferred:
     - Writing the journal atom to a file (planned: EDN or NDJSON)
     - Reading a saved session file back to an agent message list

   The journal atom lives in the session context (not a global).  There is
   no singleton here — every session context owns its own journal."
  (:require
   [psi.agent-session.session :as session]))

;; ============================================================
;; Journal operations (operate on a plain atom)
;; ============================================================

(defn create-journal
  "Create a fresh journal atom (vector of SessionEntry maps)."
  []
  (atom []))

(defn append-entry!
  "Append `entry` to `journal-atom` atomically."
  [journal-atom entry]
  (swap! journal-atom conj entry)
  entry)

(defn all-entries
  "Return all entries from `journal-atom` as a vector."
  [journal-atom]
  @journal-atom)

(defn entries-of-kind
  "Return all entries of `kind` keyword from `journal-atom`."
  [journal-atom kind]
  (filterv #(= (:kind %) kind) @journal-atom))

(defn entries-up-to
  "Return all entries up to and including the entry with `entry-id`.
  Returns the full journal if `entry-id` is nil or not found."
  [journal-atom entry-id]
  (if (nil? entry-id)
    @journal-atom
    (let [entries @journal-atom
          idx     (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))]
      (if idx
        (subvec entries 0 (inc idx))
        entries))))

(defn last-entry-of-kind
  "Return the most recent entry of `kind` from `journal-atom`, or nil."
  [journal-atom kind]
  (last (entries-of-kind journal-atom kind)))

(defn messages-from-entries
  "Extract agent message maps from all :message-kind entries in the journal."
  [journal-atom]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        @journal-atom))

(defn messages-up-to
  "Extract agent messages from journal entries up to `entry-id`."
  [journal-atom entry-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (entries-up-to journal-atom entry-id)))

;; ── Convenience entry constructors ─────────────────────

(defn message-entry [message]
  (session/make-entry :message {:message message}))

(defn thinking-level-entry [level]
  (session/make-entry :thinking-level {:thinking-level level}))

(defn model-entry [provider model-id]
  (session/make-entry :model {:provider provider :model-id model-id}))

(defn compaction-entry [result from-hook?]
  (session/make-entry :compaction
                      {:summary             (:summary result)
                       :first-kept-entry-id (:first-kept-entry-id result)
                       :tokens-before       (:tokens-before result)
                       :details             (:details result)
                       :from-hook           (boolean from-hook?)}))

(defn branch-summary-entry [from-id summary details label from-hook?]
  (session/make-entry :branch-summary
                      {:from-id   from-id
                       :summary   summary
                       :details   details
                       :label     label
                       :from-hook (boolean from-hook?)}))

(defn custom-message-entry [custom-type content details display?]
  (session/make-entry :custom-message
                      {:custom-type custom-type
                       :content     content
                       :details     details
                       :display     (boolean display?)}))

(defn label-entry [target-id label]
  (session/make-entry :label {:target-id target-id :label label}))

(defn session-info-entry [name]
  (session/make-entry :session-info {:name name}))
