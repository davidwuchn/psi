(ns psi.agent-session.extensions.loader
  "Extension discovery and loading."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]))

(defn- extension-file?
  "True if `f` is a .clj file."
  [f]
  (and (.isFile f) (str/ends-with? (.getName f) ".clj")))

(defn- discover-in-dir
  "Discover extension .clj files in `dir`. Returns vector of absolute paths.
   Discovery: direct .clj files in dir, or extension.clj in subdirs."
  [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (into []
            (for [entry (.listFiles d)
                  :let  [abs (.getAbsolutePath entry)]
                  path  (cond
                          (extension-file? entry) [abs]
                          (.isDirectory entry)
                          (let [ext-clj (io/file entry "extension.clj")]
                            (when (.exists ext-clj)
                              [(.getAbsolutePath ext-clj)]))
                          :else                   nil)]
              path)))))

(defn- conj-unique!
  [seen result path]
  (when-not (@seen path)
    (swap! seen conj path)
    (swap! result conj path)))

(defn discover-extension-paths
  "Discover extension paths from standard locations and explicit paths.
   Search order:
     1. .psi/extensions/        (project-local)
     2. ~/.psi/agent/extensions/  (user-global)
   Plus any explicit paths (files or dirs)."
  ([] (discover-extension-paths [] nil))
  ([configured-paths] (discover-extension-paths configured-paths nil))
  ([configured-paths cwd]
   (let [cwd    (or cwd (System/getProperty "user.dir"))
         home   (System/getProperty "user.home")
         seen   (atom #{})
         result (atom [])]
     (doseq [path (discover-in-dir (str cwd "/.psi/extensions"))]
       (conj-unique! seen result path))
     (doseq [path (discover-in-dir (str home "/.psi/agent/extensions"))]
       (conj-unique! seen result path))
     (doseq [configured-path configured-paths]
       (let [f (io/file configured-path)]
         (cond
           (and (.exists f) (.isFile f))
           (conj-unique! seen result (.getAbsolutePath f))

           (and (.exists f) (.isDirectory f))
           (doseq [path (discover-in-dir configured-path)]
             (conj-unique! seen result path)))))
     @result)))

(defn load-extension-in!
  "Load a single extension from `ext-path` into `reg`.
   The file must define an `init` function in its namespace.
   `create-extension-api` builds the runtime API passed to `init`.
   `register-extension-in!` seeds registry state before init runs.
   Returns {:extension ext-path :error nil} or {:extension nil :error msg}."
  [reg ext-path runtime-fns register-extension-in! create-extension-api]
  (try
    (let [f (io/file ext-path)]
      (when-not (.exists f)
        (throw (ex-info (str "Extension file not found: " ext-path) {:path ext-path})))
      (register-extension-in! reg ext-path)
      (load-file ext-path)
      (let [ns-sym   (with-open [rdr (java.io.PushbackReader. (io/reader f))]
                       (let [form (read rdr)]
                         (when (and (list? form) (= 'ns (first form)))
                           (second form))))
            _        (when-not ns-sym
                       (throw (ex-info "Extension file does not start with (ns ...)"
                                       {:path ext-path})))
            ns-obj   (find-ns ns-sym)
            _        (when-not ns-obj
                       (throw (ex-info (str "Namespace " ns-sym " not found after loading")
                                       {:path ext-path :ns ns-sym})))
            init-var (ns-resolve ns-obj 'init)
            _        (when-not init-var
                       (throw (ex-info (str "Extension " ns-sym " does not define `init` fn")
                                       {:path ext-path :ns ns-sym})))
            api      (create-extension-api reg ext-path runtime-fns)]
        (init-var api)
        {:extension ext-path :error nil}))
    (catch Exception e
      (timbre/warn "Failed to load extension" ext-path (ex-message e))
      {:extension nil :error (ex-message e)})))

(defn load-extensions-in!
  "Discover and load all extensions into `reg`.
   `configured-paths` are explicit CLI paths.
   `runtime-fns` is passed to each extension's API.
   `load-extension-in!` is the single-extension loader implementation.
   Returns {:loaded [paths] :errors [{:path :error}]}."
  ([reg runtime-fns configured-paths load-extension-in!]
   (load-extensions-in! reg runtime-fns configured-paths nil load-extension-in!))
  ([reg runtime-fns configured-paths cwd load-extension-in!]
   (let [paths  (discover-extension-paths configured-paths cwd)
         loaded (atom [])
         errors (atom [])]
     (doseq [p paths]
       (let [{:keys [extension error]} (load-extension-in! reg p runtime-fns)]
         (if extension
           (swap! loaded conj extension)
           (swap! errors conj {:path p :error error}))))
     {:loaded @loaded :errors @errors})))

(defn reload-extensions-in!
  "Clear registered extensions and reload them from discovery/configured paths."
  ([reg runtime-fns configured-paths unregister-all-in! load-extensions-in!]
   (reload-extensions-in! reg runtime-fns configured-paths nil unregister-all-in! load-extensions-in!))
  ([reg runtime-fns configured-paths cwd unregister-all-in! load-extensions-in!]
   (unregister-all-in! reg)
   (load-extensions-in! reg runtime-fns configured-paths cwd)))
