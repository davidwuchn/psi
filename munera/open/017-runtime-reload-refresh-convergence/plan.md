Approach:
- Treat post-reload runtime convergence as one named runtime refresh pass rather than a configurable unit framework.
- Make the pass explicit and diagnosable before attempting broader hot-swap ambitions.
- Build the work in vertical slices so each phase becomes coherent and provable.

Planned slices:

1. Refresh pass scaffold ✅ landed in `2051a94`
- Defined the runtime refresh pass entrypoint and structured result shape.
- Kept the pass fixed-order and full-scope for the first implementation.
- Made best-effort / non-atomic semantics explicit.
- Made the first-slice boundary explicit: the pass does not recreate `ctx`.

2. Query graph refresh
- Add one explicit phase that refreshes:
  - resolver registrations
  - mutation registrations
  - compiled query env
- Use canonical query registration surfaces as the source of truth.
- Replace stale executable registrations rather than appending to them.
- First slice may use clear-and-re-register semantics followed by env rebuild.
- Prove refreshed query graph behavior after reload-oriented re-registration.

3. Dispatch handler refresh
- Add one explicit phase for dispatch handler refresh.
- Use the canonical dispatch handler registration path as the source of truth.
- Refresh executable handler entries with clear-and-re-register semantics in the first slice.
- Preserve event-log/trace data by default while refreshing executable handler entries.
- Prove old handler fn values are replaced.

4. Extension refresh
- Define the extension refresh boundary inside the pass.
- Use the canonical extension reload path as the source of truth.
- Refresh extension runtime wiring explicitly rather than relying on namespace reload alone.
- Preserve extension-local data/state by default unless the canonical extension reload path explicitly resets an executable registry surface.
- Keep the extension step explicit and diagnosable.

5. Known hook reinstall
- Reinstall exactly these first-slice runtime hooks that do not automatically follow var reload:
  - extension run fn ✅ best-effort reinstall landed in `8885e7b`
  - background-job UI refresh fn ✅ landed earlier in scaffold slices
- Apply session-scoped reinstalls across the live sessions that currently depend on those hooks, not just the active session.
- Treat other closure-backed ctx-installed hooks as limitations unless later design work brings them into scope explicitly.

6. Limitation reporting
- Identify long-lived loops/threads that cannot be safely swapped in place. ✅ first slice landed in `2aa7f99`
- Identify in-flight prompt/background/service work that is not guaranteed to be rebound to refreshed code. ✅ first slice landed in `1d8860e`
- Report those boundaries explicitly in the final result using `boundary` / `reason` / `remediation` semantics.
- Use `:partial` when full in-place convergence cannot honestly be claimed.

7. Consumer integration ✅ first slice landed in `2051a94`
- Exposed the refresh pass so `psi-tool reload-code` now reports shared runtime refresh results.
- Kept this task responsible for the runtime refresh mechanics and result semantics.

8. Docs + proof ✅ first slice now landed through `a865f4b` + `b167e74`
- Documented the distinction between code reload and runtime refresh.
- Focused tests now prove first-slice behavior for dispatch refresh, extension refresh, background-job UI hook reinstall, and structured limitation reporting.
- Remaining proof work is mainly around sharper query-refresh guarantees and broader limitation classes.

Implementation notes:
- Prefer one clear refresh pass over a refresh-unit mini-framework.
- Preserve long-lived data where safe; refresh executable wiring explicitly.
- Use structured result reporting so partial success is visible.

Risks / decision points:
- some closures may only be refreshable by reinstalling runtime wiring rather than by any generic mechanism
- some loop/thread boundaries may require restart rather than hot refresh
- extension refresh scope may need careful worktree/code-affecting boundaries later

Verification strategy:
- land refresh-pass scaffold first
- then query graph refresh
- then dispatch refresh
- then extension refresh + known hook reinstall
- finish with limitation reporting and consumer integration
