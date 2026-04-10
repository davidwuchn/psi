(ns psi.tui.app-test
  "Tests for the charm.clj Elm Architecture TUI.
   Exercises init/update/view as pure functions — no terminal needed.
   Includes a JLine integration smoke test for terminal + keymap creation."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.components.text-input :as text-input]
   [charm.core :as charm]
   [charm.input.keymap :as keymap]
   [charm.message :as msg]
   [psi.agent-session.persistence :as persist]
   [psi.app-runtime.projections :as projections]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]
   [psi.ui.state :as ui-state])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

;;;; Helpers

(defn- default-dispatch-fn
  "Minimal dispatch-fn for tests: handles /quit, /resume, /new.
   Returns result maps matching the commands.clj contract."
  [text]
  (case text
    ("/quit" "/exit")  {:type :quit}
    "/resume"          {:type :resume}
    "/tree"            {:type :tree-open}
    "/new"             {:type :new-session :message "[New session started]"}
    "/status"          {:type :text :message "test status"}
    "/help"            {:type :text :message "test help"}
    "/worktree"        {:type :text :message "test worktree"}
    "/jobs"            {:type :text :message "test jobs"}
    "/job"             {:type :text :message "test job"}
    "/cancel-job"      {:type :text :message "test cancel-job"}
    nil))

(defn- init-state
  "Create a fresh state from make-init.
   Accepts legacy :ui-state* atom in opts; wires it into ui-read-fn + ui-dispatch-fn."
  ([] (init-state "test-model" {}))
  ([model-name] (init-state model-name {}))
  ([model-name opts]
   (let [ui-atom      (:ui-state* opts)
         ui-read-fn*  (:ui-read-fn opts)
         opts'        (dissoc opts :ui-state* :ui-read-fn)
         ui-read-fn   (or ui-read-fn*
                          (when ui-atom
                            (fn []
                              (projections/extension-ui-snapshot-from-state @ui-atom))))
         ui-disp-fn   (when ui-atom
                        (fn [event-type payload]
                          (case event-type
                            :session/ui-set-tools-expanded
                            (ui-state/set-tools-expanded! ui-atom (:expanded? payload))
                            nil)))
         init-fn      (app/make-init model-name nil ui-read-fn ui-disp-fn
                                     (merge {:dispatch-fn default-dispatch-fn} opts'))
         [state _cmd] (init-fn)]
     state)))

(defn- type-text
  "Type all chars of s into the update loop, returning new state."
  [update-fn state s]
  (reduce (fn [st ch]
            (first (update-fn st (msg/key-press (str ch)))))
          state
          s))

(defn- stub-agent-fn
  "A stub run-agent-fn! that immediately puts a done result on the queue."
  [response-text]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :done
                 :result {:role    "assistant"
                          :content [{:type :text :text response-text}]}})))

(defn- selector-session-item
  [session-id display-name worktree-path & {:keys [parent-id is-active is-streaming]}]
  {:item/id [:session session-id]
   :item/kind :session
   :item/session-id session-id
   :item/parent-id (when parent-id [:session parent-id])
   :item/display-name display-name
   :item/is-active (boolean is-active)
   :item/is-streaming (boolean is-streaming)
   :item/worktree-path worktree-path})

(defn- selector-fork-item
  [session-id entry-id display-name & {:keys [parent-id]}]
  {:item/id [:fork-point entry-id]
   :item/kind :fork-point
   :item/session-id session-id
   :item/entry-id entry-id
   :item/parent-id (vector :session (or parent-id session-id))
   :item/display-name display-name})

(defn- make-session-selector-fn
  [active-id items]
  (let [selector {:selector/kind :context-session
                  :selector/active-item-id (some-> active-id (vector :session))
                  :selector/items items}]
    (fn []
      (ui-actions/context-session-action selector))))

(defn- submit-text
  "Type s then press enter; if submission starts streaming, advance once to idle."
  [update-fn state s]
  (let [typed          (type-text update-fn state s)
        [submitted _]  (update-fn typed (msg/key-press :enter))]
    (if (= :streaming (:phase submitted))
      (first (update-fn submitted {:type :agent-result
                                   :result {:content [{:type :text :text "ok"}]}}))
      submitted)))

(defn- error-agent-fn
  "A stub run-agent-fn! that immediately puts an error on the queue."
  [error-msg]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :error :message error-msg})))

;;;; Init

