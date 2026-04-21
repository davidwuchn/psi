Approach:
- Introduce a `workflow-loader` extension that discovers, parses, compiles, and registers workflow definitions from `.psi/workflows/*.md`.
- Unified file format: YAML frontmatter (name, description) + optional EDN config (tools, skills, steps, etc.) + body text (system prompt / framing prompt).
- Compile all definitions — single-step and multi-step — into canonical agent-session workflow definitions at load time.
- Expose a single `delegate` tool and `/delegate` command.
- Migrate existing `.psi/agents/*.md` and `.psi/agents/agent-chain.edn` into `.psi/workflows/`.
- Remove `agent` and `agent-chain` extensions.

Implementation slices:

1. unified file parser
   - parse YAML frontmatter (name, description)
   - detect and read optional EDN config block from body
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
   - parse → validate → compile → register into canonical workflow state
   - publish prompt contribution listing available workflows
   - provide reload capability
   - widget for active/recent runs (consolidate current agent + chain widgets)

4. delegate tool
   - unified parameter surface: workflow, prompt, name, action
   - action=run (default): resolve workflow by name, create + execute run
   - action=list: list available workflows and active runs
   - action=remove: remove a run
   - action=continue: continue a completed/blocked run
   - tool registration through workflow-loader extension init

5. /delegate command
   - single slash command delegating to the delegate tool
   - parse: `/delegate <workflow-name> <prompt text>`

6. migration
   - move `.psi/agents/*.md` → `.psi/workflows/`
   - convert YAML `tools:` frontmatter key → EDN config block
   - split `.psi/agents/agent-chain.edn` entries → individual `.psi/workflows/*.md` files
   - update global user dirs
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
- current `agent` extension encodes prompt/prelude/skill-prelude semantics that must transfer cleanly into the workflow-loader + canonical execution path
- adapter/UI code may reference `agent` or `agent-chain` tool names or discovery attrs
- the extension workflow runtime used by `agent`/`agent-chain` must be fully replaceable by canonical workflow execution before removal
