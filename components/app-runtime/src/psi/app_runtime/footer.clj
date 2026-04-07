(ns psi.app-runtime.footer
  "Adapter-neutral footer semantic projection shared across interactive UIs."
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]))

(def footer-query
  [:psi.agent-session/cwd
   :psi.agent-session/git-branch
   :psi.agent-session/session-name
   :psi.agent-session/session-display-name
   :psi.agent-session/usage-input
   :psi.agent-session/usage-output
   :psi.agent-session/usage-cache-read
   :psi.agent-session/usage-cache-write
   :psi.agent-session/usage-cost-total
   :psi.agent-session/context-fraction
   :psi.agent-session/context-window
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/model-provider
   :psi.agent-session/model-id
   :psi.agent-session/model-reasoning
   :psi.agent-session/thinking-level
   :psi.agent-session/effective-reasoning-effort
   :psi.ui/statuses])

(defn footer-data
  ([ctx]
   (try
     (or (session/query-in ctx footer-query) {})
     (catch Throwable _
       {})))
  ([ctx session-id]
   (try
     (or (if session-id
           (session/query-in ctx session-id footer-query)
           (session/query-in ctx footer-query))
         {})
     (catch Throwable _
       {}))))

(defn- format-token-count
  [n]
  (let [n (or n 0)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (format "%.1fk" (/ n 1000.0))
      (< n 1000000) (str (Math/round (double (/ n 1000.0))) "k")
      (< n 10000000) (format "%.1fM" (/ n 1000000.0))
      :else (str (Math/round (double (/ n 1000000.0))) "M"))))

(defn- replace-home-with-tilde
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and (string? path)
             (string? home)
             (str/starts-with? path home))
      (str "~" (subs path (count home)))
      (or path ""))))

(defn- positive-number?
  [n]
  (and (number? n) (pos? (double n))))

(defn- normalize-thinking-level
  [level]
  (cond
    (keyword? level) (name level)
    (string? level)  level
    :else            "off"))

(defn- footer-context-text
  [fraction context-window auto-compact?]
  (let [suffix (if auto-compact? " (auto)" "")
        window (format-token-count (or context-window 0))]
    (if (number? fraction)
      (str (format "%.1f" (* 100.0 fraction)) "%/" window suffix)
      (str "?/" window suffix))))

(defn- usage-parts
  [d context-text]
  (let [usage-input       (or (:psi.agent-session/usage-input d) 0)
        usage-output      (or (:psi.agent-session/usage-output d) 0)
        usage-cache-read  (or (:psi.agent-session/usage-cache-read d) 0)
        usage-cache-write (or (:psi.agent-session/usage-cache-write d) 0)
        usage-cost-total  (or (:psi.agent-session/usage-cost-total d) 0.0)]
    (cond-> []
      (positive-number? usage-input)
      (conj (str "↑" (format-token-count usage-input)))

      (positive-number? usage-output)
      (conj (str "↓" (format-token-count usage-output)))

      (positive-number? usage-cache-read)
      (conj (str "CR" (format-token-count usage-cache-read)))

      (positive-number? usage-cache-write)
      (conj (str "CW" (format-token-count usage-cache-write)))

      (positive-number? usage-cost-total)
      (conj (format "$%.3f" (double usage-cost-total)))

      :always
      (conj context-text))))

(defn- model-text
  [d]
  (let [model-provider   (:psi.agent-session/model-provider d)
        model-id         (:psi.agent-session/model-id d)
        model-reasoning? (boolean (:psi.agent-session/model-reasoning d))
        thinking-level   (:psi.agent-session/thinking-level d)
        thinking-label   (normalize-thinking-level thinking-level)
        effort-label     (or (:psi.agent-session/effective-reasoning-effort d)
                             (when (not= "off" thinking-label)
                               thinking-label))
        model-label      (or model-id "no-model")
        provider-label   (or model-provider "no-provider")
        right-base       (if model-reasoning?
                           (if (= "off" thinking-label)
                             (str model-label " • thinking off")
                             (str model-label " • thinking " effort-label))
                           model-label)]
    (str "(" provider-label ") " right-base)))