(deftest init-test
  (testing "init returns idle state and starts queue polling cmd"
    (let [init-fn (app/make-init "test-model")
          [state cmd] (init-fn)]
      (is (= :idle (:phase state)))
      (is (some? cmd))
      (is (empty? (:messages state)))
      (is (nil? (:error state)))
      (is (= "test-model" (:model-name state)))))

  (testing "init includes explicit prompt-input state shape"
    (let [[state _] ((app/make-init "test-model"))
          input-state (:prompt-input-state state)]
      (is (= {:prefix ""
              :candidates []
              :selected-index 0
              :context nil
              :trigger-mode nil}
             (:autocomplete input-state)))
      (is (= {:entries []
              :browse-index nil
              :max-entries 100}
             (:history input-state)))
      (is (= {:last-ctrl-c-ms nil
              :last-escape-ms nil}
             (:timing input-state))))))

;;;; Update — key input

(deftest typing-updates-input-test
  (testing "printable keys update the text input"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "h"))
          [s2 _]    (update-fn s1 (msg/key-press "i"))]
      (is (= "hi" (text-input/value (:input s2)))))))

(deftest backspace-test
  (testing "backspace removes a character"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "a"))
          [s2 _]    (update-fn s1 (msg/key-press "b"))
          [s3 _]    (update-fn s2 (msg/key-press :backspace))]
      (is (= "a" (text-input/value (:input s3)))))))

(deftest keyword-space-inserts-immediately-test
  (testing "keyword :space input inserts a space immediately"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :input (charm/text-input-set-value (:input (init-state)) "hi"))
          [s1 _]    (update-fn state (msg/key-press :space))]
      (is (= "hi " (text-input/value (:input s1)))))))

(deftest alt-backspace-deletes-word-test
  (testing "alt+backspace deletes previous word"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (-> (init-state)
                        (assoc :input (charm/text-input-set-value (:input (init-state)) "hello world")))
          [s1 _]    (update-fn state (msg/key-press :backspace :alt true))]
      (is (= "hello " (text-input/value (:input s1)))))))

(deftest modifier-enter-adds-newline-test
  (testing "shift+enter inserts newline and does not submit"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (assoc (init-state) :input (charm/text-input-set-value (:input (init-state)) "line1"))
          [s1 _]    (update-fn state (msg/key-press :enter :shift true))]
      (is (= :idle (:phase s1)))
      (is (= "line1\n" (text-input/value (:input s1))))
      (is (nil? @submitted)))))

(deftest backslash-enter-adds-newline-test
  (testing "trailing backslash + enter inserts newline continuation"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (assoc (init-state) :input (charm/text-input-set-value (:input (init-state)) "line1\\"))
          [s1 _]    (update-fn state (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (= "line1\n" (text-input/value (:input s1))))
      (is (nil? @submitted)))))

;;;; Update — submit

(deftest submit-starts-streaming-test
  (testing "enter with text transitions to :streaming"
    (let [submitted (atom nil)
          agent-fn  (fn [text queue]
                      (reset! submitted text)
                      (.put ^LinkedBlockingQueue queue
                            {:kind :done
                             :result {:role "assistant"
                                      :content [{:type :text :text "ok"}]}}))
          update-fn (app/make-update agent-fn)
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "g"))
          [s2 _]    (update-fn s1 (msg/key-press "o"))
          [s3 cmd]  (update-fn s2 (msg/key-press :enter))]
      (is (= :streaming (:phase s3)))
      (is (some? cmd))
      (is (= "go" @submitted))
      ;; User message recorded
      (is (= 1 (count (:messages s3))))
      (is (= :user (:role (first (:messages s3))))))))

(deftest submit-blank-does-nothing-test
  (testing "enter with empty input stays idle"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 cmd]  (update-fn state (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (nil? cmd)))))

;;;; Update — /resume session selector

(deftest resume-command-opens-selector-test
  (testing "/resume enters :selecting-session phase"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions
                  (fn [_dir]
                    [{:path "/tmp/psi-test/a.ndedn"
                      :name "Session A"
                      :first-message "hello"
                      :message-count 3
                      :modified (java.time.Instant/now)
                      :cwd "/tmp/psi-test"
                      :worktree-path "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state "test-model" {:cwd "/tmp/psi-test"})
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))]
        (is (= :selecting-session (:phase s1)))
        (is (some? (:session-selector s1)))))))

