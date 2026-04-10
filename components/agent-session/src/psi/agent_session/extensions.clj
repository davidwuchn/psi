(ns psi.agent-session.extensions
  "Extension registry, loading, dispatch, tool wrapping, and introspection."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.tool-defs :as tool-defs]
   [taoensso.timbre :as timbre]))
(defrecord ExtensionRegistry [state])
(def ^:private default-allowed-events
  #{:session/ui-request-dialog
    :session/ui-set-widget
    :session/ui-clear-widget
    :session/ui-set-widget-spec
    :session/ui-clear-widget-spec
    :session/ui-set-status
    :session/ui-clear-status
    :session/ui-notify
    :session/ui-register-tool-renderer
    :session/ui-register-message-renderer
    :session/ui-set-tools-expanded})
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
(defn register-extension-in!
  "Register a new extension by path into `reg`.
   Seeds the extension record with explicit default `:allowed-events`.
   Extensions may narrow or extend these via `set-allowed-events-in!`."
  [reg path]
  (swap! (:state reg)
         (fn [s]
           (if (get-in s [:extensions path])
             s
             (-> s
                 (assoc-in [:extensions path]
                           {:path           path
                            :handlers       {}
                            :tools          {}
                            :commands       {}
                            :flags          {}
                            :shortcuts      {}
                            :allowed-events default-allowed-events})
                 (update :registration-order conj path)))))
  reg)
(defn set-allowed-events-in!
  "Replace the explicit allowed-event set for `ext-path`.
   Accepts any sequential collection of event keywords."
  [reg ext-path allowed-events]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :allowed-events]
         (set (or allowed-events #{})))
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
   Stores canonical normalized tool defs in the registry.
   Throws when tool name is missing or not canonical kebab-case."
  [reg ext-path tool]
  (let [tool-name (:name tool)]
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info (str "Invalid tool name: " (pr-str tool-name)
                           ". Expected kebab-case matching " tool-name-pattern)
                      {:ext-path  ext-path
                       :tool-name tool-name
                       :pattern   (str tool-name-pattern)})))
    (let [tool* (tool-defs/normalize-tool-def (assoc tool :source :extension :ext-path ext-path))]
      (swap! (:state reg)
             assoc-in [:extensions ext-path :tools tool-name] tool*)
      reg)))
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
(defn- state-in
  [reg]
  @(:state reg))
(defn- extension-item-maps
  [state item-key]
  (map #(or (get-in state [:extensions % item-key]) {})
       (:registration-order state)))
(defn- extension-item-names-in
  [reg item-key]
  (let [state (state-in reg)]
    (into #{}
          (mapcat keys)
          (extension-item-maps state item-key))))
(defn- all-first-registered-items-in
  [reg item-key]
  (let [state (state-in reg)
        seen  (volatile! #{})]
    (reduce
     (fn [items path]
       (reduce-kv
        (fn [items name item]
          (if (contains? @seen name)
            items
            (do
              (vswap! seen conj name)
              (conj items (assoc item :extension-path path)))))
        items
        (or (get-in state [:extensions path item-key]) {})))
     []
     (:registration-order state))))
(defn- get-extension-item-in
  [reg item-key item-name]
  (let [state (state-in reg)]
    (some #(get-in state [:extensions % item-key item-name])
          (:registration-order state))))
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
    (first (filter #(or (contains? % :content) (contains? % :details)
                        (contains? % :is-error))
                   (remove :error results)))))
(defn wrap-tool-executor
  "Wrap a tool executor fn with extension pre/post hooks.
   `execute-fn` is (fn [tool-name args] → {:content s :is-error bool}).
   Returns a wrapped fn with the same signature."
  [reg execute-fn]
  (fn [tool-name args]
    (let [block-result (dispatch-tool-call-in reg tool-name nil args)]
      (if (:block block-result)
        {:content  (or (:reason block-result)
                       "Tool execution was blocked by an extension")
         :is-error true}
        (let [result   (execute-fn tool-name args)
              is-error (:is-error result false)
              modified (dispatch-tool-result-in
                        reg tool-name nil args result is-error)]
          (if modified
            (cond-> result
              (contains? modified :content)  (assoc :content (:content modified))
              (contains? modified :details)  (assoc :details (:details modified))
              (contains? modified :is-error) (assoc :is-error (:is-error modified)))
            result))))))
(defn extensions-in
  "Return sequence of all registered extension paths in `reg`."
  [reg]
  (:registration-order (state-in reg)))
(defn extension-count-in
  "Return number of registered extensions in `reg`."
  [reg]
  (count (extensions-in reg)))
(defn handler-count-in
  "Return total number of handler registrations across all events in `reg`."
  [reg]
  (let [state (state-in reg)]
    (reduce
     (fn [acc handlers-by-event]
       (+ acc (reduce + 0 (map count (vals handlers-by-event)))))
     0
     (extension-item-maps state :handlers))))
(defn handler-event-names-in
  "Return sorted set of all event names that have at least one handler in `reg`."
  [reg]
  (into (sorted-set) (extension-item-names-in reg :handlers)))
