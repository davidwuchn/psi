(ns psi.agent-session.executor
  "Bridges the ai streaming layer into the agent-core loop protocol.

   The executor is the missing link between:
     ai/stream-response  — delivers LLM token events via callback
     agent-core          — owns statechart + data model, expects callers
                           to drive begin-stream-in! / end-stream-in! etc.
     tools               — execute tool calls, return results

   One call to `run-turn!` drives a single LLM turn:
     1. Streams the LLM response via a per-turn statechart.
     2. On completion, checks for tool calls.
     3. For each tool call: executes the tool, records the result.
     4. If tools were called, recurses for the next turn.
     5. Returns when the LLM produces a stop response (no tools).

   Per-turn statechart (Step 6)
   ────────────────────────────
   Each streaming turn is driven by a statechart:
     :idle → :text-accumulating ⇄ :tool-accumulating → :done | :error

   Provider events are translated to statechart events.
   Accumulated text, tool calls, and final message are stored in a
   :turn-data atom — queryable via EQL from nREPL at any time.

   Message format translation
   ──────────────────────────
   ai/conversation uses the ai.schemas.Message shape (role, content block).
   agent-core uses plain maps ({:role string :content [...]}).
   The executor builds agent-core messages directly and keeps a parallel
   ai/conversation for the LLM call — the two are kept in sync."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [cheshire.core :as json]
   [psi.ai.core :as ai]
   [psi.ai.conversation :as conv]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.system-prompt :as system-prompt]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.turn-statechart :as turn-sc]))

;; ============================================================
;; ai conversation ↔ agent-core message translation
;; ============================================================

(defn- parse-args-strict
  "Parse tool args strictly, preserving parse validity.
   Returns {:ok? true :value <map>} when JSON parses to a map,
   otherwise {:ok? false :value nil}."
  [arguments]
  (try
    (let [parsed (json/parse-string arguments)]
      (if (map? parsed)
        {:ok? true :value parsed}
        {:ok? false :value nil}))
    (catch Exception _
      {:ok? false :value nil})))

(defn- parse-args
  "Parse JSON tool arguments string into a map.
   Always returns a map — if the parsed value is not a map (e.g. string, array,
   nil) or parsing fails, returns {} so tool_use.input is always a valid dict."
  [arguments]
  (let [{:keys [ok? value]} (parse-args-strict arguments)]
    (if ok? value {})))

(defn- parse-tool-parameters
  "Parse tool parameters from pr-str'd string to a map, or return as-is if already a map."
  [params]
  (if (string? params) (edn/read-string params) params))

(def ^:private ephemeral-cache-control
  {:type :ephemeral})

(def ^:private max-provider-cache-breakpoints
  "Maximum cache breakpoints supported by the Anthropic API."
  4)

(defn- maybe-cache-control
  [enabled?]
  (when enabled?
    ephemeral-cache-control))

(defn- add-cache-control-to-content
  "Add ephemeral cache-control to a message's normalized content."
  [content]
  (case (:kind content)
    :text       (assoc content :cache-control ephemeral-cache-control)
    :structured (update content :blocks
                        (fn [blocks]
                          (if (seq blocks)
                            (update blocks (dec (count blocks))
                                    assoc :cache-control ephemeral-cache-control)
                            blocks)))
    content))

