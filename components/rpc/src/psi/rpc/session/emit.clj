(ns psi.rpc.session.emit
  "Canonical RPC session event emission helpers."
  (:require
   [clojure.string :as str]
   [psi.rpc.events :as events]))

(defn make-request-emitter
  [emit-frame! state request-id]
  (fn emit! [event payload]
    (events/emit-event! emit-frame! state {:event event
                                           :data payload
                                           :id request-id})))

(defn emit-session-updated!
  [emit! ctx session-id]
  (emit! "session/updated" (events/session-updated-payload ctx session-id)))

(defn emit-footer-updated!
  [emit! ctx session-id]
  (emit! "footer/updated" (events/footer-updated-payload ctx session-id)))

(defn emit-context-updated!
  [emit! ctx state session-id]
  (emit! "context/updated" (events/context-updated-payload ctx state session-id)))

(defn emit-session-snapshots!
  ([emit! ctx state session-id]
   (emit-session-snapshots! emit! ctx state session-id {:context? false}))
  ([emit! ctx state session-id {:keys [context? context-session-id]
                                :or   {context? false}}]
   (emit-session-updated! emit! ctx session-id)
   (emit-footer-updated! emit! ctx session-id)
   (when context?
     (emit-context-updated! emit! ctx state (or context-session-id session-id)))))

(defn emit-session-resumed!
  [emit! {:keys [session-id session-file message-count]}]
  (emit! "session/resumed"
         {:session-id   session-id
          :session-file session-file
          :message-count message-count}))

(defn emit-session-rehydrated!
  [emit! {:keys [messages tool-calls tool-order]}]
  (emit! "session/rehydrated"
         {:messages   messages
          :tool-calls (or tool-calls {})
          :tool-order (or tool-order [])}))

(defn emit-session-rehydration!
  [emit! {:keys [session-id session-file message-count
                 messages tool-calls tool-order]}]
  (emit-session-resumed! emit! {:session-id session-id
                                :session-file session-file
                                :message-count message-count})
  (emit-session-rehydrated! emit! {:messages messages
                                   :tool-calls tool-calls
                                   :tool-order tool-order}))

(defn emit-assistant-message!
  [emit! result]
  (let [content (or (:content result) [])
        text    (events/assistant-content-text content)]
    (emit! "assistant/message"
           (cond-> {:role    (:role result)
                    :content content}
             (and (string? text) (not (str/blank? text)))
             (assoc :text text)

             (contains? result :stop-reason)
             (assoc :stop-reason (:stop-reason result))

             (contains? result :error-message)
             (assoc :error-message (:error-message result))

             (contains? result :usage)
             (assoc :usage (:usage result))))))

(defn emit-assistant-text!
  [emit! text]
  (let [text* (str text)]
    (emit! "assistant/message"
           {:role    "assistant"
            :text    text*
            :content [{:type :text :text text*}]})))

(defn emit-command-result!
  [emit! payload]
  (emit! "command-result" payload))

(defn emit-frontend-action-request!
  [emit! request-id cmd-result]
  (emit! "ui/frontend-action-requested"
         {:request-id request-id
          :action-name (some-> (:action-name cmd-result) name)
          :prompt (:prompt cmd-result)
          :payload (:payload cmd-result)}))
