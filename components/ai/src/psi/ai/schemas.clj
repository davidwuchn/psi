(ns psi.ai.schemas
  "Malli schemas for AI entities following allium spec"
  (:require [malli.core :as m]
            [malli.transform :as mt])
  (:import [java.time Instant]
           [java.util UUID]))

;; Value types

(def Provider
  [:enum :anthropic :openai])

(def Api
  [:enum :anthropic-messages :openai-completions :openai-codex-responses])

(def MessageRole
  [:enum :user :assistant :tool-result])

(def StopReason
  [:enum :stop :length :tool-use :error :aborted])

(def StreamEventType
  [:enum :start :text-start :text-delta :text-end
   :thinking-start :thinking-delta :thinking-end
   :toolcall-start :toolcall-delta :toolcall-end
   :done :error])

(def ContentBlockKind
  [:enum :text :thinking :image :tool-call])

(def MessageContentKind
  [:enum :text :structured])

(def ConversationStatus
  [:enum :active :completed :error])

(def StreamSessionStatus
  [:enum :starting :streaming :completed :failed])

(def CacheRetention
  [:enum :none :short :long])

;; Entity schemas

(def CostBreakdown
  [:map {:closed true}
   [:input number?]
   [:output number?]
   [:cache-read number?]
   [:cache-write number?]
   [:total number?]])

(def Usage
  [:map {:closed true}
   [:input-tokens {:optional true} nat-int?]
   [:output-tokens {:optional true} nat-int?]
   [:cache-read-tokens {:optional true} nat-int?]
   [:cache-write-tokens {:optional true} nat-int?]
   [:total-tokens {:optional true} nat-int?]
   [:cost {:optional true} CostBreakdown]])

(def TextContent
  [:map {:closed true}
   [:kind [:= :text]]
   [:text string?]])

(def StructuredContent
  [:map {:closed true}
   [:kind [:= :structured]]
   [:blocks [:vector any?]]])  ;; ContentBlocks defined below

(def MessageContent
  [:multi {:dispatch :kind}
   [:text TextContent]
   [:structured StructuredContent]])

(def Tool
  [:map {:closed true}
   [:name string?]
   [:description string?]
   [:parameters map?]])  ;; JSON schema

(def Model
  [:map {:closed true}
   [:id string?]
   [:name string?]
   [:provider Provider]
   [:api Api]
   [:base-url string?]
   [:supports-reasoning boolean?]
   [:supports-images boolean?]
   [:supports-text boolean?]
   [:context-window pos-int?]
   [:max-tokens pos-int?]
   [:input-cost number?]
   [:output-cost number?]
   [:cache-read-cost number?]
   [:cache-write-cost number?]])

(def Message
  [:map {:closed true}
   [:id string?]
   [:role MessageRole]
   [:content MessageContent]
   [:timestamp inst?]
   [:provider {:optional true} Provider]
   [:model-id {:optional true} string?]
   [:api {:optional true} Api]
   [:usage {:optional true} Usage]
   [:stop-reason {:optional true} StopReason]
   [:error-message {:optional true} string?]
   [:tool-call-id {:optional true} string?]  ;; For tool-result messages
   [:tool-name {:optional true} string?]
   [:is-error {:optional true} boolean?]])

(def Conversation
  [:map {:closed true}
   [:id string?]
   [:system-prompt {:optional true} [:maybe string?]]
   [:status ConversationStatus]
   [:created-at inst?]
   [:updated-at inst?]
   [:messages [:vector Message]]
   [:tools [:set Tool]]
   [:error-message {:optional true} string?]])

(def StreamEvent
  [:map {:closed true}
   [:sequence nat-int?]
   [:event-type StreamEventType]
   [:timestamp inst?]
   [:content-index {:optional true} nat-int?]
   [:delta-text {:optional true} string?]
   [:error-message {:optional true} string?]])

(def StreamSession
  [:map {:closed true}
   [:id string?]
   [:conversation-id string?]
   [:model Model]
   [:status StreamSessionStatus]
   [:started-at inst?]
   [:completed-at {:optional true} [:maybe inst?]]
   [:temperature {:optional true} [:maybe number?]]
   [:max-tokens {:optional true} [:maybe pos-int?]]
   [:cache-retention CacheRetention]
   [:events [:vector StreamEvent]]
   [:error-message {:optional true} string?]])

;; Stream options

(def StreamOptions
  [:map {:closed false}  ;; Allow additional provider-specific options
   [:temperature {:optional true} [:maybe [:double {:min 0.0 :max 2.0}]]]
   [:max-tokens {:optional true} [:maybe pos-int?]]
   [:api-key {:optional true} [:maybe string?]]
   [:cache-retention {:optional true} CacheRetention]
   [:session-id {:optional true} [:maybe string?]]
   [:headers {:optional true} [:map-of string? string?]]
   [:metadata {:optional true} [:map-of string? any?]]])

;; Event schemas

(def StreamEventData
  [:map {:closed false}  ;; Allow additional fields per event type
   [:type StreamEventType]
   [:timestamp inst?]
   [:partial {:optional true} Message]
   [:content-index {:optional true} nat-int?]
   [:delta {:optional true} string?]
   [:reason {:optional true} StopReason]
   [:message {:optional true} Message]
   [:error-message {:optional true} string?]])

;; Validation helpers

(defn valid?
  "Check if data matches schema"
  [schema data]
  (m/validate schema data))

(defn explain
  "Get validation errors"
  [schema data]
  (m/explain schema data))

(defn validate!
  "Validate data, throw on failure"
  [schema data]
  (if (m/validate schema data)
    data
    (throw (ex-info "Validation failed"
                    {:schema schema
                     :data data
                     :errors (m/explain schema data)}))))

;; Transformers

(def string->uuid-transformer
  {:name :string->uuid
   :decoders {:string (fn [_ _]
                        (fn [x]
                          (if (string? x)
                            (UUID/fromString x)
                            x)))}})

(def instant-transformer
  {:name :instant
   :decoders {inst? (fn [_ _]
                      (fn [x]
                        (cond
                          (inst? x) x
                          (string? x) (Instant/parse x)
                          (number? x) (Instant/ofEpochMilli x)
                          :else x)))}})

(def decode-transformer
  (mt/transformer
   mt/string-transformer
   string->uuid-transformer
   instant-transformer))

(defn decode
  "Decode data using transformers"
  [schema data]
  (m/decode schema data decode-transformer))