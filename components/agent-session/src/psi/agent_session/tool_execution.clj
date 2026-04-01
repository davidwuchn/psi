(ns psi.agent-session.tool-execution
  "Tool call execution — content normalization, output stats, lifecycle events,
   execute/record pipeline, and the public runtime-effect boundary.

   Public entry points:
   - run-tool-call-through-runtime-effect! — full start→execute→record transaction"
  (:require
   [clojure.string :as str]
   [psi.agent-session.conversation :as conv-translate]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.turn-accumulator :as accum]))

;; ============================================================
;; Content helpers
;; ============================================================

(defn- utf8-bytes [s]
  (count (.getBytes (str (or s "")) "UTF-8")))

(defn tool-content->text
  "Extract plain text from tool content (string, block-vec, or other)."
  [content]
  (cond
    (string? content)
    content

    (sequential? content)
    (->> content
         (keep (fn [block] (when (= :text (:type block)) (:text block))))
         (str/join "\n"))

    :else
    (str (or content ""))))

(defn normalize-tool-content
  "Coerce tool content into a vec of {:type :text :text ...} blocks."
  [content]
  (cond
    (sequential? content) (vec content)
    (string? content)     [{:type :text :text content}]
    (nil? content)        [{:type :text :text ""}]
    :else                 [{:type :text :text (str content)}]))

;; ============================================================
;; Output stats
;; ============================================================

(defn- record-tool-output-stat!
  [ctx session-id {:keys [tool-call-id tool-name content details effective-policy]}]
  (let [truncation          (:truncation details)
        limit-hit?          (boolean (:truncated truncation))
        truncated-by        (or (:truncated-by truncation) :none)
        content-text        (tool-content->text content)
        output-bytes        (utf8-bytes content-text)
        stat                {:tool-call-id        tool-call-id
                             :tool-name           tool-name
                             :timestamp           (java.time.Instant/now)
                             :limit-hit           limit-hit?
                             :truncated-by        truncated-by
                             :effective-max-lines (:max-lines effective-policy)
                             :effective-max-bytes (:max-bytes effective-policy)
                             :output-bytes        output-bytes
                             :context-bytes-added output-bytes}]
    (sa/record-tool-output-stat-in! ctx session-id stat output-bytes limit-hit?)))

;; ============================================================
;; Lifecycle events
;; ============================================================

(defn tool-lifecycle-event
  "Build one canonical tool lifecycle event shape."
  [event-kind tool-id tool-name & {:as extra}]
  (merge {:event-kind event-kind :tool-id tool-id :tool-name tool-name} extra))

(defn- emit-tool-lifecycle!
  [ctx session-id lifecycle-event]
  (dispatch/dispatch! ctx
                      :session/tool-lifecycle-event
                      {:session-id session-id :entry lifecycle-event}
                      {:origin :core})
  lifecycle-event)

;; ============================================================
;; Effective policy
;; ============================================================

(defn- effective-tool-output-policy [ctx session-id tool-name]
  (tool-output/effective-policy
   (or (:tool-output-overrides (session/get-session-data-in ctx session-id)) {})
   tool-name))

;; ============================================================
;; Execute one tool call
;; ============================================================

(defn- execute-tool-call!
  "Execute one tool call and return a shaped result map before recording.

   Output: {:tool-call tc :tool-result raw :result-message msg :effective-policy policy}"
  [ctx session-id tool-call progress-queue]
  (let [call-id (:id tool-call)
        name    (:name tool-call)
        args    (or (:parsed-args tool-call) (conv-translate/parse-args (:arguments tool-call)))]
    (accum/emit-progress!
     progress-queue
     (emit-tool-lifecycle!
      ctx session-id
      (tool-lifecycle-event :tool-executing call-id name
                            :arguments (:arguments tool-call)
                            :parsed-args args)))
    (let [sd   (session/get-session-data-in ctx session-id)
          opts {:cwd          (or (:worktree-path sd) (:cwd ctx))
                :overrides    (:tool-output-overrides sd)
                :tool-call-id call-id
                :on-update    (fn [{:keys [content details is-error]}]
                                (let [content-blocks (normalize-tool-content content)
                                      text-fallback  (tool-content->text content)]
                                  (accum/emit-progress!
                                   progress-queue
                                   (emit-tool-lifecycle!
                                    ctx session-id
                                    (tool-lifecycle-event :tool-execution-update call-id name
                                                          :content content-blocks
                                                          :result-text text-fallback
                                                          :details details
                                                          :is-error (boolean is-error))))))}
          {:keys [content is-error details] :as tool-result}
          (or (dispatch/dispatch! ctx
                                  :session/tool-execute
                                  {:session-id session-id :tool-name name :args args :opts opts}
                                  {:origin :core})
              {:content "Error: tool execution returned no result" :is-error true})
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
      {:tool-call        tool-call
       :tool-result      tool-result
       :result-message   result-msg
       :effective-policy policy})))

;; ============================================================
;; Record result
;; ============================================================

(defn- record-tool-call-result!
  "Record one shaped execution result into progress, telemetry, and agent-core."
  [ctx session-id {:keys [tool-call tool-result result-message effective-policy]} progress-queue]
  (let [call-id     (:id tool-call)
        name        (:name tool-call)
        result-text (or (:result-text result-message)
                        (tool-content->text (:content result-message)))
        {:keys [content is-error details]} tool-result]
    (accum/emit-progress!
     progress-queue
     (emit-tool-lifecycle!
      ctx session-id
      (tool-lifecycle-event :tool-result call-id name
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
    (dispatch/dispatch! ctx :session/tool-agent-end
                        {:session-id session-id :tool-call tool-call :result tool-result :is-error? is-error}
                        {:origin :core})
    (dispatch/dispatch! ctx :session/tool-agent-record-result
                        {:session-id session-id :tool-result-msg result-message}
                        {:origin :core})
    result-message))

;; ============================================================
;; Public runtime-effect boundary
;; ============================================================

(defn run-tool-call-through-runtime-effect!
  "Execute one tool call inside the dispatch-owned runtime effect boundary.

   Owns the full tool-call transaction:
   start → execute → progress updates → telemetry/agent-core recording → result.

   Exposed so core can delegate :runtime/tool-run here without
   re-implementing executor-local shaping logic."
  [ctx session-id tool-call parsed-args progress-queue]
  (accum/emit-progress!
   progress-queue
   (emit-tool-lifecycle!
    ctx session-id
    (tool-lifecycle-event :tool-start (:id tool-call) (:name tool-call))))
  (dispatch/dispatch! ctx :session/tool-agent-start
                      {:session-id session-id :tool-call tool-call}
                      {:origin :core})
  (try
    (record-tool-call-result!
     ctx session-id
     (execute-tool-call! ctx session-id (assoc tool-call :parsed-args parsed-args) progress-queue)
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
