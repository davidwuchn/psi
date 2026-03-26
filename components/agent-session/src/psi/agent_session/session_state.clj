(ns psi.agent-session.session-state
  "Canonical session state infrastructure.
   Owns the state atom paths, read/write primitives, session-update mechanics,
   journal append, session registry helpers, and statechart phase queries.

   This is a leaf module — dispatch-handlers, session-lifecycle, mutations,
   and core all require it without creating cycles.

   Atom shape
   ──────────
   {:agent-session {:sessions {sid {:data {session-map...}
                                    :telemetry {...}
                                    :persistence {:journal [] :flush-state {...}}
                                    :turn {:ctx nil}}}}
    :runtime ...
    :background-jobs ...
    :ui ...
    :recursion ...
    :oauth ...}

   Session targeting
   ─────────────────
   Session scoping uses :target-session-id on ctx. Adapters (RPC, TUI) set
   this before calling into core. There is no shared current-session concept."
  (:require
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.statechart :as sc]))

;;; Active session resolution

(defn active-session-id-in
  "Return the current session id for ctx.
  Reads :target-session-id from ctx. Adapters must set this before calling core."
  [ctx]
  (:target-session-id ctx))

(defn retarget-ctx
  "Return ctx scoped to explicit session-id."
  [ctx session-id]
  (assoc ctx :target-session-id session-id))

;;; Per-session runtime handle accessors

(defn agent-ctx-in
  "Return the agent-core context for the active session."
  [ctx]
  (let [sid (active-session-id-in ctx)]
    (get-in @(:state* ctx) [:agent-session :sessions sid :agent-ctx])))

(defn sc-session-id-in
  "Return the statechart session id for the active session."
  [ctx]
  (let [sid (active-session-id-in ctx)]
    (get-in @(:state* ctx) [:agent-session :sessions sid :sc-session-id])))

;;; State path builders

(defn- session-data-path
  "Build the path to session data for a given session-id."
  [sid]
  [:agent-session :sessions sid :data])

(defn- session-telemetry-path
  "Build the path to a telemetry key within a session."
  [sid k]
  [:agent-session :sessions sid :telemetry k])

(defn- session-journal-path
  "Build the path to the journal within a session."
  [sid]
  [:agent-session :sessions sid :persistence :journal])

(defn- session-flush-state-path
  "Build the path to flush-state within a session."
  [sid]
  [:agent-session :sessions sid :persistence :flush-state])

(defn- session-turn-ctx-path
  "Build the path to turn ctx within a session."
  [sid]
  [:agent-session :sessions sid :turn :ctx])

(def ^:private nrepl-runtime-path [:runtime :nrepl])
(def ^:private background-jobs-path [:background-jobs :store])
(def ^:private ui-state-path [:ui :extension-ui])
(def ^:private recursion-state-path [:recursion])
(def ^:private oauth-state-path [:oauth])
(def ^:private rpc-trace-path [:runtime :rpc-trace])

(defn state-path
  "Return the canonical root-state path vector for the named state key.
   Session-scoped keys (:session-data, :provider-error-replies, :journal,
   :flush-state, :turn-ctx, :tool-output-stats, :tool-call-attempts,
   :tool-lifecycle-events, :provider-requests, :provider-replies) require
   a session-id `sid` and return nil when none is provided.
   Non-session keys work with one arg.
   Returns nil for unknown keys."
  ([k] (state-path k nil))
  ([k sid]
   (case k
     :session-data (when sid (session-data-path sid))
     :provider-error-replies (when sid (conj (session-data-path sid) :provider-error-replies))
     :tool-output-stats (when sid (session-telemetry-path sid :tool-output-stats))
     :tool-call-attempts (when sid (session-telemetry-path sid :tool-call-attempts))
     :tool-lifecycle-events (when sid (session-telemetry-path sid :tool-lifecycle-events))
     :provider-requests (when sid (session-telemetry-path sid :provider-requests))
     :provider-replies (when sid (session-telemetry-path sid :provider-replies))
     :nrepl-runtime nrepl-runtime-path
     :journal (when sid (session-journal-path sid))
     :flush-state (when sid (session-flush-state-path sid))
     :turn-ctx (when sid (session-turn-ctx-path sid))
     :background-jobs background-jobs-path
     :ui-state ui-state-path
     :recursion recursion-state-path
     :oauth oauth-state-path
     :rpc-trace rpc-trace-path
     nil)))

;;; Private state primitives

(defn- get-state-in*
  [ctx path]
  (when-let [state* (:state* ctx)]
    (get-in @state* path)))

(defn- assoc-state-in!*
  [ctx path value]
  (swap! (:state* ctx) assoc-in path value))

(defn- update-state-in!*
  [ctx path f & args]
  (apply swap! (:state* ctx) update-in path f args))

;;; Public state accessors

(defn get-state-value-in
  "Low-level canonical root-state read helper.
   Prefer named session APIs or dispatch-routed mutations for production flows.
   Retained as intentional infrastructure for resolvers, compatibility views,
   and focused test/harness usage."
  [ctx path]
  (get-state-in* ctx path))

(defn assoc-state-value-in!
  "Low-level canonical root-state write helper.
   Prefer named session APIs or dispatch-routed mutations in production code.
   Retained primarily for focused test/harness setup and deliberate low-level
   infrastructure boundaries."
  [ctx path value]
  (assoc-state-in!* ctx path value))

