(ns extensions.lsp
  "LSP integration extension.

   Owns:
   - default clojure-lsp runtime config
   - nearest-`.git` workspace root detection
   - logical workspace key derivation for service reuse
   - extension registration for write/edit post-tool processing
   - extension commands for LSP status/restart surfaces"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.service-protocol :as service-protocol]))

(def ^:private default-lsp-config
  {:command ["clojure-lsp"]
   :transport :stdio
   :diagnostics-timeout-ms 500
   :startup-timeout-ms 2000
   :sync-timeout-ms 500})

(defonce state
  (atom {:initialized-workspaces #{}
         :documents {}}))

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
  [api {:keys [config] :as input}]
  (let [root (workspace-root input)]
    (when (str/blank? root)
      (throw (ex-info "Unable to resolve LSP workspace root"
                      {:input input})))
    ((:ensure-service api)
     {:key (workspace-key root)
      :type :subprocess
      :spec (service-spec root config)})))

(defn- path-in-workspace?
  [workspace-root path]
  (let [root (str workspace-root)
        p    (str path)]
    (or (= root p)
        (str/starts-with? p (str root "/")))))

(defn stop-lsp-service!
  [api {:keys [workspace-root]}]
  ((:stop-service api) (workspace-key workspace-root))
  (swap! state update :initialized-workspaces disj workspace-root)
  (swap! state update :documents
         (fn [docs]
           (into {}
                 (remove (fn [[path _]]
                           (path-in-workspace? workspace-root path))
                         docs))))
  workspace-root)

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

(defn workspace-initialized?
  [workspace-root]
  (contains? (:initialized-workspaces @state) workspace-root))

(defn mark-workspace-initialized!
  [workspace-root]
  (swap! state update :initialized-workspaces conj workspace-root)
  workspace-root)

(defn ensure-lsp-initialized!
  [api {:keys [workspace-root startup-timeout-ms] :as _opts}]
  (when-not (workspace-initialized? workspace-root)
    (jsonrpc-request! api {:workspace-root workspace-root
                           :id "initialize"
                           :method "initialize"
                           :params (initialize-request {:workspace-root workspace-root})
                           :timeout-ms startup-timeout-ms})
    (jsonrpc-notify! api {:workspace-root workspace-root
                          :method "initialized"
                          :params {}})
    (mark-workspace-initialized! workspace-root)))

(defn- file-uri [path]
  (str "file://" (.getCanonicalPath (io/file path))))

(defn document-diagnostics-request
  [path]
  {"textDocument" {"uri" (file-uri path)}})

(defn diagnostics-content-append
  [diagnostics-by-path]
  (let [lines (->> diagnostics-by-path
                   (mapcat (fn [[path diagnostics]]
                             (when (seq diagnostics)
                               (cons (str "LSP diagnostics for " path ":")
                                     (map (fn [d]
                                            (str "- "
                                                 (or (get d "message") "<no message>")
                                                 (when-let [sev (get d "severity")]
                                                   (str " (severity " sev ")"))))
                                          diagnostics)))))
                   vec)]
    (when (seq lines)
      (str "\n" (str/join "\n" lines)))))

(defn diagnostics-enrichments
  [diagnostics-by-path]
  (->> diagnostics-by-path
       (keep (fn [[path diagnostics]]
               (when (seq diagnostics)
                 {:type "lsp/diagnostics"
                  :source "extensions.lsp"
                  :label (str "LSP diagnostics: " path)
                  :data {:path path
                         :diagnostics diagnostics}})))
       vec))

