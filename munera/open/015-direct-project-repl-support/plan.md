Approach:
- Implement direct project REPL support as a runtime-owned managed nREPL service keyed by canonical absolute `worktree-path`.
- Land the feature in vertical slices so each slice leaves a coherent, testable contract in place.
- Support both acquisition modes from the start:
  1. psi-started via project-configured command vector
  2. attach via explicit port + optional host, with worktree-local `.nrepl-port` discovery
- Keep the first slice operational model intentionally simple:
  - one connected project nREPL instance per worktree
  - one managed nREPL client session per instance
  - single-flight eval
  - interrupt targets the currently active eval

Planned slices:

1. Config + contract scaffolding
- Define the canonical config/data contract for project nREPL acquisition.
- Started mode config:
  - command vector of strings
  - first element must be command path
  - remaining elements are args
- Attach mode input:
  - explicit port
  - optional host
  - fallback discovery from `.nrepl-port` in effective worktree when port is omitted
- Add validation helpers for:
  - effective worktree resolution
  - command vector shape
  - host/port shape
  - `.nrepl-port` parsing/errors
- Decide and document where project config lives and how session-supplied args override/disambiguate project config.

2. Managed-service runtime skeleton
- Introduce a runtime-owned project-nREPL service registry on `ctx`, keyed by canonical worktree path.
- Add lifecycle helpers for:
  - ensure/start
  - attach
  - status
  - stop
  - restart/replace
- Enforce single-instance-per-worktree semantics.
- Reject conflicting acquisition attempts unless restart/replace is explicit.
- Keep runtime handles opaque and separate from projected state.

3. Started-mode acquisition
- Launch the configured command vector in the target worktree.
- Read `.nrepl-port` from the launch directory.
- Add bounded readiness waiting with explicit failure states for:
  - process exited early
  - `.nrepl-port` absent
  - invalid `.nrepl-port`
  - connect timeout/failure
- Once connected, establish the single managed nREPL client session.
- Record acquisition mode = `:started` and startup metadata in projection state.

4. Attach-mode acquisition
- Resolve endpoint from:
  1. explicit port (+ optional host)
  2. otherwise `.nrepl-port` in effective worktree
- Connect to the resolved endpoint.
- Establish the single managed nREPL client session.
- Record acquisition mode = `:attached` and endpoint provenance (`:explicit` vs `:dot-nrepl-port`) in projection state.
- Keep attach worktree-bound in psi state; do not infer worktree identity from the endpoint itself.

5. Canonical projected state + EQL surface
- Project managed project-nREPL public state into `:state*`.
- Include at least:
  - worktree-path
  - acquisition mode
  - lifecycle state
  - readiness
  - host/port/port-source
  - configured command vector when started
  - session metadata (`:single`, active-session-id)
  - capability flags
  - last startup/attach/eval summaries
- Add or extend resolvers so the feature is queryable from canonical runtime surfaces.
- Keep projection names clearly distinct from psi runtime nREPL metadata.

6. Eval path
- Implement canonical evaluate against the managed single nREPL session.
- Maintain one active operation at a time per worktree instance.
- Track operation id, timestamps, emitted out/err, final value, and failure summary.
- Surface explicit statuses:
  - success
  - error
  - interrupted
  - timed-out
  - unavailable
- Ensure transcript-visible output stays human-readable while structured state remains queryable.

7. Interrupt path
- Implement interrupt for the currently active eval on the managed session.
- Map psi interrupt semantics onto nREPL interrupt by active operation/message identity.
- Make interrupt behavior explicit when no eval is active.
- Project interrupt outcome into recent operation summaries.

8. Command/tool/UI integration
- Add a canonical control surface for users to:
  - start
  - attach
  - status
  - stop
  - restart/replace
  - eval
  - interrupt
- Keep the first integration path minimal and canonical; avoid adapter-specific semantics leaking into runtime meaning.
- Ensure adapters consume projected state/results rather than runtime handles.

9. Diagnostics + observability
- Make acquisition and eval failures diagnosable from inside psi.
- Capture enough metadata to explain:
  - what worktree was targeted
  - which acquisition mode was used
  - what endpoint was attempted/resolved
  - what command vector was launched
  - where readiness failed
  - what eval/interrupt operation was active
- Keep recent summaries bounded and transcript-safe.

10. Docs + proof
- Document:
  - project nREPL vs psi runtime nREPL
  - worktree targeting rules
  - started-mode command vector contract
  - attach-mode host/port + `.nrepl-port` discovery
  - single-session/single-flight behavior
  - interrupt semantics
- Add focused tests for:
  - target resolution
  - config validation
  - `.nrepl-port` discovery/parsing
  - single-instance conflict rules
  - started acquisition
  - attach acquisition
  - readiness failure modes
  - eval success/error/interrupted/unavailable
  - projection/query visibility

Implementation notes:
- Prefer small helpers that separate:
  - target/config resolution
  - acquisition
  - connection/session establishment
  - projection updates
  - eval/interrupt operations
- Keep process lifecycle and nREPL client-session lifecycle distinct in code and in projected state.
- Treat `.nrepl-port` discovery as an explicit helper with crisp error reporting, not scattered incidental file reads.
- Preserve the architecture rule that runtime handles live on `ctx`, while canonical status lives in `:state*`.

Risks / decision points to watch:
- precise project config location and precedence
- readiness timing and timeout defaults
- how much nREPL middleware is assumed for first-slice capabilities beyond eval/interrupt
- how best to present eval output in transcript/UI without overcommitting to a terminal model
- restart/replace semantics when a process exists but connection/session establishment failed partway

Verification strategy:
- land config/validation + runtime skeleton first
- then started-mode acquisition
- then attach-mode acquisition
- then projected state + EQL surface
- then eval
- then interrupt
- finish with command/tool integration, docs, and focused proof tests