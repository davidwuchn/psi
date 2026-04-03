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

## Policy decision

Parallel tool execution is now enabled with a conservative default bounded pool.
Current policy surface:
- session/config driven via `:config {:tool-batch-max-parallelism N}`
- default max parallelism: `4`
- no per-tool concurrency safety metadata yet

Follow-up candidates:
- per-tool opt-out / concurrency class metadata
- model/provider/session policy selection

# Ideas

- Add a `:skill` argument to the `agent` tool.
- When used without fork, the specified skill should be read and injected as a synthetic sequence in the spawned session context:
  - a synthetic "use the skill" user message
  - the skill content
  - the effective prompt
  - the corresponding assistant reply
- Insert a cache breakpoint so the reusable skill prelude is separated from the variable tail of the conversation.
- Goal: reduce end-of-conversation breakpoints from 3 to 2 for this flow.
- Expected benefit: better caching for repeated prompts that reuse the same skill.
