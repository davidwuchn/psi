(ns psi.agent-session.tool-defs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.tool-defs :as tool-defs]))

(deftest normalize-tool-def-test
  (testing "structured parameters are preserved canonically"
    (let [tool {:name "x"
                :description "desc"
                :parameters {:type "object"
                             :properties {"p" {:type "string"}}}}
          normalized (tool-defs/normalize-tool-def tool)]
      (is (= "x" (:name normalized)))
      (is (= "x" (:label normalized)))
      (is (= {:type "object"
              :properties {"p" {:type "string"}}}
             (:parameters normalized)))))

  (testing "string parameters are parsed into canonical data when possible"
    (let [tool {:name "x"
                :label "X"
                :description "desc"
                :parameters "{:type \"object\" :required [\"p\"]}"}
          normalized (tool-defs/normalize-tool-def tool)]
      (is (= {:type "object" :required ["p"]}
             (:parameters normalized)))))

  (testing "invalid parameter strings degrade to empty object schema"
    (let [tool {:name "x"
                :parameters "not-edn"}
          normalized (tool-defs/normalize-tool-def tool)]
      (is (= {:type "object"}
             (:parameters normalized))))))

(deftest agent-core-tool-projection-test
  (let [tool {:name "x"
              :description "desc"
              :parameters {:type "object"
                           :properties {"p" {:type "string"}}}}
        projected (tool-defs/agent-core-tool tool)]
    (is (= "x" (:name projected)))
    (is (= "x" (:label projected)))
    (is (= "desc" (:description projected)))
    (is (= {:type "object"
            :properties {"p" {:type "string"}}}
           (:parameters projected)))))

(deftest provider-tool-projection-test
  (let [tool {:name "x"
              :description "desc"
              :parameters {:type "object"
                           :required ["p"]}}
        projected (tool-defs/provider-tool tool)]
    (is (= {:name "x"
            :description "desc"
            :parameters {:type "object"
                         :required ["p"]}}
           projected))))
