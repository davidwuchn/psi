Goal: add direct project REPL support in psi so psi can start, own, and interact with a project-local nREPL process as a first-class runtime capability tied to a specific project/worktree.

Context:
- psi already values live introspection, runtime interaction, and REPL-driven development.
- psi already exposes metadata about psi’s own runtime nREPL and can optionally start an nREPL server for psi itself.
- that existing runtime nREPL support is not the same thing as a canonical feature for launching and interacting with a target project REPL as part of user work.
- current workflows often require a project-local interactive Clojure process bound to the current worktree so code can be evaluated, namespaces reloaded, and runtime state inspected without leaving psi.
- architecture direction already distinguishes runtime handles on `ctx` from canonical queryable projection state in `:state*`; direct project REPL support should follow that ownership model.
- `protocols.md` for this task compares stdio REPL, socket REPL, prepl, and nREPL, and recommends nREPL as the best pragmatic choice for structured interaction plus Clojure/babashka parity.

Problem statement:
psi currently lacks a first-class project REPL capability. Users may have external/manual REPL workflows, but psi does not canonically own:
- project REPL process lifecycle
- worktree-targeted REPL selection
- a stable evaluation/interaction surface
- canonical queryable status/diagnostics for that REPL

Design decision:
psi should implement direct project REPL support as a runtime-managed nREPL service family.

Why nREPL:
- structured request/response framing is a better fit than raw text parsing
- sessions and interrupt support align with psi’s need for managed interactive operations
- mature ecosystem and protocol conventions reduce invention pressure
- works across JVM Clojure and babashka in a way prepl does not
- better foundation for future capabilities such as load-file, completion, info, and richer tooling flows

What is stable in this task:
- project REPL support is a canonical psi capability
- the capability is scoped to a target project/worktree context
- psi owns lifecycle and diagnostics for the nREPL it starts/manages
- users and extensions interact through a canonical project-nREPL capability surface rather than raw ad hoc subprocess calls
- nREPL is the chosen transport/protocol family for this task

What remains intentionally open in this task:
- exact slash-command or UI affordance shape
- richer debugger-oriented and editor-middleware-oriented features beyond direct project REPL lifecycle and evaluation
- exact defaults for startup command discovery when project config does not provide an explicit command
- whether first-slice startup ships with one canonical default command path, multiple runtime-specific presets, or requires explicit project configuration

Closed design choices captured here:
- started-mode project configuration uses a vector of strings specifying command and args
- the first vector element is the command and must be a path string
- attach mode uses explicit endpoint targeting by port and optional host
- launched-mode endpoint discovery reads `.nrepl-port` in the launch directory
- connect/attach mode may also discover endpoint port via `.nrepl-port`
- first slice uses a single managed nREPL client session per worktree instance, with interrupt support

Canonical concept:
A project REPL is a managed runtime resource representing an interactive nREPL-backed Clojure process for a target worktree.

It has two layers:
1. runtime handle layer
- opaque process/connection machinery on `ctx`
- owns process, threads, sockets, nREPL client/session handles, readiness probes, temp files, and cleanup
- not itself canonical queryable state

2. projected state layer
- canonical queryable status in `:state*`
- visible to EQL/RPC/UI/tooling
- contains target worktree identity, lifecycle state, capability flags, timestamps, endpoint metadata, and recent operation summaries

This follows the same architecture rule already used for runtime nREPL, OAuth, and workflow registries: handle external, status projected.

Canonical public capability surface:
The user-facing and extension-facing capability must support these conceptual operations:

1. ensure/start
- start a project nREPL for a target worktree when none is running
- if one already exists and is healthy for that target, return its status rather than silently starting another duplicate

2. stop
- stop the managed project nREPL for a target worktree
- release owned runtime resources cleanly

3. restart
- intentionally replace the managed nREPL instance for a target worktree
- preserves worktree targeting but not process identity

4. status
- query whether a project nREPL exists for a target worktree
- expose lifecycle, readiness, endpoint metadata, and recent failure info

5. evaluate
- submit code to the target project nREPL and receive a structured result
- this is the core interaction surface that makes the REPL useful inside psi workflows

6. interrupt/cancel
- use nREPL interrupt semantics for in-flight evaluation where available
- if an operation is not interruptible in the chosen flow, the result/status must say so explicitly

7. optional future nREPL-native operations
- load-file
- namespace/module discovery helpers
- info/completion
- other middleware-backed operations

This task requires the first six operations conceptually; it does not require all future nREPL-native operations to land now.

Canonical targeting semantics:
Project REPLs are keyed by project/worktree identity, not by adapter-local cwd and not by process-global ambient focus.

Target selection precedence for project-nREPL operations should be:
1. explicit target worktree/path argument
2. invoking session’s canonical `:worktree-path`
3. otherwise: explicit error

Explicitly not allowed as primary semantic target selection:
- process cwd fallback
- hidden adapter-local cwd inference
- hidden dynamic var binding
- inferred target unrelated to the invoking session/worktree

Why this matters:
- multi-worktree development is a first-class psi workflow
- project REPLs are meaningfully tied to a checkout and its classpath/state
- incorrect implicit targeting is worse than a required explicit error

