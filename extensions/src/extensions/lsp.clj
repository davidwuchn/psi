(ns extensions.lsp
  "LSP integration extension.

   Owns:
   - default clojure-lsp runtime config
   - nearest-`.git` workspace root detection
   - logical workspace key derivation for service reuse
   - extension registration for write/edit post-tool processing"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private default-lsp-config
  {:command ["clojure-lsp"]
   :transport :stdio
   :diagnostics-timeout-ms 500
   :startup-timeout-ms 2000})

(defn default-config []
  default-lsp-config)

(defn normalize-config
  [config]
  (merge default-lsp-config (or config {})))

(defn- file-exists? [^java.io.File f]
  (and f (.exists f)))

(defn nearest-git-root
  [path]
  (when (some? path)
    (let [start-file (io/file (str path))
          start-dir  (if (.isDirectory start-file)
                       start-file
                       (.getParentFile start-file))]
      (loop [dir start-dir]
        (when dir
          (let [git-entry (io/file dir ".git")]
            (if (file-exists? git-entry)
              (.getCanonicalPath dir)
              (recur (.getParentFile dir)))))))))

(defn workspace-root
  [{:keys [path worktree-path cwd]}]
  (or (nearest-git-root path)
      (nearest-git-root worktree-path)
      (some-> worktree-path io/file .getCanonicalPath)
      (some-> cwd io/file .getCanonicalPath)))

(defn workspace-key
  [workspace-root]
  [:lsp workspace-root])

(defn service-spec
  [workspace-root config]
  (let [{:keys [command transport env]} (normalize-config config)]
    {:command command
     :cwd workspace-root
     :transport transport
     :env env}))

(defn ensure-lsp-service!
  [api {:keys [config ext-path] :as input}]
  (let [root (workspace-root input)]
    (when (str/blank? root)
      (throw (ex-info "Unable to resolve LSP workspace root"
                      {:input input})))
    ((:ensure-service api)
     {:key (workspace-key root)
      :type :subprocess
      :spec (service-spec root config)})))

(defn jsonrpc-request!
  [api {:keys [workspace-root id method params timeout-ms]}]
  ((:service-request api)
   {:key (workspace-key workspace-root)
    :request-id id
    :payload {"jsonrpc" "2.0"
              "id" id
              "method" method
              "params" params}
    :timeout-ms timeout-ms}))

(defn jsonrpc-notify!
  [api {:keys [workspace-root method params]}]
  ((:service-notify api)
   {:key (workspace-key workspace-root)
    :payload {"jsonrpc" "2.0"
              "method" method
              "params" params}}))

(defn initialize-request
  [{:keys [workspace-root]}]
  {"processId" nil
   "clientInfo" {"name" "psi"
                  "version" "0"}
   "rootUri" (str "file://" workspace-root)
   "capabilities" {"workspace" {}
                     "textDocument" {"publishDiagnostics" {"relatedInformation" true}}}})

(defn ensure-lsp-initialized!
  [api {:keys [workspace-root startup-timeout-ms] :as _opts}]
  (jsonrpc-request! api {:workspace-root workspace-root
                         :id "initialize"
                         :method "initialize"
                         :params (initialize-request {:workspace-root workspace-root})
                         :timeout-ms startup-timeout-ms})
  (jsonrpc-notify! api {:workspace-root workspace-root
                        :method "initialized"
                        :params {}}))

(defn- file-uri [path]
  (str "file://" (.getCanonicalPath (io/file path))))

(defn- changed-paths
  [{:keys [tool-result]}]
  (->> (:effects tool-result)
       (keep :path)
       distinct
       vec))

(defn- read-text [path]
  (slurp (io/file path)))

(defn did-open-or-change!
  [api {:keys [workspace-root path version text first-open?]}]
  (let [method (if first-open?
                 "textDocument/didOpen"
                 "textDocument/didChange")
        params (if first-open?
                 {"textDocument" {"uri" (file-uri path)
                                   "languageId" "clojure"
                                   "version" version
                                   "text" text}}
                 {"textDocument" {"uri" (file-uri path)
                                   "version" version}
                  "contentChanges" [{"text" text}]})]
    (jsonrpc-notify! api {:workspace-root workspace-root
                          :method method
                          :params params})))

(defn sync-tool-result!
  [api {:keys [tool-result worktree-path config] :as input}]
  (let [root (workspace-root {:worktree-path worktree-path
                              :cwd worktree-path
                              :path (first (changed-paths input))})]
    (ensure-lsp-service! api {:worktree-path worktree-path
                              :path (first (changed-paths input))
                              :config config})
    (ensure-lsp-initialized! api {:workspace-root root
                                  :startup-timeout-ms (:startup-timeout-ms (normalize-config config))})
    (doseq [path (changed-paths input)]
      (did-open-or-change! api {:workspace-root root
                                :path path
                                :version 1
                                :text (read-text path)
                                :first-open? true}))
    {:workspace-root root
     :changed-paths (changed-paths input)}))

(defn make-post-tool-handler
  [api]
  (fn [input]
    (when (seq (changed-paths input))
      (let [{:keys [workspace-root changed-paths] :as sync}
            (sync-tool-result! api input)]
        {:details/merge {:lsp {:workspace-root workspace-root
                               :synced-paths changed-paths}}
         :enrichments [{:type "lsp/workspace-sync"
                        :source "extensions.lsp"
                        :label "LSP workspace synced"
                        :data sync}]}))))

(defn lsp-post-tool-handler
  "Backward-compatible handler constructor entrypoint used in tests."
  [input]
  ((make-post-tool-handler nil) input))

(defn init [api]
  ((:register-post-tool-processor api)
   {:name "lsp-diagnostics"
    :match {:tools #{"write" "edit"}}
    :timeout-ms (:diagnostics-timeout-ms (default-config))
    :handler (make-post-tool-handler api)}))
