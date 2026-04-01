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

## Startup resolution

At bootstrap, psi:

1. Reads `~/.psi/agent/config.edn` (user config — missing file is silently ignored)
2. Reads `<cwd>/.psi/project.edn` (project config — missing file is silently ignored)
3. Merges: system defaults ← user ← project (rightmost wins per key)
4. Resolves the model — if the merged model spec doesn't match a known model,
   falls back to the CLI `--model` flag or `PSI_MODEL` env var
5. Clamps thinking-level to what the resolved model supports
6. Sets session state from the merged result

CLI flags and environment variables are applied before config-file resolution
and act as the fallback when no config file specifies a value.

Full precedence for model selection:

```
project config > user config > --model flag > PSI_MODEL env > compiled default (sonnet-4.6)
```
