(ns psi.agent-session.turn-accumulator
  "Streaming turn accumulation — content blocks, text merging, tool-call assembly,
   and the make-turn-actions factory that drives the per-turn statechart.

   Owns all mutable state updates to the turn-data atom during a streaming turn."
  (:require
   [clojure.string :as str]
   [psi.agent-session.conversation :as conv-translate]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.turn-statechart :as turn-sc]))

;; ============================================================
;; Progress emission
;; ============================================================

(defn emit-progress!
  "Emit a progress event to the progress queue (if provided).
   Events are maps with :type :agent-event and :event-kind."
  [progress-queue event]
  (when progress-queue
    (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
            (assoc event :type :agent-event))))

;; ============================================================
;; Content block tracking
;; ============================================================

(defn- note-last-provider-event! [turn-data event-type data]
  (swap! turn-data assoc
         :last-provider-event
         (cond-> {:type      event-type
                  :timestamp (java.time.Instant/now)}
           (contains? data :content-index) (assoc :content-index (:content-index data))
           (contains? data :reason)        (assoc :reason (:reason data))
           (contains? data :http-status)   (assoc :http-status (:http-status data))
           (contains? data :error-message) (assoc :error-message (:error-message data)))))

(defn- update-content-block! [turn-data idx f]
  (swap! turn-data update :content-blocks
         (fn [blocks]
           (let [blocks* (or blocks (sorted-map))
                 current (get blocks* idx {:content-index idx
                                           :kind          :unknown
                                           :status        :open
                                           :delta-count   0})]
             (assoc blocks* idx (f current))))))

(defn- begin-content-block! [turn-data idx]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (let [block (assoc current :content-index idx :status :open :ended-at nil)]
         (cond-> block (nil? (:started-at block)) (assoc :started-at ts)))))))

(defn- note-content-delta! [turn-data idx kind]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (let [block (-> current
                       (assoc :content-index idx :kind kind :status :open :last-delta-at ts)
                       (update :delta-count (fnil inc 0)))]
         (cond-> block (nil? (:started-at block)) (assoc :started-at ts)))))))

(defn- end-content-block! [turn-data idx]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (assoc current :content-index idx :status :closed :ended-at ts)))))

;; ============================================================
;; Text merging
;; ============================================================

(defn- common-prefix-length [a b]
  (let [a*    (or a "")
        b*    (or b "")
        limit (min (count a*) (count b*))]
    (loop [idx 0]
      (if (and (< idx limit)
               (= (.charAt ^String a* idx) (.charAt ^String b* idx)))
        (recur (inc idx))
        idx))))

(defn merge-stream-text
  "Merge current streamed text with incoming provider chunk.
   Handles both incremental deltas (append) and cumulative snapshots (replace)."
  [current incoming]
  (let [current*  (or current "")
        incoming* (or incoming "")]
    (cond
      (empty? incoming*) current*
      (empty? current*)  incoming*
      (str/starts-with? incoming* current*) incoming*
      :else
      (let [cur-len    (count current*)
            in-len     (count incoming*)
            cp         (common-prefix-length current* incoming*)
            tail-close (and (> in-len cur-len) (>= cp (max 1 (dec cur-len))))]
        (if tail-close incoming* (str current* incoming*))))))

;; ============================================================
;; Tool-call assembly helpers
;; ============================================================

(defn- canonical-tool-call-id [turn-id content-index provider-tool-call-id]
  (or provider-tool-call-id (str turn-id "/toolcall/" content-index)))

