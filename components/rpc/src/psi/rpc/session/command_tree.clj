(ns psi.rpc.session.command-tree
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.app-runtime.selectors :as selectors]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.rpc.events :as events]
   [psi.rpc.session.command-resume :as command-resume]
   [psi.rpc.session.command-results :as command-results]
   [psi.rpc.session.emit :as emit]))

(defn handle-tree-command!
  [ctx state request-id emit! trimmed session-id]
  (let [active-id (events/focus-session-id state)
        selector  (selectors/context-session-selector ctx session-id)
        items     (:selector/items selector)]
    (if (= trimmed "/tree")
      (emit/emit-frontend-action-request! emit! request-id (ui-actions/context-session-action selector))
      (let [arg (-> (str/replace trimmed #"^/tree\s+" "") str/trim)]
        (if (str/starts-with? arg "name ")
          (let [tail                (-> (subs arg 5) str/trim)
                [sid-part name-part] (let [[a b] (str/split tail #"\s+" 2)]
                                       [a (some-> b str/trim)])]
            (cond
              (str/blank? sid-part)
              (command-results/emit-text-command-result! emit! "Usage: /tree name <session-id|prefix> <name>")

              (str/blank? name-part)
              (command-results/emit-text-command-result! emit! "Usage: /tree name <session-id|prefix> <name>")

              :else
              (let [chosen (command-resume/maybe-match-selector-session items sid-part)
                    sid    (:item/session-id chosen)]
                (if-not chosen
                  (command-results/emit-text-command-result! emit! (str "Session not found in context: " sid-part))
                  (do
                    (session/set-session-name-in! ctx sid name-part)
                    (command-results/emit-text-command-result! emit! (str "Renamed session " sid " to " (pr-str name-part))))))))
          (let [chosen (command-resume/maybe-match-selector-session items arg)
                sid    (:item/session-id chosen)]
            (cond
              (nil? chosen)
              (command-results/emit-text-command-result! emit! (str "Session not found in context: " arg))

              (= sid active-id)
              (command-results/emit-text-command-result! emit! (str "Already active session: " sid))

              :else
              (do
                (session/ensure-session-loaded-in! ctx active-id sid)
                (command-resume/emit-session-rehydration-from-sid! ctx state emit! sid)))))))))