(defn request-diagnostics!
  [api {:keys [workspace-root paths diagnostics-timeout-ms]}]
  (reduce (fn [acc path]
            (let [response (jsonrpc-request! api {:workspace-root workspace-root
                                                  :id (str "diagnostics:" path)
                                                  :method "textDocument/diagnostic"
                                                  :params (document-diagnostics-request path)
                                                  :timeout-ms diagnostics-timeout-ms})
                  result   (or (service-protocol/await-jsonrpc-result
                                 {:response (:psi.extension.service/response response)})
                               (service-protocol/await-jsonrpc-result response)
                               [])
                  items    (cond
                             (vector? result) result
                             (map? result) (vec (or (get result "items") []))
                             :else [])]
              (assoc acc path items)))
          {}
          paths))

(defn- changed-paths
  [{:keys [tool-result]}]
  (->> (:effects tool-result)
       (keep :path)
       distinct
       vec))

(defn- read-text [path]
  (slurp (io/file path)))

(defn document-state
  [path]
  (get-in @state [:documents path]))

(defn next-document-version
  [path]
  (inc (long (or (:version (document-state path)) 0))))

(defn document-open?
  [path]
  (true? (:open? (document-state path))))

(defn record-document-sync!
  [path version text]
  (swap! state assoc-in [:documents path] {:open? true
                                           :version version
                                           :text text})
  {:path path :version version})

(defn sync-document!
  [api {:keys [workspace-root path text]}]
  (let [first-open? (not (document-open? path))
        version     (next-document-version path)
        method      (if first-open?
                      "textDocument/didOpen"
                      "textDocument/didChange")
        params      (if first-open?
                      {"textDocument" {"uri" (file-uri path)
                                        "languageId" "clojure"
                                        "version" version
                                        "text" text}}
                      {"textDocument" {"uri" (file-uri path)
                                        "version" version}
                       "contentChanges" [{"text" text}]})]
    (jsonrpc-notify! api {:workspace-root workspace-root
                          :method method
                          :params params})
    (record-document-sync! path version text)
    {:path path :version version :first-open? first-open? :method method}))

(defn sync-tool-result!
  [api {:keys [worktree-path config] :as input}]
  (let [cfg   (normalize-config config)
        paths (changed-paths input)
        root  (workspace-root {:worktree-path worktree-path
                               :cwd worktree-path
                               :path (first paths)})
        syncs (atom [])]
    (ensure-lsp-service! api {:worktree-path worktree-path
                              :path (first paths)
                              :config cfg})
    (ensure-lsp-initialized! api {:workspace-root root
                                  :startup-timeout-ms (:startup-timeout-ms cfg)})
    (doseq [path paths]
      (swap! syncs conj (sync-document! api {:workspace-root root
                                             :path path
                                             :text (read-text path)})))
    {:workspace-root root
     :changed-paths paths
     :document-syncs @syncs
     :diagnostics-by-path (request-diagnostics! api {:workspace-root root
                                                     :paths paths
                                                     :diagnostics-timeout-ms (:diagnostics-timeout-ms cfg)})}))

(defn- diagnostic-path-count
  [diagnostics-by-path]
  (count (filter (comp seq val) diagnostics-by-path)))

(defn- diagnostics-failure-enrichment
  [message data]
  {:type "lsp/diagnostics-error"
   :source "extensions.lsp"
   :label message
   :data data})

(defn make-post-tool-handler
  [api]
  (fn [input]
    (when (seq (changed-paths input))
      (try
        (let [{:keys [workspace-root changed-paths diagnostics-by-path] :as sync}
              (sync-tool-result! api input)
              diagnostic-enrichments (diagnostics-enrichments diagnostics-by-path)
              diagnostic-append      (diagnostics-content-append diagnostics-by-path)]
          {:content/append diagnostic-append
           :details/merge {:lsp {:workspace-root workspace-root
                                 :synced-paths changed-paths
                                 :diagnostic-path-count (diagnostic-path-count diagnostics-by-path)
                                 :document-syncs (:document-syncs sync)}}
           :enrichments (into [{:type "lsp/workspace-sync"
                                :source "extensions.lsp"
                                :label "LSP workspace synced"
                                :data sync}]
                              diagnostic-enrichments)})
        (catch Throwable t
          {:details/merge {:lsp {:error (ex-message t)}}
           :enrichments [(diagnostics-failure-enrichment
                          "LSP diagnostics unavailable"
                          {:error (ex-message t)
                           :tool-call-id (:tool-call-id input)
                           :tool-name (:tool-name input)})]})))))

