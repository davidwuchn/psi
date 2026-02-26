(ns psi.agent-session.persistence
  "Session entry journal — append-only in-memory log with disk write.

   Format: NDEDN — one EDN map per line (newline-delimited EDN).

   In-memory journal
   ─────────────────
   The journal atom holds a vector of SessionEntry maps.  All callers
   append via `append-entry!`.

   Disk write (lazy flush)
   ───────────────────────
   Entries are held in memory until the first assistant message arrives.
   At that point the full journal (header + all entries) is written at
   once.  Subsequent entries are appended one line at a time.

   The flush state is tracked in a separate atom returned by
   `create-flush-state`.  Both atoms live in the session context.

   Session directory layout
   ────────────────────────
   ~/.psi/agent/sessions/--<encoded-cwd>--/<timestamp>_<uuid>.ndedn

   cwd encoding: strip leading slash, replace / and : with -.

   Migration
   ─────────
   v1 — no :version, no :id/:parent-id on entries
        → add linear :id/:parent-id chain
   v2 — :version 2, entries have :id/:parent-id
        → rename :hook-message role to :custom in message entries
   v3 — current

   Public API
   ──────────
   Journal (in-memory):
     create-journal, append-entry!, all-entries, entries-of-kind,
     entries-up-to, last-entry-of-kind, messages-from-entries,
     messages-up-to

   Flush state:
     create-flush-state

   Disk write:
     session-dir-for, new-session-file-path,
     write-header!, append-entry-to-disk!, flush-journal!

   Disk read:
     load-session-file, find-most-recent-session,
     list-sessions, list-all-sessions

   Entry constructors:
     message-entry, thinking-level-entry, model-entry, compaction-entry,
     branch-summary-entry, custom-message-entry, label-entry,
     session-info-entry"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.session :as session])
  (:import
   (java.io File)
   (java.nio.file Files OpenOption Path StandardOpenOption)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)))

;;; ============================================================
;;; Constants
;;; ============================================================

(def ^:private current-version 3)

(def ^:private sessions-root
  (delay (io/file (System/getProperty "user.home") ".psi" "agent" "sessions")))

;;; ============================================================
;;; Journal operations (in-memory atom)
;;; ============================================================

(defn create-journal
  "Create a fresh journal atom (vector of SessionEntry maps)."
  []
  (atom []))