(deftest resume-selection-restores-messages-test
  (testing "selecting a session calls resume-fn! and loads returned messages"
    (let [selected-path (atom nil)
          restored      {:messages [{:role :user :text "restored user"}
                                    {:role :assistant :text "restored assistant"}]
                         :tool-calls {"t1" {:name "read"
                                            :args "{\"path\":\"foo.txt\"}"
                                            :status :success
                                            :result "ok"
                                            :is-error false
                                            :expanded? false}}
                         :tool-order ["t1"]}]
      (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                    persist/list-sessions
                    (fn [_dir]
                      [{:path "/tmp/psi-test/a.ndedn"
                        :name "Session A"
                        :first-message "hello"
                        :message-count 3
                        :modified (java.time.Instant/now)
                        :cwd "/tmp/psi-test"
                        :worktree-path "/tmp/psi-test"}])]
        (let [update-fn (app/make-update (stub-agent-fn ""))
              state     (init-state "test-model"
                                    {:cwd "/tmp/psi-test"
                                     :resume-fn! (fn [path]
                                                   (reset! selected-path path)
                                                   restored)})
              typed     (type-text update-fn state "/resume")
              [s1 _]    (update-fn typed (msg/key-press :enter))
              [s2 _]    (update-fn s1 (msg/key-press :enter))]
          (is (= :idle (:phase s2)))
          (is (= (:messages restored) (:messages s2)))
          (is (= (:tool-order restored) (:tool-order s2)))
          (is (= "ok" (get-in s2 [:tool-calls "t1" :result])))
          (is (= "/tmp/psi-test/a.ndedn" @selected-path))
          (is (= "/tmp/psi-test/a.ndedn" (:current-session-file s2))))))))

(deftest resume-selector-escape-cancels-test
  (testing "escape from selector returns to idle without changing messages"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions (fn [_dir] [])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (assoc (init-state "test-model" {:cwd "/tmp/psi-test"})
                             :messages [{:role :assistant :text "keep me"}])
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))
            [s2 _]    (update-fn s1 (msg/key-press :escape))]
        (is (= :idle (:phase s2)))
        (is (= [{:role :assistant :text "keep me"}] (:messages s2)))))))

(deftest resume-selector-keyword-key-ignored-test
  (testing "non-printable keyword keys in selector are ignored (no exception)"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions
                  (fn [_dir]
                    [{:path "/tmp/psi-test/a.ndedn"
                      :name "Session A"
                      :first-message "hello"
                      :message-count 3
                      :modified (java.time.Instant/now)
                      :cwd "/tmp/psi-test"
                      :worktree-path "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state "test-model" {:cwd "/tmp/psi-test"})
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))
            [s2 _]    (update-fn s1 (msg/key-press :left))]
        (is (= :selecting-session (:phase s2)))
        (is (= "" (get-in s2 [:session-selector :search])))))))

(deftest resume-selector-backspace-keyword-test
  (testing "keyword backspace edits selector search"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions
                  (fn [_dir]
                    [{:path "/tmp/psi-test/a.ndedn"
                      :name "Session A"
                      :first-message "hello"
                      :message-count 3
                      :modified (java.time.Instant/now)
                      :cwd "/tmp/psi-test"
                      :worktree-path "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state "test-model" {:cwd "/tmp/psi-test"})
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))
            [s2 _]    (update-fn s1 (msg/key-press "a"))
            [s3 _]    (update-fn s2 (msg/key-press :backspace))]
        (is (= "a" (get-in s2 [:session-selector :search])))
        (is (= "" (get-in s3 [:session-selector :search])))))))

(deftest tree-command-opens-tree-selector-test
  (testing "/tree enters selector in :tree mode"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)])
          [state _]   ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                                  :cwd "/tmp/psi-test"
                                                                  :focus-session-id "s1"
                                                                  :session-selector-fn selector-fn}))
          typed      (type-text update-fn state "/tree")
          [s1 _]     (update-fn typed (msg/key-press :enter))]
      (is (= :selecting-session (:phase s1)))
      (is (= :tree (:session-selector-mode s1)))
      (is (= :select-session (get-in s1 [:session-selector :ui/action :ui/action-name])))
      (is (= "s1" (get-in s1 [:session-selector :active-session-id]))))))