(defn complete-tool-calls [turn-id tool-calls]
  (->> tool-calls
       (sort-by key)
       (mapv (fn [[idx tc]]
               (let [content-index (or (:content-index tc) idx)]
                 (-> tc
                     (assoc :content-index content-index)
                     (update :id #(canonical-tool-call-id turn-id content-index %))))))))

(defn invalid-tool-call [tc]
  (let [{:keys [ok?]} (conv-translate/parse-args-strict (:arguments tc))]
    (cond
      (or (nil? (:id tc)) (str/blank? (str (:id tc))))
      {:reason :missing-call-id
       :message "Tool call missing call_id; cannot execute reliably"
       :tool-call tc}

      (or (nil? (:name tc)) (str/blank? (str (:name tc))))
      {:reason :missing-tool-name
       :message "Tool call missing name; cannot execute reliably"
       :tool-call tc}

      (not ok?)
      {:reason :invalid-arguments
       :message "Tool call arguments invalid; expected JSON object"
       :tool-call tc}

      :else nil)))

(defn- thinking-blocks-in-order [thinking-blocks]
  (->> thinking-blocks
       vals
       (sort-by :content-index)
       (mapv (fn [{:keys [text provider signature]}]
               (cond-> {:type :thinking :text (or text "")}
                 provider  (assoc :provider provider)
                 signature (assoc :signature signature))))))

(defn build-final-content [thinking-blocks text-buffer tool-calls]
  (let [invalids       (keep invalid-tool-call tool-calls)
        valid-calls    (remove invalid-tool-call tool-calls)
        thinking-parts (thinking-blocks-in-order thinking-blocks)
        text-blocks    (cond-> [] (seq text-buffer) (conj {:type :text :text text-buffer}))
        error-blocks   (mapv (fn [inv] {:type :error :text (:message inv)}) invalids)
        tool-blocks    (mapv (fn [tc] {:type :tool-call :id (:id tc) :name (:name tc) :arguments (:arguments tc)})
                             valid-calls)]
    (-> thinking-parts (into text-blocks) (into error-blocks) (into tool-blocks))))

(defn- emit-tool-assembly-errors! [progress-queue tool-calls]
  (doseq [invalid (keep invalid-tool-call tool-calls)]
    (emit-progress! progress-queue
                    {:event-kind :error
                     :error      (:message invalid)
                     :detail     (:reason invalid)
                     :error-code "tool-call/assembly-failed"})))

;; ============================================================
;; Provider identity
;; ============================================================

(defn- provider-id [provider]
  (cond
    (keyword? provider) (name provider)
    (symbol? provider)  (name provider)
    (string? provider)  provider
    :else               (str (or provider ""))))

(defn anthropic-provider? [ai-model]
  (= "anthropic" (provider-id (:provider ai-model))))

(defn reset-thinking-buffers-on-toolcall-start!
  "Clear thinking buffers at a tool-call boundary.
   Anthropic: clear only the matching index. OpenAI: clear all."
  [thinking-buffers ai-model content-index]
  (if (anthropic-provider? ai-model)
    (swap! thinking-buffers dissoc content-index)
    (reset! thinking-buffers {})))

;; ============================================================
;; Turn actions factory
;; ============================================================

(defn make-turn-actions
  "Create the actions-fn for the per-turn statechart.
   Handles data accumulation (in turn-data atom) and session-data writes.
   When progress-queue is non-nil, emits :agent-event messages for TUI.

   Tool-call identity semantics:
   - content-index is the provisional per-turn identity during assembly
   - every tool call is upgraded to a canonical tool-call-id by execution time
   - lifecycle/result events must target rows by canonical tool-call-id

   `ai-model`        — for provider-specific thinking accumulation (Anthropic vs OpenAI)
   `thinking-buffers` — atom({content-index → merged-text}) for per-block accumulation"
  [ctx session-id _agent-ctx done-p progress-queue ai-model thinking-buffers]
  (fn [action-key data]
    (let [td (:turn-data data)]
      (case action-key
        :on-stream-start
        (note-last-provider-event! td :start data)

        :on-text-start
        (let [idx (or (:content-index data) 0)]
          (note-last-provider-event! td :text-start data)
          (begin-content-block! td idx)
          (update-content-block! td idx #(assoc % :kind :text)))

        :on-text-delta
        (let [idx    (or (:content-index data) 0)
              merged (:text-buffer (swap! td update :text-buffer merge-stream-text (:delta data)))]
          (note-last-provider-event! td :text-delta data)
          (note-content-delta! td idx :text)
          (emit-progress! progress-queue {:event-kind :text-delta :content-index idx :text merged}))

        :on-text-end
        (do
          (note-last-provider-event! td :text-end data)
          (end-content-block! td (or (:content-index data) 0)))

        :on-thinking-start
        (let [idx (or (:content-index data) 0)]
          (note-last-provider-event! td :thinking-start data)
          (begin-content-block! td idx)
          (update-content-block! td idx #(assoc % :kind :thinking))
          (when (anthropic-provider? ai-model)
            (swap! td update :thinking-blocks
                   (fnil assoc (sorted-map)) idx
                   {:content-index idx
                    :provider      :anthropic
                    :text          (or (:thinking data) "")
                    :signature     (:signature data)})))

        :on-thinking-delta
        (let [idx    (or (:content-index data) 0)
              raw    (let [d (:delta data)] (if (string? d) d (str (or d ""))))
              merged (get (swap! thinking-buffers update idx merge-stream-text raw) idx)]
          (note-last-provider-event! td :thinking-delta data)
          (note-content-delta! td idx :thinking)
          (when (anthropic-provider? ai-model)
            (swap! td update :thinking-blocks
                   (fn [blocks]
                     (let [blocks* (or blocks (sorted-map))
                           current (get blocks* idx {:content-index idx :provider :anthropic :signature nil})]
                       (assoc blocks* idx (assoc current :text merged))))))
          (emit-progress! progress-queue {:event-kind :thinking-delta :content-index idx :text merged}))

        :on-thinking-signature-delta
        (let [idx (or (:content-index data) 0)]
          (when (anthropic-provider? ai-model)
            (swap! td update :thinking-blocks
                   (fn [blocks]
                     (let [blocks* (or blocks (sorted-map))
                           current (get blocks* idx {:content-index idx :provider :anthropic :text ""})]
                       (assoc blocks* idx (assoc current :signature (:signature data))))))))

        :on-thinking-end
        (do
          (note-last-provider-event! td :thinking-end data)
          (end-content-block! td (or (:content-index data) 0)))

        :on-toolcall-start
        (let [idx     (:content-index data)
              tc-id   (:tool-id data)
              tc-name (:tool-name data)
              updated (get-in (swap! td update-in [:tool-calls idx]
                                     (fn [cur]
                                       (let [merged (merge {:id nil :name nil :arguments "" :content-index idx}
                                                           cur
                                                           {:id (or tc-id (:id cur))
                                                            :name (or tc-name (:name cur))
                                                            :content-index idx})]
                                         (assoc merged :id (canonical-tool-call-id (:turn-id @td) idx (:id merged))))))
                              [:tool-calls idx])]
          (reset-thinking-buffers-on-toolcall-start! thinking-buffers ai-model idx)
          (note-last-provider-event! td :toolcall-start data)
          (begin-content-block! td idx)
          (update-content-block! td idx #(assoc % :kind :tool-call))
          (sa/append-tool-call-attempt-in! ctx session-id
                                           {:turn-id       (:turn-id @td)
                                            :event-kind    :toolcall-start
                                            :content-index idx
                                            :id            tc-id
                                            :name          tc-name})
          (emit-progress! progress-queue
                          {:event-kind            :tool-call-assembly
                           :phase                 :start
                           :turn-id               (:turn-id @td)
                           :content-index         idx
                           :tool-id               (:id updated)
                           :provider-tool-call-id (when (= tc-id (:id updated)) tc-id)
                           :tool-name             (:name updated)
                           :arguments             (:arguments updated)}))

        :on-toolcall-delta
        (let [idx   (:content-index data)
              delta (:delta data)
              updated (get-in (swap! td update-in [:tool-calls idx]
                                     (fn [cur]
                                       (let [current-args (or (:arguments cur) "")
                                             merged       (merge {:id nil :name nil :arguments "" :content-index idx}
                                                                 cur
                                                                 {:arguments     (str current-args (or delta ""))
                                                                  :content-index idx})]
                                         (assoc merged :id (canonical-tool-call-id (:turn-id @td) idx (:id merged))))))
                              [:tool-calls idx])]
          (note-last-provider-event! td :toolcall-delta data)
          (note-content-delta! td idx :tool-call)
          (sa/append-tool-call-attempt-in! ctx session-id
                                           {:turn-id       (:turn-id @td)
                                            :event-kind    :toolcall-delta
                                            :content-index idx
                                            :delta         delta})
          (emit-progress! progress-queue
                          {:event-kind            :tool-call-assembly
                           :phase                 :delta
                           :turn-id               (:turn-id @td)
                           :content-index         idx
                           :tool-id               (:id updated)
                           :provider-tool-call-id (when-not (str/starts-with? (str (:id updated))
                                                                               (str (:turn-id @td) "/toolcall/"))
                                                    (:id updated))
                           :tool-name             (:name updated)
                           :arguments             (:arguments updated)}))

        :on-toolcall-end
        (let [idx     (:content-index data)
              updated (get-in @td [:tool-calls idx])]
          (note-last-provider-event! td :toolcall-end data)
          (end-content-block! td idx)
          (sa/append-tool-call-attempt-in! ctx session-id
                                           {:turn-id       (:turn-id @td)
                                            :event-kind    :toolcall-end
                                            :content-index idx})
          (emit-progress! progress-queue
                          {:event-kind            :tool-call-assembly
                           :phase                 :end
                           :turn-id               (:turn-id @td)
                           :content-index         idx
                           :tool-id               (:id updated)
                           :provider-tool-call-id (when-not (str/starts-with? (str (:id updated))
                                                                               (str (:turn-id @td) "/toolcall/"))
                                                    (:id updated))
                           :tool-name             (:name updated)
                           :arguments             (:arguments updated)}))

        :on-done
        (let [{:keys [thinking-blocks text-buffer tool-calls]} @td
              completed (complete-tool-calls (:turn-id @td) tool-calls)
              content   (build-final-content thinking-blocks text-buffer completed)
              usage     (:usage data)
              final     (cond-> {:role        "assistant"
                                 :content     content
                                 :stop-reason (or (:reason data) :stop)
                                 :timestamp   (java.time.Instant/now)}
                          (map? usage) (assoc :usage usage))]
          (note-last-provider-event! td :done data)
          (emit-tool-assembly-errors! progress-queue completed)
          (swap! td assoc :final-message final)
          (session/journal-append-in! ctx session-id (persist/message-entry final))
          (deliver done-p final))

        :on-error
        (let [{:keys [text-buffer]} @td
              err-msg (:error-message data)
              content (cond-> []
                        (seq text-buffer) (conj {:type :text :text text-buffer})
                        :always           (conj {:type :error :text err-msg}))
              final   (cond-> {:role          "assistant"
                                :content       content
                                :stop-reason   :error
                                :error-message err-msg
                                :timestamp     (java.time.Instant/now)}
                        (:http-status data) (assoc :http-status (:http-status data)))]
          (note-last-provider-event! td :error data)
          (swap! td assoc :final-message final :error-message err-msg)
          (session/journal-append-in! ctx session-id (persist/message-entry final))
          (deliver done-p final))

        :on-reset
        (reset! td (turn-sc/create-turn-data))

        nil))))
