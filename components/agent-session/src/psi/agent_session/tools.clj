(ns psi.agent-session.tools
  "Built-in tool implementations: read, bash, edit, write.

   Each tool returns {:content string :is-error boolean}.
   Errors throw ex-info so the executor can catch and report them."
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ============================================================
;; Tool schemas (for agent registration)
;; ============================================================

(def read-tool
  {:name        "read"
   :label       "Read"
   :description "Read the contents of a file. Returns the file text."
   :parameters  (pr-str {:type       "object"
                         :properties {:path {:type "string" :description "File path to read"}}
                         :required   ["path"]})})

(def bash-tool
  {:name        "bash"
   :label       "Bash"
   :description "Execute a bash command. Returns stdout and stderr combined."
   :parameters  (pr-str {:type       "object"
                         :properties {:command {:type "string" :description "Bash command to run"}
                                      :timeout {:type "integer" :description "Timeout in seconds (default 30)"}}
                         :required   ["command"]})})

(def edit-tool
  {:name        "edit"
   :label       "Edit"
   :description "Replace exact text in a file. oldText must match exactly."
   :parameters  (pr-str {:type       "object"
                         :properties {:path    {:type "string" :description "File path"}
                                      :oldText {:type "string" :description "Exact text to find"}
                                      :newText {:type "string" :description "Replacement text"}}
                         :required   ["path" "oldText" "newText"]})})

(def write-tool
  {:name        "write"
   :label       "Write"
   :description "Write content to a file, creating it if it does not exist."
   :parameters  (pr-str {:type       "object"
                         :properties {:path    {:type "string" :description "File path"}
                                      :content {:type "string" :description "Content to write"}}
                         :required   ["path" "content"]})})

(def eql-query-tool
  {:name        "eql_query"
   :label       "EQL Query"
   :description "Execute an EQL query against the live session graph. Returns session state, tool info, extension status, and more. Input is an EDN vector, e.g. [:psi.agent-session/phase :psi.agent-session/model]"
   :parameters  (pr-str {:type       "object"
                         :properties {:query {:type "string" :description "EQL query vector as EDN string, e.g. \"[:psi.agent-session/phase :psi.agent-session/session-id]\""}}
                         :required   ["query"]})})

(def all-tool-schemas
  [read-tool bash-tool edit-tool write-tool eql-query-tool])

;; ============================================================
;; Tool implementations
;; ============================================================

(defn- slurp-file [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "File not found: " path) {:path path})))
    (slurp f)))

(defn execute-read
  "Read a file and return its contents."
  [{:strs [path]}]
  (let [content (slurp-file path)]
    {:content  content
     :is-error false}))

(defn execute-bash
  "Run a shell command via babashka.process, returning combined stdout+stderr.
  Stdin is bound to /dev/null so tools like rg don't misdetect a readable
  pipe and search stdin instead of the working directory."
  [{:strs [command]}]
  (let [result (proc/shell {:out      :string
                            :err      :string
                            :continue true
                            :in       (java.io.File. "/dev/null")}
                           "bash" "-c" command)
        out    (str (:out result) (:err result))]
    {:content  (if (str/blank? out) "[no output]" out)
     :is-error (not= 0 (:exit result))}))

(defn execute-edit
  "Replace oldText with newText in a file."
  [{:strs [path oldText newText]}]
  (let [content (slurp-file path)]
    (when-not (str/includes? content oldText)
      (throw (ex-info "oldText not found in file"
                      {:path path :oldText (subs oldText 0 (min 80 (count oldText)))})))
    (let [updated (str/replace-first content oldText newText)]
      (spit path updated)
      {:content  (str "Edited " path)
       :is-error false})))

(defn execute-write
  "Write content to a file (creates parent dirs if needed)."
  [{:strs [path content]}]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f content)
    {:content  (str "Wrote " path)
     :is-error false}))

(defn make-eql-query-tool
  "Create an eql_query tool with an :execute fn that closes over `query-fn`.
   `query-fn` should be (fn [eql-query-vec] -> result-map), typically
   `(partial resolvers/query-in ctx)` or `(fn [q] (session/query-in ctx q))`."
  [query-fn]
  (assoc eql-query-tool
         :execute
         (fn [{:strs [query]}]
           (try
             (let [q (binding [*read-eval* false]
                       (read-string query))]
               (when-not (vector? q)
                 (throw (ex-info "Query must be an EDN vector" {:input query})))
               (let [result (query-fn q)]
                 {:content  (pr-str result)
                  :is-error false}))
             (catch Exception e
               {:content  (str "EQL query error: " (ex-message e))
                :is-error true})))))

(def all-tools
  "Built-in tool definitions including execution fns.
   Use this when registering tools into agent state.
   Note: eql_query is excluded — it requires a session context.
   Use `make-eql-query-tool` to create it with a query-fn."
  [{:name        (:name read-tool)
    :label       (:label read-tool)
    :description (:description read-tool)
    :parameters  (:parameters read-tool)
    :execute     execute-read}
   {:name        (:name bash-tool)
    :label       (:label bash-tool)
    :description (:description bash-tool)
    :parameters  (:parameters bash-tool)
    :execute     execute-bash}
   {:name        (:name edit-tool)
    :label       (:label edit-tool)
    :description (:description edit-tool)
    :parameters  (:parameters edit-tool)
    :execute     execute-edit}
   {:name        (:name write-tool)
    :label       (:label write-tool)
    :description (:description write-tool)
    :parameters  (:parameters write-tool)
    :execute     execute-write}])

;; ============================================================
;; Dispatch
;; ============================================================

(defn execute-tool
  "Dispatch a tool call by name. Returns {:content string :is-error boolean}.
  Throws ex-info for unknown tools.
  Note: eql_query is not dispatched here — it requires a session context
  and is handled via the tool registry's :execute fn."
  [tool-name args-map]
  (case tool-name
    "read"  (execute-read args-map)
    "bash"  (execute-bash args-map)
    "edit"  (execute-edit args-map)
    "write" (execute-write args-map)
    (throw (ex-info (str "Unknown tool: " tool-name) {:tool tool-name}))))
