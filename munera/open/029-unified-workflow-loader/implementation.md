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
