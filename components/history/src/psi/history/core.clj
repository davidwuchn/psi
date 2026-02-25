(ns psi.history.core
  "History component — git-backed history queryable via EQL.

   Public API:
     register-resolvers! — register all git resolvers into the global query graph
     query-commits       — recent commits (with optional grep/path)
     query-commit        — single commit detail by sha
     query-grep          — grep across HEAD file contents
     query-learning      — commits containing the λ symbol
     query-repo          — repository status summary

   Pass an explicit `git-ctx` (GitContext from psi.history.git) to target
   any repository.  Defaults to cwd.  Tests supply a null context."
  (:require
   [psi.history.resolvers :as resolvers]
   [psi.history.git :as git]
   [psi.query.core :as query]))

;;; Registration

(defn register-resolvers!
  "Register all history/git resolvers into the global query graph.
   Call once at system startup before querying."
  []
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!))

;;; Convenience query API

(defn query-commits
  "Return recent commits via EQL.
   `git-ctx` defaults to cwd context.  `opts` forwarded as EQL params."
  ([] (query-commits (git/create-context) {}))
  ([git-ctx] (query-commits git-ctx {}))
  ([git-ctx _opts]
   (query/query-one {:git/context git-ctx} :git.repo/commits)))

(defn query-commit
  "Return full detail for commit `sha`.
   `git-ctx` defaults to cwd context."
  ([sha] (query-commit sha (git/create-context)))
  ([sha git-ctx]
   (query/query {:git/context    git-ctx
                 :git.commit/sha sha}
                [:git.commit/date
                 :git.commit/author
                 :git.commit/email
                 :git.commit/subject
                 :git.commit/body
                 :git.commit/stat
                 :git.commit/diff
                 :git.commit/symbols
                 :git.commit/is-learning
                 :git.commit/is-delta])))

(defn query-grep
  "Search `pattern` in HEAD file contents.
   `git-ctx` defaults to cwd context."
  ([pattern] (query-grep pattern (git/create-context)))
  ([pattern git-ctx]
   (query/query-one {:git/context           git-ctx
                     :git.repo/grep-pattern  pattern}
                    :git.repo/grep-results)))

(defn query-learning
  "Return commits containing the λ learning symbol.
   `git-ctx` defaults to cwd context."
  ([] (query-learning (git/create-context)))
  ([git-ctx]
   (query/query-one {:git/context git-ctx}
                    :git.repo/learning-commits)))

(defn query-repo
  "Return repository status: status, current-commit, has-changes, has-history.
   `git-ctx` defaults to cwd context."
  ([] (query-repo (git/create-context)))
  ([git-ctx]
   (query/query {:git/context git-ctx}
                [:git.repo/status
                 :git.repo/current-commit
                 :git.repo/has-changes
                 :git.repo/has-history])))
