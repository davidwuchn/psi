(ns psi.app-runtime.transcript
  (:require
   [clojure.string :as str]
   [psi.agent-session.message-text :as message-text]))

(defn message->display-text
  [msg]
  (or (message-text/content-display-text (:content msg))
      ""))

(defn- ->kw
  [x]
  (cond
    (keyword? x) x
    (string? x)  (keyword x)
    :else        nil))

(defn- block-attr
  [block k]
  (or (get block k)
      (get block (name k))))

(defn- tool-call-block?
  [block]
  (= :tool-call (->kw (or (block-attr block :type)
                          (block-attr block :kind)))))

(defn- normalize-tool-call-block
  [block]
  {:id        (block-attr block :id)
   :name      (block-attr block :name)
   :arguments (or (block-attr block :arguments)
                  (some-> (block-attr block :input) pr-str)
                  "")})

(defn- assistant-tool-call-blocks
  [content]
  (cond
    (sequential? content)
    (->> content
         (filter map?)
         (filter tool-call-block?)
         (mapv normalize-tool-call-block))

    (and (map? content)
         (= :structured (->kw (block-attr content :kind))))
    (assistant-tool-call-blocks (block-attr content :blocks))

    :else
    []))

(defn- tool-result->display-text
  [msg]
  (let [text (message->display-text msg)]
    (when-not (str/blank? text)
      text)))

(defn agent-messages->tui-resume-state
  [messages]
  (reduce
   (fn [acc msg]
     (case (:role msg)
       "user"
       (let [text (message->display-text msg)]
         (update acc :messages conj {:role :user
                                     :text (if (str/blank? text) "[user]" text)}))

       "assistant"
       (let [text        (message->display-text msg)
             tool-blocks (assistant-tool-call-blocks (:content msg))
             acc'        (if (str/blank? text)
                           acc
                           (update acc :messages conj {:role :assistant :text text}))]
         (reduce
          (fn [a block]
            (let [id (:id block)
                  tc {:name      (:name block)
                      :args      (or (:arguments block) "")
                      :status    :pending
                      :result    nil
                      :is-error  false
                      :expanded? false}]
              (-> a
                  (update :tool-calls #(if (contains? % id) % (assoc % id tc)))
                  (update :tool-order #(if (some #{id} %) % (conj % id))))))
          acc'
          tool-blocks))

       "toolResult"
       (let [id       (:tool-call-id msg)
             text     (tool-result->display-text msg)
             content  (:content msg)
             details  (:details msg)
             err?     (boolean (:is-error msg))
             fallback {:name      (:tool-name msg)
                       :args      ""
                       :status    (if err? :error :success)
                       :result    text
                       :content   content
                       :details   details
                       :is-error  err?
                       :expanded? false}]
         (-> acc
             (update :tool-calls
                     (fn [m]
                       (if-let [tc (get m id)]
                         (assoc m id
                                (-> tc
                                    (assoc :status (if err? :error :success)
                                           :content content
                                           :details details
                                           :is-error err?)
                                    (cond-> text (assoc :result text))))
                         (assoc m id fallback))))
             (update :tool-order #(if (some #{id} %) % (conj % id)))))

       acc))
   {:messages [] :tool-calls {} :tool-order []}
   messages))
