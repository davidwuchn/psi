Goal: add direct project REPL support in psi so psi can start and interact with a project-local REPL process directly.

Context:
- psi already has strong orientation toward introspection, live runtime interaction, and REPL-driven development.
- The system already exposes runtime nREPL endpoint metadata and can optionally start an nREPL server for psi itself.
- However, that existing nREPL support is about psi’s own runtime introspection surface, not a canonical feature for launching and interacting with a target project REPL as part of user work.
- Current development workflows often require a project-local interactive Clojure process tied to a specific worktree/project context.

Problem statement:
psi currently lacks a first-class feature for directly starting and interacting with a project REPL process inside the workflow it manages.

Desired capability:
- psi should be able to start a project REPL process directly
- psi should be able to interact with that REPL as part of normal workflow
- the transport/runtime shape is not yet decided and may be one of:
  - Clojure’s built-in REPL facilities
  - a network REPL / socket REPL style approach
  - nREPL
- the task is to establish this as a first-class psi capability, while deferring the protocol choice until the design is sharpened

Why this matters:
- direct project REPL interaction is a central part of effective Clojure development
- without first-class support, psi must rely on indirect/manual external REPL setup, which fractures the runtime workflow and weakens psi’s ability to help with live evaluation, debugging, and code reload loops
- project REPL lifecycle should follow project/worktree context rather than being treated as an unrelated external tool
- bringing project REPL support into psi creates a more complete development loop: orient → edit → evaluate → inspect → reload → continue

Clarifications:
- this task is about a project REPL, not only psi’s own runtime nREPL endpoint
- the target REPL should be understood as belonging to the current or explicitly selected project/worktree context
- the main open design choice is the underlying protocol/mechanism, not whether the capability is useful

Important open decision:
- the eventual implementation may use:
  - plain `clojure.main` / built-in REPL process management
  - socket/network REPL facilities
  - nREPL
- this task should keep that decision open for now
- the desired user-facing capability is stable even though the transport/mechanism is not yet chosen

Required outcome at the task level:
- psi gains a clear task to add project REPL lifecycle and interaction support
- the task frames project REPL support as a canonical runtime/workflow capability rather than an incidental external manual step
- the eventual design must account for:
  - process lifecycle
  - project/worktree targeting
  - interaction surface
  - visibility/diagnostics
  - relationship to existing psi runtime nREPL support

Non-goals:
- do not decide yet between built-in REPL, socket/network REPL, or nREPL
- do not broaden this task into a full debugger design
- do not conflate psi’s own runtime introspection nREPL with project REPL support unless the final design intentionally unifies them
- do not require manual external REPL setup as the primary intended experience once this capability exists

Acceptance:
- a task exists that clearly scopes direct project REPL support as a psi feature
- the task preserves the protocol/mechanism decision as open
- the design explicitly distinguishes project REPL support from psi’s existing runtime nREPL metadata support
- placeholder planning/implementation surfaces exist for future refinement
