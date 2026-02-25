# AI Component

A Polylith-style component implementing the Allium AI provider specification. Provides unified multi-provider LLM interface supporting Anthropic and OpenAI.

## Features

- **Unified Interface**: Single API across Anthropic and OpenAI providers
- **Streaming Responses**: Real-time token streaming with event-based updates  
- **Conversation Management**: Full conversation lifecycle with message history
- **Tool Support**: Tool calling and response handling
- **Usage Tracking**: Token usage and cost calculation
- **Type Safety**: Malli schemas for all entities and validation

## Architecture

Based on the [Allium specification](../../spec/ai-abstract-model.allium), the component follows behavioral patterns:

- `Conversation` - conversation lifecycle with messages and tools
- `Model` - provider models with capabilities and costs
- `StreamSession` - streaming response sessions with events
- `Message` - user/assistant/tool messages with usage tracking

## Usage

### Basic Conversation

```clojure
(require '[psi.ai.core :as ai]
         '[psi.ai.models :as models])

;; Create conversation
(def conversation (ai/create-conversation "You are a helpful assistant"))

;; Add user message
(def updated (ai/send-message conversation "What is 2+2?"))

;; Stream response
(def model (models/get-model :claude-3-5-sonnet))
(def options {:temperature 0.7 :max-tokens 1000})
(def {:keys [stream session]} (ai/stream-response updated model options))
```

### Processing Stream Events

```clojure
(require '[clojure.core.async :as async])

;; Consume events from stream
(async/go-loop []
  (when-let [event (async/<! stream)]
    (case (:type event)
      :start (println "Response starting...")
      :text-delta (print (:delta event))
      :thinking-delta (println "Thinking:" (:delta event))
      :done (println "\nResponse complete:" (:reason event))
      :error (println "Error:" (:error-message event)))
    (when (not= (:type event) :done)
      (recur))))
```

### Available Models

```clojure
;; List all models
(models/all-models)

;; List by provider
(models/list-for-provider :anthropic)
(models/list-for-provider :openai)

;; Get specific model
(models/get-model :claude-3-5-sonnet)
(models/get-model :gpt-4o)

;; Filter by capability
(models/list-reasoning-models)
(models/list-multimodal-models)
```

### Usage and Cost Tracking

```clojure
;; Get conversation usage
(ai/get-conversation-usage conversation)
;; => {:input-tokens 15 :output-tokens 42 :total-tokens 57}

;; Get conversation cost  
(ai/get-conversation-cost conversation)
;; => {:input 0.045 :output 0.63 :total 0.675}
```

## Configuration

Set environment variables for API access:

```bash
export ANTHROPIC_API_KEY="your-key"
export OPENAI_API_KEY="your-key" 
```

Or pass in options:

```clojure
(ai/stream-response conversation model {:api-key "your-key"})
```

## Schemas

All entities are validated using Malli schemas:

- `schemas/Conversation` - conversation state and metadata
- `schemas/Message` - individual messages with role and content
- `schemas/Model` - model definitions and capabilities
- `schemas/StreamOptions` - streaming configuration options
- `schemas/Usage` - token usage and cost tracking

## Provider Support

Currently supports:

### Anthropic
- Claude 3.5 Sonnet (reasoning, multimodal)
- Claude 3.5 Haiku (multimodal)
- Messages API with streaming
- Prompt caching support

### OpenAI  
- GPT-4o (multimodal)
- o1-preview (reasoning)
- Chat Completions API with streaming
- Tool calling support

## Testing

```bash
clj -M:test
```

Run with API keys to test live providers:

```bash
ANTHROPIC_API_KEY=... OPENAI_API_KEY=... clj -M:test
```