# Configuration

Psi resolves configuration from multiple sources with a fixed precedence order.
Higher sources win:

```
session  (runtime, not persisted)
  ↑
project  — <cwd>/.psi/project.edn
  ↑
user     — ~/.psi/agent/config.edn
  ↑
system   (compiled-in defaults)
```

Session-level overrides are held in memory for the lifetime of the session and
are not written to disk unless you specify a scope when setting them.

---

## Config files

Both files use the same EDN shape:

```edn
{:version 1
 :agent-session {}}
```

The `:agent-session` map holds the settings described below.

### Project config — `<cwd>/.psi/project.edn`

Per-project defaults. Checked into source control when you want the whole team
to share the same model or prompt style.

```edn
{:version 1
 :agent-session {:model-provider      "anthropic"
                 :model-id            "claude-sonnet-4-6"
                 :thinking-level      :medium
                 :prompt-mode         :lambda
                 :nucleus-prelude-override nil}}
```

### User config — `~/.psi/agent/config.edn`

Personal defaults that apply across all projects. Overridden by project config.

```edn
{:version 1
 :agent-session {:model-provider "anthropic"
                 :model-id       "claude-sonnet-4-6"
                 :thinking-level :off
                 :prompt-mode    :lambda}}
```

---

## Settings reference

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:model-provider` | string | — | Provider name: `"anthropic"` or `"openai"` |
| `:model-id` | string | — | Model id string (e.g. `"claude-sonnet-4-6"`) |
| `:thinking-level` | keyword | `:off` | Extended thinking budget — see below |
| `:prompt-mode` | keyword | `:lambda` | System prompt style — `:lambda` or `:prose` |
| `:nucleus-prelude-override` | string | — | Replace the nucleus prelude block in the system prompt |
| `:llm-stream-idle-timeout-ms` | positive integer | `600000` | Milliseconds without provider stream progress before the backend aborts the run |

Both `:model-provider` and `:model-id` must be set together; a partial entry is
ignored and the next lower source is used instead.

### `:thinking-level` values

| Value | Meaning |
|-------|---------|
| `:off` | No extended thinking (default) |
| `:minimal` | Minimal budget |
| `:low` | Low budget |
| `:medium` | Medium budget |
| `:high` | High budget |
| `:xhigh` | Maximum budget |

The level is clamped to what the selected model supports. Models that do not
support reasoning ignore levels above `:off`.

### `:prompt-mode` values

| Value | Meaning |
|-------|---------|
| `:lambda` | Lambda-calculus compressed system prompt (default) |
| `:prose` | Plain prose system prompt |

---

## Runtime setters and scope

Settings can be changed at runtime via EQL mutations. Each setter accepts an
optional `:scope` keyword controlling where the change is persisted.

| `:scope` | Persists to |
|----------|-------------|
| `:session` | Memory only — lost when session ends |
| `:project` | `<cwd>/.psi/project.edn` |
| `:user` | `~/.psi/agent/config.edn` |

### `psi.extension/set-model`

```clojure
;; runtime only
{:model {:provider :anthropic :id "claude-sonnet-4-6"} :scope :session}

;; save to project (default when :scope is omitted)
{:model {:provider :anthropic :id "claude-sonnet-4-6"} :scope :project}

;; save to user config
{:model {:provider :anthropic :id "claude-sonnet-4-6"} :scope :user}
```

Default scope: `:project`.

### `psi.extension/set-thinking-level` (via RPC `set_thinking_level`)

```clojure
{:level :medium :scope :project}
```

Default scope: `:project`.

### `psi.extension/set-prompt-mode`

```clojure
{:mode :prose :scope :user}
```

Default scope: `:session` (prompt-mode changes are session-local unless you
explicitly persist them).

---

## Custom models — `models.edn`

Define custom providers (local models, proxies, self-hosted endpoints) in EDN
config files. Both user-global and project-local files are supported:

| File | Scope |
|------|-------|
| `~/.psi/agent/models.edn` | User-global — loaded at startup |
| `<cwd>/.psi/models.edn` | Project-local — loaded at session bootstrap |

Project models override user models when the same `(provider, id)` pair exists
in both files. Custom models never shadow built-in models.

### Format

```edn
{:version 1
 :providers
 {"local"
  {:base-url "http://localhost:8080/v1"
   :api      :openai-completions
   :auth     {:api-key      "none"       ; literal, or "env:MY_API_KEY"
              :auth-header? true          ; send Authorization: Bearer (default true)
              :headers      {}}           ; extra request headers
   :models
   [{:id               "llama-3.3-70b"
     :name             "Llama 3.3 70B"
     :supports-reasoning false
     :supports-images  false
     :context-window   128000
     :max-tokens       16384}]}}}