(defn workspace-status-lines
  [api {:keys [worktree-path path cwd]}]
  (let [root      (workspace-root {:worktree-path worktree-path :path path :cwd cwd})
        services  (or ((:list-services api)) [])
        service   (some #(when (= (workspace-key root) (:psi.service/key %)) %) services)
        docs      (->> (:documents @state)
                       (filter (fn [[doc-path _]]
                                 (path-in-workspace? root doc-path)))
                       (into (sorted-map)))
        init?     (workspace-initialized? root)]
    (cond-> [(str "LSP workspace: " root)
             (str "Initialized: " init?)
             (str "Service status: " (or (:psi.service/status service) :missing))
             (str "Tracked documents: " (count docs))]
      (seq docs)
      (into (map (fn [[doc-path {:keys [version open?]}]]
                   (str "- " doc-path " (version " version ", open=" open? ")"))
                 docs)))))

(defn current-cwd
  [api]
  (if-let [cwd-fn (:cwd-fn api)]
    (cwd-fn)
    (System/getProperty "user.dir")))

(defn command-target
  [api args]
  (let [arg  (some-> args str str/trim not-empty)
        path (when arg (.getPath (io/file arg)))
        f    (when path (io/file path))
        cwd  (cond
               (nil? path) (current-cwd api)
               (.isDirectory f) (.getPath f)
               :else (some-> f .getParentFile .getPath))]
    {:cwd  (or cwd (current-cwd api))
     :path path}))

(defn close-document!
  [api {:keys [workspace-root path]}]
  (when (document-open? path)
    (when-let [notify! (:service-notify api)]
      (jsonrpc-notify! api {:workspace-root workspace-root
                            :method "textDocument/didClose"
                            :params {"textDocument" {"uri" (file-uri path)}}}))
    (swap! state update :documents dissoc path)
    {:path path :closed? true}))

(defn close-workspace-documents!
  [api workspace-root]
  (->> (:documents @state)
       (keep (fn [[path _]]
               (when (path-in-workspace? workspace-root path)
                 (close-document! api {:workspace-root workspace-root :path path}))))
       vec))

(defn print-workspace-status!
  [api opts]
  (doseq [line (workspace-status-lines api opts)]
    (println line))
  nil)

(defn restart-workspace!
  [api {:keys [worktree-path path cwd config]}]
  (let [root (workspace-root {:worktree-path worktree-path :path path :cwd cwd})]
    (close-workspace-documents! api root)
    (stop-lsp-service! api {:workspace-root root})
    (ensure-lsp-service! api {:worktree-path worktree-path :path path :cwd cwd :config config})
    (println (str "Restarted LSP workspace " root))
    root))

(defn lsp-post-tool-handler
  "Backward-compatible handler constructor entrypoint used in tests."
  [input]
  ((make-post-tool-handler nil) input))

(defn init [api]
  ((:register-post-tool-processor api)
   {:name "lsp-diagnostics"
    :match {:tools #{"write" "edit"}}
    :timeout-ms (:diagnostics-timeout-ms (default-config))
    :handler (make-post-tool-handler api)})
  ((:register-command api) "lsp-status"
   {:description "Show LSP workspace status for the current worktree or a provided path"
    :handler (fn [args]
               (print-workspace-status! api (command-target api args)))})
  ((:register-command api) "lsp-restart"
   {:description "Restart LSP workspace service for the current worktree or a provided path"
    :handler (fn [args]
               (restart-workspace! api (command-target api args)))})
  (when-let [ui (:ui api)]
    ((:notify ui) "lsp loaded" :info)))