Canonical instance model:
This task should treat a project nREPL as a managed instance keyed by canonical absolute effective worktree path.

Stable instance invariants:
- instance identity is canonical absolute effective `worktree-path`
- a managed project nREPL belongs to exactly one effective target worktree
- worktrees do not implicitly share managed nREPL instances, even if they point at the same git origin or logical project
- a managed project nREPL has one lifecycle state at a time
- a managed project nREPL exposes endpoint metadata once started or attached
- a managed project nREPL exposes its supported capability subset explicitly

Recommended lifecycle states:
- `:absent`
- `:starting`
- `:ready`
- `:busy`
- `:stopping`
- `:failed`

The exact internal implementation may use richer substates, but canonical projection should remain simple and queryable.

Canonical minimum projected data:
The projected state for a managed project nREPL should include at least:
- stable target worktree identity/path
- acquisition mode = `:started` | `:attached`
- lifecycle state
- started-at / updated-at timestamps
- transport kind = `:nrepl`
- readiness flag or equivalent derived status
- endpoint metadata when available:
  - host
  - port
  - port-source = `:explicit` | `:dot-nrepl-port`
  - transport/session facts that are safe and useful to expose
- startup metadata when acquisition mode is `:started`:
  - configured command-vector
- session model metadata:
  - session-mode = `:single`
  - active-session-id when established
- capability flags:
  - can-eval?
  - can-interrupt?
  - can-load-file?
  - can-info?
  - can-complete?
- recent operation summary:
  - last eval started/finished time
  - last eval success/failure summary
  - last startup error summary when applicable
  - last attach error summary when applicable
- provenance distinguishing this from psi runtime nREPL metadata

Relationship to existing psi runtime nREPL support:
This task must keep the distinction explicit.

psi runtime nREPL:
- belongs to psi’s own process
- exists for introspection/debugging of psi itself
- is process-scoped infrastructure for psi runtime access

project nREPL:
- belongs to a target project/worktree workflow
- exists so psi can evaluate code against the target project runtime
- is worktree-scoped managed service infrastructure

They may share implementation machinery or even some lower-level helpers, but they are not the same conceptual resource and must not share ambiguous projection names or semantics.

Startup and attachment model:
This task chooses nREPL and requires two canonical acquisition modes.

1. psi-started mode
- psi launches a project-local process configured to expose nREPL
- the startup command is project-configured as a vector of strings
- the first vector element is the command and must be a path string
- remaining vector elements are passed as command arguments in order
- psi starts that command in the effective target worktree
- endpoint discovery for started mode reads `.nrepl-port` in the launch directory
- psi waits for readiness and then owns the resulting connection/session lifecycle
- startup configuration is part of the canonical project REPL feature, not an incidental adapter-local shell trick

2. attach mode
- psi connects to an already-running nREPL endpoint
- attach uses explicit endpoint targeting by port and optional host
- when attach input does not supply a port directly, psi may discover the port via `.nrepl-port` in the effective target worktree
- attach requires explicit endpoint information from the caller/configuration; it must not guess by scanning ambient ports
- attach remains worktree-targeted: the attached endpoint is bound to the effective target worktree in psi’s managed-service model
- attach is a first-class mode, not an afterthought

Acquisition-mode requirements:
- both psi-started mode and attach mode are in scope for this task
- both modes must project the same canonical lifecycle/evaluation/status surface once acquired
- the public capability is "managed project nREPL for worktree X", regardless of whether psi started it or attached to it
- projected state must record acquisition mode so diagnostics can distinguish psi-started vs attached instances
- for a given worktree, psi manages at most one connected project nREPL instance at a time
- conflicting acquisition attempts do not silently replace the existing instance; explicit restart/replace is required

Canonical interaction semantics for evaluate:
Evaluation uses nREPL request/response semantics but must present a psi-owned stable contract.

Evaluation must:
- target a specific managed project nREPL instance
- execute in the target project runtime, not in psi’s own JVM
- return a structured result rather than only raw terminal text
- preserve human-readable transcript visibility
- expose failure in a diagnosable way from inside psi
- associate output and completion with a specific operation id

Minimum evaluation result semantics:
- submitted input text
- target worktree identity
- operation id / correlation id
- nREPL session id when applicable
- start/end timestamps or duration
- status: success | error | interrupted | timed-out | unavailable
- rendered primary result text/value
- stdout/stderr side output when available
- structured error summary when failed
- capability/protocol notes when a requested operation is unsupported

Streaming/output model:
The implementation may differ by operation, but the user-visible contract should support:
- visible progress while evaluation is in flight
- output chunks associated with the active operation
- final structured result summary
- clear distinction between returned value, standard output, standard error, and transport/protocol failure

This does not require a full terminal emulator and does not imply debugger design.

Managed-service architecture direction:
The architectural fit is a runtime-owned managed service on `ctx`, analogous to other long-lived runtime resources.

