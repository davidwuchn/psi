#!/usr/bin/env clojure
;;
;; Component-isolated test runner
;; Tests each component separately to avoid circular dependency issues
;;

(require '[clojure.test :as test]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn find-test-namespaces
  "Find all test namespaces in a directory"
  [test-dir]
  (when (.exists (io/file test-dir))
    (->> (file-seq (io/file test-dir))
         (filter #(.endsWith (.getName %) "_test.clj"))
         (map #(-> (.getPath %)
                   (str/replace test-dir "")
                   (str/replace "/" ".")
                   (str/replace "\\" ".")
                   (str/replace #"^\.+" "")
                   (str/replace #"\.clj$" "")
                   (str/replace "_" "-")
                   symbol)))))

(defn test-component
  "Test a single component in isolation"
  [component-name]
  (println (str "\n" (str/join (repeat 60 "=")) "\n"))
  (println (str "🧪 Testing component: " component-name))
  (println (str (str/join (repeat 60 "=")) "\n"))

  (let [test-dir (str "components/" component-name "/test")
        test-nses (find-test-namespaces test-dir)]

    (if (empty? test-nses)
      (println (str "⚠️  No tests found in " test-dir))
      (do
        (println (str "Found " (count test-nses) " test namespaces"))

        ;; Test each namespace
        (doseq [test-ns test-nses]
          (println (str "\n--- Testing " test-ns " ---"))
          (try
            (require test-ns)
            (let [result (test/run-tests test-ns)]
              (if (and (zero? (:fail result)) (zero? (:error result)))
                (println (str "✅ " test-ns ": " (:test result) " tests, " (:pass result) " assertions"))
                (println (str "❌ " test-ns ": " (:fail result) " failures, " (:error result) " errors"))))

            (catch Exception e
              (println (str "💥 " test-ns ": " (.getMessage e))))))))))

;; Components to test (in dependency order to minimize conflicts)
(def components-to-test
  ["query"           ; No internal deps
   "engine"          ; No internal deps  
   "memory"          ; query
   "history"         ; query
   "recursion"       ; memory, query
   "agent-core"      ; No internal deps
   "ai"              ; engine, query
   "tui"             ; No internal deps
   "agent-session"   ; Many deps - test last
   ])

(println "🚀 Starting component-isolated test runner...")
(println (str "Testing " (count components-to-test) " components\n"))

(doseq [component components-to-test]
  (test-component component))

(println "\n🏁 Component-isolated testing complete!")