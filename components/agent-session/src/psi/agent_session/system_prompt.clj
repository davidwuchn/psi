(ns psi.agent-session.system-prompt
  "System prompt assembly for the agent session.

   The system prompt is built from:
     - Tool descriptions (available tools)
     - Context files (AGENTS.md / CLAUDE.md discovered up directory tree)
     - Skills (progressive disclosure: name + description only)
     - Custom/append prompt overrides
     - Session creation time and working directory (frozen, cache-stable)

   The assembled prompt is introspectable: stored in session data as
   :system-prompt and queryable via EQL :psi.agent-session/system-prompt.

   Follows the same pattern as pi's system-prompt.ts — skills are appended
   as XML when the read tool is available."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.skills :as skills]))

;;; Tool descriptions (built-in)

(def ^:private tool-description-pairs
  "Built-in tools carry both prose and lambda descriptions."
  {"read"           {:prose  "Read file contents"
                     :lambda "λf. content(f)"}
   "bash"           {:prose  "Execute bash commands (ls, grep, find, etc.)"
                     :lambda "λcmd. shell(cmd) | {ls grep find …}"}
   "edit"           {:prose  "Make surgical edits to files (find exact text and replace)"
                     :lambda "λf. find(exact) → replace"}
   "write"          {:prose  "Create or overwrite files"
                     :lambda "λf. create(f) ∨ overwrite(f)"}
   "psi-tool"       {:prose  "Execute live psi runtime operations: action-based graph query, in-process eval, and explicit code reload."
                     :lambda "λaction. runtime(query ∨ eval ∨ reload-code) → {graph ∨ value ∨ reload-report}"}})

;;; Lambda mode constants

(def ^:private default-nucleus-prelude
  "λ engage(nucleus).\n[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h] | OODA\nHuman ⊗ AI ⊗ REPL")

(def ^:private lambda-identity
  "λ identity(ψ). agent(coding) ∈ harness(psi) | read ∧ exec ∧ edit ∧ write")

(def ^:private lambda-guidelines
  "λ guide.\n  bash → {ls rg find} | file_ops\n  read → pre(edit) | ¬cat ¬sed\n  edit → precise(old ≡ match → new)\n  write → new_file ∨ full_rewrite\n  output → plaintext | ¬cat ¬bash\n  style → concise ∧ show(paths)")

(def ^:private lambda-graph-discovery
  "λ graph(eql).\n  purpose → discover(capabilities ∧ attrs) | ¬guess(paths)\n  endpoints → {:psi.graph/ resolver-count mutation-count resolver-syms mutation-syms env-built nodes edges capabilities domain-coverage}\n  workflow → query(resolver-syms) → query(discovered-attrs)\n  root → {root-seeds → contexts | root-queryable-attrs → attrs}\n  usage → {:psi.agent-session/ usage-input usage-output usage-cache-read usage-cache-write context-tokens context-window}")

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

(defn format-prompt-contributions-for-prompt
  "Render extension-managed prompt contributions as a deterministic prompt layer.

   Input: vector of maps with keys
   :id :ext-path :section :content :priority :enabled.

   Returns nil when no enabled contributions exist, otherwise a formatted string
   that can be appended to the base system prompt."
  [contributions]
  (let [enabled (->> contributions
                     (filter map?)
                     (filter #(not (false? (:enabled %))))
                     (sort-by (fn [{:keys [priority ext-path id]}]
                                [(or priority 1000)
                                 (or ext-path "")
                                 (or id "")]))
                     vec)]
    (when (seq enabled)
      (str "\n\n# Extension Prompt Contributions\n\n"
           (str/join
            "\n\n"
            (map (fn [{:keys [id ext-path section content]}]
                   (str "<prompt_contribution"
                        " id=\"" (or id "") "\""
                        " ext_path=\"" (or ext-path "") "\""
                        (when (some? section)
                          (str " section=\"" section "\""))
                        ">\n"
                        (or content "")
                        "\n</prompt_contribution>"))
                 enabled))))))

(defn apply-prompt-contributions
  "Append the rendered extension contribution layer to an already assembled
   base system prompt.

   Returns `base-prompt` unchanged when no enabled contributions are present."
  [base-prompt contributions]
  (let [base (or base-prompt "")]
    (if-let [section (format-prompt-contributions-for-prompt contributions)]
      (str base section)
      base)))

(def ^:private datetime-formatter
  (java.time.format.DateTimeFormatter/ofPattern
   "EEEE, MMMM d, yyyy 'at' hh:mm:ss a z"))

(defn format-instant
  "Format an Instant as a human-readable date/time string in the system default zone."
  [^java.time.Instant instant]
  (.format (.atZone instant (java.time.ZoneId/systemDefault))
           datetime-formatter))

(defn runtime-metadata-tail
  "Return the runtime metadata suffix for the system prompt.
   Pure function — uses the provided instant, not the wall clock."
  [cwd instant]
  (str "\nCurrent date and time: " (format-instant instant)
       "\nCurrent working directory: " cwd
       "\nCurrent worktree directory: " cwd))

(defn system-prompt-blocks
  "Return Anthropic-compatible system prompt blocks.
   The entire prompt is stable (time and cwd are frozen at session creation),
   so it is returned as a single cacheable block."
  [prompt cache-system?]
  (when (and (string? prompt) (seq prompt))
    [(cond-> {:kind :text :text prompt}
       cache-system?
       (assoc :cache-control {:type :ephemeral}))]))

(defn- tool-description-for-mode
  "Return the description string for a tool in the given mode.
   Built-in tools use the pairs map. Extension tools use :lambda-description
   if present in lambda mode, otherwise fall back to :description."
  [tool-name mode ext-tool-map]
  (if-let [pair (get tool-description-pairs tool-name)]
    (get pair mode)
    ;; Extension tool — fallback to prose when lambda not available
    (if (and (= mode :lambda) (:lambda-description ext-tool-map))
      (:lambda-description ext-tool-map)
      (:description ext-tool-map))))

(defn- format-tools-section
  "Format the tool list section for the given mode.
   Built-in tools come first, then extension tools not already listed."
  [tool-names mode extension-tool-descriptions]
  (let [ext-by-name  (into {} (map (juxt :name identity)) (or extension-tool-descriptions []))
        builtin-set  (set tool-names)
        ext-only     (remove #(builtin-set (:name %)) (or extension-tool-descriptions []))
        all-tools    (into (vec (distinct tool-names)) (map :name ext-only))
        lines        (keep (fn [tn]
                             (when-let [desc (tool-description-for-mode tn mode (get ext-by-name tn))]
                               (if (= mode :lambda)
                                 (str tn " → " desc)
                                 (str "- " tn ": " desc))))
                           all-tools)]
    (if (seq lines)
      (str/join "\n" lines)
      "(none)")))

(defn- build-prose-preamble
  "Build the psi-authored preamble sections in prose mode."
  [tool-names has-app-query? loaded-caps extension-tool-descriptions]
  (let [tools-section (format-tools-section tool-names :prose extension-tool-descriptions)

        guidelines
        (cond-> []
          (some #(= "bash" %) tool-names)
          (conj "Use bash for file operations like ls, rg, find")

          (and (some #(= "read" %) tool-names) (some #(= "edit" %) tool-names))
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

        graph-discovery-section
        (when has-app-query?
          (str "\n\nCapability graph (EQL discovery):\n"
               "- Purpose: discover live query capabilities and valid attrs before guessing paths.\n"
               "- Endpoints: :psi.graph/resolver-count :psi.graph/mutation-count :psi.graph/resolver-syms :psi.graph/mutation-syms :psi.graph/env-built :psi.graph/nodes :psi.graph/edges :psi.graph/capabilities :psi.graph/domain-coverage\n"
               "- Workflow: 1) query :psi.graph/resolver-syms 2) query discovered attrs directly.\n"
               "- Canonical root discovery:\n"
               "  - psi-tool(action: \"query\", query: \"[:psi.graph/root-seeds]\")        ; shows injected root contexts\n"
               "  - psi-tool(action: \"query\", query: \"[:psi.graph/root-queryable-attrs]\") ; authoritative list of root-queryable attrs\n"
               "- Canonical explicit session targeting:\n"
               "  - psi-tool(action: \"query\", query: \"[:psi.agent-session/session-name :psi.agent-session/model-id]\", entity: \"{:psi.agent-session/session-id \\\"sid\\\"}\")\n"
               "  - Session-scoped attrs require :psi.agent-session/session-id; missing targeting should fail rather than silently using another session.\n"
               "- Eval:\n"
               "  - psi-tool(action: \"eval\", ns: \"clojure.core\", form: \"(+ 1 2)\")\n"
               "  - Eval is namespace-scoped and requires an already loaded namespace.\n"
               "- Reload code:\n"
               "  - namespace mode: psi-tool(action: \"reload-code\", namespaces: [\"psi.agent-session.tools\"])\n"
               "  - worktree mode (session-derived): psi-tool(action: \"reload-code\")\n"
               "  - worktree mode (explicit): psi-tool(action: \"reload-code\", worktree-path: \"/abs/path/to/worktree\")\n"
               "  - Reload reports code-reload and graph-refresh separately.\n"
               "- Token usage attrs: :psi.agent-session/usage-input :psi.agent-session/usage-output :psi.agent-session/usage-cache-read :psi.agent-session/usage-cache-write :psi.agent-session/context-tokens :psi.agent-session/context-window\n"
               "- Legacy compatibility: query-only psi-tool(query: \"...\") remains accepted during migration, but canonical docs use action-based requests.\n"
               "- Example: psi-tool(action: \"query\", query: \"[:psi.graph/resolver-syms]\")"))

        graph-capabilities-section
        (when (and has-app-query? (seq loaded-caps))
          (str "\nCurrent capabilities (from :psi.graph/capabilities):\n"
               (format-graph-capabilities loaded-caps)))]

    (str "You are ψ (Psi), an expert coding assistant operating inside psi, a coding agent harness. "
         "You help users by reading files, executing commands, editing code, and writing new files.\n\n"
         "Available tools:\n"
         tools-section "\n\n"
         "In addition to the tools above, you may have access to other custom tools depending on the project.\n\n"
         "Guidelines:\n"
         guidelines-section
         (or graph-discovery-section "")
         (or graph-capabilities-section ""))))

(defn- build-lambda-preamble
  "Build the psi-authored preamble sections in lambda mode."
  [tool-names has-app-query? loaded-caps nucleus-prelude extension-tool-descriptions]
  (let [prelude       (or nucleus-prelude default-nucleus-prelude)
        tools-section (format-tools-section tool-names :lambda extension-tool-descriptions)

        graph-section
        (when has-app-query?
          (str lambda-graph-discovery
               (when (seq loaded-caps)
                 (str "\n" (format-graph-capabilities loaded-caps)))))]

    (str prelude "\n\n"
         lambda-identity "\n\n"
         "λ tools.\n" tools-section "\n\n"
         lambda-guidelines
         (when graph-section
           (str "\n\n" graph-section)))))

(defn build-system-prompt
  "Build the complete system prompt from all sources.

   Options:
     :cwd                        — working directory (default: user.dir)
     :session-instant             — frozen session creation time
     :prompt-mode                 — :lambda (default) or :prose
     :nucleus-prelude-override    — custom prelude text (lambda mode only)
     :custom-prompt               — replaces the default prompt entirely
     :append-prompt               — text appended after the main prompt
     :selected-tools              — tool name strings
     :extension-tool-descriptions — [{:name :description :lambda-description}]
     :context-files               — [{:path :content}] pre-loaded context files
     :skills                      — [Skill] pre-loaded skills
     :graph-capabilities          — [{:domain :operation-count ...}]

   Returns the assembled prompt as a string."
  ([] (build-system-prompt {}))
  ([{:keys [cwd session-instant prompt-mode nucleus-prelude-override
            custom-prompt append-prompt selected-tools extension-tool-descriptions
            context-files skills graph-capabilities]}]
   (let [resolved-cwd     (or cwd (System/getProperty "user.dir"))
         resolved-instant (or session-instant (java.time.Instant/now))
         mode           (or prompt-mode :lambda)
         tool-names     (or selected-tools ["read" "bash" "edit" "write" "psi-tool"])
         has-read?      (some #(= "read" %) tool-names)
         has-app-query? (some #(= "psi-tool" %) tool-names)
         loaded-skills   (or skills [])
         loaded-ctx      (or context-files [])
         loaded-caps     (or graph-capabilities [])

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
           (if (= mode :lambda)
             (skills/format-skills-for-prompt-lambda loaded-skills)
             (skills/format-skills-for-prompt loaded-skills)))

         ;; Main prompt — mode-branched preamble
         base-prompt
         (if custom-prompt
           custom-prompt
           (if (= mode :lambda)
             (build-lambda-preamble tool-names has-app-query? loaded-caps
                                    nucleus-prelude-override extension-tool-descriptions)
             (build-prose-preamble tool-names has-app-query? loaded-caps
                                   extension-tool-descriptions)))]

     ;; Skills come before context files (AGENTS.md) so that psi-authored
     ;; content groups together and project context appears last before the
     ;; runtime metadata tail. Extension contributions are now appended by the
     ;; prompt handler/request-preparation path, not by base prompt assembly.
     (str base-prompt
          (or append-section "")
          (or skills-section "")
          (or context-section "")
          (runtime-metadata-tail resolved-cwd resolved-instant)))))
