Design:
- [x] Define unified file format (YAML frontmatter + optional EDN + body)
- [x] Define directory layout (`.psi/workflows/`)
- [x] Define tool surface (`delegate` tool, `/delegate` command)
- [x] Define parameter shapes
- [ ] Validate design against current agent extension capabilities (prelude, skills, fork, sync/async modes)

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
- [ ] Implement prompt contribution (list available workflows)
- [ ] Implement reload capability
- [ ] Implement consolidated widget for active/recent runs

Tool:
- [ ] Implement `delegate` tool with unified parameter surface
- [ ] Implement action=run (default)
- [ ] Implement action=list
- [ ] Implement action=remove
- [ ] Implement action=continue
- [ ] Register tool in workflow-loader init

Command:
- [ ] Implement `/delegate` command

Migration:
- [ ] Move `.psi/agents/*.md` → `.psi/workflows/`
- [ ] Convert YAML `tools:` keys → EDN config blocks
- [ ] Split `.psi/agents/agent-chain.edn` → individual `.psi/workflows/*.md` files
- [ ] Update global user workflow dirs
- [ ] Update `.psi/extensions.edn`
- [ ] Verify all migrated workflows load and execute correctly

Cleanup:
- [ ] Remove `agent` extension
- [ ] Remove `agent-chain` extension
- [ ] Remove legacy `workflow-agent-chain` compiler
- [ ] Retire `.psi/agents/` directory
- [ ] Retire `:psi.agent-chain/*` discovery attrs
- [ ] Update docs
