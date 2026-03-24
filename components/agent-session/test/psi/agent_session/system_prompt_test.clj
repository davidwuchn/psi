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
      (is (str/includes? prompt "app-query-tool: Execute an EQL query against the live session graph."))
      (is (str/includes? prompt "Guidelines:"))
      (is (str/includes? prompt "Capability graph (EQL discovery):"))
      (is (str/includes? prompt ":psi.graph/resolver-syms"))
      (is (str/includes? prompt "[:psi.graph/root-seeds]"))
      (is (str/includes? prompt "[:psi.graph/root-queryable-attrs]"))
      (is (str/includes? prompt ":psi.agent-session/usage-input"))
      (is (str/includes? prompt "/test/dir"))))

  (testing "includes current capabilities when graph-capabilities are provided"
    (let [prompt (sys-prompt/build-system-prompt
                  {:graph-capabilities [{:domain :agent-session
                                         :operation-count 10
                                         :resolver-count 8
                                         :mutation-count 2}
                                        {:domain :ai
                                         :operation-count 4
                                         :resolver-count 4
                                         :mutation-count 0}]})]
      (is (str/includes? prompt "Current capabilities (from :psi.graph/capabilities):"))
      (is (str/includes? prompt "- agent-session (ops=10, resolvers=8, mutations=2)"))
      (is (str/includes? prompt "- ai (ops=4, resolvers=4, mutations=0)"))))

  (testing "includes session creation time, not wall clock"
    (let [instant (java.time.Instant/parse "2026-01-15T10:30:00Z")
          prompt  (sys-prompt/build-system-prompt {:session-instant instant})]
      (is (str/includes? prompt "Current date and time:"))
      (is (str/includes? prompt "January 15, 2026"))))

  (testing "is deterministic given the same inputs"
    (let [instant (java.time.Instant/parse "2026-01-15T10:30:00Z")
          opts    {:cwd "/test" :session-instant instant}
          p1      (sys-prompt/build-system-prompt opts)
          p2      (sys-prompt/build-system-prompt opts)]
      (is (= p1 p2))))

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

  (testing "excludes graph discovery section when app-query-tool is not available"
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

  (testing "includes explicit worktree directory metadata"
    (let [prompt (sys-prompt/build-system-prompt
                  {:cwd "/tmp/worktree-demo"})]
      (is (str/includes? prompt "Current working directory: /tmp/worktree-demo"))
      (is (str/includes? prompt "Current worktree directory: /tmp/worktree-demo"))))

  (testing "includes prompt contributions when provided"
    (let [prompt (sys-prompt/build-system-prompt
                  {:prompt-contributions [{:id "x"
                                           :ext-path "/ext/a"
                                           :section "Hints"
                                           :content "Use stable IDs"
                                           :priority 100
                                           :enabled true}]})]
      (is (str/includes? prompt "# Extension Prompt Contributions"))
      (is (str/includes? prompt "<prompt_contribution"))
      (is (str/includes? prompt "Use stable IDs"))))

  (testing "runtime metadata is emitted after extension prompt contributions"
    (let [prompt (sys-prompt/build-system-prompt
                  {:cwd "/tmp/worktree-demo"
                   :prompt-contributions [{:id "x"
                                           :ext-path "/ext/a"
                                           :section "Hints"
                                           :content "Use stable IDs"
                                           :priority 100
                                           :enabled true}]})
          contrib-idx (.indexOf prompt "# Extension Prompt Contributions")
          time-idx    (.indexOf prompt "Current date and time:")]
      (is (pos? contrib-idx))
      (is (> time-idx contrib-idx))))

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

(deftest system-prompt-blocks-test
  ;; system-prompt-blocks returns a single cacheable block
  ;; since the entire prompt is now stable (time + cwd frozen)
  (testing "system-prompt-blocks"
    (testing "returns single cached block when caching enabled"
      (let [blocks (sys-prompt/system-prompt-blocks "test prompt" true)]
        (is (= 1 (count blocks)))
        (is (= "test prompt" (:text (first blocks))))
        (is (= {:type :ephemeral} (:cache-control (first blocks))))))
    (testing "returns single uncached block when caching disabled"
      (let [blocks (sys-prompt/system-prompt-blocks "test prompt" false)]
        (is (= 1 (count blocks)))
        (is (= "test prompt" (:text (first blocks))))
        (is (nil? (:cache-control (first blocks))))))
    (testing "returns nil for empty prompt"
      (is (nil? (sys-prompt/system-prompt-blocks "" false)))
      (is (nil? (sys-prompt/system-prompt-blocks nil false))))))

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