(deftest tree-direct-switch-restores-state-test
  (testing "/tree <id> dispatch result triggers switch-session callback"
    (let [switched-id (atom nil)
          dispatch-fn (fn [text]
                        (when (= text "/tree s2")
                          {:type :tree-switch :session-id "s2"}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model"
                                  {:dispatch-fn dispatch-fn
                                   :switch-session-fn! (fn [sid]
                                                         (reset! switched-id sid)
                                                         {:nav/session-id sid
                                                          :nav/rehydration {:messages [{:role :assistant :text "switched"}]
                                                                            :tool-calls {"t1" {:name "read" :status :success :result "ok"}}
                                                                            :tool-order ["t1"]}})})
          typed       (type-text update-fn state "/tree s2")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= "s2" @switched-id))
      (is (= :idle (:phase s1)))
      (is (= "switched" (get-in s1 [:messages 0 :text])))
      (is (= ["t1"] (:tool-order s1)))
      (is (:force-clear? s1)))))

(deftest tree-selector-enter-switches-via-callback-test
  (testing "enter in :tree selector switches highlighted session"
    (let [switched-id (atom nil)
          update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")])
          [state _]   ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                                  :cwd "/tmp/psi-test"
                                                                  :focus-session-id "s1"
                                                                  :session-selector-fn selector-fn
                                                                  :switch-session-fn! (fn [sid]
                                                                                        (reset! switched-id sid)
                                                                                        {:nav/session-id sid
                                                                                         :nav/rehydration {:messages [{:role :assistant
                                                                                                                       :text (str "switched " sid)}]
                                                                                                           :tool-calls {}
                                                                                                           :tool-order []}})}))
          typed       (type-text update-fn state "/tree")
          [s1 _]      (update-fn typed (msg/key-press :enter))
          [s2 _]      (update-fn s1 (msg/key-press :down))
          [s3 _]      (update-fn s2 (msg/key-press :enter))]
      (is (= "s2" @switched-id))
      (is (= :idle (:phase s3)))
      (is (= "switched s2" (get-in s3 [:messages 0 :text])))
      (is (:force-clear? s3)))))

(deftest tree-selector-view-renders-hierarchy-and-active-badge-test
  (testing "tree selector view renders parent-child connectors and active marker"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")])
          [state _]   ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                                  :cwd "/tmp/psi-test"
                                                                  :focus-session-id "s1"
                                                                  :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          root-idx  (.indexOf plain "Root")
          child-idx (.indexOf plain "└─ Child")]
      (is (str/includes? plain "Session Tree"))
      (is (str/includes? plain "● Root"))
      (is (str/includes? plain "/tmp/psi-test/root"))
      (is (str/includes? plain "/tmp/psi-test/child"))
      (is (str/includes? plain "[active]"))
      (is (str/includes? plain "└─ Child"))
      (is (and (>= root-idx 0)
               (>= child-idx 0)
               (< root-idx child-idx))))))

(deftest tree-selector-view-aligns-status-columns-test
  (testing "tree selector status badges align across rows"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1" :is-streaming true)])
          [state _]   ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                                  :cwd "/tmp/psi-test"
                                                                  :focus-session-id "s1"
                                                                  :session-selector-fn selector-fn}))
          typed      (type-text update-fn state "/tree")
          [s1 _]     (update-fn typed (msg/key-press :enter))
          plain      (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          lines      (str/split-lines plain)
          row-root       (first (filter #(str/includes? % "Root") lines))
          row-child      (first (filter #(str/includes? % "Child") lines))
          active-col     (when row-root (.indexOf row-root "[active]"))
          stream-col     (when row-child (.indexOf row-child "[stream]"))
          root-id-col    (when row-root (.indexOf row-root "s1"))
          child-id-col   (when row-child (.indexOf row-child "s2"))
          expected-stream-col (when (number? active-col)
                                (+ active-col (count "[active]") 1))]
      (is (string? row-root))
      (is (string? row-child))
      (is (number? active-col))
      (is (number? stream-col))
      ;; stream badge sits in fixed slot after active slot
      (is (= expected-stream-col stream-col))
      ;; session-id suffix column stays aligned row-to-row
      (is (= root-id-col child-id-col)))))

(deftest tree-selector-view-prefers-display-name-test
  (testing "tree selector uses derived display-name for live sessions"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Investigate failing tests" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Refactor selector" "/tmp/psi-test/child" :parent-id "s1")])
          [state _]   ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                                  :cwd "/tmp/psi-test"
                                                                  :focus-session-id "s1"
                                                                  :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))]
      (is (str/includes? plain "Investigate failing tests"))
      (is (str/includes? plain "Refactor selector"))
      (is (not (str/includes? plain "session-s1")))
      (is (not (str/includes? plain "session-s2"))))))

