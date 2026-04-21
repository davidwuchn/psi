2026-04-20
- Task created from user request to replace `agent` and `agent-chain` extensions with a unified workflow-loader.

2026-04-20
- Design collaboration with user established:
  - unified file format: YAML frontmatter (name, description) + optional EDN config + body text
  - agent profiles are just workflows without steps — normalized on load
  - single directory: `.psi/workflows/`
  - single tool: `delegate`
  - single command: `/delegate`
  - tool parameters: workflow, prompt, name, action
  - LLM chooses between predefined workflow or single-step profile — both are just workflow names
  - EDN config block carries structured child-session creation params (tools, skills, thinking-level, steps, etc.)
  - YAML frontmatter stays for name/description for consistency and easy parsing
  - body text is system prompt (single-step) or framing prompt (multi-step)
  - all execution goes through canonical agent-session deterministic workflow runtime
  - `.psi/agents/` is retired, `agent-chain.edn` is retired
  - no backward compatibility bridge — direct migration

Key design decisions:
- `name` is general — freed from identifying what to delegate to; `workflow` parameter identifies the target
- `prompt` instead of `task` — it's what it is
- `workflow` parameter covers both profiles and multi-step workflows since all are normalized to workflows on load
- steps use EDN (not YAML) to avoid quoting issues with prompt templates
- YAML frontmatter preserved for name/description for consistency with broader ecosystem conventions
- EDN block is optional — detected by first non-whitespace `{` in body
- body of multi-step workflows is framing prompt injected into step context

2026-04-20
- Capability validation completed against current `agent` extension (1401 lines).
- Capabilities inventoried:
  - sync/async mode, fork session, include result in context, skill prelude, agent profile resolution,
    continue, widget, prompt contribution, background job tracking, session lifecycle cleanup,
    result injection, timeout
- Design decisions from validation:
  - `fork_session` → invocation-time parameter only (controls child session creation, not workflow nature)
  - `include_result_in_context` → invocation-time parameter only (controls result delivery to caller)
  - continue = resume with new prompt → unified concept (agent "continue" and workflow "resume blocked" are the same thing)
  - sync/async → invocation-time `mode` parameter; background-job handles async management
  - skill prelude → already partially landed in child-session creation; surfaces through `:skills` in EDN config
- No blocking gaps identified — all current agent capabilities map cleanly to the delegate tool + canonical workflow execution

2026-04-20
- Implementation started — 5 slices landed:

1. **Parser** (`workflow_file_parser.clj`) — 2 tests, 41 assertions
   - YAML frontmatter extraction via existing `extract-frontmatter`
   - EDN prefix detection (first non-whitespace `{` → read first form)
   - Body text extraction after EDN form
   - Error reporting for missing fields and invalid EDN

2. **Compiler** (`workflow_file_compiler.clj`) — 6 tests, 44 assertions
   - Single-step: 1-step canonical definition with capability-policy + workflow-file-meta
   - Multi-step: N-step definition with step-chained input bindings
   - Batch compilation with error separation
   - Step reference validation and name collision detection

3. **Loader** (`workflow_file_loader.clj`) — 3 tests, 18 assertions
   - Directory scanning for *.md files
   - Merge by name (later directories win)
   - Batch parse → compile → validate pipeline
   - Directory precedence: legacy global < preferred global < project

4. **Extension** (`workflow_loader.clj`) — 4 tests, 20 assertions
   - Discovers, parses, compiles, registers definitions on init
   - `delegate` tool with action=run|list|continue|remove
   - `/delegate` and `/delegate-reload` commands
   - Prompt contribution
   - Session lifecycle cleanup

5. **Migration** — 12 workflow files + 3 validation tests, 27 assertions
   - All 8 agent profiles migrated (tools: YAML → {:tools [...]} EDN)
   - All 4 chain definitions migrated (agent-chain.edn → individual .md files)
   - Skills references added where agents had implicit skill usage
   - All files parse, compile, validate with resolved references

Architecture decisions:
- `workflow-file-meta` carries source context (system-prompt, tools, skills, etc.) through
  compilation into the canonical definition — this metadata is available to the execution bridge
  for child-session creation without needing to re-parse files
- The compiler produces standard canonical workflow definitions that satisfy `workflow-definition-schema`
- No compatibility bridge — the old format simply isn't loaded once `.psi/agents/` is retired

2026-04-20
- Async/sync/fork/include-result implementation landed:

6. **Execution bridge enrichment** (`workflow_execution.clj`)
   - `resolve-step-session-config` resolves child session config from workflow-file-meta
   - Single-step: uses run's own :workflow-file-meta (system-prompt, tools, skills, thinking-level)
   - Multi-step: looks up referenced workflow definition's :workflow-file-meta per step
   - Fallback: multi-step framing-prompt used when referenced def has no system-prompt
   - `execute-current-step!` now passes resolved config to child session creation
   - 3 new tests (single-step config, multi-step config, framing-prompt fallback)

7. **Delegate tool mode/option support** (`workflow_loader.clj`)
   - mode=async (default): launches execution on a future, returns run-id immediately
   - mode=sync: launches async + awaits future with configurable timeout
   - fork_session: passes through in workflow-input for downstream canonical execution
   - include_result_in_context: injects user+assistant messages after async completion
   - on-async-completion! handler: notify, inject results, emit entries, clean up
   - active-runs tracking atom for lifecycle management
   - delegate-continue now resumes asynchronously
   - delegate-list shows [async] tag for tracked runs
   - 9 new delegate-tool tests (async default, sync, timeout, include, fork, errors, list, continue)

