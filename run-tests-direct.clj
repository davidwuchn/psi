#!/usr/bin/env clojure

(require '[clojure.test :as test])

;; Test namespaces that should work
(def test-namespaces
  '[psi.agent-core.core-test
    psi.memory.core-test
    psi.ai.core-test])

(println "=== Running tests directly via clojure.test ===")

(doseq [ns-sym test-namespaces]
  (println (str "\n--- Testing " ns-sym " ---"))
  (try
    (require ns-sym)
    (let [result (test/run-tests ns-sym)]
      (println (format "✓ %s: %d tests, %d pass, %d fail, %d errors"
                       ns-sym
                       (:test result)
                       (:pass result)
                       (:fail result)
                       (:error result))))
    (catch Exception e
      (println (format "✗ %s: Failed to load - %s" ns-sym (.getMessage e))))))

(println "\n=== Direct test run complete ===")