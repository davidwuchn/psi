(ns psi.agent-session.oauth.store
  "Credential storage for API keys and OAuth tokens.

   Nullable pattern: `create-store` for production (file-backed),
   `create-null-store` for tests (in-memory, no disk).

   Both return the same map interface — all fns take the store as first arg.

   Credential shapes:
     API key:  {:type :api-key  :key \"sk-...\"}
     OAuth:    {:type :oauth    :refresh \"...\" :access \"...\"
                :expires <epoch-ms> :metadata {...}}"
  (:require [cheshire.core :as json])
  (:import [java.io File]))

;;; File helpers

(defn- ensure-parent-dir
  "Create parent directories for `path` with 0700 permissions."
  [^String path]
  (let [dir (.getParentFile (File. path))]
    (when (and dir (not (.exists dir)))
      (.mkdirs dir))))

(defn- read-json-file
  "Read and parse a JSON file. Returns {} on missing/invalid."
  [^String path]
  (try
    (if (.exists (File. path))
      (json/parse-string (slurp path) true)
      {})
    (catch Exception _e {})))

(defn- write-json-file!
  "Write `data` as JSON to `path` with 0600 permissions."
  [^String path data]
  (ensure-parent-dir path)
  (spit path (json/generate-string data {:pretty true}))
  (let [f (File. path)]
    (.setReadable f false false)
    (.setReadable f true true)
    (.setWritable f false false)
    (.setWritable f true true)))

;;; Store operations (work on both file and in-memory stores)

(defn get-credential
  "Get credential for a provider. Returns nil if not found."
  [store provider]
  (get @(:data store) (keyword provider)))

(defn set-credential!
  "Set credential for a provider and persist."
  [store provider credential]
  (let [k (keyword provider)]
    (swap! (:data store) assoc k credential)
    (when-let [persist! (:persist! store)]
      (persist! @(:data store)))))

(defn remove-credential!
  "Remove credential for a provider and persist."
  [store provider]
  (let [k (keyword provider)]
    (swap! (:data store) dissoc k)
    (when-let [persist! (:persist! store)]
      (persist! @(:data store)))))

(defn list-providers
  "List all providers with stored credentials."
  [store]
  (keys @(:data store)))

(defn has-credential?
  "Check if any credential exists for a provider."
  [store provider]
  (contains? @(:data store) (keyword provider)))

(defn reload!
  "Reload credentials from backing storage."
  [store]
  (when-let [load-fn (:load! store)]
    (reset! (:data store) (load-fn))))

;;; API key resolution

(def ^:private env-var-map
  "Map of provider keyword → environment variable name."
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"
   :groq      "GROQ_API_KEY"
   :xai       "XAI_API_KEY"})

(defn- env-api-key
  "Get API key from environment variable for a provider."
  [provider]
  (when-let [var-name (get env-var-map (keyword provider))]
    (System/getenv var-name)))

(defn get-api-key
  "Resolve API key for a provider using the priority chain:
     1. Runtime override
     2. Stored API key credential
     3. Stored OAuth credential (access token)
     4. Environment variable
     5. Fallback resolver

   Does NOT auto-refresh expired OAuth tokens — caller should check
   `oauth-expired?` and refresh before calling this."
  [store provider]
  (let [k (keyword provider)]
    (or
     ;; 1. Runtime override
     (get @(:runtime-overrides store) k)
     ;; 2–3. Stored credential
     (let [cred (get @(:data store) k)]
       (case (:type cred)
         :api-key (:key cred)
         :oauth   (:access cred)
         nil))
     ;; 4. Environment variable
     (env-api-key k)
     ;; 5. Fallback resolver
     (when-let [fallback (:fallback-resolver store)]
       (fallback k)))))

(defn has-auth?
  "Quick check: is any form of auth configured for a provider?
   Does NOT refresh tokens."
  [store provider]
  (boolean (get-api-key store provider)))

(defn oauth-expired?
  "Check if an OAuth credential's access token is expired."
  [store provider]
  (let [cred (get-credential store provider)]
    (and (= :oauth (:type cred))
         (>= (System/currentTimeMillis) (:expires cred 0)))))

(defn set-runtime-override!
  "Set a runtime API key override (not persisted). Used for --api-key flag."
  [store provider api-key]
  (swap! (:runtime-overrides store) assoc (keyword provider) api-key))

(defn remove-runtime-override!
  "Remove a runtime API key override."
  [store provider]
  (swap! (:runtime-overrides store) dissoc (keyword provider)))

;;; Factory: file-backed (production)

(defn create-store
  "Create a file-backed credential store.

   Options:
     :path — path to auth.json (default ~/.psi/agent/auth.json)"
  ([] (create-store {}))
  ([{:keys [path]}]
   (let [auth-path (or path
                       (str (System/getProperty "user.home")
                            "/.psi/agent/auth.json"))
         load-fn   #(read-json-file auth-path)
         data      (atom (load-fn))]
     {:data              data
      :runtime-overrides (atom {})
      :fallback-resolver nil
      :path              auth-path
      :load!             load-fn
      :persist!          (fn [d] (write-json-file! auth-path d))})))

;;; Factory: in-memory (tests — Nullable pattern)

(defn create-null-store
  "Create an in-memory credential store for testing. No disk I/O.

   Optionally seed with initial credentials:
     (create-null-store {:anthropic {:type :api-key :key \"sk-test\"}})"
  ([] (create-null-store {}))
  ([initial-data]
   (let [persisted (atom nil)]
     {:data              (atom initial-data)
      :runtime-overrides (atom {})
      :fallback-resolver nil
      :path              nil
      :load!             nil
      :persist!          (fn [d] (reset! persisted d))
      ;; Test-only: inspect what was persisted
      :persisted         persisted})))