(defn update-state-value-in!
  "Low-level canonical root-state update helper.
   Prefer named session APIs or dispatch-routed mutations in production code.
   Retained primarily for focused test/harness setup and deliberate low-level
   infrastructure boundaries."
  [ctx path f & args]
  (apply update-state-in!* ctx path f args))

(defn get-session-data-in
  "Return the AgentSession data map from `ctx`.
   Without session-id, returns the active session's data.
   With session-id, returns that session's data."
  ([ctx]
   (let [sid (active-session-id-in ctx)]
     (get-state-in* ctx (session-data-path sid))))
  ([ctx sid]
   (get-state-in* ctx (session-data-path sid))))

;;; Session update mechanics

(defn session-update
  "Wrap a session-data transform into a root-state transform for session `sid`.
   Use in dispatch handler results:
     {:root-state-update (session-update sid #(assoc % :session-name name))}"
  [sid f]
  (fn [state]
    (update-in state (session-data-path sid) f)))

(defn apply-root-state-update-in!
  "Apply a pure root-state update function to canonical state in `ctx`.
   `f` is (fn [root-state] → new-root-state). Used by the dispatch pipeline
   for pure handlers that intentionally operate on non-session root slices."
  [ctx f]
  (swap! (:state* ctx) f)
  @(:state* ctx))

;;; Effective CWD

(defn effective-cwd-in
  "Return the effective working directory for the active session.
   Prefers session-scoped :worktree-path over context :cwd."
  [ctx]
  (or (:worktree-path (get-session-data-in ctx))
      (:cwd ctx)))

;;; Journal

(defn- journal-append!
  "Append `entry` to the journal and conditionally persist to disk."
  [ctx entry]
  (persist/append-entry-in! ctx entry)
  (when (:persist? ctx)
    (let [sd (get-session-data-in ctx)
          session-file (:session-file sd)]
      (when session-file
        (persist/persist-entry-in!
         ctx
         (:session-id sd)
         (effective-cwd-in ctx)
         (:parent-session-id sd)
         session-file)))))

(defn journal-append-in!
  "Append `entry` to the journal and persist.
   Use `persist/message-entry` et al. to build entries."
  [ctx entry]
  (journal-append! ctx entry))

;;; Session registry helpers

(defn get-sessions-map-in
  "Return the sessions map {sid -> session-entry} from the atom."
  [ctx]
  (get-state-in* ctx [:agent-session :sessions]))

(defn list-context-sessions-in
  "Return session metadata entries sorted by updated-at."
  [ctx]
  (let [sessions (get-sessions-map-in ctx)]
    (->> (vals sessions)
         (map (fn [{:keys [data]}]
                (select-keys data [:session-id :session-file :session-name
                                   :worktree-path :parent-session-id
                                   :parent-session-path])))
         (sort-by :session-id)
         vec)))

;; set-context-active-session-in! removed — adapters own focus locally.
;; Session targeting is via :target-session-id on ctx.

;;; Statechart phase queries

(defn sc-phase-in
  "Return the active statechart phase for `ctx`."
  [ctx]
  (sc/sc-phase (:sc-env ctx) (sc-session-id-in ctx)))

(defn idle-in?
  "True when the session phase is :idle."
  [ctx]
  (= :idle (sc-phase-in ctx)))

;;; Prompt contribution helpers

(defn sorted-prompt-contributions
  "Return prompt contributions sorted by deterministic render order."
  [coll]
  (->> (or coll [])
       (filter map?)
       (sort-by (fn [{:keys [priority ext-path id]}]
                  [(or priority 1000)
                   (or ext-path "")
                   (or id "")]))
       vec))

(defn list-prompt-contributions-in
  "Return prompt contributions sorted by deterministic render order."
  [ctx]
  (sorted-prompt-contributions (:prompt-contributions (get-session-data-in ctx))))

;;; UI state compatibility

(defn atom-view-in
  "Return an atom-like view over canonical root state at `path`.
   Compatibility boundary for integration code that still expects swap!/deref
   semantics while canonical state remains rooted in :state*."
  [ctx path]
  (let [store (get-state-in* ctx path)]
    (when (some? store)
      (reify
        clojure.lang.IDeref
        (deref [_] (get-state-in* ctx path))
        clojure.lang.IAtom
        (compareAndSet [_ oldv newv]
          (let [curv (get-state-in* ctx path)]
            (if (= curv oldv)
              (do
                (assoc-state-in!* ctx path newv)
                true)
              false)))
        (reset [_ newv]
          (assoc-state-in!* ctx path newv)
          newv)
        (swap [_ f]
          (let [newv (f (get-state-in* ctx path))]
            (assoc-state-in!* ctx path newv)
            newv))
        (swap [_ f a]
          (let [newv (f (get-state-in* ctx path) a)]
            (assoc-state-in!* ctx path newv)
            newv))
        (swap [_ f a b]
          (let [newv (f (get-state-in* ctx path) a b)]
            (assoc-state-in!* ctx path newv)
            newv))
        (swap [_ f a b xs]
          (let [newv (apply f (get-state-in* ctx path) a b xs)]
            (assoc-state-in!* ctx path newv)
            newv))))))