(defn- path-text
  [d fallback-cwd]
  (let [cwd                  (or (:psi.agent-session/cwd d) fallback-cwd "")
        git-branch           (:psi.agent-session/git-branch d)
        session-display-name (or (:psi.agent-session/session-display-name d)
                                 (:psi.agent-session/session-name d))
        path0                (replace-home-with-tilde cwd)
        path1                (if (seq git-branch)
                               (str path0 " (" git-branch ")")
                               path0)]
    (if (seq session-display-name)
      (str path1 " • " session-display-name)
      path1)))

(defn- sanitize-status-text
  [text]
  (-> (or text "")
      (str/replace #"[\r\n\t]" " ")
      (str/replace #" +" " ")
      (str/trim)))

(defn- status-items
  [statuses]
  (->> (or statuses [])
       (sort-by #(or (:extension-id %) (:extensionId %) ""))
       (mapv (fn [status]
               {:status/extension-id (or (:extension-id status) (:extensionId status))
                :status/text         (sanitize-status-text (or (:text status) (:message status)))}))))

(defn footer-model-from-data
  ([d]
   (footer-model-from-data d {}))
  ([d {:keys [cwd]}]
   (let [context-fraction  (:psi.agent-session/context-fraction d)
         context-window    (:psi.agent-session/context-window d)
         auto-compact?     (boolean (:psi.agent-session/auto-compaction-enabled d))
         context-text      (footer-context-text context-fraction context-window auto-compact?)
         usage-parts*      (usage-parts d context-text)
         model-text*       (model-text d)
         path-text*        (path-text d cwd)
         statuses*         (status-items (:psi.ui/statuses d))
         status-line       (->> statuses*
                                (map :status/text)
                                (remove str/blank?)
                                (str/join " "))
         stats-line        (str/join " " (concat usage-parts* [model-text*]))
         thinking-level    (normalize-thinking-level (:psi.agent-session/thinking-level d))]
     {:footer/path {:cwd                  (or (:psi.agent-session/cwd d) cwd "")
                    :git-branch           (:psi.agent-session/git-branch d)
                    :session-name         (:psi.agent-session/session-name d)
                    :session-display-name (or (:psi.agent-session/session-display-name d)
                                              (:psi.agent-session/session-name d))
                    :text                 path-text*}
      :footer/usage {:input       (or (:psi.agent-session/usage-input d) 0)
                     :output      (or (:psi.agent-session/usage-output d) 0)
                     :cache-read  (or (:psi.agent-session/usage-cache-read d) 0)
                     :cache-write (or (:psi.agent-session/usage-cache-write d) 0)
                     :cost-total  (or (:psi.agent-session/usage-cost-total d) 0.0)
                     :parts       usage-parts*}
      :footer/context {:fraction                 context-fraction
                       :window                   context-window
                       :auto-compaction-enabled auto-compact?
                       :text                     context-text}
      :footer/model {:provider                   (:psi.agent-session/model-provider d)
                     :id                         (:psi.agent-session/model-id d)
                     :reasoning                  (boolean (:psi.agent-session/model-reasoning d))
                     :thinking-level             thinking-level
                     :effective-reasoning-effort (:psi.agent-session/effective-reasoning-effort d)
                     :text                       model-text*}
      :footer/statuses statuses*
      :footer/lines {:path-line   path-text*
                     :stats-line  stats-line
                     :status-line (when (seq status-line) status-line)}})))

(defn footer-model
  ([ctx]
   (footer-model-from-data (footer-data ctx)))
  ([ctx session-id]
   (footer-model-from-data (footer-data ctx session-id)
                           {:cwd (when session-id
                                   (ss/effective-cwd-in ctx session-id))})))