Architecture decisions:
- Async execution uses Clojure futures — lightweight, no thread pool management needed
- Sync mode reuses async path + Future.get(timeout) — single code path, clean timeout
- fork_session is passed as workflow-input data rather than per-step config — it's a run-level concern
- include_result_in_context maintains strict user/assistant alternation via bridge messages
- Result injection happens on the async completion thread, not the calling thread

2026-04-20
- Deferred cleanup slice landed:

8. **Legacy surface removal**
   - removed `extensions/agent/` and `extensions/agent-chain/`
   - removed legacy canonical workflow compatibility compiler/runtime:
     - `workflow_agent_chain.clj`
     - `workflow_agent_chain_runtime.clj`
   - retired `.psi/agents/` directory from the project worktree
   - removed `:psi.agent-chain/*` discovery resolver attrs and their tests
   - removed `psi-tool` workflow compatibility ops:
     - `register-agent-chains`
     - `create-run-from-agent-chain`
   - updated `psi_tool` request validation/docs to canonical workflow ops only
   - removed agent/agent-chain source + test paths from:
     - `deps.edn`
     - `tests.edn`
     - `extensions/deps.edn`
   - updated docs/current-surface references:
     - `doc/extensions.md`
     - `components/agent-session/extensions.clj` tool-name examples
     - `mementum/state.md`
     - bootstrap invariant / tool validation tests

Validation:
- focused cleanup-related suite green:
  - `psi.agent-session.workflow-tools-test`
  - `psi.agent-session.tools-test`
  - `psi.agent-session.resolvers-test`
  - `psi.bootstrap-extension-invariant-test`
  - `extensions.workflow-loader-test`
  - `extensions.workflow-loader-delegate-test`
- result: `51 tests, 394 assertions, 0 failures`

2026-04-20
- Implementation review against design completed.

Review outcome:
- The implementation matches the design well at the structural level:
  - unified `.psi/workflows/` discovery/parsing/loading exists
  - single `delegate` tool and `/delegate` command exist
  - canonical workflow runtime is the execution substrate
  - migration away from `agent` and `agent-chain` landed
- The implementation does not yet fully match the design at the behavioral level.

Confirmed gaps from review:
- `delegate action=continue` currently requires a `prompt` but does not actually use it; current behavior is closer to async `resume-run` than the designed "continue with new prompt" surface.
- `delegate action=remove` currently cancels a run rather than removing it; public contract and behavior diverge.
- Multi-step workflow body text is currently used as fallback framing/system prompt only when referenced workflow defs lack a system prompt; this is weaker than the design intent of injecting framing context into each delegated step by default.
- `:skills` and `:model` metadata are preserved through parsing/compilation, but execution-time propagation is incomplete from the current bridge implementation.
- `include_result_in_context` currently operates via ambient extension session access rather than explicit originating parent-session targeting, so async completion after session switching is not yet proven correct.
- Reload behavior has not yet been proven to retire removed/renamed workflow definitions from canonical runtime state.
- Minor contract drift remains between tool schema and behavior (for example defaulting `action` to `run` while schema marks it required).

Direction taken after review:
- treat task 029 as structurally converged but behaviorally incomplete
- add corrective plan + steps instead of marking full feature parity complete
- next slice should focus on semantic alignment, not further surface expansion

2026-04-20
- First corrective alignment slice landed: `continue` semantics.

What changed:
- added pure workflow runtime support to replace a run's top-level `:workflow-input` before resuming
- extended canonical `psi.workflow/resume-run` mutation to accept optional `workflow-input`
- updated `delegate action=continue` behavior:
  - blocked runs now update workflow input from the supplied prompt and resume the existing run asynchronously
  - terminal runs (`:completed`, `:failed`, `:cancelled`) now create a fresh run from the original source definition and execute it asynchronously
  - non-stopped runs are now rejected explicitly
- added focused tests proving:
  - blocked-run continuation uses the supplied prompt
  - terminal-run continuation creates a fresh run from the source definition
  - running runs are rejected
  - runtime-level workflow-input replacement is recorded correctly
  - mutation-level resume path updates workflow input before execution

Validation:
- focused suite green:
  - `psi.agent-session.workflow-runtime-test`
  - `psi.agent-session.mutations.canonical-workflows-test`
  - `extensions.workflow-loader-delegate-test`
- result: `23 tests, 83 assertions, 0 failures`

2026-04-20
- Second corrective alignment slice landed: `remove` semantics.

What changed:
- added pure canonical workflow runtime support to remove a run from `:workflows :runs` and `:run-order`
- added canonical mutation `psi.workflow/remove-run`
- updated `delegate action=remove` to call true removal instead of cancel
- extension-side cleanup now also drops active-run tracking and refreshes widgets after removal

Validation:
- focused suite green:
  - `psi.agent-session.workflow-runtime-test`
  - `psi.agent-session.mutations.canonical-workflows-test`
  - `extensions.workflow-loader-delegate-test`
- result after continue + remove slices: `26 tests, 95 assertions, 0 failures`

2026-04-20
- Third corrective alignment slice landed: explicit parent-session targeting for async result injection.

What changed:
- workflow-loader now stores and threads the originating parent session id through async execution/completion handling
- result injection no longer depends on ambient session query/mutate access when explicit session-targeting APIs are available
- workflow-loader now uses `:query-session` to inspect the origin session's last visible role and `:mutate-session` to append injected user/assistant messages into that specific session
- ambient query/mutate remain as fallback behavior for narrower API contexts

Validation:
- focused suite green:
  - `extensions.workflow-loader-delegate-test`
- result for this slice: `13 tests, 40 assertions, 0 failures`

Remaining work:
- corrective alignment slice recorded in `plan.md` and `steps.md`:
  - framing prompt injection semantics
  - execution-time propagation for `:skills` and `:model`
  - reload retirement correctness
  - contract/doc/schema cleanup