(deftest tree-selector-view-renders-current-session-fork-points-test
  (testing "tree selector renders abbreviated user prompts under the active session"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")
                        (selector-fork-item "s1" "e1" "Investigate prompt lifecycle convergence")])
          [state _]   ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                                  :cwd "/tmp/psi-test"
                                                                  :focus-session-id "s1"
                                                                  :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          root-idx  (.indexOf plain "● Root")
          child-idx (.indexOf plain "└─ Child")
          fork-idx  (.indexOf plain "⎇ Investigate prompt lifecycle convergence")]
      (is (str/includes? plain "Root"))
      (is (str/includes? plain "Investigate prompt lifecycle convergence"))
      (is (str/includes? plain "fork"))
      (is (not (str/includes? plain "⎇ /tree")))
      (is (and (>= root-idx 0)
               (>= child-idx 0)
               (>= fork-idx 0)
               (< root-idx child-idx)
               (< child-idx fork-idx))))))

(deftest tree-selector-view-renders-shared-selector-order-test
  (testing "tree selector can consume shared app-runtime selector order directly"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          selector-fn (fn []
                        (ui-actions/context-session-action
                         {:selector/kind :context-session
                          :selector/active-item-id [:session "s1"]
                          :selector/items [{:item/id [:session "s1"]
                                            :item/kind :session
                                            :item/session-id "s1"
                                            :item/display-name "Root"
                                            :item/is-active true
                                            :item/worktree-path "/tmp/psi-test/root"}
                                           {:item/id [:session "s2"]
                                            :item/kind :session
                                            :item/session-id "s2"
                                            :item/parent-id [:session "s1"]
                                            :item/display-name "Child"
                                            :item/worktree-path "/tmp/psi-test/child"}
                                           {:item/id [:fork-point "e1"]
                                            :item/kind :fork-point
                                            :item/session-id "s1"
                                            :item/entry-id "e1"
                                            :item/parent-id [:session "s1"]
                                            :item/display-name "Branch from here"}]}))
          [state _] ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                               :cwd "/tmp/psi-test"
                                                               :focus-session-id "s1"
                                                               :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          lines     (vec (str/split-lines plain))
          root-idx  (first (keep-indexed (fn [i line] (when (str/includes? line "Root") i)) lines))
          child-idx (first (keep-indexed (fn [i line] (when (str/includes? line "Child") i)) lines))
          fork-idx  (first (keep-indexed (fn [i line] (when (str/includes? line "Branch from here") i)) lines))]
      (is (and (some? root-idx)
               (some? child-idx)
               (some? fork-idx)
               (< root-idx child-idx)
               (< child-idx fork-idx))))))

