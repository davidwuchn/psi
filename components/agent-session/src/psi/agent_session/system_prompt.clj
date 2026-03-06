(ns psi.agent-session.system-prompt
  "System prompt assembly for the agent session.

   The system prompt is built from:
     - Tool descriptions (available tools)
     - Context files (AGENTS.md / CLAUDE.md discovered up directory tree)
     - Skills (progressive disclosure: name + description only)
     - Custom/append prompt overrides
     - Current date/time and working directory

   The assembled prompt is introspectable: stored in session data as
   :system-prompt and queryable via EQL :psi.agent-session/system-prompt.

   Follows the same pattern as pi's system-prompt.ts — skills are appended
   as XML when the read tool is available."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.skills :as skills]))

;; ============================================================
;; Tool descriptions (built-in)
;; ============================================================

(def ^:private tool-descriptions
  {"read"      "Read file contents"
   "bash"      "Execute bash commands (ls, grep, find, etc.)"
   "edit"      "Make surgical edits to files (find exact text and replace)"
   "write"     "Create or overwrite files"
   "eql_query" "Execute an EQL query against the live session graph. Returns session state, tool info, extension status, and more."})

(defn- format-graph-capabilities
  "Format a terse capability list from :psi.graph/capabilities maps."
  [capabilities]
  (->> capabilities
       (sort-by (comp str :domain))
       (map (fn [{:keys [domain operation-count resolver-count mutation-count]}]
              (str "- " (name (or domain :unknown))
                   " (ops=" (or operation-count 0)
                   ", resolvers=" (or resolver-count 0)
                   ", mutations=" (or mutation-count 0) ")")))
       (str/join "\n")))

;; ============================================================
;; Context file discovery
;; ============================================================

(defn- load-context-file-from-dir
  "Look for AGENTS.md or CLAUDE.md in `dir`. Returns {:path :content} or nil."
  [dir]
  (let [candidates ["AGENTS.md" "CLAUDE.md"]]
    (some (fn [filename]
            (let [f (io/file dir filename)]
              (when (.exists f)
                (try
                  {:path    (.getAbsolutePath f)
                   :content (slurp f)}
                  (catch Exception _ nil)))))
          candidates)))

(defn discover-context-files
  "Walk from `cwd` up to filesystem root, collecting AGENTS.md/CLAUDE.md files.
   Also checks `agent-dir` for a global context file.
   Returns [{:path :content}] in root-first order."
  ([cwd] (discover-context-files cwd nil))
  ([cwd agent-dir]
   (let [seen      (atom #{})
         result    (atom [])
         ;; Global context first
         _         (when agent-dir
                     (when-let [ctx (load-context-file-from-dir agent-dir)]
                       (swap! seen conj (:path ctx))
                       (swap! result conj ctx)))
         ;; Walk up from cwd
         ancestors (atom [])
         root      (.getAbsolutePath (io/file "/"))]
     (loop [dir (io/file cwd)]
       (when dir
         (when-let [ctx (load-context-file-from-dir (.getAbsolutePath dir))]
           (when-not (@seen (:path ctx))
             (swap! seen conj (:path ctx))
             (swap! ancestors conj ctx)))
         (let [parent (.getParentFile dir)]
           (when (and parent
                      (not= (.getAbsolutePath dir) root))
             (recur parent)))))
     ;; ancestors were collected child-first, reverse to get root-first
     (into @result (reverse @ancestors)))))

;; ============================================================
;; Prompt assembly
;; ============================================================

(defn build-system-prompt
  "Build the complete system prompt from all sources.

   Options:
     :cwd               — working directory (default: user.dir)
     :custom-prompt      — replaces the default prompt entirely
     :append-prompt      — text appended after the main prompt
     :selected-tools     — tool name strings (default: read bash edit write eql_query)
     :context-files      — [{:path :content}] pre-loaded context files
     :skills             — [Skill] pre-loaded skills
     :graph-capabilities — [{:domain :operation-count :resolver-count :mutation-count}]
                           from :psi.graph/capabilities

   The assembled prompt is returned as a string."
  ([] (build-system-prompt {}))
  ([{:keys [cwd custom-prompt append-prompt selected-tools
            context-files skills graph-capabilities]}]
   (let [resolved-cwd   (or cwd (System/getProperty "user.dir"))
         tool-names     (or selected-tools ["read" "bash" "edit" "write" "eql_query"])
         has-read?      (some #(= "read" %) tool-names)
         has-eql-query? (some #(= "eql_query" %) tool-names)
         loaded-skills  (or skills [])
         loaded-ctx     (or context-files [])
         loaded-caps    (or graph-capabilities [])

         ;; Date/time stamp
         now    (java.time.ZonedDateTime/now)
         fmt    (java.time.format.DateTimeFormatter/ofPattern
                 "EEEE, MMMM d, yyyy 'at' hh:mm:ss a z")
         dt-str (.format now fmt)

         ;; Tool list
         tools-section
         (let [known (filter #(contains? tool-descriptions %) tool-names)]
           (if (seq known)
             (str/join "\n" (map #(str "- " % ": " (get tool-descriptions %)) known))
             "(none)"))

         ;; Guidelines based on available tools
         guidelines
         (cond-> []
           (some #(= "bash" %) tool-names)
           (conj "Use bash for file operations like ls, rg, find")

           (and has-read? (some #(= "edit" %) tool-names))
           (conj "Use read to examine files before editing. You must use this tool instead of cat or sed.")

           (some #(= "edit" %) tool-names)
           (conj "Use edit for precise changes (old text must match exactly)")

           (some #(= "write" %) tool-names)
           (conj "Use write only for new files or complete rewrites")

           (or (some #(= "edit" %) tool-names)
               (some #(= "write" %) tool-names))
           (conj "When summarizing your actions, output plain text directly - do NOT use cat or bash to display what you did")

           true
           (conj "Be concise in your responses")

           true
           (conj "Show file paths clearly when working with files"))

         guidelines-section (str/join "\n" (map #(str "- " %) guidelines))

         ;; Graph capability discovery section
         graph-discovery-section
         (when has-eql-query?
           (str "\n\nCapability graph (EQL discovery):\n"
                "- Purpose: discover live query capabilities and valid attrs before guessing paths.\n"
                "- Endpoints: :psi.graph/resolver-count :psi.graph/mutation-count :psi.graph/resolver-syms :psi.graph/mutation-syms :psi.graph/env-built :psi.graph/nodes :psi.graph/edges :psi.graph/capabilities :psi.graph/domain-coverage\n"
                "- Workflow: 1) query :psi.graph/resolver-syms 2) query discovered attrs directly.\n"
                "- Canonical root discovery:\n"
                "  - eql_query(query: \"[:psi.graph/root-seeds]\")        ; shows injected root contexts\n"
                "  - eql_query(query: \"[:psi.graph/root-queryable-attrs]\") ; authoritative list of root-queryable attrs\n"
                "- Token usage attrs: :psi.agent-session/usage-input :psi.agent-session/usage-output :psi.agent-session/usage-cache-read :psi.agent-session/usage-cache-write :psi.agent-session/context-tokens :psi.agent-session/context-window\n"
                "- Example: eql_query(query: \"[:psi.graph/resolver-syms]\")"))

         graph-capabilities-section
         (when (and has-eql-query? (seq loaded-caps))
           (str "\nCurrent capabilities (from :psi.graph/capabilities):\n"
                (format-graph-capabilities loaded-caps)))

         ;; Append section
         append-section (when append-prompt (str "\n\n" append-prompt))

         ;; Context files section
         context-section
         (when (seq loaded-ctx)
           (str "\n\n# Project Context\n\n"
                "Project-specific instructions and guidelines:\n\n"
                (str/join "\n\n"
                          (map (fn [{:keys [path content]}]
                                 (str "## " path "\n\n" content))
                               loaded-ctx))))

         ;; Skills section (only if read tool available)
         skills-section
         (when (and has-read? (seq loaded-skills))
           (skills/format-skills-for-prompt loaded-skills))

         ;; Main prompt
         base-prompt
         (if custom-prompt
           custom-prompt
           (str "You are ψ (Psi), an expert coding assistant operating inside psi, a coding agent harness. "
                "You help users by reading files, executing commands, editing code, and writing new files.\n\n"
                "Available tools:\n"
                tools-section "\n\n"
                "In addition to the tools above, you may have access to other custom tools depending on the project.\n\n"
                "Guidelines:\n"
                guidelines-section
                (or graph-discovery-section "")
                (or graph-capabilities-section "")))]

     (str base-prompt
          (or append-section "")
          (or context-section "")
          (or skills-section "")
          "\nCurrent date and time: " dt-str
          "\nCurrent working directory: " resolved-cwd))))
