(ns psi.history.git
  "Thin wrapper over the git CLI.  Nullable pattern: all fns take a
   GitContext record (created via `create-context` or `create-null-context`).
   Tests use `create-null-context` which builds an isolated temp git repo —
   no mocking, no shared state, no dependency on the real project repo."
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;;; GitContext record

(defrecord GitContext [repo-dir])

(defn create-context
  "Create a GitContext pointing at `repo-dir` (defaults to cwd)."
  ([]
   (create-context (System/getProperty "user.dir")))
  ([repo-dir]
   (->GitContext repo-dir)))

(defn create-null-context
  "Create an isolated GitContext backed by a fresh temp git repo.
   Seeds it with the provided `commits` seq of {:message :files} maps,
   where :files is a map of {filename content}.

   Returns a GitContext.  Caller is responsible for cleanup (temp dirs
   are in the JVM temp dir and will be cleaned up on JVM exit)."
  ([]
   (create-null-context [{:message "⚒ Initial commit"
                          :files   {"README.md" "# psi\n"}}
                         {:message "λ First learning"
                          :files   {"LEARNING.md" "## learned something\n"}}
                         {:message "Δ Show a delta"
                          :files   {"CHANGELOG.md" "## v0.1\n"}}]))
  ([commits]
   (let [tmp  (str (Files/createTempDirectory "psi-git-null-"
                                              (make-array FileAttribute 0)))
         run! (fn [args]
                (let [pb   (ProcessBuilder. ^java.util.List args)
                      _    (.directory pb (File. ^String tmp))
                      _    (doto (.environment pb)
                             (.put "GIT_AUTHOR_NAME"     "Test Author")
                             (.put "GIT_AUTHOR_EMAIL"    "test@example.com")
                             (.put "GIT_COMMITTER_NAME"  "Test Author")
                             (.put "GIT_COMMITTER_EMAIL" "test@example.com"))
                      proc (.start pb)
                      _    (slurp (.getInputStream proc))
                      _    (slurp (.getErrorStream proc))]
                  (.waitFor proc)))]
     (run! ["git" "init" "-b" "main"])
     (run! ["git" "config" "user.email" "test@example.com"])
     (run! ["git" "config" "user.name"  "Test Author"])
     (doseq [{:keys [message files]} commits]
       (doseq [[fname content] files]
         (let [f (File. (str tmp "/" fname))]
           (.mkdirs (.getParentFile f))
           (spit f content)))
       (run! ["git" "add" "."])
       (run! ["git" "commit" "-m" message]))
     (->GitContext tmp))))

;;; Internal helpers

(defn- run-git
  "Run git `args` in ctx's repo-dir; return trimmed stdout or throw."
  [^GitContext ctx args]
  (let [pb   (ProcessBuilder. ^java.util.List (into ["git"] args))
        _    (.directory pb (File. ^String (:repo-dir ctx)))
        proc (.start pb)
        out  (slurp (.getInputStream proc))
        _err (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    (when (pos? exit)
      (throw (ex-info "git command failed"
                      {:dir  (:repo-dir ctx)
                       :args args
                       :exit exit})))
    (str/trim out)))

(defn- canonical-path
  ^String [p]
  (when (seq (str p))
    (try
      (.getCanonicalPath (File. (str p)))
      (catch Exception _
        (.getAbsolutePath (File. (str p)))))))

(defn- inside-path?
  [^String child ^String parent]
  (and (seq child)
       (seq parent)
       (or (= child parent)
           (str/starts-with? child (str parent File/separator)))))

(defn- select-containing-worktree
  [cwd worktrees]
  (let [cwd* (canonical-path cwd)]
    (->> worktrees
         (filter (fn [wt]
                   (inside-path? cwd* (canonical-path (:git.worktree/path wt)))))
         (sort-by (fn [wt] (count (canonical-path (:git.worktree/path wt)))) >)
         first)))

(def ^:private sep    "\u001F")
(def ^:private sep-re (re-pattern sep))

;;; Symbol extraction

(defn- extract-symbols
  "Return set of psi vocabulary symbols found in `text`."
  [text]
  (let [vocab #{"⚒" "◇" "⊘" "◈" "∿" "·" "λ" "Δ" "✓" "✗" "‖" "↺" "…" "刀" "ψ"}]
    (into #{} (filter #(str/includes? text %) vocab))))

;;; Worktree parsing

(defn- parse-worktree-block
  [lines]
  (reduce (fn [acc line]
            (cond
              (str/starts-with? line "worktree ")
              (assoc acc :git.worktree/path (subs line 9))

              (str/starts-with? line "HEAD ")
              (assoc acc :git.worktree/head (subs line 5))

              (str/starts-with? line "branch ")
              (let [ref (subs line 7)]
                (assoc acc
                       :git.worktree/branch-ref ref
                       :git.worktree/branch-name (when (str/starts-with? ref "refs/heads/")
                                                   (subs ref 11))
                       :git.worktree/detached? false))

              (= line "detached")
              (assoc acc :git.worktree/detached? true)

              (= line "bare")
              (assoc acc :git.worktree/bare? true)

              (str/starts-with? line "locked")
              (assoc acc
                     :git.worktree/locked? true
                     :git.worktree/lock-reason (some-> (subs line (min (count line) 7))
                                                       str/trim
                                                       not-empty))

              (str/starts-with? line "prunable")
              (assoc acc
                     :git.worktree/prunable? true
                     :git.worktree/prunable-reason (some-> (subs line (min (count line) 9))
                                                          str/trim
                                                          not-empty))

              :else acc))
          {:git.worktree/branch-ref nil
           :git.worktree/branch-name nil
           :git.worktree/detached? false
           :git.worktree/bare? false
           :git.worktree/locked? false
           :git.worktree/prunable? false
           :git.worktree/prunable-reason nil
           :git.worktree/lock-reason nil}
          lines))

(defn parse-worktree-porcelain
  "Parse `git worktree list --porcelain` output into worktree maps."
  [s]
  (if (str/blank? s)
    []
    (->> (str/split s #"\n\s*\n")
         (map str/split-lines)
         (map parse-worktree-block)
         (filter :git.worktree/path)
         vec)))

(defn inside-repo?
  "Return true when `ctx` points inside a git worktree/repository."
  [ctx]
  (try
    (= "true" (run-git ctx ["rev-parse" "--is-inside-work-tree"]))
    (catch Exception _ false)))

(defn- emit-worktree-parse-failed!
  [ctx e]
  (timbre/warn "git.worktree.parse_failed"
               {:repo-dir (:repo-dir ctx)
                :error (ex-message e)}))

(defn worktree-list
  "Return vector of worktree maps for `ctx`.

   Each map includes:
     :git.worktree/path
     :git.worktree/head
     :git.worktree/branch-ref
     :git.worktree/branch-name
     :git.worktree/detached?
     :git.worktree/bare?
     :git.worktree/locked?
     :git.worktree/prunable?
     :git.worktree/prunable-reason
     :git.worktree/lock-reason
     :git.worktree/current?

   On parse/command errors, returns [] and logs a telemetry marker:
     event: git.worktree.parse_failed."
  [ctx]
  (if-not (inside-repo? ctx)
    []
    (try
      (let [out       (run-git ctx ["worktree" "list" "--porcelain"])
            parsed    (parse-worktree-porcelain out)
            current   (select-containing-worktree (:repo-dir ctx) parsed)
            current-p (some-> current :git.worktree/path canonical-path)]
        (mapv (fn [wt]
                (assoc wt :git.worktree/current?
                       (= (canonical-path (:git.worktree/path wt)) current-p)))
              parsed))
      (catch Exception e
        (emit-worktree-parse-failed! ctx e)
        []))))

(defn current-worktree
  "Return the current worktree map for `ctx`, or nil when unavailable."
  [ctx]
  (first (filter :git.worktree/current? (worktree-list ctx))))

;;; Public API — all take a GitContext as first arg

(defn log
  "Return a seq of commit maps from `ctx`.

   Options:
     :n    — max commits (default 50)
     :grep — filter by message pattern
     :path — restrict to file/directory

   Each map: :git.commit/sha :git.commit/date :git.commit/author
              :git.commit/email :git.commit/subject :git.commit/symbols"
  ([ctx] (log ctx {}))
  ([ctx {:keys [n grep path] :or {n 50}}]
   (let [fmt  (str "--format=%H" sep "%aI" sep "%an" sep "%ae" sep "%s")
         args (cond-> ["log" fmt (str "-" n)]
                grep (conj (str "--grep=" grep))
                path (conj "--" path))
         out  (run-git ctx args)]
     (when (seq out)
       (for [line (str/split-lines out)
             :when (seq line)
             :let [parts                          (str/split line sep-re)
                   [sha date author email subject] parts]]
         {:git.commit/sha     sha
          :git.commit/date    date
          :git.commit/author  author
          :git.commit/email   email
          :git.commit/subject subject
          :git.commit/symbols (extract-symbols (or subject ""))})))))

(defn show
  "Return full detail map for commit `sha` in `ctx`.
   Adds :git.commit/body :git.commit/stat :git.commit/diff."
  [ctx sha]
  (let [fmt    (str "--format=%H" sep "%aI" sep "%an" sep "%ae" sep "%s" sep "%b")
        header (run-git ctx ["show" fmt "--stat" sha])
        diff   (run-git ctx ["show" "--format=" "--unified=3" sha])
        lines  (str/split-lines header)
        parts  (str/split (first lines) sep-re)
        [sha2 date author email subject body] parts]
    {:git.commit/sha     sha2
     :git.commit/date    date
     :git.commit/author  author
     :git.commit/email   email
     :git.commit/subject subject
     :git.commit/body    (str/trim (or body ""))
     :git.commit/stat    (str/join "\n" (rest lines))
     :git.commit/diff    diff
     :git.commit/symbols (extract-symbols (str subject " " body))}))

(defn status
  "Return :clean | :modified | :staged | :error for `ctx`."
  [ctx]
  (try
    (let [out (run-git ctx ["status" "--porcelain"])]
      (cond
        (str/includes? out "M ") :staged
        (seq out)                :modified
        :else                    :clean))
    (catch Exception _ :error)))

(defn current-commit
  "Return the SHA of HEAD in `ctx`."
  [ctx]
  (run-git ctx ["rev-parse" "HEAD"]))

(defn ls-files
  "Return a seq of tracked file paths in `ctx`.
   Optional `:path` restricts to a subdirectory."
  ([ctx] (ls-files ctx {}))
  ([ctx {:keys [path]}]
   (let [args (cond-> ["ls-files"] path (conj path))
         out  (run-git ctx args)]
     (when (seq out) (str/split-lines out)))))

(defn grep
  "Search `pattern` in file contents at HEAD of `ctx`.

   Options:
     :path — restrict to file/directory
     :n    — max results (default 200)

   Returns seq of {:git.grep/file :git.grep/line :git.grep/content}."
  ([ctx pattern] (grep ctx pattern {}))
  ([ctx pattern {:keys [path n] :or {n 200}}]
   (let [args (cond-> ["grep" "-n" "--no-color" "-e" pattern "HEAD"]
                path (conj "--" path))
         out  (try (run-git ctx args)
                   (catch Exception _ ""))]
     (when (seq out)
       (->> (str/split-lines out)
            (take n)
            (keep (fn [line]
                    (let [[_ file lineno content]
                          (re-matches #"HEAD:([^:]+):(\d+):(.*)" line)]
                      (when file
                        {:git.grep/file    file
                         :git.grep/line    (Long/parseLong lineno)
                         :git.grep/content content})))))))))