(defn append-entry!
  "Append `entry` to `journal-atom` atomically. Returns `entry`."
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
  "Extract agent message maps from all :message entries in the journal."
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

;;; ============================================================
;;; Flush state
;;; ============================================================

(defn create-flush-state
  "Create a flush-state atom.
  {:flushed? false :session-file nil}
  :flushed? — true once the initial bulk write has happened
  :session-file — java.io.File for the session file, or nil"
  []
  (atom {:flushed? false :session-file nil}))

;;; ============================================================
;;; NDEDN serialisation
;;; ============================================================

(defn- instant->date
  "Recursively convert any java.time.Instant values in `x` to java.util.Date
  so that pr-str produces #inst literals readable by clojure.edn/read-string."
  [x]
  (cond
    (instance? Instant x)    (java.util.Date/from x)
    (map? x)                 (reduce-kv (fn [m k v] (assoc m k (instant->date v))) {} x)
    (sequential? x)          (mapv instant->date x)
    :else                    x))

(defn- entry->line
  "Serialise a single map to a single EDN line (no newlines inside).
  Converts java.time.Instant to #inst so edn/read-string can round-trip."
  [m]
  (pr-str (instant->date m)))

(defn- parse-line
  "Parse a single NDEDN line. Returns nil on blank, parse error, or non-map."
  [line]
  (let [trimmed (str/trim line)]
    (when-not (str/blank? trimmed)
      (try
        (let [v (edn/read-string
                 {:readers {'inst #(java.util.Date/from (Instant/parse %))}}
                 trimmed)]
          (when (map? v) v))
        (catch Exception _
          nil)))))

;;; ============================================================
;;; Session directory and file naming
;;; ============================================================

(defn session-dir-for
  "Return the session directory (java.io.File) for `cwd`.
  Creates the directory if it does not exist."
  [cwd]
  (let [encoded (-> (str cwd)
                    (str/replace #"^[/\\]" "")
                    (str/replace #"[/\\:]" "-"))
        dir     (io/file @sessions-root (str "--" encoded "--"))]
    (.mkdirs dir)
    dir))

(defn- timestamp-prefix
  "Return a filesystem-safe timestamp string for now."
  []
  (let [fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss-SSS")]
    (.format fmt (java.time.LocalDateTime/now ZoneOffset/UTC))))

(defn new-session-file-path
  "Return a new session file (java.io.File) under `session-dir`."
  [session-dir session-id]
  (io/file session-dir (str (timestamp-prefix) "_" session-id ".ndedn")))

;;; ============================================================
;;; Session file header
;;; ============================================================

(defn- make-header
  [session-id cwd parent-session]
  {:type           :session
   :version        current-version
   :id             session-id
   :timestamp      (Instant/now)
   :cwd            (str cwd)
   :parent-session (when parent-session (str parent-session))})

;;; ============================================================
;;; Disk write
;;; ============================================================

(defn- append-line!
  "Append `line` + newline to `file` atomically via NIO."
  [^File file line]
  (let [^Path path (.toPath file)
        ^bytes bytes (.getBytes (str line "\n") "UTF-8")
        ^"[Ljava.nio.file.OpenOption;" opts
        (into-array OpenOption [StandardOpenOption/CREATE
                                StandardOpenOption/APPEND])]
    (Files/write path bytes opts)))

(defn write-header!
  "Write the session header as the first line of `file`.
  Overwrites any existing content."
  [^File file session-id cwd parent-session]
  (let [header (make-header session-id cwd parent-session)
        ^Path path (.toPath file)
        ^bytes bytes (.getBytes (str (entry->line header) "\n") "UTF-8")
        ^"[Ljava.nio.file.OpenOption;" opts
        (into-array OpenOption [StandardOpenOption/CREATE
                                StandardOpenOption/TRUNCATE_EXISTING
                                StandardOpenOption/WRITE])]
    (Files/write path bytes opts)))

(defn append-entry-to-disk!
  "Append a single `entry` line to `file`."
  [^File file entry]
  (append-line! file (entry->line entry)))

(defn flush-journal!
  "Write the header + all `entries` to `file` in one operation.
  Overwrites any existing content."
  [^File file session-id cwd parent-session entries]
  (let [header (make-header session-id cwd parent-session)
        lines  (str/join "\n"
                         (cons (entry->line header)
                               (map entry->line entries)))
        ^Path path (.toPath file)
        ^bytes bytes (.getBytes (str lines "\n") "UTF-8")
        ^"[Ljava.nio.file.OpenOption;" opts
        (into-array OpenOption [StandardOpenOption/CREATE
                                StandardOpenOption/TRUNCATE_EXISTING
                                StandardOpenOption/WRITE])]
    (Files/write path bytes opts)))

;;; ============================================================
;;; Persist-on-append (called by core.clj after append-entry!)
;;; ============================================================

(defn persist-entry!
  "Conditionally write `entry` to disk.

  Rules:
    - If :session-file is nil → no-op (in-memory only mode).
    - If the journal has no assistant message yet → no-op (lazy flush).
    - If not yet :flushed? → bulk-write header + all entries, set :flushed? true.
    - Otherwise → append single entry line.

  `journal-atom`   — the session journal atom
  `flush-state-atom` — atom from create-flush-state
  `session-id`     — string
  `cwd`            — string
  `parent-session` — string or nil"
  [journal-atom flush-state-atom session-id cwd parent-session]
  (let [{:keys [flushed? session-file]} @flush-state-atom]
    (when session-file
      (let [entries       @journal-atom
            has-assistant (some (fn [e]
                                  (and (= :message (:kind e))
                                       (= "assistant" (get-in e [:data :message :role]))))
                                entries)]
        (when has-assistant
          (if flushed?
            (append-entry-to-disk! session-file (last entries))
            (do
              (flush-journal! session-file session-id cwd parent-session entries)
              (swap! flush-state-atom assoc :flushed? true))))))))

;;; ============================================================
;;; Migration
;;; ============================================================

(defn- short-id
  "Generate a short random hex ID."
  []
  (str (java.util.UUID/randomUUID)))

(defn- migrate-v1->v2
  "Add :id and :parent-id to entries that lack them (v1 format).
  Returns updated entries vector."
  [entries]
  (let [indexed (map-indexed vector entries)]
    (reduce
     (fn [{:keys [result prev-id]} [_i entry]]
       (if (:id entry)
         ;; already has id — leave alone
         {:result (conj result entry) :prev-id (:id entry)}
         (let [new-id (short-id)
               entry' (assoc entry :id new-id :parent-id prev-id)]
           {:result (conj result entry') :prev-id new-id})))
     {:result [] :prev-id nil}
     indexed)
    (:result
     (reduce
      (fn [{:keys [result prev-id]} [_i entry]]
        (if (:id entry)
          {:result (conj result entry) :prev-id (:id entry)}
          (let [new-id (short-id)
                entry' (assoc entry :id new-id :parent-id prev-id)]
            {:result (conj result entry') :prev-id new-id})))
      {:result [] :prev-id nil}
      indexed))))

(defn- migrate-v2->v3
  "Rename :hook-message role to :custom in message entries."
  [entries]
  (mapv (fn [entry]
          (if (and (= :message (:kind entry))
                   (= "hook-message" (get-in entry [:data :message :role])))
            (assoc-in entry [:data :message :role] "custom")
            entry))
        entries))

(defn- migrate-entries
  "Apply all necessary migrations to bring `entries` to current-version.
  `header` is the parsed header map (mutated version field is ignored here;
  we derive version from the header passed in).
  Returns {:header header' :entries entries'}."
  [header entries]
  (let [version (or (:version header) 1)]
    (cond-> {:header (assoc header :version current-version)
             :entries entries}
      (< version 2) (update :entries migrate-v1->v2)
      (< version 3) (update :entries migrate-v2->v3))))

;;; ============================================================
;;; Disk read
;;; ============================================================

(defn- valid-header?
  "True if `m` looks like a session header."
  [m]
  (and (map? m)
       (= :session (:type m))
       (string? (:id m))
       (not (str/blank? (:id m)))))

(defn load-session-file
  "Parse a session file from `file` (java.io.File or path string).
  Returns {:header header-map :entries [entry-map ...]}
  or nil if the file is missing, empty, or has no valid header.
  Malformed lines are silently skipped."
  [file]
  (let [f (io/file file)]
    (when (.exists f)
      (let [lines   (str/split-lines (slurp f))
            parsed  (keep parse-line lines)]
        (when (seq parsed)
          (let [header (first parsed)]
            (when (valid-header? header)
              (let [entries (vec (rest parsed))
                    {:keys [header entries]} (migrate-entries header entries)]
                {:header  header
                 :entries entries}))))))))

(defn- peek-header
  "Read only the first line of `file` and parse it.
  Returns the header map or nil. Cheap validity check."
  [^File file]
  (try
    (with-open [^java.io.BufferedReader rdr (io/reader file)]
      (let [first-line (.readLine rdr)]
        (when first-line
          (let [m (parse-line first-line)]
            (when (valid-header? m) m)))))
    (catch Exception _ nil)))

(defn find-most-recent-session
  "Return the path (string) of the most recently modified valid session
  file in `session-dir`, or nil if none found."
  [session-dir]
  (let [dir (io/file session-dir)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName ^File %) ".ndedn"))
           (filter #(peek-header %))
           (sort-by #(.lastModified ^File %) >)
           first
           (#(when % (.getAbsolutePath ^File %)))))))

;;; ============================================================
;;; Session info (lightweight metadata for listing)
;;; ============================================================

(defn- extract-session-info
  "Build a SessionInfo map from a loaded session {:header :entries}."
  [file-path {:keys [header entries]}]
  (let [name          (some (fn [e]
                              (when (= :session-info (:kind e))
                                (get-in e [:data :name])))
                            (rseq (vec entries)))
        msg-entries   (filter #(= :message (:kind %)) entries)
        messages      (keep #(get-in % [:data :message]) msg-entries)
        user-messages (filter #(= "user" (:role %)) messages)
        first-text    (some (fn [m]
                              (let [c (:content m)]
                                (cond
                                  (string? c) (when-not (str/blank? c) c)
                                  (sequential? c)
                                  (some #(when (= :text (:type %)) (:text %)) c))))
                            user-messages)
        all-text      (str/join " "
                                (keep (fn [m]
                                        (let [c (:content m)]
                                          (cond
                                            (string? c) c
                                            (sequential? c)
                                            (str/join " "
                                                      (keep #(when (= :text (:type %)) (:text %)) c)))))
                                      messages))
        ;; modified = latest user/assistant message timestamp, else header
        last-activity (reduce (fn [acc m]
                                (let [ts (:timestamp m)]
                                  (cond
                                    (nil? ts)   acc
                                    (nil? acc)  ts
                                    (instance? Instant ts)
                                    (if (instance? Instant acc)
                                      (if (.isAfter ^Instant ts ^Instant acc) ts acc)
                                      ts)
                                    :else acc)))
                              nil
                              messages)
        modified      (or last-activity (:timestamp header))
        f             (io/file file-path)]
    {:path                file-path
     :id                  (:id header)
     :cwd                 (:cwd header)
     :name                name
     :parent-session-path (:parent-session header)
     :created             (:timestamp header)
     :modified            modified
     :message-count       (count msg-entries)
     :first-message       (or first-text "(no messages)")
     :all-messages-text   all-text}))

(defn list-sessions
  "Return a vector of SessionInfo maps for all valid session files in
  `session-dir`, sorted by :modified descending."
  [session-dir]
  (let [^File dir (io/file session-dir)]
    (if-not (.isDirectory dir)
      []
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName ^File %) ".ndedn"))
           (keep (fn [^File f]
                   (when-let [loaded (load-session-file f)]
                     (extract-session-info (.getAbsolutePath f) loaded))))
           (sort-by (fn [info]
                      (let [m (:modified info)]
                        (cond
                          (instance? Instant m)       (- (.toEpochMilli ^Instant m))
                          (instance? java.util.Date m) (- (.getTime ^java.util.Date m))
                          :else                        0)))
                    <)
           vec))))

(defn list-all-sessions
  "Return a vector of SessionInfo maps across all project directories
  under the sessions root, sorted by :modified descending."
  []
  (let [^File root @sessions-root]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (mapcat #(list-sessions (.getAbsolutePath ^File %)))
           (sort-by (fn [info]
                      (let [m (:modified info)]
                        (cond
                          (instance? Instant m)       (- (.toEpochMilli ^Instant m))
                          (instance? java.util.Date m) (- (.getTime ^java.util.Date m))
                          :else                        0)))
                    <)
           vec))))

;;; ============================================================
;;; Convenience entry constructors
;;; ============================================================

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
