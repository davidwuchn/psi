(ns psi.agent-session.dispatch-handlers.session-state
  "Pure state helpers: paths, telemetry constant, bounded-append,
   and all initialize-* / update-* functions used by handlers."
  (:require
   [clojure.java.io :as io]
   [psi.agent-session.session :as session-data-ns]))

;;; Paths

(defn session-data-path        [sid]   [:agent-session :sessions sid :data])
(defn session-journal-path     [sid]   [:agent-session :sessions sid :persistence :journal])
(defn session-flush-state-path [sid]   [:agent-session :sessions sid :persistence :flush-state])
(defn session-telemetry-path   [sid k] [:agent-session :sessions sid :telemetry k])
(defn session-turn-ctx-path    [sid]   [:agent-session :sessions sid :turn :ctx])

;;; Telemetry

(def initial-telemetry
  {:tool-output-stats {:calls      []
                       :aggregates {:total-context-bytes  0
                                    :by-tool              {}
                                    :limit-hits-by-tool   {}}}
   :tool-call-attempts    []
   :tool-lifecycle-events []
   :provider-requests     []
   :provider-replies      []})

;;; Utilities

(defn bounded-append
  "Append item to coll (coercing to vector), trimming to at most limit entries."
  [limit coll item]
  (let [v (conj (vec (or coll [])) item)
        n (count v)]
    (if (> n limit) (subvec v (- n limit)) v)))

;;; Session slot initialisation

(defn initialize-session-slots
  "Set journal, telemetry, and turn slots for sid.
   journal-entries is the initial journal vector ([] for new, (vec entries) for resume)."
  [state sid journal-entries]
  (-> state
      (assoc-in (session-journal-path sid) journal-entries)
      (assoc-in [:agent-session :sessions sid :telemetry] initial-telemetry)
      (assoc-in [:agent-session :sessions sid :turn] {:ctx nil})))

;;; Runtime projection updaters

(defn update-runtime-rpc-trace-state [state enabled? file]
  (assoc-in state [:runtime :rpc-trace] {:enabled? enabled? :file file}))

(defn update-nrepl-runtime-state [state runtime]
  (assoc-in state [:runtime :nrepl] runtime))

(defn update-oauth-projection-state [state oauth]
  (assoc-in state [:oauth] oauth))

(defn update-recursion-projection-state [state recursion-state]
  (assoc-in state [:recursion] recursion-state))

(defn update-background-jobs-store-state [state update-fn]
  (if (fn? update-fn)
    (update-in state [:background-jobs :store] update-fn)
    state))

;;; Session initialisation

(defn initialize-resume-missing-state
  [state current-sd session-path]
  (let [next-sd (assoc current-sd :session-file session-path)
        sid     (:session-id next-sd)]
    (-> state
        (assoc-in (session-data-path sid) next-sd)
        (assoc-in (session-flush-state-path sid) {:flushed?     false
                                                  :session-file (io/file session-path)})
        (initialize-session-slots sid []))))

(defn- carry-runtime-handles
  "Copy :agent-ctx and :sc-session-id from source-session-id to new-session-id."
  [state source-session-id new-session-id]
  (let [agent-ctx (get-in state [:agent-session :sessions source-session-id :agent-ctx])
        sc-sid    (get-in state [:agent-session :sessions source-session-id :sc-session-id])]
    (-> state
        (assoc-in [:agent-session :sessions new-session-id :agent-ctx] agent-ctx)
        (assoc-in [:agent-session :sessions new-session-id :sc-session-id] sc-sid))))

