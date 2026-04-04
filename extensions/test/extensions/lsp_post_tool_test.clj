(ns extensions.lsp-post-tool-test
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
        "psi-lsp-post-tool-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest lsp-post-tool-handler-syncs-workspace-test
  (testing "post-tool handler ensures service, initializes, and syncs changed file"
    (let [root (tmp-dir)
          _    (mkdirs! (str root "/.git"))
          path (spit! (str root "/src/foo/core.clj") "(ns foo.core)")
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/lsp.clj"})
          api' (assoc api
                      :ensure-service (fn [spec]
                                        ((:mutate api) 'psi.extension/ensure-service spec))
                      :service-request (fn [spec]
                                         ((:mutate api) 'psi.extension/service-request spec))
                      :service-notify (fn [spec]
                                        ((:mutate api) 'psi.extension/service-notify spec)))
          handler (sut/make-post-tool-handler api')
          contribution (handler {:tool-name "write"
                                 :tool-call-id "call-1"
                                 :tool-result {:content "ok"
                                               :is-error false
                                               :details nil
                                               :meta {:tool-name "write" :tool-call-id "call-1"}
                                               :effects [{:type "file/write"
                                                          :path path
                                                          :worktree-path root}]
                                               :enrichments []}
                                 :worktree-path root
                                 :config nil})]
      (is (= [{:key [:lsp (.getCanonicalPath (io/file root))]
               :type :subprocess
               :spec {:command ["clojure-lsp"]
                      :cwd (.getCanonicalPath (io/file root))
                      :transport :stdio
                      :env nil}}]
             (:services @state)))
      (is (= 1 (count (:service-requests @state))))
      (is (= 2 (count (:service-notifications @state))))
      (is (= "initialize"
             (get-in @state [:service-requests 0 :payload "method"])))
      (is (= "initialized"
             (get-in @state [:service-notifications 0 :payload "method"])))
      (is (= "textDocument/didOpen"
             (get-in @state [:service-notifications 1 :payload "method"])))
      (is (= "LSP workspace synced"
             (get-in contribution [:enrichments 0 :label]))))))
