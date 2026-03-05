(ns psi.agent-session.system-prompt-test
  "Tests for system prompt assembly and context file discovery."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.system-prompt :as sys-prompt]))

;; ============================================================
;; Test helpers
;; ============================================================

(defn- make-temp-dir [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- cleanup-dir! [dir]
  (when (.isDirectory dir)
    (doseq [f (.listFiles dir)]
      (cleanup-dir! f)))
  (.delete dir))

;; ============================================================
;; Context file discovery
;; ============================================================

(deftest discover-context-files-test
  (testing "finds AGENTS.md in cwd"
    (let [dir (make-temp-dir "psi-ctx-test")]
      (spit (io/file dir "AGENTS.md") "# Project Context")
      (try
        (let [files (sys-prompt/discover-context-files (str dir))]
          (is (= 1 (count files)))
          (is (str/includes? (:content (first files)) "# Project Context")))
        (finally (cleanup-dir! dir)))))

  (testing "prefers AGENTS.md over CLAUDE.md"
    (let [dir (make-temp-dir "psi-ctx-prefer-test")]
      (spit (io/file dir "AGENTS.md") "AGENTS content")
      (spit (io/file dir "CLAUDE.md") "CLAUDE content")
      (try
        (let [files (sys-prompt/discover-context-files (str dir))]
          ;; Should find AGENTS.md (checked first)
          (is (= 1 (count files)))
          (is (str/includes? (:content (first files)) "AGENTS content")))
        (finally (cleanup-dir! dir)))))

  (testing "returns empty for dir without context files"
    (let [dir (make-temp-dir "psi-ctx-empty-test")]
      (try
        (is (empty? (sys-prompt/discover-context-files (str dir))))
        (finally (cleanup-dir! dir))))))

;; ============================================================
;; System prompt assembly
;; ============================================================

(deftest build-system-prompt-test
  (testing "default prompt includes tools, guidelines, and graph discovery"
    (let [prompt (sys-prompt/build-system-prompt {:cwd "/test/dir"})]
      (is (str/includes? prompt "Available tools:"))
      (is (str/includes? prompt "read: Read file contents"))
      (is (str/includes? prompt "eql_query: Execute an EQL query against the live session graph."))
      (is (str/includes? prompt "Guidelines:"))
      (is (str/includes? prompt "Capability graph (EQL discovery):"))
      (is (str/includes? prompt ":psi.graph/resolver-syms"))
      (is (str/includes? prompt ":psi.agent-session/usage-input"))
      (is (str/includes? prompt "/test/dir"))))

  (testing "includes current date/time"
    (let [prompt (sys-prompt/build-system-prompt {})]
      (is (str/includes? prompt "Current date and time:"))))

  (testing "includes context files"
    (let [prompt (sys-prompt/build-system-prompt
                  {:context-files [{:path "/project/AGENTS.md"
                                    :content "# My Project\nInstructions here"}]})]
      (is (str/includes? prompt "# Project Context"))
      (is (str/includes? prompt "Instructions here"))))

  (testing "includes skills section when read tool available"
    (let [skills [{:name "test-skill" :description "A test skill"
                   :file-path "/skills/test-skill/SKILL.md"
                   :base-dir "/skills/test-skill"
                   :source :user :disable-model-invocation false}]
          prompt (sys-prompt/build-system-prompt {:skills skills})]
      (is (str/includes? prompt "<available_skills>"))
      (is (str/includes? prompt "test-skill"))
      (is (str/includes? prompt "A test skill"))))

  (testing "excludes skills when read tool not available"
    (let [skills [{:name "test-skill" :description "A test skill"
                   :file-path "/skills/test-skill/SKILL.md"
                   :base-dir "/skills/test-skill"
                   :source :user :disable-model-invocation false}]
          prompt (sys-prompt/build-system-prompt
                  {:skills skills
                   :selected-tools ["bash" "edit" "write"]})]
      (is (not (str/includes? prompt "<available_skills>")))))

  (testing "excludes graph discovery section when eql_query is not available"
    (let [prompt (sys-prompt/build-system-prompt
                  {:selected-tools ["read" "bash" "edit" "write"]})]
      (is (not (str/includes? prompt "Capability graph (EQL discovery):")))))

  (testing "custom prompt replaces default"
    (let [prompt (sys-prompt/build-system-prompt
                  {:custom-prompt "Custom system prompt."})]
      (is (str/starts-with? prompt "Custom system prompt."))
      (is (not (str/includes? prompt "Available tools:")))))

  (testing "append prompt is added"
    (let [prompt (sys-prompt/build-system-prompt
                  {:append-prompt "Extra instructions here."})]
      (is (str/includes? prompt "Extra instructions here."))))

  (testing "custom prompt with skills and context"
    (let [skills [{:name "my-skill" :description "My skill"
                   :file-path "/s/SKILL.md" :base-dir "/s"
                   :source :user :disable-model-invocation false}]
          prompt (sys-prompt/build-system-prompt
                  {:custom-prompt "Custom base."
                   :context-files [{:path "/AGENTS.md" :content "Context text"}]
                   :skills skills})]
      (is (str/starts-with? prompt "Custom base."))
      (is (str/includes? prompt "Context text"))
      (is (str/includes? prompt "<available_skills>")))))

(deftest system-prompt-introspectable-test
  (testing "assembled prompt is a string that can be stored and queried"
    (let [prompt (sys-prompt/build-system-prompt
                  {:skills [{:name "s1" :description "Skill one"
                             :file-path "/s1/SKILL.md" :base-dir "/s1"
                             :source :user :disable-model-invocation false}]})]
      (is (string? prompt))
      (is (pos? (count prompt)))
      ;; The prompt contains the skills section for introspection
      (is (str/includes? prompt "s1")))))
