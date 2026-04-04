(ns extensions.lsp-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [extensions.lsp :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- mkdirs! [path]
  (.mkdirs (io/file path))
  path)

(defn- spit! [path content]
  (mkdirs! (.getParent (io/file path)))
  (spit path content)
  path)

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "psi-lsp-ext-test-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest nearest-git-root-test
  (testing "finds nearest ancestor containing .git from file path"
    (let [root (tmp-dir)
          _    (mkdirs! (str root "/.git"))
          file (spit! (str root "/src/foo/core.clj") "(ns foo.core)")]
      (is (= (.getCanonicalPath (io/file root))
             (sut/nearest-git-root file)))))

  (testing "returns nil when no ancestor contains .git"
    (let [root (tmp-dir)
          file (spit! (str root "/src/foo/core.clj") "(ns foo.core)")]
      (is (nil? (sut/nearest-git-root file))))))

(deftest workspace-root-test
  (testing "prefers nearest git root from changed path"
    (let [root     (tmp-dir)
          worktree (str root "/nested/worktree")
          _        (mkdirs! (str worktree "/.git"))
          file     (spit! (str worktree "/src/foo/core.clj") "(ns foo.core)")]
      (is (= (.getCanonicalPath (io/file worktree))
             (sut/workspace-root {:path file
                                  :worktree-path root
                                  :cwd "/fallback"}))))))

(deftest ensure-lsp-service-test
  (testing "ensures clojure-lsp service keyed by logical workspace identity"
    (let [root (tmp-dir)
          _    (mkdirs! (str root "/.git"))
          file (spit! (str root "/src/foo/core.clj") "(ns foo.core)")
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/lsp.clj"})]
      (sut/ensure-lsp-service! (assoc api :ensure-service (fn [spec]
                                                           ((:mutate api) 'psi.extension/ensure-service spec)))
                               {:path file
                                :worktree-path root})
      (is (= [{:key [:lsp (.getCanonicalPath (io/file root))]
               :type :subprocess
               :spec {:command ["clojure-lsp"]
                      :cwd (.getCanonicalPath (io/file root))
                      :transport :stdio
                      :env nil}}]
             (:services @state)))))

  (testing "caller config can override command while keeping workspace keying"
    (let [root (tmp-dir)
          _    (mkdirs! (str root "/.git"))
          file (spit! (str root "/src/foo/core.clj") "(ns foo.core)")
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/lsp.clj"})]
      (sut/ensure-lsp-service! (assoc api :ensure-service (fn [spec]
                                                           ((:mutate api) 'psi.extension/ensure-service spec)))
                               {:path file
                                :worktree-path root
                                :config {:command ["custom-lsp" "--stdio"]}})
      (is (= ["custom-lsp" "--stdio"]
             (get-in @state [:services 0 :spec :command]))))))

(deftest init-registers-post-tool-processor-test
  (testing "extension init registers write/edit post-tool processor"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/lsp.clj"})]
      (sut/init (assoc api :register-post-tool-processor (fn [spec]
                                                          ((:mutate api) 'psi.extension/register-post-tool-processor spec))))
      (is (= [{:name "lsp-diagnostics"
               :ext-path "/test/lsp.clj"
               :tools #{"write" "edit"}
               :timeout-ms 500}]
             (:post-tool-processors @state))))))
