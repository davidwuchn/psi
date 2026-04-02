# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# True parallel tool execution

## Problem

The executor supports multiple tool calls in a single assistant turn, but
executes them sequentially with `mapv`. This preserves the correct
conversation shape (many tool results → one follow-up assistant reply), but
wastes latency and can unnecessarily lengthen agent turns.

## Goal

Execute independent tool calls concurrently while preserving the current turn
semantics:
- one assistant turn may emit many tool calls
- all tool results are recorded
- all tool results are sent back in the next provider request together
- one follow-up assistant reply is produced for the batch

## Constraints

- Preserve deterministic journal semantics as far as possible.
- Keep provider-facing behavior unchanged:
  - Anthropic: consecutive `tool_result` blocks still collapse into one user message
  - OpenAI: tool messages still appear together in the next request context
- Tool lifecycle telemetry must remain attributable per tool-id.
- Parallel execution must not reorder provider turn boundaries.
- Errors remain per-tool; one failing tool must still yield a recorded tool result.

## Steps

### Step 1 — Separate execution policy from continuation semantics

In `executor.clj`:
- keep `continue-after-tool-use!` owning batch semantics
- extract `run-tool-calls!` helper returning `tool-results` for a vector of tool calls
- preserve current output shape: `{:turn/continuation :turn.continue/next-turn ...}`

### Step 2 — Add bounded parallel execution for tool batches

In `executor.clj`:
- run tool calls concurrently via a bounded executor / futures
- preserve result association by original tool-call id and input order
- make max parallelism explicit and configurable
- default to a conservative limit (e.g. small fixed pool), not unbounded parallelism

### Step 3 — Preserve stable recording order

- Allow execution to complete out of order internally
- Record canonical `toolResult` messages in deterministic batch order
  (assistant-emitted order by content-index / tool-call order)
- Keep lifecycle/progress events attributable to the real tool-id even when
  completion order differs

### Step 4 — Verify runtime effect/tool parity

Review dispatch/runtime boundaries touched by parallel tool execution:
- `:session/tool-run`
- tool lifecycle event recording
- tool output stats
- background workflow/tool job tracking

Ensure no hidden shared mutable state assumes sequential tool execution.

### Step 5 — Add tests

Add/extend tests for:
- multiple tool calls execute concurrently but yield one follow-up assistant turn
- deterministic journal ordering despite out-of-order completion
- per-tool error isolation in a parallel batch
- telemetry correctness (`tool-call-attempts`, lifecycle events, summaries)
- bounded parallelism (no unbounded fan-out)

### Step 6 — Evaluate policy surface

Decide whether parallel tool execution should be:
- always on
- provider/model/session configurable
- disabled for tools that are not concurrency-safe

Likely follow-up: add per-tool or per-session concurrency policy metadata.
