(ns psi.ai.providers.openai.reasoning)

(def thinking-level->effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(def reasoning-part-types
  #{"reasoning"
    "reasoning_text"
    "reasoning_content"
    "reasoning_summary"
    "summary"
    "summary_text"})

(defn reasoning-effort
  "Return provider reasoning effort string for MODEL/OPTIONS, or nil when disabled."
  [model options]
  (when (:supports-reasoning model)
    (get thinking-level->effort
         (:thinking-level options)
         "medium")))
