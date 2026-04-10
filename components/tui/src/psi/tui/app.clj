(ns psi.tui.app
  (:require
   [charm.core :as charm]
   [charm.message :as msg]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.persistence :as persist]
   [psi.app-runtime.footer :as footer]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.tui.ansi :as ansi]
   [psi.tui.markdown :as md]
   [psi.tui.patches :as patches]
   [psi.tui.session-selector :as session-selector]
   [psi.tui.session-selector-render :as selector-render])
  (:import
   [java.time Instant]
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(patches/install!)

(def ^:private title-style   (charm/style :fg charm/magenta :bold true))
(def ^:private user-style    (charm/style :fg charm/cyan :bold true))
(def ^:private assist-style  (charm/style :fg charm/green :bold true))
(def ^:private error-style   (charm/style :fg charm/red))
(def ^:private dim-style     (charm/style :fg 240))
(def ^:private sep-style     (charm/style :fg 240))
(def ^:private tool-style    (charm/style :fg charm/yellow :bold true))
(def ^:private tool-ok-style (charm/style :fg charm/green))
(def ^:private tool-err-style (charm/style :fg charm/red))
(def ^:private tool-dim-style (charm/style :fg 245))

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def ^:private prompt-history-max-entries 100)

(defn- initial-prompt-input-state
  []
  {:autocomplete {:prefix ""
                  :candidates []
                  :selected-index 0
                  :context nil
                  :trigger-mode nil}
   :history {:entries []
             :browse-index nil
             :max-entries prompt-history-max-entries}
   :timing {:last-ctrl-c-ms nil
            :last-escape-ms nil}})

(def ^:private builtin-slash-commands
  ["/quit" "/exit" "/resume" "/new" "/tree" "/status" "/help" "/remember"
   "/worktree" "/jobs" "/job" "/cancel-job"])

(defn- input-value [state]
  (charm/text-input-value (:input state)))

(defn- input-pos [state]
  (let [v (input-value state)]
    (min (max 0 (or (get-in state [:input :pos]) (count v))) (count v))))

(defn- set-input-value
  [state s]
  (assoc state :input (charm/text-input-set-value (:input state) s)))

(defn- clear-history-browse
  [state]
  (assoc-in state [:prompt-input-state :history :browse-index] nil))

(defn- set-input-model
  [state input]
  (-> state
      (assoc :input input)
      clear-history-browse))

(defn- now-ms [] (System/currentTimeMillis))

(defn- double-press-window-ms
  [state]
  (or (:double-press-window-ms state) 500))

(defn- within-double-press-window?
  [last-ms now-ms window-ms]
  (and (some? last-ms)
       (<= (- now-ms last-ms) window-ms)))

(defn- append-assistant-status
  [state text]
  (if (str/blank? text)
    state
    (update state :messages conj {:role :assistant :text text})))

(defn- merge-queued-and-draft
  [queued-text draft-text]
  (let [queued (str/trim (or queued-text ""))
        draft  (str/trim (or draft-text ""))]
    (cond
      (and (str/blank? queued) (str/blank? draft)) ""
      (str/blank? queued) draft
      (str/blank? draft) queued
      :else (str queued "\n" draft))))

(defn- history-entries
  [state]
  (vec (get-in state [:prompt-input-state :history :entries] [])))

(defn- history-max-entries
  [state]
  (or (get-in state [:prompt-input-state :history :max-entries])
      prompt-history-max-entries))

(defn- record-history-entry
  [state text]
  (let [entry (str/trim (or text ""))]
    (if (str/blank? entry)
      state
      (let [entries      (history-entries state)
            last-entry   (peek entries)
            max-entries  (history-max-entries state)
            next-entries (if (= last-entry entry)
                           entries
                           (let [appended (conj entries entry)
                                 extra    (max 0 (- (count appended) max-entries))]
                             (if (pos? extra)
                               (vec (subvec appended extra))
                               appended)))]
        (-> state
            (assoc-in [:prompt-input-state :history :entries] next-entries)
            (assoc-in [:prompt-input-state :history :browse-index] nil))))))

(defn- history-current-entry
  [state idx]
  (let [entries (history-entries state)]
    (when (and (some? idx)
               (<= 0 idx)
               (< idx (count entries)))
      (nth entries idx))))

