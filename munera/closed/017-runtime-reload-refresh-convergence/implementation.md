No implementation notes yet.
Task created to separate post-reload runtime refresh convergence from raw code reload.

Initial findings captured in design:
- surviving runtime data is often not the problem
- stale executable wiring in registries/hooks/callbacks is the main reload hazard
- task 014 should consume this task’s refresh protocol rather than own the whole architectural concern

Focused diagnosis/repro for `/work-on` after `/new`:
- added repro/behavior test in `components/agent-session/test/psi/agent_session/extension_targeting_runtime_test.clj`
- initial repro showed that an extension API built with `make-extension-runtime-fns` for session `s1` implicitly queried `s1` even when the command was dispatched from `s2`
- then promoted the desired behavior test: extension command implicit targeting should follow the active dispatch session after `/new`
- implemented fix by adding dynamic active-session rebinding around extension slash-command handler invocation
- `make-extension-runtime-fns` now prefers dynamically bound active extension session id over the original load-time session id for implicit query/mutate calls, while preserving explicit `:query-session` / `:mutate-session` targeting
- focused test now passes, supporting the fix for silent `/work-on` behavior after `/new`
- added a concrete `extensions.work-on` regression test proving `/work-on` dispatched from a new session updates the active session's worktree-path rather than the original extension-load session
