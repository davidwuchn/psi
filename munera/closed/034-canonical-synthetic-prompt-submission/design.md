# 034 — Canonical synthetic prompt submission

## Intent

Reduce coupling between scheduler-style producers of synthetic user prompts and the low-level prompt lifecycle event sequence.

## Problem

The scheduler implementation currently knows the concrete dispatch sequence needed to inject a synthetic user prompt into the canonical prompt lifecycle:
- `:session/prompt-submit`
- `:session/prompt`
- `:session/prompt-prepare-request`

This works, but it spreads prompt lifecycle wiring knowledge into a feature-specific handler namespace. That makes the scheduler harder to evolve and creates a risk that future synthetic prompt producers repeat the same event bundle or drift from the canonical sequence.

## Scope

In scope:
- introduce one higher-level dispatch-owned entry point for synthetic user prompt submission
- move the low-level prompt lifecycle wiring behind that entry point
- migrate scheduler delivery/drain to use the new entry point
- preserve current scheduler behavior exactly
- add focused tests proving the new entry point routes through the same canonical lifecycle

Out of scope:
- changing scheduler semantics
- changing prompt lifecycle semantics
- introducing recurring schedules
- changing background-job projection
- adding new synthetic prompt producers beyond scheduler

## Minimum concepts

- **synthetic user prompt**: a non-human user-role message created by the runtime
- **canonical submission entry point**: one dispatch event or helper that owns how such a prompt enters the prompt lifecycle
- **producer**: a feature like scheduler that creates a synthetic prompt but should not own lifecycle wiring

## Architectural fit

This task should follow existing architecture:
- feature handlers compute intent and data
- dispatch-owned handlers/effects own lifecycle coordination
- prompt lifecycle sequencing remains centralized rather than copied into feature code

This task should remove a local pattern (feature-specific lifecycle event bundling), not add a new parallel architecture.

## Desired shape

After this task:
- scheduler constructs the scheduled user message and dispatches one higher-level canonical event
- one dispatch-owned handler or helper owns the internal event/effect sequence for synthetic prompt submission
- the system has a reusable pattern for future synthetic prompt producers

## Acceptance criteria

- scheduler delivery path no longer assembles the multi-event prompt lifecycle bundle locally
- one canonical dispatch-owned synthetic prompt submission entry point exists
- the canonical entry point preserves current scheduler behavior:
  - user-role injected message
  - provenance metadata preserved
  - normal prompt lifecycle still runs
- scheduler tests remain green with the new entry point
- focused tests prove the canonical entry point itself
- no parallel prompt injection path is introduced