(defn- browse-history
  [state direction]
  (let [entries (history-entries state)
        n       (count entries)
        idx     (get-in state [:prompt-input-state :history :browse-index])
        input   (input-value state)]
    (if (zero? n)
      state
      (case direction
        :up
        (cond
          (and (nil? idx) (str/blank? input))
          (let [new-idx (dec n)]
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                (set-input-value (history-current-entry state new-idx))))

          (some? idx)
          (let [new-idx (max 0 (dec idx))]
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                (set-input-value (history-current-entry state new-idx))))

          :else
          state)

        :down
        (if (some? idx)
          (if (>= idx (dec n))
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] nil)
                (set-input-value ""))
            (let [new-idx (min (dec n) (inc idx))]
              (-> state
                  (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                  (set-input-value (history-current-entry state new-idx)))))
          state)

        state))))

(defn- whitespace-char?
  [^Character c]
  (Character/isWhitespace c))

(defn- token-context-at-cursor
  [state]
  (let [text        (input-value state)
        pos         (input-pos state)
        before      (subs text 0 pos)
        after       (subs text pos)
        token-start (or (some->> (keep-indexed (fn [i ch]
                                                 (when (whitespace-char? ch) i))
                                               before)
                                 last
                                 inc)
                        0)
        token       (subs before token-start)
        context     (cond
                      (and (= token-start 0)
                           (str/starts-with? token "/"))
                      :slash_command

                      (str/starts-with? token "@")
                      :file_reference

                      :else
                      :file_path)]
    {:text text
     :pos pos
     :before before
     :after after
     :token token
     :token-start token-start
     :token-end pos
     :context context
     :prefix token}))

(defn- as-slash-command
  [x]
  (let [s (some-> x str str/trim)]
    (when (seq s)
      (if (str/starts-with? s "/")
        s
        (str "/" s)))))

