(ns psi.agent-session.extensions
  "Extension system: registry, loading, event dispatch, tool wrapping, introspection.

   Design
   ──────
   An extension is a Clojure file exporting an `init` function.
   During factory invocation the extension calls registration fns on the
   ExtensionAPI map to declare its handlers, tools, commands, flags, and shortcuts.

   Discovery: extensions are found in:
     1. Project-local:  .psi/extensions/
     2. User-global:    ~/.psi/agent/extensions/
     3. CLI-provided:   --extension paths

   Loading: each .clj file is `load-file`d, then its `init` fn is called
   with the ExtensionAPI map.

   Event dispatch (broadcast model):
     - Handlers fire in registration order (first registered, first called).
     - All registered handlers fire for every event (broadcast semantics).
     - A handler returning {:cancel true} signals that the associated session
       action should be blocked.  Remaining handlers still fire; if *any*
       handler returns {:cancel true} the action is blocked.
     - session_before_compact handlers may return {:result CompactionResult}
       to supply a custom compaction, bypassing the default LLM summarisation.

   Tool wrapping:
     - Extensions can hook into tool_call (before) and tool_result (after).
     - tool_call handlers may return {:block true} to prevent execution.
     - tool_result handlers may modify :content, :details, or :is-error.

   Introspection:
     - All registry state is queryable via EQL :psi.extension/* attributes.
     - `extension-details-in` returns per-extension detail maps.

   Nullable pattern:
     `create-registry` returns an isolated registry map (atom inside).
     All public fns take the registry as first arg (`-in` suffix)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]))

;; ============================================================
;; Registry structure
;; ============================================================

(defrecord ExtensionRegistry [state])

(defn create-registry
  "Create an isolated extension registry.
  State is {:extensions          {path ExtensionRecord}
            :registration-order  [path ...]
            :flag-values         {name value}
            :event-bus           {channel [handler-fn ...]}}"
  []
  (->ExtensionRegistry
   (atom {:extensions         {}
          :registration-order []
          :flag-values        {}
          :event-bus          {}})))

;; ============================================================
;; Registration helpers (operate on isolated registry)
;; ============================================================

(defn register-extension-in!
  "Register a new extension by path into `reg`."
  [reg path]
  (swap! (:state reg)
         (fn [s]
           (if (get-in s [:extensions path])
             s
             (-> s
                 (assoc-in [:extensions path]
                           {:path       path
                            :handlers   {}
                            :tools      {}
                            :commands   {}
                            :flags      {}
                            :shortcuts  {}})
                 (update :registration-order conj path)))))
  reg)

(defn register-handler-in!
  "Register `handler-fn` for `event-name` on the extension at `ext-path`."
  [reg ext-path event-name handler-fn]
  (swap! (:state reg)
         update-in [:extensions ext-path :handlers event-name]
         (fnil conj [])
         {:extension-path ext-path :handler handler-fn})
  reg)

(def ^:private tool-name-pattern
  "Canonical tool names are kebab-case ASCII.
   This keeps names portable across model providers and transports."
  #"^[a-z0-9][a-z0-9-]*$")

(defn valid-tool-name?
  "Return true when `tool-name` is canonical kebab-case ASCII.
   Examples: read, app-query-tool, agent-chain."
  [tool-name]
  (and (string? tool-name)
       (boolean (re-matches tool-name-pattern tool-name))))

(defn register-tool-in!
  "Register `tool` (a map with :name key) for the extension at `ext-path`.
   Tool maps may include :lambda-description for lambda-mode prompt rendering.
   Throws when tool name is missing or not canonical kebab-case."
  [reg ext-path tool]
  (let [tool-name (:name tool)]
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info (str "Invalid tool name: " (pr-str tool-name)
                           ". Expected kebab-case matching " tool-name-pattern)
                      {:ext-path  ext-path
                       :tool-name tool-name
                       :pattern   (str tool-name-pattern)})))
    (swap! (:state reg)
           assoc-in [:extensions ext-path :tools tool-name] tool)
    reg))

(defn register-command-in!
  "Register `cmd` (a map with :name key) for the extension at `ext-path`."
  [reg ext-path cmd]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :commands (:name cmd)] cmd)
  reg)

(defn register-flag-in!
  "Register `flag` (a map with :name key) for the extension at `ext-path`.
   If the flag has a :default value and no value is set yet, sets the default."
  [reg ext-path flag]
  (swap! (:state reg)
         (fn [s]
           (let [s (assoc-in s [:extensions ext-path :flags (:name flag)] flag)]
             (if (and (contains? flag :default)
                      (not (contains? (:flag-values s) (:name flag))))
               (assoc-in s [:flag-values (:name flag)] (:default flag))
               s))))
  reg)

(defn register-shortcut-in!
  "Register `shortcut` (a map with :key and :handler) for the extension at `ext-path`."
  [reg ext-path shortcut]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :shortcuts (:key shortcut)] shortcut)
  reg)

(defn unregister-all-in!
  "Remove all registered extensions from `reg`. Used during reload.
   Preserves flag-values and event-bus."
  [reg]
  (swap! (:state reg) assoc :extensions {} :registration-order [])
  reg)

;; ============================================================
;; Flag values
;; ============================================================

(defn get-flag-in
  "Get the current value of flag `name` from `reg`."
  [reg name]
  (get-in @(:state reg) [:flag-values name]))

(defn set-flag-in!
  "Set the value of flag `name` in `reg`."
  [reg name value]
  (swap! (:state reg) assoc-in [:flag-values name] value)
  reg)

(defn all-flag-values-in
  "Return map of all flag name → current value."
  [reg]
  (:flag-values @(:state reg)))

;; ============================================================
;; Event bus (inter-extension communication)
;; ============================================================

(defn bus-emit-in!
  "Emit `data` on `channel` to all event bus subscribers in `reg`."
  [reg channel data]
  (let [handlers (get-in @(:state reg) [:event-bus channel])]
    (doseq [h handlers]
      (try (h data)
           (catch Exception e
             (timbre/warn "Event bus handler error on channel" channel
                          (ex-message e)))))))

(defn bus-on-in!
  "Subscribe `handler-fn` to `channel` on the event bus in `reg`.
   Returns a no-arg unsubscribe function."
  [reg channel handler-fn]
  (swap! (:state reg) update-in [:event-bus channel] (fnil conj []) handler-fn)
  (fn []
    (swap! (:state reg) update-in [:event-bus channel]
           (fn [handlers] (vec (remove #(= % handler-fn) handlers))))))

;; ============================================================
;; Dispatch
;; ============================================================

(defn dispatch-in
  "Dispatch `event` to all handlers registered for `event-name` in `reg`.

   Fires handlers in registration order (extension registration order, then
   handler-registration order within each extension).

   Returns a map:
     :cancelled?  — true if any handler returned {:cancel true}
     :results     — vector of all handler return values (nil for void handlers)
     :override    — last explicit override payload from handlers, or nil.
                    Accepts {:result x} and {:compaction x} (for compat)."
  [reg event-name event]
  (let [state             @(:state reg)
        ordered-paths     (:registration-order state)
        all-handler-lists (keep #(get-in state [:extensions % :handlers event-name])
                                ordered-paths)
        all-handlers      (mapcat identity all-handler-lists)
        results           (mapv (fn [{:keys [handler]}]
                                  (try (handler event)
                                       (catch Exception e
                                         {:error (.getMessage e)})))
                                all-handlers)
        overrides         (keep (fn [r]
                                  (or (:result r)
                                      (:compaction r)))
                                results)]
    {:cancelled? (boolean (some :cancel results))
     :override   (last overrides)
     :results    results}))

;; ============================================================
;; Tool wrapping
;; ============================================================

(defn dispatch-tool-call-in
  "Dispatch a tool_call event. Returns {:block true :reason s} or nil."
  [reg tool-name tool-call-id args]
  (let [{:keys [results]} (dispatch-in reg "tool_call"
                                       {:type         "tool_call"
                                        :tool-name    tool-name
                                        :tool-call-id tool-call-id
                                        :input        args})]
    (first (filter :block results))))

(defn dispatch-tool-result-in
  "Dispatch a tool_result event. Returns modified result map or nil."
  [reg tool-name tool-call-id args result is-error?]
  (let [{:keys [results]} (dispatch-in reg "tool_result"
                                       {:type         "tool_result"
                                        :tool-name    tool-name
                                        :tool-call-id tool-call-id
                                        :input        args
                                        :content      (:content result)
                                        :details      (:details result)
                                        :is-error     is-error?})]
    ;; Return the first result that modifies content/details/is-error
    (first (filter #(or (contains? % :content) (contains? % :details)
                        (contains? % :is-error))
                   (remove :error results)))))

(defn wrap-tool-executor
  "Wrap a tool executor fn with extension pre/post hooks.
   `execute-fn` is (fn [tool-name args] → {:content s :is-error bool}).
   Returns a wrapped fn with the same signature."
  [reg execute-fn]
  (fn [tool-name args]
    ;; Pre-hook: tool_call
    (let [block-result (dispatch-tool-call-in reg tool-name nil args)]
      (if (:block block-result)
        {:content  (or (:reason block-result)
                       "Tool execution was blocked by an extension")
         :is-error true}
        ;; Execute the actual tool
        (let [result   (execute-fn tool-name args)
              is-error (:is-error result false)
              ;; Post-hook: tool_result
              modified (dispatch-tool-result-in
                        reg tool-name nil args result is-error)]
          (if modified
            (cond-> result
              (contains? modified :content)  (assoc :content (:content modified))
              (contains? modified :details)  (assoc :details (:details modified))
              (contains? modified :is-error) (assoc :is-error (:is-error modified)))
            result))))))

;; ============================================================
;; Inspection
;; ============================================================

(defn extensions-in
  "Return sequence of all registered extension paths in `reg`."
  [reg]
  (:registration-order @(:state reg)))

(defn extension-count-in
  "Return number of registered extensions in `reg`."
  [reg]
  (count (:registration-order @(:state reg))))

(defn handler-count-in
  "Return total number of handler registrations across all events in `reg`."
  [reg]
  (let [state @(:state reg)]
    (reduce
     (fn [acc path]
       (+ acc (reduce + 0 (map count (vals (get-in state [:extensions path :handlers]))))))
     0
     (:registration-order state))))

(defn handler-event-names-in
  "Return sorted set of all event names that have at least one handler in `reg`."
  [reg]
  (let [state @(:state reg)]
    (into (sorted-set)
          (mapcat #(keys (get-in state [:extensions % :handlers]))
                  (:registration-order state)))))

(defn tool-names-in
  "Return set of all registered tool names across all extensions in `reg`."
  [reg]
  (let [state @(:state reg)]
    (into #{}
          (mapcat #(keys (get-in state [:extensions % :tools]))
                  (:registration-order state)))))

(defn command-names-in
  "Return set of all registered command names across all extensions in `reg`."
  [reg]
  (let [state @(:state reg)]
    (into #{}
          (mapcat #(keys (get-in state [:extensions % :commands]))
                  (:registration-order state)))))

(defn flag-names-in
  "Return set of all registered flag names across all extensions in `reg`."
  [reg]
  (let [state @(:state reg)]
    (into #{}
          (mapcat #(keys (get-in state [:extensions % :flags]))
                  (:registration-order state)))))

(defn all-tools-in
  "Return vector of all registered tool definition maps across all extensions.
   First registration per name wins."
  [reg]
  (let [state @(:state reg)
        seen  (atom #{})]
    (into []
          (for [path  (:registration-order state)
                [name tool] (get-in state [:extensions path :tools])
                :when (not (@seen name))
                :let  [_ (swap! seen conj name)]]
            (assoc tool :extension-path path)))))

(defn all-commands-in
  "Return vector of all registered command maps across all extensions.
   First registration per name wins."
  [reg]
  (let [state @(:state reg)
        seen  (atom #{})]
    (into []
          (for [path  (:registration-order state)
                [name cmd] (get-in state [:extensions path :commands])
                :when (not (@seen name))
                :let  [_ (swap! seen conj name)]]
            (assoc cmd :extension-path path)))))

(defn all-flags-in
  "Return vector of all registered flag maps across all extensions."
  [reg]
  (let [state @(:state reg)]
    (into []
          (for [path  (:registration-order state)
                [_name flag] (get-in state [:extensions path :flags])]
            (assoc flag :extension-path path
                   :current-value (get-in state [:flag-values (:name flag)]))))))

(defn get-command-in
  "Return the command map for `cmd-name`, or nil."
  [reg cmd-name]
  (let [state @(:state reg)]
    (some (fn [path]
            (get-in state [:extensions path :commands cmd-name]))
          (:registration-order state))))

(defn get-tool-in
  "Return the tool map for `tool-name`, or nil."
  [reg tool-name]
  (let [state @(:state reg)]
    (some (fn [path]
            (get-in state [:extensions path :tools tool-name]))
          (:registration-order state))))

(defn extension-detail-in
  "Return detail map for a single extension at `ext-path`, or nil."
  [reg ext-path]
  (let [state @(:state reg)
        ext   (get-in state [:extensions ext-path])]
    (when ext
      {:path           ext-path
       :handler-names  (into (sorted-set) (keys (:handlers ext)))
       :handler-count  (reduce + 0 (map count (vals (:handlers ext))))
       :tool-names     (into (sorted-set) (keys (:tools ext)))
       :tool-count     (count (:tools ext))
       :command-names  (into (sorted-set) (keys (:commands ext)))
       :command-count  (count (:commands ext))
       :flag-names     (into (sorted-set) (keys (:flags ext)))
       :flag-count     (count (:flags ext))
       :shortcut-count (count (:shortcuts ext))})))

(defn extension-details-in
  "Return vector of detail maps for all registered extensions."
  [reg]
  (mapv #(extension-detail-in reg %) (extensions-in reg)))

(defn summary-in
  "Return a summary map describing the extension registry state."
  [reg]
  {:extension-count (extension-count-in reg)
   :extensions      (extensions-in reg)
   :handler-count   (handler-count-in reg)
   :handler-events  (handler-event-names-in reg)
   :tool-names      (tool-names-in reg)
   :command-names   (command-names-in reg)
   :flag-names      (flag-names-in reg)})

(defn- runtime-not-initialized
  [action]
  (throw (ex-info "Extension runtime not initialized" {:action action})))

(defn- extension-op?
  [op-sym]
  (and (symbol? op-sym)
       (let [ns* (namespace op-sym)]
         (or (= "psi.extension" ns*)
             (and (string? ns*)
                  (str/starts-with? ns* "psi.extension."))))))

(defn- with-ext-path
  [ext-path params]
  (if (and (map? params)
           (not (contains? params :ext-path)))
    (assoc params :ext-path ext-path)
    params))

(defn- normalize-prompt-contribution
  [c]
  {:id         (or (:id c)
                   (:psi.extension.prompt-contribution/id c))
   :ext-path   (or (:ext-path c)
                   (:psi.extension.prompt-contribution/ext-path c))
   :section    (or (:section c)
                   (:psi.extension.prompt-contribution/section c))
   :content    (or (:content c)
                   (:psi.extension.prompt-contribution/content c))
   :priority   (or (:priority c)
                   (:psi.extension.prompt-contribution/priority c))
   :enabled    (if (contains? c :enabled)
                 (:enabled c)
                 (:psi.extension.prompt-contribution/enabled c))
   :created-at (or (:created-at c)
                   (:psi.extension.prompt-contribution/created-at c))
   :updated-at (or (:updated-at c)
                   (:psi.extension.prompt-contribution/updated-at c))})

(defn- list-extension-prompt-contributions
  [runtime-fns ext-path]
  (let [all (if-let [query-fn (:query-fn runtime-fns)]
              (:psi.extension/prompt-contributions
               (query-fn [:psi.extension/prompt-contributions]))
              [])]
    (->> all
         (map normalize-prompt-contribution)
         (filter #(= ext-path (:ext-path %)))
         vec)))

;; ============================================================
;; Extension API (passed to extension init functions)
;; ============================================================

(defn create-extension-api
  "Build the ExtensionAPI map for an extension at `ext-path`.
   The API provides registration methods plus EQL runtime access.

   `runtime-fns` is a map of runtime implementations:
     :query-fn       — (fn [eql-query])
     :mutate-fn      — (fn [op-sym params])
     :get-api-key-fn — (fn [provider]) ; narrow auth capability
     :ui-type-fn     — (fn [] => :console|:tui|:emacs|nil)
     :ui-context-fn  — (fn [ext-path] => extension ui context)
     :log-fn         — (fn [text]) ; diagnostic output → stderr

   Any missing runtime key throws."
  [reg ext-path runtime-fns]
  (let [mutate-fn      (:mutate-fn runtime-fns)
        query-fn       (:query-fn runtime-fns)
        get-api-key-fn (:get-api-key-fn runtime-fns)
        ui-type-fn     (:ui-type-fn runtime-fns)
        ui-context-fn  (:ui-context-fn runtime-fns)
        log-fn         (:log-fn runtime-fns)]
    {:path ext-path

     ;; ── Event subscription (via mutation) ─────────────
     :on
     (fn [event-name handler-fn]
       (if mutate-fn
         (mutate-fn 'psi.extension/register-handler
                    {:ext-path   ext-path
                     :event-name event-name
                     :handler-fn handler-fn})
         (register-handler-in! reg ext-path event-name handler-fn)))

     ;; ── Tool registration (via mutation) ──────────────
     :register-tool
     (fn [tool]
       (if mutate-fn
         (mutate-fn 'psi.extension/register-tool
                    {:ext-path ext-path
                     :tool     tool})
         (register-tool-in! reg ext-path tool)))

     ;; ── Command registration (via mutation) ───────────
     :register-command
     (fn [name opts]
       (if mutate-fn
         (mutate-fn 'psi.extension/register-command
                    {:ext-path ext-path
                     :name     name
                     :opts     opts})
         (register-command-in! reg ext-path (assoc opts :name name))))

     ;; ── Shortcut registration (via mutation) ──────────
     :register-shortcut
     (fn [key opts]
       (if mutate-fn
         (mutate-fn 'psi.extension/register-shortcut
                    {:ext-path ext-path
                     :key      key
                     :opts     opts})
         (register-shortcut-in! reg ext-path (assoc opts :key key))))

     ;; ── Flag registration (via mutation) ──────────────
     :register-flag
     (fn [name opts]
       (if mutate-fn
         (mutate-fn 'psi.extension/register-flag
                    {:ext-path ext-path
                     :name     name
                     :opts     opts})
         (register-flag-in! reg ext-path (assoc opts :name name))))

     ;; ── Flag access ────────────────────────────────────
     :get-flag
     (fn [name]
       (get-flag-in reg name))

     ;; ── Managed services + post-tool processors ────────
     :register-post-tool-processor
     (fn [{:keys [name match timeout-ms handler]}]
       (if mutate-fn
         (mutate-fn 'psi.extension/register-post-tool-processor
                    {:ext-path ext-path
                     :name     name
                     :match    match
                     :timeout-ms timeout-ms
                     :handler  handler})
         (runtime-not-initialized :register-post-tool-processor)))

     :ensure-service
     (fn [{:keys [key type spec]}]
       (if mutate-fn
         (mutate-fn 'psi.extension/ensure-service
                    {:ext-path ext-path
                     :key      key
                     :type     type
                     :spec     spec})
         (runtime-not-initialized :ensure-service)))

     :stop-service
     (fn [key]
       (if mutate-fn
         (mutate-fn 'psi.extension/stop-service
                    {:ext-path ext-path
                     :key      key})
         (runtime-not-initialized :stop-service)))

     :service-request
     (fn [{:keys [key request-id payload timeout-ms]}]
       (if mutate-fn
         (mutate-fn 'psi.extension/service-request
                    {:ext-path   ext-path
                     :key        key
                     :request-id request-id
                     :payload    payload
                     :timeout-ms timeout-ms})
         (runtime-not-initialized :service-request)))

     :service-notify
     (fn [{:keys [key payload]}]
       (if mutate-fn
         (mutate-fn 'psi.extension/service-notify
                    {:ext-path ext-path
                     :key      key
                     :payload  payload})
         (runtime-not-initialized :service-notify)))

     :list-services
     (fn []
       (if query-fn
         (:psi.service/services (query-fn [{:psi.service/services [:psi.service/key
                                                                   :psi.service/status
                                                                   :psi.service/command
                                                                   :psi.service/cwd
                                                                   :psi.service/transport
                                                                   :psi.service/ext-path]}]))
         (runtime-not-initialized :list-services)))

     ;; ── Prompt contribution helpers (extension-scoped) ─
     :register-prompt-contribution
     (fn [id contribution]
       (if mutate-fn
         (mutate-fn 'psi.extension/register-prompt-contribution
                    {:ext-path     ext-path
                     :id           id
                     :contribution contribution})
         (runtime-not-initialized :register-prompt-contribution)))

     :update-prompt-contribution
     (fn [id patch]
       (if mutate-fn
         (mutate-fn 'psi.extension/update-prompt-contribution
                    {:ext-path ext-path
                     :id       id
                     :patch    patch})
         (runtime-not-initialized :update-prompt-contribution)))

     :unregister-prompt-contribution
     (fn [id]
       (if mutate-fn
         (mutate-fn 'psi.extension/unregister-prompt-contribution
                    {:ext-path ext-path
                     :id       id})
         (runtime-not-initialized :unregister-prompt-contribution)))

     :list-prompt-contributions
     (fn []
       (list-extension-prompt-contributions runtime-fns ext-path))

     ;; ── EQL runtime surface ────────────────────────────
     :query
     (fn [eql-query]
       (if query-fn
         (query-fn eql-query)
         (runtime-not-initialized :query)))

     :mutate
     (fn [op-sym params]
       (if mutate-fn
         (mutate-fn op-sym
                    (if (extension-op? op-sym)
                      (with-ext-path ext-path params)
                      params))
         (runtime-not-initialized :mutate)))

     ;; ── Session lifecycle helpers ──────────────────────
     :create-session
     (fn [{:keys [session-name worktree-path system-prompt thinking-level] :as opts}]
       (if mutate-fn
         (mutate-fn 'psi.extension/create-session
                    (cond-> {:session-name session-name
                             :worktree-path worktree-path}
                      (contains? opts :system-prompt) (assoc :system-prompt system-prompt)
                      (contains? opts :thinking-level) (assoc :thinking-level thinking-level)))
         (runtime-not-initialized :create-session)))

     :switch-session
     (fn [session-id]
       (if mutate-fn
         (mutate-fn 'psi.extension/switch-session {:session-id session-id})
         (runtime-not-initialized :switch-session)))

     ;; ── Narrow auth helper (not queryable) ─────────────
     :get-api-key
     (fn [provider]
       (if get-api-key-fn
         (get-api-key-fn provider)
         (runtime-not-initialized :get-api-key)))

     ;; ── Event bus ──────────────────────────────────────
     :events
     {:emit (fn [channel data] (bus-emit-in! reg channel data))
      :on   (fn [channel handler] (bus-on-in! reg channel handler))}

     ;; ── UI type hint ───────────────────────────────────
     ;; Runtime UI surface marker for extension branching.
     :ui-type
     (when ui-type-fn
       (ui-type-fn))

     ;; ── UI context ─────────────────────────────────────
     ;; nil when headless (no TUI); present when TUI is active.
     :ui
     (when ui-context-fn
       (ui-context-fn ext-path))

     ;; ── Diagnostic logging ─────────────────────────────
     ;; Always routes to stderr — never to the RPC stdout pipe.
     :log
     (fn [text]
       (if log-fn
         (log-fn text)
         (binding [*out* *err*]
           (println text))))}))

;; ============================================================
;; Extension loading
;; ============================================================

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
                          ;; Direct .clj file
                          (extension-file? entry) [abs]
                          ;; Subdir with extension.clj
                          (.isDirectory entry)
                          (let [ext-clj (io/file entry "extension.clj")]
                            (when (.exists ext-clj)
                              [(.getAbsolutePath ext-clj)]))
                          :else nil)]
              path)))))

(defn- conj-unique!
  [seen result path]
  (when-not (@seen path)
    (swap! seen conj path)
    (swap! result conj path)))

(defn discover-extension-paths
  "Discover extension paths from standard locations and explicit paths.
   Returns deduplicated vector of absolute paths.

   Standard locations:
     1. .psi/extensions/  (project-local)
     2. ~/.psi/agent/extensions/  (user-global)
   Plus any explicit paths (files or dirs)."
  ([] (discover-extension-paths [] nil))
  ([configured-paths] (discover-extension-paths configured-paths nil))
  ([configured-paths cwd]
   (let [cwd    (or cwd (System/getProperty "user.dir"))
         home   (System/getProperty "user.home")
         seen   (atom #{})
         result (atom [])]
     ;; 1. Project-local
     (doseq [path (discover-in-dir (str cwd "/.psi/extensions"))]
       (conj-unique! seen result path))
     ;; 2. User-global
     (doseq [path (discover-in-dir (str home "/.psi/agent/extensions"))]
       (conj-unique! seen result path))
     ;; 3. Explicit paths
     (doseq [configured-path configured-paths]
       (let [f (io/file configured-path)]
         (cond
           ;; Direct file
           (and (.exists f) (.isFile f))
           (conj-unique! seen result (.getAbsolutePath f))

           ;; Directory — discover within it
           (and (.exists f) (.isDirectory f))
           (doseq [path (discover-in-dir configured-path)]
             (conj-unique! seen result path)))))
     @result)))

(defn load-extension-in!
  "Load a single extension from `ext-path` into `reg`.
   The file must define an `init` function in its namespace.
   `runtime-fns` is passed through to `create-extension-api`.
   Returns {:extension ext-path :error nil} or {:extension nil :error msg}."
  [reg ext-path runtime-fns]
  (try
    (let [f (io/file ext-path)]
      (when-not (.exists f)
        (throw (ex-info (str "Extension file not found: " ext-path) {:path ext-path})))
      ;; Register the extension path
      (register-extension-in! reg ext-path)
      ;; Load the file (defines namespace + vars)
      (load-file ext-path)
      ;; Find the init fn: look for `init` in the last namespace loaded from the file.
      ;; Convention: the file's ns should have an `init` fn.
      ;; We resolve it by reading the ns form from the file.
      (let [ns-sym  (with-open [rdr (java.io.PushbackReader. (io/reader f))]
                      (let [form (read rdr)]
                        (when (and (list? form) (= 'ns (first form)))
                          (second form))))
            _       (when-not ns-sym
                      (throw (ex-info "Extension file does not start with (ns ...)"
                                      {:path ext-path})))
            ns-obj  (find-ns ns-sym)
            _       (when-not ns-obj
                      (throw (ex-info (str "Namespace " ns-sym " not found after loading")
                                      {:path ext-path :ns ns-sym})))
            init-var (ns-resolve ns-obj 'init)
            _       (when-not init-var
                      (throw (ex-info (str "Extension " ns-sym " does not define `init` fn")
                                      {:path ext-path :ns ns-sym})))
            api     (create-extension-api reg ext-path runtime-fns)]
        (init-var api)
        {:extension ext-path :error nil}))
    (catch Exception e
      (timbre/warn "Failed to load extension" ext-path (ex-message e))
      {:extension nil :error (ex-message e)})))

(defn load-extensions-in!
  "Discover and load all extensions into `reg`.
   `configured-paths` are explicit CLI paths.
   `runtime-fns` is passed to each extension's API.
   Returns {:loaded [paths] :errors [{:path :error}]}."
  ([reg] (load-extensions-in! reg {} []))
  ([reg runtime-fns] (load-extensions-in! reg runtime-fns []))
  ([reg runtime-fns configured-paths]
   (load-extensions-in! reg runtime-fns configured-paths nil))
  ([reg runtime-fns configured-paths cwd]
   (let [paths  (discover-extension-paths configured-paths cwd)
         loaded (atom [])
         errors (atom [])]
     (doseq [p paths]
       (let [result (load-extension-in! reg p runtime-fns)]
         (if (:error result)
           (swap! errors conj {:path p :error (:error result)})
           (swap! loaded conj p))))
     {:loaded @loaded :errors @errors})))

(defn reload-extensions-in!
  "Unregister all extensions and re-discover/load them.
   Returns the load result."
  ([reg] (reload-extensions-in! reg {} []))
  ([reg runtime-fns] (reload-extensions-in! reg runtime-fns []))
  ([reg runtime-fns configured-paths]
   (reload-extensions-in! reg runtime-fns configured-paths nil))
  ([reg runtime-fns configured-paths cwd]
   (unregister-all-in! reg)
   (load-extensions-in! reg runtime-fns configured-paths cwd)))


