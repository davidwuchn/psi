(ns psi.agent-session.graph-surface-test
  "Focused contract tests for the session-root graph discovery surface.

   Scope:
   - :psi.graph/* queryability and shape
   - root seed and root-queryable advertisement
   - graph introspection visibility for bridged attrs
   - graph summary coherence invariants

   Non-scope:
   - concrete resolver business behavior; see resolvers_test.clj

   This namespace exists to keep the graph surface easy to scan and to make
   discovery-contract regressions obvious."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]))

(defn- q
  "Run EQL query against a fresh session context."
  [eql]
  (session/query-in (session/create-context {:persist? false}) eql))

(defn- join-attr?
  [x]
  (and (map? x) (= 1 (count x)) (keyword? (ffirst x))))

(defn- graph-attr-present?
  [attrs k]
  (or (some #(= k %) attrs)
      (some #(and (join-attr? %) (contains? % k)) attrs)))

;; Graph contract helpers mirror the public discovery surface:
;; - capabilities are rich summaries for domains present in the graph
;; - domain-coverage is normalized and includes required zero-count domains
;; - edges are operation->capability membership edges annotated by :attribute
;; - edge :attribute may be a keyword, a join map, or nil
;; - root-queryable attrs come from resolver-only fixed-point reachability

(defn- assert-graph-summary-map
  [m]
  (is (and (map? m)
           (contains? m :domain)
           (contains? m :operation-count)
           (contains? m :resolver-count)
           (contains? m :mutation-count)
           (keyword? (:domain m))
           (nat-int? (:operation-count m))
           (nat-int? (:resolver-count m))
           (nat-int? (:mutation-count m)))
      (str "expected graph summary map, got: " (pr-str m))))

(defn- assert-graph-node
  [node]
  (is (and (map? node)
           (contains? node :id)
           (contains? node :type)
           (contains? node :domain)
           (#{:resolver :mutation :capability} (:type node)))
      (str "expected graph node map, got: " (pr-str node))))

(defn- assert-graph-edge
  [edge]
  (is (and (map? edge)
           (contains? edge :from)
           (contains? edge :to)
           (contains? edge :attribute)
           (or (nil? (:attribute edge))
               (keyword? (:attribute edge))
               (join-attr? (:attribute edge))))
      (str "expected graph edge map, got: " (pr-str edge))))

(def canonical-graph-root-attrs
  #{:psi.graph/root-seeds
    :psi.graph/root-queryable-attrs
    :psi.graph/resolver-count
    :psi.graph/mutation-count
    :psi.graph/resolver-syms
    :psi.graph/mutation-syms
    :psi.graph/env-built
    :psi.graph/nodes
    :psi.graph/edges
    :psi.graph/capabilities
    :psi.graph/domain-coverage})

(defn- assert-canonical-graph-root-attrs
  [root-attrs]
  (let [root-attrs (set root-attrs)
        missing    (seq (remove root-attrs canonical-graph-root-attrs))]
    (is (nil? missing)
        (str "expected canonical graph root attrs to be advertised, missing: "
             (pr-str (vec missing))))))

(deftest host-index-graph-introspection-test
  (testing "host-index attrs are discoverable in graph root attrs and edges"
    (let [result     (q [:psi.graph/root-queryable-attrs :psi.graph/edges])
          root-attrs (:psi.graph/root-queryable-attrs result)
          edge-attrs (keep :attribute (:psi.graph/edges result))]
      (is (graph-attr-present? root-attrs :psi.agent-session/host-active-session-id))
      (is (graph-attr-present? root-attrs :psi.agent-session/host-session-count))
      (is (graph-attr-present? edge-attrs :psi.agent-session/host-active-session-id))
      (is (graph-attr-present? edge-attrs :psi.agent-session/host-session-count))
      (is (graph-attr-present? edge-attrs :psi.agent-session/host-sessions)))))

(deftest background-jobs-graph-introspection-test
  (testing "background job attrs are discoverable in graph introspection"
    (let [result (q [:psi.graph/root-queryable-attrs
                     :psi.graph/edges])
          root-attrs (set (:psi.graph/root-queryable-attrs result))
          edge-attrs (set (keep :attribute (:psi.graph/edges result)))]
      (is (contains? root-attrs :psi.agent-session/background-job-count))
      (is (contains? root-attrs :psi.agent-session/background-job-statuses))
      (is (contains? root-attrs :psi.agent-session/background-jobs))
      (is (contains? edge-attrs :psi.agent-session/background-job-count))
      (is (contains? edge-attrs :psi.agent-session/background-job-statuses))
      (is (contains? edge-attrs :psi.agent-session/background-jobs)))))

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
  (testing "resolver-syms is a set of symbols"
    (let [result (q [:psi.graph/resolver-syms])
          syms   (:psi.graph/resolver-syms result)]
      (is (set? syms))
      (is (every? symbol? syms))
      (is (contains? syms 'psi.agent-session.resolvers/agent-session-identity)))))

(deftest graph-bridge-mutation-syms-test
  (testing "mutation-syms is a set of symbols"
    (let [result (q [:psi.graph/mutation-syms])
          syms   (:psi.graph/mutation-syms result)]
      (is (set? syms))
      (is (every? symbol? syms)))))

(deftest graph-bridge-env-built-test
  (testing "env-built is a boolean"
    (let [result (q [:psi.graph/env-built])]
      (is (boolean? (:psi.graph/env-built result))))))

(deftest graph-bridge-nodes-test
  (testing "nodes is a vector of graph node maps"
    (let [result (q [:psi.graph/nodes])
          nodes  (:psi.graph/nodes result)]
      (is (vector? nodes))
      (doseq [node nodes]
        (assert-graph-node node)))))

(deftest graph-bridge-edges-test
  (testing "edges are operation->capability membership edges with annotated attributes"
    (let [result (q [:psi.graph/edges])
          edges  (:psi.graph/edges result)]
      (is (vector? edges))
      (doseq [edge edges]
        (assert-graph-edge edge)))))

(deftest graph-bridge-capabilities-test
  (testing "capabilities is a non-empty vector of rich summaries for domains present in the graph"
    (let [result       (q [:psi.graph/capabilities])
          capabilities (:psi.graph/capabilities result)]
      (is (vector? capabilities))
      (is (seq capabilities))
      (doseq [capability capabilities]
        (assert-graph-summary-map capability)))))

(deftest graph-bridge-domain-coverage-test
  (testing "domain-coverage is normalized and includes required Step 7 domains even when empty"
    (let [result   (q [:psi.graph/domain-coverage])
          coverage (:psi.graph/domain-coverage result)
          domains  (set (map :domain coverage))]
      (is (vector? coverage))
      (is (seq coverage))
      (doseq [domain-summary coverage]
        (assert-graph-summary-map domain-summary))
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
      (is (vector? (:psi.graph/domain-coverage result)))
      (is (= (:psi.graph/resolver-count result)
             (count (:psi.graph/resolver-syms result))))
      (is (= (:psi.graph/mutation-count result)
             (count (:psi.graph/mutation-syms result))))
      (is (seq (:psi.graph/capabilities result)))
      (is (seq (:psi.graph/domain-coverage result))))))

(deftest root-queryable-attrs-contract-test
  (testing "every advertised root-queryable attr resolves from session root via resolver-only reachability"
    (let [meta       (q [:psi.graph/root-seeds :psi.graph/root-queryable-attrs])
          root-seeds (:psi.graph/root-seeds meta)
          root-attrs (:psi.graph/root-queryable-attrs meta)]
      (is (vector? root-seeds))
      (is (every? keyword? root-seeds))
      (is (= [:psi/agent-session-ctx :psi/memory-ctx :psi/recursion-ctx :psi/engine-ctx]
             root-seeds))
      (is (vector? root-attrs))
      (is (seq root-attrs))
      (is (every? keyword? root-attrs))
      (assert-canonical-graph-root-attrs root-attrs)
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