(defn tool-names-in
  "Return set of all registered tool names across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :tools))
(defn command-names-in
  "Return set of all registered command names across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :commands))
(defn flag-names-in
  "Return set of all registered flag names across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :flags))
(defn all-tools-in
  "Return vector of all registered tool definition maps across all extensions.
   First registration per name wins."
  [reg]
  (all-first-registered-items-in reg :tools))
(defn all-commands-in
  "Return vector of all registered command maps across all extensions.
   First registration per name wins."
  [reg]
  (all-first-registered-items-in reg :commands))
(defn all-flags-in
  "Return vector of all registered flag maps across all extensions."
  [reg]
  (let [state (state-in reg)]
    (reduce
     (fn [flags path]
       (reduce-kv
        (fn [flags _ flag]
          (conj flags
                (assoc flag
                       :extension-path path
                       :current-value (get-in state [:flag-values (:name flag)]))))
        flags
        (or (get-in state [:extensions path :flags]) {})))
     []
     (:registration-order state))))
(defn get-command-in
  "Return the command map for `cmd-name`, or nil."
  [reg cmd-name]
  (get-extension-item-in reg :commands cmd-name))
(defn get-tool-in
  "Return the tool map for `tool-name`, or nil."
  [reg tool-name]
  (get-extension-item-in reg :tools tool-name))
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
       :shortcut-count (count (:shortcuts ext))
       :allowed-events (:allowed-events ext)})))
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
(def ^:private services-query
  [{:psi.service/services [:psi.service/key
                           :psi.service/status
                           :psi.service/command
                           :psi.service/cwd
                           :psi.service/transport
                           :psi.service/ext-path]}])
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
(defn- mutate-extension-op
  [mutate-fn ext-path op params]
  (mutate-fn op (with-ext-path ext-path params)))
(defn- mutate-ext-or-local
  [mutate-fn ext-path op params fallback-fn]
  (if mutate-fn
    (mutate-extension-op mutate-fn ext-path op params)
    (fallback-fn)))
(defn- service-request-params
  [{:keys [key request-id payload timeout-ms dispatch-id] :as opts}]
  (cond-> {:key        key
           :request-id request-id
           :payload    payload}
    (contains? opts :timeout-ms) (assoc :timeout-ms timeout-ms)
    (contains? opts :dispatch-id) (assoc :dispatch-id dispatch-id)))
(defn- service-notify-params
  [{:keys [key payload dispatch-id] :as opts}]
  (cond-> {:key     key
           :payload payload}
    (contains? opts :dispatch-id) (assoc :dispatch-id dispatch-id)))
