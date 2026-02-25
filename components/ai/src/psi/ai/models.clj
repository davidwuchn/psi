(ns psi.ai.models
  "Model definitions and capabilities"
  (:require [clojure.spec.alpha :as s]))

;; Model definitions following allium spec

(def anthropic-models
  {:claude-3-5-sonnet
   {:id "claude-3-5-sonnet-20241022"
    :name "Claude 3.5 Sonnet"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 8192
    :input-cost 3.0      ;; $/million tokens
    :output-cost 15.0
    :cache-read-cost 0.3
    :cache-write-cost 3.75}
   
   :claude-3-5-haiku
   {:id "claude-3-5-haiku-20241022"
    :name "Claude 3.5 Haiku"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning false
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 8192
    :input-cost 1.0
    :output-cost 5.0
    :cache-read-cost 0.1
    :cache-write-cost 1.25}})

(def openai-models
  {:gpt-4o
   {:id "gpt-4o"
    :name "GPT-4o"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning false
    :supports-images true
    :supports-text true
    :context-window 128000
    :max-tokens 16384
    :input-cost 2.5
    :output-cost 10.0
    :cache-read-cost 1.25
    :cache-write-cost 5.0}
   
   :o1-preview
   {:id "o1-preview"
    :name "GPT-o1 Preview"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images false
    :supports-text true
    :context-window 128000
    :max-tokens 32768
    :input-cost 15.0
    :output-cost 60.0
    :cache-read-cost 7.5
    :cache-write-cost 30.0}})

(def all-models
  (merge anthropic-models openai-models))

;; Model access functions

(defn get-model
  "Get model by key"
  [model-key]
  (get all-models model-key))

(defn list-for-provider
  "List models for specific provider"
  [provider]
  (->> all-models
       (filter (fn [[_ model]] (= provider (:provider model))))
       (into {})))

(defn list-reasoning-models
  "List models that support reasoning"
  []
  (->> all-models
       (filter (fn [[_ model]] (:supports-reasoning model)))
       (into {})))

(defn list-multimodal-models
  "List models that support images"
  []
  (->> all-models
       (filter (fn [[_ model]] 
                 (and (:supports-images model)
                      (:supports-text model))))
       (into {})))

;; Cost calculation

(defn calculate-cost
  "Calculate cost for given usage and model"
  [model usage]
  (let [{:keys [input-cost output-cost cache-read-cost cache-write-cost]} model
        {:keys [input-tokens output-tokens cache-read-tokens cache-write-tokens]} usage
        input-cost-total (* (/ input-tokens 1000000.0) input-cost)
        output-cost-total (* (/ output-tokens 1000000.0) output-cost) 
        cache-read-cost-total (* (/ cache-read-tokens 1000000.0) cache-read-cost)
        cache-write-cost-total (* (/ cache-write-tokens 1000000.0) cache-write-cost)]
    {:input input-cost-total
     :output output-cost-total
     :cache-read cache-read-cost-total
     :cache-write cache-write-cost-total
     :total (+ input-cost-total output-cost-total 
               cache-read-cost-total cache-write-cost-total)}))

;; Model validation

(defn supports-multimodal?
  "Check if model supports both text and images"
  [model]
  (and (:supports-images model)
       (:supports-text model)))

(defn supports-reasoning?
  "Check if model supports reasoning/thinking"
  [model]
  (:supports-reasoning model))

(defn get-context-window
  "Get context window size for model"
  [model]
  (:context-window model))

(defn get-max-tokens
  "Get max output tokens for model"
  [model]
  (:max-tokens model))