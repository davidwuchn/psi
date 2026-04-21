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
- [ ] Implement unified file parser (frontmatter → EDN detection → body extraction)
- [ ] Unit tests for parser

Compiler:
- [ ] Implement single-step workflow definition compiler
- [ ] Implement multi-step workflow definition compiler
- [ ] Validate step references resolve to known workflows
- [ ] Validate no name collisions across global + project definitions
- [ ] Unit tests for compiler

Extension:
- [ ] Implement workflow-loader extension (discover, parse, compile, register)
- [ ] Directory precedence: legacy global < preferred global < project
- [ ] Implement prompt contribution (list all workflows with descriptions)
- [ ] Implement reload capability
- [ ] Implement consolidated widget for active/recent runs
- [ ] Implement session lifecycle cleanup (clear runs on session_switch)

Tool:
- [ ] Implement `delegate` tool with unified parameter surface
- [ ] Implement action=run (default)
  - [ ] mode=async (default): background execution, return immediately
  - [ ] mode=sync: block until completion or timeout, return result inline
  - [ ] fork_session: create child session from fork of parent conversation
  - [ ] include_result_in_context: inject result as user+assistant messages into parent context
  - [ ] timeout_ms: sync mode timeout
- [ ] Implement action=list
- [ ] Implement action=remove
- [ ] Implement action=continue (resume stopped/blocked run with new prompt)
- [ ] Register tool in workflow-loader init

Command:
- [ ] Implement `/delegate` command

Migration:
- [ ] Move `.psi/agents/*.md` → `.psi/workflows/`
- [ ] Convert YAML `tools:` keys → EDN config blocks
- [ ] Split `.psi/agents/agent-chain.edn` → individual `.psi/workflows/*.md` files
- [ ] Update global user workflow dirs (`~/.psi/agent/workflows/`, `~/.psi/workflows/` legacy fallback)
- [ ] Update `.psi/extensions.edn` (remove agent + agent-chain, add workflow-loader)
- [ ] Verify all migrated workflows load and execute correctly

Cleanup:
- [ ] Remove `agent` extension
- [ ] Remove `agent-chain` extension
- [ ] Remove legacy `workflow-agent-chain` compiler
- [ ] Retire `.psi/agents/` directory
- [ ] Retire `:psi.agent-chain/*` discovery attrs
- [ ] Update AGENTS.md prompt contributions (agent/agent-chain → delegate)
- [ ] Update docs
