(ns psi.extension-test-helpers.nullable-api
  "Nullable (in-memory) ExtensionAPI fixture for extension tests.

   Purpose:
   - Provide a realistic API map for extension `init` fns
   - Record registrations and runtime calls for assertions
   - Avoid mocks/spies in favor of nullable infrastructure"
  (:require
   [clojure.string :as str]))

(defn create-state
  "Create mutable in-memory state used by the nullable API."
  []
  (atom {:queries        []
         :mutations      []
         :commands       {}
         :tools          {}
         :handlers       {}
         :flags          {}
         :flag-values    {}
         :shortcuts      {}
         :notifications  []
         :widgets        {}
         :cleared-widgets []
         :status-lines   []
         :entries        []
         :messages       []
         :workflow-types {}
         :workflows      {}
         :prompt-contributions {}}))

(defn- workflow-seq
  [state ext-path]
  (->> (vals (:workflows @state))
       (filter (fn [wf]
                 (or (nil? ext-path)
                     (= ext-path (:psi.extension/path wf)))))
       vec))

(defn- default-query-fn
  [state {:keys [path model system-prompt ui-type]} q]
  (swap! state update :queries conj q)
  (cond
    ;; Session model
    (= q [:psi.agent-session/model])
    {:psi.agent-session/model
     (or model
         {:provider :anthropic
          :id       "claude-sonnet-4-6"})}

    ;; Session system prompt
    (= q [:psi.agent-session/system-prompt])
    {:psi.agent-session/system-prompt system-prompt}

    ;; Session UI type
    (= q [:psi.agent-session/ui-type])
    {:psi.agent-session/ui-type (or ui-type :console)}

    ;; Extension workflows
    (and (vector? q)
         (some (fn [x]
                 (and (map? x)
                      (contains? x :psi.extension/workflows)))
               q))
    {:psi.extension/workflows (workflow-seq state path)}

    ;; Extension prompt contributions
    (= q [:psi.extension/prompt-contributions])
    {:psi.extension/prompt-contributions (->> (:prompt-contributions @state)
                                              vals
                                              vec)}

    :else
    {}))

