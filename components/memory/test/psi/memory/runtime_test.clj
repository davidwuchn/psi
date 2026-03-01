(ns psi.memory.runtime-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.memory.runtime :as runtime]))

(deftest runtime-public-api-vars-exist
  (is (var? #'runtime/sync-memory-layer!))
  (is (var? #'runtime/remember-session-message!))
  (is (var? #'runtime/recover-for-query!)))

