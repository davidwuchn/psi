(ns psi.tui.tool-render
  (:require
   [charm.core :as charm]
   [cheshire.core :as json]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.tui.ansi :as ansi]))

(def ^:private tool-style     (charm/style :fg charm/yellow :bold true))
(def ^:private tool-ok-style  (charm/style :fg charm/green))
(def ^:private tool-err-style (charm/style :fg charm/red))
(def ^:private tool-dim-style (charm/style :fg 245))
(def ^:private dim-style      (charm/style :fg 240))

(def ^:private default-preview-lines 5)
(def ^:private read-preview-lines 10)
(def ^:private write-preview-lines 10)
(def ^:private ls-preview-lines 20)
(def ^:private find-preview-lines 20)
(def ^:private grep-preview-lines 15)
(def ^:private bash-preview-lines 5)

(defn- parse-tool-args
  [parsed-args args-str]
  (or parsed-args
      (try (json/parse-string args-str)
           (catch Exception _ nil))))

(defn- tool-header
  [tool-name parsed-args args-str]
  (let [args (parse-tool-args parsed-args args-str)]
    (case tool-name
      "read"  (str (charm/render tool-style "read")  " " (get args "path" "…"))
      "bash"  (str (charm/render tool-style "$")     " " (get args "command" "…"))
      "edit"  (str (charm/render tool-style "edit")  " " (get args "path" "…"))
      "write" (str (charm/render tool-style "write") " " (get args "path" "…"))
      (str (charm/render tool-style tool-name)))))

(defn- tool-status-indicator
  [status spinner-char]
  (case status
    :pending (str spinner-char)
    :running (str spinner-char)
    :success (charm/render tool-ok-style "✓")
    :error   (charm/render tool-err-style "✗")
    ""))

(defn- wrap-tool-result-line
  [line avail]
  (if (or (nil? avail) (<= (ansi/visible-width line) avail))
    [line]
    (ansi/word-wrap-ansi line avail)))

(defn- tool-expanded?
  [tools-expanded? _tc]
  (boolean tools-expanded?))

(defn- content-block->text
  [block]
  (let [t (:type block)
        t-label (cond
                  (keyword? t) (name t)
                  (string? t)  t
                  :else        "unknown")]
    (case t
      :text  (:text block)
      :image (str "[image " (or (:mime-type block) "unknown") "]")
      (str "[unsupported content block: " t-label "]"))))

(defn- tool-content->text
  [content]
  (when (seq content)
    (str/join "\n" (map content-block->text content))))

(defn- preview-lines-for-tool
  [tool-name]
  (case tool-name
    "read" read-preview-lines
    "write" write-preview-lines
    "ls" ls-preview-lines
    "find" find-preview-lines
    "grep" grep-preview-lines
    "bash" bash-preview-lines
    default-preview-lines))

(defn- tool-preview
  [tool-name text expanded?]
  (when (and text (not (str/blank? text)))
    (let [lines (str/split-lines text)
          total (count lines)]
      (if expanded?
        {:lines lines :hidden? false :bash-tail? false :hidden-count 0}
        (let [limit (preview-lines-for-tool tool-name)]
          (if (<= total limit)
            {:lines lines :hidden? false :bash-tail? false :hidden-count 0}
            (if (= "bash" tool-name)
              {:lines (vec (take-last limit lines))
               :hidden? true
               :bash-tail? true
               :hidden-count (- total limit)}
              {:lines (vec (take limit lines))
               :hidden? true
               :bash-tail? false
               :hidden-count (- total limit)})))))))

