(ns psi.agent-session.extension-installs
  "Extension install manifest reading, effective-state projection, diagnostics,
   and conservative apply-state tracking.

   Slice one intentionally separates manifest/config introspection from actual
   dependency realization. The runtime can therefore expose canonical install
   state now while keeping activation conservative until tools.deps-backed
   realization lands fully."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [psi.agent-session.session-state :as ss]))

(defn user-manifest-file
  []
  (io/file (System/getProperty "user.home") ".psi" "agent" "extensions.edn"))

(defn project-manifest-file
  [cwd]
  (io/file cwd ".psi" "extensions.edn"))

(defn- base-manifest
  []
  {:deps {}})

(defn- diagnostic
  [{:keys [severity category message libs init-var scopes source data]}]
  {:severity severity
   :category category
   :message message
   :libs (vec (or libs []))
   :init-var init-var
   :scopes (vec (or scopes []))
   :source source
   :data (or data {})})

(defn- read-manifest-file
  [file source]
  (if-not (.exists ^java.io.File file)
    {:manifest (base-manifest)
     :diagnostics []}
    (try
      (let [value (edn/read-string (slurp file))]
        (cond
          (not (map? value))
          {:manifest (base-manifest)
           :diagnostics [(diagnostic {:severity :error
                                      :category :malformed-entry
                                      :message (str "Extension manifest must be a map: " (.getAbsolutePath ^java.io.File file))
                                      :source source
                                      :data {:path (.getAbsolutePath ^java.io.File file)
                                             :problem :manifest-not-map}})]}

          (not (map? (:deps value)))
          {:manifest (base-manifest)
           :diagnostics [(diagnostic {:severity :error
                                      :category :malformed-entry
                                      :message (str "Extension manifest :deps must be a map: " (.getAbsolutePath ^java.io.File file))
                                      :source source
                                      :data {:path (.getAbsolutePath ^java.io.File file)
                                             :problem :deps-not-map}})]}

          :else
          {:manifest (update value :deps #(or % {}))
           :diagnostics []}))
      (catch Exception e
        {:manifest (base-manifest)
         :diagnostics [(diagnostic {:severity :error
                                    :category :malformed-entry
                                    :message (str "Failed to read extension manifest: " (ex-message e))
                                    :source source
                                    :data {:path (.getAbsolutePath ^java.io.File file)}})]}))))

(defn- psi-meta-keys
  [dep]
  (->> (keys dep)
       (filter #(and (keyword? %) (= "psi" (namespace %))))
       set))

(defn- coordinate-family
  [dep]
  (let [families (cond-> #{}
                   (contains? dep :local/root) (conj :local)
                   (or (contains? dep :git/url)
                       (contains? dep :git/sha)) (conj :git)
                   (contains? dep :mvn/version) (conj :mvn))]
    (when (= 1 (count families))
      (first families))))

(defn- extension-dep?
  [dep]
  (and (map? dep) (contains? dep :psi/init)))

(defn- malformed-extension-candidate?
  [dep]
  (let [psi-keys (psi-meta-keys dep)]
    (and (seq psi-keys)
         (not (contains? psi-keys :psi/init)))))

(defn- manifest-source
  [scope]
  (keyword (str (name scope) "-manifest")))

(defn- entry-diagnostics
  [scope lib dep]
  (let [coord-family (when (map? dep) (coordinate-family dep))
        extension?   (extension-dep? dep)
        malformed?   (and (map? dep) (malformed-extension-candidate? dep))]
    (vec
     (concat
      (when-not (map? dep)
        [(diagnostic {:severity :error
                      :category :malformed-entry
                      :message (str "Dependency entry must be a map for " lib)
                      :libs [lib]
                      :scopes [scope]
                      :source (manifest-source scope)})])
      (when malformed?
        [(diagnostic {:severity :error
                      :category :malformed-entry
                      :message (str "Extension metadata requires :psi/init for " lib)
                      :libs [lib]
                      :scopes [scope]
                      :source (manifest-source scope)})])
      (when (and extension? (nil? coord-family))
        [(diagnostic {:severity :error
                      :category :malformed-entry
                      :message (str "Extension dependency must declare exactly one coordinate family for " lib)
                      :libs [lib]
                      :init-var (:psi/init dep)
                      :scopes [scope]
                      :source (manifest-source scope)
                      :data {:dep dep}})])
      (when (and extension? (= coord-family :git) (not (contains? dep :git/sha)))
        [(diagnostic {:severity :error
                      :category :missing-git-sha
                      :message (str "Git extension dependency requires :git/sha for " lib)
                      :libs [lib]
                      :init-var (:psi/init dep)
                      :scopes [scope]
                      :source (manifest-source scope)})])
      (when (and (= scope :project) (= coord-family :local))
        [(diagnostic {:severity :warning
                      :category :project-local-root-nonreproducible
                      :message (str "Project extension dependency uses non-reproducible :local/root for " lib)
                      :libs [lib]
                      :init-var (:psi/init dep)
                      :scopes [scope]
                      :source :project-manifest})])))))

(defn- normalize-entry
  [lib scope dep source-manifests overridden?]
  [lib {:dep dep
        :extension? (extension-dep? dep)
        :support-dep? (not (extension-dep? dep))
        :enabled? (boolean (if (extension-dep? dep)
                             (get dep :psi/enabled true)
                             false))
        :scope scope
        :source-manifests (vec source-manifests)
        :overridden? overridden?
        :effective? true
        :status (if (extension-dep? dep) :configured :not-applicable)
        :load-error nil
        :init-var (when (extension-dep? dep) (:psi/init dep))}])

(defn- duplicate-init-diagnostics
  [entries-by-lib]
  (->> entries-by-lib
       (keep (fn [[lib {:keys [extension? init-var scope]}]]
               (when (and extension? init-var)
                 [init-var {:lib lib :scope scope}])))
       (group-by first)
       (keep (fn [[init-var claims]]
               (let [claimants (mapv second claims)
                     libs      (mapv :lib claimants)]
                 (when (> (count (set libs)) 1)
                   (diagnostic {:severity :error
                                :category :duplicate-init
                                :message (str "Duplicate :psi/init claim for " init-var)
                                :libs libs
                                :init-var init-var
                                :scopes (mapv :scope claimants)
                                :source :effective
                                :data {:claims claimants}})))))
       vec))

(defn compute-install-state
  [cwd]
  (let [{user-manifest :manifest user-diags :diagnostics}
        (read-manifest-file (user-manifest-file) :user-manifest)
        {project-manifest :manifest project-diags :diagnostics}
        (read-manifest-file (project-manifest-file cwd) :project-manifest)
        user-deps      (:deps user-manifest)
        project-deps   (:deps project-manifest)
        all-libs       (set/union (set (keys user-deps)) (set (keys project-deps)))
        entries-by-lib (into {}
                             (map (fn [lib]
                                    (if (contains? project-deps lib)
                                      (normalize-entry lib
                                                       :project
                                                       (get project-deps lib)
                                                       (cond-> [:project]
                                                         (contains? user-deps lib) (conj :user))
                                                       (boolean (contains? user-deps lib)))
                                      (normalize-entry lib
                                                       :user
                                                       (get user-deps lib)
                                                       [:user]
                                                       false))))
                             (sort all-libs))
        effective-raw-deps (into {}
                                 (map (fn [[lib {:keys [dep]}]]
                                        [lib dep]))
                                 entries-by-lib)
        effective-extension-deps (into {}
                                       (keep (fn [[lib {:keys [dep extension?]}]]
                                               (when extension?
                                                 [lib dep])))
                                       entries-by-lib)
        entry-diags (vec (concat (mapcat (fn [[lib dep]] (entry-diagnostics :user lib dep)) user-deps)
                                 (mapcat (fn [[lib dep]] (entry-diagnostics :project lib dep)) project-deps)))
        dup-diags   (duplicate-init-diagnostics entries-by-lib)
        diagnostics (vec (concat user-diags project-diags entry-diags dup-diags))]
    {:psi.extensions/user-manifest user-manifest
     :psi.extensions/project-manifest project-manifest
     :psi.extensions/effective {:raw-deps effective-raw-deps
                                :extension-deps effective-extension-deps
                                :entries-by-lib entries-by-lib
                                :active? (boolean (seq effective-extension-deps))}
     :psi.extensions/diagnostics diagnostics}))

(defn extension-installs-state-in
  [ctx]
  (or (ss/get-state-value-in ctx (ss/state-path :extension-installs))
      {}))

(defn apply-installs-in!
  "Re-read install manifests and publish conservative apply status.

   Slice one behavior:
   - invalid manifests => diagnostics update, active runtime unchanged, no new
     last-apply success state
   - valid manifests with no effective extension deps => :applied
   - valid manifests with extension deps => :restart-required"
  [ctx cwd]
  (let [state       (compute-install-state cwd)
        diagnostics (:psi.extensions/diagnostics state)
        has-errors? (boolean (some #(= :error (:severity %)) diagnostics))
        ext-deps    (get-in state [:psi.extensions/effective :extension-deps])
        last-apply  (when-not has-errors?
                      {:status (if (seq ext-deps) :restart-required :applied)
                       :restart-required? (boolean (seq ext-deps))
                       :summary (if (seq ext-deps)
                                  (str "extension install manifests validated; restart required to realize "
                                       (count ext-deps)
                                       " extension deps")
                                  "extension install manifests validated; no restart required")
                       :diagnostic-count (count diagnostics)
                       :at (str (java.time.Instant/now))})
        persisted   (assoc state :psi.extensions/last-apply last-apply)]
    (ss/assoc-state-value-in! ctx (ss/state-path :extension-installs) persisted)
    {:state persisted
     :applied? (boolean last-apply)
     :status (:status last-apply)
     :diagnostics diagnostics}))
