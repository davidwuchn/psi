(ns psi.agent-session.message-text
  "Helpers for extracting display text from assistant/user message content.

   Supports both canonical block vectors (`{:type :text ...}`) and
   structured conversation maps (`{:kind :structured :blocks [...]}`)."
  (:require
   [clojure.string :as str]))

(defn- ->kw
  [x]
  (cond
    (keyword? x) x
    (string? x)  (keyword x)
    :else        nil))

(defn- first-present
  [m ks]
  (some (fn [k]
          (when (contains? m k)
            (get m k)))
        ks))

(defn- content-kind
  [m]
  (->kw (first-present m [:type "type" :kind "kind"])))

(defn- block-text
  [m]
  (some-> (first-present m [:text "text"
                            :message "message"
                            :thinking "thinking"])
          str))

(defn- block-error-text
  [m]
  (some-> (first-present m [:text "text"
                            :message "message"
                            :error-message "error-message"
                            :errorMessage "errorMessage"
                            :error_message "error_message"])
          str))

(defn content-text-parts
  "Return ordered text parts from CONTENT.

   CONTENT may be:
   - string
   - vector/list of canonical blocks
   - map with `:kind :structured` + `:blocks`
   - map with `:kind :text`/`:type :text`"
  [content]
  (cond
    (nil? content)
    []

    (string? content)
    [content]

    (sequential? content)
    (mapcat content-text-parts content)

    (map? content)
    (let [kind   (content-kind content)
          blocks (first-present content [:blocks "blocks"])
          nested (first-present content [:content "content"])]
      (cond
        (= kind :structured)
        (content-text-parts blocks)

        (= kind :text)
        (if-let [t (block-text content)] [t] [])

        (contains? #{:error :tool-call :thinking :image} kind)
        []

        (contains? content :content)
        (content-text-parts nested)

        (contains? content "content")
        (content-text-parts nested)

        :else
        (if-let [t (block-text content)] [t] [])))

    :else
    [(str content)]))

(defn content-error-parts
  "Return ordered error text parts from CONTENT blocks.

   Supports both canonical and structured message content forms."
  [content]
  (cond
    (nil? content)
    []

    (string? content)
    []

    (sequential? content)
    (mapcat content-error-parts content)

    (map? content)
    (let [kind   (content-kind content)
          blocks (first-present content [:blocks "blocks"])
          nested (first-present content [:content "content"])]
      (cond
        (= kind :structured)
        (content-error-parts blocks)

        (= kind :error)
        (if-let [err (block-error-text content)] [err] [])

        (contains? content :content)
        (content-error-parts nested)

        (contains? content "content")
        (content-error-parts nested)

        :else
        []))

    :else
    []))

(defn content-text
  "Return CONTENT text as a single string (joined with newlines), or nil."
  [content]
  (when-let [parts (seq (content-text-parts content))]
    (str/join "\n" parts)))

(defn content-display-text
  "Return best-effort display text for CONTENT, including error parts.

   Error blocks are rendered as `[error] <text>`."
  [content]
  (let [text   (content-text content)
        errors (->> (content-error-parts content)
                    (map #(str "[error] " %))
                    seq)
        parts  (cond-> []
                 (string? text) (conj text)
                 errors (into errors))]
    (when (seq parts)
      (str/join "\n" parts))))

(def ^:private default-display-name-max-chars 48)

(defn short-display-text
  "Normalize whitespace in `text` and truncate to a compact single-line display form."
  ([text]
   (short-display-text text default-display-name-max-chars))
  ([text max-chars]
   (let [normalized (some-> text
                            str
                            (str/replace #"\s+" " ")
                            str/trim)]
     (when (seq normalized)
       (if (> (count normalized) max-chars)
         (str (subs normalized 0 (max 1 (dec max-chars))) "…")
         normalized)))))

(defn- slash-command-text?
  [text]
  (let [trimmed (some-> text str/trim)]
    (and (seq trimmed)
         (str/starts-with? trimmed "/"))))

(defn user-message-display-text
  "Return compact display text for a user MESSAGE map, or nil.

   Slash-command inputs (for example `/tree`, `/status`, `/tree name ...`) are
   ignored so session labels and fork affordances reflect conversational/task
   content rather than navigation commands."
  [message]
  (when (= "user" (:role message))
    (let [text (short-display-text (content-text (:content message)))]
      (when-not (slash-command-text? text)
        text))))

(defn last-user-message-text
  "Return the most recent non-command user message text from agent-core `messages`, or nil."
  [messages]
  (some->> (reverse (vec (or messages [])))
           (some user-message-display-text)))

(defn session-display-name
  "Return live display name from explicit `session-name` or the latest user message."
  [session-name messages]
  (or (short-display-text session-name)
      (last-user-message-text messages)))