(deftest tree-selector-enter-on-fork-point-forks-and-selects-fork-test
  (testing "enter on a prompt row forks from its entry and selects the fork"
    (let [forked-entry-id (atom nil)
          update-fn       (app/make-update (stub-agent-fn ""))
          selector-fn     (make-session-selector-fn
                           "s1"
                           [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                            (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")
                            (selector-fork-item "s1" "e1" "Branch from here")])
          [state _]       ((app/make-init "test-model" nil nil nil {:dispatch-fn default-dispatch-fn
                                                                      :cwd "/tmp/psi-test"
                                                                      :focus-session-id "s1"
                                                                      :session-selector-fn selector-fn
                                                                      :fork-session-fn! (fn [entry-id]
                                                                                          (reset! forked-entry-id entry-id)
                                                                                          {:nav/session-id "s3"
                                                                                           :nav/rehydration {:messages [{:role :user
                                                                                                                        :text "Branch from here"}
                                                                                                                       {:role :assistant
                                                                                                                        :text "reply included"}]
                                                                                                             :tool-calls {}
                                                                                                             :tool-order []}})}))
          typed           (type-text update-fn state "/tree")
          [s1 _]          (update-fn typed (msg/key-press :enter))
          [s2 _]          (update-fn s1 (msg/key-press :down))
          [s3 _]          (update-fn s2 (msg/key-press :down))
          [s4 _]          (update-fn s3 (msg/key-press :enter))]
      (is (= "e1" @forked-entry-id))
      (is (= :idle (:phase s4)))
      (is (= "s3" (:focus-session-id s4)))
      (is (= "Branch from here" (get-in s4 [:messages 0 :text])))
      (is (= "reply included" (get-in s4 [:messages 1 :text])))
      (is (:force-clear? s4)))))

(deftest tree-rename-result-renders-status-message-test
  (testing "tree rename command result appends confirmation text"
    (let [dispatch-fn (fn [text]
                        (when (= text "/tree name s1 Focus on prompt lifecycle")
                          {:type :tree-rename :session-id "s1" :session-name "Focus on prompt lifecycle"}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/tree name s1 Focus on prompt lifecycle")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (str/includes? (get-in s1 [:messages 0 :text]) "Renamed session s1 to")))))

(deftest history-up-from-empty-enters-browsing-at-newest-test
  (testing "up from empty input enters history browse mode at newest entry"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          base      (submit-text update-fn
                                 (submit-text update-fn (init-state) "alpha")
                                 "beta")
          state     (assoc base :phase :idle
                           :input (charm/text-input-set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))]
      (is (= "beta" (text-input/value (:input s1))))
      (is (= 1 (get-in s1 [:prompt-input-state :history :browse-index]))))))

(deftest history-down-from-newest-exits-browsing-to-empty-test
  (testing "down at newest history entry exits browse mode and restores empty input"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          base      (submit-text update-fn
                                 (submit-text update-fn (init-state) "alpha")
                                 "beta")
          state     (assoc base :phase :idle
                           :input (charm/text-input-set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))
          [s2 _]    (update-fn s1 (msg/key-press :down))]
      (is (= "" (text-input/value (:input s2))))
      (is (nil? (get-in s2 [:prompt-input-state :history :browse-index]))))))

(deftest history-editing-clears-browse-index-test
  (testing "normal edit and programmatic set-text clear history browse mode"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          base      (submit-text update-fn
                                 (submit-text update-fn (init-state) "alpha")
                                 "beta")
          state     (assoc base :phase :idle
                           :input (charm/text-input-set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))
          [s2 _]    (update-fn s1 (msg/key-press "x"))
          [s3 _]    (update-fn s2 (msg/key-press :enter))]
      (is (nil? (get-in s2 [:prompt-input-state :history :browse-index])))
      (is (nil? (get-in s3 [:prompt-input-state :history :browse-index]))))))

(deftest autocomplete-slash-opens-on-leading-slash-test
  (testing "typing leading / opens slash autocomplete with slash context"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "/"))
          ac        (get-in s1 [:prompt-input-state :autocomplete])]
      (is (= :slash_command (:context ac)))
      (is (= :auto (:trigger-mode ac)))
      (is (seq (:candidates ac)))
      (is (some #(= "/help" (:value %)) (:candidates ac))))))

(deftest autocomplete-slash-includes-extension-commands-test
  (testing "slash autocomplete includes extension command names"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :extension-command-names ["chain" "chain-list"])
          [s1 _]    (update-fn state (msg/key-press "/"))
          cand-vals (set (map :value (get-in s1 [:prompt-input-state :autocomplete :candidates])))]
      (is (contains? cand-vals "/chain"))
      (is (contains? cand-vals "/chain-list")))))

(deftest autocomplete-accept-on-enter-submits-slash-test
  (testing "enter accepts selected slash suggestion and submits"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (init-state)
          state     (assoc state :prompt-templates [{:name "deploy"}])
          [s1 _]    (update-fn state (msg/key-press "/"))
          [s2 _]    (update-fn s1 (msg/key-press "d"))
          [s3 _]    (update-fn s2 (msg/key-press :enter))]
      (is (= :streaming (:phase s3)))
      (is (= "/deploy" @submitted))
      (is (empty? (get-in s3 [:prompt-input-state :autocomplete :candidates]))))))

(deftest autocomplete-escape-closes-menu-not-quit-test
  (testing "escape closes open autocomplete without quitting"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          [s1 _]    (update-fn (init-state) (msg/key-press "/"))
          [s2 cmd]  (update-fn s1 (msg/key-press :escape))]
      (is (= :idle (:phase s2)))
      (is (nil? cmd))
      (is (empty? (get-in s2 [:prompt-input-state :autocomplete :candidates]))))))

(deftest autocomplete-tab-opens-path-and-single-auto-applies-test
  (testing "tab opens path completion and single match auto-applies"
    (let [tmp-dir (java.nio.file.Files/createTempDirectory "psi-ac" (make-array java.nio.file.attribute.FileAttribute 0))
          root    (.toFile tmp-dir)
          _       (spit (io/file root "alpha.txt") "x")
          update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :cwd (.getAbsolutePath root))
          state     (assoc state :input (charm/text-input-set-value (:input state) "alp"))
          [s1 _]    (update-fn state (msg/key-press :tab))]
      (is (= "alpha.txt" (text-input/value (:input s1))))
      (is (empty? (get-in s1 [:prompt-input-state :autocomplete :candidates]))))))

