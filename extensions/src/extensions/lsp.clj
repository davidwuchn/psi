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

(defn lsp-post-tool-handler
  "Placeholder v1 handler.

   Current increment only establishes extension ownership and workspace/service
   routing. Diagnostics sync/request flow lands next."
  [_input]
  nil)

(defn init [api]
  ((:register-post-tool-processor api)
   {:name "lsp-diagnostics"
    :match {:tools #{"write" "edit"}}
    :timeout-ms (:diagnostics-timeout-ms (default-config))
    :handler lsp-post-tool-handler}))
