(ns psi.agent-session.runtime
  "Shared runtime helpers for prompt execution across CLI, TUI, and RPC.

   Provides one path for:
   - input expansion (skills/templates)
   - memory recover/remember hooks
   - user message journaling
   - API key resolution
   - agent loop execution + context usage update"
  (:require
   [psi.agent-session.core :as session]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.memory.runtime :as memory-runtime]))

(defn make-user-message
  "Create a canonical user message map from text and optional image blocks."
  ([text]
   (make-user-message text nil))
  ([text images]
   {:role      "user"
    :content   (cond-> [{:type :text :text text}]
                 (seq images) (into images))
    :timestamp (java.time.Instant/now)}))

(defn journal-user-message-in!
  "Append a raw user message to the session journal and return it.
   Use for command traces that should not trigger expansion/memory hooks."
  ([ctx text]
   (journal-user-message-in! ctx text nil))
  ([ctx text images]
   (let [user-msg (make-user-message text images)]
     (session/journal-append-in! ctx (persist/message-entry user-msg))
     user-msg)))

(defn expand-input-in
  "Expand user input through skills (/skill:name) or prompt templates (/name).

   Returns:
   {:text <expanded-text>
    :expansion {:kind :skill|:template :name string} | nil}"
  [ctx text]
  (let [sd            (session/get-session-data-in ctx)
        loaded-skills (:skills sd)
        templates     (:prompt-templates sd)
        commands      (ext/command-names-in (:extension-registry ctx))]
    (if-let [skill-result (skills/invoke-skill loaded-skills text)]
      {:text      (:content skill-result)
       :expansion {:kind :skill :name (:skill-name skill-result)}}
      (if-let [tpl-result (pt/invoke-template templates commands text)]
        {:text      (:content tpl-result)
         :expansion {:kind :template :name (:source-template tpl-result)}}
        {:text      text
         :expansion nil}))))

(defn- safe-recover-memory!
  [query-text]
  (try
    (memory-runtime/recover-for-query! query-text)
    (catch Exception _
      nil)))

(defn- safe-remember-session-message!
  [ctx msg]
  (try
    (memory-runtime/remember-session-message!
     msg
     {:session-id (:session-id (session/get-session-data-in ctx))})
    (catch Exception _
      nil)))

(defn prepare-user-message-in!
  "Expand text, run memory hooks, journal the user message, and return payload.

   Returns:
   {:user-message map
    :expanded-text string
    :expansion {:kind ... :name ...} | nil}"
  ([ctx text]
   (prepare-user-message-in! ctx text nil))
  ([ctx text images]
   (let [{:keys [text expansion]} (expand-input-in ctx text)
         _        (safe-recover-memory! text)
         user-msg (make-user-message text images)]
     (session/journal-append-in! ctx (persist/message-entry user-msg))
     (safe-remember-session-message! ctx user-msg)
     {:user-message  user-msg
      :expanded-text text
      :expansion     expansion})))

(defn resolve-api-key-in
  "Resolve API key for ai-model provider from session oauth context, if any."
  [ctx ai-model]
  (when-let [oauth-ctx (:oauth-ctx ctx)]
    (oauth/get-api-key oauth-ctx (:provider ai-model))))

(defn usage->context-tokens
  "Best-effort context token count from an assistant usage map."
  [usage]
  (when (map? usage)
    (let [input  (or (:input-tokens usage) 0)
          output (or (:output-tokens usage) 0)
          read   (or (:cache-read-tokens usage) 0)
          write  (or (:cache-write-tokens usage) 0)
          total  (or (:total-tokens usage)
                     (+ input output read write))]
      (when (and (number? total) (pos? total))
        total))))

(defn update-context-usage-from-result-in!
  "Update session context usage from assistant result usage when available."
  [ctx ai-model result]
  (let [tokens (usage->context-tokens (:usage result))
        window (:context-window ai-model)]
    (when (and (some? tokens) (number? window) (pos? window))
      (session/update-context-usage-in! ctx tokens window))))

(defn run-agent-loop-in!
  "Run executor loop with shared option shaping and usage updates.

   opts:
   - :run-loop-fn   custom runner (default executor/run-agent-loop!)
   - :turn-ctx-atom optional turn context atom
   - :api-key       optional provider API key
   - :progress-queue optional LinkedBlockingQueue"
  ([ctx ai-ctx ai-model user-messages]
   (run-agent-loop-in! ctx ai-ctx ai-model user-messages nil))
  ([ctx ai-ctx ai-model user-messages {:keys [run-loop-fn turn-ctx-atom api-key progress-queue]}]
   (let [runner (or run-loop-fn executor/run-agent-loop!)
         opts   (cond-> {}
                  (or turn-ctx-atom (:turn-ctx-atom ctx))
                  (assoc :turn-ctx-atom (or turn-ctx-atom (:turn-ctx-atom ctx)))

                  api-key
                  (assoc :api-key api-key)

                  progress-queue
                  (assoc :progress-queue progress-queue))
         result (runner ai-ctx ctx (:agent-ctx ctx) ai-model user-messages opts)]
     (update-context-usage-from-result-in! ctx ai-model result)
     result)))
