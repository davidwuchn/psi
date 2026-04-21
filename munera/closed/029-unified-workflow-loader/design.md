Goal: replace the `agent` and `agent-chain` extensions with a single `workflow-loader` extension backed by one unified file format and directory, and expose a single `delegate` tool and `/delegate` command.

Context:
- today there are overlapping surfaces for agent execution and chained agent execution:
  - the `agent` extension/tool
  - the `agent-chain` extension/tool
  - `.psi/agents/*.md` agent profile files (YAML frontmatter + system prompt body)
  - `.psi/agents/agent-chain.edn` for chain definitions (aggregate EDN vector)
  - canonical agent-session workflow runtime introduced in task 026
- the codebase already has a canonical deterministic workflow runtime plus a compatibility compiler from legacy chain config into canonical workflow definitions
- an agent profile is just a workflow without orchestration steps
- single-step and multi-step runs differ by data, not by extension/runtime concept

Problem statement:
- `agent` and `agent-chain` are partially overlapping extension concepts over a workflow substrate that is now more general
- `.psi` authoring is split across agent markdown files, legacy chain EDN config, and canonical workflow runtime state
- users have to learn different concepts for single-step vs multi-step runs
- the legacy `agent-chain.edn` single-file config does not align with a file-per-definition model
- two separate tools (`agent`, `agent-chain`) and multiple commands (`/agent`, `/chain`, etc.) fragment the surface

## Unified file format

One file format covers both single-step profiles and multi-step orchestrations.

Three layers in one `.md` file:

1. **YAML frontmatter** — `name` and `description` (identity/display metadata)
2. **Optional EDN block** — structured config, present when body text starts with `{`
3. **Body text** — system prompt (single-step) or framing prompt (multi-step)

### Single-step (no config)

```markdown
---
name: planner
description: Analyzes tasks, creates implementation plans
---

You are a planning agent. Your job is to analyze a task and produce a clear, actionable implementation plan.

## Guidelines
...
```

### Single-step with config

```markdown
---
name: planner
description: Analyzes tasks, creates implementation plans
---
{:tools ["read" "bash"]
 :skills ["clojure-coding-standards"]
 :thinking-level :off}

You are a planning agent. Your job is to analyze a task and produce a clear, actionable implementation plan.

## Guidelines
...
```

### Multi-step

```markdown
---
name: plan-build-review
description: Plan, build, and review code changes
---
{:steps [{:workflow "planner"
          :prompt "$INPUT"}
         {:workflow "builder"
          :prompt "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}
         {:workflow "reviewer"
          :prompt "Review the following implementation:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}]}

Coordinate a plan-build-review cycle. Ensure each step builds on the previous output.
```

### Parser behavior

1. Extract YAML frontmatter via existing `extract-frontmatter` → yields `name`, `description`
2. If remaining body starts with `{` (first non-whitespace), read first EDN form → structured config
3. Everything after the EDN form (or the whole body if no EDN) → prompt/system-prompt text

### Frontmatter keys

| Key | Required | Purpose |
|-----|----------|---------|
| `name` | yes | Workflow identity, used for references and discovery |
| `description` | yes | Human/LLM-readable summary |

### EDN config keys

| Key | Purpose |
|-----|---------|
| `:tools` | Tool set for execution (vector of tool name strings) |
| `:skills` | Skills to preload (vector of skill name strings) |
| `:thinking-level` | Thinking config (`:off`, `:low`, `:medium`, `:high`) |
| `:model` | Override model for execution |
| `:steps` | Multi-step orchestration (absent = single-step) |

The EDN config is the structured child-session creation surface. Keys align with child-session creation parameters.

### Step definition shape

Each step in `:steps` is a map:

| Key | Required | Purpose |
|-----|----------|---------|
| `:workflow` | yes | Name of workflow to delegate to |
| `:prompt` | yes | Prompt template; `$INPUT` = prior step output (or user prompt for step 1); `$ORIGINAL` = user's original prompt |

### Body semantics

- **Single-step**: body is the system prompt for the executor
- **Multi-step**: body is a framing prompt injected into each step's context (before the step prompt by default)

## Directory layout

```
.psi/
  workflows/          ← one file per workflow definition
    planner.md
    builder.md
    reviewer.md
    allium-check.md
    lambda-compiler.md
    lambda-decompiler.md
    prompt-compiler.md
    prompt-decompiler.md
    plan-build-review.md
    plan-build.md
    prompt-build.md
    lambda-build.md
```

- `.psi/agents/` is retired
- `.psi/agents/agent-chain.edn` is retired
- Global user workflows live under `~/.psi/agent/workflows/` (with `~/.psi/workflows/` as legacy fallback, matching the current global agents dir pattern)
- Project workflows live under `.psi/workflows/`
- Precedence: legacy global < preferred global < project (same as current agent defs)

## Tool surface

One tool: `delegate`

### Parameters

| Parameter | Purpose | When |
|-----------|---------|------|
| `workflow` | Workflow name to run | `action=run` |
| `prompt` | Input/request text | `action=run`, `action=continue` |
| `name` | Optional label for this run | `action=run` |
| `action` | `run` (default), `list`, `remove`, `continue` | always |
| `id` | Run id | `action=continue`, `action=remove` |
| `mode` | `sync` (block and return result) or `async` (background, default) | `action=run` |
| `fork_session` | When true, child session starts from a fork of the parent conversation | `action=run` |
| `include_result_in_context` | When true, inject result into parent context as user+assistant messages | `action=run`, `action=continue` |
| `timeout_ms` | Sync mode timeout in milliseconds (default 300000) | `action=run`, `mode=sync` |

