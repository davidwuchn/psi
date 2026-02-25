(ns psi.agent-session.extensions
  "Extension registry and event dispatch.

   Design
   ──────
   An extension is registered by path.  During factory invocation the
   extension calls registration fns to declare its handlers, tools,
   commands, flags, and shortcuts.

   Event dispatch (broadcast model):
     - Handlers fire in registration order (first registered, first called).
     - All registered handlers fire for every event (broadcast semantics).
     - A handler returning {:cancel true} signals that the associated session
       action should be blocked.  Remaining handlers still fire; if *any*
       handler returns {:cancel true} the action is blocked.
     - session_before_compact handlers may return {:result CompactionResult}
       to supply a custom compaction, bypassing the default LLM summarisation.

   Nullable pattern:
     `create-registry` returns an isolated registry map (atom inside).
     All public fns have a context-aware `-in` variant.
     The global singleton delegates to `global-registry`."
  )

;; ============================================================
;; Schema
;; ============================================================

(def handler-entry-schema
  [:map
   [:extension-path :string]
   [:handler fn?]])

(def extension-record-schema
  [:map
   [:path :string]
   [:handlers [:map-of :string [:vector handler-entry-schema]]]
   [:tools [:map-of :string :map]]
   [:commands [:map-of :string :map]]
   [:flags [:map-of :string :map]]
   [:shortcuts [:map-of :string :map]]])

;; ============================================================
;; Registry structure
;; ============================================================

(defrecord ExtensionRegistry [state])

(defn create-registry
  "Create an isolated extension registry.
  State is {:extensions {path ExtensionRecord}
             :registration-order [path ...]}."
  []
  (->ExtensionRegistry
   (atom {:extensions        {}
          :registration-order []})))

(defonce ^:private global-reg (atom nil))

(defn- ensure-global-reg! []
  (or @global-reg
      (let [r (create-registry)]
        (reset! global-reg r)
        r)))

(defn- global-registry [] (ensure-global-reg!))

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
                            {:path      path
                             :handlers  {}
                             :tools     {}
                             :commands  {}
                             :flags     {}
                             :shortcuts {}})
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

(defn register-tool-in!
  "Register `tool` (a map with :name key) for the extension at `ext-path`."
  [reg ext-path tool]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :tools (:name tool)] tool)
  reg)

(defn register-command-in!
  "Register `cmd` (a map with :name key) for the extension at `ext-path`."
  [reg ext-path cmd]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :commands (:name cmd)] cmd)
  reg)

(defn register-flag-in!
  "Register `flag` (a map with :name key) for the extension at `ext-path`."
  [reg ext-path flag]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :flags (:name flag)] flag)
  reg)

(defn unregister-all-in!
  "Remove all registered extensions from `reg`. Used during reload."
  [reg]
  (swap! (:state reg) assoc :extensions {} :registration-order [])
  reg)

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
     :override    — first :result value from a handler, or nil
                    (used by session_before_compact to supply custom compaction)"
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
                                all-handlers)]
    {:cancelled? (boolean (some :cancel results))
     :override   (first (keep :result results))
     :results    results}))

(defn dispatch
  "Dispatch `event` to the global registry."
  [event-name event]
  (dispatch-in (global-registry) event-name event))

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

(defn summary-in
  "Return a summary map describing the extension registry state."
  [reg]
  {:extension-count (extension-count-in reg)
   :extensions      (extensions-in reg)
   :handler-count   (handler-count-in reg)
   :tool-names      (tool-names-in reg)
   :command-names   (command-names-in reg)})

;; ============================================================
;; Global singleton wrappers
;; ============================================================

(defn register-extension! [path]
  (register-extension-in! (global-registry) path))

(defn register-handler! [ext-path event-name handler-fn]
  (register-handler-in! (global-registry) ext-path event-name handler-fn))

(defn register-tool! [ext-path tool]
  (register-tool-in! (global-registry) ext-path tool))

(defn register-command! [ext-path cmd]
  (register-command-in! (global-registry) ext-path cmd))

(defn register-flag! [ext-path flag]
  (register-flag-in! (global-registry) ext-path flag))

(defn unregister-all! []
  (unregister-all-in! (global-registry)))

(defn summary []
  (summary-in (global-registry)))
