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
- LSP ownership is now being kept extension-local, not core/runtime-local.
- Added extension `extensions/src/extensions/lsp.clj` with:
  - default LSP runtime config
  - nearest-`.git` workspace root detection
  - logical workspace keying via `[:lsp workspace-root]`
  - extension-owned `ensure-lsp-service!`
  - initial registration of a `write`/`edit` post-tool processor placeholder
- Removed the earlier core-owned `psi.agent-session.lsp` experiment and core callback injection.
- Added focused extension tests covering:
  - nearest git root detection
  - workspace root resolution
  - service ensure request shaping
  - config override of spawned LSP command
  - post-tool processor registration at extension init

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
