(ns psi.ai.content
  "Provider-neutral helpers for reading canonical AI message/content shapes."
  (:require [clojure.string :as str]))

(defn- message-content
  [x]
  (if (and (map? x) (contains? x :content))
    (:content x)
    x))

(defn structured-blocks
  "Return canonical structured blocks from a MESSAGE or CONTENT value."
  [message-or-content]
  (let [content (message-content message-or-content)]
    (when (= :structured (:kind content))
      (:blocks content))))

(defn text-blocks
  "Return canonical :text blocks from MESSAGE, CONTENT, or BLOCK seq."
  [x]
  (let [blocks (cond
                 (sequential? x) x
                 :else (structured-blocks x))]
    (->> blocks
         (filter #(= :text (:kind %))))))

(defn thinking-blocks
  "Return canonical :thinking blocks from MESSAGE, CONTENT, or BLOCK seq."
  [x]
  (let [blocks (cond
                 (sequential? x) x
                 :else (structured-blocks x))]
    (->> blocks
         (filter #(= :thinking (:kind %))))))

(defn tool-call-blocks
  "Return canonical :tool-call blocks from MESSAGE, CONTENT, or BLOCK seq."
  [x]
  (let [blocks (cond
                 (sequential? x) x
                 :else (structured-blocks x))]
    (->> blocks
         (filter #(= :tool-call (:kind %))))))

(defn join-text-blocks
  "Join canonical text blocks into a single string separated by newlines."
  [blocks]
  (->> blocks
       (keep :text)
       (str/join "\n")))

(defn content-text
  "Return best-effort text for canonical MESSAGE or CONTENT.

   Supports:
   - string content
   - {:kind :text :text ...}
   - {:kind :structured :blocks [...]} (joins text + thinking block text)
   - provider-style maps with :text / \"text\" as a fallback
   - any other value via str coercion"
  [message-or-content]
  (let [content (message-content message-or-content)]
    (cond
      (nil? content)
      nil

      (string? content)
      content

      (and (map? content)
           (= :text (:kind content)))
      (:text content)

      (and (map? content)
           (= :structured (:kind content)))
      (let [parts (concat (map :text (text-blocks content))
                          (map :text (thinking-blocks content)))
            text  (str/join "\n" (remove nil? parts))]
        (when (seq text)
          text))

      (map? content)
      (or (:text content)
          (get content "text")
          (str content))

      :else
      (str content))))

(defn assistant-content-parts
  "Partition canonical assistant MESSAGE or CONTENT into provider-neutral parts."
  [message-or-content]
  (let [blocks (vec (or (structured-blocks message-or-content) []))]
    {:blocks blocks
     :text-blocks (vec (text-blocks blocks))
     :thinking-blocks (vec (thinking-blocks blocks))
     :tool-call-blocks (vec (tool-call-blocks blocks))}))
