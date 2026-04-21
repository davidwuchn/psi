(ns extensions.workflow-loader-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-loader :as loader]
   [psi.agent-session.workflow-file-parser :as parser]
   [psi.agent-session.workflow-file-compiler :as compiler]
   [psi.agent-session.workflow-model :as workflow-model]))

;;; End-to-end: raw file text → parsed → compiled → valid canonical definition

(def planner-raw
  (str "---\nname: planner\ndescription: Plans tasks\n---\n"
       "{:tools [\"read\" \"bash\"]}\n\n"
       "You are a planner."))

(def builder-raw
  (str "---\nname: builder\ndescription: Builds code\n---\n"
       "{:tools [\"read\" \"bash\" \"edit\" \"write\"]\n"
       " :skills [\"clojure-coding-standards\"]}\n\n"
       "You are a builder agent."))

(def reviewer-raw
  (str "---\nname: reviewer\ndescription: Reviews code\n---\n"
       "You are a reviewer."))

(def chain-raw
  (str "---\nname: plan-build-review\ndescription: Plan, build, and review\n---\n"
       "{:steps [{:workflow \"planner\" :prompt \"$INPUT\"}\n"
       "         {:workflow \"builder\" :prompt \"Execute: $INPUT\\nOriginal: $ORIGINAL\"}\n"
       "         {:workflow \"reviewer\" :prompt \"Review: $INPUT\\nOriginal: $ORIGINAL\"}]}\n\n"
       "Coordinate a plan-build-review cycle."))

(deftest end-to-end-single-step-test
  (testing "raw planner file → parse → compile → valid canonical definition"
    (let [parsed (parser/parse-workflow-file planner-raw)
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "planner" (:definition-id definition)))
      (is (= ["step-1"] (:step-order definition)))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Tools carry through
      (is (= #{"read" "bash"} (get-in definition [:steps "step-1" :capability-policy :tools])))
      ;; System prompt in metadata
      (is (= "You are a planner." (get-in definition [:workflow-file-meta :system-prompt]))))))

(deftest end-to-end-multi-step-test
  (testing "raw chain file → parse → compile → valid canonical multi-step definition"
    (let [parsed (parser/parse-workflow-file chain-raw)
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "plan-build-review" (:definition-id definition)))
      (is (= 3 (count (:step-order definition))))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Framing prompt in metadata
      (is (= "Coordinate a plan-build-review cycle."
             (get-in definition [:workflow-file-meta :framing-prompt]))))))

(deftest end-to-end-batch-with-validation-test
  (testing "batch parse-compile-validate across all workflow types"
    (let [all-raw [planner-raw builder-raw reviewer-raw chain-raw]
          parsed (mapv parser/parse-workflow-file all-raw)
          {:keys [definitions errors]} (compiler/compile-workflow-files parsed)]
      (is (= 4 (count definitions)))
      (is (empty? errors))
      (is (every? workflow-model/valid-workflow-definition? definitions))
      ;; Step references all resolve
      (is (true? (:valid? (compiler/validate-step-references definitions))))
      ;; No name collisions
      (is (true? (:valid? (compiler/validate-no-name-collisions definitions)))))))

(deftest legacy-agent-profile-compatibility-test
  (testing "existing .psi/agents/*.md files parse and compile cleanly"
    ;; Current agent profiles have YAML frontmatter with tools: and lambda: keys
    (let [raw (str "---\n"
                   "name: planner\n"
                   "description: Analyzes tasks, creates implementation plans\n"
                   "lambda: λtask. analyze(task)\n"
                   "tools: read,bash\n"
                   "---\n\n"
                   "You are a planning agent.")
          parsed (parser/parse-workflow-file raw)
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "planner" (:definition-id definition)))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Body becomes system prompt (no EDN config detected since body doesn't start with '{')
      (is (= "You are a planning agent."
             (get-in definition [:workflow-file-meta :system-prompt])))
      ;; Note: tools: from YAML frontmatter is not auto-migrated to EDN config —
      ;; migration step will handle that conversion
      )))
