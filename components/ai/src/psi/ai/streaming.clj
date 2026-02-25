(ns psi.ai.streaming
  "Streaming response management and event handling"
  (:require [clojure.core.async :as async]
            [psi.ai.conversation :as conversation])
  (:import [java.util UUID]
           [java.time Instant]))

;; Stream session management

(defn create-stream-session
  "Create new streaming session"
  [conversation model options]
  {:id (str (UUID/randomUUID))
   :conversation-id (:id conversation)
   :model model
   :status :starting
   :started-at (Instant/now)
   :completed-at nil
   :temperature (:temperature options)
   :max-tokens (:max-tokens options)
   :cache-retention (get options :cache-retention :short)
   :events []})

(defn add-event
  "Add event to streaming session"
  [session event-type & {:keys [content-index delta-text error-message]}]
  (let [event {:sequence (count (:events session))
               :event-type event-type
               :timestamp (Instant/now)
               :content-index content-index
               :delta-text delta-text
               :error-message error-message}]
    (update session :events conj event)))

(defn complete-session
  "Mark session as completed"
  [session]
  (assoc session
         :status :completed
         :completed-at (Instant/now)))

(defn fail-session
  "Mark session as failed"
  [session error-msg]
  (assoc session
         :status :failed
         :completed-at (Instant/now)
         :error-message error-msg))

;; Simple event streaming

(defn stream-response
  "Stream assistant response using provider implementation"
  [provider-impl conversation model options]
  (let [event-chan (async/chan 100)
        session (atom (create-stream-session conversation model options))
        partial-message (atom {:id (str (UUID/randomUUID))
                              :role :assistant
                              :content {:kind :structured :blocks []}
                              :provider (:provider model)
                              :model-id (:id model)
                              :api (:api model)
                              :timestamp (Instant/now)
                              :stop-reason nil
                              :usage nil})]
    
    ;; Start streaming asynchronously
    (async/go
      (try
        ;; Start the session
        (swap! session add-event :start)
        (async/>! event-chan {:type :start :partial @partial-message})
        (swap! session assoc :status :streaming)
        
        ;; Get provider stream and process events
        (let [provider-chan ((:stream provider-impl) conversation model options)]
          (loop []
            (when-let [provider-event (async/<! provider-chan)]
              (case (:type provider-event)
                :text-start
                (do
                  (swap! session add-event :text-start :content-index (:content-index provider-event))
                  (async/>! event-chan {:type :text-start 
                                       :partial @partial-message
                                       :content-index (:content-index provider-event)}))
                
                :text-delta  
                (do
                  (swap! session add-event :text-delta 
                         :content-index (:content-index provider-event)
                         :delta-text (:delta provider-event))
                  (async/>! event-chan {:type :text-delta
                                       :partial @partial-message
                                       :content-index (:content-index provider-event)
                                       :delta (:delta provider-event)}))
                
                :done
                (do
                  (swap! session complete-session)
                  (swap! partial-message assoc 
                         :stop-reason (:reason provider-event)
                         :usage (:usage provider-event))
                  (async/>! event-chan {:type :done
                                       :reason (:reason provider-event)
                                       :message @partial-message})
                  (async/close! event-chan))
                
                :error
                (do
                  (swap! session fail-session (:error-message provider-event))
                  (swap! partial-message assoc
                         :stop-reason :error
                         :error-message (:error-message provider-event))
                  (async/>! event-chan {:type :error
                                       :reason :error
                                       :error-message (:error-message provider-event)})
                  (async/close! event-chan))
                
                ;; Handle other event types similarly...
                nil)
              
              (when (not= (:type provider-event) :done)
                (recur)))))
        
        (catch Exception e
          (swap! session fail-session (str e))
          (async/>! event-chan {:type :error
                               :reason :error  
                               :error-message (str e)})
          (async/close! event-chan))))
    
    ;; Return the event channel and session
    {:stream event-chan
     :session session}))