(defn- default-mutate-fn
  [state _opts op params]
  (swap! state update :mutations conj {:op op :params params})
  (case op
    ;; Generic extension registration via mutation path
    psi.extension/register-command
    (let [name (:name params)
          opts (:opts params)]
      (swap! state assoc-in [:commands name] (assoc opts :name name))
      {:psi.extension/registered-command? true})

    psi.extension/register-handler
    (let [event-name (:event-name params)
          handler-fn (:handler-fn params)]
      (swap! state update-in [:handlers event-name] (fnil conj []) handler-fn)
      {:psi.extension/registered-handler? true})

    psi.extension/register-tool
    (let [tool (:tool params)]
      (swap! state assoc-in [:tools (:name tool)] tool)
      {:psi.extension/registered-tool? true})

    psi.extension/register-flag
    (let [name (or (:name params) (get-in params [:opts :name]))
          opts (:opts params)
          flag (assoc opts :name name)]
      (swap! state assoc-in [:flags name] flag)
      (when (and (contains? flag :default)
                 (not (contains? (:flag-values @state) name)))
        (swap! state assoc-in [:flag-values name] (:default flag)))
      {:psi.extension/registered-flag? true})

    psi.extension/register-shortcut
    (let [key (:key params)
          opts (:opts params)]
      (swap! state assoc-in [:shortcuts key] (assoc opts :key key))
      {:psi.extension/registered-shortcut? true})

    ;; Workflow runtime surface
    psi.extension.workflow/register-type
    (let [type (:type params)]
      (swap! state assoc-in [:workflow-types type] params)
      {:psi.extension.workflow/registered? true})

    psi.extension.workflow/create
    (let [id      (str (:id params))
          wf      {:psi.extension/path                   (:ext-path params)
                   :psi.extension.workflow/id            id
                   :psi.extension.workflow/type          (:type params)
                   :psi.extension.workflow/phase         :idle
                   :psi.extension.workflow/running?      false
                   :psi.extension.workflow/done?         false
                   :psi.extension.workflow/error?        false
                   :psi.extension.workflow/error-message nil
                   :psi.extension.workflow/input         (:input params)
                   :psi.extension.workflow/meta          (:meta params)
                   :psi.extension.workflow/data          {}
                   :psi.extension.workflow/result        nil
                   :psi.extension.workflow/elapsed-ms    0
                   :psi.extension.workflow/started-at    nil}]
      (swap! state assoc-in [:workflows id] wf)
      {:psi.extension.workflow/created? true
       :psi.extension.workflow/id id})

    psi.extension.workflow/send-event
    {:psi.extension.workflow/event-accepted? true}

    psi.extension.workflow/remove
    (let [id      (str (:id params))
          removed? (contains? (:workflows @state) id)]
      (swap! state update :workflows dissoc id)
      {:psi.extension.workflow/removed? removed?})

    psi.extension.workflow/abort
    {:psi.extension.workflow/aborted? true}

    ;; Prompt contribution helpers
    psi.extension/register-prompt-contribution
    (let [id (str (:id params))
          value (merge {:id id
                        :ext-path (:ext-path params)}
                       (:contribution params))]
      (swap! state assoc-in [:prompt-contributions [(:ext-path params) id]] value)
      {:psi.extension.prompt-contribution/registered? true
       :psi.extension.prompt-contribution/id id
       :psi.extension.prompt-contribution/count (count (:prompt-contributions @state))})

    psi.extension/update-prompt-contribution
    (let [id (str (:id params))
          key [(:ext-path params) id]
          current (get-in @state [:prompt-contributions key])]
      (if current
        (do
          (swap! state update-in [:prompt-contributions key] merge (:patch params))
          {:psi.extension.prompt-contribution/updated? true
           :psi.extension.prompt-contribution/id id
           :psi.extension.prompt-contribution/count (count (:prompt-contributions @state))})
        {:psi.extension.prompt-contribution/updated? false
         :psi.extension.prompt-contribution/id id
         :psi.extension.prompt-contribution/count (count (:prompt-contributions @state))}))

    psi.extension/unregister-prompt-contribution
    (let [id (str (:id params))
          key [(:ext-path params) id]
          existed? (contains? (:prompt-contributions @state) key)]
      (swap! state update :prompt-contributions dissoc key)
      {:psi.extension.prompt-contribution/removed? existed?
       :psi.extension.prompt-contribution/id id
       :psi.extension.prompt-contribution/count (count (:prompt-contributions @state))})

    ;; Transcript/message convenience used by extensions
    psi.extension/append-entry
    (do
      (swap! state update :entries conj params)
      {:psi.extension/entry-appended? true})

    psi.extension/send-message
    (do
      (swap! state update :messages conj params)
      {:psi.extension/message-sent? true})

    psi.extension/send-prompt
    (do
      (swap! state update :messages conj (assoc params :role "user" :custom-type "extension-prompt"))
      {:psi.extension/prompt-accepted? true
       :psi.extension/prompt-delivery :prompt})

    ;; Default
    {}))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
  create-nullable-extension-api
  "Create {:api .. :state atom} for extension tests.

   Options:
   - :path             extension path (default /test/extension.clj)
   - :model            value returned for [:psi.agent-session/model]
   - :system-prompt    value returned for [:psi.agent-session/system-prompt]
   - :query-fn         optional override
   - :mutate-fn        optional override
   - :get-api-key-fn   optional override"
  ([] (create-nullable-extension-api {}))
  ([{:keys [path query-fn mutate-fn get-api-key-fn]
     :as   opts}]
   (let [path*       (or path "/test/extension.clj")
         opts*       (assoc opts :path path*)
         state       (create-state)
         query*      (or query-fn (fn [q] (default-query-fn state opts* q)))
         mutate-base (or mutate-fn (fn [op params] (default-mutate-fn state opts* op params)))
         mutate*     (fn [op params]
                       (let [ext-op?  (and (symbol? op)
                                           (let [ns* (namespace op)]
                                             (or (= "psi.extension" ns*)
                                                 (and (string? ns*)
                                                      (str/starts-with? ns* "psi.extension.")))))
                             params' (if (and ext-op?
                                              (map? params)
                                              (not (contains? params :ext-path)))
                                       (assoc params :ext-path path*)
                                       params)]
                         (mutate-base op params')))
         get-key*    (or get-api-key-fn (fn [_provider] nil))
         api         {:path path*
                      :query query*
                      :mutate mutate*
                      :get-api-key get-key*
                      :ui-type (or (:ui-type opts*) :console)

                      :register-tool
                      (fn [tool]
                        (swap! state assoc-in [:tools (:name tool)] tool)
                        tool)

                      :register-command
                      (fn [name opts]
                        (let [cmd (assoc opts :name name)]
                          (swap! state assoc-in [:commands name] cmd)
                          cmd))

                      :register-flag
                      (fn [name opts]
                        (let [flag (assoc opts :name name)]
                          (swap! state assoc-in [:flags name] flag)
                          (when (and (contains? flag :default)
                                     (not (contains? (:flag-values @state) name)))
                            (swap! state assoc-in [:flag-values name] (:default flag)))
                          flag))

                      :register-shortcut
                      (fn [key opts]
                        (let [shortcut (assoc opts :key key)]
                          (swap! state assoc-in [:shortcuts key] shortcut)
                          shortcut))

                      :on
                      (fn [event-name handler-fn]
                        (swap! state update-in [:handlers event-name] (fnil conj []) handler-fn)
                        handler-fn)

                      :get-flag
                      (fn [name]
                        (get-in @state [:flag-values name]))

                      :register-prompt-contribution
                      (fn [id contribution]
                        (mutate* 'psi.extension/register-prompt-contribution
                                 {:id id :contribution contribution}))

                      :update-prompt-contribution
                      (fn [id patch]
                        (mutate* 'psi.extension/update-prompt-contribution
                                 {:id id :patch patch}))

                      :unregister-prompt-contribution
                      (fn [id]
                        (mutate* 'psi.extension/unregister-prompt-contribution
                                 {:id id}))

                      :list-prompt-contributions
                      (fn []
                        (let [all (:psi.extension/prompt-contributions
                                   (query* [:psi.extension/prompt-contributions]))]
                          (->> all
                               (filter #(= path* (:ext-path %)))
                               vec)))

                      :ui {:notify       (fn [text level]
                                           (swap! state update :notifications conj {:text text :level level}))
                           :set-widget   (fn [id position lines]
                                           (swap! state assoc-in [:widgets id]
                                                  {:id id :position position :lines lines}))
                           :clear-widget (fn [id]
                                           (swap! state update :widgets dissoc id)
                                           (swap! state update :cleared-widgets conj id))
                           :set-status   (fn [text]
                                           (swap! state update :status-lines conj (str/trim (or text ""))))}}]
     {:api api
      :state state})))

(defmacro ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
  with-user-dir
  "Temporarily set java user.dir while evaluating body."
  [dir & body]
  `(let [orig# (System/getProperty "user.dir")]
     (try
       (System/setProperty "user.dir" (str ~dir))
       ~@body
       (finally
         (System/setProperty "user.dir" orig#)))))