Expected ownership boundaries:
- runtime handle owns process, nREPL connection, nREPL client sessions, and lifecycle machinery
- dispatch/mutations/events project status changes into canonical state
- EQL exposes projected public data
- RPC/TUI/Emacs consume projected public data rather than poking runtime handles directly
- tool/command surfaces invoke canonical operations rather than shelling out ad hoc

This task should therefore be understood as a managed-service capability, not merely “add a new shell command”.

nREPL-specific constraints and implications:
- the target process must start with an nREPL server available; psi cannot magically retrofit one into an arbitrary already-running process that did not expose one
- psi-started mode must launch the configured command vector in the target worktree and derive/connect to the resulting nREPL endpoint
- started-mode endpoint discovery reads `.nrepl-port` in the launch directory
- attach mode uses explicit endpoint targeting by port and optional host; when port is not supplied directly, psi may discover it from `.nrepl-port` in the effective target worktree
- startup/readiness detection must account for endpoint acquisition, connection establishment, client session establishment, and successful minimal eval-path readiness
- psi uses a single managed nREPL client session per worktree instance in the first slice
- psi should avoid conflating process lifecycle with client-session lifecycle; both exist and matter
- interrupt semantics map to the currently active operation on the managed single session by operation/message identity where possible
- if middleware-dependent features are used, capability flags must reflect whether that middleware is actually available
- attach mode must not weaken worktree targeting semantics: the binding between endpoint and worktree is explicit in psi state, not guessed from the endpoint alone
- for a given worktree, psi connects to at most one project nREPL instance at a time unless an explicit restart/replace operation is requested

Non-goals:
- do not broaden this task into a full debugger or inspector design
- do not require manual external REPL setup as the primary intended workflow
- do not conflate psi runtime introspection nREPL with project REPL capability
- do not define a pseudo-feature that is only raw subprocess spawning with no canonical status/eval surface
- do not require all possible editor middleware features before the basic direct project nREPL capability is considered real
- do not treat attach-to-any-random-port as sufficient targeting semantics without worktree identity

Docs requirements:
The task is not complete until docs explain:
- what a project nREPL is in psi terms
- how project nREPL support differs from psi runtime nREPL support
- worktree-targeted ownership and targeting precedence
- lifecycle operations: start/stop/restart/status/evaluate/interrupt
- the two acquisition modes: psi-started and attach
- the started-mode configuration contract: a vector of strings where the first element is the command path and remaining elements are arguments
- `.nrepl-port` discovery rules for started mode and attach mode
- the attach endpoint contract: explicit port and optional host, with `.nrepl-port` discovery allowed in worktree context
- single-session behavior and interrupt semantics in the first slice
- what diagnostics/status are queryable
- what features are canonical versus middleware-dependent

Test/design-proof requirements:
The task is not complete until tests prove the chosen implementation preserves the design invariants.

At minimum, tests for the eventual implementation must cover:
1. targeting
- explicit target selection wins over ambient context
- invoking session worktree-path is used when explicit target is absent
- missing target fails explicitly rather than guessing from cwd

2. lifecycle
- start creates a managed instance for a worktree using the configured command vector in psi-started mode
- attach creates a managed instance for a worktree using the explicit endpoint or `.nrepl-port` discovery in attach mode
- repeated ensure/start or repeated attach for the same worktree does not create uncontrolled duplicates
- conflicting acquisition attempts for the same worktree do not silently replace the existing instance
- restart/replace intentionally tears down or disconnects the current instance and establishes the replacement
- stop removes or deactivates the managed instance cleanly
- startup failure projects diagnosable failed state
- attach failure projects diagnosable failed state
- readiness is not reported until psi can acquire the endpoint, connect, establish the managed client session, and prove the minimal eval path

3. evaluation
- evaluate routes to the targeted worktree’s managed nREPL
- the first slice uses a single managed client session per worktree instance
- eval requests on that instance are single-flight/serialized
- interrupt targets the currently active eval on that managed session
- success/error/interrupted/unavailable states are distinguishable
- output/result/error are rendered and structured distinctly
- correlation between request and streamed/final output is preserved
- unsupported middleware-dependent operations are surfaced explicitly

4. observability
- projected state exposes lifecycle, endpoint metadata, and capability flags
- recent failure/eval summaries are queryable
- transcript/tool/UI surfaces can show useful diagnostics without external REPL spelunking
- projection names and values cannot be confused with psi runtime nREPL metadata

Acceptance:
- `015-direct-project-repl-support` clearly scopes project REPL support as a first-class psi capability
- the design now chooses nREPL as the canonical mechanism
- the design supports both attach-to-existing-port and psi-started acquisition via a project-configured command vector
- started-mode endpoint discovery is defined via `.nrepl-port` in the launch directory
- attach mode uses explicit port with optional host, with worktree-local `.nrepl-port` discovery allowed
- the design uses one managed client session and interrupt-capable single-flight eval semantics per worktree instance in the first slice
- the design distinguishes project nREPL support from psi’s existing runtime nREPL metadata support
- the design defines stable canonical semantics for targeting, lifecycle, evaluation, and observability
- the task frames project nREPL support as managed runtime infrastructure bound to worktree context, not an incidental external manual tool step