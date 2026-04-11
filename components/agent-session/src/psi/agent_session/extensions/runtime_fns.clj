(ns psi.agent-session.extensions.runtime-fns
  (:require
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-eql :as runtime-eql]
   [psi.agent-session.extensions.runtime-ui :as runtime-ui]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.session-state :as ss]))

(defn make-extension-runtime-fns
  "Build the runtime-fns map for extension API EQL access.
   Extensions interact with session state via query/mutation only.
   Secrets are exposed via narrow capability fns (not queryable resolvers)."
  [ctx session-id ext-path]
  (let [register-resolvers!  (:register-resolvers-fn ctx)
        register-mutations!  (:register-mutations-fn ctx)
        current-session-data (fn []
                               (ss/get-session-data-in ctx session-id))]
    (when-let [reg (:extension-registry ctx)]
      (ext/set-allowed-events-in! reg (str ext-path) runtime-ui/extension-ui-allowed-events))
    {:query-fn
     (fn [eql-query]
       (runtime-eql/query-extension-state register-resolvers! register-mutations! ctx session-id eql-query))

     :mutate-fn
     (fn [op-sym params]
       (runtime-eql/run-extension-mutation-in! ctx session-id op-sym params))

     :get-api-key-fn
     (fn [provider]
       (when-let [oauth-ctx (:oauth-ctx ctx)]
         (oauth/get-api-key oauth-ctx provider)))

     :ui-type-fn
     (fn []
       (:ui-type (current-session-data)))

     :ui-context-fn
     (fn [ext-path*]
       (runtime-ui/extension-ui-context ctx session-id current-session-data ext-path*))

     :log-fn
     (fn [text]
       (binding [*out* *err*]
         (println text)))}))
