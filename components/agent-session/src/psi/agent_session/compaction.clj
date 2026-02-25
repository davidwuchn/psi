(ns psi.agent-session.compaction
  "Compaction support: preparation logic and injectable execute fn.

   The actual summarisation algorithm (LLM call) is deferred and injected
   as a `:compaction-fn` in the session context.  This namespace owns:

     - CompactionPreparation construction (which entries to summarise vs keep)
     - The stub/default compaction fn (returns a placeholder summary)
     - Helpers used by the session statechart's :compacting state

   The context carries:
     :compaction-fn — (fn [session preparation custom-instructions]) → CompactionResult
                       Default: stub-compaction-fn (no LLM call)

   Production callers replace :compaction-fn with a real implementation that
   calls the LLM.  Tests use the stub."
  )

;; ============================================================
;; CompactionPreparation
;; ============================================================

(defn prepare-compaction
  "Compute a CompactionPreparation from `session-data`.

  Selects entries to summarise vs. to keep based on
  `keep-recent-tokens`.  Since we do not have exact token counts per
  entry at this layer, we use message count as a proxy: the most recent
  `keep-count` messages are kept; everything before is summarised.

  Returns:
    {:entries-to-summarise [SessionEntry ...]
     :first-kept-entry-id  String or nil
     :tokens-before        Integer or nil}"
  ([session-data] (prepare-compaction session-data 10))
  ([session-data keep-count]
   (let [entries      (:session-entries session-data)
         n            (count entries)
         split-at     (max 0 (- n keep-count))
         to-summarise (vec (take split-at entries))
         to-keep      (vec (drop split-at entries))
         first-kept   (some-> to-keep first :id)]
     {:entries-to-summarise to-summarise
      :first-kept-entry-id  first-kept
      :tokens-before        (:context-tokens session-data)})))

;; ============================================================
;; Stub compaction fn (default / test)
;; ============================================================

(defn stub-compaction-fn
  "Default (stub) compaction function.  Returns a placeholder CompactionResult
  without calling the LLM.  Replace in production with a real implementation."
  [_session-data preparation _custom-instructions]
  {:summary           (str "Compacted "
                           (count (:entries-to-summarise preparation))
                           " entries. [stub — replace :compaction-fn for real summarisation]")
   :first-kept-entry-id (:first-kept-entry-id preparation)
   :tokens-before     (:tokens-before preparation)
   :details           nil})

;; ============================================================
;; Branch summary stub
;; ============================================================

(defn stub-branch-summary-fn
  "Default (stub) branch summarisation fn.  Returns a placeholder summary."
  [_session-data _abandoned-entries _custom-instructions]
  {:summary "Branch summary. [stub — replace :branch-summary-fn for real summarisation]"
   :details nil})

;; ============================================================
;; Rebuild messages from kept entries
;;
;; After compaction the agent's message list is replaced with:
;;   [system-summary-message] ++ messages-from-kept-entries
;; This is called by the session statechart's :compact-done action.
;; ============================================================

(defn rebuild-messages-from-entries
  "Given a compaction `result` and current `session-data`, return the
  vector of agent messages that should replace the agent's message list.

  Inserts a synthetic user message carrying the compaction summary,
  followed by the messages from kept session entries."
  [result session-data]
  (let [summary-msg  {:role      "user"
                      :content   [{:type :text
                                   :text (str "Previous conversation summary:\n\n"
                                              (:summary result))}]
                      :timestamp (java.time.Instant/now)}
        kept-entries (drop-while #(not= (:id %) (:first-kept-entry-id result))
                                 (:session-entries session-data))
        kept-msgs    (keep (fn [entry]
                             (when (= (:kind entry) :message)
                               (get-in entry [:data :message])))
                           kept-entries)]
    (into [summary-msg] kept-msgs)))
