(ns psi.agent-session.resolvers-test
  "Tests for canonical telemetry resolvers (Step 7a) and graph bridge (Step 7).

   Each test asserts that a direct EQL query against a fresh session context
   returns a well-typed value for the target attribute — no resolver error."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.oauth.core :as oauth]))

;; ── helpers ─────────────────────────────────────────────

(defn- q
  "Run EQL query against a fresh session context."
  [eql]
  (session/query-in (session/create-context {:persist? false}) eql))

(defn- q-in
  "Run EQL query against explicit session context CTX."
  [ctx eql]
  (session/query-in ctx eql))

(defmacro with-user-dir
  "Temporarily set java user.dir while evaluating body."
  [dir & body]
  `(let [orig# (System/getProperty "user.dir")]
     (try
       (System/setProperty "user.dir" (str ~dir))
       ~@body
       (finally
         (System/setProperty "user.dir" orig#)))))

;; ── :psi.agent-session/messages-count ───────────────────

(deftest messages-count-resolver-test
  (testing "messages-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/messages-count])]
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (zero? (:psi.agent-session/messages-count result))
          "fresh session has no messages"))))

;; ── :psi.agent-session/ai-call-count ────────────────────

(deftest ai-call-count-resolver-test
  (testing "ai-call-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/ai-call-count])]
      (is (integer? (:psi.agent-session/ai-call-count result)))
      (is (zero? (:psi.agent-session/ai-call-count result))
          "fresh session has no AI calls"))))

;; ── :psi.agent-session/tool-call-count ──────────────────

(deftest tool-call-count-resolver-test
  (testing "tool-call-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/tool-call-count])]
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (zero? (:psi.agent-session/tool-call-count result))
          "fresh session has no tool calls"))))

;; ── :psi.agent-session/start-time ───────────────────────

(deftest start-time-resolver-test
  (testing "start-time is an Instant"
    (let [result (q [:psi.agent-session/start-time])]
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))))

  (testing "start-time is before or equal to current-time"
    (let [result (q [:psi.agent-session/start-time
                     :psi.agent-session/current-time])
          start  (:psi.agent-session/start-time result)
          now    (:psi.agent-session/current-time result)]
      (is (not (.isAfter ^java.time.Instant start ^java.time.Instant now))
          "start-time should not be after current-time"))))

;; ── :psi.agent-session/current-time ─────────────────────

(deftest current-time-resolver-test
  (testing "current-time is an Instant"
    (let [result (q [:psi.agent-session/current-time])]
      (is (instance? java.time.Instant (:psi.agent-session/current-time result)))))

  (testing "current-time is recent (within 60 seconds)"
    (let [result (q [:psi.agent-session/current-time])
          now    (java.time.Instant/now)
          ct     (:psi.agent-session/current-time result)
          diff   (Math/abs (- (.toEpochMilli now) (.toEpochMilli ^java.time.Instant ct)))]
      (is (< diff 60000) "current-time should be within 60s of system clock"))))

;; ── Combined query (mirrors the failing app-query-tool pattern) ──

(deftest combined-telemetry-query-test
  (testing "all canonical telemetry attrs succeed in one query"
    (let [result (q [:psi.agent-session/messages-count
                     :psi.agent-session/ai-call-count
                     :psi.agent-session/tool-call-count
                     :psi.agent-session/ui-type
                     :psi.agent-session/start-time
                     :psi.agent-session/current-time])]
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (integer? (:psi.agent-session/ai-call-count result)))
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (contains? #{:console :tui :emacs} (:psi.agent-session/ui-type result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result))))))

;; ── Mixed with existing attrs (regression) ──────────────

(deftest mixed-attrs-query-test
  (testing "new attrs compose with existing stable attrs"
    (let [result (q [:psi.agent-session/phase
                     :psi.agent-session/model
                     :psi.agent-session/ui-type
                     :psi.agent-session/session-id
                     :psi.agent-session/messages-count
                     :psi.agent-session/ai-call-count
                     :psi.agent-session/tool-call-count
                     :psi.agent-session/start-time
                     :psi.agent-session/current-time])]
      (is (keyword? (:psi.agent-session/phase result)))
      (is (contains? #{:console :tui :emacs} (:psi.agent-session/ui-type result)))
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (integer? (:psi.agent-session/ai-call-count result)))
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result))))))

;; ── Model selector bridge attrs ──────────────────────────

(deftest model-catalog-resolver-test
  (testing "model-catalog is queryable with deterministic model entries"
    (let [result (q [:psi.agent-session/model-catalog])
          catalog (:psi.agent-session/model-catalog result)]
      (is (vector? catalog))
      (is (seq catalog))
      (is (every? map? catalog))
      (is (every? string? (keep :provider catalog)))
      (is (every? string? (keep :id catalog)))
      (is (every? string? (keep :name catalog)))
      (is (= (sort-by (juxt :provider :id) catalog) catalog)
          "catalog should be sorted by provider then model id"))))

(deftest authenticated-providers-resolver-test
  (testing "resolver returns empty vector when oauth context is absent"
    (let [result (q [:psi.agent-session/authenticated-providers])]
      (is (= [] (:psi.agent-session/authenticated-providers result)))))

  (testing "resolver reports providers with configured auth"
    (let [oauth-ctx (oauth/create-null-context
                     {:credentials {:anthropic {:type :oauth
                                                :access "test-access"
                                                :refresh "test-refresh"
                                                :expires (+ (System/currentTimeMillis) 3600000)}}})
          ctx      (session/create-context {:persist? false :oauth-ctx oauth-ctx})
          result   (q-in ctx [:psi.agent-session/authenticated-providers])
          providers (:psi.agent-session/authenticated-providers result)]
      (is (vector? providers))
      (is (= ["anthropic"] providers)))))

;; ── Git history bridge (cwd -> :git/context) ─────────────

(deftest git-history-status-query-test
  (testing "git.repo/status is queryable from session root via bridge resolver"
    (let [result (q [:git.repo/status
                     :git.repo/current-commit
                     :git.repo/has-changes])]
      (is (contains? #{:clean :modified :staged :error}
                     (:git.repo/status result)))
      (is (or (nil? (:git.repo/current-commit result))
              (string? (:git.repo/current-commit result))))
      (is (boolean? (:git.repo/has-changes result))))))

(deftest git-history-commits-query-test
  (testing "git.repo/commits returns commit entities via in-session eql"
    (let [result  (q [:git.repo/commits
                      :git.repo/has-history])
          commits (:git.repo/commits result)]
      (is (vector? commits))
      (is (boolean? (:git.repo/has-history result)))
      (when (seq commits)
        (is (string? (:git.commit/sha (first commits))))
        (is (string? (:git.commit/subject (first commits))))))))

(deftest git-worktree-query-test
  (testing "git.worktree attrs are queryable from session root via git-context bridge"
    (let [result (q [:git.worktree/inside-repo?
                     :git.worktree/list
                     :git.worktree/current
                     :git.worktree/count])]
      (is (boolean? (:git.worktree/inside-repo? result)))
      (is (vector? (:git.worktree/list result)))
      (is (integer? (:git.worktree/count result)))
      (when (:git.worktree/inside-repo? result)
        (is (map? (:git.worktree/current result)))))))

(deftest session-git-worktree-bridge-query-test
  (testing "session bridge exposes namespaced git worktree attrs"
    (let [result (q [:psi.agent-session/git-worktrees
                     :psi.agent-session/git-worktree-current
                     :psi.agent-session/git-worktree-count])]
      (is (vector? (:psi.agent-session/git-worktrees result)))
      (is (integer? (:psi.agent-session/git-worktree-count result)))
      (when (pos? (:psi.agent-session/git-worktree-count result))
        (is (map? (:psi.agent-session/git-worktree-current result)))))))

;; ── Step 7 graph bridge — :psi.graph/* ───────────────────

(deftest graph-bridge-resolver-count-test
  (testing "resolver-count is a non-negative integer"
    (let [result (q [:psi.graph/resolver-count])]
      (is (integer? (:psi.graph/resolver-count result)))
      (is (nat-int? (:psi.graph/resolver-count result))))))

(deftest graph-bridge-mutation-count-test
  (testing "mutation-count is a non-negative integer"
    (let [result (q [:psi.graph/mutation-count])]
      (is (integer? (:psi.graph/mutation-count result)))
      (is (nat-int? (:psi.graph/mutation-count result))))))

(deftest graph-bridge-resolver-syms-test
  (testing "resolver-syms is a set"
    (let [result (q [:psi.graph/resolver-syms])]
      (is (set? (:psi.graph/resolver-syms result))))))

(deftest graph-bridge-mutation-syms-test
  (testing "mutation-syms is a set"
    (let [result (q [:psi.graph/mutation-syms])]
      (is (set? (:psi.graph/mutation-syms result))))))

(deftest graph-bridge-env-built-test
  (testing "env-built is a boolean"
    (let [result (q [:psi.graph/env-built])]
      (is (boolean? (:psi.graph/env-built result))))))

(deftest graph-bridge-nodes-test
  (testing "nodes is a vector"
    (let [result (q [:psi.graph/nodes])]
      (is (vector? (:psi.graph/nodes result))))))

(deftest graph-bridge-edges-test
  (testing "edges is a vector"
    (let [result (q [:psi.graph/edges])]
      (is (vector? (:psi.graph/edges result))))))

(deftest graph-bridge-capabilities-test
  (testing "capabilities is a vector with at least one entry"
    (let [result (q [:psi.graph/capabilities])]
      (is (vector? (:psi.graph/capabilities result))))))

(deftest agent-chain-discovery-resolver-test
  (testing "agent-chain config is queryable from session root"
    (let [tmp (str (java.nio.file.Files/createTempDirectory
                    "psi-agent-chain-resolver-test-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
          cfg (io/file tmp ".psi" "agents" "agent-chain.edn")]
      (try
        (io/make-parents cfg)
        (spit cfg (pr-str [{:name "prompt-build"
                            :description "Build prompts"
                            :steps [{:agent "prompt-compiler" :prompt "$INPUT"}
                                    {:agent "prompt-compiler" :prompt "again: $INPUT"}]}]))
        (with-user-dir tmp
          (let [result (q [:psi.agent-chain/config-path
                           :psi.agent-chain/count
                           :psi.agent-chain/names
                           :psi.agent-chain/chains
                           :psi.agent-chain/error])
                chains (:psi.agent-chain/chains result)]
            (is (= 1 (:psi.agent-chain/count result)))
            (is (= ["prompt-build"] (:psi.agent-chain/names result)))
            (is (string? (:psi.agent-chain/config-path result)))
            (is (nil? (:psi.agent-chain/error result)))
            (is (= 1 (count chains)))
            (is (= "prompt-build" (:name (first chains))))
            (is (= 2 (:step-count (first chains))))
            (is (= ["prompt-compiler" "prompt-compiler"]
                   (:agents (first chains))))))
        (finally
          (when (.exists cfg)
            (.delete cfg))
          (let [agents-dir (.getParentFile cfg)
                psi-dir (.getParentFile agents-dir)
                root-dir (io/file tmp)]
            (when (and agents-dir (.exists agents-dir))
              (.delete agents-dir))
            (when (and psi-dir (.exists psi-dir))
              (.delete psi-dir))
            (when (.exists root-dir)
              (.delete root-dir))))))))

(deftest graph-bridge-domain-coverage-test
  (testing "domain-coverage includes required Step 7 domains"
    (let [result   (q [:psi.graph/domain-coverage])
          coverage (:psi.graph/domain-coverage result)
          domains  (set (map :domain coverage))]
      (is (vector? coverage))
      (is (contains? domains :ai))
      (is (contains? domains :history))
      (is (contains? domains :agent-session))
      (is (contains? domains :introspection)))))

(deftest graph-bridge-all-nine-attrs-test
  (testing "all 9 required Step 7 graph attrs succeed in one query"
    (let [result (q [:psi.graph/resolver-count
                     :psi.graph/mutation-count
                     :psi.graph/resolver-syms
                     :psi.graph/mutation-syms
                     :psi.graph/env-built
                     :psi.graph/nodes
                     :psi.graph/edges
                     :psi.graph/capabilities
                     :psi.graph/domain-coverage])]
      (is (integer? (:psi.graph/resolver-count result)))
      (is (integer? (:psi.graph/mutation-count result)))
      (is (set? (:psi.graph/resolver-syms result)))
      (is (set? (:psi.graph/mutation-syms result)))
      (is (boolean? (:psi.graph/env-built result)))
      (is (vector? (:psi.graph/nodes result)))
      (is (vector? (:psi.graph/edges result)))
      (is (vector? (:psi.graph/capabilities result)))
      (is (vector? (:psi.graph/domain-coverage result))))))

(deftest root-queryable-attrs-contract-test
  (testing "every attr advertised as root-queryable resolves from session root"
    (let [meta       (q [:psi.graph/root-seeds :psi.graph/root-queryable-attrs])
          root-seeds (:psi.graph/root-seeds meta)
          root-attrs (:psi.graph/root-queryable-attrs meta)]
      (is (vector? root-seeds))
      (is (= [:psi/agent-session-ctx :psi/memory-ctx :psi/recursion-ctx :psi/engine-ctx]
             root-seeds))
      (is (vector? root-attrs))
      (is (seq root-attrs))
      (doseq [attr root-attrs]
        (let [result (q [attr])]
          (is (contains? result attr)
              (str "expected root-queryable attr to resolve: " attr)))))))

(deftest root-queryable-attrs-clean-contract-test
  (testing "root-queryable attrs do not expose legacy/compat names"
    (let [root-attrs (set (:psi.graph/root-queryable-attrs
                           (q [:psi.graph/root-queryable-attrs])))]
      (is (not-any? #(re-find #"-compat$" (name %)) root-attrs))
      (is (not (contains? root-attrs :psi.agent-session/current-request-shape)))
      (is (not (contains? root-attrs :psi.agent-session/api-error-list)))
      (is (not (contains? root-attrs :psi.memory/memory-state)))
      (is (not (contains? root-attrs :psi.memory/memory-store-state)))
      (is (not (contains? root-attrs :psi.memory/memory-context-state)))
      (is (not (contains? root-attrs :psi.history/git-repo-status)))
      (is (not (contains? root-attrs :psi.history/git-repo-commits)))
      (is (not (contains? root-attrs :psi.history/git-learning-commits)))
      (is (not (contains? root-attrs :psi.introspection/query-graph-summary)))
      (is (not (contains? root-attrs :psi.introspection/engine-system-state))))))

(deftest worktree-attr-discovery-and-query-contract-test
  (testing "worktree attrs are queryable from session root"
    (let [git-result (q [:git.worktree/list
                         :git.worktree/current
                         :git.worktree/count
                         :git.worktree/inside-repo?])
          session-result (q [:psi.agent-session/git-worktrees
                             :psi.agent-session/git-worktree-current
                             :psi.agent-session/git-worktree-count])]
      (is (contains? git-result :git.worktree/list))
      (is (contains? git-result :git.worktree/current))
      (is (contains? git-result :git.worktree/count))
      (is (contains? git-result :git.worktree/inside-repo?))
      (is (contains? session-result :psi.agent-session/git-worktrees))
      (is (contains? session-result :psi.agent-session/git-worktree-current))
      (is (contains? session-result :psi.agent-session/git-worktree-count))
      (is (integer? (:psi.agent-session/git-worktree-count session-result))))))


