Design:
- [x] Define unified file format (YAML frontmatter + optional EDN + body)
- [x] Define directory layout (`.psi/workflows/`)
- [x] Define tool surface (`delegate` tool, `/delegate` command)
- [x] Define parameter shapes (workflow, prompt, name, action, id, mode, fork_session, include_result_in_context, timeout_ms)
- [x] Validate design against current agent extension capabilities (prelude, skills, fork, sync/async modes)

Parser:
- [x] Implement unified file parser (frontmatter → EDN detection → body extraction)
  - `workflow_file_parser.clj`: YAML frontmatter + optional EDN prefix + body extraction
- [x] Unit tests for parser (41 assertions)

Compiler:
- [x] Implement single-step workflow definition compiler
- [x] Implement multi-step workflow definition compiler
- [x] Validate step references resolve to known workflows
- [x] Validate no name collisions across global + project definitions
- [x] Unit tests for compiler (44 assertions)

Loader:
- [x] Implement workflow file loader (discover, parse, compile, validate)
- [x] Directory precedence: legacy global < preferred global < project
- [x] Unit tests for loader (18 assertions)

Canonical Workflow Mutations:
- [x] register-definition — register canonical definition in root state
- [x] create-run — create run from registered definition
- [x] execute-run — execute run to terminal/blocked
- [x] resume-run — resume blocked run
- [x] cancel-run — cancel active run
- [x] list-definitions — list registered definitions
- [x] list-runs — list runs
- [x] Registered in mutations.clj aggregation
- [x] Unit tests for mutations (25 assertions)

Extension:
- [x] Implement workflow-loader extension (discover, parse, compile, register)
- [x] Directory precedence: legacy global < preferred global < project
- [x] Implement prompt contribution (list all workflows with descriptions)
- [x] Implement reload capability (/delegate-reload)
- [x] Implement session lifecycle cleanup (reload on session_switch)
- [x] Implement consolidated widget for active/recent runs
  - run-status-icon + run-widget-lines per run
  - refresh-widgets! syncs tracked async + canonical runs
  - wired into async start, completion, and cleanup

Tool:
- [x] Implement `delegate` tool with unified parameter surface
- [x] Implement action=run — creates + executes canonical workflow run
- [x] Implement action=list — shows workflows + active runs
- [x] Implement action=remove — cancels run
- [x] Implement action=continue — resumes blocked run
- [x] Register tool in workflow-loader init
- [x] Wire session-id from parent session to execute/resume mutations
- [x] Enrich execute-current-step! to resolve session config from workflow-file-meta
  - resolve-step-session-config: single-step uses own meta; multi-step looks up referenced definition
  - child sessions now get system-prompt, tools, thinking-level from definition metadata
- [x] mode=async: background execution, return immediately
  - launches execution on future, returns run-id immediately
  - on-async-completion handler: notify, inject results, emit entries, clean up
  - active-runs tracking atom for lifecycle management
- [x] mode=sync: block until completion or timeout, return result inline
  - launches async + awaits future with timeout
  - returns result text or timeout error
- [x] fork_session: passes through in workflow-input for canonical execution
- [x] include_result_in_context: inject result as user+assistant messages into parent context
  - maintains strict user/assistant alternation with bridge messages

Command:
- [x] Implement `/delegate` command
- [x] Implement `/delegate-reload` command

Migration:
- [x] Move `.psi/agents/*.md` → `.psi/workflows/` (12 files)
- [x] Convert YAML `tools:` keys → EDN config blocks
- [x] Split `.psi/agents/agent-chain.edn` → individual `.psi/workflows/*.md` files
- [x] Add `:skills` references where agents had implicit skill usage
- [x] Migrate global explore agent to `~/.psi/agent/workflows/`
- [x] Update `.psi/extensions.edn` (remove agent + agent-chain, add workflow-loader)
- [x] Verify all migrated workflows load and compile correctly (27 assertions)

Cleanup — deferred to later slice:
- [x] Remove `agent` extension from `extensions/`
- [x] Remove `agent-chain` extension from `extensions/`
- [x] Remove legacy `workflow-agent-chain` compiler
- [x] Retire `.psi/agents/` directory
- [x] Retire `:psi.agent-chain/*` discovery resolver attrs
- [x] Update docs
  - `doc/extensions.md` now documents `workflow-loader` + `delegate`
  - `psi-tool` workflow ops/docs now expose canonical-only workflow operations
  - `deps.edn`, `tests.edn`, and `extensions/deps.edn` no longer include agent/agent-chain paths

Corrective follow-up after implementation review:
- [x] Align `delegate action=continue` with the design
  - supplied `prompt` now feeds continuation workflow input
  - blocked runs now resume the existing run with updated workflow input
  - terminal runs now create a fresh run from the source definition and execute it
  - focused tests now prove blocked, terminal, and non-stopped continue behavior
- [x] Align `delegate action=remove` with the design
  - added true canonical run removal (`psi.workflow/remove-run`)
  - `delegate remove` now removes rather than cancels
  - active-run tracking/widget cleanup now follows removal
  - focused tests now prove runtime, mutation, and delegate remove behavior
- [ ] Align multi-step framing prompt behavior with the design
  - inject framing prompt into each delegated step context by default
  - stop relying on fallback-only framing behavior
  - add direct execution tests for prompt composition
- [ ] Propagate execution-time workflow config completely
  - make `:skills` reach child session creation/runtime
  - make `:model` overrides effective during execution
  - add tests that prove effective runtime config, not just stored metadata
- [ ] Fix parent-session targeting for `include_result_in_context`
  - target the originating parent session explicitly
  - add test covering session switch before async completion
- [ ] Fix reload lifecycle correctness
  - ensure removed/renamed workflow files are retired from canonical runtime state
  - add reload tests for deletion/rename cases
- [ ] Reconcile public contract drift
  - schema vs behavior for default `action`
  - docs/help text/prompt contribution consistency

Remaining for full feature parity:
- corrective alignment slice above