(deftest autocomplete-file-reference-filters-git-and-adds-space-test
  (testing "@ completion excludes .git entries and appends trailing space for files"
    (let [tmp-dir  (java.nio.file.Files/createTempDirectory "psi-fr" (make-array java.nio.file.attribute.FileAttribute 0))
          root     (.toFile tmp-dir)
          _        (.mkdir (io/file root ".git"))
          _        (spit (io/file root ".git" "ignored.txt") "x")
          _        (spit (io/file root ".hidden") "x")
          _        (spit (io/file root "file.txt") "x")
          update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :cwd (.getAbsolutePath root))
          [s1 _]    (update-fn state (msg/key-press "@"))
          cands     (get-in s1 [:prompt-input-state :autocomplete :candidates])
          cand-vals (set (map :value cands))
          _         (is (contains? cand-vals ".hidden"))
          _         (is (contains? cand-vals "file.txt"))
          _         (is (not-any? #(str/starts-with? % ".git") cand-vals))
          ;; choose file.txt explicitly
          s2        (assoc-in s1 [:prompt-input-state :autocomplete :selected-index]
                              (or (first (keep-indexed (fn [idx c]
                                                         (when (= "file.txt" (:value c)) idx))
                                                       cands))
                                  0))
          [s3 _]    (update-fn s2 (msg/key-press :tab))]
      (is (= "@file.txt " (text-input/value (:input s3)))))))

(deftest autocomplete-quoted-acceptance-avoids-duplicate-closing-quote-test
  (testing "accepting quoted completion does not duplicate closing quote"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (-> (init-state)
                        (assoc :input (charm/text-input-set-value (:input (init-state)) "@\"foo\""))
                        (assoc-in [:prompt-input-state :autocomplete]
                                  {:prefix "@\"f"
                                   :candidates [{:value "\"foo\""
                                                 :label "\"foo\""
                                                 :description nil
                                                 :kind :file_reference
                                                 :is-directory false}]
                                   :selected-index 0
                                   :context :file_reference
                                   :trigger-mode :auto
                                   :token-start 0
                                   :token-end 5}))
          [s1 _]    (update-fn state (msg/key-press :tab))]
      (is (= "@\"foo\" " (text-input/value (:input s1)))))))

(deftest keys-edit-input-during-streaming-test
  (testing "printable keys edit draft input while streaming"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          streaming (-> (init-state)
                        (assoc :phase :streaming)
                        (assoc :input (charm/text-input-set-value (:input (init-state)) "")))
          [s1 cmd]  (update-fn streaming (msg/key-press "x"))]
      (is (= :streaming (:phase s1)))
      (is (= "x" (text-input/value (:input s1))))
      (is (nil? cmd)))))


;;;; View/runtime tests moved to psi.tui.app-view-runtime-test

;;;; Window resize

(deftest window-resize-updates-dimensions-test
  (testing "window-size message updates width and height and requests a hard clear"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/window-size 120 40))
          out       (app/view s1)]
      (is (= 120 (:width s1)))
      (is (= 40 (:height s1)))
      (is (true? (:force-clear? s1)))
      (is (str/starts-with? out "\u001b[2J\u001b[H")))))

(deftest external-message-appended-to-transcript-test
  (testing "external-message appends assistant text and keeps polling"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          event     {:type :external-message
                     :message {:role "assistant"
                               :content [{:type :text :text "Agent complete"}]}}
          [s1 cmd]  (update-fn state event)]
      (is (= 1 (count (:messages s1))))
      (is (= :assistant (:role (first (:messages s1)))))
      (is (= "Agent complete" (:text (first (:messages s1)))))
      (is (some? cmd)))))

(deftest agent-result-rich-render-test
  (testing "agent-result custom-type renders rich heading"
    (let [state (assoc (init-state)
                       :messages [{:role :assistant
                                   :custom-type "agent-result"
                                   :text "Agent #1 finished \"do thing\" in 2s\n\nResult:\nAll good"}])
          out   (app/view state)]
      (is (str/includes? out "Agent Result"))
      (is (str/includes? out "Agent #1 finished"))
      (is (str/includes? out "All good")))))

(deftest plan-state-learning-rich-render-test
  (testing "plan-state-learning custom-type renders dedicated heading"
    (let [state (assoc (init-state)
                       :messages [{:role :assistant
                                   :custom-type "plan-state-learning"
                                   :text "PSL phase1 committed PLAN/STATE at abc1234."}])
          out   (app/view state)]
      (is (str/includes? out "Plan/State/Learning"))
      (is (str/includes? out "PSL phase1 committed")))))