(defn- apply-message-cache-breakpoints
  "Mark the last N user messages with ephemeral cache-control.
   N = available breakpoint slots after system and tools."
  [conv system-cache? tools-cache?]
  (let [system-slots (if system-cache? 1 0)
        tools-slots  (if tools-cache? 1 0)
        available    (- max-provider-cache-breakpoints system-slots tools-slots)]
    (if (pos? available)
      (let [messages  (:messages conv)
            user-idxs (->> (range (count messages))
                           (filterv #(= :user (:role (nth messages %)))))]
        (if (empty? user-idxs)
          conv
          (let [target-idxs (set (take-last available user-idxs))]
            (assoc conv :messages
                   (into []
                         (map-indexed
                          (fn [i msg]
                            (if (contains? target-idxs i)
                              (update msg :content add-cache-control-to-content)
                              msg)))
                         messages)))))
      conv)))

(defn- append-user-msg
  "Append one user message to an ai/conversation."
  [conv msg]
  (let [text-blocks (->> (:content msg)
                         (keep (fn [block]
                                 (when (= :text (:type block))
                                   (cond-> {:type :text
                                            :text (or (:text block) "")}
                                     (:cache-control block)
                                     (assoc :cache-control (:cache-control block))))))
                         vec)
        user-content (if (seq text-blocks)
                       text-blocks
                       (or (some #(when (= :text (:type %)) (:text %)) (:content msg))
                           (str (:content msg))))]
    (conv/add-user-message conv user-content)))

(defn- append-assistant-msg
  "Append one assistant message to an ai/conversation."
  [conv msg]
  (let [thinking-blocks (->> (:content msg)
                              (keep (fn [block]
                                      (when (= :thinking (:type block))
                                        (cond-> {:kind :thinking
                                                 :text (or (:text block) "")}
                                          (:provider block)  (assoc :provider (:provider block))
                                          (:signature block) (assoc :signature (:signature block)))))))
        text-parts      (keep #(when (= :text (:type %)) (:text %)) (:content msg))
        tool-calls      (filter #(= :tool-call (:type %)) (:content msg))
        text            (str/join "\n" text-parts)
        structured-blocks (vec
                           (concat
                            thinking-blocks
                            (when (seq text) [{:kind :text :text text}])
                            (map (fn [tc]
                                   {:kind  :tool-call
                                    :id    (:id tc)
                                    :name  (:name tc)
                                    :input (parse-args (:arguments tc))})
                                 tool-calls)))]
    (if (seq structured-blocks)
      (conv/add-assistant-message conv {:content {:kind :structured :blocks structured-blocks}})
      conv)))

(defn- append-tool-result-msg
  "Append one toolResult message to an ai/conversation."
  [conv msg]
  (let [text (or (some #(when (= :text (:type %)) (:text %)) (:content msg)) "")]
    (conv/add-tool-result conv
                          (:tool-call-id msg)
                          (:tool-name msg)
                          {:kind :text :text text}
                          (boolean (:is-error msg)))))

(defn- append-msg
  "Append one agent-core message to an ai/conversation, skipping unknown/extension roles."
  [conv msg]
  (if (:custom-type msg)
    conv ;; skip extension transcript markers
    (case (:role msg)
      "user"       (append-user-msg conv msg)
      "assistant"  (append-assistant-msg conv msg)
      "toolResult" (append-tool-result-msg conv msg)
      conv)))

(defn- add-tools-to-conv
  "Add agent tool definitions to an ai/conversation."
  [conv agent-tools tools-cache?]
  (reduce (fn [c tool]
            (conv/add-tool c
                           (cond-> {:name        (:name tool)
                                    :description (:description tool)
                                    :parameters  (parse-tool-parameters (:parameters tool))}
                             tools-cache?
                             (assoc :cache-control (maybe-cache-control tools-cache?)))))
          conv
          agent-tools))

(defn- agent-messages->ai-conversation
  "Rebuild an ai/conversation from agent-core message history.
  Used to reconstruct the conversation context before each LLM call.
  Includes tool definitions so the Anthropic API can offer tool_use.

  Messages with :custom-type are extension transcript markers (e.g. PSL status
  messages) and are excluded — they must not be sent to the LLM because they
  can produce consecutive same-role messages that cause provider 400 errors."
  [system-prompt messages agent-tools {:keys [cache-breakpoints]}]
  (let [cache-set     (set (or cache-breakpoints #{}))
        system-cache? (contains? cache-set :system)
        tools-cache?  (contains? cache-set :tools)
        conv          (reduce append-msg
                              (conv/create {:system-prompt        system-prompt
                                            :system-prompt-blocks (system-prompt/system-prompt-blocks
                                                                   system-prompt system-cache?)})
                              messages)]
    (-> conv
        (apply-message-cache-breakpoints system-cache? tools-cache?)
        (add-tools-to-conv agent-tools tools-cache?))))

;; ============================================================
;; Turn actions — agent-core integration on top of accumulation
;; ============================================================

(defn- emit-progress!
  "Emit a progress event to the progress queue (if provided).
   Events are maps with :type :agent-event and :event-kind."
  [progress-queue event]
  (when progress-queue
    (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
            (assoc event :type :agent-event))))

(defn- note-last-provider-event!
  [turn-data event-type data]
  (swap! turn-data assoc
         :last-provider-event
         (cond-> {:type      event-type
                  :timestamp (java.time.Instant/now)}
           (contains? data :content-index) (assoc :content-index (:content-index data))
           (contains? data :reason) (assoc :reason (:reason data))
           (contains? data :http-status) (assoc :http-status (:http-status data))
           (contains? data :error-message) (assoc :error-message (:error-message data)))))

(defn- update-content-block!
  [turn-data idx f]
  (swap! turn-data update :content-blocks
         (fn [blocks]
           (let [blocks* (or blocks (sorted-map))
                 current (get blocks* idx {:content-index idx
                                           :kind :unknown
                                           :status :open
                                           :delta-count 0})]
             (assoc blocks* idx (f current))))))

(defn- begin-content-block!
  [turn-data idx]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (let [block (assoc current
                          :content-index idx
                          :status :open
                          :ended-at nil)]
         (cond-> block
           (nil? (:started-at block)) (assoc :started-at ts)))))))

(defn- note-content-delta!
  [turn-data idx kind]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (let [block (-> current
                       (assoc :content-index idx
                              :kind kind
                              :status :open
                              :last-delta-at ts)
                       (update :delta-count (fnil inc 0)))]
         (cond-> block
           (nil? (:started-at block)) (assoc :started-at ts)))))))

(defn- end-content-block!
  [turn-data idx]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (assoc current
              :content-index idx
              :status :closed
              :ended-at ts)))))

(defn- common-prefix-length
  "Return length of common prefix shared by strings `a` and `b`."
  [a b]
  (let [a*    (or a "")
        b*    (or b "")
        limit (min (count a*) (count b*))]
    (loop [idx 0]
      (if (and (< idx limit)
               (= (.charAt ^String a* idx)
                  (.charAt ^String b* idx)))
        (recur (inc idx))
        idx))))

(defn- merge-stream-text
  "Merge current streamed assistant text with incoming provider chunk.

Supports both provider styles:
- incremental deltas (append)
- cumulative snapshots (replace when incoming extends current)

Also tolerates cumulative snapshots that differ near previous tail
(e.g. trailing newline churn while total text grows)."
  [current incoming]
  (let [current*  (or current "")
        incoming* (or incoming "")]
    (cond
      (empty? incoming*) current*
      (empty? current*) incoming*
      (str/starts-with? incoming* current*)
      incoming*

      :else
      (let [cur-len    (count current*)
            in-len     (count incoming*)
            cp         (common-prefix-length current* incoming*)
            tail-close (and (> in-len cur-len)
                            (>= cp (max 1 (dec cur-len))))]
        (if tail-close
          incoming*
          (str current* incoming*))))))

(defn- canonical-tool-call-id
  [turn-id content-index provider-tool-call-id]
  (or provider-tool-call-id
      (str turn-id "/toolcall/" content-index)))

(defn- complete-tool-calls
  [turn-id tool-calls]
  (->> tool-calls
       (sort-by key)
       (mapv (fn [[idx tc]]
               (let [content-index (or (:content-index tc) idx)]
                 (-> tc
                     (assoc :content-index content-index)
                     (update :id #(canonical-tool-call-id turn-id content-index %))))))))

(defn- invalid-tool-call
  [tc]
  (let [id  (:id tc)
        nm  (:name tc)
        raw (:arguments tc)
        {:keys [ok?]} (parse-args-strict raw)]
    (cond
      (or (nil? id) (str/blank? (str id)))
      {:reason :missing-call-id
       :message "Tool call missing call_id; cannot execute reliably"
       :tool-call tc}

      (or (nil? nm) (str/blank? (str nm)))
      {:reason :missing-tool-name
       :message "Tool call missing name; cannot execute reliably"
       :tool-call tc}

      (not ok?)
      {:reason :invalid-arguments
       :message "Tool call arguments invalid; expected JSON object"
       :tool-call tc}

      :else nil)))

(defn- thinking-blocks-in-order
  [thinking-blocks]
  (->> thinking-blocks
       vals
       (sort-by :content-index)
       (mapv (fn [{:keys [text provider signature]}]
               (cond-> {:type :thinking
                        :text (or text "")}
                 provider (assoc :provider provider)
                 signature (assoc :signature signature))))))

(defn- build-final-content
  [thinking-blocks text-buffer tool-calls]
  (let [invalids       (keep invalid-tool-call tool-calls)
        valid-calls    (->> tool-calls (remove invalid-tool-call))
        thinking-parts (thinking-blocks-in-order thinking-blocks)
        text-blocks    (cond-> []
                         (seq text-buffer) (conj {:type :text :text text-buffer}))
        error-blocks   (mapv (fn [invalid]
                               {:type :error :text (:message invalid)})
                             invalids)
        tool-blocks    (mapv (fn [tc]
                               {:type      :tool-call
                                :id        (:id tc)
                                :name      (:name tc)
                                :arguments (:arguments tc)})
                             valid-calls)]
    (-> thinking-parts
        (into text-blocks)
        (into error-blocks)
        (into tool-blocks))))

(defn- emit-tool-assembly-errors!
  [progress-queue tool-calls]
  (doseq [invalid (keep invalid-tool-call tool-calls)]
    (emit-progress! progress-queue
                    {:event-kind :error
                     :error      (:message invalid)
                     :detail     (:reason invalid)
                     :error-code "tool-call/assembly-failed"})))

(defn- append-tool-call-attempt!
  [ctx session-id attempt]
  (sa/append-tool-call-attempt-in! ctx session-id attempt))

(defn- provider-id
  [provider]
  (cond
    (keyword? provider) (name provider)
    (symbol? provider)  (name provider)
    (string? provider)  provider
    :else               (str (or provider ""))))

(defn- anthropic-provider?
  [ai-model]
  (= "anthropic" (provider-id (:provider ai-model))))

(defn- reset-thinking-buffers-on-toolcall-start!
  "Tool-call boundaries split thinking segments.

   Anthropic has explicit thinking block lifecycle + per-content-index semantics,
   so we only clear the matching index.
   OpenAI-style reasoning deltas are unframed; any toolcall marks a boundary, so
   clear all thinking buffers to avoid cross-boundary prefix accumulation."
  [thinking-buffers ai-model content-index]
  (if (anthropic-provider? ai-model)
    (swap! thinking-buffers dissoc content-index)
    (reset! thinking-buffers {})))

(defn- make-turn-actions
  "Create the actions-fn for the per-turn statechart.
   Handles data accumulation (in turn-data atom) and session-data writes.
   When progress-queue is non-nil, emits :agent-event messages for TUI.

   Tool-call identity semantics:
   - provider stream assembly is visible live to the UI
   - content-index is the provisional per-turn identity during assembly
   - every tool call is upgraded to a canonical tool-call-id by execution time
   - lifecycle/result events must target rows by canonical tool-call-id

   `ai-model` — used to dispatch provider-specific thinking accumulation (Anthropic vs OpenAI)
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
          (emit-progress! progress-queue
                          {:event-kind    :text-delta
                           :content-index idx
                           :text          merged}))

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
                   (fnil assoc (sorted-map))
                   idx
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
                     (let [blocks*  (or blocks (sorted-map))
                           current  (get blocks* idx {:content-index idx
                                                      :provider      :anthropic
                                                      :signature     nil})]
                       (assoc blocks* idx (assoc current :text merged))))))
          (emit-progress! progress-queue
                          {:event-kind    :thinking-delta
                           :content-index idx
                           :text          merged}))

        :on-thinking-signature-delta
        (let [idx (or (:content-index data) 0)]
          (when (anthropic-provider? ai-model)
            (swap! td update :thinking-blocks
                   (fn [blocks]
                     (let [blocks*  (or blocks (sorted-map))
                           current  (get blocks* idx {:content-index idx
                                                      :provider      :anthropic
                                                      :text          ""})]
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
          (append-tool-call-attempt! ctx session-id
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
                                                                 {:arguments (str current-args (or delta ""))
                                                                  :content-index idx})]
                                         (assoc merged :id (canonical-tool-call-id (:turn-id @td) idx (:id merged))))))
                              [:tool-calls idx])]
          (note-last-provider-event! td :toolcall-delta data)
          (note-content-delta! td idx :tool-call)
          (append-tool-call-attempt! ctx session-id
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
                           :provider-tool-call-id (when-not (str/starts-with? (str (:id updated)) (str (:turn-id @td) "/toolcall/"))
                                                    (:id updated))
                           :tool-name             (:name updated)
                           :arguments             (:arguments updated)}))

        :on-toolcall-end
        (let [idx     (:content-index data)
              updated (get-in @td [:tool-calls idx])]
          (note-last-provider-event! td :toolcall-end data)
          (end-content-block! td idx)
          (append-tool-call-attempt! ctx session-id
                                     {:turn-id       (:turn-id @td)
                                      :event-kind    :toolcall-end
                                      :content-index idx})
          (emit-progress! progress-queue
                          {:event-kind            :tool-call-assembly
                           :phase                 :end
                           :turn-id               (:turn-id @td)
                           :content-index         idx
                           :tool-id               (:id updated)
                           :provider-tool-call-id (when-not (str/starts-with? (str (:id updated)) (str (:turn-id @td) "/toolcall/"))
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
          ;; Tool-start progress is now emitted by the lifecycle system in
          ;; run-tool-call-through-runtime-effect! — no duplicate here.
          (emit-tool-assembly-errors! progress-queue completed)
          (swap! td assoc :final-message final)
          (session/journal-append-in! ctx session-id
                                      (persist/message-entry final))
          (deliver done-p final))

        :on-error
        (let [{:keys [text-buffer]} @td
              err-msg (:error-message data)
              content (cond-> []
                        (seq text-buffer) (conj {:type :text :text text-buffer})
                        :always           (conj {:type :error :text err-msg}))
              final (cond-> {:role          "assistant"
                             :content       content
                             :stop-reason   :error
                             :error-message err-msg
                             :timestamp     (java.time.Instant/now)}
                      (:http-status data) (assoc :http-status (:http-status data)))]
          (note-last-provider-event! td :error data)
          (swap! td assoc :final-message final :error-message err-msg)
          (session/journal-append-in! ctx session-id
                                      (persist/message-entry final))
          (deliver done-p final))

        :on-reset
        (reset! td (turn-sc/create-turn-data))

        ;; unknown — ignore
        nil))))

;;; Session-data reads — replace agent/get-data-in

(defn- session-messages
  "Derive the LLM conversation messages from the persistence journal."
  [ctx session-id]
  (into []
        (keep (fn [entry]
                (when (= :message (:kind entry))
                  (get-in entry [:data :message]))))
        (sa/journal-state-in ctx session-id)))

(defn- session-tool-schemas
  "Return the active tool schemas from session data."
  [session-data]
  (or (:tool-schemas session-data) []))

;; ============================================================
;; Single LLM turn
;; ============================================================

(defn- do-stream!
  "Invoke the appropriate streaming fn depending on whether ai-ctx is provided."
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (if ai-ctx
    (ai/stream-response-in ai-ctx ai-conv ai-model ai-options consume-fn)
    (ai/stream-response ai-conv ai-model ai-options consume-fn)))

(def ^:private llm-stream-idle-timeout-ms 600000)
(def ^:private llm-stream-wait-poll-ms 250)

(defn- now-ms []
  (System/currentTimeMillis))

(defn- append-provider-request-capture!
  [ctx session-id capture]
  (sa/append-provider-request-capture-in! ctx session-id capture))

(defn- append-provider-reply-capture!
  [ctx session-id capture]
  (sa/append-provider-reply-capture-in! ctx session-id capture))

(defn- chain-callbacks
  [& callbacks]
  (let [callbacks* (vec (keep #(when (fn? %) %) callbacks))]
    (when (seq callbacks*)
      (fn [payload]
        (doseq [cb callbacks*]
          (try
            (cb payload)
            (catch Exception _
              nil)))))))

(defn- wait-for-turn-result
  "Wait for `done-p` with an idle timeout.

   The timeout window resets whenever `last-progress-ms` is updated by
   incoming provider events (text/thinking/tool/done/error).

   Optional opts:
   - :idle-timeout-ms
   - :wait-poll-ms"
  [done-p last-progress-ms {:keys [idle-timeout-ms wait-poll-ms]}]
  (let [poll-ms    (max 1 (long (or wait-poll-ms llm-stream-wait-poll-ms)))
        timeout-ms (max 1 (long (or idle-timeout-ms llm-stream-idle-timeout-ms)))]
    (loop []
      (let [result (deref done-p poll-ms ::pending)]
        (cond
          (not= ::pending result)
          result

          (>= (- (now-ms) @last-progress-ms) timeout-ms)
          ::timeout

          :else
          (recur))))))

(defn- stream-turn!
  "Stream one LLM response into agent-core via the per-turn statechart.

   Creates a turn context (statechart + turn-data atom), sends :turn/start,
   then translates each provider event into a statechart event.  Blocks until
   the statechart reaches :done or :error.

   Stores the turn context in agent-session-ctx canonical state so it can be
   queried live from nREPL.

   `extra-ai-options` — merged into the ai-options map sent to the provider
                        (e.g. {:api-key \"...\"})"
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [sd               (session/get-session-data-in ctx session-id)
        turn-id          (str (java.util.UUID/randomUUID))
        system-prompt    (:system-prompt sd)
        messages         (session-messages ctx session-id)
        agent-tools      (session-tool-schemas sd)
        ai-conv          (agent-messages->ai-conversation system-prompt messages agent-tools
                                                          {:cache-breakpoints (:cache-breakpoints sd)})
        base-ai-options  (or extra-ai-options {})
        ai-options       (-> base-ai-options
                             (assoc :on-provider-request
                                    (chain-callbacks
                                     (:on-provider-request base-ai-options)
                                     (fn [capture]
                                       (append-provider-request-capture!
                                        ctx session-id
                                        (assoc capture :turn-id turn-id)))))
                             (assoc :on-provider-response
                                    (chain-callbacks
                                     (:on-provider-response base-ai-options)
                                     (fn [capture]
                                       (append-provider-reply-capture!
                                        ctx session-id
                                        (assoc capture :turn-id turn-id))))))
        done-p           (promise)
        ;; Per-content-index thinking buffers — Anthropic interleaved thinking can
        ;; emit multiple thinking blocks (each with a distinct content-index).
        ;; merge-stream-text normalises both cumulative-snapshot and incremental
        ;; provider styles. The emitted :text is the full accumulated thinking text
        ;; for the block so far (consumers should replace, not append).
        thinking-buffers (atom {})
        actions-fn       (make-turn-actions ctx session-id agent-ctx done-p progress-queue
                                            ai-model thinking-buffers)
        turn-ctx         (turn-sc/create-turn-context actions-fn)
        _                (swap! (:turn-data turn-ctx) assoc :turn-id turn-id)
        last-progress-ms (atom (now-ms))
        timed-out?       (atom false)]
    ;; Expose turn context for nREPL introspection
    (sa/set-turn-context-in! ctx session-id turn-ctx)
    ;; Transition: :idle → :text-accumulating
    (turn-sc/send-event! turn-ctx :turn/start)
    ;; Start provider stream.
    ;; State-transition events go through turn-sc/send-event! (updates statechart config).
    ;; Accumulation-only events (text-start/end, thinking-*) call actions-fn directly —
    ;; the statechart has no transitions for them, but make-turn-actions handles them.
    ;; actions-fn expects (action-key data) where data contains :turn-data atom.
    (let [call-action! (fn [action-key extra]
                         (actions-fn action-key
                                     (merge {:turn-data (:turn-data turn-ctx)} extra)))]
      (do-stream! ai-ctx ai-conv ai-model ai-options
                  (fn [event]
                    (when-not @timed-out?
                      (reset! last-progress-ms (now-ms))
                      (case (:type event)
                        :start                    nil ;; already transitioned via :turn/start
                        :text-start               (call-action! :on-text-start
                                                                {:content-index (:content-index event)})
                        :text-delta               (turn-sc/send-event! turn-ctx :turn/text-delta
                                                                       {:content-index (:content-index event)
                                                                        :delta         (:delta event)})
                        :text-end                 (call-action! :on-text-end
                                                                {:content-index (:content-index event)})
                        :thinking-start           (call-action! :on-thinking-start
                                                                {:content-index (:content-index event)
                                                                 :thinking      (:thinking event)
                                                                 :signature     (:signature event)})
                        :thinking-delta           (call-action! :on-thinking-delta
                                                                {:content-index (:content-index event)
                                                                 :delta         (:delta event)})
                        :thinking-signature-delta (call-action! :on-thinking-signature-delta
                                                                {:content-index (:content-index event)
                                                                 :signature     (:signature event)})
                        :thinking-end             (call-action! :on-thinking-end
                                                                {:content-index (:content-index event)})
                        :toolcall-start           (turn-sc/send-event! turn-ctx :turn/toolcall-start
                                                                       {:content-index (:content-index event)
                                                                        :tool-id       (:id event)
                                                                        :tool-name     (:name event)})
                        :toolcall-delta           (turn-sc/send-event! turn-ctx :turn/toolcall-delta
                                                                       {:content-index (:content-index event)
                                                                        :delta         (:delta event)})
                        :toolcall-end             (turn-sc/send-event! turn-ctx :turn/toolcall-end
                                                                       {:content-index (:content-index event)})
                        :done                     (turn-sc/send-event! turn-ctx :turn/done
                                                                       {:reason (:reason event)
                                                                        :usage  (:usage event)})
                        :error                    (turn-sc/send-event! turn-ctx :turn/error
                                                                       (cond-> {:error-message (:error-message event)}
                                                                         (:http-status event) (assoc :http-status (:http-status event))))
                        nil)))))
    ;; Block until streaming completes (idle timeout resets on stream progress)
    (let [result (wait-for-turn-result
                  done-p
                  last-progress-ms
                  {:idle-timeout-ms (:llm-stream-idle-timeout-ms ai-options)
                   :wait-poll-ms    (:llm-stream-wait-poll-ms ai-options)})]
      (if (= result ::timeout)
        (let [timeout-msg {:role          "assistant"
                           :content       [{:type  :error
                                            :text  "Timeout waiting for LLM response"}]
                           :stop-reason   :error
                           :error-message "Timeout waiting for LLM response"
                           :timestamp     (java.time.Instant/now)}]
          (reset! timed-out? true)
          (turn-sc/send-event! turn-ctx :turn/error
                               {:error-message "Timeout waiting for LLM response"})
          timeout-msg)
        result))))

;; ============================================================
;; Tool execution for one turn
;; ============================================================

(defn- extract-tool-calls
  "Extract tool call specs from an assistant message's content."
  [assistant-msg]
  (filter #(= :tool-call (:type %)) (:content assistant-msg)))

(defn- effective-tool-output-policy
  [ctx session-id tool-name]
  (tool-output/effective-policy
   (or (:tool-output-overrides (session/get-session-data-in ctx session-id)) {})
   tool-name))

(defn- utf8-bytes
  [s]
  (count (.getBytes (str (or s "")) "UTF-8")))

(defn- tool-content->text
  [content]
  (cond
    (string? content)
    content

    (sequential? content)
    (->> content
         (keep (fn [block]
                 (when (= :text (:type block))
                   (:text block))))
         (str/join "\n"))

    :else
    (str (or content ""))))

(defn- normalize-tool-content
  [content]
  (cond
    (sequential? content)
    (vec content)

    (string? content)
    [{:type :text :text content}]

    (nil? content)
    [{:type :text :text ""}]

    :else
    [{:type :text :text (str content)}]))

(defn- record-tool-output-stat!
  [ctx session-id {:keys [tool-call-id tool-name content details effective-policy]}]
  (let [truncation          (:truncation details)
        limit-hit?          (boolean (:truncated truncation))
        truncated-by        (or (:truncated-by truncation) :none)
        content-text        (tool-content->text content)
        output-bytes        (utf8-bytes content-text)
        context-bytes-added output-bytes
        stat                {:tool-call-id         tool-call-id
                             :tool-name            tool-name
                             :timestamp            (java.time.Instant/now)
                             :limit-hit            limit-hit?
                             :truncated-by         truncated-by
                             :effective-max-lines  (:max-lines effective-policy)
                             :effective-max-bytes  (:max-bytes effective-policy)
                             :output-bytes         output-bytes
                             :context-bytes-added  context-bytes-added}]
    (sa/record-tool-output-stat-in! ctx session-id stat context-bytes-added limit-hit?)))

(defn- tool-lifecycle-event
  "Build one canonical tool lifecycle event shape.

   `:tool-id` is the canonical tool-call-id used by the UI/runtime to route
   updates to the correct visible tool row, including parallel executions."
  [event-kind tool-id tool-name & {:as extra}]
  (merge {:event-kind event-kind
          :tool-id    tool-id
          :tool-name  tool-name}
         extra))

(defn- emit-tool-lifecycle!
  [ctx session-id lifecycle-event]
  (dispatch/dispatch! ctx
                      :session/tool-lifecycle-event
                      {:session-id session-id
                       :entry      lifecycle-event}
                      {:origin :core})
  lifecycle-event)

(defn- execute-tool-call!
  "Execute one tool call and return a shaped execution result before recording.

   This isolates tool execution from progress/agent-core recording.

   Output shape:
   - {:tool-call tc
      :tool-result raw-result
      :result-message tool-result-msg
      :effective-policy policy}"
  [ctx session-id tool-call progress-queue]
  (let [call-id  (:id tool-call)
        name     (:name tool-call)
        args     (or (:parsed-args tool-call)
                     (parse-args (:arguments tool-call)))]
    (emit-progress!
     progress-queue
     (emit-tool-lifecycle!
      ctx session-id
      (tool-lifecycle-event
       :tool-executing call-id name
       :arguments (:arguments tool-call)
       :parsed-args args)))
    (let [sd   (session/get-session-data-in ctx session-id)
          opts {:cwd          (or (:worktree-path sd) (:cwd ctx))
                :overrides    (:tool-output-overrides sd)
                :tool-call-id call-id
                :on-update    (fn [{:keys [content details is-error]}]
                                (let [content-blocks (normalize-tool-content content)
                                      text-fallback  (tool-content->text content)]
                                  (emit-progress!
                                   progress-queue
                                   (emit-tool-lifecycle!
                                    ctx session-id
                                    (tool-lifecycle-event
                                     :tool-execution-update call-id name
                                     :content content-blocks
                                     :result-text text-fallback
                                     :details details
                                     :is-error (boolean is-error))))))}
          {:keys [content is-error details] :as tool-result}
          (or (dispatch/dispatch! ctx
                                  :session/tool-execute
                                  {:session-id session-id
                                   :tool-name  name
                                   :args       args
                                   :opts       opts}
                                  {:origin :core})
              {:content "Error: tool execution returned no result"
               :is-error true})
          content-blocks (normalize-tool-content content)
          text-fallback  (tool-content->text content)
          policy         (effective-tool-output-policy ctx session-id name)
          result-msg     {:role         "toolResult"
                          :tool-call-id call-id
                          :tool-name    name
                          :content      content-blocks
                          :is-error     is-error
                          :details      details
                          :result-text  text-fallback
                          :timestamp    (java.time.Instant/now)}]
      {:tool-call         tool-call
       :tool-result       tool-result
       :result-message    result-msg
       :effective-policy  policy})))

(defn- record-tool-call-result!
  "Record one shaped tool execution result into progress, telemetry, and agent-core."
  [ctx session-id {:keys [tool-call tool-result result-message effective-policy]} progress-queue]
  (let [call-id         (:id tool-call)
        name            (:name tool-call)
        result-text     (or (:result-text result-message)
                            (tool-content->text (:content result-message)))
        {:keys [content is-error details]} tool-result]
    (emit-progress!
     progress-queue
     (emit-tool-lifecycle!
      ctx session-id
      (tool-lifecycle-event
       :tool-result call-id name
       :content (:content result-message)
       :result-text result-text
       :details details
       :is-error is-error)))
    (record-tool-output-stat!
     ctx session-id
     {:tool-call-id     call-id
      :tool-name        name
      :content          content
      :details          details
      :effective-policy effective-policy})
    (dispatch/dispatch! ctx
                        :session/tool-agent-end
                        {:session-id session-id
                         :tool-call  tool-call
                         :result     tool-result
                         :is-error?  is-error}
                        {:origin :core})
    (dispatch/dispatch! ctx
                        :session/tool-agent-record-result
                        {:session-id      session-id
                         :tool-result-msg result-message}
                        {:origin :core})
    result-message))

(defn run-tool-call-through-runtime-effect!
  "Execute one tool call inside the dispatch-owned runtime effect boundary.

   This owns the full tool-call transaction:
   start → execute → progress updates → telemetry/agent-core recording → result.

   Exposed as a named runtime helper so core can delegate `:runtime/tool-run`
   here without re-implementing executor-local shaping logic."
  [ctx session-id tool-call parsed-args progress-queue]
  (emit-progress!
   progress-queue
   (emit-tool-lifecycle!
    ctx session-id
    (tool-lifecycle-event :tool-start (:id tool-call) (:name tool-call))))
  (dispatch/dispatch! ctx
                      :session/tool-agent-start
                      {:session-id session-id
                       :tool-call  tool-call}
                      {:origin :core})
  (try
    (record-tool-call-result!
     ctx session-id
     (execute-tool-call! ctx session-id
                         (assoc tool-call :parsed-args parsed-args)
                         progress-queue)
     progress-queue)
    (catch Exception e
      (let [err-text   (str "Error: " (ex-message e))
            result-msg {:role         "toolResult"
                        :tool-call-id (:id tool-call)
                        :tool-name    (:name tool-call)
                        :content      [{:type :text :text err-text}]
                        :is-error     true
                        :details      {:exception true}
                        :result-text  err-text
                        :timestamp    (java.time.Instant/now)}]
        (record-tool-call-result!
         ctx session-id
         {:tool-call        tool-call
          :tool-result      {:content err-text :is-error true}
          :result-message   result-msg
          :effective-policy nil}
         progress-queue)))))

(defn- run-tool-call!
  "Execute one tool call through one dispatch-owned runtime boundary."
  [ctx session-id tool-call progress-queue]
  (dispatch/dispatch! ctx
                      :session/tool-run
                      {:session-id     session-id
                       :tool-call      tool-call
                       :parsed-args    (or (:parsed-args tool-call)
                                           (parse-args (:arguments tool-call)))
                       :progress-queue progress-queue}
                      {:origin :core}))

;; ============================================================
;; Public: run a full agent turn (recursive for tool loops)
;; ============================================================

(defn- classify-turn-outcome
  "Classify one completed streamed assistant message into the next turn boundary.

   Outcome shapes:
   - {:turn/outcome :turn.outcome/stop     :assistant-message msg}
   - {:turn/outcome :turn.outcome/tool-use :assistant-message msg :tool-calls [...]}
   - {:turn/outcome :turn.outcome/error    :assistant-message msg}

   This keeps stream assembly (`stream-turn!`) separate from the decision about
   whether the agent should stop, execute tools, or terminate on error."
  [assistant-msg]
  (let [tool-calls (vec (extract-tool-calls assistant-msg))]
    (cond
      (= :error (:stop-reason assistant-msg))
      {:turn/outcome     :turn.outcome/error
       :assistant-message assistant-msg}

      (seq tool-calls)
      {:turn/outcome     :turn.outcome/tool-use
       :assistant-message assistant-msg
       :tool-calls       tool-calls}

      :else
      {:turn/outcome     :turn.outcome/stop
       :assistant-message assistant-msg})))

(defn- execute-tool-calls!
  "Execute all tool calls from a tool-use outcome. Returns tool results."
  [ctx session-id outcome progress-queue]
  (mapv (fn [tc] (run-tool-call! ctx session-id tc progress-queue))
        (:tool-calls outcome)))

(defn- execute-one-turn!
  "Execute exactly one streamed assistant turn and return its explicit outcome.

   Output shape:
   - {:assistant-message msg
      :outcome outcome-map}

   This isolates single-turn execution from recursive multi-turn orchestration."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [assistant-msg (stream-turn! ai-ctx ctx session-id agent-ctx ai-model
                                    extra-ai-options progress-queue)
        outcome       (classify-turn-outcome assistant-msg)]
    {:assistant-message assistant-msg
     :outcome outcome}))

(defn- run-turn-loop!
  "Run the recursive multi-turn loop until one terminal assistant outcome.

   Delegates one-turn work to `execute-one-turn!` and only owns the loop control
   that follows explicit turn outcomes and continuations."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (let [{:keys [assistant-message outcome]}
        (execute-one-turn! ai-ctx ctx session-id agent-ctx ai-model
                           extra-ai-options progress-queue)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do
        (execute-tool-calls! ctx session-id outcome progress-queue)
        (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model
                        extra-ai-options progress-queue))

      :turn.outcome/stop
      assistant-message

      :turn.outcome/error
      assistant-message)))

(defn run-turn!
  "Drive one complete agent interaction loop:
     execute one turn → continue after tool-use when needed → loop until terminal.

   `ai-ctx`     — ai component context (has :provider-registry)
   `ctx`        — agent session context
   `session-id` — target session id
   `agent-ctx`  — agent-core context
   `ai-model`   — ai.schemas.Model map
   `extra-ai-options` — extra options merged into ai-options (e.g. {:api-key \"...\"})
   `progress-queue`   — optional LinkedBlockingQueue for TUI progress events

   Returns the final terminal assistant message map.
   Emits agent-core events throughout."
  ([ai-ctx ctx session-id agent-ctx ai-model]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model nil nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options]
   (run-turn! ai-ctx ctx session-id agent-ctx ai-model extra-ai-options nil))
  ([ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
   (run-turn-loop! ai-ctx ctx session-id agent-ctx ai-model
                   extra-ai-options progress-queue)))

(defn- session-thinking-level
  [ctx session-id]
  (:thinking-level (session/get-session-data-in ctx session-id)))

(defn- session-llm-stream-idle-timeout-ms
  [ctx]
  (let [v (get-in ctx [:config :llm-stream-idle-timeout-ms])]
    (when (and (number? v) (pos? v))
      (long v))))

(defn- agent-loop-options
  "Build the effective AI options for one agent loop from session/runtime inputs."
  [ctx session-id {:keys [api-key]}]
  (let [thinking-level  (session-thinking-level ctx session-id)
        idle-timeout-ms (session-llm-stream-idle-timeout-ms ctx)]
    (cond-> {}
      api-key (assoc :api-key api-key)
      (keyword? thinking-level) (assoc :thinking-level thinking-level)
      idle-timeout-ms (assoc :llm-stream-idle-timeout-ms idle-timeout-ms))))

(defn- run-agent-loop-body!
  "Execute the core turn loop for one agent loop lifecycle.

   Converts unexpected exceptions into canonical assistant error messages so the
   outer lifecycle boundary can finalize success/error uniformly."
  [ai-ctx ctx session-id agent-ctx ai-model extra-ai-options progress-queue]
  (try
    (run-turn! ai-ctx ctx session-id agent-ctx ai-model
               extra-ai-options progress-queue)
    (catch Throwable e
      (cond-> {:role "assistant" :content []
               :stop-reason :error
               :error-message (or (ex-message e) (.getMessage e) (str e))
               :timestamp (java.time.Instant/now)}
        (:status (ex-data e)) (assoc :http-status (:status (ex-data e)))))))

(defn- finish-agent-loop!
  "Finalize one agent loop lifecycle from the terminal assistant result.
  Sends :agent-end to the session statechart for the main session path.
  Child sessions (spawn-mode :agent) skip the statechart event —
  their lifecycle is managed by the workflow engine."
  [ctx session-id _agent-ctx result]
  (when (not= :agent (:spawn-mode (session/get-session-data-in ctx session-id)))
    (let [sc-env   (:sc-env ctx)
          sc-sid   (session/sc-session-id-in ctx session-id)
          messages (session-messages ctx session-id)]
      (when (and sc-env sc-sid)
        (sc/send-event! sc-env sc-sid
                        :session/agent-event
                        {:pending-agent-event {:type     :agent-end
                                               :messages messages}}))))
  result)

(defn run-agent-loop!
  "Run a complete agent loop starting from the current agent-core state.

   Sends :session/prompt to the session statechart externally (caller's job).
   This fn drives the internal turn loop and returns when the LLM stops
   requesting tool calls.

   Options (optional 6th arg map):
     :api-key        — API key to pass through to the provider (from OAuth store)
     :progress-queue — LinkedBlockingQueue for TUI progress events

   Timeout behavior:
     Idle timeout defaults to 600s and resets on any stream progress event.
     Session config key `:llm-stream-idle-timeout-ms` overrides that default.

   Returns the final assistant message."
  ([ai-ctx ctx session-id agent-ctx ai-model new-messages]
   (run-agent-loop! ai-ctx ctx session-id agent-ctx ai-model new-messages nil))
  ([ai-ctx ctx session-id agent-ctx ai-model new-messages opts]
   ;; Ensure user messages are in the journal before the turn loop reads them.
   ;; For the main session path, prompt-in! already journals user messages via
   ;; :session/prompt-submit. The duplicate append is harmless.
   (doseq [msg new-messages]
     (session/journal-append-in! ctx session-id (persist/message-entry msg)))
   (let [extra-ai-options (agent-loop-options ctx session-id opts)
         result           (run-agent-loop-body! ai-ctx ctx session-id agent-ctx ai-model
                                                extra-ai-options (:progress-queue opts))]
     (finish-agent-loop! ctx session-id agent-ctx result))))
