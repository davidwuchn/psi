Design:
- [x] Define unified file format (YAML frontmatter + optional EDN + body)
- [x] Define directory layout (`.psi/workflows/`)
- [x] Define tool surface (`delegate` tool, `/delegate` command)
- [x] Define parameter shapes (workflow, prompt, name, action, id, mode, fork_session, include_result_in_context, timeout_ms)
- [x] Validate design against current agent extension capabilities (prelude, skills, fork, sync/async modes)
  - fork_session: invocation-time parameter only (not workflow definition)
  - include_result_in_context: invocation-time parameter only (delivery concern)
  - continue = resume with new prompt (unified concept)
  - sync/async: invocation-time mode; background-job for management
  - skill prelude: already partially landed in child-session creation; works through workflow definitions via `:skills` EDN config

Parser:
- [x] Implement unified file parser (frontmatter → EDN detection → body extraction)
  - `workflow_file_parser.clj`: YAML frontmatter + optional EDN prefix + body extraction
- [x] Unit tests for parser
  - 41 assertions covering single-step, multi-step, config-only, edge cases

Compiler:
- [x] Implement single-step workflow definition compiler
  - `workflow_file_compiler.clj`: 1-step canonical definition with capability-policy and workflow-file-meta
- [x] Implement multi-step workflow definition compiler
  - N-step definition with step-chained input bindings
- [x] Validate step references resolve to known workflows
- [x] Validate no name collisions across global + project definitions
- [x] Unit tests for compiler
  - 44 assertions covering compilation, validation, batch, error handling

Loader:
- [x] Implement workflow file loader (discover, parse, compile, validate)
  - `workflow_file_loader.clj`: directory scanning + merge by name + batch compile/validate
- [x] Directory precedence: legacy global < preferred global < project
- [x] Unit tests for loader
  - 18 assertions covering scanning, compilation, references, error collection, precedence

Extension:
- [x] Implement workflow-loader extension (discover, parse, compile, register)
- [x] Directory precedence: legacy global < preferred global < project
- [x] Implement prompt contribution (list all workflows with descriptions)
- [x] Implement reload capability (/delegate-reload)
- [ ] Implement consolidated widget for active/recent runs
- [x] Implement session lifecycle cleanup (reload on session_switch)

Tool:
- [x] Implement `delegate` tool with unified parameter surface
- [x] Implement action=run (default)
  - [ ] mode=async (default): background execution, return immediately
  - [ ] mode=sync: block until completion or timeout, return result inline
  - [ ] fork_session: create child session from fork of parent conversation
  - [ ] include_result_in_context: inject result as user+assistant messages into parent context
  - [ ] timeout_ms: sync mode timeout
- [x] Implement action=list
- [x] Implement action=remove
- [x] Implement action=continue (resume stopped/blocked run with new prompt)
- [x] Register tool in workflow-loader init

Command:
- [x] Implement `/delegate` command
- [x] Implement `/delegate-reload` command

Migration:
- [x] Move `.psi/agents/*.md` → `.psi/workflows/`
- [x] Convert YAML `tools:` keys → EDN config blocks
- [x] Split `.psi/agents/agent-chain.edn` → individual `.psi/workflows/*.md` files
- [x] Add `:skills` references where agents had implicit skill usage
- [ ] Update global user workflow dirs (`~/.psi/agent/workflows/`, `~/.psi/workflows/` legacy fallback)
- [ ] Update `.psi/extensions.edn` (remove agent + agent-chain, add workflow-loader)
- [x] Verify all migrated workflows load and compile correctly (12 files, 27 assertions)

Cleanup:
- [ ] Remove `agent` extension
- [ ] Remove `agent-chain` extension
- [ ] Remove legacy `workflow-agent-chain` compiler
- [ ] Retire `.psi/agents/` directory
- [ ] Retire `:psi.agent-chain/*` discovery attrs
- [ ] Update AGENTS.md prompt contributions (agent/agent-chain → delegate)
- [ ] Update docs

Remaining work:
- Consolidated widget display for active/recent runs
- Async/sync mode execution wiring through canonical workflow runtime mutations
- fork_session and include_result_in_context plumbing in delegate-run
- Extension registration in .psi/extensions.edn
- Global user workflow dir migration
- Extension removal (agent + agent-chain)
- Legacy compiler removal
- AGENTS.md prompt contribution update
