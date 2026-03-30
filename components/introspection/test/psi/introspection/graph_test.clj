(ns psi.introspection.graph-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.introspection.core :as introspection]
   [psi.introspection.graph :as graph]
   [psi.engine.core :as engine]
   [psi.agent-session.core :as agent-session]
   [psi.query.registry :as registry]))

(defn- operation-metadata
  []
  (let [[session-ctx _] (agent-session/create-context)
        ctx              (introspection/create-context {:agent-session-ctx session-ctx})
        qctx        (:query-ctx ctx)]
    (engine/bootstrap-system-in! (:engine-ctx ctx))
    (introspection/register-resolvers-in! ctx)
    {:resolver-ops (mapv #(graph/operation->metadata :resolver %)
                         (registry/all-resolvers-in (:reg qctx)))
     :mutation-ops (mapv #(graph/operation->metadata :mutation %)
                         (registry/all-mutations-in (:reg qctx)))}))

(def ^:private operation-metadata-fixture
  (delay (operation-metadata)))

(defn- cached-operation-metadata
  []
  @operation-metadata-fixture)

(deftest classify-domain-test
  (testing "domain classification maps required Step 7 domains"
    (is (= :ai (graph/classify-domain 'psi.ai.core/ai-model-resolver)))
    (is (= :history (graph/classify-domain 'psi.history.resolvers/git-log)))
    (is (= :introspection (graph/classify-domain 'psi.introspection.resolvers/query-graph-summary)))
    (is (= :agent-session (graph/classify-domain 'psi.agent-session.core/add-prompt-template)))
    (is (= :agent-session (graph/classify-domain 'psi.extension/add-prompt-template)))))

(deftest derive-capability-graph-deterministic-shape-test
  (testing "capability graph derivation is deterministic and Step 7-constrained"
    (let [ops    (cached-operation-metadata)
          g1     (graph/derive-capability-graph ops)
          g2     (graph/derive-capability-graph ops)
          types  (set (map :type (:nodes g1)))]

      (is (= g1 g2) "graph derivation should be deterministic for same inputs")

      (testing "node types are limited to resolver/mutation/capability"
        (is (= #{:resolver :mutation :capability} types)))

      (testing "at least one edge includes non-nil :attribute metadata"
        (is (some :attribute (:edges g1))))

      (testing "mutation-derived operations have deferred sideEffects=nil"
        (let [mutation-ops (filter #(= :mutation (:type %)) (:domain-operations g1))]
          (is (seq mutation-ops))
          (is (every? #(contains? % :sideEffects) mutation-ops))
          (is (every? #(nil? (:sideEffects %)) mutation-ops)))))))

(deftest domain-coverage-includes-required-domains-test
  (testing "domain coverage always includes required Step 7 domains"
    (let [coverage (-> (cached-operation-metadata)
                       (graph/derive-capability-graph)
                       :domain-coverage)
          by-domain (into {} (map (juxt :domain identity) coverage))]
      (is (contains? by-domain :ai))
      (is (contains? by-domain :history))
      (is (contains? by-domain :agent-session))
      (is (contains? by-domain :introspection)))))

(deftest derive-capability-graph-node-ids-unique-test
  (testing "derived graph node ids are unique"
    (let [node-ids (->> (cached-operation-metadata)
                        graph/derive-capability-graph
                        :nodes
                        (map :id))]
      (is (= (count node-ids) (count (distinct node-ids)))))))

(deftest derive-capability-graph-edge-endpoints-exist-test
  (testing "derived graph edges always point at existing node ids"
    (let [{:keys [nodes edges]} (graph/derive-capability-graph (cached-operation-metadata))
          node-ids              (set (map :id nodes))]
      (is (every? #(contains? node-ids (:from %)) edges))
      (is (every? #(contains? node-ids (:to %)) edges)))))

(deftest derive-capability-graph-operation-symbols-have-nodes-test
  (testing "derived operation symbols are represented by operation nodes"
    (let [{:keys [resolver-ops mutation-ops]} (cached-operation-metadata)
          {:keys [nodes]}                     (graph/derive-capability-graph (cached-operation-metadata))
          resolver-syms                      (set (map :symbol resolver-ops))
          mutation-syms                      (set (map :symbol mutation-ops))
          resolver-node-syms                 (->> nodes
                                                  (filter #(= :resolver (:type %)))
                                                  (map :symbol)
                                                  set)
          mutation-node-syms                 (->> nodes
                                                  (filter #(= :mutation (:type %)))
                                                  (map :symbol)
                                                  set)]
      (is (= resolver-syms resolver-node-syms))
      (is (= mutation-syms mutation-node-syms)))))

(deftest derive-capability-graph-capabilities-align-with-domain-coverage-test
  (testing "derived capabilities align with domain coverage for present domains"
    (let [{:keys [capabilities domain-coverage]} (graph/derive-capability-graph (cached-operation-metadata))
          coverage-map                           (into {} (map (juxt :domain identity) domain-coverage))]
      (doseq [{:keys [domain operation-count resolver-count mutation-count]} capabilities
              :let [coverage (get coverage-map domain)]]
        (is coverage (str "expected domain-coverage entry for capability domain " domain))
        (is (= operation-count (:operation-count coverage)))
        (is (= resolver-count (:resolver-count coverage)))
        (is (= mutation-count (:mutation-count coverage)))))))
