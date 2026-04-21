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

Remaining work:
- Async/sync mode execution wiring through canonical workflow runtime mutations
- fork_session and include_result_in_context plumbing
- Consolidated widget for active/recent runs
- Extension registration in .psi/extensions.edn
- Extension removal (agent + agent-chain)
- AGENTS.md prompt contribution update
