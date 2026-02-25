(ns psi.engine.core-test
  "Tests for core engine functionality"
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.engine.core :as engine]))

(deftest schema-validation-test
  (testing "Engine schema validation"
    (let [valid-engine {:engine-id "test-engine"
                        :engine-status :ready
                        :statechart-config {}
                        :active-states #{"ready"}}]
      (is (engine/valid-engine? valid-engine)
          "Valid engine should pass validation"))
    
    (let [invalid-engine {:engine-id 123  ; should be string
                          :engine-status :invalid-status
                          :statechart-config {}
                          :active-states #{"ready"}}]
      (is (not (engine/valid-engine? invalid-engine))
          "Invalid engine should fail validation")))
  
  (testing "System state schema validation"
    (let [valid-state {:current-mode :explore
                       :evolution-stage :bootstrap
                       :last-updated (java.time.Instant/now)}]
      (is (engine/valid-system-state? valid-state)
          "Valid system state should pass validation"))
    
    (let [invalid-state {:current-mode :invalid-mode  ; not in enum
                         :evolution-stage :bootstrap
                         :last-updated (java.time.Instant/now)}]
      (is (not (engine/valid-system-state? invalid-state))
          "Invalid system state should fail validation")))
  
  (testing "State transition schema validation"
    (let [valid-transition {:engine-id "test-engine"
                            :from-state "initializing"
                            :to-state "ready"
                            :trigger "configuration-complete"
                            :timestamp (java.time.Instant/now)
                            :context {}}]
      (is (engine/valid-state-transition? valid-transition)
          "Valid state transition should pass validation"))
    
    (let [invalid-transition {:engine-id "test-engine"
                              :from-state "initializing"
                              :to-state "ready"
                              :trigger "configuration-complete"
                              :timestamp "not-an-instant"  ; should be instant
                              :context {}}]
      (is (not (engine/valid-state-transition? invalid-transition))
          "Invalid state transition should fail validation"))))

(deftest engine-lifecycle-test
  (testing "Engine creation and lifecycle"
    ;; Clear state before test
    (reset! @#'engine/engines {})
    (reset! @#'engine/system-state nil)
    (reset! @#'engine/state-transitions [])
    (reset! @#'engine/sc-env nil)
    
    ;; Test engine creation
    (let [engine-id "test-engine"
          config {:test true}
          engine (engine/create-engine engine-id config)]
      
      (is (= engine-id (:engine-id engine))
          "Engine should have correct ID")
      
      (is (= config (:statechart-config engine))
          "Engine should store configuration")
      
      (is (= :initializing (:engine-status engine))
          "Engine should start in initializing state")
      
      ;; Test engine retrieval
      (let [retrieved-engine (engine/get-engine engine-id)]
        (is (not (nil? retrieved-engine))
            "Should be able to retrieve created engine"))
      
      ;; Test engine status
      (let [status (engine/engine-status engine-id)]
        (is (contains? status :engine-status)
            "Status should include engine status")
        (is (contains? status :active-states)
            "Status should include active states")))))

(deftest system-state-management-test
  (testing "System state initialization and updates"
    ;; Clear state before test
    (reset! @#'engine/engines {})
    (reset! @#'engine/system-state nil)
    (reset! @#'engine/state-transitions [])
    (reset! @#'engine/sc-env nil)
    
    ;; Initialize system state
    (let [state (engine/initialize-system-state!)]
      (is (= :explore (:current-mode state))
          "System should start in explore mode")
      
      (is (= :bootstrap (:evolution-stage state))
          "System should start in bootstrap stage")
      
      (is (false? (:engine-ready state))
          "Engine should not be ready initially"))
    
    ;; Test component updates
    (engine/update-system-component! :engine-ready true)
    (let [updated-state (engine/get-system-state)]
      (is (:engine-ready updated-state)
          "Engine should be marked as ready"))
    
    ;; Test derived properties
    (engine/update-system-component! :query-ready true)
    (is (engine/system-has-interface?)
        "System should have interface when engine and query are ready")))

(deftest derived-properties-test
  (testing "System derived property calculations"
    ;; Clear state before test
    (reset! @#'engine/engines {})
    (reset! @#'engine/system-state nil)
    (reset! @#'engine/state-transitions [])
    (reset! @#'engine/sc-env nil)
    
    ;; Initialize system state and update components
    (engine/initialize-system-state!)
    (engine/update-system-component! :engine-ready true)
    (engine/update-system-component! :query-ready true)
    (engine/update-system-component! :graph-ready false)
    (engine/update-system-component! :introspection-ready false)
    (engine/update-system-component! :history-ready true)
    (engine/update-system-component! :knowledge-ready true)
    (engine/update-system-component! :memory-ready false)
    
    (is (engine/system-has-interface?)
        "Should have interface when engine and query ready")
    
    (is (engine/system-has-substrate?)
        "Should have substrate when engine ready")
    
    (is (engine/system-has-memory-layer?)
        "Should have memory layer when query, history, and knowledge ready")
    
    (is (not (engine/system-is-ai-complete?))
        "Should not be AI complete when not all components ready")))

(deftest bootstrap-integration-test
  (testing "Full system bootstrap"
    ;; Clear state before test
    (reset! @#'engine/engines {})
    (reset! @#'engine/system-state nil)
    (reset! @#'engine/state-transitions [])
    (reset! @#'engine/sc-env nil)
    
    ;; Bootstrap the system
    (let [result (engine/bootstrap-system!)]
      
      (is (contains? result :system-state)
          "Bootstrap should return system state")
      
      (is (contains? result :main-engine)
          "Bootstrap should return main engine")
      
      (is (contains? result :diagnostics)
          "Bootstrap should return diagnostics")
      
      ;; Verify system is properly initialized
      (let [diagnostics (:diagnostics result)]
        (is (= 1 (:engine-count diagnostics))
            "Should have one engine after bootstrap")
        
        (is (true? (get-in diagnostics [:derived-properties :has-substrate]))
            "Should have substrate after bootstrap")))))