;;;; Text input word wrap

(deftest wrap-text-input-short-test
  (testing "short text renders on one line with prompt"
    (let [state (init-state)
          s1    (assoc state :width 80)
          ;; Type "hello"
          update-fn (app/make-update (stub-agent-fn ""))
          [s2 _] (update-fn s1 (msg/key-press "h"))
          [s3 _] (update-fn s2 (msg/key-press "i"))
          out    (app/view s3)]
      ;; Should have prompt + text on one line, no extra newlines in input area
      (is (str/includes? out "hi")))))

(deftest wrap-text-input-long-test
  (testing "long text wraps at terminal width"
    (let [state   (init-state)
          ;; Set narrow width so wrapping kicks in
          state   (assoc state :width 20)
          update-fn (app/make-update (stub-agent-fn ""))
          ;; Type enough text to exceed width (prompt "刀: " is ~4 cols)
          ;; Available = 20 - 4 = 16 cols
          long-text "the quick brown fox jumps over"
          s (reduce (fn [s ch]
                      (first (update-fn s (msg/key-press (str ch)))))
                    state
                    long-text)
          out (app/view s)
          lines (str/split-lines out)]
      ;; The input area should span multiple lines
      ;; Find lines containing parts of our text
      (is (str/includes? out "the quick"))
      (is (str/includes? out "fox"))
      ;; Verify continuation lines are indented (prompt width spaces)
      (let [input-lines (filter #(or (str/includes? % "the quick")
                                     (str/includes? % "fox")
                                     (str/includes? % "jumps"))
                                lines)]
        (is (> (count input-lines) 1)
            "text should wrap to multiple lines")))))

(deftest wrap-text-input-placeholder-test
  (testing "empty input shows placeholder"
    (let [state (assoc (init-state) :width 80)
          out   (app/view state)]
      ;; Cursor renders on first char, so check for rest of placeholder
      (is (str/includes? out "ype a message")))))

;;;; Extension command output capture

(deftest extension-cmd-println-captured-as-message-test
  (testing "extension command println output appears as assistant message"
    (let [dispatch-fn (fn [text]
                        (when (= text "/hello")
                          {:type    :extension-cmd
                           :name    "hello"
                           :args    ""
                           :handler (fn [_args] (println "Hello from extension!"))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/hello")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (= 1 (count (:messages s1))))
      (is (= :assistant (:role (first (:messages s1)))))
      (is (= "Hello from extension!" (:text (first (:messages s1))))))))

(deftest extension-cmd-no-output-no-message-test
  (testing "extension command with no println adds no message"
    (let [called      (atom false)
          dispatch-fn (fn [text]
                        (when (= text "/silent")
                          {:type    :extension-cmd
                           :name    "silent"
                           :args    ""
                           :handler (fn [_args] (reset! called true))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/silent")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is @called)
      (is (= :idle (:phase s1)))
      (is (empty? (:messages s1))))))

(deftest extension-cmd-error-shown-as-message-test
  (testing "extension command exception appears as error message"
    (let [dispatch-fn (fn [text]
                        (when (= text "/boom")
                          {:type    :extension-cmd
                           :name    "boom"
                           :args    ""
                           :handler (fn [_args] (throw (ex-info "kaboom" {})))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/boom")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (= 1 (count (:messages s1))))
      (is (str/includes? (:text (first (:messages s1))) "kaboom")))))

(deftest extension-cmd-multiline-output-test
  (testing "extension command with multiple printlns captured as single message"
    (let [dispatch-fn (fn [text]
                        (when (= text "/multi")
                          {:type    :extension-cmd
                           :name    "multi"
                           :args    ""
                           :handler (fn [_args]
                                      (println "Line 1")
                                      (println "Line 2"))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/multi")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= 1 (count (:messages s1))))
      (is (str/includes? (:text (first (:messages s1))) "Line 1"))
      (is (str/includes? (:text (first (:messages s1))) "Line 2")))))

;;;; JLine integration smoke test

(deftest jline-terminal-keymap-test
  (testing "JLine terminal + keymap creation works (catches JLine API compat bugs)"
    (let [terminal (charm/create-terminal)]
      (try
        (let [km (keymap/create-keymap terminal)]
          (is (some? km) "keymap created successfully"))
        (finally
          (.close terminal))))))
