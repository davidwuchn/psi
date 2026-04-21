Approach:
- Introduce a `workflow-loader` extension that discovers, parses, compiles, and registers workflow definitions from `.psi/workflows/*.md`.
- Unified file format: YAML frontmatter (name, description) + optional EDN config (tools, skills, steps, etc.) + body text (system prompt / framing prompt).
- Agent profiles are workflows without steps — normalized on load.
- Compile all definitions — single-step and multi-step — into canonical agent-session workflow definitions at load time.
- Expose a single `delegate` tool and `/delegate` command.
- Invocation-time parameters (fork_session, include_result_in_context, mode, timeout_ms) stay on the tool, not in workflow definitions — they are caller concerns.
- Continue = resume with new prompt — unified concept covering both "continue done/errored agent" and "resume blocked workflow step".
- Migrate existing `.psi/agents/*.md` and `.psi/agents/agent-chain.edn` into `.psi/workflows/`.
- Remove `agent` and `agent-chain` extensions.

Corrective plan after implementation review:
- Close the remaining gaps between the implemented surface and the design before treating task 029 as done.
- Prioritize semantic alignment over further surface expansion: fix workflow continuation, removal semantics, framing prompt injection, and execution-time config propagation first.
- Treat the current implementation as a strong structural convergence slice, but not yet full behavioral parity.

Corrective slices:

Recommended execution order:
1. continue semantics
   - highest design mismatch and current public contract violation
   - likely requires small canonical runtime API addition; should settle semantics before adjacent tool/doc cleanup
2. remove semantics
   - keeps public contract honest and prevents carrying forward a misleading action name
3. parent-session targeting correctness
   - correctness fix for async result delivery; adjacent to delegate execution semantics
4. multi-step framing prompt behavior
   - important design alignment, but more self-contained once continuation semantics are stable
5. execution-time config propagation
   - extend the same execution bridge after prompt/context composition is settled
6. reload correctness
   - state lifecycle cleanup once core execution semantics are stable
7. contract cleanup
   - final reconciliation pass across schema/docs/help/tests after behavior stabilizes

1. continue semantics
   - make `delegate action=continue` actually consume the supplied prompt
   - define canonical behavior for stopped runs with new input:
     - blocked runs: resume the current run with updated workflow input / step input
     - done / failed / cancelled runs: create a fresh run derived from the original definition and continue from the new prompt, or narrow the design explicitly if that is not intended
   - update canonical runtime/mutation surface as needed so "continue with new prompt" is a first-class operation rather than UI-local behavior
   - add focused tests proving prompt use, blocked resume, and non-blocked continuation behavior

2. remove semantics
   - either implement true run removal or narrow the public contract to `cancel`
   - prefer one obvious meaning across tool docs, tool output, and runtime mutations
   - if true removal is added, ensure active-run tracking, widget cleanup, and list output all reflect deletion
   - add tests for remove-vs-cancel semantics

3. multi-step framing prompt behavior
   - make multi-step workflow body text inject into each delegated step context by default, as designed
   - avoid current fallback-only behavior where framing prompt is used only when the referenced workflow has no system prompt
   - decide the exact composition rule for step context:
     - referenced workflow system prompt
     - multi-step framing prompt
     - step prompt template
   - implement that composition in one canonical execution path and test it directly

4. execution-time config propagation
   - ensure `:skills` from workflow file config actually reach child-session creation/runtime
   - ensure `:model` overrides are actually honored during execution, not merely preserved in metadata
   - review whether other workflow-file metadata should also flow through explicitly instead of being stored but ignored
   - add execution tests proving effective session config, not just metadata presence

5. parent-session targeting correctness
   - make `include_result_in_context` inject into the originating parent session explicitly, not the ambient current session
   - persist the source session identity in async tracking / workflow input / run metadata as needed
   - add tests covering session switch before async completion

6. reload correctness
   - make reload retire definitions that are no longer present on disk, or document and enforce a different canonical lifecycle
   - ensure prompt contribution, delegate lookup, and canonical runtime definitions stay in sync after deletions/renames
   - add reload tests covering removal and rename scenarios

7. contract cleanup
   - reconcile tool schema and implementation defaults (`action` currently required in schema but defaults to `run` in behavior)
   - align docs, tool descriptions, command help, and tests with the final chosen semantics
   - re-review `doc/extensions.md` and any prompt contributions for contract drift

Implementation slices:

1. unified file parser
   - parse YAML frontmatter (name, description)
   - detect and read optional EDN config block from body (first non-whitespace `{`)
   - extract remaining body text as prompt content
   - handle: no EDN (body-only), EDN + body, EDN-only (no body)
   - unit tests for parser

2. workflow definition compiler
   - single-step: frontmatter + config + body → 1-step canonical workflow definition
   - multi-step: frontmatter + config + steps + body → N-step canonical workflow definition
   - validate step references, required fields, no name collisions
   - reuse/adapt existing `workflow-agent-chain` compilation logic
   - unit tests for compilation

3. workflow-loader extension
   - discover `*.md` files from global + project `.psi/workflows/` dirs
   - precedence: legacy global < preferred global < project
   - parse → validate → compile → register into canonical workflow state
   - publish prompt contribution listing all available workflows with descriptions
   - provide reload capability
   - widget for active/recent runs (consolidate current agent + chain widgets into unified delegate widget)

4. delegate tool
   - parameters: workflow, prompt, name, action, id, mode, fork_session, include_result_in_context, timeout_ms
   - action=run (default): resolve workflow by name, create + execute run
     - mode=async (default): fire in background, return immediately, background-job for management
     - mode=sync: block until completion or timeout, return result inline
     - fork_session: create child session from fork of parent conversation
     - include_result_in_context: inject result as user+assistant messages into parent context
   - action=list: list available workflows and active runs
   - action=remove: remove a run by id
   - action=continue: resume a stopped/blocked run with new prompt
   - tool registration through workflow-loader extension init

5. /delegate command
   - single slash command delegating to the delegate tool
   - parse: `/delegate <workflow-name> <prompt text>`

6. migration
   - move `.psi/agents/*.md` → `.psi/workflows/`
   - convert YAML `tools:` frontmatter key → EDN config block where needed
   - split `.psi/agents/agent-chain.edn` entries → individual `.psi/workflows/*.md` files
   - update global user dirs (`~/.psi/agent/workflows/`, `~/.psi/workflows/` as legacy fallback)
   - update `.psi/extensions.edn`: remove agent + agent-chain, add workflow-loader
   - verify all current profiles and chains work through the new loader

7. cleanup
   - remove `agent` extension from `extensions/`
   - remove `agent-chain` extension from `extensions/`
   - remove legacy `workflow-agent-chain` compiler from agent-session (replaced by unified compiler)
   - retire `.psi/agents/` directory
   - retire `:psi.agent-chain/*` discovery resolver attrs
   - update docs

Risks:
- adapter/UI code may reference `agent` or `agent-chain` tool names or discovery attrs
- the extension workflow runtime used by `agent`/`agent-chain` must be fully replaceable by canonical workflow execution before removal
- prompt contribution content in AGENTS.md currently references `agent` and `agent-chain` tool names
- corrective changes may require small canonical workflow runtime API additions rather than remaining fully extension-local
