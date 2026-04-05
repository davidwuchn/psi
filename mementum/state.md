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
- Managed services + post-tool processing foundation is in place.
- LSP ownership is extension-local, not core/runtime-local.
- The `lsp` extension now owns:
  - default `clojure-lsp` runtime config
  - nearest-`.git` workspace root detection
  - workspace service keying via `[:lsp workspace-root]`
  - extension-owned service ensure/stop
  - extension-owned JSON-RPC request/notify helpers
  - initialize + initialized handshake
  - per-workspace initialization tracking
  - per-document open/version tracking
  - `didOpen` on first sync and `didChange` on subsequent syncs
  - diagnostic request shaping for synced files
  - additive tool result enrichments + appended diagnostic text
  - non-fatal structured enrichment on LSP failure
  - extension commands:
    - `lsp-status`
    - `lsp-restart`
- Core/runtime gained generic extension-facing service protocol mutations only:
  - `psi.extension/service-request`
  - `psi.extension/service-notify`
- Service protocol helper now surfaces synchronous shim responses through `:response` when available.
- Canonical dispatch observability was extended with:
  - `:dispatch/interceptor-enter`
  - `:dispatch/interceptor-exit`
  - `:dispatch/handler-result`
  - `:dispatch/effects-emitted`
- Dispatch trace EQL now exposes `:psi.dispatch-trace/interceptor-id`.
- Focused tests now prove:
  - core dispatch trace includes interceptor / handler / effect / completion stages under one `dispatch-id`
  - LSP runtime-path trace remains queryable by `dispatch-id`
  - extension API service request/notify surface
  - mutation delegation to service protocol helpers
  - extension-owned JSON-RPC shaping
  - post-tool workspace sync and diagnostics projection
  - command registration
  - status rendering
  - restart behavior

## Event log vs dispatch trace
- Keep both for now; they serve different purposes.
- `event-log`:
  - one summarized entry per dispatch
  - coarse-grained journal of event type, event data, blocking, validation, effect summaries, db summaries, timing
  - replay-oriented; used as the retained dispatch journal / replay substrate
- `dispatch-trace`:
  - many entries per logical dispatch, correlated by `dispatch-id`
  - fine-grained observability across interceptor stages, handler result, emitted effects, effect execution, and service request/response/notify activity
  - debugging / architectural introspection surface, exposed via EQL
- Overlap is intentional; do not collapse them unless replay semantics and trace semantics are preserved as distinct projections.

## Recent relevant commits
- `7590d0a` — ⚒ extensions: add work-on project link
- `f972eab` — ◈ plan: pivot to lsp integration on new runtime
- `12d19e0` — ⚒ protocol: add managed service stdio/jsonrpc helpers
- `7afa1a3` — ⚒ mutations: register post-tool and service ops
- `aa15b96` — ⚒ extensions: add post-tool and service api hooks

## Suggested next step
- Implement LSP JSON-RPC initialize / notification / request flow on top of the managed service boundary.
- Then add a `write`/`edit` post-tool processor that syncs changed files and requests diagnostics within timeout.

## Notes for future ψ
- Mementum was absent at session start and has now been initialized.
- Re-run orientation from this file first in future sessions.
- `bb lint` currently reports unrelated pre-existing repo issues outside this LSP slice; focused LSP/service tests are green.