```

### Provider fields

| Key | Required | Default | Description |
|-----|----------|---------|-------------|
| `:base-url` | **yes** | — | Base URL for the API endpoint |
| `:api` | **yes** | — | Wire protocol: `:openai-completions`, `:anthropic-messages`, or `:openai-codex-responses` |
| `:auth` | no | — | Auth config (see below) |
| `:models` | **yes** | — | At least one model definition |

### Auth fields

| Key | Default | Description |
|-----|---------|-------------|
| `:api-key` | `nil` | API key — literal string or `"env:VAR_NAME"` to read from environment |
| `:auth-header?` | `true` | When true, sends `Authorization: Bearer <key>`. Set to false for servers that reject auth headers |
| `:headers` | `{}` | Additional headers merged into every request |

### Model fields

| Key | Default | Description |
|-----|---------|-------------|
| `:id` | **(required)** | Model identifier sent to the API |
| `:name` | same as `:id` | Human-readable display name |
| `:supports-reasoning` | `false` | Supports extended thinking |
| `:supports-images` | `false` | Accepts image input |
| `:supports-text` | `true` | Accepts text input |
| `:context-window` | `128000` | Input context window (tokens) |
| `:max-tokens` | `16384` | Max output tokens |
| `:input-cost` | `0.0` | Cost per million input tokens |
| `:output-cost` | `0.0` | Cost per million output tokens |
| `:cache-read-cost` | `0.0` | Cost per million cache-read tokens |
| `:cache-write-cost` | `0.0` | Cost per million cache-write tokens |

### Examples

**Ollama on localhost:**

```edn
{:version 1
 :providers
 {"ollama"
  {:base-url "http://localhost:11434/v1"
   :api      :openai-completions
   :auth     {:api-key "ollama" :auth-header? false}
   :models
   [{:id "qwen2.5-coder:32b" :name "Qwen 2.5 Coder 32B" :context-window 32768}
    {:id "llama3.3:70b"       :name "Llama 3.3 70B"       :context-window 128000}]}}}
```

**vLLM on a remote server:**

```edn
{:version 1
 :providers
 {"vllm-dev"
  {:base-url "http://gpu-server.local:8000/v1"
   :api      :openai-completions
   :auth     {:api-key "env:VLLM_API_KEY"}
   :models
   [{:id "Qwen/Qwen3-Coder-480B-A35B-Instruct"
     :name "Qwen3 Coder 480B"
     :supports-reasoning true
     :context-window 262144
     :max-tokens 32768}]}}}
```

**LM Studio:**

```edn
{:version 1
 :providers
 {"lm-studio"
  {:base-url "http://localhost:1234/v1"
   :api      :openai-completions
   :auth     {:auth-header? false}
   :models
   [{:id "local-model" :name "LM Studio Model"}]}}}
```

### Validation

Invalid `models.edn` files are logged as warnings and skipped. Built-in models
remain available. Check `*warn-on-reflection*` output or the session log for
parse errors.

---

## Startup resolution

At bootstrap, psi:

1. Reads `~/.psi/agent/models.edn` (user models — missing file is silently ignored)
2. Reads `~/.psi/agent/config.edn` (user config — missing file is silently ignored)
3. Reads `<cwd>/.psi/models.edn` (project models — missing file is silently ignored)
4. Reads `<cwd>/.psi/project.edn` (project config — missing file is silently ignored)
5. Merges custom models: user models ← project models (project wins per key)
6. Merges model catalog: built-in models + custom models (custom extends, does not shadow)
7. Merges config: system defaults ← user config ← project config (rightmost wins per key)
8. Resolves the model — if the merged model spec doesn't match a catalog entry,
   falls back to the CLI `--model` flag or `PSI_MODEL` env var
9. Clamps thinking-level to what the resolved model supports
10. Sets session state from the merged result

CLI flags and environment variables are applied before config-file resolution
and act as the fallback when no config file specifies a value.

Full precedence for model selection:

```
project config > user config > --model flag > PSI_MODEL env > compiled default (sonnet-4.6)
```