Notes on invocation-time parameters:
- `fork_session` is purely invocation-time — it controls how the child session is created, not a property of the workflow definition
- `include_result_in_context` is purely invocation-time — it controls how the result is delivered to the caller, not a property of the workflow
- `mode` is purely invocation-time — sync blocks and returns the result inline; async fires in background and the caller monitors via background jobs

### Invocation shapes

**Run a predefined workflow:**
```
{workflow: "plan-build-review", prompt: "refactor the dispatch pipeline"}
```

**Run a single-step profile:**
```
{workflow: "planner", prompt: "analyze the dispatch pipeline"}
```

**Labeled run:**
```
{workflow: "plan-build-review", prompt: "...", name: "dispatch-refactor"}
```

**Sync with result:**
```
{workflow: "planner", prompt: "...", mode: "sync"}
```

**Fork parent conversation:**
```
{workflow: "builder", prompt: "...", fork_session: true}
```

**Continue a stopped/blocked run with new input:**
```
{action: "continue", id: "3", prompt: "also check error handling"}
```

**Management:**
```
{action: "list"}
{action: "remove", id: "3"}
```

The LLM sees all available workflows in the prompt contribution and picks what fits. No distinction between single-step and multi-step — just workflow names.

### Continue semantics

Continue resumes a stopped (done, errored, or blocked) run with a new prompt. This unifies the current agent "continue with new prompt" and canonical workflow "resume blocked step" concepts — both are "push a stopped run forward with new input."

## Command surface

One command: `/delegate`

```
/delegate plan-build-review refactor the dispatch pipeline
/delegate planner analyze the scope
```

## Prompt contribution

```
tool: delegate
workflows:
- planner: Analyzes tasks, creates implementation plans
- builder: Implements code changes following a plan
- reviewer: Reviews code changes for correctness and style
- plan-build-review: Plan, build, and review code changes
- plan-build: Plan and build without review
- prompt-build: Build a new prompt
- lambda-build: Build a lambda expression
```

No distinction between source format — the LLM sees a flat list of available workflows.

## Workflow-loader extension responsibilities

1. **Discover** — scan `.psi/workflows/` (global + project) for `*.md` files
2. **Parse** — extract frontmatter, optional EDN config, body text
3. **Validate** — check required fields, step references resolve, no name collisions
4. **Compile** — normalize into canonical workflow definitions for the agent-session workflow runtime
5. **Register** — register compiled definitions into canonical workflow state
6. **Expose** — publish prompt contribution listing available workflows
7. **Tool** — provide the `delegate` tool
8. **Command** — provide the `/delegate` command
9. **Widget** — display active/recent workflow runs (consolidate current agent + chain widgets)

## Migration

- Move `.psi/agents/*.md` files to `.psi/workflows/` (format is backward compatible — existing files work as-is since EDN block is optional)
- Split `.psi/agents/agent-chain.edn` entries into individual `.psi/workflows/*.md` files
- Add EDN config blocks to workflow files that need tools/skills/thinking config (currently encoded in YAML frontmatter keys `tools:` etc.)
- Remove `agent` extension from `.psi/extensions.edn`
- Remove `agent-chain` extension from `.psi/extensions.edn`
- Add `workflow-loader` extension to `.psi/extensions.edn`
- Update global user dirs from `~/.psi/agent/agents/` to `~/.psi/agent/workflows/`
- Retire `.psi/agents/` directory

### Backward compatibility

- The YAML frontmatter portion of existing agent profile files is already valid in the new format
- Current YAML frontmatter keys like `tools: read,bash` should be handled during migration by moving them into the EDN config block
- No temporary compatibility bridge between old and new is planned — direct migration in one step

## Execution model

All workflow execution goes through the canonical agent-session deterministic workflow runtime:
- Single-step workflows compile to 1-step canonical workflow definitions
- Multi-step workflows compile to N-step canonical workflow definitions
- The extension workflow runtime used by the current `agent` and `agent-chain` extensions is retired

## Introspection

Loaded workflow definitions are exposed through existing canonical workflow read surfaces:
- `:psi.workflow/definitions` lists all registered definitions
- Current `:psi.agent-chain/*` discovery attrs are retired
- The `delegate` tool `action=list` provides a human/LLM-readable summary

## Constraints

- one obvious model over parallel compatibility surfaces
- canonical agent-session deterministic workflow runtime is the execution substrate
- introspectable through canonical read surfaces
- file layout is easy to inspect, diff, and commit
- single-step and multi-step are the same structural kind
- no broadening into workflow marketplace or scheduling

## Acceptance

- `workflow-loader` extension exists and replaces `agent` + `agent-chain`
- `.psi/workflows/` is the single canonical directory for workflow definitions
- `.psi/agents/` is retired
- one unified file format (YAML frontmatter + optional EDN + body) covers all cases
- `delegate` tool with unified parameter surface exists
- `/delegate` command exists
- all current agent profiles and chain definitions are migrated to the new format
- single-step and multi-step workflows both execute through canonical workflow runtime
- prompt contribution lists all available workflows
