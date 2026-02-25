(ns psi.engine.core-test
  "Tests for core engine functionality.

  Uses engine/create-context (Nullable pattern) so every test gets its
  own isolated set of atoms — no global-state mutations, no private-var
  hacking."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.engine.core :as engine]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Schema-validation tests — pure logic, no context needed
;; ─────────────────────────────────────────────────────────────────────────────

(deftest schema-validation-test
  (testing "Engine schema validation"
    (is (engine/valid-engine?
         {:engine-id         "test-engine"
          :engine-status     :ready
          :statechart-config {}
          :active-states     #{"ready"}})
        "Valid engine should pass validation")

    (is (not (engine/valid-engine?
              {:engine-id         123          ; should be string
               :engine-status     :invalid-status
               :statechart-config {}
               :active-states     #{"ready"}}))
        "Invalid engine should fail validation"))

  (testing "System state schema validation"
    (is (engine/valid-system-state?
         {:current-mode    :explore
          :evolution-stage :bootstrap
          :last-updated    (java.time.Instant/now)})
        "Valid system state should pass validation")

    (is (not (engine/valid-system-state?
              {:current-mode    :invalid-mode   ; not in enum
               :evolution-stage :bootstrap
               :last-updated    (java.time.Instant/now)}))
        "Invalid system state should fail validation"))

  (testing "State transition schema validation"
    (is (engine/valid-state-transition?
         {:engine-id  "test-engine"
          :from-state "initializing"
          :to-state   "ready"
          :trigger    "configuration-complete"
          :timestamp  (java.time.Instant/now)
          :context    {}})
        "Valid state transition should pass validation")

    (is (not (engine/valid-state-transition?
              {:engine-id  "test-engine"
               :from-state "initializing"
               :to-state   "ready"
               :trigger    "configuration-complete"
               :timestamp  "not-an-instant"   ; should be Instant
               :context    {}}))
        "Invalid state transition should fail validation")))

;; ─────────────────────────────────────────────────────────────────────────────
;; Engine lifecycle — isolated context
;; ─────────────────────────────────────────────────────────────────────────────

(deftest engine-lifecycle-test
  (testing "Engine creation and lifecycle"
    (let [ctx       (engine/create-context)
          engine-id "test-engine"
          config    {:test true}
          eng       (engine/create-engine-in ctx engine-id config)]

      (is (= engine-id (:engine-id eng))
          "Engine should have correct ID")

      (is (= config (:statechart-config eng))
          "Engine should store configuration")

      (is (= :initializing (:engine-status eng))
          "Engine should start in initializing state")

      (testing "engine is retrievable"
        (is (some? (engine/get-engine-in ctx engine-id))))

      (testing "engine status contains expected keys"
        (let [status (engine/engine-status-in ctx engine-id)]
          (is (contains? status :engine-status))
          (is (contains? status :active-states)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; System state management — isolated context
;; ─────────────────────────────────────────────────────────────────────────────

(deftest system-state-management-test
  (testing "System state initialization and updates"
    (let [ctx (engine/create-context)]

      (testing "initial state"
        (let [state (engine/initialize-system-state-in! ctx)]
          (is (= :explore (:current-mode state))
              "System should start in explore mode")
          (is (= :bootstrap (:evolution-stage state))
              "System should start in bootstrap stage")
          (is (false? (:engine-ready state))
              "Engine should not be ready initially")))

      (testing "component update"
        (engine/update-system-component-in! ctx :engine-ready true)
        (is (:engine-ready (engine/get-system-state-in ctx))
            "Engine should be marked as ready"))

      (testing "derived interface property"
        (engine/update-system-component-in! ctx :query-ready true)
        (is (engine/system-has-interface-in? ctx)
            "System should have interface when engine and query are ready")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Derived properties — isolated context
;; ─────────────────────────────────────────────────────────────────────────────

(deftest derived-properties-test
  (testing "System derived property calculations"
    (let [ctx (engine/create-context)]
      (engine/initialize-system-state-in! ctx)
      (doseq [[k v] {:engine-ready        true
                     :query-ready         true
                     :graph-ready         false
                     :introspection-ready false
                     :history-ready       true
                     :knowledge-ready     true
                     :memory-ready        false}]
        (engine/update-system-component-in! ctx k v))

      (is (engine/system-has-interface-in? ctx)
          "Should have interface when engine and query ready")

      (is (engine/system-has-substrate-in? ctx)
          "Should have substrate when engine ready")

      (is (engine/system-has-memory-layer-in? ctx)
          "Should have memory layer when query, history, and knowledge ready")

      (is (not (engine/system-is-ai-complete-in? ctx))
          "Should not be AI complete when not all components ready"))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Bootstrap integration — isolated context
;; ─────────────────────────────────────────────────────────────────────────────

(deftest bootstrap-integration-test
  (testing "Full system bootstrap"
    (let [ctx    (engine/create-context)
          result (engine/bootstrap-system-in! ctx)]

      (is (contains? result :system-state)
          "Bootstrap should return system state")

      (is (contains? result :main-engine)
          "Bootstrap should return main engine")

      (is (contains? result :diagnostics)
          "Bootstrap should return diagnostics")

      (let [diagnostics (:diagnostics result)]
        (is (= 1 (:engine-count diagnostics))
            "Should have one engine after bootstrap")

        (is (true? (get-in diagnostics [:derived-properties :has-substrate]))
            "Should have substrate after bootstrap")))))
