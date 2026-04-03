# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi (`/Users/duncan/projects/hugoduncan/psi/refactor`)
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `PLAN.md` — current implementation plan
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state
- Parallel tool execution landed in incremental commits.
- Tool batch execution now uses a shared ctx-owned executor instead of creating a pool per batch.
- Tool execution boundary is now dispatch-owned end-to-end.
- `:session/tool-run` now composes:
  - `:session/tool-execute-prepared`
  - `:session/tool-record-result`
- Parallel batches execute prepared phases concurrently and record final results in deterministic tool-call order.
- Current policy surface:
  - `:config {:tool-batch-max-parallelism N}`
  - default parallelism `4`
  - no per-tool concurrency-safety metadata yet

## Recent relevant commits
- `00fe622` — ⚒ move tool batch executor to shared ctx runtime
- `052d143` — ◈ document parallel tool execution policy
- `1eb8ede` — ⚒ add parallel tool batch coverage
- `e7cee54` — ⚒ verify parallel tool runtime parity
- `4bb5a44` — ⚒ preserve deterministic tool result recording order
- `4ed2109` — ⚒ add bounded parallel tool batch execution
- `3c8ffba` — ⚒ extract tool batch execution helper

## Suggested next step
- Add per-tool concurrency metadata / opt-out so tools that are not concurrency-safe can force sequential execution.

## Notes for future ψ
- Mementum was absent at session start and has now been initialized.
- Re-run orientation from this file first in future sessions.