(defn- detail-warning-lines
  [details]
  (let [truncation (or (:truncation details)
                       (get details "truncation"))
        full-output-path (or (:full-output-path details)
                             (:fullOutputPath details)
                             (get details "full-output-path")
                             (get details "fullOutputPath"))
        entry-limit (or (:entry-limit-reached details)
                        (:entryLimitReached details)
                        (get details "entry-limit-reached")
                        (get details "entryLimitReached"))
        result-limit (or (:result-limit-reached details)
                         (:resultLimitReached details)
                         (get details "result-limit-reached")
                         (get details "resultLimitReached"))
        match-limit (or (:match-limit-reached details)
                        (:matchLimitReached details)
                        (get details "match-limit-reached")
                        (get details "matchLimitReached"))
        lines-truncated? (boolean (or (:lines-truncated details)
                                      (:linesTruncated details)
                                      (get details "lines-truncated")
                                      (get details "linesTruncated")))]
    (cond-> []
      (and (map? truncation) (:truncated truncation))
      (conj (str "Truncated output"
                 (when-let [by (:truncated-by truncation)]
                   (str " (" by ")"))))

      full-output-path
      (conj (str "Full output: " full-output-path))

      entry-limit
      (conj (str "Entry limit reached: " entry-limit))

      result-limit
      (conj (str "Result limit reached: " result-limit))

      match-limit
      (conj (str "Match limit reached: " match-limit))

      lines-truncated?
      (conj "Long lines truncated"))))

(defn- extension-call-render
  [ui-snapshot tc]
  (when-let [render-fn (some-> (get-in ui-snapshot [:tool-renderers (:name tc)])
                               :render-call-fn)]
    (try
      (some-> (render-fn (parse-tool-args (:parsed-args tc) (:args tc))) str)
      (catch Exception e
        (timbre/warn "Tool call renderer failed for" (:name tc) "- falling back:" (ex-message e))
        nil))))

(defn- extension-result-render
  [ui-snapshot tc opts]
  (when-let [render-fn (some-> (get-in ui-snapshot [:tool-renderers (:name tc)])
                               :render-result-fn)]
    (try
      (some-> (render-fn tc opts) str)
      (catch Exception e
        (timbre/warn "Tool result renderer failed for" (:name tc) "- falling back:" (ex-message e))
        nil))))

(defn- render-prefixed-lines
  [lines avail style]
  (mapcat (fn [line]
            (let [wrapped (wrap-tool-result-line line avail)]
              (map #(str "    " (charm/render style %)) wrapped)))
          lines))

(defn render-tool-calls
  [tool-calls tool-order spinner-char width tools-expanded? ui-snapshot]
  (when (seq tool-order)
    (let [header-avail (when (and width (> width 4)) (- width 4))
          result-avail (when (and width (> width 4)) (- width 4))]
      (str/join
       "\n"
       (for [id tool-order
             :let [tc (get tool-calls id)]
             :when tc]
         (let [status-icon   (tool-status-indicator (:status tc) spinner-char)
               call-render   (extension-call-render ui-snapshot tc)
               header        (if (seq call-render)
                               call-render
                               (tool-header (:name tc) (:parsed-args tc) (:args tc)))
               header        (if header-avail
                               (ansi/truncate-to-width header header-avail)
                               header)
               expanded?     (tool-expanded? tools-expanded? tc)
               raw-result    (or (:result tc) (tool-content->text (:content tc)))
               {:keys [lines hidden? bash-tail? hidden-count]}
               (or (tool-preview (:name tc) raw-result expanded?)
                   {:lines [] :hidden? false :bash-tail? false :hidden-count 0})
               hint-line     (when hidden?
                               (if bash-tail?
                                 (str "… (" hidden-count " earlier lines hidden, ctrl+o to expand)")
                                 (str "… (" hidden-count " more lines, ctrl+o to expand)")))
               warning-lines (into []
                                   (concat (detail-warning-lines (:details tc))
                                           (when hint-line [hint-line])))
               result-render (extension-result-render ui-snapshot tc
                                                      {:expanded? expanded?
                                                       :width width
                                                       :tool-id id
                                                       :tool-name (:name tc)})
               result-lines  (if (seq result-render)
                               (str/split-lines result-render)
                               lines)
               warning-lines (if (seq result-render) [] warning-lines)
               result-style  (if (:is-error tc) tool-err-style tool-dim-style)
               body-lines    (concat (render-prefixed-lines result-lines result-avail result-style)
                                     (render-prefixed-lines warning-lines result-avail dim-style))]
           (str "  " status-icon " " header
                (when (seq body-lines)
                  (str "\n" (str/join "\n" body-lines))))))))))
