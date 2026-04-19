Step 1 landed in commit `2051a94` (`⚒ runtime-refresh: add canonical refresh scaffold`).
Step 3 strengthened proof in commit `a865f4b` (`⚒ runtime-refresh: strengthen scaffold proof`).
Step 5 documented the code-reload vs runtime-refresh distinction in commit `b167e74` (`⚒ runtime-refresh: document reload vs refresh`).
Step 6 added best-effort extension run-fn reinstall in commit `8885e7b` (`⚒ runtime-refresh: reinstall extension run fn when possible`).

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

Implemented so far:
- added shared runtime refresh entrypoint at `components/agent-session/src/psi/agent_session/runtime_refresh.clj`
- wired `psi-tool reload-code` to report `:psi-tool/runtime-refresh` using the shared pass
- added focused tests in:
  - `components/agent-session/test/psi/agent_session/runtime_refresh_test.clj`
  - `components/agent-session/test/psi/agent_session/tools_test.clj`
- updated user/runtime-facing docs in:
  - `README.md`
  - `doc/psi-project-config.md`
  - `components/agent-session/src/psi/agent_session/system_prompt.clj`

Current first-slice behavior:
- fixed refresh order is now explicit in the shared pass:
  1. query graph
  2. dispatch handlers
  3. extensions
  4. runtime hooks
- structured result shape is now canonical:
  - `:psi.runtime-refresh/status`
  - `:psi.runtime-refresh/steps`
  - `:psi.runtime-refresh/limitations`
  - `:psi.runtime-refresh/duration-ms`
- refresh remains best-effort and non-atomic
- refresh explicitly records that it does not recreate `ctx` or replace `:state*`

Current implementation boundaries:
- query graph refresh uses runtime `requiring-resolve` to avoid compile-time load cycles while still delegating to canonical registration surfaces
- dispatch refresh clears and re-registers handlers through the canonical registration path
- extension refresh delegates to the canonical extension reload path when requested
- background-job UI refresh hook is reinstalled through the app-runtime installer when available
- extension run fn is now reinstalled best-effort when runtime refresh has:
  - a target `session-id`
  - a usable session model (`:provider`, `:id`, optional `:reasoning`)
- extension run fn still remains a limitation when runtime refresh cannot safely reconstruct it from current session state

Current honest limitation reporting:
- if `:extension-run-fn-atom` is populated but runtime refresh cannot safely rebuild it, runtime refresh reports a limitation entry with:
  - `:boundary :extension-run-fn`
  - `:reason`
  - `:remediation`
- current explicit extension-run-fn limitation classes are:
  - missing `session-id`
  - missing usable session model
- proof now explicitly covers:
  - background-job UI refresh hook reinstall
  - successful extension run-fn reinstall when session model is present
  - structured limitation entry shape and contents for extension run-fn reinstall failures

Notes from landing this slice:
- a direct compile-time require from `runtime_refresh` into `context` / `dispatch-handlers` caused a load cycle through `psi_tool`; switching refresh internals to `requiring-resolve` removed that cycle
- `psi-tool` namespace-mode tests without runtime ctx correctly report extension refresh as `"extension registry unchanged (no runtime ctx provided)"`; preservation without rediscovery only applies when a runtime ctx exists and extension refresh is intentionally skipped
