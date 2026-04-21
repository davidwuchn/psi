2026-04-21 — initial decomposition notes

Responsibility clusters found in `extensions.workflow_loader`:

1. Extension state + API accessors
   - `state`, `inflight-runs`, query/mutate/log helpers

2. Definition loading / prompt contribution
   - `retire-removed-definitions!`
   - `register-definitions!`
   - `build-prompt-contribution`
   - `register-prompt-contribution!`
   - `reload-definitions!`

3. Delegate result delivery
   - `session-last-role`
   - `append-message-in-session!`
   - `inject-result-into-context!`
   - async completion entry shaping in `on-async-completion!`

4. Delegate runtime orchestration
   - background-job helpers
   - `execute-async!`
   - `await-run-completion`
   - `delegate-run`
   - `delegate-continue`
   - `delegate-remove`

5. Delegate text shaping / projection
   - `available-workflows-text`
   - `active-runs-text`
   - tool result strings in `execute-delegate-tool`
   - widget text helpers

6. Extension wiring
   - command parsing
   - `init`

Chosen decomposition direction:
- extract pure text/projection helpers first
- then extract result delivery helpers
- then extract orchestration helpers
- keep `extensions.workflow-loader` as the thin assembly namespace

Progress:
- extracted `extensions.workflow-loader.text`
  - prompt contribution shaping
  - delegate list/result/error strings
  - widget/list text shaping
  - `/delegate` command parsing
- extracted `extensions.workflow-loader.delivery`
  - session-targeted result injection
  - message append + last-role lookup
- extracted `extensions.workflow-loader.orchestration`
  - background-job start/terminal shaping
  - async execute/wait completion helpers
  - blocked-run and terminal-run continue execution paths
- top-level `extensions.workflow-loader` now delegates those concerns outward and retains extension wiring plus workflow-specific policy/control flow

Trade-offs:
- definition loading/registration still remains top-level because it is already compact and directly tied to extension init/reload wiring
- `delegate-continue` remains top-level as the policy/validation dispatcher, but its blocked-run and terminal-run execution branches now live in orchestration

Cleanup follow-on completed:
- removed unused top-level `workflow-runtime` require after decomposition
- removed unused orchestration bindings surfaced by clj-kondo
- top-level namespace now retains only workflow-loader-specific control flow, definition reload/wiring, and widget refresh assembly

Guardrails:
- preserve existing tests and user-visible strings where possible
- do not redesign canonical workflow runtime or background-job architecture
- keep `inflight-runs` as local sync-wait state only
