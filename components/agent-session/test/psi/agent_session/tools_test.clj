(ns psi.agent-session.tools-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.tools :as tools]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context opts)
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest app-query-tool-schema-test
  (testing "app-query-tool schema is well-formed"
    (is (= "app-query-tool" (:name tools/app-query-tool)))
    (is (string? (:description tools/app-query-tool)))
    (is (string? (:parameters tools/app-query-tool)))))

(deftest app-query-tool-in-all-schemas-test
  (testing "app-query-tool appears in all-tool-schemas"
    (is (some #(= "app-query-tool" (:name %)) tools/all-tool-schemas))))

(deftest app-query-tool-not-in-all-tools-test
  (testing "app-query-tool is excluded from all-tools (requires context)"
    (is (not (some #(= "app-query-tool" (:name %)) tools/all-tools)))))

(deftest make-app-query-tool-valid-query-test
  (testing "valid EQL query returns EDN result"
    (let [query-fn (fn [q]
                     (is (= [:foo/bar] q))
                     {:foo/bar 42})
          tool     (tools/make-app-query-tool query-fn)
          result   ((:execute tool) {"query" "[:foo/bar]"})]
      (is (false? (:is-error result)))
      (is (= {:foo/bar 42} (read-string (:content result)))))))

(deftest make-app-query-tool-nested-query-test
  (testing "nested EQL query with joins"
    (let [query-fn (fn [_q]
                     {:psi.agent-session/stats {:total-messages 5}})
          tool     (tools/make-app-query-tool query-fn)
          result   ((:execute tool)
                    {"query" "[{:psi.agent-session/stats [:total-messages]}]"})]
      (is (false? (:is-error result)))
      (is (string? (:content result))))))

(deftest make-app-query-tool-invalid-edn-test
  (testing "invalid EDN returns error"
    (let [tool   (tools/make-app-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" "[not valid edn!!!"})]
      (is (true? (:is-error result)))
      (is (re-find #"EQL query error" (:content result))))))

(deftest make-app-query-tool-not-a-vector-test
  (testing "non-vector EDN returns error"
    (let [tool   (tools/make-app-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" ":just-a-keyword"})]
      (is (true? (:is-error result)))
      (is (re-find #"must be an EDN vector" (:content result))))))

(deftest make-app-query-tool-query-fn-throws-test
  (testing "query-fn exception is caught and returned as error"
    (let [tool   (tools/make-app-query-tool
                  (fn [_q] (throw (ex-info "resolver failed" {}))))
          result ((:execute tool) {"query" "[:foo]"})]
      (is (true? (:is-error result)))
      (is (re-find #"resolver failed" (:content result))))))

(deftest make-app-query-tool-truncation-test
  (testing "truncated app-query-tool output includes spill path and narrowing guidance"
    (let [tool   (tools/make-app-query-tool
                  (fn [_q] {:big (apply str (repeat 500 "x"))})
                  {:overrides {"app-query-tool" {:max-lines 1000 :max-bytes 80}}
                   :tool-call-id "test-call-id"})
          result ((:execute tool) {"query" "[:big]"})
          spill  (get-in result [:details :full-output-path])]
      (is (false? (:is-error result)))
      (is (re-find #"Output truncated" (:content result)))
      (is (re-find #"Use a narrower query" (:content result)))
      (is (string? spill))
      (is (.exists (io/file spill))))))

(deftest make-app-query-tool-read-eval-disabled-test
  (testing "read-eval is disabled for safety"
    (let [tool   (tools/make-app-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" "#=(+ 1 2)"})]
      (is (true? (:is-error result))))))

(deftest make-app-query-tool-sanitizes-recursive-session-ctx-test
  (testing "result maps containing :psi/agent-session-ctx do not overflow printer"
    (let [cyclic (let [l (java.util.ArrayList.)]
                   (.add l l)
                   l)
          tool   (tools/make-app-query-tool
                  (fn [_q]
                    {:psi.agent-session/api-errors
                     [{:psi.api-error/http-status 400
                       :psi/agent-session-ctx     {:cyclic cyclic}}]}))
          result ((:execute tool) {"query" "[:psi.agent-session/api-errors]"})]
      (is (false? (:is-error result)))
      (is (string? (:content result)))
      (is (re-find #"api-errors" (:content result)))
      (is (not (re-find #":psi/agent-session-ctx" (:content result)))))))

(deftest execute-tool-dispatch-test
  (testing "built-in dispatch handles read/bash/edit/write"
    (let [tmp (java.io.File/createTempFile "psi-dispatch-test" "")]
      (.delete tmp)
      (.mkdirs tmp)
      (try
        (spit (io/file tmp "a.txt") "alpha")
        (let [dir          (.getAbsolutePath tmp)
              read-result  (tools/execute-tool "read" {"path" "a.txt"} {:cwd dir})
              bash-result  (tools/execute-tool "bash" {"command" "cat a.txt"} {:cwd dir})
              edit-result  (tools/execute-tool "edit" {"path" "a.txt" "oldText" "alpha" "newText" "beta"} {:cwd dir})
              write-result (tools/execute-tool "write" {"path" "b.txt" "content" "gamma"} {:cwd dir})]
          (is (false? (:is-error read-result)))
          (is (= "alpha" (:content read-result)))
          (is (false? (:is-error bash-result)))
          (is (str/includes? (:content bash-result) "alpha"))
          (is (false? (:is-error edit-result)))
          (is (= "beta" (slurp (io/file dir "a.txt"))))
          (is (false? (:is-error write-result)))
          (is (= "gamma" (slurp (io/file dir "b.txt")))))
        (finally
          (doseq [file (reverse (file-seq tmp))]
            (.delete file))))))

  (testing "built-in dispatch rejects removed search tools"
    (doseq [tool-name ["ls" "find" "grep"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
                            (tools/execute-tool tool-name {})))))

  (testing "built-in dispatch does not handle app-query-tool"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
                          (tools/execute-tool "app-query-tool" {"query" "[:foo]"})))))

(deftest app-query-tool-integration-test
  (testing "app-query-tool works with a real session context"
    (let [[ctx _]            (create-session-context {:persist? false})
          sd                 (session/new-session-in! ctx nil {})
          session-id         (:session-id sd)
          tool               (tools/make-app-query-tool (fn [q] (session/query-in ctx session-id q)))
          exec               (:execute tool)
          result             (exec {"query" "[:psi.agent-session/phase]"})
          parsed             (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :idle (:psi.agent-session/phase parsed)))))

  (testing "app-query-tool returns session-id from live context"
    (let [[ctx _]            (create-session-context {:persist? false})
          sd                 (session/new-session-in! ctx nil {})
          session-id         (:session-id sd)
          tool               (tools/make-app-query-tool (fn [q] (session/query-in ctx session-id q)))
          exec               (:execute tool)
          result             (exec {"query" "[:psi.agent-session/session-id]"})
          parsed             (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= session-id (:psi.agent-session/session-id parsed))))))

;;; CWD support tests

(defn- with-temp-dir
  "Create a temp dir, run f with its path, clean up."
  [f]
  (let [tmp (java.io.File/createTempFile "psi-tools-test" "")]
    (.delete tmp)
    (.mkdirs tmp)
    (try
      (f (.getAbsolutePath tmp))
      (finally
        (doseq [file (reverse (file-seq tmp))]
          (.delete file))))))

(deftest execute-read-cwd-test
  (testing "read resolves relative path against cwd"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "hello.txt") "world")
        (let [result (tools/execute-read {"path" "hello.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (= "world" (:content result)))))))

  (testing "read with absolute path ignores cwd"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "abs.txt"))]
          (spit abs-path "absolute")
          (let [result (tools/execute-read {"path" abs-path} {:cwd "/nonexistent"})]
            (is (false? (:is-error result)))
            (is (= "absolute" (:content result))))))))

  (testing "read without cwd works as before"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "plain.txt"))]
          (spit abs-path "plain")
          (let [result (tools/execute-read {"path" abs-path})]
            (is (false? (:is-error result)))
            (is (= "plain" (:content result)))))))))

(deftest execute-bash-cwd-test
  (testing "bash runs in cwd when provided"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "marker.txt") "found")
        (let [result (tools/execute-bash {"command" "cat marker.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "found"))))))

  (testing "bash without cwd works as before"
    (let [result (tools/execute-bash {"command" "echo hello"})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "hello")))))

(deftest execute-edit-cwd-test
  (testing "edit resolves relative path against cwd"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "edit-me.txt") "old text here")
        (let [result (tools/execute-edit
                      {"path" "edit-me.txt" "oldText" "old text" "newText" "new text"}
                      {:cwd dir})]
          (is (false? (:is-error result)))
          (is (re-find #"Successfully replaced text" (:content result)))
          (is (string? (get-in result [:details :diff])))
          (is (pos-int? (get-in result [:details :first-changed-line])))
          (is (= "new text here" (slurp (io/file dir "edit-me.txt"))))))))

  (testing "edit fuzzy fallback handles smart quotes and trailing whitespace"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "fuzzy.txt") "hello ‘world’   \nnext")
        (let [result (tools/execute-edit
                      {"path" "fuzzy.txt" "oldText" "hello 'world'" "newText" "hello everyone"}
                      {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (slurp (io/file dir "fuzzy.txt")) "hello everyone"))))))

  (testing "edit preserves UTF-8 BOM"
    (with-temp-dir
      (fn [dir]
        (let [p (io/file dir "bom.txt")]
          (spit p (str "\uFEFF" "before"))
          (let [result (tools/execute-edit
                        {"path" "bom.txt" "oldText" "before" "newText" "after"}
                        {:cwd dir})
                updated (slurp p)]
            (is (false? (:is-error result)))
            (is (str/starts-with? updated "\uFEFF"))
            (is (str/includes? updated "after")))))))

  (testing "edit without cwd works as before"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "edit-abs.txt"))]
          (spit abs-path "before")
          (let [result (tools/execute-edit
                        {"path" abs-path "oldText" "before" "newText" "after"})]
            (is (false? (:is-error result)))
            (is (= "after" (slurp abs-path)))))))))

(deftest execute-write-cwd-test
  (testing "write resolves relative path against cwd"
    (with-temp-dir
      (fn [dir]
        (let [result (tools/execute-write
                      {"path" "sub/output.txt" "content" "written"}
                      {:cwd dir})]
          (is (false? (:is-error result)))
          (is (re-find #"Successfully wrote 7 bytes" (:content result)))
          (is (= "written" (slurp (io/file dir "sub" "output.txt"))))))))

  (testing "write without cwd works as before"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "write-abs.txt"))
              result   (tools/execute-write
                        {"path" abs-path "content" "abs-written"})]
          (is (false? (:is-error result)))
          (is (re-find #"Successfully wrote" (:content result)))
          (is (= "abs-written" (slurp abs-path))))))))

(deftest make-tools-with-cwd-test
  (testing "returns four tools scoped to cwd"
    (with-temp-dir
      (fn [dir]
        (let [tools-vec (tools/make-tools-with-cwd dir)]
          (is (= 4 (count tools-vec)))
          (is (= #{"read" "bash" "edit" "write"}
                 (into #{} (map :name) tools-vec)))))))

  (testing "tools execute in the scoped directory"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "scoped.txt") "scoped-content")
        (let [tools-vec  (tools/make-tools-with-cwd dir)
              read-tool  (first (filter #(= "read" (:name %)) tools-vec))
              bash-tool  (first (filter #(= "bash" (:name %)) tools-vec))
              write-tool (first (filter #(= "write" (:name %)) tools-vec))
              edit-tool  (first (filter #(= "edit" (:name %)) tools-vec))]
          ;; read
          (let [r ((:execute read-tool) {"path" "scoped.txt"})]
            (is (false? (:is-error r)))
            (is (= "scoped-content" (:content r))))
          ;; bash
          (let [r ((:execute bash-tool) {"command" "cat scoped.txt"})]
            (is (false? (:is-error r)))
            (is (str/includes? (:content r) "scoped-content")))
          ;; write
          (let [r ((:execute write-tool) {"path" "new.txt" "content" "new-content"})]
            (is (false? (:is-error r)))
            (is (= "new-content" (slurp (io/file dir "new.txt")))))
          ;; edit
          (let [r ((:execute edit-tool) {"path" "new.txt" "oldText" "new-content" "newText" "edited"})]
            (is (false? (:is-error r)))
            (is (= "edited" (slurp (io/file dir "new.txt"))))))))))

(deftest make-read-only-tools-with-cwd-test
  (testing "returns read-only tools in canonical order"
    (with-temp-dir
      (fn [dir]
        (let [tools-vec (tools/make-read-only-tools-with-cwd dir)]
          (is (= ["read"] (mapv :name tools-vec)))))))

  (testing "read-only tools execute in scoped cwd"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "notes.txt") "hello alpha")
        (let [tools-vec (tools/make-read-only-tools-with-cwd dir)
              read-tool (first (filter #(= "read" (:name %)) tools-vec))]
          (is (str/includes? (:content ((:execute read-tool) {"path" "notes.txt"})) "hello alpha")))))))