(defn- slash-candidates
  [state prefix]
  (let [templates  (mapv (fn [{:keys [name]}] (str "/" name)) (:prompt-templates state))
        skills     (mapv (fn [{:keys [name]}] (str "/skill:" name)) (:skills state))
        ext-cmds   (vec (keep as-slash-command (:extension-command-names state)))
        all        (->> (concat builtin-slash-commands templates skills ext-cmds)
                        (remove str/blank?)
                        distinct
                        sort)
        pfx        (or prefix "/")
        lowered    (str/lower-case pfx)]
    (->> all
         (filter #(str/starts-with? (str/lower-case %) lowered))
         (mapv (fn [cmd]
                 {:value cmd
                  :label cmd
                  :description nil
                  :kind :slash_command
                  :is-directory false})))))

(defn- rel-path
  [cwd ^java.io.File f]
  (let [cwd-path (.toPath (io/file cwd))
        file-path (.toPath f)]
    (-> (.relativize cwd-path file-path)
        (.normalize)
        (.toString))))

(defn- hidden-or-git-path?
  [rel]
  (or (= ".git" rel)
      (str/starts-with? rel ".git/")
      (str/includes? rel "/.git/")
      (str/ends-with? rel "/.git")))

(defn- quote-if-needed [s]
  (if (str/includes? s " ")
    (str "\"" s "\"")
    s))

(defn- file-reference-candidates
  [state prefix]
  (let [cwd        (:cwd state)
        token      (or prefix "@")
        typed      (subs token (min 1 (count token)))
        typed      (str/replace typed #"^\"" "")
        query      (str/lower-case typed)
        root       (io/file cwd)]
    (->> (file-seq root)
         (remove #(.equals root %))
         (map (fn [^java.io.File f]
                (let [rel (rel-path cwd f)]
                  {:file f :rel rel :dir? (.isDirectory f)})))
         (remove #(hidden-or-git-path? (:rel %)))
         (filter (fn [{:keys [rel]}]
                   (or (str/blank? query)
                       (str/includes? (str/lower-case rel) query))))
         (sort-by (juxt (fn [{:keys [dir?]}] (if dir? 0 1)) :rel))
         (take 100)
         (mapv (fn [{:keys [rel dir?]}]
                 (let [v (cond-> rel
                           dir? (str "/"))
                       v (quote-if-needed v)]
                   {:value v
                    :label v
                    :description nil
                    :kind :file_reference
                    :is-directory dir?}))))))

(defn- path-completion-candidates
  [state prefix]
  (let [cwd          (:cwd state)
        token        (or prefix "")
        slash-idx    (str/last-index-of token "/")
        [dir-part name-part] (if slash-idx
                               [(subs token 0 (inc slash-idx))
                                (subs token (inc slash-idx))]
                               ["" token])
        base         (io/file cwd dir-part)
        dir-exists?  (.isDirectory base)]
    (if-not dir-exists?
      []
      (->> (.listFiles base)
           (filter some?)
           (map (fn [^java.io.File f]
                  (let [nm (.getName f)
                        rel (str dir-part nm)]
                    {:name nm
                     :rel rel
                     :dir? (.isDirectory f)})))
           (remove #(hidden-or-git-path? (:rel %)))
           (filter (fn [{:keys [name]}]
                     (str/starts-with? (str/lower-case name)
                                       (str/lower-case name-part))))
           (sort-by (juxt (fn [{:keys [dir?]}] (if dir? 0 1)) :rel))
           (mapv (fn [{:keys [rel dir?]}]
                   (let [v (cond-> rel
                             dir? (str "/"))]
                     {:value v
                      :label v
                      :description nil
                      :kind :file_path
                      :is-directory dir?})))))))

(defn- clear-autocomplete
  [state]
  (assoc-in state [:prompt-input-state :autocomplete]
            {:prefix ""
             :candidates []
             :selected-index 0
             :context nil
             :trigger-mode nil}))

(defn- open-autocomplete
  [state {:keys [prefix context trigger-mode token-start token-end]} candidates]
  (if (seq candidates)
    (assoc-in state [:prompt-input-state :autocomplete]
              {:prefix prefix
               :candidates candidates
               :selected-index 0
               :context context
               :trigger-mode trigger-mode
               :token-start token-start
               :token-end token-end})
    (clear-autocomplete state)))

(defn- context-candidates
  [state context prefix]
  (case context
    :slash_command (slash-candidates state prefix)
    :file_reference (file-reference-candidates state prefix)
    :file_path (path-completion-candidates state prefix)
    []))

(defn- refresh-autocomplete
  [state trigger-mode]
  (let [{:keys [context prefix token-start token-end]} (token-context-at-cursor state)
        candidates (context-candidates state context prefix)]
    (open-autocomplete state {:prefix prefix
                              :context context
                              :trigger-mode trigger-mode
                              :token-start token-start
                              :token-end token-end}
                       candidates)))

(defn- autocomplete-open?
  [state]
  (seq (get-in state [:prompt-input-state :autocomplete :candidates])))

(defn- move-autocomplete-selection
  [state delta]
  (let [cands (get-in state [:prompt-input-state :autocomplete :candidates])
        cnt   (count cands)]
    (if (zero? cnt)
      state
      (update-in state [:prompt-input-state :autocomplete :selected-index]
                 (fn [i]
                   (let [i (or i 0)]
                     (mod (+ i delta) cnt)))))))

(defn- drop-duplicate-closing-quote-in-after
  [replacement after]
  (if (and (str/ends-with? replacement "\"")
           (str/starts-with? (or after "") "\""))
    (subs after 1)
    after))

(defn- apply-selected-autocomplete
  [state]
  (let [ac         (get-in state [:prompt-input-state :autocomplete])
        idx        (or (:selected-index ac) 0)
        candidate  (nth (:candidates ac) idx nil)
        text       (input-value state)
        start      (or (:token-start ac) (count text))
        end        (or (:token-end ac) (count text))
        before     (subs text 0 (min start (count text)))
        after      (subs text (min end (count text)))
        context    (:context ac)]
    (if-not candidate
      state
      (let [base-value (:value candidate)
            replacement (case context
                          :file_reference (str "@" base-value)
                          base-value)
            after       (drop-duplicate-closing-quote-in-after replacement after)
            replacement (if (and (= :file_reference context)
                                 (not (:is-directory candidate)))
                          (str replacement " ")
                          replacement)
            text'      (str before replacement after)]
        (-> state
            (set-input-value text')
            clear-autocomplete)))))

(declare printable-key)

(defn- maybe-auto-open-autocomplete
  [state key-token]
  (let [ch (printable-key key-token)]
    (if-not (or (= ch "/") (= ch "@"))
      state
      (let [{:keys [context token]} (token-context-at-cursor state)]
        (cond
          (and (= ch "/") (= :slash_command context))
          (refresh-autocomplete state :auto)

          (and (= ch "@") (= :file_reference context)
               (str/starts-with? token "@"))
          (refresh-autocomplete state :auto)

          :else
          state)))))

(defn- open-tab-autocomplete
  [state]
  (let [{:keys [token token-start token-end]} (token-context-at-cursor state)
        context    (if (and (= token-start 0) (str/starts-with? token "/"))
                     :slash_command
                     :file_path)
        candidates (context-candidates state context token)
        opened     (open-autocomplete state {:prefix token
                                             :context context
                                             :trigger-mode :tab
                                             :token-start token-start
                                             :token-end token-end}
                                      candidates)]
    (if (and (= :file_path context)
             (= 1 (count candidates)))
      (apply-selected-autocomplete opened)
      opened)))


(load "app_support")
(load "app_update_helpers")

;; ── Update ──────────────────────────────────────────────────

(defn- update-tick-state
  [state]
  (let [state (cond-> state
                (:force-clear? state) (assoc :force-clear? false))
        state (if-let [read-fn (:ui-read-fn state)]
                (let [snap (read-fn)]
                  (assoc state :ui-snapshot snap :tools-expanded? (boolean (:tools-expanded? snap))))
                state)]
    (dispatch-ui-event! state :session/ui-dismiss-expired {})
    (dispatch-ui-event! state :session/ui-dismiss-overflow {})
    (refresh-extension-command-names state)))

(defn- log-key-debug!
  [m]
  (when (and (key-debug-enabled?) (msg/key-press? m))
    (println (str "[key-debug] key=" (pr-str (:key m))
                  " ctrl=" (boolean (:ctrl m))
                  " alt=" (boolean (:alt m))
                  " shift=" (boolean (:shift m))))))

(defn- handle-window-size-message
  [state m]
  (when (msg/window-size? m)
    [(assoc state
            :width (:width m)
            :height (:height m)
            :force-clear? true)
     nil]))

(defn- external-message-text
  [m]
  (or (some #(when (= :text (:type %)) (:text %)) (get-in m [:message :content]))
      ""))

(defn- handle-agent-message
  [state m]
  (cond
    (agent-event? m)
    (let [[new-state cmd] (handle-agent-event state m)]
      [new-state (or cmd (poll-cmd (:queue state)))])

    (external-message? m)
    (let [text (external-message-text m)]
      [(cond-> state
         (seq text) (update :messages conj {:role :assistant
                                            :text text
                                            :custom-type (:custom-type m)}))
       (poll-cmd (:queue state))])

    (agent-result? m)
    (handle-agent-result state (:result m))

    (agent-error? m)
    [(assoc state :phase :idle :error (:error m))
     (poll-cmd (:queue state))]

    (= :agent-aborted (:type m))
    (let [merged-text (merge-queued-and-draft (:queued-text m) (input-value state))
          status-msg  (or (:message m) "Interrupted.")]
      [(-> state
           (set-input-value merged-text)
           (assoc :phase :idle)
           clear-live-turn
           (append-assistant-status status-msg))
       (poll-cmd (:queue state))])

    (agent-poll? m)
    (if (= :streaming (:phase state))
      (handle-agent-poll state)
      [state (poll-cmd (:queue state))])

    :else nil))

(defn- handle-streaming-input
  [state m]
  (cond
    (and (= :streaming (:phase state))
         (msg/key-match? m "escape"))
    (handle-streaming-escape state)

    (and (= :streaming (:phase state))
         (msg/key-match? m "enter"))
    (handle-streaming-submit state)

    (and (= :streaming (:phase state))
         (msg/key-match? m "backspace"))
    (let [[new-input cmd] (charm/text-input-update (:input state) m)]
      [(set-input-model state new-input) cmd])

    (and (= :streaming (:phase state))
         (msg/key-match? m "space"))
    (let [[new-input cmd] (charm/text-input-update (:input state) (msg/key-press " "))]
      [(set-input-model state new-input) cmd])

    (and (= :streaming (:phase state))
         (msg/key-press? m))
    (let [[new-input cmd] (charm/text-input-update (:input state) m)]
      [(set-input-model state new-input) cmd])

    :else nil))

(defn- continue-input-line?
  [state m]
  (and (msg/key-match? m "enter")
       (or (:shift m)
           (:alt m)
           (and (:ctrl m) (:alt m))
           (str/ends-with? (charm/text-input-value (:input state)) "\\"))))

(defn- toggle-tools-expanded
  [state]
  (let [new-expanded? (not (:tools-expanded? state))]
    (dispatch-ui-event! state :session/ui-set-tools-expanded {:expanded? new-expanded?})
    [(assoc state :tools-expanded? new-expanded?) nil]))

(defn- delete-prev-word-update
  [state]
  (let [before    (charm/text-input-value (:input state))
        new-state (update state :input delete-prev-word)
        after     (charm/text-input-value (:input new-state))]
    (when (key-debug-enabled?)
      (println (str "[key-debug] branch=alt+backspace before=" (pr-str before)
                    " after=" (pr-str after)
                    " pos=" (:pos (:input new-state)))))
    [new-state nil]))

(defn- idle-edit-update
  [state update-message next-state-fn]
  (let [[new-input cmd] (charm/text-input-update (:input state) update-message)]
    [(next-state-fn (set-input-model state new-input)) cmd]))

(defn- idle-next-state-after-edit
  [state key-token]
  (if (autocomplete-open? state)
    (refresh-autocomplete state (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
    (maybe-auto-open-autocomplete state key-token)))

(defn- autocomplete-nav-update
  [state m autocomplete?]
  (cond
    (and autocomplete? (msg/key-match? m "up")) [(move-autocomplete-selection state -1) nil]
    (and autocomplete? (msg/key-match? m "down")) [(move-autocomplete-selection state 1) nil]
    (and (not autocomplete?) (msg/key-match? m "up")) [(browse-history state :up) nil]
    (and (not autocomplete?) (msg/key-match? m "down")) [(browse-history state :down) nil]
    :else nil))

(defn- autocomplete-submit-update
  [state m autocomplete? run-agent-fn!]
  (cond
    (and autocomplete? (msg/key-match? m "tab")) [(apply-selected-autocomplete state) nil]
    (msg/key-match? m "tab") [(open-tab-autocomplete state) nil]
    (continue-input-line? state m) (continue-input-line state)
    (and autocomplete? (msg/key-match? m "enter"))
    (let [slash?     (= :slash_command (get-in state [:prompt-input-state :autocomplete :context]))
          next-state (apply-selected-autocomplete state)]
      (if slash?
        (submit-input next-state run-agent-fn!)
        [next-state nil]))
    (msg/key-match? m "enter") (submit-input state run-agent-fn!)
    :else nil))

(defn- idle-special-key-update
  [state m autocomplete?]
  (cond
    (msg/key-match? m "ctrl+d") (handle-ctrl-d state)
    (and autocomplete? (msg/key-match? m "escape")) [(clear-autocomplete state) nil]
    (and (not (has-active-dialog? state)) (msg/key-match? m "escape")) (handle-idle-escape state)
    (msg/key-match? m "ctrl+o") (toggle-tools-expanded state)
    (msg/key-match? m "alt+backspace") (delete-prev-word-update state)
    :else nil))

(defn- idle-text-edit-update
  [state m autocomplete?]
  (cond
    (msg/key-match? m "backspace")
    (idle-edit-update state m #(if autocomplete?
                                 (refresh-autocomplete % (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
                                 %))
    (msg/key-match? m "space")
    (idle-edit-update state (msg/key-press " ") #(idle-next-state-after-edit % :space))
    (msg/key-press? m)
    (let [key-token (:key m)]
      (idle-edit-update state m #(idle-next-state-after-edit % key-token)))
    :else nil))

(defn- handle-idle-key-message
  [state m run-agent-fn!]
  (let [autocomplete? (autocomplete-open? state)]
    (or (idle-special-key-update state m autocomplete?)
        (autocomplete-nav-update state m autocomplete?)
        (autocomplete-submit-update state m autocomplete? run-agent-fn!)
        (idle-text-edit-update state m autocomplete?))))

(defn make-update
  "Create an update function.

   `run-agent-fn!` is called with (text queue) and should start the
   agent in a background thread that puts {:kind :done :result msg}
   or {:kind :error :message str} on queue when finished."
  [run-agent-fn!]
  (fn [state m]
    (let [state (update-tick-state state)]
      (log-key-debug! m)
      (or (when (msg/key-match? m "ctrl+c")
            (handle-ctrl-c state))
          (handle-window-size-message state m)
          (when (and (has-active-dialog? state) (msg/key-press? m))
            (or (handle-dialog-key state m) [state nil]))
          (handle-agent-message state m)
          (when (= :selecting-session (:phase state))
            (handle-selector-key state m))
          (when (= :idle (:phase state))
            (handle-idle-key-message state m run-agent-fn!))
          (handle-streaming-input state m)
          [state nil]))))


(load "app_render")

;; ── Public entry point ──────────────────────────────────────

(defn start!
  ([model-name run-agent-fn!]
   (start! model-name run-agent-fn! {}))
  ([model-name run-agent-fn! opts]
   (charm/run {:init       (make-init model-name (:query-fn opts) (:ui-read-fn opts) (:ui-dispatch-fn opts) opts)
               :update     (make-update run-agent-fn!)
               :view       view
               :alt-screen (if (contains? opts :alt-screen)
                             (boolean (:alt-screen opts))
                             true)})))