(defn initialize-new-session-state
  [state current-sd {:keys [new-session-id worktree-path session-name spawn-mode session-file]}]
  (let [_source-sid (:session-id current-sd)
        next-sd     (assoc current-sd
                           :session-id new-session-id
                           :session-file session-file
                           :session-name session-name
                           :worktree-path worktree-path
                           :parent-session-id nil
                           :parent-session-path nil
                           :spawn-mode (or spawn-mode :new-root)
                           :interrupt-pending false
                           :interrupt-requested-at nil
                           :steering-messages []
                           :follow-up-messages []
                           :retry-attempt 0
                           :startup-prompts []
                           :startup-bootstrap-completed? false
                           :startup-bootstrap-started-at nil
                           :startup-bootstrap-completed-at nil
                           :startup-message-ids []
                           :created-at (java.time.Instant/now))]
    (cond-> (-> state
                (assoc-in (session-data-path new-session-id) next-sd)
                (initialize-session-slots new-session-id []))
      session-file
      (assoc-in (session-flush-state-path new-session-id) {:flushed?     false
                                                           :session-file (io/file session-file)}))))

(defn initialize-child-session-state
  "Add a child session entry without switching active-session-id.
   The child is a lightweight session for agent execution."
  [state parent-sd {:keys [child-session-id session-name system-prompt tool-defs thinking-level]}]
  (let [tool-defs (or tool-defs (:tool-defs parent-sd))
        child-sd (merge (session-data-ns/initial-session
                         {:worktree-path (:worktree-path parent-sd)})
                        {:session-id         child-session-id
                         :session-name       session-name
                         :spawn-mode         :agent
                         :parent-session-id  (:session-id parent-sd)
                         :system-prompt      (or system-prompt (:system-prompt parent-sd))
                         :base-system-prompt (or system-prompt (:base-system-prompt parent-sd))
                         :thinking-level     (or thinking-level :off)
                         :tool-defs          tool-defs
                         :model              (:model parent-sd)
                         :created-at         (java.time.Instant/now)})]
    (-> state
        (assoc-in (session-data-path child-session-id) child-sd)
        (assoc-in [:agent-session :sessions child-session-id :persistence]
                  {:journal     []
                   :flush-state {:flushed? false :session-file nil}})
        (initialize-session-slots child-session-id [])
        (assoc-in [:agent-session :sessions child-session-id :sc-session-id]
                  (java.util.UUID/randomUUID)))))

(defn initialize-resumed-session-state
  [state current-sd {:keys [session-id _source-session-id session-path header entries model thinking-level]}]
  (let [session-name (some #(when (= :session-info (:kind %))
                              (get-in % [:data :name]))
                           (rseq (vec entries)))
        next-sd      (assoc current-sd
                            :session-id session-id
                            :session-file session-path
                            :session-name session-name
                            :worktree-path (or (:worktree-path header) (:cwd header))
                            :parent-session-id (:parent-session-id header)
                            :parent-session-path (:parent-session header)
                            :interrupt-pending false
                            :interrupt-requested-at nil
                            :model model
                            :thinking-level thinking-level)]
    (-> state
        (assoc-in (session-flush-state-path session-id) {:flushed?     true
                                                         :session-file (io/file session-path)})
        (assoc-in (session-data-path session-id) next-sd)
        (initialize-session-slots session-id (vec entries)))))

(defn initialize-forked-session-state
  [state parent-sd {:keys [new-session-id branch-entries session-file]}]
  (let [parent-session-id   (:session-id parent-sd)
        parent-session-file (:session-file parent-sd)
        next-sd             (assoc parent-sd
                                   :session-id new-session-id
                                   :parent-session-id parent-session-id
                                   :parent-session-path parent-session-file
                                   :session-file session-file
                                   :startup-prompts []
                                   :startup-bootstrap-completed? false
                                   :startup-bootstrap-started-at nil
                                   :startup-bootstrap-completed-at nil
                                   :startup-message-ids [])]
    ;; carry-runtime-handles must run before any pruning as active session may be ephemeral
    (cond-> (-> state
                (carry-runtime-handles parent-session-id new-session-id)
                (assoc-in (session-data-path new-session-id) next-sd)
                (initialize-session-slots new-session-id branch-entries))
      session-file
      (assoc-in (session-flush-state-path new-session-id) {:flushed?     true
                                                           :session-file (io/file session-file)}))))
