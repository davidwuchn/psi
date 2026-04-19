(ns psi.agent-session.dispatch-handlers.session-state
  "Pure state helpers: paths, telemetry constant, bounded-append,
   and all initialize-* / update-* functions used by handlers."
  (:require
   [clojure.java.io :as io]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session :as session-data-ns]
   [psi.agent-session.system-prompt]))

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
        baseline    (merge (session-data-ns/initial-session)
                           (select-keys current-sd [:model
                                                    :thinking-level
                                                    :base-system-prompt
                                                    :system-prompt
                                                    :prompt-mode
                                                    :nucleus-prelude-override
                                                    :cache-breakpoints
                                                    :system-prompt-build-opts
                                                    :prompt-component-selection
                                                    :developer-prompt
                                                    :developer-prompt-source
                                                    :auto-retry-enabled
                                                    :auto-compaction-enabled
                                                    :scoped-models
                                                    :skills
                                                    :prompt-templates
                                                    :prompt-contributions
                                                    :tool-defs
                                                    :extensions
                                                    :context-tokens
                                                    :context-window
                                                    :ui-type
                                                    :tool-output-overrides]))
        next-sd     (assoc baseline
                           :session-id new-session-id
                           :session-file session-file
                           :session-name session-name
                           :worktree-path worktree-path
                           :parent-session-id nil
                           :parent-session-path nil
                           :spawn-mode (or spawn-mode :new-root)
                           :model (:model baseline)
                           :thinking-level (or (:thinking-level baseline) :off)
                           :interrupt-pending false
                           :interrupt-requested-at nil
                           :steering-messages []
                           :follow-up-messages []
                           :retry-attempt 0
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
  [state parent-sd {:keys [child-session-id session-name system-prompt tool-defs thinking-level developer-prompt developer-prompt-source preloaded-messages cache-breakpoints prompt-component-selection]}]
  (let [normalized-selection (psi.agent-session.system-prompt/normalize-prompt-component-selection prompt-component-selection)
        tool-defs (if (some? prompt-component-selection)
                    (psi.agent-session.system-prompt/filter-tool-defs (or tool-defs (:tool-defs parent-sd)) normalized-selection)
                    (or tool-defs (:tool-defs parent-sd)))
        filtered-skills (if (some? prompt-component-selection)
                          (psi.agent-session.system-prompt/filter-skills (:skills parent-sd) normalized-selection)
                          (:skills parent-sd))
        build-opts (when (some? prompt-component-selection)
                     (-> (:system-prompt-build-opts parent-sd)
                         (assoc :selected-tools (mapv :name tool-defs))
                         (assoc :skills filtered-skills)
                         (assoc :include-preamble? (:include-preamble? normalized-selection))
                         (assoc :include-runtime-metadata? (:include-runtime-metadata? normalized-selection))
                         (assoc :include-context-files? (:include-context-files? normalized-selection))))
        computed-base-prompt (when build-opts
                               (psi.agent-session.system-prompt/build-system-prompt build-opts))
        child-sd (merge (session-data-ns/initial-session
                         {:worktree-path (:worktree-path parent-sd)})
                        {:session-id              child-session-id
                         :session-name            session-name
                         :spawn-mode              :agent
                         :parent-session-id       (:session-id parent-sd)
                         :system-prompt           (or system-prompt computed-base-prompt (:system-prompt parent-sd))
                         :base-system-prompt      (or system-prompt computed-base-prompt (:base-system-prompt parent-sd))
                         :developer-prompt        (or developer-prompt (:developer-prompt parent-sd))
                         :developer-prompt-source (or developer-prompt-source (:developer-prompt-source parent-sd))
                         :thinking-level          (or thinking-level :off)
                         :tool-defs               tool-defs
                         :skills                  filtered-skills
                         :system-prompt-build-opts build-opts
                         :cache-breakpoints       (or cache-breakpoints (:cache-breakpoints parent-sd))
                         :prompt-component-selection normalized-selection
                         :model                   (:model parent-sd)
                         :created-at              (java.time.Instant/now)})]
    (-> state
        (assoc-in (session-data-path child-session-id) child-sd)
        (assoc-in [:agent-session :sessions child-session-id :persistence]
                  {:journal     (vec (map persist/message-entry (or preloaded-messages [])))
                   :flush-state {:flushed? false :session-file nil}})
        (initialize-session-slots child-session-id []))))

(defn initialize-resumed-session-state
  [state current-sd {:keys [session-id _source-session-id session-path header entries model thinking-level]}]
  (let [session-name (some #(when (= :session-info (:kind %))
                              (get-in % [:data :name]))
                           (rseq (vec entries)))
        baseline     (merge (session-data-ns/initial-session)
                            (select-keys current-sd [:base-system-prompt
                                                     :system-prompt
                                                     :prompt-mode
                                                     :nucleus-prelude-override
                                                     :cache-breakpoints
                                                     :system-prompt-build-opts
                                                     :prompt-component-selection
                                                     :developer-prompt
                                                     :developer-prompt-source
                                                     :auto-retry-enabled
                                                     :auto-compaction-enabled
                                                     :scoped-models
                                                     :skills
                                                     :prompt-templates
                                                     :prompt-contributions
                                                     :tool-defs
                                                     :extensions
                                                     :context-tokens
                                                     :context-window
                                                     :ui-type
                                                     :tool-output-overrides]))
        next-sd      (assoc baseline
                            :session-id session-id
                            :session-file session-path
                            :session-name session-name
                            :worktree-path (:worktree-path header)
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
        baseline            (merge (session-data-ns/initial-session)
                                   (select-keys parent-sd [:model
                                                           :thinking-level
                                                           :prompt-mode
                                                           :nucleus-prelude-override
                                                           :cache-breakpoints
                                                           :developer-prompt
                                                           :developer-prompt-source
                                                           :auto-retry-enabled
                                                           :auto-compaction-enabled
                                                           :scoped-models
                                                           :skills
                                                           :prompt-templates
                                                           :prompt-contributions
                                                           :tool-defs
                                                           :extensions
                                                           :context-tokens
                                                           :context-window
                                                           :ui-type
                                                           :tool-output-overrides]))
        next-sd             (assoc baseline
                                   :session-id new-session-id
                                   :worktree-path (:worktree-path parent-sd)
                                   :parent-session-id parent-session-id
                                   :parent-session-path parent-session-file
                                   :session-file session-file
                                   :spawn-mode :fork-head)]
    ;; carry-runtime-handles must run before any pruning as active session may be ephemeral
    (cond-> (-> state
                (carry-runtime-handles parent-session-id new-session-id)
                (assoc-in (session-data-path new-session-id) next-sd)
                (initialize-session-slots new-session-id branch-entries))
      session-file
      (assoc-in (session-flush-state-path new-session-id) {:flushed?     true
                                                           :session-file (io/file session-file)}))))