(defn- create-session-params
  [{:keys [session-name worktree-path system-prompt thinking-level] :as opts}]
  (cond-> {:session-name session-name
           :worktree-path worktree-path}
    (contains? opts :system-prompt) (assoc :system-prompt system-prompt)
    (contains? opts :thinking-level) (assoc :thinking-level thinking-level)))
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
  [query-fn ext-path]
  (let [all (if query-fn
              (:psi.extension/prompt-contributions
               (query-fn [:psi.extension/prompt-contributions]))
              [])]
    (->> all
         (map normalize-prompt-contribution)
         (filter #(= ext-path (:ext-path %)))
         vec)))
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
  [reg ext-path {:keys [mutate-fn query-fn get-api-key-fn ui-type-fn ui-context-fn log-fn]}]
  (let [mutate-local
        (fn [op params fallback-fn]
          (mutate-ext-or-local mutate-fn ext-path op params fallback-fn))
        mutate-ext-required
        (fn [action op params]
          (if mutate-fn
            (mutate-extension-op mutate-fn ext-path op params)
            (runtime-not-initialized action)))
        mutate-required
        (fn [action op params]
          (if mutate-fn
            (mutate-fn op params)
            (runtime-not-initialized action)))
        runtime-query
        (fn [action eql-query]
          (if query-fn
            (query-fn eql-query)
            (runtime-not-initialized action)))
        runtime-mutate
        (fn [op-sym params]
          (mutate-required :mutate
                           op-sym
                           (if (extension-op? op-sym)
                             (with-ext-path ext-path params)
                             params)))
        runtime-log
        (fn [text]
          (if log-fn
            (log-fn text)
            (binding [*out* *err*]
              (println text))))
        on!
        (fn [event-name handler-fn]
          (mutate-local 'psi.extension/register-handler
                        {:event-name event-name
                         :handler-fn handler-fn}
                        #(register-handler-in! reg ext-path event-name handler-fn)))
        register-tool!
        (fn [tool]
          (mutate-local 'psi.extension/register-tool
                        {:tool tool}
                        #(register-tool-in! reg ext-path tool)))
        register-command!
        (fn [name opts]
          (mutate-local 'psi.extension/register-command
                        {:name name
                         :opts opts}
                        #(register-command-in! reg ext-path (assoc opts :name name))))
        register-shortcut!
        (fn [key opts]
          (mutate-local 'psi.extension/register-shortcut
                        {:key key
                         :opts opts}
                        #(register-shortcut-in! reg ext-path (assoc opts :key key))))
        register-flag!
        (fn [name opts]
          (mutate-local 'psi.extension/register-flag
                        {:name name
                         :opts opts}
                        #(register-flag-in! reg ext-path (assoc opts :name name))))
        set-allowed-events!
        (fn [allowed-events]
          (mutate-local 'psi.extension/set-allowed-events
                        {:allowed-events (vec allowed-events)}
                        #(set-allowed-events-in! reg ext-path allowed-events)))
        get-flag
        (fn [name]
          (get-flag-in reg name))
        register-post-tool-processor!
        (fn [{:keys [name match timeout-ms handler]}]
          (mutate-ext-required :register-post-tool-processor
                               'psi.extension/register-post-tool-processor
                               {:name       name
                                :match      match
                                :timeout-ms timeout-ms
                                :handler    handler}))
        ensure-service!
        (fn [{:keys [key type spec]}]
          (mutate-ext-required :ensure-service
                               'psi.extension/ensure-service
                               {:key  key
                                :type type
                                :spec spec}))
        stop-service!
        (fn [key]
          (mutate-ext-required :stop-service
                               'psi.extension/stop-service
                               {:key key}))
        service-request!
        (fn [opts]
          (mutate-ext-required :service-request
                               'psi.extension/service-request
                               (service-request-params opts)))
        service-notify!
        (fn [opts]
          (mutate-ext-required :service-notify
                               'psi.extension/service-notify
                               (service-notify-params opts)))
        list-services
        (fn []
          (:psi.service/services
           (runtime-query :list-services services-query)))
        register-prompt-contribution!
        (fn [id contribution]
          (mutate-ext-required :register-prompt-contribution
                               'psi.extension/register-prompt-contribution
                               {:id           id
                                :contribution contribution}))
        update-prompt-contribution!
        (fn [id patch]
          (mutate-ext-required :update-prompt-contribution
                               'psi.extension/update-prompt-contribution
                               {:id    id
                                :patch patch}))
        unregister-prompt-contribution!
        (fn [id]
          (mutate-ext-required :unregister-prompt-contribution
                               'psi.extension/unregister-prompt-contribution
                               {:id id}))
        list-prompt-contributions
        (fn []
          (list-extension-prompt-contributions query-fn ext-path))
        query
        (fn [eql-query]
          (runtime-query :query eql-query))
        create-session!
        (fn [opts]
          (mutate-required :create-session
                           'psi.extension/create-session
                           (create-session-params opts)))
        switch-session!
        (fn [session-id]
          (mutate-required :switch-session
                           'psi.extension/switch-session
                           {:session-id session-id}))
        get-api-key
        (fn [provider]
          (if get-api-key-fn
            (get-api-key-fn provider)
            (runtime-not-initialized :get-api-key)))
        events
        {:emit (fn [channel data] (bus-emit-in! reg channel data))
         :on   (fn [channel handler] (bus-on-in! reg channel handler))}
        ui-type
        (when ui-type-fn
          (ui-type-fn))
        ui
        (when ui-context-fn
          (ui-context-fn ext-path))]
    {:path ext-path
     :on on!
     :register-tool register-tool!
     :register-command register-command!
     :register-shortcut register-shortcut!
     :register-flag register-flag!
     :set-allowed-events set-allowed-events!
     :get-flag get-flag
     :register-post-tool-processor register-post-tool-processor!
     :ensure-service ensure-service!
     :stop-service stop-service!
     :service-request service-request!
     :service-notify service-notify!
     :list-services list-services
     :register-prompt-contribution register-prompt-contribution!
     :update-prompt-contribution update-prompt-contribution!
     :unregister-prompt-contribution unregister-prompt-contribution!
     :list-prompt-contributions list-prompt-contributions
     :query query
     :mutate runtime-mutate
     :create-session create-session!
     :switch-session switch-session!
     :get-api-key get-api-key
     :events events
     :ui-type ui-type
     :ui ui
     :log runtime-log}))
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
                          :else nil)]
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
   `runtime-fns` is passed through to `create-extension-api`.
   Returns {:extension ext-path :error nil} or {:extension nil :error msg}."
  [reg ext-path runtime-fns]
  (try
    (let [f (io/file ext-path)]
      (when-not (.exists f)
        (throw (ex-info (str "Extension file not found: " ext-path) {:path ext-path})))
      (register-extension-in! reg ext-path)
      (load-file ext-path)
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
       (let [{:keys [extension error]} (load-extension-in! reg p runtime-fns)]
         (if extension
           (swap! loaded conj extension)
           (swap! errors conj {:path p :error error}))))
     {:loaded @loaded :errors @errors})))
(defn reload-extensions-in!
  "Clear registered extensions and reload them from discovery/configured paths."
  ([reg runtime-fns configured-paths]
   (reload-extensions-in! reg runtime-fns configured-paths nil))
  ([reg runtime-fns configured-paths cwd]
   (unregister-all-in! reg)
   (load-extensions-in! reg runtime-fns configured-paths cwd)))
