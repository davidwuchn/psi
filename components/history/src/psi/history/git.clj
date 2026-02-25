(ns psi.history.git
  "Thin wrapper over the git CLI.  Nullable pattern: all fns take a
   GitContext record (created via `create-context` or `create-null-context`).
   Tests use `create-null-context` which builds an isolated temp git repo —
   no mocking, no shared state, no dependency on the real project repo."
  (:require [clojure.string :as str])
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

(def ^:private sep    "\u001F")
(def ^:private sep-re (re-pattern sep))

;;; Symbol extraction

(defn- extract-symbols
  "Return set of psi vocabulary symbols found in `text`."
  [text]
  (let [vocab #{"⚒" "◇" "⊘" "◈" "∿" "·" "λ" "Δ" "✓" "✗" "‖" "↺" "…" "刀" "ψ"}]
    (into #{} (filter #(str/includes? text %) vocab))))

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
