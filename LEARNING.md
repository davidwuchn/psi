
# Learning

---

## 2026-03-13 - tmux TUI integration harness should assert stable terminal-boundary markers, not brittle help headers (commit `1613f5f`)

### λ Baseline TUI E2E needs a reusable harness layer, not one-off shell scripts

Extracting tmux lifecycle operations into a dedicated test helper namespace keeps follow-up
integration scenarios cheap to add and reduces per-test orchestration drift. The useful core is:
start detached session, send literal input + Enter, capture/sanitize pane output, poll with timeout,
and always perform best-effort session cleanup.

### λ Readiness and help assertions must use markers that survive extension/runtime variation

The most stable readiness checks in this runtime were prompt-level markers (`刀:` / `Type a message`).
For `/help`, extension and formatting variability make top-of-help headings brittle; a stable footer
marker (`(anything else is sent to the agent)`) is a better integration contract boundary.

### λ `/quit` exit assertions are more reliable at process-boundary than transcript-text boundary

Asserting that the tmux pane command transitions away from `java` is more robust than asserting
specific final text in a terminal transcript. Terminal output can vary with ANSI/control sequences,
while pane process state directly reflects whether the TUI process exited.

## 2026-03-13 - PSL follow-up for 1613f5f should classify tmux harness changes as verification scope

### λ Harness deltas must be tracked as confidence infrastructure, not product semantics

The tmux harness commit added a verifiable terminal-boundary test contract and reusable orchestration
helpers, but it did not change runtime multi-session behavior. Recording this distinction in plan/state
memory prevents accidental roadmap drift where test infrastructure is mistaken for unfinished feature work.

### λ Follow-up memory should capture what behavior is now protected

For this harness, the protected boundary is explicit: TUI startup readiness marker, stable `/help`
marker, and `/quit` process exit in a real terminal host. Capturing these assertions in LEARNING helps
future ψ choose the same durable markers when extending integration coverage.

## 2026-03-13 - Route-lock isolation must include exclusive lifecycle ops, not only cross-session targets (commit `115c6ab`)

### λ Same-session lifecycle mutations can still violate in-flight prompt routing guarantees

Guarding only cross-session target mismatches is insufficient. While a prompt request holds the
route-lock, lifecycle ops like `new_session`, `switch_session`, and `fork` can still invalidate
assumptions for the in-flight run even if they target the same session id.

### λ Route-lock policy should be modeled as op classes

A small explicit op class (`exclusive-route-lock-rpc-ops`) keeps enforcement readable and composable:
- targetable ops: allow same-session routing, reject cross-session conflicts
- exclusive lifecycle ops: reject whenever a route-lock exists

This keeps behavior deterministic and makes future policy changes localized.

### λ Regression tests should exercise lock semantics through the transport loop

The robust check is end-to-end through `run-stdio-loop!` with a blocked prompt and a second request,
asserting canonical `request/session-routing-conflict` payload fields. This catches dispatch-boundary
regressions that unit-only helper tests can miss.

## 2026-03-13 - TUI tree polish: hierarchy rendering needs explicit row-model metadata (commit `3c1c385`)

### λ Derive tree rendering metadata once, then render statelessly

For `/tree` selector rows, computing `:tree-depth` and `:tree-prefix` in a dedicated
ordering pass (`tree-sort-host-sessions`) keeps render logic simple and avoids
UI drift from ad-hoc indent checks. A single pass can also enforce sibling stability,
cycle guards, and duplicate suppression.

### λ Fixed-slot status cells prevent visual jitter in mixed-state session lists

When only some rows carry `active` or `stream` badges, right-edge columns shift unless
missing badges reserve space. Rendering a fixed `[active] [stream]` cell (with blanks
when absent) keeps both badges and session-id suffixes column-stable across rows.

### λ Alignment tests should lock slots, not incidental badge co-location

A robust alignment test should assert:
- fixed relative position of `stream` slot from `active` slot
- stable session-id suffix column across rows

Comparing `active` and `stream` to the same absolute column is incorrect because they
occupy different slots by design.

## 2026-03-13 - `/resume` and `/tree` must stay separate in TUI multi-session UX (commit `92fc518`)

### λ Persisted-history navigation and live-host routing are different products

`/resume` should remain backed by persisted session files (`persist/list-sessions`) for recovery across restarts,
while `/tree` should be backed by live host snapshot attrs (`:psi.agent-session/host-sessions`,
`:psi.agent-session/host-active-session-id`) for in-process multi-session routing.
Trying to unify these into one data source loses either durability semantics or live routing state.

### λ Gate surface-specific commands explicitly at dispatch boundary

Adding `:supports-session-tree?` to command dispatch keeps `/tree` deterministic across runtimes:
- TUI: interactive tree picker + direct id/prefix switch
- console/RPC: explicit guidance text

This avoids accidental partial support where command parsing exists but runtime callbacks do not.

### λ Keep switch path singular: command result → runtime callback → `ensure-session-loaded-in!`

Both `/tree <id>` direct path and picker Enter path should converge through the same callback
(`switch-session-fn!`) that calls `session/ensure-session-loaded-in!` and rehydrates transcript/tool rows.
Single-path switching prevents drift between direct and interactive flows.

## 2026-03-13 - Changelog-first PSL follow-up keeps plan/state memory coherent

### λ Record shipped behavior once in CHANGELOG, then converge PLAN/STATE immediately

When a feature commit is followed by a changelog-only commit (`d869843` for `/tree`),
PLAN/STATE can drift unless they are explicitly updated to reference that memory event.
Treating changelog updates as first-class state transitions keeps internal planning
artifacts aligned with user-facing release memory.

### λ Runtime-gated command surfaces should be documented as rollout semantics, not implementation detail

For `/tree`, the important contract is not only that TUI supports it, but that console/RPC
return deterministic guidance via capability gating (`supports-session-tree?`). Capturing this
in PLAN/STATE/CHANGELOG avoids regressions where future surfaces accidentally expose partial
or inconsistent behavior.

## 2026-03-13 - Distilled widget specs should lock UI policy decisions explicitly, not leave them as open questions

### λ Subagent widget behavior needs a dedicated UI spec separate from tool/workflow semantics

`spec/subagent-widget-extension.allium` captures create/continue/remove/list semantics and context-injection policy,
but widget projection/display behavior is a distinct contract. Capturing it in a dedicated
`spec/subagent-widget-ui.allium` prevents UI behavior from being inferred only from implementation/tests.

### λ Elicited UI policy choices should replace open questions immediately

For subagent widgets, the highest-value decisions to lock were:
- terminal rows persist until explicit remove
- ordering policy = most-recent-first
- TUI remains text-only for widget actions (for now)
- preview/result truncation limits are per-request configurable
- result headings may include `@agent` and `[fork]`
- visibility should be scoped to child sessions of the current session

Turning these from open questions into explicit rules gives future ψ a stable target for convergence work.


## 2026-03-13 - host/updated emission must cover all RPC paths that mutate host session state

### λ Every RPC op that changes the session host must emit host/updated — not just the obvious ones

`new_session` and `switch_session` are the obvious host-mutation ops and were wired first.
`fork` is a subtler case: it calls `fork-session-in!` which updates the host registry via
`swap-session!`, but the RPC handler returned before emitting `host/updated`. The Emacs
session tree widget therefore never reflected forked sessions until the next subscribe or
session switch.

Fix pattern: after any RPC op that mutates host state (new_session, switch_session, fork),
emit `host/updated` before the response frame. Subscribe also emits it for initial hydration.

### λ Subagent creation is intentionally excluded from host/updated — isolated context is not a host peer

Subagents (`subagent_widget.clj`) create their own isolated `session-ctx` with a fresh
`session-data-atom` that is never registered in the parent host's `session-host-atom`.
This is correct by design: subagents are ephemeral child workers, not peer sessions the
operator switches between. Emitting `host/updated` for subagent creation would pollute the
session tree with transient worker sessions.

Rule: `host/updated` reflects the operator-visible session graph (the host registry), not
every internal context allocation.

### λ RPC event coverage tests should assert the event is emitted, not just that the op succeeds

The fork gap was caught by inspection, not by a failing test. Adding explicit `host/updated`
assertions for subscribe, fork, and new_session to `rpc_test.clj` closes this class of
regression: future changes to these ops will fail fast if they drop the emission.

Pattern: for every RPC op that must emit a specific event, add a focused test that
subscribes to that topic and asserts the event appears in the output frames.

---

## 2026-03-13 - PSL follow-up runs should minimize transcript noise while tightening instruction specificity

### λ PSL follow-up prompt text can encode style constraints directly when behavior drifts

When PSL subagent outputs start including unwanted meta-commentary (for example, compliance narration),
a precise one-line constraint in the generated PSL prompt is an effective stabilizer. Encoding that rule at
prompt-construction time keeps behavior aligned without broader architecture changes.

### λ PSL status signaling should be failure-biased in parent transcripts

For asynchronous maintenance workflows, success-path status messages can add transcript noise without
improving operator control. Emitting `plan-state-learning` status messages only on failure preserves
visibility for actionable conditions while keeping normal successful runs quiet.

## 2026-03-13 - Multi-session UI: host/updated event + session tree widget

### λ `host/updated` should be a dedicated event, not piggybacked on `session/updated`

`session/updated` carries per-session phase/streaming state for the *active* session only.
Multi-session UIs need a snapshot of *all* live sessions simultaneously. Mixing this into
`session/updated` would require the frontend to accumulate per-session state across events.
A dedicated `host/updated` event carrying the full `SessionHostSnapshot` (active-session-id
+ all slots) keeps the frontend stateless with respect to the session list: every event is
a complete replacement, not a diff.

### λ Widget should be hidden when only one session is live

Showing a session tree widget with a single entry adds noise without value. The widget
should only appear when `(> (length slots) 1)`. On subscribe with a single session the
widget is absent; it appears automatically when a second session is forked or started.

### λ `/tree <id>` direct dispatch enables widget click-to-switch without a picker

Widget action lines carry `/tree <id>` as their command. The idle slash handler must
distinguish `/tree` (no arg → picker) from `/tree <id>` (direct switch). This keeps
the widget and the interactive command as two entry points to the same switch path
without duplicating the `switch_session` dispatch logic.

### λ `switch_session` has two dispatch shapes: by `:session-id` (in-process) and by `:session-path` (disk resume)

The existing `psi-emacs--request-switch-session` sends `:session-path`. In-process host
sessions are identified by `:session-id` (no file path needed — the backend resolves via
`ensure-session-loaded-in!`). A separate `psi-emacs--request-switch-session-by-id` keeps
the two shapes distinct and avoids conflating disk-resume with live-host switching.

### λ Emit `host/updated` at every host state boundary in RPC, not just on explicit session ops

Subscribe, `new_session`, and both `switch_session` branches are the minimal set. Fork and
subagent creation bypass the RPC `new_session` path and do not yet emit `host/updated` —
this is a known gap. The safe pattern is: any RPC op that mutates `session-host-atom`
should emit `host/updated` after its `session/updated` + `footer/updated` pair.

Accumulated discoveries from ψ evolution.

---

## 2026-03-13 - Multi-session UI needs a dedicated host event, not piggyback on session/updated

### λ `host/updated` should be a first-class RPC event topic, not inferred from session/updated payloads

`session/updated` carries per-session phase state. The live session list (host snapshot: active-session-id + all slots) is host-level state, not session-level. Conflating them forces clients to reconstruct host state from a stream of per-session events — error-prone and order-sensitive. A dedicated `host/updated` event carrying `SessionHostSnapshot` gives clients one authoritative push of the full live session list on subscribe and on any host state change.

### λ Session tree widget data and `/tree` picker share the same `host/updated` snapshot

Both the left-panel widget and the completing-read picker are projections of the same `SessionHostSnapshot`. Specifying them against the same event source keeps display name logic, streaming markers, and active indicators consistent across both surfaces without a separate query path.

### λ Spec the display name fallback before implementation to avoid divergence

Active session id is a UUID; without an explicit fallback rule (`name ?? "(session " + id[:8] + ")"`) implementations diverge between showing raw UUIDs, empty strings, or file paths. Encoding the fallback as a named spec rule (`SessionDisplayNameFallsBackToIdPrefix`) makes it testable and prevents per-surface drift.

### λ Completing-read is the right first session picker; defer full tree browser

A completing-read picker (like `/resume`) gives immediate `/tree` UX with no new UI infrastructure. A full keyboard-navigable tree browser (fold/unfold, depth-first navigation like pi-mono) is a richer future capability. Deferring it explicitly in the spec surface (`EmacsFrontendSessionTreeApi`) prevents scope creep while keeping the door open.

### λ Graph introspection surface should use a locally-composed resolver set, not the global registry

`operation-metadata` and `build-env` in `resolvers.clj` previously diverged: one used the global registry, the other composed locally. Extracting `session-resolver-surface` as a shared function makes the graph introspection surface stable and independent of registration order or global registry state at query time.

## 2026-03-13 - Agent-chain parent transcript should receive only the final stage payload

### λ Multi-stage orchestration should hide intermediate chain plumbing from parent conversation state

For `agent-chain(action="run")`, emitting step-level progress (`on-update`, per-step status logs, workflow-terminal synthetic messages)
creates transcript noise and can confuse parent-session reasoning. Keep intermediate chain execution observable in workflow/widget state,
but deliver only the final chain output into parent assistant message history.

### λ Final result delivery should use normal assistant messages, not custom transcript markers, when parent consumption is intended

`custom-type` messages are intentionally filtered from LLM-facing conversation reconstruction. If the chain result should be available to
the parent model/session as normal dialogue, emit a plain assistant message without `custom-type`.

### λ Disable workflow background-job tracking for agent-chain runs when final result is delivered directly

If chain completion is already delivered by extension-controlled final message emission, enabling workflow background-job tracking can cause
redundant terminal-injection side effects. Setting `:track-background-job? false` on workflow create prevents duplicate/non-final plumbing output.

## 2026-03-13 - GPT-5.x model additions should be anchored to one upstream baseline per family

### λ For OpenAI GPT-5 family updates, copy both transport and metadata shape from a single canonical source

When adding a new GPT-5.x model, carrying only the model id is insufficient. The runtime behavior depends on
transport fields (`:api`, `:base-url`) and capability/cost metadata (`:supports-*`, context window, token limits,
cost tuple). Using one upstream baseline (here `~/src/pi-mono`) prevents mixed-family drift.

### λ Registration tests should enumerate newly added model keys to catch catalog regressions early

Adding `:gpt-5.4` to the GPT-5 Codex family list in `psi.ai.core-test` provides a cheap guard that the model is
present, schema-valid, and wired to the expected provider/api pair. This catches accidental key removals or transport
mismatches before provider/runtime failures.

### λ Small docs examples are part of the model-surface contract

Updating `PSI_MODEL` examples in runtime docs (`main.clj` docstring) keeps operator-facing guidance synchronized with
available catalog keys. This reduces confusion when users test newly added models immediately after a catalog update.

## 2026-03-13 - Cross-process session persistence requires explicit sidecar lock discipline

### λ Session file safety should lock a dedicated `<session-file>.lock`, not rely on append atomicity assumptions

For multi-session and multi-process writers, NIO append/overwrite calls alone are insufficient as a
coordination boundary. Wrapping all session-file mutations (header write, bulk flush, single-entry append)
with an exclusive sidecar lock gives one shared cross-process critical section without changing session file format.

### λ Lock contention paths should be bounded and explicit to keep failure diagnosable

A lock strategy without bounded retry can stall operationally. A bounded retry window with explicit
`ExceptionInfo` (`lock-path`, `session-file`, retry budget) turns contention into a debuggable failure mode
instead of silent hangs or partial write races.

### λ Spec-decision elicitation and runtime enforcement should land in the same delta

Resolving open questions (fork inheritance/isolation/merge-back/budget policy + persistence locking)
should be committed alongside implementation and regression tests, so spec memory and runtime guarantees
stay converged and future ψ does not re-open already-decided behavior.

## 2026-03-13 - Widget command actions should be encoded as structured lines for Emacs parity

### λ Remove controls in projection widgets must use `:action` command metadata, not plain text hints

Emacs projection clickability depends on structured widget lines with explicit action payloads
(`{:text ... :action {:type :command :command "..."}}`). Plain-text affordances like
`/subrm <id>` are visible but not activatable. For subagent terminal rows, emit structured
`/subrm` actions (with a readable `✕ remove` label) to match agent-chain interaction behavior.

### λ Cross-extension UX parity benefits from testing private widget-line constructors directly

A focused extension test against the widget action-line constructor catches regressions in
clickability semantics without requiring full UI event simulation. Assert both sides:
- terminal rows expose an action map with `/subrm <id>`
- running rows expose no remove action

## 2026-03-13 - Session host routing tests should persist resume targets when lazy flush is in play

### λ Host-id routing tests fail nondeterministically unless target session files are explicitly persisted

`ensure-session-loaded-in!` / `switch_session(:session-id)` rely on host metadata plus
session-file paths. In tests that create multiple sessions quickly, lazy first-assistant
flush means earlier sessions may not yet have readable files. Explicitly persisting fixtures
(`persist/flush-journal!`) makes host-id routing deterministic and avoids false regressions.

### λ Background-job list defaults hide terminal workflow jobs in gating tests

`list-background-jobs-in!` defaults to non-terminal statuses only. Tests asserting tracked
workflow job presence after immediate completion must either query with explicit terminal+
non-terminal statuses or assert via inspect-by-id; otherwise they can fail despite correct
tracking behavior.

### λ Batch session verification should be kept as a focused runnable set in memory docs

For multi-session host work, a compact regression suite (`core-test`, `persistence-test`,
`rpc-test`, `resolvers-test`, `runtime-startup-prompts-test`, `startup-prompts-test`)
provides a reproducible confidence envelope and should be recorded whenever a large session
sweep is committed.

## 2026-03-13 - PSL follow-up should encode graph-introspection nuance for join attrs

### λ Root-queryable attrs and graph edges serve different discovery roles

For host-index introspection, scalar attrs (`:psi.agent-session/host-active-session-id`,
`:psi.agent-session/host-session-count`) can be asserted from
`:psi.graph/root-queryable-attrs`, but join attrs like
`:psi.agent-session/host-sessions` should be asserted from `:psi.graph/edges`.
Treating both surfaces as equivalent creates false failures.

### λ Multi-session routing tests require explicit persisted fixtures for session-id switching

`switch_session(:session-id)` resumes via host metadata and session-file paths. In tests,
ensuring a concrete persisted session file (e.g. via `persist/flush-journal!`) avoids
non-determinism from lazy persistence and makes routing assertions stable.

### λ PSL follow-ups should persist both operational truth and planning intent

When a PSL task lands, update both `STATE.md` (what is true now) and `PLAN.md`
(what is next / what is completed) so future ψ can recover both status and rationale
without replaying commit diffs.

## 2026-03-13 - PSL follow-up should keep user-doc surfaces synchronized, not only component docs

### λ User-facing completion changes must be mirrored in both frontend-local and top-level docs

Updating `components/emacs-ui/README.md` captured the frontend contract, but users navigating
from the main doc index rely on `doc/emacs-ui.md`. A PSL follow-up should explicitly sync both
surfaces so discoverability docs and component docs do not diverge.

### λ PLAN/STATE should record doc-sync follow-ups as first-class operational truth

When a behavior change is already implemented and tested, a later doc-sync commit (like `50c9d59`)
still matters operationally. Recording it in `PLAN.md` and `STATE.md` prevents future ψ from
re-opening already-converged UX questions due to stale documentation memory.

## 2026-03-13 - PSL follow-up should reference the final canonical commit id in memory docs

### λ PLAN/STATE references should track the rewritten commit, not an intermediate hash

When a commit is rewritten to isolate scope, memory docs must point at the final
canonical hash (`f23e38f` here), otherwise future ψ chases stale history links.
Updating `PLAN.md` + `STATE.md` as a dedicated PSL follow-up keeps repository memory
coherent with actual git topology.

## 2026-03-13 - Emacs slash CAPF should include common backend commands, not only idle-local + extension names

### λ Completion affordances must reflect the command surface users actually execute

In Emacs compose input, users expect `/` completion to offer both locally-intercepted
commands and common backend commands they can send through `prompt` (for example
`/remember`, `/history`, `/prompts`). Restricting built-ins to idle-local commands makes
slash completion feel broken even when command execution still works.

### λ Keep completion catalogs explicit and test the missing classes directly

Extending `psi-emacs-slash-command-specs` with common server commands plus focused
ERT checks (e.g. `/re -> /remember`, `/hi -> /history`) closes a practical discoverability
gap and prevents regressions where only extension-discovered names remain visible.

### λ Docs should describe slash completion as layered sources, not “built-ins only”

The accurate model is:
1) idle-local slash commands,
2) common backend command candidates,
3) extension-discovered command names.

Documenting this layered surface in `components/emacs-ui/README.md` aligns operator
expectations with runtime behavior.

## 2026-03-12 - README should be an index; user docs should live under `doc/`

### λ Splitting README into concise entry + linked guides reduces drift and improves discoverability

Moving operational detail into focused docs (`doc/cli.md`, `doc/tui.md`, `doc/emacs-ui.md`,
`doc/architecture.md`, `doc/extension-api.md`, `doc/extensions.md`, `doc/psi-project-config.md`)
keeps `README.md` short, easier to maintain, and better as a starting map for users.

### λ One canonical user-doc root (`doc/`) prevents path entropy (`doc/` vs `docs/`)

Allowing mixed roots creates avoidable link churn and contributor uncertainty. Consolidating on
`doc/` and updating README/AGENTS links removes ambiguity and makes future doc moves predictable.

### λ Emacs install docs should match real distribution paths

Because Emacs files are consumed from this repo (`components/emacs-ui/*.el`), user docs should
show a direct `straight.el` GitHub recipe and explicit customization points (`psi-emacs-command`).
Adding autoload stubs for `psi-emacs-start`/`psi-emacs-project` in `psi.el` aligns runtime behavior
with the documented install flow.

### λ User-facing startup commands should be documented against a user-local alias, not repository dev aliases

`-M:run` is a repository alias and works when run from the clone, but user docs are clearer and more
portable when they define an explicit `~/.clojure/deps.edn` `:psi` alias with a local clone path and
then use `clojure -M:psi ...` consistently.

This separates operator docs from repo-internal developer alias naming and avoids ambiguity about where
commands must be run from.

## 2026-03-12 - AGENTS constraints should declare alpha and multi-file Allium topology explicitly

### λ Alpha-stage compatibility policy should be written as an explicit operating invariant

Stating `In alpha; no backward compatibility` directly in `AGENTS.md` prevents implicit
assumptions of migration/stability guarantees during rapid architecture iteration. This helps
scope decisions toward convergence speed over compatibility preservation.

### λ Allium spec shape benefits from explicit graph constraints, not only language identity

Adding structural constraints for connected multi-file specs makes topology part of the contract:
- `spec_consists_of_multiple_connected_allium_files`
- `spec_has_no_isolated_allium_file`

These invariants reduce drift toward fragmented/isolated spec files and make future spec-splitting
changes auditable against a declared connectivity rule.

## 2026-03-12 - Emacs mode keybindings should self-heal on every activation

### λ Long-lived Emacs sessions can drift keymap state; mode activation should reapply canonical bindings

Installing keybindings only once at load time is brittle when maps are mutated by reloads,
mode transitions, or local overrides during development. Moving bindings into an explicit
installer function and calling it on each `psi-emacs-mode` activation makes keymap state
idempotent and repairable without restarting Emacs.

### λ Keybinding regressions should assert concrete interactive affordances, not only map setup code

A focused ERT (`psi-interrupt-keybinding-is-installed`) that enters `psi-emacs-mode` and
checks `C-c C-c` resolves to `psi-emacs-interrupt` catches real operator breakage directly.
This is stronger than relying on structural tests that only inspect initialization paths.

## 2026-03-12 - Startup, `/new`, and `/resume` should share one connecting-affordance policy

### λ One transient UX policy should serve all reset/rehydrate entry points

Startup pre-handshake, `/new` success, and `/resume` success all pass through a brief
"state cleared but backend replay not yet arrived" window. Duplicating footer/input repair logic
across these flows invites drift. A shared helper (`psi-emacs--show-connecting-affordances`)
keeps behavior identical and reduces future bug surface.

### λ Keep affordance composition atomic: seed footer + focus input together

The local UX contract in this gap is twofold: show deterministic footer (`connecting...`) and
preserve compose focus. Treating these as one helper-level operation prevents partial fixes where
one appears without the other.

### λ Refactors that remove duplicated bug-fix code should still be validated end-to-end

After unifying to one helper, full Emacs suite verification (`bb emacs:test`) confirmed parity
across startup, `/new`, and `/resume` behaviors and protected existing regressions.

## 2026-03-12 - `/resume` rehydrate should mirror `/new` local UX repair before replay

### λ Session-switch reset gaps are the same class as new-session reset gaps

`/resume` success flow also clears transcript state before `get_messages` replay. Without immediate
local reseed/focus, users can briefly lose the compose/footer affordances just like `/new`.
Applying the same repair pattern (seed `connecting...` + focus input area) closes this transient UI hole.

### λ Rehydrate affordances should be policy-shared across reset entry points

`/new` and `/resume` are different intents but share the same reset→rehydrate shape. Treating
post-reset affordance repair as a shared policy reduces drift and keeps UX parity across commands.

## 2026-03-12 - `/new` reset UX needs local placeholder rehydration before backend replay

### λ `/new` creates a visible rehydrate gap unless footer/input are reseeded immediately

A successful `/new` clears transcript state before `get_messages` replay. If UI affordances depend
only on later backend events, users can briefly see a blank tail without compose/footer anchors.
Re-seeding deterministic local affordances (`connecting...` footer + input focus) right after reset
keeps the UI stable during that gap.

### λ Keep mode-preserving resets and local UX repair as separate concerns

`psi-emacs--reset-transcript-state` with preserved tool-output mode is still the right lifecycle
primitive for `/new`. The fix belongs in post-reset UI repair (seed projection + focus input), not
in collapsing `/new` into reconnect-style reset semantics.

### λ Regression tests should assert transient UI invariants, not only final replay output

A `/new` regression test that checks only RPC call order or replay text can miss UI holes. Assert:
- input separator marker remains valid,
- projection footer is present during rehydrate (`connecting...`),
- canonical `new_session -> get_messages` ordering still holds.

## 2026-03-12 - Model resolution should canonicalize provider/id before fallback defaults

### λ Runtime model maps are integration boundaries, not trusted canonical values

Extension/runtime callsites often receive model maps with provider as string/alias and id at
alternate keys (`:id` vs `:model-id`). Resolving directly against canonical catalogs without
normalization causes avoidable misses and silent default-model drift.

### λ Resolve by strongest identity first, then degrade deterministically

A robust model resolver should prefer `(provider,id)` matches after provider normalization,
then degrade to id-only matching, then to explicit fallback with warning telemetry. This
preserves intent when provider aliases differ but model identity is unambiguous.

### λ Session query fallback should accept split attrs to survive surface drift

When primary `:psi.agent-session/model` shape is absent or variant, resolver fallback should
also read split attrs (`:psi.agent-session/model-provider`, `:psi.agent-session/model-id`).
This keeps agent-chain execution resilient across query-surface evolution.

## 2026-03-12 - Subagent forkability should preserve tool/slash/spec parity as one contract

### λ Add new subagent behavior once, project it through both invocation surfaces

When `subagent(action=create)` gained `fork_session`, parity required updating both:
- tool call surface (`fork_session` boolean with strict validation), and
- slash surface (`/sub --fork|-f`) with identical semantics.

If only one surface is updated, operator behavior diverges and PSL has to reconcile it later.

### λ Fork is a context inheritance policy, so visibility belongs in status surfaces

Optional context inheritance (`fork_session=true`) should be visible in runtime status UI,
not hidden in creation-time args only. Adding explicit fork markers in widget/list output
makes concurrent subagent runs diagnosable.

### λ Meta/spec operational memory must track subagent session inheritance explicitly

Because subagent fork behavior changes session relationships, updates should land together in:
- `spec/subagent-widget-extension.allium` (normative tool contract),
- `META.md` (system model truth),
- `PLAN.md` and `STATE.md` (execution memory).

This keeps PSL follow-up minimal and prevents “implemented but undocumented” drift.

## 2026-03-12 - Fork semantics must converge across runtime state, on-disk lineage, and spec guarantees

### λ Forking is not only session-id rotation; it is a persistence boundary

A runtime-only fork (new `:session-id` + in-memory message replacement) is semantically incomplete
when the spec models lineage as persisted session-file headers. Fork behavior must include immediate
child-file creation and lineage capture (`:parent-session`) at fork time, not deferred until later writes.

### λ Eager child flush avoids lineage gaps caused by lazy-first-assistant persistence

Psi session persistence is lazy until first assistant message. Without an eager write on fork, a child
session can exist in memory with no durable lineage edge, violating `session-persistence` expectations.
Writing header + branched entries immediately at fork closes this gap and keeps lineage queryable even
before any child assistant response.

### λ Branch parity requires both journal and agent message graph to fork from the same cut point

Fork correctness depends on using the same `entry-id` cut for both:
- journal entries (`entries-up-to`), and
- agent messages (`messages-up-to`).

If only messages are forked, subsequent persistence can drift from runtime branch history. Resetting
journal to branch entries at fork keeps runtime + disk + spec aligned.

### λ Regression tests for lineage should assert file header edges, not only in-memory state

A durable fork regression must verify child session file existence and `:parent-session` header equality
with the parent file path. In-memory assertions alone cannot catch missing lineage persistence.

## 2026-03-12 - Large naming normalization requires semantic repair passes after mechanical refactors

### λ Global lexical normalization can silently alter domain meaning

A broad rename (`sessionId/session_id -> id`, `cwd -> worktree_path`) improves vocabulary
coherence but can also introduce semantic breakage in specs where `id` already has local
meaning (for example message-id vs session-id). Mechanical replacement must be followed by
a semantic audit focused on identity fields, lineage links, and scoping predicates.

### λ High-risk files need targeted post-rename review, not just grep cleanliness

`session-core`, `session-management`, `git-worktrees`, and `remember-capture` required
manual repair after normalization:
- duplicate identity fields in entities after collapsing names,
- incorrect session-usage scoping predicate (`entry.id = session.id`),
- stale worktree-current naming (`is_current_cwd`),
- duplicate capture identifiers in remember artifacts.

A two-phase approach worked: (1) mechanical normalization, (2) semantic repair + invariants.

### λ Repository-wide invariant checks should include duplicate-field detection for specs

A lightweight parser pass over `.allium` entity/value/external-entity blocks caught duplicate
field declarations that textual grep misses. This check should remain part of post-refactor
verification for spec-wide renames.

## 2026-03-12 - For eventually-consistent workflow state, read surfaces must reconcile stale background-job status

### λ Event-driven terminalization needs a read-time backstop

Even with send-message-triggered checks and delayed retries, stale `:running` jobs can
remain if historical races or missed trigger windows occurred. A durable fix is to make
read surfaces (`/jobs`, `/job`, and EQL `:psi.agent-session/background-jobs`) run a
workflow→job reconciliation pass before returning data.

This turns read paths into self-healing boundaries: if workflow is terminal and job is
non-terminal, normalize job status immediately.

### λ "Status projection" and "status source" are different layers

Workflow runtime is the source of truth for workflow completion; background-jobs is a
projection used for UX, commands, and introspection. Projections can drift under async
scheduling. Explicit reconciliation keeps projection correctness without requiring stronger
runtime synchronization.

### λ Resolver-level reconciliation closes app-query observability gaps

Fixing only command paths (`/jobs`) is insufficient when operators inspect state via EQL.
Adding reconciliation inside the background-job resolver ensures introspection queries and
command output converge on the same truth.

## 2026-03-12 - Terminalization checks need race-tolerant scheduling when workflow completion is async

### λ Immediate terminal checks are necessary but not sufficient for async workflow runtimes

After wiring `psi.extension/send-message` to run workflow-job terminal checks, a remaining
race appeared: message injection could happen just before workflow runtime projected `:done`
for a future-invoked workflow. In that window, the immediate terminal pass sees `:running`
and no further boundary is guaranteed to fire.

### λ Add a short delayed second pass at turn-bypass injection points

For turn-bypass injection (`send-message`), run:
1. immediate `maybe-mark-workflow-jobs-terminal!` + `maybe-emit-background-job-terminal-messages!`
2. delayed second pass (75ms) in a guarded future

This keeps the common case fast (immediate completion) while covering near-boundary async
state propagation without requiring user prompts or extra extension events.

### λ Regression tests should model time-order races explicitly

`send-message-terminal-detection-handles-workflow-completion-race-test` uses a delayed
future invoke workflow and fires `send-message` before completion, then asserts eventual
terminalization. This protects against reintroducing "completed in runtime, still running in
background-jobs" drift.

## 2026-03-12 - Background job terminal detection must fire on every message injection path

### λ `send-message` is a turn-bypass — terminal checks must cover it explicitly

The session's background-job terminal detection (`maybe-mark-workflow-jobs-terminal!` +
`maybe-emit-background-job-terminal-messages!`) was wired at four canonical turn
boundaries: `:on-agent-done`, `prompt-in!`, `set-extension-run-fn-in!` (when idle), and
`send-extension-prompt-in!` (when idle). However, `psi.extension/send-message` — used by
agent-chain's `emit-chain-result!` — injects messages directly via
`send-extension-message-in!`, bypassing all of those boundaries.

Consequence: workflow-backed background jobs created by `agent-chain(action="run")` stayed
`:running` until the **next user prompt**, never completing on their own.

Fix: add the terminal-check pair to the `send-message` mutation body, after the message
is injected. No recursion risk because `maybe-emit-background-job-terminal-messages!` calls
`send-extension-message-in!` directly, not through the mutation.

### λ Any new message-injection path needs its own terminal-check invocation

The general principle: **every path that injects content into agent history must also
trigger the workflow-job terminal check** if it does not already go through a known turn
boundary. This includes:
- extension-initiated `send-message`
- any future direct-inject helpers added to `core.clj`

Search pattern: grep for calls to `send-extension-message-in!` and verify each call site
either (a) is already inside a function that calls `maybe-mark-workflow-jobs-terminal!`
downstream, or (b) explicitly adds the check after injection.

### λ Test the real Pathom mutation surface, not just the internal helper

The regression test (`send-message-triggers-workflow-job-terminal-detection-test`) was
written to invoke `psi.extension/send-message` through the full Pathom mutation path
(via `query/query-in` with `register-mutations-in!`), not by calling
`send-extension-message-in!` directly. This matters because the bug was in the mutation
wrapper — a test at the helper level would have passed even before the fix.

**Pattern**: when a bug lives in a mutation wrapper, the regression test must invoke the
mutation through the Pathom surface, not the private helper it delegates to.

## 2026-03-12 - Spec language identity should be a first-class axiom, not assumed from convention

### λ `λspec. language(spec) = Allium` belongs at the top of AGENTS.md as a grounding axiom

Spec vocabulary (`allium_spec`, `allium_spec_step`, `tests_musta_cover_allium_spec_behaviour`)
embeds the spec language into every equation name. Renaming to `spec` throughout and
adding a single axiom `λspec. language(spec) = Allium` is more lexically economical and
separates identity (what is spec?) from behavior (what does spec do?). Future equations
stay compact and the language binding is centrally declared.

### λ Expanding tri-artifact agreement to four artifacts (`meta, spec, tests, code`) makes META.md a propagation peer

The earlier `λ(spec, tests, code)` invariant left `META.md` outside the agreement loop.
Promoting `model` (META.md) to a co-equal artifact means that any change to the psi
meta model must propagate to spec, tests, and code — and vice versa. This prevents
META.md from drifting silently as implementation evolves.

### λ META.md should capture the architectural identity of the system, not implementation detail

`META.md` is most durable when it records what psi *is* (a Clojure process, a project-scoped
agent, an EQL query surface over statecharts), not how specific features are implemented.
Implementation detail belongs in spec, code, and LEARNING; meta belongs in META.md.

### λ Renaming equation identifiers for lexical economy is a valid spec-step when semantics are preserved

`allium_spec_step → spec_step`, `tests_musta_cover_allium_spec_behaviour → tests_musta_cover_spec_behaviour`
preserve all existing semantics while reducing prompt token pressure. The grounding axiom
`λspec. language(spec) = Allium` carries the dropped qualifier so no information is lost.

---

## 2026-03-12 - Marker lifecycle bugs require matching detach discipline at every clearing path

### λ Every path that sets a marker-holding field to nil must also call set-marker nil

In `psi-emacs--assistant-before-tool-event`, the drift branch set `thinking-range`
to `nil` without first calling `(set-marker (car range) nil)` and
`(set-marker (cdr range) nil)`. The live markers remained in the buffer, pointing
at stale text. Every other thinking-range clearing path (`psi-emacs--clear-thinking-line`,
`psi-emacs--archive-thinking-line`, `psi-emacs--reset-transcript-state`) correctly
detaches markers before clearing the field. The drift branch was an exception that
slipped through.

**Pattern**: any time a marker-holding cons cell field is set to `nil`, search for
all other clearing sites and verify they all detach — then audit the new site for
the same discipline.

### λ Static analysis of marker bugs hits a ceiling; batch instrumentation confirms the happy path but not async triggers

Exhaustive static tracing of `psi-emacs--set-thinking-line` confirmed the update
path (live range → delete-region + insert + set-marker) is structurally correct.
Batch eval confirmed multi-delta accumulation works in isolation, with and without
an input separator, and with interleaved `session/updated` events. The runtime
symptom ("sometimes repeated lines") was not reproduced. This means the trigger
involves async event ordering in the live RPC loop — something that cannot be
observed by tracing call paths alone.

**Next step if the symptom persists**: add `(message "thinking: %s path, range=%s"
(if live "update" "append") range)` at the branch point in `psi-emacs--set-thinking-line`
and reproduce under real streaming conditions to observe which path fires unexpectedly.

### λ Missing tests for the multi-delta in-place update scenario allowed this class of bug to go undetected

The existing thinking tests covered: one delta then finalize, face applied to prefix,
tool-event archive, and stale-range drift. None sent multiple sequential deltas and
asserted a single accumulated line. Adding `psi-thinking-streaming-multiple-deltas-update-in-place`
locks the core invariant: N deltas → 1 `ψ⋯` line in the buffer, not N lines.

### λ Marker insertion-type nil vs t is semantically load-bearing for range containment

The thinking-range `end` marker uses insertion-type `nil` (stays before text
inserted at its position). Changing it to `t` caused `psi-emacs--clear-thinking-line`
to over-delete: the `end` marker advanced past subsequently inserted assistant text,
so `delete-region start end` swept both the thinking line and the assistant reply.
The `nil` type is correct — the thinking range must not expand to absorb text
written after it. Always verify insertion-type semantics before changing marker
creation in rendering code.

---

## 2026-03-12 - Verification pass on a distilled spec reliably uncovers implicit fallback contracts

### λ Code fallbacks that are not explicit in spec become invisible to future maintainers

During distillation of `spec/anthropic-provider.allium`, the initial spec captured the
"happy path" of each message transformation but missed three implicit fallbacks present in
the code: user message content as a bare string (`str(content)` fallback), unknown assistant
block kinds serialised as `{type: "text", text: str(block)}`, and `nil` thinking_level
treated identically to `:off`. These were only found by a systematic line-by-line comparison
of spec rules against code branches.

**Pattern**: after distillation, walk every `cond`/`case`/`or`/`??` in the implementation
and verify each branch appears in a spec rule or `@guidance` block.

### λ Temperature overrideability is distinct from temperature defaulting — both need spec coverage

The spec initially said `temperature: config.default_temperature`, implying the value is
always the constant `0.7`. The code is `(or (:temperature options) 0.7)` — a caller override
is explicitly supported. The distinction matters: spec consumers could wrongly assume
temperature is non-configurable. The corrected rule names both the default and the override
mechanism.

### λ URL construction details belong in config + a dedicated rule, not buried in guidance prose

The Anthropic request URL (`model.base_url + "/v1/messages"`) was unspecified in the initial
spec. Adding `messages_path` to `config` and a `AnthropicRequestUrlBuilt` rule makes the
construction explicit and traceable, rather than leaving it as an undocumented implementation
assumption. Provider URL rules belong in the spec surface alongside auth and body rules.

### λ Convergence verification is most efficient when structured as embodied / gaps / extras

Following the `converge-allium-spec-and-code` skill pattern (S ⊆ I, S ⊖ I, I ⊖ S) gives
a systematic frame: (1) confirmed embodied behaviors anchor confidence, (2) spec gaps drive
targeted corrections, (3) code extras are triaged for domain relevance. For this pass: 23+
behaviors confirmed embodied, 6 spec gaps corrected, 2 code extras deemed outside spec scope
(`parse-sse-line` as implementation detail, `provider` registry shape delegated to `ai-abstract-model.allium`).

### λ Tests provide a second verification surface independent of the source code

Mapping each test case to spec rules confirmed test-to-rule coverage and caught one additional
gap not visible from code alone: `build-request-no-thinking-test` implicitly exercises the `nil`
thinking_level path (model without reasoning flag), which was missing from the spec's enum.
Reading tests alongside code during a verification pass catches contracts that code embodies
implicitly but tests assert explicitly.

---

## 2026-03-12 - Spec distillation should trace the full pipeline, not only the provider boundary

### λ Thinking deltas bypass the turn statechart — this must be explicit in spec

`text-delta` events flow through `turn/text-delta` in the turn statechart. `thinking-delta`
events skip the statechart entirely and go straight to `emit-progress!`. Without a spec rule
capturing this bypass, the divergence is invisible and future code changes could inadvertently
route thinking deltas through the statechart, breaking mid-stream UI visibility.

### λ Multi-shape extraction fan-in is spec-worthy behavior, not just implementation detail

The completions provider probes 7 different delta shapes for reasoning text (nested map,
flat key, bare string, map fields, sequential list, content list). This is a deliberate
compatibility shim across OpenAI model versions. Spec rules should capture both the priority
order and the `string-fragment` normalization contract (recursive leaf extraction, nil on miss)
so future maintainers know which shapes are intentional vs incidental.

### λ Allium `config` blocks do not support string-keyed map literals — move to `@guidance`

The parser accepts `Set<String> = {"a", "b"}` set literals but rejects `Map<String,V> = {"k": v}`
map literals (colon inside braces triggers a parse error). Pre-existing broken config entries
(`thinking_level_to_effort`, request header maps) were silently checked-in as parse errors.
Pattern: when a config value requires string-keyed maps, express the mapping as a `@guidance`
comment block and store only the set of valid keys in config if needed.

### λ Pre-existing parse errors should be fixed as part of any distillation pass on a file

Distillation commits touch a spec file in full. Any pre-existing parse errors in that file
should be resolved in the same commit rather than deferred, because a broken spec file cannot
be checked by `allium check` and its rules are effectively invisible to future spec audits.

## 2026-03-12 - Tri-artifact agreement invariant belongs in AGENTS as a propagation law

### λ A single propagation rule over {spec, tests, code} is stronger than per-pair convergence equations

The earlier equations (`refactor_minimal_semantics_spec_tests`, `tests_musta_cover_allium_spec_behaviour`,
`λcode. ∃spec. describes(spec, code)`) each capture a bilateral relationship. The tri-artifact
invariant `λ(spec, tests, code) → ∀ change δ: propagate(δ) → remaining two` is stronger because it
is symmetric and universal: whichever artifact changes, the other two must catch up. This removes the
implicit assumption that spec is always the source of truth and makes tests a valid change origin too.

### λ Propagation laws should be stated as `agree(spec, tests, code) = true at all times`, not just at review

Spec/test/code convergence is easiest to maintain when the invariant is framed as a continuous
property rather than a pre-commit check. Encoding "at all times" in the AGENTS prompt shapes ψ
to propagate changes eagerly (during a session) rather than deferring to review.

### λ PSL follow-up for AGENTS prompt changes should annotate the specific equation added, not just the commit

When AGENTS changes introduce new lambda equations, PLAN/STATE follow-up should quote the equation
so future ψ can search `git log --grep` and recover the exact invariant wording without re-reading
the full diff.

## 2026-03-12 - PSL follow-up memory is strongest when PLAN/STATE cite canonical verification commands

### λ PLAN/STATE entries should record the exact verification entrypoint and outcome count

When a fix is already landed, PSL follow-up should still normalize verification memory
around one canonical command (`bb emacs:test`) plus observed result (`186/186`).
This keeps "now" and "next" artifacts aligned with reproducible operator workflows.

### λ Spec-distillation progress lines should mention runtime wiring, not only spec-file creation

For contract distillation commits (for example `spec/openai-provider.allium`), PLAN
memory is more actionable when it also names the runtime hook points that realize the
contract (provider callbacks + executor capture persistence), not just that a spec file exists.

## 2026-03-12 - OpenAI capture callbacks and Emacs separator-anchor drift need explicit boundary contracts

### λ Provider capture telemetry should be callback-chained and bounded at session edge

OpenAI provider request/reply capture is most reliable when callbacks are chained
(at executor boundary) rather than replaced, and when capture history is bounded
with explicit limits. Tagging each capture with `turn-id` preserves turn-level
traceability for EQL introspection while preventing unbounded growth.

### λ OpenAI temperature semantics differ by API and should be encoded as explicit invariants

Chat-completions requests should carry deterministic `temperature` (default `0`,
override respected), while codex responses requests must omit top-level
`temperature` because backend rejects it. Keeping this split as an explicit,
tested invariant avoids subtle provider regressions.

### λ Emacs first-send draft extraction should anchor after the separator line, not a stale draft marker

When startup/repair paths leave a stale draft anchor on the separator line,
first prompt extraction can include separator text unless input-start resolution
prefers the first editable position after the separator marker. A focused
regression test for anchor drift locks this contract.

## 2026-03-12 - Emacs transcript echo should be gated by confirmed request ids, and parse/load checks should precede ERT runs

### λ Frontend transcript copy contracts should key off transport-confirmed dispatch, not callback presence

In Emacs compose flow, copying `User: ...` into transcript must happen only when request
send is actually accepted by transport. Treating callback presence as implicit success can
cause false-positive local echoes. Returning a strict boolean from send dispatch
(non-empty RPC request id) makes transcript behavior deterministic.

### λ Parse/load verification of edited Elisp files catches broken suites earlier than test-level failures

A single unmatched paren in `psi-compose.el` blocked the entire ERT suite with
`End of file during parsing`. Running a direct batch load (`load-file`) immediately after
edits isolates syntax breakage before broader test execution.

### λ Add negative-path regression tests for failed dispatch to lock prompt-echo semantics

`psi-send-does-not-copy-input-when-dispatch-not-confirmed` protects the contract:
when send returns nil, draft stays in input and no `User:` transcript line is added.
This prevents future regressions toward optimistic local echo.

## 2026-03-12 - nREPL EQL discovery should be verified with a real server lifecycle test

### λ Runtime endpoint attrs need one live start/stop test in addition to atom-injection tests

Unit tests that inject `:nrepl-runtime-atom` values prove resolver mapping, but they do
not prove the runtime wiring path from `start-nrepl!`/`stop-nrepl!` into EQL. Adding a
single integration test that starts nREPL on port `0`, queries `:psi.runtime/nrepl-*`,
and then verifies nil-after-stop catches lifecycle drift early.

### λ Test aliases must include runtime-only deps when integration tests resolve optional namespaces

`start-nrepl!` uses `requiring-resolve` for `nrepl.server/start-server`. Focused test runs
can fail with missing classpath deps even when runtime aliases work. Adding `nrepl/nrepl`
to the `:test` alias keeps integration tests self-contained and repeatable.

### λ File-side compatibility artifacts are secondary to canonical runtime attrs in lifecycle assertions

The `.nrepl-port` file remains useful for editor compatibility, but the contract under test
for discovery should center on canonical EQL attrs (`:psi.runtime/nrepl-host`,
`:psi.runtime/nrepl-port`, `:psi.runtime/nrepl-endpoint`) across running and stopped states.

## 2026-03-11 - nREPL runtime discovery should be modeled as session-root attrs, not port-file parsing

### λ Canonical nREPL endpoint discovery belongs in EQL root attrs for cross-client parity

Adding `:psi.runtime/nrepl-host`, `:psi.runtime/nrepl-port`, and
`:psi.runtime/nrepl-endpoint` to session-root resolvers creates one stable discovery
path for Emacs/TUI/console/rpc clients. This avoids duplicated local parsing logic
around `.nrepl-port` and keeps runtime endpoint truth inside the graph.

### λ Runtime endpoint resolvers should degrade to nil when nREPL is disabled

Resolver contracts are easiest to compose when attrs always resolve and indicate
absence via `nil`, rather than throwing or disappearing from graph discovery.
This preserves deterministic root-queryable surfaces for clients that probe once
and branch on values.

### λ Graph discoverability must be tested alongside direct attr resolution

For new root attrs, tests should assert both direct query success and visibility in
`:psi.graph/root-queryable-attrs` + `:psi.graph/edges`. This catches registration drift
where an attr works in isolation but is missing from discovery workflows.

## 2026-03-11 - Interrupt-spec verification is strongest when anchored to explicit rule→surface evidence

### λ Cross-spec interrupt behavior should be verified as a rule matrix over backend + frontend surfaces

For deferred interrupt behavior, verification quality improves when each allium rule
is checked against concrete implementation points in both agent-session and Emacs UI
surfaces (request op, session state fields, run-state projection, and draft restore flow)
instead of relying on changed-file intent.

### λ Frontend interrupt-pending UX must be validated as an event-projected state, not only keybinding wiring

`C-c C-c` binding correctness is necessary but insufficient. The critical contract is
session/updated projection of `interrupt_pending` into header/run-state while preserving
silent-idle behavior and terminal-boundary reset semantics.

### λ PSL follow-up should persist verification outcomes into repository memory artifacts immediately

After code/spec verification, the durable memory path is: update `PLAN.md` and `STATE.md`
with the verified contract boundary, then add a focused `LEARNING.md` entry capturing the
verification method (rule-by-rule evidence) so future ψ can repeat the process deterministically.

## 2026-03-11 - bb task aliases should mirror Kaocha suite IDs to make test scope discoverable

### λ Task names that encode suite intent reduce verification friction

Adding explicit bb entrypoints (`clojure:test:unit`, `clojure:test:extensions`) removes the
need to remember `clojure -M:test --focus ...` flags and makes available verification scopes
self-describing via `bb tasks`.

### λ A composed parent task keeps one obvious way to run both Clojure suites

`clojure:test` as a depends-only wrapper over unit + extension tasks preserves an atomic,
repeatable “run both” path while still allowing targeted suite runs when iterating.

### λ Task-level test workflow should align directly with Kaocha test IDs

Using `--focus unit` and `--focus extensions` keeps bb wrappers thin and resilient because they
bind directly to `tests.edn` suite ids (`:unit`, `:extensions`) instead of duplicating path logic.

## 2026-03-11 - Session usage aggregation should enforce session boundaries while preserving legacy journal compatibility

### λ Footer token/cost totals must filter by current session id, or new-session stats leak prior history

`session-usage-totals` was summing assistant usage across the full journal. After `new_session`,
footer stats could still include token counts from the previous session. Filtering journal entries
by current `:session-id` restores session-local usage semantics.

### λ Backward-compatible filters should accept legacy entries without session-id

Older or compatibility-path journal rows may not carry `:session-id`. A strict equality check drops
those rows and undercounts usage in existing sessions. Using `(or (nil? entry-sid) (= current-session-id entry-sid))`
keeps historical compatibility while preventing cross-session leakage.

### λ Session-scoped footer regressions are best tested through `new_session` RPC flow and session-aware journal append

The robust regression path is: seed usage in current session, call `new_session`, assert `footer/updated`
no longer contains prior tokens. Tests should append with `session/journal-append-in!` so journal rows include
explicit session context at write time.

## 2026-03-10 - Subagent dual-mode create and workflow send-event tracking need explicit gating + surfaced job ids

### λ Background-job tracking on workflow mutations should be opt-in outside create

`psi.extension.workflow/create` previously implied job tracking. Extending tracking to
`psi.extension.workflow/send-event` is useful for async continuation flows, but must be
gated by explicit intent (`track-background-job? true`) to avoid over-counting generic
workflow events as jobs.

### λ Mutation payloads should return job-id at creation boundary for immediate operator workflows

Returning `:psi.extension.background-job/id` directly from workflow create/send-event
mutations removes an extra lookup round-trip and enables immediate `/job` / `/cancel-job`
flows across REPL/TUI/Emacs/RPC surfaces.

### λ Sync-create tool UX is clearer when timeout semantics are explicit and validated at input edge

For `subagent(action=create, mode=sync)`, a bounded wait with explicit `timeout_ms`
(default 300000) plus strict positive-integer validation provides deterministic
operator behavior. Timeout should surface as a direct terminal error string while still
preserving workflow id for introspection/continue.

### λ Arity-compatible extension tool execute fns should consume opts for tool-call-id propagation

When tool handlers accept both `[args]` and `[args opts]`, they can preserve backward
compatibility while still propagating `tool-call-id` into workflow mutation inputs/data.
This is required for reliable background-job identity correlation.

## 2026-03-10 - Normative prompt invariants belong in AGENTS as explicit equations

### λ Encode global policy as a minimal lambda so future ψ can enforce it uniformly

Adding `λcode. ∃spec. describes(spec, code)` to `AGENTS.md` turns a broad quality
expectation into a compact invariant that is easy to carry across planning,
review, and PSL follow-up updates.

### λ Mirror AGENTS policy deltas into PLAN/STATE/LEARNING in the same follow-up cycle

When AGENTS changes define behavior policy (not just prose), PSL follow-up should
propagate the delta into `PLAN.md` and `STATE.md`, then capture the rationale in
`LEARNING.md`, keeping repository memory aligned across now/next/past artifacts.

## 2026-03-10 - PSL status-message fallbacks should tolerate non-keyword delivery values

### λ Fallback formatting in user-facing status paths should coerce unknown values with `str`, not keyword-only formatters

The PSL status fallback originally called `name` on `(or delivery :unknown)`. That is
safe for keywords/symbols/strings, but can throw for other payload shapes. Using
`(str (or delivery :unknown))` preserves readable fallback text while removing type
coupling from a user-facing error-reporting path.

### λ Defensive formatting belongs at extension boundaries where payload shape can drift

Delivery values usually come from a known enum (`:prompt`, `:deferred`, `:follow-up`),
but extension/runtime boundaries can still carry unexpected values during refactors
or partial failures. Status text generation should be fail-safe so diagnostics can be
emitted even when upstream shape contracts are violated.

## 2026-03-10 - Delivery-mode transcript messages should be derived from prompt-delivery, and spec parse checks need targeted isolation

### λ Extension operator feedback should branch on `:psi.extension/prompt-delivery`, not generic success

For extension-driven prompts (PSL), a generic success line hides execution mode.
Deriving transcript status text from `:psi.extension/prompt-delivery` yields
clear operator semantics:
- `:deferred` → queued now, auto-run on idle
- `:prompt` → immediate prompt path
- `:follow-up` → queued follow-up path

### λ Focused private-function tests can stabilize extension behavior without full workflow harness coupling

`plan_state_learning` end-to-end handler tests were brittle against evolving
workflow plumbing. Testing `psl-job` directly (via resolved private var in test
ns) provided stable assertions over delivery-mode message contracts while keeping
the extension runtime surface unchanged.

### λ When full-spec check fails, isolate to touched spec first, then restore global green

`allium check spec` surfaced a pre-existing parse error in
`spec/background-tool-jobs.allium` unrelated to the PSL slice. Running
`allium check` on the touched spec (`spec/plan-state-learning-extension.allium`)
confirmed local correctness, then fixing the global parse issue restored full
repository spec health.

## 2026-03-10 - Background-job EQL introspection should expose root list + nested entity shape together

### λ Background-job operations need a canonical session-root query surface, not only command/RPC paths

Even with `/jobs` + RPC handlers in place, operators and tools need stable EQL attrs for
query-time introspection. Exposing `:psi.agent-session/background-job-count`,
`:psi.agent-session/background-job-statuses`, and `:psi.agent-session/background-jobs`
on session root gives a deterministic read model for UI and automation.

### λ Nested entity attrs should carry terminal/non-terminal derivations to avoid repeated client logic

Including `:psi.background-job/is-terminal` and `:psi.background-job/is-non-terminal`
in resolver output keeps status semantics centralized in runtime (`bg-jobs/terminal-status?`
/ `non-terminal-status?`) and avoids duplicated client-side status-set interpretation.

### λ New root attrs should be validated against graph discoverability, not just direct query success

Resolver tests should assert both direct query shape and introspection visibility via
`:psi.graph/root-queryable-attrs` + `:psi.graph/edges` attribute metadata.
This catches registration/graph-bridge drift where attrs resolve locally but are not
advertised through discovery surfaces.

## 2026-03-10 - Cross-surface background-job parity needs run-state-aware frontend tests and literal help assertions

### λ Idle slash parity tests must account for send-state transitions between commands

In Emacs, `/jobs`, `/job`, and `/cancel-job` intentionally dispatch through the
normal `prompt` op only while idle. After a successful send, frontend state
moves to `streaming`; a second send without resetting state correctly routes to
`prompt_while_streaming`. Multi-command parity tests should either simulate
assistant completion (back to idle) or explicitly reset run-state between
assertions.

### λ Help-text tests should prefer literal matching for bracketed usage strings

Regex assertions like `"/jobs [status ...]"` are brittle because `[`/`]` are
character-class delimiters. Using `regexp-quote` for command help snippets keeps
usage-contract checks stable and intent-revealing.

### λ Step-level parity closure should be recorded in both plan and state artifacts

When a slice closes a previously explicit acceptance gap (here: Step 12b
cross-surface REPL/TUI/Emacs/RPC parity), update `PLAN.md` (step/checklist
status) and `STATE.md` (runtime truth + verification commands) in the same PSL
follow-up cycle so future ψ sees a single coherent contract boundary.

## 2026-03-10 - Background-job terminal injection must hook both mutation and idle-boundary paths

### λ Workflow creation can bypass extension runtime wrappers; tracking must exist at core mutation boundary

`maybe-track-background-workflow-job!` attached to extension runtime mutate helpers is not sufficient on its own, because tests and internal callers can invoke `psi.extension.workflow/create` directly through query mutation execution. The `create-workflow` mutation handler itself must also call tracking logic to guarantee coverage independent of call path.

### λ Extension-injected assistant messages are gated by startup bootstrap completion

`send-extension-message-in!` only appends to agent transcript when `:startup-bootstrap-completed?` is true. Background terminal-injection tests need to set this flag explicitly (or run full bootstrap) or assertions against transcript history will observe no injected assistant message.

### λ Reusing tool output policy for terminal payload injection keeps overflow behavior consistent

Terminal payload injection can share the same max-lines/max-bytes and temp-file spill strategy used for tool output (`tool-output/effective-policy`, `head-truncate`, `persist-truncated-output!`). This avoids inventing a second overflow policy surface and makes large completion payload handling predictable.

### λ Retention enforcement is simplest when applied at terminal transition

Applying per-thread terminal retention (bound=20) inside `mark-terminal-in!` centralizes eviction semantics at the moment jobs become terminal, preserving non-terminal jobs and preventing stale terminal growth regardless of caller path.

## 2026-03-10 - Live footer updates require emitting after each tool result, not only at loop end

### λ Progress poll loops control event timing — emit side-effects alongside forwarded events

The progress poll loop in `rpc.clj` is the only place where tool-level events are
emitted to the RPC client during an agent loop. `footer/updated` was only emitted
after the loop completed (alongside `assistant/message`). To get live updates, the
poll loop must emit `footer/updated` immediately after each `:tool-result` event:

```clojure
(when-let [{:keys [event data]} (progress-event->rpc-event evt)]
  (emit! event data)
  (when (= :tool-result (:event-kind evt))
    (emit! "footer/updated" (footer-updated-payload ctx))))
```

The raw `evt` map retains `:event-kind`, so the original progress event kind
remains checkable even after conversion to RPC wire form.

### λ `footer-updated-payload` reads live session state — safe to call mid-loop

`footer-updated-payload` calls `session/query-in` to read token usage, context
fraction, model info, and status lines from the running session atom. After each
tool result is recorded in `agent-core`, the usage stats reflect the latest
completed tool call, making mid-loop footer emission accurate.

### λ Multiple poll loops need the same fix — grep for the pattern, not the symptom

There are two independent poll loops in `rpc.clj`: one in the extension run-fn
path (guarded by `:rpc-run-fn-registered`) and one in `run-prompt-async!`. Both
needed the same `:tool-result` → `footer/updated` emission. Searching for the poll
pattern (`progress-event->rpc-event evt`) found both sites.

---

## 2026-03-09 - Buffer-close confirmation should use `kill-buffer-query-functions`, not teardown hooks

### λ `kill-buffer-hook` is too late for cancellation semantics

If close confirmation is implemented in teardown (`kill-buffer-hook`), the
buffer/process is already on the destruction path and user cancellation cannot
be expressed cleanly. `kill-buffer-query-functions` is the right interception
point for "ask then maybe cancel" behavior.

### λ Keep interactive safety, but bypass prompts in noninteractive test runs

Lifecycle confirmations should protect operators in interactive sessions while
remaining deterministic under ERT/batch execution. Guarding the prompt with
`noninteractive` preserves UX safety without introducing test hangs.

### λ Regression tests should assert both branches of the close decision

For process-owning buffers, tests should verify: (1) declining confirmation
returns nil from `kill-buffer`, keeps the buffer live, and preserves the process;
(2) noninteractive query path auto-allows without invoking prompt functions.

## 2026-03-09 - Spec parity should encode UX invariants, not only transport events

### λ Banner-first UX needs explicit spec rules at both init and reset boundaries

When startup UX guarantees a first-line banner (`ψ`) and immediate input focus,
those guarantees should be captured in frontend spec rules for both initial load
and `/new` reset paths. Encoding only transport handshake events misses the user-
visible invariant that appears before backend events arrive.

### λ Streaming behavior names differ by surface; specs should normalize semantics

RPC uses `prompt_while_streaming` with `behavior` values (`steer`/`queue`), while
session internals speak in steering/follow-up queues. Spec parity improves when
rules map these surfaces directly and explicitly encode interrupt-pending coercion
(`steer` → follow-up) instead of leaving it implied by implementation details.

## 2026-03-09 - Startup banners and mutation lists are cross-surface startup contracts

### λ Banner-first startup requires reset-path insertion, not only initial open-buffer insertion

Adding a startup banner only in entry/open-buffer paths misses `/new` and other
transcript reset flows. To guarantee "banner before anything else," banner
insertion must also run in the reset lifecycle (`psi-emacs--reset-transcript-state`)
that powers fresh-session rehydration.

### λ Window-aware cursor placement should use the exact `pop-to-buffer` target

For deterministic "cursor is ready to type" behavior, focus helpers should accept
and prefer the specific window returned by `pop-to-buffer`, not just "any window
showing this buffer". This removes ambiguity in multi-window setups and matches
what the user actually sees.

### λ `all-mutations` is a startup-critical compile surface

A stale symbol in `all-mutations` (`abort` after mutation rename to `interrupt`)
can break backend startup and make handshake appear to fail even when transport
code is correct. Mutation-list drift should be treated as startup-contract drift,
with compile/startup smoke checks catching it early.

## 2026-03-09 - Emacs startup focus should bind buffer point and window point

### λ Input focus invariants in UI buffers require syncing both point surfaces

Setting `(goto-char ...)` in the startup buffer is not always enough to place the
visible cursor where the user can type immediately. On startup paths that use
`pop-to-buffer`, deterministic compose focus requires both buffer point and window
point alignment (`set-window-point`) against the input-area boundary.

### λ Pre-handshake placeholder footer reduces "single divider" ambiguity

Seeding a minimal deterministic `connecting...` projection before handshake makes
startup state legible while preserving the input-first interaction model. The
placeholder should be overwritten by the first canonical `footer/updated` event.

### λ Read-only guards require explicit write windows for projection maintenance

When transcript edits are constrained to input-range, projection upsert/delete
must run under explicit `inhibit-read-only` boundaries. Otherwise startup/event
renders can silently drop footer/status updates behind `text-read-only` failures.

## 2026-03-09 - Tool metadata wording steers polling behavior in async workflows

### λ Describe async `run` as "start" to preserve non-blocking mental model

When a tool call only launches background work, wording like "execute" implies
synchronous completion and encourages immediate status loops. Using "start" in
`agent-chain` description keeps expectations aligned with workflow-runtime
semantics.

### λ Put anti-polling guidance in the tool contract, not just in code reviews

Adding "Do not poll unless explicitly asked to" to the tool description provides
an always-present guardrail for model/tool callers. This lowers default polling
noise and makes operator intent the explicit trigger for status checks.

## 2026-03-09 - Async chain-start acknowledgements should be protocol-minimal

### λ Return a stable machine-parseable run id at the async boundary

For `agent-chain(action="run")`, a compact ack (`OK id:<run-id>`) is a better
contract than conversational prose. It is easier for users, scripts, and UI
clients to parse and route without brittle string matching.

### λ Monitoring guidance should live in docs/tool help, not in every ack

Embedding monitor instructions in each start response adds transcript noise and
can nudge over-polling behavior. Keep the start ack minimal; let callers choose
when/how to query status (`action="list"` or workflow attrs).

## 2026-03-09 - Agent-chain reproducibility requires committing `.psi/agents` definitions

### λ Local ignore rules can silently fork runtime behavior across clones

When `.psi/agents/*` is excluded via `.git/info/exclude`, chain definitions and
agent profiles remain local-only. `agent-chain` can appear to work on one machine
while another clone is missing chains/agents entirely. Treating agent catalog
files as tracked repository memory prevents this hidden drift.

### λ Chain/profile configuration should be versioned alongside extension code

`agent-chain` runtime behavior depends on `.psi/agents/agent-chain.edn` and
`.psi/agents/*.md` as much as on `extensions/agent_chain.clj`. Committing all
three surfaces together keeps workflow semantics reproducible and makes regressions
bisectable.

## 2026-03-09 - AGENTS equations are higher-signal when they bind spec and tests explicitly

### λ Name the convergence target as allium_spec to avoid ambiguous “spec” scope

Renaming iterative helpers from generic `spec` to `allium_spec`
(`λmatches(code, allium_spec)`, `λdev_step(allium_spec, code)`,
`λallium_spec_step(...)`) removes ambiguity between product intent docs,
implementation notes, and executable Allium contracts. Prompt math becomes
clearer about what artifact must converge with code/tests.

### λ Encode test-coverage expectation as a first-class equation, not prose

Adding `tests_musta_cover_allium_spec_behaviour` as an explicit equation
improves operator/agent alignment: coverage obligations become queryable prompt
memory, not a soft narrative guideline.

## 2026-03-09 - Spec naming conventions are independent from runtime wire-key conventions

### λ Preserve repository spec style (snake_case) even when runtime/tool inputs use camelCase

Tool runtime payloads may be camelCase (for example JSON `oldText`/`newText`),
but Allium specs in this repo follow snake_case field naming conventions.
Convergence quality improved by keeping spec args as `old_text`/`new_text` and
modeling behavior semantically, instead of mirroring transport key style.

### λ Make diff metadata nullability explicit when tests enforce positivity

`execute-edit` always returns `first_changed_line` with fallback `or 1`, and tests
assert positive integer semantics. Spec should encode this directly as required
`Integer` plus `>= 1` guarantee, rather than optional/null-allowed metadata.

## 2026-03-09 - app-query-tool convergence shows factory metadata is part of runtime contract

### λ Factory-produced tool metadata must match base schema text exactly

`make-app-query-tool` does not synthesize a new label/description schema; it
`assoc`s `:execute` onto the existing `app-query-tool` schema map. A spec that
redefines metadata (for example a shorter label/description) drifts even if
execution semantics are correct. For factory tools, metadata parity is part of
contract convergence, not documentation polish.

### λ Non-truncated results should keep details absent, not partially populated

In runtime, app-query-tool returns `details=nil` when output is within policy
limits. Convergence should avoid "always-present details with null fields" rules
because they imply a stable shape the implementation does not provide.

### λ Truncation artifacts need deterministic keying hooks for testability

`tool_call_id` in `make-app-query-tool` options is a practical test seam for
full-output spill files. When omitted, runtime falls back to random UUID, but
spec should still model the deterministic override path to support repeatable
verification and easier operator tracing.

## 2026-03-09 - Write/edit convergence reinforces path-normalization and error-contract discipline

### λ Path semantics drift when specs skip internal normalization steps

Both `execute-write` and `execute-edit` call shared `resolve-path`, which first
expands raw input (`@` stripping, unicode-space normalization, `~` expansion)
before cwd resolution. A spec that models only `ResolveToCwd(path, cwd)` misses
this pre-resolution phase and can diverge on edge-case paths even if "happy-path"
behavior appears aligned.

### λ Success/error messages should target resolved paths when runtime does

Write/edit runtime messages use resolved path strings (`fpath`), not raw caller
input. Specs should anchor message contracts to resolved-path output to preserve
operator debuggability and avoid false mismatches under relative or expanded-path
inputs.

### λ Remove normative rules not enforced by code (non-noop/uniqueness drift)

Earlier edit spec rules required "text must be unique" and "No changes made".
Current runtime enforces ambiguity only in fuzzy fallback and does not reject
exact-match no-op replacements. Convergence quality improves when those stronger
but unenforced rules are removed rather than treated as implicit guarantees.

## 2026-03-09 - Read tool convergence highlights shape drift risks between spec and runtime payloads

### λ Tool result shape drift is most likely around heterogeneous content fields

`execute-read` returns two different content shapes depending on file type:
- text/binary paths return `content` as string
- image paths return `content` as a vector of blocks (`{"type":"text" ...}`, `{"type":"image" ...}`)

A spec that models image output as a separate `result.image` field can look
plausible while being fully wrong at runtime. Convergence should verify the
actual payload envelope first (`content` shape + `details` presence), then
field-level semantics.

### λ Error-message paths should be specified at the same path normalization level as code

`execute-read` reports missing/binary file paths using absolute resolved paths,
not caller-provided raw path text. This is important for operator debugging and
for test determinism when cwd/path fallback logic is involved. Specs should bind
messages to `AbsolutePath(resolved)` when code emits resolved absolute paths.

### λ Guidance-string exactness is part of contract quality for interactive tools

For read pagination/truncation, wording and format of continuation hints (`---`
separator, shown-line range, `Use offset=...`) are operator-facing behavior, not
incidental formatting. Keeping these strings aligned in spec reduces drift across
REPL/RPC/Emacs/TUI clients that may depend on recognizable continuation cues.

## 2026-03-09 - Spec convergence should remove aspirational behavior, not retrofit code to stale contracts

### λ Convergence pass should target observed runtime semantics as the source of truth

For `spec/tools/bash.allium`, the runtime in `tools.clj` is final-result oriented
with optional `on_update` callback carrying snapshots, not chunk-level streaming
partials. Convergence quality improved once rules for `BashOutputChunkReceived`,
`BashPartialResult`, and speculative `spawn_hook`/`operations` adapters were
removed and replaced with behavior that actually exists at runtime.

### λ Keep option-surface parity explicit to prevent silent drift

Drift often hides in option maps. The effective parity set for bash is now:
`cwd`, `overrides`, `command_prefix`, `on_update`, `tool_call_id` (+ `timeout`
in args with default 30s). Recording this explicitly in spec avoids future
reintroduction of unsupported fields and makes review diffs immediately legible.

### λ Model abort as a separate API contract when it is not part of execute result flow

`abort-bash!` is a side-channel boolean operation over process state; it does not
currently produce an "aborted" `BashResult` payload through `execute-bash`.
Specifying abort as `AbortBashCalled() -> Boolean` matches implementation and
avoids conflating process-control API with result-shape semantics.

## 2026-03-09 - Allium v2 migration is syntax-first for large legacy specs

### λ Parse-valid structure first, then behavioral refinement

For large legacy specs (for example `spec/tui.allium`), migrating to Allium v2
is fastest and safest when done in two passes:
1) enforce parser-valid v2 forms everywhere,
2) then refine domain detail incrementally.

This prevents long error cascades from blocking progress and keeps contracts
queryable while deeper behavior is refined.

### λ High-signal v2 breakpoints are predictable

The recurring migration breakpoints were:
- missing first-line marker (`-- allium: 1`)
- `for` in surfaces (must be `facing`)
- `guidance:` (must be `@guidance`)
- `open_question` (must be `open question`)
- list literals `[...]` in expressions (replace with set literals/helper funcs)
- `for ... with ...` filters (split into `for ...:` + `requires`)
- legacy inline indexing/forms not accepted by the current grammar

A targeted rewrite against this checklist collapses most failures quickly.

### λ Helper functions are an effective migration bridge

When legacy expression constructs are no longer accepted, helper-style
operations (`ItemAt`, `LineAt`, `Append`, `Prepend`, `KeepMostRecent`, etc.)
preserve contract intent while restoring parse validity.

## 2026-03-09 - Values + drift guards should be first-class project memory

### λ Put system values in README, not only in prompts or operator lore

Values that guide architecture choices (extension-first, introspectable,
provider-agnostic, minimal built-ins) should live in repo-facing docs, not only
in agent prompt context. README placement makes them visible to humans and AI,
and reduces divergence between implementation decisions and stated intent.

## 2026-03-08 - Tool names should be canonical at registration boundaries

### λ Enforce naming policy at registration time, not during downstream execution

Rejecting invalid tool names (`^[a-z0-9][a-z0-9-]*$`) inside extension
registration creates one clear failure point. If invalid names are allowed into
registry state, every downstream surface (tool schema emission, prompt
contribution text, provider transport, UI rendering) must defensively normalize
or fail later.

### λ Kebab-case tool names avoid cross-provider edge cases and reduce escaping drift

Underscore and mixed-style naming worked locally but increased drift between tool
schema text, extension docs, and provider payload expectations. Standardizing on
kebab-case (`agent-chain`, `hello-upper`, `hello-wrap`) made names consistent
across extension registry, prompt contributions, tests, and docs.

### λ Spec + runtime guard together prevent regression

A naming rule in `spec/tools/tool-naming.allium` documents the contract; runtime
validation in `register-tool-in!` enforces it. Either one alone is weaker:
- spec-only can drift from implementation
- runtime-only lacks durable design memory

Keeping both aligned turns naming into a stable invariant.

## 2026-03-08 - Anthropic SSE usage is split across two events; hardcoded zeros are silent

### λ Anthropic streaming usage arrives in two separate SSE events — not one

`message_start` carries input-side tokens (`input_tokens`, `cache_read_input_tokens`,
`cache_creation_input_tokens`). `message_delta` carries `output_tokens`. The `:done`
event must merge both to produce a complete usage map. Treating either event in
isolation produces a partial (or zero) usage count.

### λ Hardcoded zeros in provider code produce silent, incorrect telemetry

The Anthropic provider emitted `{:input-tokens 0 :output-tokens 0 :total-tokens 0}`
unconditionally. This is syntactically valid — no error, no warning — but silently
wrong. The TUI footer received zeros, computed `context-fraction nil`, and displayed
`?/0`. The bug was invisible until the footer symptom was reported.

Pattern: when telemetry shows `?` or `0` unexpectedly, check whether the provider
layer is actually reading the API response or substituting a placeholder.

### λ Accumulate mutable state across SSE events with an atom, not a single-event read

SSE streaming is a sequence of partial events. Usage accumulation requires:
1. An atom initialized at stream start with zero values.
2. `message_start` handler: `swap!` to set input/cache tokens.
3. `message_delta` handler: `swap!` to set output tokens.
4. `:done` construction: deref atom, compute total, calculate cost.

Trying to read usage from a single event will always miss part of the data.

### λ Compare provider implementations when one is broken and one is correct

OpenAI provider correctly read usage from the final chunk (`(:usage chunk)`).
Anthropic provider hardcoded zeros. Diffing the two implementations immediately
revealed the pattern: OpenAI builds a `usage-map` from the API response; Anthropic
did not. Cross-provider comparison is a fast diagnostic for "why does X work but Y
doesn't."

### λ Add a require for `models/calculate-cost` at the same time as usage accumulation

Usage without cost is incomplete telemetry. Adding `[psi.ai.models :as models]` to
the Anthropic provider namespace and calling `(models/calculate-cost model usage)`
at `:done` time brings Anthropic cost reporting to parity with OpenAI in one commit.

---

## 2026-03-08 - Subagent profiles should be explicit tool/session inputs, not implicit prompt assumptions

### λ Normalize agent identity once at the boundary (`@name` → canonical key)

Users type `/sub @planner ...`; tool callers pass `agent: "planner"`; agent files
may declare mixed case names in frontmatter. A single normalization function
(trim, remove optional `@`, lowercase, non-empty) at ingress prevents mismatched
lookups and duplicated normalization logic across slash/tool/workflow paths.

### λ Validate optional profile references before workflow creation

`subagent create` previously accepted any task and only failed later if prompt
composition couldn't resolve profile context. Early validation (`unknown agent`)
keeps errors deterministic and avoids spawning unusable background workflows.

### λ Prompt contributions should expose both names and short descriptions

A flat list of agent names is discoverable but weak for model/tool selection.
Including frontmatter `description` in contribution content (`- planner: plan`)
improves zero-shot routing quality and reduces tool retries.

### λ Keep selected profile visible in runtime status lines

When subagents run concurrently, operator clarity depends on seeing which profile
is active per workflow (`#id ... · @planner ...`). Surfacing `agent-name` in
public workflow data and list rendering makes behavior auditable from UI/tool
surfaces without opening agent files.

---

## 2026-03-08 - All internal transcript mutations need `inhibit-read-only` — including separator refresh

### λ Every path that writes to a read-only buffer region must bind `inhibit-read-only`

`psi-emacs--refresh-input-separator-line` deletes and reinserts the separator
line — a region inside the read-only transcript — but was missing
`(inhibit-read-only t)`. The `before-change-functions` guard
(`psi-emacs--input-read-only-filter`) raised `text-read-only` on every edit
attempt, producing the user-visible `"if: Text is read-only"` error.

The fix is one binding at the mutation site:

```elisp
(let ((inhibit-read-only t)
      (bol (line-beginning-position))
      (eol (line-end-position)))
  (delete-region bol ...)
  (insert line))
```

### λ A "refresh" path is still a write path — the guard applies

The separator refresh function was added as a width-correction helper and may
have been treated mentally as a "lightweight read" operation. But any
`delete-region`/`insert` pair is a write, regardless of intent. The read-only
guard fires on all writes outside the compose area, with no exemption for
cosmetic or structural updates.

### λ One missing `inhibit-read-only` can surface far from where it was added

The missing binding in `psi-emacs--refresh-input-separator-line` was only
triggered on re-entry to an existing buffer (not fresh open), because only then
does `psi-emacs--ensure-input-area` take the "separator valid, needs refresh"
branch. The error appeared as a consequence of the prior fix (7b63628), which
made re-entry reach this path for the first time.

Pattern: when a guard-based error appears after a refactor that changes which
code paths are taken, look for existing mutation sites that were previously
unreachable and check each one for the required binding.

### λ Audit all internal mutation sites when adding a new read-only invariant

When `psi-emacs-mode` dropped its global `inhibit-read-only` setting (commit
`0c6667f`), the contract became: every internal write must carry its own
binding. New mutation functions added after that point should be audited at
review time. A checklist grep for `delete-region\|insert\|replace-regexp` in
the file, filtered for missing `inhibit-read-only`, would have caught this.

---

## 2026-03-08 - `psi-emacs-open-buffer` must be idempotent for live buffers

### λ Unconditional mode activation resets buffer-local state on every call

`psi-emacs-open-buffer` called `(text-mode)` unconditionally before checking
for `psi-emacs-mode`. Calling a major mode function clears buffer-local variables,
wiping `psi-emacs--state`. This caused the `psi-emacs--state` nil branch to fire,
which called `psi-emacs--start-rpc-client`, triggering transcript hydration and
re-running all startup prompts — every time `psi-emacs-project` was invoked on an
already-live buffer.

### λ Guard mode setup with `derived-mode-p` to make open-buffer idempotent

The correct pattern: check first, activate only if needed.

```elisp
(unless (derived-mode-p 'psi-emacs-mode)
  (let ((mode (psi-emacs--preferred-major-mode)))
    (funcall mode))
  (psi-emacs-mode))
```

When the buffer is already in `psi-emacs-mode`, skip all mode setup. The
existing-state branch then handles the live-buffer case cleanly (RPC client
recovery only if process died), and `pop-to-buffer` focuses the buffer.

### λ The existing-state guard in open-buffer is correct — the mode setup was the bug

`psi-emacs-open-buffer` already handled the alive vs. dead process distinction
correctly inside the `(if psi-emacs--state ...)` branch. The bug was upstream:
mode activation wiped `psi-emacs--state` before that branch was reached, so the
alive-process path was never taken for existing buffers.

### λ Trace re-runs to their cause before patching the symptom

The visible symptom was "prompts re-run on buffer switch." The natural patch
target might have been `psi-emacs-project` or the hydration logic — but the root
cause was `(text-mode)` clearing buffer-local state. Tracing the call chain
(`psi-emacs-project` → `psi-emacs-open-buffer` → `(funcall mode)` → `text-mode`
clears locals → `psi-emacs--state` nil → full init) identified the real fix site.

---

## 2026-03-08 - Emacs transcript read-only boundaries must be local, not mode-global

### λ Never set `inhibit-read-only` as a mode-local default

Setting `setq-local inhibit-read-only t` in `psi-emacs-mode` leaked broad
read-only bypass semantics into interactive flows and conflicted with unrelated
hook-driven buffer updates (notably LSP `*lsp-log*` writes on post-command).

Correct pattern: keep mode defaults conservative and use explicit local
bindings only at known internal mutation points.

### λ Input guards must honor intentional internal writes

`before-change-functions` guards that enforce input-only editing should short-
circuit when `inhibit-read-only` is non-nil. This preserves strong user-facing
boundaries while allowing deterministic internal transcript/render updates.

### λ Read-only transcript regions require paired write windows

Once transcript/error/thinking/replay regions are marked read-only, every
programmatic mutation path must wrap edits and property changes in
`(let ((inhibit-read-only t)) ...)`. Missing even one path causes sporadic
`text-read-only` failures in runtime or tests.

### λ Tests must model read-only transcript semantics explicitly

ERT tests that clear or rewrite whole buffers after transcript rendering must
bind `inhibit-read-only` around `erase-buffer`. Previously-valid test helpers
that assumed writable buffers become invalid once transcript immutability is
enforced.

### λ Separator-marker validity should verify anchor invariants, not only glyph

Checking only the separator character at marker position can miss drift when
edits insert text at the same point. Requiring both line-start anchoring and
separator glyph yields reliable detection/repair behavior.


## 2026-03-08 - Emacs `defcustom` vs `.dir-locals.el` safety are separate contracts

### λ `defcustom` does not imply safe local variable

A variable defined with `defcustom` is customizable via Customize, but Emacs
still warns in `.dir-locals.el` unless the variable is marked safe for local
assignment (`:safe` or safe-local-variable metadata).

### λ Mark command vectors safe with a shape predicate

For process command settings like `psi-emacs-command`, the safe contract is
"list of strings". Encoding that directly in the variable declaration keeps the
policy explicit and sharable across repositories:

```elisp
:safe (lambda (value)
        (and (listp value)
             (cl-every #'stringp value)))
```

This allows project-level overrides in `.dir-locals.el` without per-user prompt
acceptance and without weakening safety to arbitrary forms.

### λ Prefer variable-level safety over per-value user allowlists

Adding exact entries to `safe-local-variable-values` works locally but does not
travel with the codebase. Marking the variable itself with a structural
predicate gives consistent team behavior and keeps trust rules versioned.


## 2026-03-07 - Extension messages during bootstrap corrupt LLM history

### λ `send-message!` during `init` appends to LLM history before any user turn

`send-extension-message-in!` calls `agent/append-message-in!` unconditionally.
When an extension calls it inside `init`, extensions load during
`bootstrap-session-in!` — before `startup-bootstrap-completed?` is set true.
The resulting assistant message sits at position 0 in history, and the UI shows
it prepended to the first user prompt.

### λ Guard history append behind `startup-bootstrap-completed?`

The session data atom already carries this flag. Reading it in
`send-extension-message-in!` is cheap and correct:

```clojure
(let [bootstrap-complete? (boolean (:startup-bootstrap-completed?
                                     (get-session-data-in ctx)))]
  (when bootstrap-complete?
    (agent/append-message-in! (:agent-ctx ctx) msg)
    (agent/emit-in! ...)))
```

Messages sent during bootstrap still reach the event queue (UI notification),
but never corrupt the conversation context the LLM will see.

### λ Don't silence extension events — redirect them

Dropping bootstrap messages entirely would make `send-message!` silently fail
from an extension's perspective. The correct fix preserves the event-queue path
(UI notification) while blocking the history-append path. Extensions that call
`send-message!` during `init` get UI visibility; they just don't pollute history.

### λ Remove noise at the source AND at the infrastructure layer

Two-layer fix:
1. Infrastructure: `send-extension-message-in!` guards against bootstrap-time
   history mutation (robust to any extension).
2. PSL: remove the `send-message! "PSL extension loaded."` call — it served no
   operator purpose and was the only known caller of this anti-pattern at init time.

Both layers are needed: the guard protects against future extensions; the removal
eliminates the known trigger.

### λ `startup-bootstrap-completed?` is the right gate — not `idle?`

The session is already `:idle` (statechart) at the time extensions run, so
`idle-in?` would return `true` and would not gate anything. The semantic question
is "has startup finished?" — which is exactly what `startup-bootstrap-completed?`
tracks. Use the right predicate for the right question.

---

## 2026-03-07 - Extension run-fn needs emit-frame! to be visible to RPC clients

### λ The default extension run-fn has no progress-queue — its output is invisible

`register-extension-run-fn-in!` in `main.clj` creates a run-fn that calls
`run-agent-loop-in!` without a `progress-queue`. Streaming deltas go only to
`agent-core events-atom`. The RPC `run-prompt-async!` polls its own `progress-q`
(created locally, not shared), so the extension run's events never reach it.
Result: PSL response streams, tools, and final `assistant/message` are all
silently dropped from the RPC client's perspective.

### λ Re-register the run-fn from the RPC subscribe handler with emit-frame! closure

The fix pattern:
1. At `subscribe` time (when `emit-frame!` is available), replace the run-fn.
2. New run-fn creates a fresh `progress-queue` per invocation.
3. A background future polls the queue → `progress-event->rpc-event` → `emit-frame!`.
4. After `run-agent-loop-in!` returns, stop the poller, flush remaining events,
   emit `assistant/message` + `session/updated` + `footer/updated`.

This mirrors exactly what `run-prompt-async!` does for normal user prompts.

### λ Guard one-time registration with a state flag

`subscribe` may be called multiple times. Use a `:rpc-run-fn-registered` flag in
the RPC `state` atom to ensure the run-fn is only replaced once per connection.
Without the guard, each subscribe would create a new closure over a stale
`emit-frame!` or overwrite a valid one.

### λ Two layers set the extension run-fn — the RPC layer must win

`main.clj` sets a baseline run-fn (no streaming) for CLI/REPL mode.
`rpc.clj` must replace it after `subscribe` with a streaming-aware version.
The resolution order is: `main.clj` bootstrap → `rpc.clj` subscribe override.
Any code path that needs streaming must be the last writer.

### λ Extension-initiated and user-initiated runs must share the same emission contract

A user prompt in `run-prompt-async!` emits: deltas → `assistant/message` →
`session/updated` → `footer/updated`. Extension runs must emit the same sequence
or frontends get stuck in a stale streaming/idle state.

---

## 2026-03-07 - Extension output: prefer outcome messages over internal state messages

### λ Status messages should describe what the user cares about, not internal delivery state

Prior PSL messages surfaced internal jargon (`"PSL prompt queued via deferred."`,
`"PSL sync start for abc1234."`, `"PSL skipped for abc1234 (self commit marker)."`)
that reflects implementation details, not operator outcomes.

Cleaner contract:
- success → `"Updating PLAN.md, STATE.md and LEARNING.md …"` (what is happening)
- failure → `"Failed to update PLAN.md, STATE.md and LEARNING.md"` (what went wrong)
- skip (self-commit) → silent (no message needed; the commit marker explains itself)

### λ Silent skip is cleaner than a "skipped" message

A self-commit skip message creates noise in the transcript for every PSL-auto
commit. Since the skip reason is encoded in the commit subject (`[psi:psl-auto]`),
no additional message is needed — silence is the correct output.

### λ Remove intermediate progress messages when the final outcome message is sufficient

`"PSL sync start for …"` was emitted before `send-prompt!` then superseded by
the accepted/rejected message. Two messages for one logical operation creates
chattiness. A single outcome message after the operation is complete is cleaner.

---

## 2026-03-07 - Chain result delivery: close the loop back to the session

### λ Background workflow output must be routed back to the session explicitly

`run_chain` runs the chain in a background statechart workflow. Without an
explicit delivery step, the chain output exists only in workflow state and never
reaches the operator. `emit-chain-result!` closes this gap: `done-script` and
`error-script` call the `on-finished` callback, which delivers a formatted
assistant message (`custom-type: "chain-result"`) via `mutate-fn`.

### λ `on-finished` belongs at registration time, not inside statechart data

Wiring the callback at `register-chain-workflow-type!` time (closure over the
extension state atom) keeps statechart data free of function references and
makes the delivery contract explicit at the boundary between workflow-runtime and
extension-API layers.

### λ Deliver after a tick, not synchronously in the script

A 30ms `future` sleep before `emit-chain-result!` lets the statechart finish its
transition (assign ops, final-state entry) before the delivery side-effect fires.
Delivering synchronously inside a `done-script` can race with state finalization.

### λ Model resolution needs a priority chain, not a single fallback

`run-chain-workflow-job` needs a model but may be invoked without one in the
input. The correct resolution order is:
1. explicit model from workflow input (operator override)
2. session model from `query-fn` (active session preference)
3. hard coded safe default (`:sonnet-4.6`)

Missing step 2 meant chains always fell back to the hard default even when a
session model was configured.

### λ Catch `Throwable` in execution boundaries, not just `Exception`

JVM `Error` subclasses (e.g. `AssertionError`, `StackOverflowError`) are not
`Exception` descendants. Step execution code that only catches `Exception` lets
errors propagate unchecked and produce opaque workflow failures. Broadening to
`Throwable` ensures the step produces a structured error result regardless of
thrown type.

---

## 2026-03-07 - tool_use.input must always be a JSON object

### λ json/parse-string does not guarantee a map — validate before use as tool input

`json/parse-string` returns whatever the JSON top-level value is: map, vector,
string, number, boolean, or nil. When a LLM emits tool arguments whose JSON is
not an object (rare but possible — e.g. a string literal or array), the raw
non-map flows through to `tool_use.input`, producing Anthropic 400:
`Input should be a valid dictionary`.

Fix: validate after parse.
```clojure
(let [parsed (json/parse-string arguments)]
  (if (map? parsed) parsed {}))
```

### λ Guard at both the parse layer and the wire layer (belt-and-suspenders)

Two defence points cover independent failure paths:

1. **`parse-args` (executor.clj)**: catches non-map values from raw JSON parsing.
2. **`transform-messages` (anthropic.clj)**: catches non-map `:input` values that
   might arrive via other conversation reconstruction paths.

Neither guard alone is sufficient — conversation can be rebuilt from persisted
history where the `:input` field was already set to a non-map before the parse-args
fix existed.

### λ Validated API error messages enable immediate root-cause identification

The prior session's 400 was diagnosed in one step once error body decoding was
in place (commit `4ffaa11`). The decoded message
`messages.19.content.1.tool_use.input: Input should be a valid dictionary`
identified the exact field and constraint without any request logging or
reproduction effort. Error body decoding pays for itself immediately.

---

## 2026-03-07 - Anthropic API error diagnosis: always decode the error body

### λ clj-http 400 exceptions carry the error body as a stream in ex-data, not in the message

When Anthropic returns a 400, clj-http throws an `ExceptionInfo` with the full
response map in `ex-data`. The useful error text is in `:body` — a
`GZIPInputStream` that must be explicitly read. Stringifying the exception only
yields the status code and response metadata, not the API error message.

Pattern:
```clojure
(catch Exception e
  (let [body-stream (:body (ex-data e))
        api-error   (when (instance? java.io.InputStream body-stream)
                      (get-in (json/parse-string (slurp (io/reader body-stream)) true)
                              [:error :message]))]
    ...))
```

### λ Opaque HTTP errors in agent chains are likely invalid model IDs

A chain run 400 that shows only `"clj-http: status 400 {...}"` almost always
means the model ID sent to the API doesn't exist. Common cause: the fallback
model in `run-chain-workflow-job` (e.g. `:sonnet-4.6` → `"claude-sonnet-4-6"`)
is a speculative/future model name not yet live on Anthropic's API. Decoding the
body confirms this immediately.

### λ Decode before you debug: surface the API message first

Opaque HTTP error strings cause unnecessary guesswork. Always surface the
provider's error message text as the `:error-message` before any other
diagnosis. The fix is one layer — provider catch block — and benefits all
callers (chain runs, direct session turns, sub-agents).

---

## 2026-03-07 - PSL ordering: event handler vs workflow job

### λ Extension event handlers fire during/after the triggering turn — not after it

`git_head_changed` is dispatched while or just after the commit agent turn is
running. Any `send-message!` or `send-prompt!` called synchronously in the handler
lands in the transcript before that turn's output is flushed, creating visible
ordering inversions (PSL header before commit summary).

### λ Move side-effects that must follow a turn into a workflow job

The statechart `:future` invoke runs in a background thread and calls
`send-prompt!` through the normal deferred path. The deferred runner waits for
the session to go idle before executing — guaranteeing the PSL prompt fires
after the triggering turn completes.

Pattern:
```
event handler → fast check only → workflow/create
workflow job (future) → send-message + send-prompt (deferred)
```

### λ Skip-check belongs in the handler, not the job

The self-commit skip (`[psi:psl-auto]` marker) is a fast git-log read.
Keep it in the handler to avoid creating a workflow at all for self-commits,
while keeping all transcript side-effects in the job.

---

## 2026-03-07 - Agent-chain discoverability + completion parity

### λ Workflow discoverability should include configured chain catalog, not only runtime tools

Exposing only `run_chain`/`chain-*` commands made capability presence discoverable,
but not the available chain definitions themselves. Adding top-level query attrs for
chain config (`:psi.agent-chain/*`) closes this gap and lets frontends/agents inspect
configured flows without parsing files directly.

### λ Long-running tool flows should default to non-blocking unless synchronous output is required

For UI-driven sessions, default blocking behavior in `run_chain` can stall the active
request path even when the underlying workflow is asynchronous. Defaulting to
background start, with explicit opt-in wait (`wait=true`), preserves responsiveness
while still allowing synchronous callers when needed.

### λ Interactive tool-call execution should ignore synchronous wait hints for workflow-backed tools

Even with `wait=true` available for programmatic callers, interactive tool-call
contexts (where `on-update` streaming is active) should remain non-blocking to avoid
UI lockups. Commit `11feddf` codifies this by forcing background start for
interactive `run_chain` requests and surfacing an explicit note when `wait=true`
is ignored.

### λ Completion sources must be backend-driven to keep extension UX in sync

Static slash command completion drifts as extensions change. Pulling
`:psi.extension/command-names` into completion state (Emacs CAPF + TUI autocomplete)
keeps command discovery aligned with live extension registration.

## 2026-03-08 - Runtime UI type should be first-class context for extension rendering

### λ Expose UI surface once, consume everywhere

Extensions need to branch rendering/interaction policy by runtime surface
(console, TUI, Emacs). Pulling this from ad-hoc heuristics in each extension
creates drift and hidden coupling. A single session attr + API projection is
cleaner:
- canonical session attr: `:psi.agent-session/ui-type`
- extension API field: `:ui-type`

This gives both query-path introspection and zero-roundtrip runtime access.

### λ Seed UI type at bootstrap boundary, not inside extensions

The right place to determine UI type is runtime bootstrap (`run-session`,
`run-tui-session`, rpc-edn/Emacs handshake path), not extension init.
Extensions should consume a resolved value, not infer transport from available
APIs.

### λ Handshake metadata is part of UI identity contract

For rpc-edn clients, backend/UI coupling should be explicit in handshake
server-info. Including `:ui-type` in handshake payload lets frontends and
debug tooling verify negotiated runtime surface early, before any prompt run.

### λ Widget placement policy should be deterministic per UI surface

A simple policy (`:emacs -> :below-editor`, otherwise `:above-editor`) avoids
extension-specific divergence and keeps projection order predictable across
surfaces.

### λ Nullable extension fixtures should model ui-type explicitly

If test fixtures don’t carry `:ui-type`, extension behavior silently defaults
and UI-branching logic goes untested. Extending nullable API helpers with
`:ui-type` makes extension tests deterministic and catches rendering regressions.

---

## 2026-03-08 - Prompt contributions should carry the catalog, not just the capability name

### λ A tool schema tells the model what arguments to supply; a prompt contribution tells it what values are valid

`agent-chain` tool schema advertises `action`, `chain`, and `task` parameters.
But the schema alone cannot enumerate valid `chain` values — those come from
`.psi/agents/agent-chain.edn` at runtime. Without the catalog in the system
prompt, the model must call `action="list"` as a discovery step before every
`action="run"` call. That is an avoidable round-trip.

The prompt contribution closes this gap:
```
tool: agent-chain
available chains:
- plan-build-review: Plan, build, and review code changes
- prompt-build: Build prompts
```

The model can now select the correct chain name directly from context.

### λ Contribution content is runtime state — it must be kept in sync with the underlying data

Registering a static contribution at init is insufficient if the underlying
data can change. `agent-chain` chains can be reloaded via `action="reload"` or
on session switch. Every path that mutates chain definitions must call
`sync-prompt-contribution!` immediately after. Missing one update path leaves
the model working from a stale catalog.

Sync points for agent-chain:
1. `init` — initial registration
2. `action-reload` — after disk rescan
3. `session_switch` event — after session-scoped rescan

### λ Priority and section placement are part of the contribution contract

`priority=200` places agent-chain below subagent-widget (`priority=250`) in
the `Extension Capabilities` section. Lower number = higher in the composed
system prompt. For peer tools in the same section, ordering by priority keeps
the prompt layout deterministic and reviewable.

### λ The subagent-widget pattern is the established template — follow it exactly

`subagent_widget.clj` established: `prompt-contribution-id` constant →
`prompt-contribution-content` fn → `sync-prompt-contribution!` fn →
`register-prompt-contribution!` fn → wire into init + refresh paths.

Deviating from this structure (e.g. inlining content generation, skipping the
sync fn, calling register directly from multiple sites) creates inconsistency
and makes future audits harder. The pattern is the contract.

---

## 2026-03-08 - Tool APIs should carry their context as arguments, not as pre-selected global state

### λ A tool that depends on prior side-effect selection is fragile and context-blind

`run_chain` required the operator (or agent) to first call `/chain <name>` to set
`active-chain`, then call `run_chain(task=...)`. Two separate steps, one of which
is a global mutation. If the session is switched, reloaded, or the agent forgets to
select, the tool silently runs against the wrong chain or returns an error.

Replace: `active-chain` atom + pre-selection requirement
With: `agent-chain(action="run", chain="<name>", task="...")`

The chain is resolved by name at call time from the loaded chains. No state to
pre-configure, no selection to remember.

### λ Named lookup is more robust than indexed or globally-selected state

A chain name is stable and self-describing across sessions. An index or a pre-selected
atom is fragile: it drifts on reload, session-switch, or agent context loss.
`resolve-chain` does a case-insensitive name lookup on each call — cheap and correct.

### λ Consolidate related tool actions into one tool with an `action` parameter

Before: three separate surfaces for chain interaction — `run_chain` tool, `/chain-list`
command, `/chain-reload` command. The agent had to know about all three.

After: one tool, three actions:
- `agent-chain(action="run", chain=..., task=...)` — run
- `agent-chain(action="list")` — inspect
- `agent-chain(action="reload")` — reset

A single tool with an enum `action` field is discoverable from the tool schema alone.
The agent can introspect all available operations without needing knowledge of the
slash command surface.

### λ Human-facing slash commands can remain as thin aliases without cost

`/chain` and `/chain-reload` still exist and delegate to `action-list` /
`action-reload` respectively. They cost one line each in `init`. Humans keep the
ergonomic shortcut; the agent uses the tool. Both surfaces stay in sync because
they share the same implementation functions.

### λ Removing global state from an extension simplifies lifecycle management

`active-chain` atom had to be reset in three places: `init`, `chain-reload` handler,
and `session_switch` handler. Removing it removes all three reset sites and the
widget code that displayed it. Less state → fewer reset paths → fewer bugs.

### λ Widget display should reflect actual dynamic state, not configured selection

`active: (none)` / `active: <name>` was a display of pre-selection state, not of
what was running. The widget now shows only run history — a reflection of actual
activity, not operator intent that may or may not have been acted on.

---

## 2026-03-07 - Chain selection UX should accept operator intent directly

### λ Name-first selection is a natural operator behavior

After removing implicit default activation, operators often try `/chain <name>`
(e.g. `/chain prompt-build`) rather than remembering numeric indexes.
Treating name-based selection as first-class keeps command ergonomics aligned
with chain labels shown in `chain-list` and widget output.

### λ Explicit selection and no-default-active are complementary

`active: (none)` on init/reload/session-switch preserves intent boundaries.
Supporting `/chain <number|name>` adds convenience without reintroducing hidden
activation behavior.

## 2026-03-07 - Agent-chain default selection should be explicit, not implicit

### λ Defaulting to first chain on load hides operator intent

Auto-selecting the first configured chain on init/reload/session-switch makes the
widget appear "active" before the user has made any chain choice. This creates
implicit behavior and can trigger accidental runs against the wrong chain.

### λ Keep active-chain nil across lifecycle resets until explicit `/chain <number>`

Reset paths should converge on one rule: `active-chain = nil` unless user selects
one. This keeps init/reload/session-switch behavior consistent and makes the widget
state truthful (`active: (none)`).

### λ Regression tests should assert UI state, not just command registration

The no-default-active contract is best pinned by reading rendered widget lines and
asserting `active: (none)` after extension init with configured chains.

## 2026-03-07 - Agent-chain run progress should heartbeat independently of step transitions

### λ Long-running workflow waits need heartbeat updates, not change-only updates

When `run_chain` waits on extension workflow completion, purely change-driven tool
updates can go silent during long steps. Emitting throttled heartbeat progress
updates (time-based) keeps frontend tool output alive and prevents "stalled"
perception even when the active step has not changed.

### λ Run-state tracking should be first-class extension state for UI projection

Maintaining explicit per-run tracked state (`phase`, `step-index`, `step-agent`,
`elapsed-ms`, `last-work`) enables deterministic status projection to both tool
updates and extension widgets, instead of reconstructing status ad hoc from
workflow snapshots.

### λ Widget projection should refresh on lifecycle boundaries, not only on run completion

For extension UI parity, widget refresh needs to occur on init/reload/session-switch
as well as run updates. Deterministic refresh hooks prevent stale "active runs"
views when chain definitions or sessions change.

## 2026-03-07 - Emacs project startup command: prefix semantics should be explicit and test-anchored

### λ `C-u` and `C-u N` need distinct buffer lifecycle semantics

For project-scoped command UX, plain universal arg (`C-u`) should mean
"fresh generated name" while numeric universal arg (`C-u N`) should mean
"deterministic slot selection." Encoding this split keeps behavior predictable:
- no prefix -> canonical `*psi:<project>*`
- `C-u` -> fresh generated from project base
- `C-u N` -> slot name `*psi:<project>*<N>` (with `N<=1` collapsing to canonical)

### λ Reuse helper names carefully in split Emacs modules

`psi-emacs--project-root-directory` already existed in tool rendering helpers.
Adding entry-point logic with the same symbol changed unrelated runtime behavior
(project-relative tool-path summaries). Using a dedicated entry helper
(`psi-emacs--entry-project-root-directory`) preserves module boundaries and
prevents cross-feature regressions.

### λ New interactive command contracts should ship with behavior-level ERT tests

Project command work stayed stable after adding tests that assert:
- canonical-buffer reuse
- fresh-buffer creation via `C-u`
- numeric slot behavior via `C-u N`
- project-root absence error path

This test set prevents regressions in command semantics and startup cwd behavior.

## 2026-03-08 - Emacs submit path should enforce separator invariants

### λ Submit lifecycle should reassert input-area boundary immediately

Even with resize/window-change repair hooks, the separator can still appear to
vanish specifically on prompt submission if marker validity drifts during send
and no immediate repair runs. A reliable fix is to enforce the invariant in the
submit path itself: after successful dispatch + input consumption, call
`psi-emacs--ensure-input-area`.

### λ Invariant location matters more than incidental refresh triggers

Repair logic tied only to window changes or projection refresh is opportunistic.
Separator correctness is a compose/send invariant, so it should be guaranteed in
`psi-emacs--consume-dispatched-input`, not left to later unrelated events.

### λ Add a behavior-level regression test for submit-cycle separator resilience

A focused ERT (`psi-send-repairs-missing-input-separator-after-submit`) pins the
contract that submit keeps or repairs a valid input separator marker and preserves
expected transcript/input behavior. This prevents future regressions from submit
flow refactors.

## 2026-03-07 - Emacs separator width parity needs both width-source correctness and marker repair

### λ Refresh-on-resize alone is insufficient when separator markers drift

`db9d4c7` added window-configuration refresh hooks, which helped footer updates,
but user feedback showed the pre-edit separator could still fail to resize. Root cause:
if the input separator marker is present but stale/misaligned, narrow "refresh-if-valid"
logic can skip repair. Calling `psi-emacs--ensure-input-area` on window changes gives
idempotent repair + width refresh.

### λ `window-text-width` preference alone may not guarantee parity across all separators

Switching projection width logic to prefer `window-text-width` improves one class of
mismatch, but user-observed layouts can still show unequal lines when different separator
paths are inserted/refreshed under slightly different boundary assumptions.

Practical implication: treat separator parity as a multi-path invariant (projection/footer
separator and pre-edit/input separator must derive width from the same effective context)
rather than assuming one width helper change resolves all rendered lines.

### λ Use visible text width (`window-text-width`) as first-choice for separator sizing

Margin/body arithmetic can still overestimate in real layouts. For separator lines that
must match the editable text column, prefer `window-text-width`, with margin-based fallback
for compatibility/test contexts.

### λ Confirm reported UI surface before fixing width/render bugs

A divider-length report from Emacs UI was initially patched in TUI (`3e02b97`),
which improved TUI separator sizing but did not address the user-visible Emacs
issue. Separator/render bugs are frontend-specific in this repo:

- TUI separators: `components/tui/src/psi/tui/app.clj`
- Emacs projection/input separators: `components/emacs-ui/*`

Before patching, pin the failing surface (Emacs vs TUI) from the transcript/screenshot,
then validate in that path's tests/runtime loop.

## 2026-03-06 - OpenAI thinking parity needs provider + executor + UI alignment

### λ Thinking visibility is a pipeline contract, not a single parser fix

`fbbb173` confirmed that restoring OpenAI reasoning requires end-to-end alignment:

1. provider extracts reasoning deltas from real chat-completions chunk shapes,
2. executor preserves/emits canonical `:thinking-delta` events,
3. UI layer (TUI) renders thinking deltas distinctly from assistant text.

If any layer is missing, reasoning may exist in payloads but remain invisible to users.

### λ Stream parity is an explicit acceptance target

When adding/fixing provider streaming features, verify parity across active frontends
(not just provider/unit tests): transport event shape, session event mapping, and final UI
rendering must all agree on the same semantic channel (`thinking` vs `text`).

## 2026-03-06 - OpenAI chat-completions thinking required both request + parser fixes

### λ OpenAI reasoning visibility is two-part: request intent + stream parsing

For `:openai-completions` models, no visible thinking can come from either:

1. request side missing reasoning intent (`reasoning_effort` not sent), or
2. response side parser too narrow for real delta shapes.

Both must be correct to get stable `:thinking-delta` output.

### λ Delta schemas drift; parse by shape families, not one path

OpenAI reasoning arrived in multiple observed forms, not one canonical field:
- `delta.reasoning_content`
- `delta.reasoning` as map/string
- `delta.reasoning` as vector of typed parts
- reasoning parts inside `delta.content` vector

A parser that only checks one field silently drops thinking output. Robust
extractors should normalize across shape families and then emit one internal
signal (`:thinking-delta`).

### λ Keep usage maps normalized before cost calculation

Completion usage payloads can omit cache token fields. Cost functions should
receive a normalized map (`input/output/cache-read/cache-write/total`) to avoid
nil arithmetic failures and preserve deterministic terminal events.

## 2026-03-06 - Anthropic extended thinking: two independent bugs

### λ Thinking text leaked into main stream (provider bug)

Anthropic's extended thinking emits `content_block_start` with `type: "thinking"`,
then `content_block_delta` with `delta.type: "thinking_delta"` and `delta.thinking`.

The provider only tracked `"tool_use"` vs everything-else in `block-types`, so
thinking block deltas fell through to the `:text-delta` branch — thinking text
appeared inline in the main response, not as a separate thinking signal.

**Fix**: track `"thinking"` as a distinct block type; route `delta.thinking` →
`:thinking-delta`. Also: add `thinking` param + `interleaved-thinking-2025-05-14`
beta header to requests when `thinking-level` is non-`:off`; suppress `temperature`
(incompatible with extended thinking per Anthropic API).

```clojure
;; content_block_delta routing (anthropic.clj)
(case btype
  "tool_use"  (emit :toolcall-delta ...)
  "thinking"  (emit :thinking-delta {:delta (:thinking delta)})
  ;; default: "text" + unknown
              (emit :text-delta {:delta (:text delta)}))
```

### λ Emacs thinking render: snapshot-merge heuristic wrong for incremental deltas

`psi-emacs--merge-assistant-stream-text` detects cumulative snapshots vs
incremental deltas using a common-prefix heuristic. This is correct for the
main text stream (RPC can send either style), but **thinking deltas are always
incremental** — each event is a small new chunk, never a growing snapshot.

The heuristic misfired on short/repeated chunks, triggering `concat` on what
it thought were deltas when they were actually misidentified, producing
ever-growing repeated lines.

**Fix**: `psi-emacs--assistant-thinking-delta` uses direct `concat` append,
bypassing the merge heuristic entirely. The main text path (`psi-emacs--assistant-delta`)
retains the heuristic — it is still needed there.

```elisp
;; pure append — thinking deltas are always incremental
(let ((next (concat (or (psi-emacs-state-thinking-in-progress psi-emacs--state) "")
                    (or text ""))))
  ...)
```

### λ Two streams, two contracts — keep merge strategies separate

- Main text stream: may be cumulative snapshot OR incremental delta → use merge heuristic
- Thinking stream: always incremental delta → use pure append
- Tool input stream: always incremental JSON delta → use pure append (already correct)

Don't unify what has different contracts.

## 2026-03-06 - Step 11 startup-prompts completion reclassification

### λ Verify before planning: implementation/tests outrank stale plan text

When `PLAN.md` says "in progress" but code paths, resolver surfaces, and tests
say "done", treat plan/state docs as stale memory and reclassify from observed
runtime+repo truth.

### λ Startup prompts contract is now closed in implementation

Step 11 is complete when all of these are present together:
- config discovery from `~/.psi/agent/startup-prompts.edn` + `.psi/startup-prompts.edn`
- deterministic merge/order (`global < project`)
- startup execution as visible transcript turns during new-session bootstrap
- top-level EQL startup telemetry attrs + graph discoverability
- explicit fork/new-session behavior with tests

### λ Keep STATE/PLAN/Open Questions synchronized with closure

After reclassification, remove resolved startup-prompt open questions and move
"next executable" focus to the next real frontier (currently Step 12 Emacs UI
stabilization).

## 2026-03-06 - Emacs CAPF completion architecture for `/` and `@`

### λ Standard Emacs completion architecture fits prompt input cleanly

A single CAPF dispatcher (`completion-at-point-functions`) can route by token
context:
- `/...` → slash command table
- `@...` → file-reference table

Returning `nil` outside these contexts preserves normal CAPF composition with
other completion sources.

### λ Category metadata makes completion UI integration predictable

Using explicit categories (`psi_prompt`, `psi_reference`) plus annotation /
affixation metadata enables consistent behavior across default
`completion-in-region`, Corfu, and company-capf bridges.

### λ Reference completion needs project-root fallback + configurable policy

`@` completion quality improved when candidate search includes both cwd and
project-root (when distinct), with operator-tunable knobs:
- candidate limits
- match style (`substring` / `prefix`)
- include hidden paths toggle
- excluded path prefixes (default excludes `.git`)

### λ Exit hooks are useful for deterministic compose ergonomics

A CAPF `:exit-function` can normalize accepted references (for example,
appending a trailing space after file candidates while preserving directory
continuation behavior).

## 2026-03-05 - Memory Boundary Clarification (session vs persistent vs git)

### λ Treat memory as two stores plus one query surface

The system should model memory as:
- session memory (short-term, ephemeral, high-churn working context)
- persistent memory (cross-session, distilled and reusable artifacts)
- git history (queryable provenance, not stored as memory artifacts)

### λ Do not duplicate git history into persistent memory

Git commit/log/diff data is already canonical and queryable through history
resolvers. Storing mirrored git summaries in memory introduces drift and
maintenance overhead without adding new capability.

### λ Persist only non-derivable, action-improving knowledge

A practical filter for persistent memory:
- keep: stable operator preferences, validated conventions, distilled facts
- drop: temporary turn context, unresolved scratch notes, git-derivable facts

### λ Session persistence for `/resume` is distinct from memory-store semantics

Persisted session transcript/state supports operational continuity (resume),
while remember/recover memory artifacts support cross-session distillation and
retrieval. These should remain separate contracts even if both are disk-backed.

## 2026-03-05 - Session Startup Prompt Spec Decisions (Step 11 planning)

### λ Visible startup behavior should use transcript turns, not hidden system-prompt concatenation

If startup prompts must be visible to UI (and persisted as session history), they
should execute as normal startup-tagged user messages with normal agent responses.
This preserves observability and avoids hidden initialization state.

### λ Keep startup prompt source layering minimal at first

Global + project sources with precedence `global < project` are enough for v1.
Removing session overrides reduces hidden behavior and conflict complexity.

### λ Discoverability is a first-class contract

Startup prompt telemetry attrs should be top-level `:psi.agent-session/*`
resolvers and must appear in graph introspection (`:psi.graph/resolver-syms`,
`:psi.graph/nodes`, `:psi.graph/edges`, `:psi.graph/capabilities`,
`:psi.graph/domain-coverage`).

## 2026-03-05 - Memory Durability Telemetry Surface (Step 9.5 completion)

### λ Provider failure telemetry must be registry-level, not provider-specific

Tracking write/read/failure counters and last-error in the store registry
(all providers, same shape) keeps introspection uniform and avoids coupling
operator diagnostics to one provider implementation.

### λ Fallback is only half the story; operators need causal breadcrumbs

`:psi.memory.store/selection` tells *what* was selected, but not *why* a
provider failed. Surfacing `:psi.memory.store/last-failure` + per-provider
`:telemetry` (failure count + last error payload) closes that gap for runtime
triage.

### λ Retention/migration docs reduce ambiguity during runtime upgrades

Operational docs should pair config knobs (`--memory-retention-*`,
`PSI_MEMORY_*`) with migration-hook wiring examples so version upgrades are
observable and repeatable.

## 2026-03-03 - Memory Runtime Hardening (Step 9.5 initial)

### λ Boolean config parsing must not use plain `or`

`or` treats `false` as missing. For runtime flags like fallback policy,
use explicit `some?` precedence (`explicit -> env -> default`) so
`false` survives and does not collapse to default `true`.

### λ Retention limits should live in state, not constants only

`capture-graph-change-in!` now reads retention from memory state
(`:retention {:snapshots ... :deltas ...}`), so runtime config can change
compaction windows without code changes.

### λ Datalevin schema migration needs explicit hook boundaries

Version upgrades are now guarded by schema-version metadata in the DB.
If runtime target version is higher, each step requires a hook; missing
hooks fail open with clear health/error state instead of silent drift.

## 2026-03-05 - Datalevin Store Integration (Step 9a Phase 2)

### λ Write-through + hydration is a low-risk bridge to persistent memory

Keeping remember/recover ranking logic in `psi.memory.core` while adding
provider write-through (`remember`, `recover`, graph snapshot/delta) and
activation-time hydration from provider state gives persistence without
rewriting recovery semantics.

### λ Remember command tests should pin memory readiness explicitly

`/remember` command readiness uses live `:psi.memory/status` via EQL.
Tests that rely on implicit global memory state can become order-dependent.
Attach explicit per-test memory contexts (`with-ready-memory-ctx` /
`with-unready-memory-ctx`) to keep command tests deterministic.

## 2026-03-03 - Memory Store Extension Point (Step 9a Phase 1)

### λ Backing-store contract can be introduced without changing remember/recover semantics

`psi.memory.store` now defines the provider protocol + registry selection model,
while `psi.memory.core` still owns current remember/recover behavior.
This lets us add persistent providers incrementally (Datalevin next) without
breaking current in-memory semantics.

### λ Memory store introspection attrs are now first-class EQL surface

These attrs are queryable from `:psi/memory-ctx`:

- `:psi.memory.store/providers`
- `:psi.memory.store/active-provider-id`
- `:psi.memory.store/default-provider-id`
- `:psi.memory.store/fallback-provider-id`
- `:psi.memory.store/selection`
- `:psi.memory.store/health`

Practical effect: provider selection/fallback state is observable from
agent-session/introspection flows before Datalevin write-path routing lands.

## 2026-02-28 - System Prompt Introspection

### λ System prompt is intentionally queryable verbatim via EQL

There is a first-class resolver for `:psi.agent-session/system-prompt` in
`components/agent-session/src/psi/agent_session/resolvers.clj`.

Use this query to retrieve the exact assembled prompt for the current session:

```clojure
[:psi.agent-session/system-prompt]
```

This is the canonical runtime source of truth (better than reconstructing from
files), and enables direct debugging/auditing of prompt assembly.

## 2026-02-28 - EQL Prompt Querying

### λ Query only resolver-backed attrs in app-query-tool

`app-query-tool` succeeds for `:psi.agent-session/system-prompt`, but queries that include
non-existent attrs (for example `:psi.agent-session/prompt`,
`:psi.agent-session/instructions`, `:psi.agent-session/messages`) can fail the whole
request.

Known-good prompt introspection queries:

```clojure
[:psi.agent-session/system-prompt]

[{:psi.agent-session/request-shape
  [:psi.request-shape/system-prompt-chars
   :psi.request-shape/estimated-tokens
   :psi.request-shape/total-chars]}]
```

Practical rule: start narrow, then expand with only attrs confirmed by resolvers.

## 2026-02-28 - Tool Output Delta

### λ Tool-output EQL introspection contract (stable attrs)

Tool-output policy and telemetry are queryable via these stable attrs:

- `:psi.tool-output/default-max-lines`
- `:psi.tool-output/default-max-bytes`
- `:psi.tool-output/overrides`
- `:psi.tool-output/calls`
- `:psi.tool-output/stats`

Per-call entities use `:psi.tool-output.call/*` attrs:

- `:psi.tool-output.call/tool-call-id`
- `:psi.tool-output.call/tool-name`
- `:psi.tool-output.call/timestamp`
- `:psi.tool-output.call/limit-hit?`
- `:psi.tool-output.call/truncated-by`
- `:psi.tool-output.call/effective-max-lines`
- `:psi.tool-output.call/effective-max-bytes`
- `:psi.tool-output.call/output-bytes`
- `:psi.tool-output.call/context-bytes-added`

### λ Policy + truncation semantics

- Default tool output policy: `max-lines=1000`, `max-bytes=25600`.
- Per-tool overrides are read from session data `:tool-output-overrides`.
- `read`, `app-query-tool`, `ls`, `find`, `grep` use head-style truncation.
- `bash` uses tail-style truncation.
- Truncated `bash`/`app-query-tool` responses include `:details {:full-output-path ...}`.

### λ Context-bytes-added semantics

`contextBytesAdded` is measured from the shaped tool result content that is
actually recorded in the tool result message (post-truncation / post-formatting),
not raw underlying command/file output bytes.

### λ Temp artifact lifecycle

- Truncated full-output artifacts are persisted under one process temp root.
- `tool-output/cleanup-temp-store!` is invoked on orderly teardown paths.
- Cleanup failures are warning-only and do not block shutdown.

### λ EQL query pattern for telemetry

Example query shape:

```clojure
[:psi.tool-output/stats
 {:psi.tool-output/calls
  [:psi.tool-output.call/tool-name
   :psi.tool-output.call/limit-hit?
   :psi.tool-output.call/output-bytes
   :psi.tool-output.call/context-bytes-added]}]
```

Use this after one or more tool calls to inspect per-call limit hits plus session
aggregates (`:total-context-bytes`, `:by-tool`, `:limit-hits-by-tool`).

## 2026-02-27 - mcp-tasks-run Orchestration + Tool Scoping

### λ mcp-tasks CLI Is CWD-Sensitive

Running `mcp-tasks add/update` from the wrong directory can create/use
`.mcp-tasks` in an unexpected parent and write tasks to the wrong repo
(observed: `/Users/duncan/projects/hugoduncan/.mcp-tasks/tasks.ednl`).

Always execute task mutations with an explicit project working directory
(shell `:dir`) and verify `pwd` before any write operation.

### λ Extension Workflow Futures Must Keep Invoke IDs Free of Runtime Keys

Fulcrologic statecharts `:future` invocation runtime tracks active futures in
an internal map keyed by `child-session-id = <session-id>.<invokeid>`.
When the parent state exits, `stop-invocation!` looks up that key and calls
`future-cancel` on the stored value.

If workflow data injects keys that collide with invoke metadata (e.g. `:control`
or other invoke-runtime keys), the runtime can store/read a non-Future value
for the invoke slot and fail with:

`class clojure.lang.Keyword cannot be cast to class java.util.concurrent.Future`

Observed symptom in `mcp-tasks-run`: workflow entered `:running`, then failed
immediately on `done.invoke.runner` with the Future cast exception before any
step execution.

Rule: keep extension runtime control values namespaced and avoid generic keys
that may overlap invocation internals.

### λ Standard Tool `:cwd` Support Prevents Extension Tool Forking

Worktree-aware orchestration needs tools to run relative to a worktree.
Re-defining `read/bash/edit/write` inside an extension duplicates behavior
and drifts from core semantics.

Better pattern:
- built-in tools accept optional `:cwd`
- expose a shared factory (e.g. `make-tools-with-cwd`)
- extensions/sub-agents reuse standard tools with scoped cwd

## 2026-02-26 - Hierarchical API Error Resolvers

### λ Hierarchical Resolver Pattern — Cheap List, Lazy Detail

Pathom3 resolvers naturally support hierarchical drill-down: a list
resolver outputs entities with identity keys + ctx, and downstream
detail resolvers only fire when their output attributes are queried.

Pattern:
```
Level 1 (list, cheap): scan for entities, output identity + ctx
Level 2 (detail, moderate): seeded by identity, parses/enriches
Level 3 (expensive): seeded by identity, reconstructs full state
```

Each level's resolver is independent — querying L1 never triggers L2/L3.
The ctx passthrough (`{:psi/agent-session-ctx agent-session-ctx}`) in
each list entity seeds downstream resolvers automatically.

### λ Request Shape as Diagnostic Surface

Computing "what would the API request look like?" is valuable both for
error forensics and live "will my next prompt fit?" checks.

Key insight: the same `compute-request-shape` fn serves both:
- `:psi.api-error/request-shape` — messages[0..error-index), post-mortem
- `:psi.agent-session/request-shape` — all current messages, live check

The shape is provider-agnostic (token estimate from char count / 4,
structural checks on agent-core messages directly).

### λ 400 Root Cause: headroom-tokens = 186

The resolvers immediately revealed the root cause: 320 messages with
~183K estimated tokens + 16K max-output left only 186 tokens of headroom.
The actual tokenizer likely pushed it over 200K. Auto-compaction didn't
trigger before this final call.

### λ Context Window Info Not in Session Data Atom

The session-data-atom `:model` only stores `{:provider :id :reasoning}`,
not the full model config. Context window and max-tokens come from the
ai-model config (stored separately in session-state). Resolvers need
fallback paths: session-data → model-config-atom → defaults.

## 2026-02-26 - OAuth Module

### λ No Clojure OAuth Client Library Fits CLI Use Case

All existing Clojure OAuth libs (`ring-oauth2`, `clj-oauth2`, `clj-oauth`)
are **server-side Ring middleware** — they authenticate users visiting a web
app. A CLI agent needs **client-side** OAuth: build auth URL, open browser,
receive local callback, exchange code. None of the libs do this.

**Decision**: Build directly on `clj-http` (already a dep) + JDK built-ins.
The actual OAuth logic is ~150 lines of shared infra + ~40 lines per provider.

### λ JDK HttpServer for OAuth Callback — Zero New Deps

`com.sun.net.httpserver.HttpServer` is built into the JDK, confirmed
available. Binds to localhost, receives one redirect callback, shuts down.
No need for http-kit or Ring just for this.

```clojure
(doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
  (.createContext "/" handler)
  (.start))
```

### λ Nullable Callback Server — Deliver Without Network

The callback server's Nullable uses a `LinkedBlockingQueue` with a
`:deliver` fn instead of a real HTTP server:

```clojure
;; Production: real HTTP callback
(let [srv (cb/start-server {:port 0})]
  ((:wait-for-code srv) 30000))

;; Test: inject result directly
(let [srv (cb/create-null-server)]
  ((:deliver srv) {:code "abc" :state "xyz"})
  ((:wait-for-code srv) 3000))
```

Same interface, same `wait-loop` fn — only the delivery mechanism differs.

### λ Credential Store Nullable — :persisted Atom for Inspection

The null store captures what was persisted via a `:persisted` atom,
letting tests verify persistence without disk I/O:

```clojure
(let [s (store/create-null-store)]
  (store/set-credential! s :anthropic {:type :api-key :key "k"})
  @(:persisted s))
;; => {:anthropic {:type :api-key :key "k"}}
```

### λ OAuth Context Composes Store + Providers — Three Nullable Layers

`oauth.core/create-null-context` composes a null store with stub providers.
Tests can override login/refresh behaviour per-context:

```clojure
(create-null-context
  {:credentials {:anthropic {:type :oauth :access "old" :expires 1000}}
   :login-fn    (fn [_] {:type :oauth :access "new" ...})})
```

Three independent Nullable layers (server, store, context) compose cleanly.

### λ Distill Spec Before Build — Scope Decisions Made Upfront

Writing `oauth-auth.allium` before code forced explicit decisions:
- Credential as sum type (ApiKeyCredential | OAuthCredential)
- 5-level API key priority chain
- Token refresh with concurrent-process awareness
- Provider contract surface (login, refresh, getApiKey, modifyModels)
- What's excluded (PKCE crypto, HTTP details, TUI rendering)

The spec excluded concerns cleanly, preventing scope creep during
implementation.

---

## 2026-02-26 - Retry, Tool Call Introspection, bash stdin

### λ should-retry? Guard Read From Wrong Data Source

The `should-retry?` statechart guard checked `(:messages sd)` from
session-data — but messages live in **agent-core**, not session-data.
`(:messages sd)` was always nil, so retry never fired.

**Fix**: Read from the `:pending-agent-event` which carries `:messages`
from agent-core's `:agent-end` event:

```clojure
;; ✗ session-data has no :messages key
(let [msgs (:messages @(:session-data-atom data)) ...])

;; ✓ agent-end event carries messages
(let [msgs (:messages (:pending-agent-event data)) ...])
```

### λ HTTP Status Lost Through Error Chain — Propagate Structured Data

clj-http throws `ExceptionInfo` with `:status` in `ex-data`, but every
layer stringified it: `(str e)` / `(ex-message e)`. The numeric status
was lost by the time it reached the retry guard.

**Fix**: Extract `:http-status` from `ex-data` at each catch site and
propagate it through the entire chain:

```
provider catch → :http-status in error event
streaming catch → :http-status preserved
turn statechart → :http-status in turn data
executor → :http-status on message map
statechart guard → numeric check
```

`retry-error?` now checks numeric status first (reliable), falls back
to string patterns (legacy compatibility):

```clojure
(def ^:private retriable-http-statuses #{429 500 502 503 529})

(defn retry-error? [stop-reason error-message http-status]
  (and (= stop-reason :error)
       (or (contains? retriable-http-statuses http-status)
           (some #(re-find % (or error-message "")) ...))))
```

### λ Hierarchical Pathom3 Resolvers — Split List from Detail

One monolithic resolver that computes everything can't be decomposed by
the EQL consumer. Split into list + detail resolvers:

```clojure
;; List resolver — cheap, no result loading
(pco/defresolver agent-session-tool-calls [...]
  {::pco/output [{:psi.agent-session/tool-call-history
                  [:psi.tool-call/id :psi.tool-call/name
                   :psi.tool-call/arguments :psi/agent-session-ctx]}]})

;; Detail resolver — runs only when result/error queried
(pco/defresolver tool-call-result [...]
  {::pco/input [:psi.tool-call/id :psi/agent-session-ctx]
   ::pco/output [:psi.tool-call/result :psi.tool-call/is-error]})
```

Three query levels, each triggers only what's needed:
- `[:psi.agent-session/tool-call-history-count]` → count only
- `[{… [:psi.tool-call/name]}]` → list resolver only
- `[{… [:psi.tool-call/name :psi.tool-call/result]}]` → list + detail

**Key**: Pass `:psi/agent-session-ctx` through the list entities so the
detail resolver can access agent-core messages.

### λ Agent-Core Messages vs Session Journal

Messages live in **agent-core** (`(:messages (agent/get-data-in agent-ctx))`),
not the session journal. The journal stores session entries (`:kind :message`)
but the journal atom was empty while agent-core had 9 messages. Always check
where data actually lives before writing resolvers.

### λ shell/sh Stdin Pipe Breaks rg

`clojure.java.shell/sh` connects stdin as a pipe. ripgrep detects
`is_readable_stdin=true` and searches **stdin** instead of the working
directory. Result: `rg pattern` returns nothing + exit 1.

**Fix**: Use `babashka.process/shell` with `:in (java.io.File. "/dev/null")`.
This gives rg a file descriptor (not a pipe), so it correctly searches cwd.
Pipes within commands still work because bash establishes their own stdin.

```clojure
(proc/shell {:out :string :err :string :continue true
             :in (java.io.File. "/dev/null")}
            "bash" "-c" command)
```

**Note**: `:in ""` does NOT work — empty string still creates a readable pipe.

### λ Protocol Reload Breaks Running Records

`:reload-all` redefines protocols, but existing record instances still
reference the old protocol. Statechart `LocalMemoryStore` records created
at session startup break with `No implementation of method` errors.

**Rule**: Changes to code that touches protocols/records used by the
statechart require a process restart. Namespace `:reload` (not `:reload-all`)
is safe for most changes but not protocol-dependent code.

### λ Statechart Guards Are Captured at Startup

Statechart guard functions are closures captured when the chart is created.
Reloading the namespace that defines `should-retry?` doesn't replace the
guard in the already-running statechart. The new code only takes effect
after a process restart.

### λ Pathom3 Index Is the Resolver Registry

The Pathom3 environment indexes are queryable:

```clojure
(let [env (resolvers/build-env)]
  ;; All resolver names
  (keys (:com.wsscode.pathom3.connect.indexes/index-resolvers env))
  ;; All queryable attributes
  (keys (:com.wsscode.pathom3.connect.indexes/index-attributes env)))
```

25 resolvers, 80 attributes across 8 namespaces (agent-session, tool-call,
turn, extension, prompt-template, skill, ui, agent-session-ctx).

---

## 2026-02-25 - UI Extension Points Implementation

### λ Extension UI State Lives in TUI Component, Not Agent-Session

`psi.tui.extension-ui` lives in the `tui` component because both `tui/app.clj`
(which renders it) and `agent-session/extensions.clj` (which creates UI contexts)
need to require it.  Since agent-session depends on tui but not vice versa,
the module must live in tui.

### λ Promise Bridge for Blocking Dialogs in Elm Architecture

The challenge: extensions call `(confirm "title" "msg")` and block, but the
TUI is message-driven (Elm update/view).  Solution — enqueue a dialog with a
`promise`, block the extension thread on `deref`, and have the TUI update fn
call `deliver` when the user responds.

```clojure
;; Extension thread (blocking)
(let [result (deref (:promise dialog))]  ; blocks here
  (if result "confirmed" "cancelled"))

;; TUI update thread (resolves)
(deliver (:promise active-dialog) true)  ; unblocks extension
```

FIFO queue ensures one dialog at a time. `advance-queue!` promotes next.

### λ clear-all! Must Snapshot Before Reset

When clearing all UI state (e.g. extension reload), snapshot the active and
pending dialogs **before** resetting the atom.  If you cancel the active dialog
first, `cancel-dialog!` calls `advance-queue!` which promotes a pending dialog
to active — then the pending loop finds nothing to deliver:

```clojure
;; ✗ Race: cancel advances queue, doseq finds empty pending
(cancel-dialog! ui-atom)
(doseq [d (get-in @ui-atom [:dialog-queue :pending])] (deliver (:promise d) nil))

;; ✓ Snapshot all, reset, then deliver
(let [active  (get-in @ui-atom [:dialog-queue :active])
      pending (get-in @ui-atom [:dialog-queue :pending])]
  (reset! ui-atom {...empty...})
  (when active (deliver (:promise active) nil))
  (doseq [d pending] (deliver (:promise d) nil)))
```

### λ Dialog Routing in Elm Update — Check Before Normal Input

When a dialog is active, **all keypresses** go to the dialog handler, not the
editor.  The update function must check for active dialog before checking idle
state, otherwise escape/enter goes to the wrong handler:

```clojure
(cond
  ;; ctrl+c always quits
  (msg/key-match? m "ctrl+c") [state charm/quit-cmd]
  ;; Dialog intercepts ALL key input when active
  (and (has-active-dialog? state) (msg/key-press? m))
  (handle-dialog-key state m)
  ;; Normal idle input...
  ...)
```

### λ charm.clj Key Press Messages Have :key Field

charm.clj key press messages are maps with `:key` (a string).  For single
printable characters, `(:key m)` returns the character.  There is no
`key-runes` function — that's a Bubble Tea concept.

### λ EQL Snapshot Must Strip Promises and Functions

Promises and function objects are not serialisable.  The `snapshot` fn for
EQL resolvers strips `:promise` from dialog maps and `:render-*-fn` from
renderer maps, returning only data the resolver can safely expose.

### λ Allium Spec → Design Decisions → Implementation

Writing the allium spec first forced 10 design decisions to be explicit
before any code was written:

1. State ownership (centralized Elm model)
2. Dialog model (promise bridge)
3. Dialog queueing (FIFO)
4. Widget placement (above/below editor, keyed by ext-id)
5. Status vs widget vs notification (three primitives)
6. Custom rendering (register fn by tool-name/custom-type)
7. Render output (ANSI strings)
8. UI availability (:ui key, nil when headless)
9. Screen takeover (deferred)
10. EQL queryability (yes, read-only)

The spec's `open_question` blocks captured decisions to revisit later without
blocking implementation.  This is significantly faster than discovering
these decisions during coding.

### λ Deferred Items Get Smaller After Spec

Items originally on the deferred list (`RegisteredCommand`, extension tool
wrapping) were already partially implemented by the time the UI spec was done.
Writing the spec clarified that tool wrapping pre/post hooks were already
implemented in `wrap-tool-executor`.  The deferred list shrinks when you spec
precisely enough to see what's already done.

---

## 2026-02-25 - Extension System Implementation

### λ Clojure Extensions = load-file + init fn

Extensions are `.clj` files with a namespace that defines an `init` function.
The loader reads the `ns` form, `load-file`s the source, resolves the `init`
var, and calls it with an ExtensionAPI map.

```clojure
;; ~/.psi/agent/extensions/hello_ext.clj
(ns my.hello-ext)
(defn init [api]
  ((:register-command api) "hello" {:description "Say hi"
                                    :handler (fn [args] (println "Hello" args))})
  ((:on api) "session_switch" (fn [ev] (println "switched!" ev))))
```

This is simpler than pi's TypeScript approach (jiti + virtualModules + aliases)
because Clojure's `load-file` handles compilation natively.

### λ ExtensionAPI as a Plain Map

The API passed to extension `init` fns is a plain Clojure map of functions.
No protocol, no deftype — just a map with keyword keys for registration and
action fns.  Each action fn delegates to the session context via closures:

```clojure
{:on                 (fn [event-name handler-fn] ...)
 :register-tool      (fn [tool] ...)
 :register-command   (fn [name opts] ...)
 :register-flag      (fn [name opts] ...)
 :get-flag           (fn [name] ...)
 :set-session-name   (fn [name] ...)
 :events             {:emit (fn [ch data] ...) :on (fn [ch handler] ...)}}
```

Action fns that haven't been wired to a session throw on call
(same pattern as pi's "throwing stubs until runner.initialize()").

### λ Tool Wrapping via wrap-tool-executor

Tool wrapping creates a higher-order function around the tool executor:

```clojure
(let [wrapped (ext/wrap-tool-executor reg execute-tool)]
  (wrapped "bash" {"command" "ls"}))
```

Pre-hook: dispatches `tool_call` event → handler returns `{:block true}` to prevent.
Post-hook: dispatches `tool_result` event → handler returns `{:content "modified"}`.

This is compositional — the wrapped fn has the same signature as the original.

### λ Forward Declarations for Mutual References

When `make-extension-action-fns` (early in core.clj) needs to call
`set-session-name-in!` (defined later), use `(declare set-session-name-in!)`.
This is standard Clojure but easy to forget when adding cross-referencing
functions to an existing namespace.

### λ Discovery Convention: .clj in dir OR extension.clj in subdir

Extension discovery searches:
1. `.psi/extensions/*.clj` — direct files
2. `.psi/extensions/*/extension.clj` — subdirectory convention

This mirrors pi's `index.ts` convention for directories.

---

## 2026-02-25 - Per-Turn Streaming Statechart (Step 6)

### λ FlatWorkingMemoryDataModel Uses Its Own Namespace Key

The fulcrologic `FlatWorkingMemoryDataModel` stores and reads data from
`::wmdm/data-model` (`:com.fulcrologic.statecharts.data-model.working-memory-data-model/data-model`),
**NOT** from `::sc/data-model` (`:com.fulcrologic.statecharts/data-model`).

These are different fully-qualified keywords. Using the wrong one means
scripts receive `nil` as their `data` parameter — actions silently don't
fire because the execution model swallows the nil.

```clojure
;; ✗ WRONG — different namespace, scripts get nil
(update wm ::sc/data-model merge extra-data)

;; ✓ CORRECT — matches what FlatWorkingMemoryDataModel reads
(require '[com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm])
(update wm ::wmdm/data-model merge extra-data)
```

### λ sp/start! Puts Initial Data at WM Top-Level, Not in Data Model

`sp/start!` (processor-level) merges the `params` map into the working
memory at the **top level** alongside `::sc/configuration`, `::sc/session-id`,
etc.  It does NOT populate `::wmdm/data-model`.

Scripts read from `(sp/current-data data-model env)` which reads
`::wmdm/data-model`.  So initial user data (actions-fn, context atoms)
must be explicitly placed there after `start!`:

```clojure
(let [wm (sp/start! processor env :chart-id {::sc/session-id sid})]
  (save-working-memory! sc-env sid
    (assoc wm ::wmdm/data-model {:actions-fn af :turn-data td})))
```

### λ Session Statechart Data Model Bug (Pre-existing)

The existing session statechart (`statechart.clj`) uses `::sc/data-model`
for merging extra-data in `send-event!`.  This is the **wrong key** — the
flat data model reads from `::wmdm/data-model`.  Guards that check
`:pending-agent-event` receive nil and always return false.

The system still works because:
1. Auto-compact and retry guards silently fail (return false on nil)
2. The `:session/agent-event` with `:agent-end` falls through all guards,
   leaving the session in `:streaming` — but `run-agent-loop!` manages
   the session lifecycle directly via `end-loop-in!`
3. No test exercises the reactive guard path end-to-end

**Impact**: Auto-compaction and auto-retry via statechart guards are
non-functional.  They need the same `::wmdm/data-model` fix.

### λ Debugging WM Data Flow — Use Assertion Messages

When `println` output is captured by test harnesses (kaocha captures
stdout per test), use **assertion message comparison** to surface values:

```clojure
;; ✗ println swallowed by test output capture
(println "DM:" dm)

;; ✓ intentionally-wrong assertion shows actual value in failure output
(is (= :show-me-the-keys (keys wm)))
```

### λ Self-Transitions Required for simple-env Accumulation

Targetless transitions (no `:target` attribute) in fulcrologic's
`simple-env` are untested territory.  Use **self-transitions** (`:target`
pointing to the current state) for accumulation events like
`:turn/text-delta`.  The exit/re-entry overhead is negligible when there
are no `on-entry`/`on-exit` handlers.

```clojure
;; Self-transition — safe, works in simple-env
(ele/transition {:event  :turn/text-delta
                 :target :text-accumulating}
  (ele/script {:expr (fn [_env data] (dispatch! data :on-text-delta))}))
```

### λ Per-Turn Statechart Architecture

Each streaming turn gets its own short-lived statechart context:

```
:idle → :text-accumulating ⇄ :tool-accumulating → :done | :error
```

- **Statechart** owns state transitions (explicit, queryable)
- **turn-data atom** owns accumulated content (text buffer, tool calls)
- **actions-fn** bridges statechart events to side effects (agent-core calls)
- **turn-ctx-atom** on agent-session context enables nREPL introspection

Provider events are translated 1:1 to statechart events:
`:text-delta` → `:turn/text-delta`, `:toolcall-start` → `:turn/toolcall-start`, etc.

The executor creates a fresh turn context per `stream-turn!` call and
stores it in the session's `:turn-ctx-atom` for live EQL queries.

### λ with-redefs Works on Private Vars

`with-redefs` resolves symbols via `(var sym)` at compile time.  Private
vars ARE accessible through fully-qualified `#'ns/private-var`.  This is
the standard pattern for stubbing internal functions in tests:

```clojure
(with-redefs [psi.agent-session.executor/do-stream!
              (fn [_ctx _conv _model _opts consume-fn]
                (consume-fn {:type :start})
                (consume-fn {:type :text-delta :delta "hello"})
                (consume-fn {:type :done :reason :stop}))]
  (executor/run-agent-loop! ...))
```

---

## 2026-02-25 - agent-session Component

### λ Statechart Working Memory Data Pattern

`simple/simple-env` uses a **flat** working memory data model.  Guard and script
functions receive `(fn [env data])` where `data` is the flat WM map.  The current
event is stored at `:_event` inside `data` by the v20150901 algorithm.

To pass extra data to guards (e.g. the agent event that triggered a transition),
merge it into the WM before calling `sp/process-event!`:

```clojure
(defn send-event! [sc-env session-id event-kw extra-data]
  (let [wm  (get-working-memory sc-env session-id)
        wm' (if extra-data
              (update wm ::sc/data-model merge extra-data)
              wm)]
    (sp/save-working-memory! ...)
    (sp/process-event! ... wm' evt)))
```

Guards then read `(:pending-agent-event data)` — the key we merged in.

Initial WM is populated via `sp/start!`:
```clojure
(sp/start! processor env :chart-id {::sc/session-id id
                                     :session-data-atom a
                                     :actions-fn f
                                     :config c})
```

### λ Reactive Agent Event Bridge via add-watch

Agent-core's `events-atom` accumulates events (never reset between calls).
Bridge to session statechart using `add-watch` with old/new comparison:

```clojure
(add-watch (:events-atom agent-ctx) ::session-bridge
  (fn [_key _ref old-events new-events]
    (let [new-count (count new-events)
          old-count (count old-events)]
      (when (> new-count old-count)
        (doseq [ev (subvec new-events old-count new-count)]
          (sc/send-event! sc-env sc-session-id :session/agent-event
                          {:pending-agent-event ev}))))))
```

This is simpler than a callback and avoids modifying agent-core's API.

### λ Statechart Script Elements Pattern

Use `(ele/script {:expr (fn [env data] ...)})` inside transitions for side
effects, and `(ele/on-entry {} (ele/script {:expr ...}))` for entry actions.
Guards go in `{:cond (fn [_env data] ...)}` on the transition map.

The `actions-fn` in WM is a dispatcher: `(fn [action-key] ...)`.  Statechart
scripts call `(dispatch! data :action-key)` which is pure (reads from data, no
closures over ctx), keeping the statechart definition portable.

### λ Allium Sub-spec Splitting Pattern

When a monolithic `.allium` spec grows too large, split by orthogonal concern:
- Each sub-spec `use`s its dependencies by path
- Cross-references use `ext/` and `compact/` namespace prefixes
- Open questions follow each spec and reference the parent spec's original questions
- The original spec is retained as reference; sub-specs are the authoritative source

---

## 2026-02-25 - Global Query Graph Wiring (Step 4)

### λ register-resolvers! / register-resolvers-in! Pattern

Every component that contributes resolvers to the graph gets two registration
functions matching the pattern in `psi.ai.core`:

```clojure
(defn register-resolvers-in!
  "Isolated — for tests. rebuild? flag avoids double-rebuild when caller
   batches multiple components."
  ([qctx] (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r all-resolvers]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-resolvers!
  "Global — call once at startup."
  []
  (doseq [r all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!))
```

The `rebuild?` flag prevents double-rebuild when a higher-level component
(e.g. introspection) registers multiple sub-component resolver sets then
rebuilds once at the end.

### λ Batched Registration — Single Rebuild

When a component wires N sub-components into a shared `QueryContext`, register
all resolver sets first and rebuild once at the end:

```clojure
;; introspection/register-resolvers-in!
(doseq [r introspection-resolvers] (register-resolver-in! qctx r))
(when session-ctx
  (agent-session/register-resolvers-in! qctx false))  ; rebuild?=false
(rebuild-env-in! qctx)                                  ; single rebuild
```

This avoids N intermediate Pathom index compilations (each `rebuild-env-in!`
recompiles the full index from scratch).

### λ Introspection Context Accepts Optional Sub-Contexts

When a component's isolated context needs to include another component's
graph, use an options map factory with optional keys — nil means "omit":

```clojure
(defn create-context
  ([] (create-context {}))
  ([{:keys [engine-ctx query-ctx agent-session-ctx]}]
   (->IntrospectionContext
    (or engine-ctx (engine/create-context))
    (or query-ctx  (query/create-query-context))
    agent-session-ctx)))  ; nil = no agent-session resolvers registered
```

`register-resolvers-in!` checks `(:agent-session-ctx ctx)` and conditionally
registers the additional resolver set.  Tests that don't need agent-session
simply call `(create-context)` and get the old behaviour.

### λ Unused Public Var — clojure-lsp INFO vs Error

`register-resolvers!` (global) has no callers in the codebase until startup
code is added — clojure-lsp reports it as `INFO: Unused public var`.  This is
**not an error** and does not block compilation.  It is suppressed by either:

- Adding `^:export` metadata to the var, or
- Calling it from startup code (e.g. `main.clj`)

The warning is expected for intentional public API entry points.

### λ Drop Unused Requires Immediately

Adding a require for a future refactor (e.g. `[psi.query.core :as query]` in
`main.clj`) and never using the alias leaves dead weight.  Remove it in the
same commit or the next atomic one.  clj-kondo and clojure-lsp may not warn
in all configurations, so check manually after every namespace edit.

---

## 2026-02-25 - Introspection Component

### λ Introspection = Engine Queries Itself via EQL

The introspection component wires engine + query together so the system
is self-describing via a uniform EQL surface.  Two namespaces:

- `psi.introspection.resolvers` — five Pathom3 resolvers; all accept
  context objects as EQL seed inputs (`:psi/engine-ctx`, `:psi/query-ctx`)
- `psi.introspection.core`      — public API, Nullable pattern throughout

Key design decisions:
1. **Contexts as EQL seeds** — resolvers receive engine/query contexts
   through the EQL input map, not as closed-over globals.  This makes
   every resolver testable in isolation with `create-context`.
2. **Self-describing graph** — `query-graph-summary-in` queries the graph
   for its own resolver list, so introspection resolvers appear in their
   own output (`graph-self-describes-test`).
3. **Derived properties live in engine** — `has-interface?`, `is-ai-complete?`
   etc. are computed by `psi.engine.core`; the resolver just surfaces them.

### λ EQL Attribute Namespace Convention for Cross-Component Queries

Use `psi.X/Y` namespaces for attributes that cross component boundaries:

| Prefix          | Domain                          |
|-----------------|---------------------------------|
| `:psi/`         | top-level system context inputs |
| `:psi.engine/`  | engine entity attributes        |
| `:psi.system/`  | system state attributes         |
| `:psi.graph/`   | query graph attributes          |

Seed inputs (`:psi/engine-ctx`, `:psi/query-ctx`) are opaque Clojure
records — Pathom treats them as plain values in the entity map.

### λ trigger stored as (str keyword) — contains colon

`engine/trigger-engine-event-in!` stores `(str event)` on each transition.
For a keyword `:configuration-complete` this yields `":configuration-complete"`
(with leading colon).  Tests must match the stringified form, not the bare name.

## 2026-02-25 - clj-http Cookie Policy

### λ clj-http :cookie-policy :none → CookieSpecs/IGNORE_COOKIES

clj-http's `get-cookie-policy` multimethod dispatches on the `:cookie-policy`
key.  The correct key to fully disable cookie processing is **`:none`**, not
`:ignore-cookies`:

```clojure
(defmethod get-cookie-policy :none [_] CookieSpecs/IGNORE_COOKIES)
```

An unknown key (e.g. `:ignore-cookies`) falls through to the `nil` default
which uses `CookieSpecs/DEFAULT` — cookies are still processed, warnings still appear.

Use `:none` when calling APIs (e.g. OpenAI) that return cookies (Cloudflare
`__cf_bm`) with non-standard `expires` date formats.  Apache HttpClient
emits a `WARNING: Invalid cookie header` via `ResponseProcessCookies` when
it cannot parse the date.  `:none` prevents that interceptor from running.

```clojure
(http/post url (merge request {:as :stream :cookie-policy :none}))
```

---

## 2026-02-25 - JVM Shutdown / CLI Entry Point

### λ clj-http Parks a Non-Daemon Thread — Call System/exit

`clj-http` (Apache HttpClient) starts a connection-eviction background thread
with `isDaemon() = false`.  Non-daemon threads block JVM shutdown.  After the
CLI prompt loop exits (e.g. `/quit`), the JVM hangs indefinitely waiting for
this thread to finish.

**Fix**: call `(System/exit 0)` at the end of `-main`:

```clojure
(defn -main [& args]
  (run-session ...)
  ;; clj-http parks a non-daemon connection-eviction thread.
  ;; Explicitly exit so the JVM does not hang after /quit.
  (System/exit 0))
```

This is the standard pattern for CLI tools using clj-http (or any library
that parks non-daemon threads).  It only runs after `run-session` returns
normally so it does not swallow exceptions during the session.

Other potential culprits investigated and cleared:
- `simple-env` statecharts — use manually-polled queue, **no** background threads
- `future` in streaming layer — uses ForkJoin pool, **all daemon** threads

### λ Provider is Encoded in the Model Key — No --provider Flag Needed

The model key already identifies the provider.  `--model <key>` is the one
obvious way to select both:

| CLI flag | Model | Provider |
|----------|-------|----------|
| `--model claude-3-5-haiku` | Claude 3.5 Haiku | Anthropic |
| `--model claude-3-5-sonnet` | Claude 3.5 Sonnet | Anthropic |
| `--model gpt-4o` | GPT-4o | OpenAI |
| `--model o1-preview` | GPT-o1 Preview | OpenAI |

`PSI_MODEL` env var is the alternative.  A separate `--provider` flag would
be redundant and violate the **One Way** principle.

---

## 2025-02-24 23:34 - Bootstrap Testing

### λ Testing Infrastructure Works

**Test Command**: `clojure -M:test` (not `-X:test`)
- `-X:test` fails (no :exec-fn defined)
- `-M:test` succeeds (uses :main-opts with kaocha.runner)

**AI Component Status**: ✓ All tests passing
- psi.ai.core-test: 5 tests, 23 assertions, 0 failures
- Core functionality verified:
  - Stream options validation
  - Message handling
  - Usage calculation
  - Model validation (Claude/OpenAI)
  - Conversation lifecycle

**Test Configuration**:
- Kaocha runner with documentation reporter
- Test paths: test/ + components/ai/test/
- Integration tests skipped (marked with :integration meta)
- Colorized output enabled

### λ System State Understanding

**Runtime**: JVM Clojure ready
- deps.edn: Polylith AI component + pathom3 + statecharts
- tests.edn: Kaocha configuration
- Latest: AI component integrated (commit 8663e14)

**Architecture Progress**:
- ✓ AI component implemented & tested
- ✓ Allium specs defined
- ? Engine (statecharts integration)
- ? Query interface (pathom3 integration)
- ? Graph emergence from resolvers

**Next**: System integration beyond component tests

---

## 2026-02-25 - EQL Query Component

### λ psi/query Component Built and Clean

**Component**: `components/query/` — Pathom3 EQL query surface

Three namespaces, one responsibility each:
- `psi.query.registry` — additive resolver/mutation store (atoms, malli-validated)
- `psi.query.env`      — Pathom3 environment construction (`build-env`, `process`)
- `psi.query.core`     — public API: `register-resolver!`, `query`, `query-one`,
                          `rebuild-env!`, `graph-summary`, `defresolver`, `defmutation`

**Status**: 10 tests, 32 assertions, 0 failures. 0 kondo errors/warnings. 0 LSP diagnostics.

### λ clj-kondo Config Import

Run this after adding new deps or components — imports hook/type configs from jars:
```bash
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```
Run at **root** and in **each component dir** separately.

New configs gained this session: pathom3, promesa, guardrails, potemkin, prismatic/schema.

### λ Two Separate Lint Systems

**clj-kondo** (`.clj-kondo/config.edn`) and **clojure-lsp** (`.lsp/config.edn`) are distinct:

| Concern | Config file | Linter key |
|---------|-------------|------------|
| clj-kondo unused public var | `.clj-kondo/config.edn` | `:unused-public-var` |
| clojure-lsp unused public var | `.lsp/config.edn` | `:clojure-lsp/unused-public-var` |

**✗ Do not** put `:unused-public-var` or `:clojure-lsp/*` keys in `.clj-kondo/config.edn`
— clj-kondo will warn "Unexpected linter name".

**Authoritative check**: `clojure-lsp diagnostics --project-root .` — not the pi tool
(which caches stale results).

### λ Test Isolation Pattern

Polylith components use `defonce` atoms — state bleeds between tests in the same JVM.
`use-fixtures :each` resets between *test functions* but not between `testing` blocks.

**Pattern** — use a `with-clean-*` macro:
```clojure
(defmacro with-clean-registry [& body]
  `(do (registry/reset-registry!)
       (try ~@body (finally (registry/reset-registry!)))))
```
Wrap each isolated scenario in its own `with-clean-*` call.

### λ Inline defs in Tests

`pco/defresolver` / `pco/defmutation` inside a `deftest` body triggers
clj-kondo `inline-def` warning and confuses clojure-lsp symbol resolution.

**Fix**: define resolvers/mutations at **top-level** in the test namespace.
If the test needs a clean registry, re-register the top-level var inside
`with-clean-*` rather than redefining.

### λ Kaocha --focus Syntax

`--focus psi.query` does not match test namespaces (needs exact ns name).
Use: `--focus psi.query.core-test --focus psi.query.registry-test`

### λ Architecture Progress

- ✓ AI component implemented & tested
- ✓ Engine (statecharts) component implemented & tested
- ✓ Query (EQL/Pathom3) component implemented & tested
- ✓ AI integrated with engine + query — resolvers registered, core.async removed
- ? Graph emergence from resolvers (next: add domain resolvers)
- ? Introspection (engine queries engine via EQL)
- ? History / Knowledge resolvers (git + knowledge graph)

---

## 2026-02-25 - AI ↔ Engine/Query Integration

### λ Callback > Channel for blocking I/O

Provider HTTP streaming is purely blocking I/O.  `core.async/go` +
`async/chan` added scheduler complexity with no benefit.  Replacing with:

- **`consume-fn` callback** — provider calls it synchronously per event
- **`future` + `LinkedBlockingQueue`** — bridges background thread to a
  lazy seq when callers prefer pull-style consumption

Pattern:
```clojure
;; Push style (callback)
(stream-response provider conv model opts
  (fn [ev] (when (= :text-delta (:type ev)) (print (:delta ev)))))

;; Pull style (lazy seq)
(let [{:keys [events]} (stream-response-seq provider conv model opts)]
  (doseq [ev events] ...))
```

### λ AI Resolvers in EQL Graph

Register AI capabilities as Pathom resolvers so the whole system can query
them via a uniform EQL surface:

```clojure
(core/register-resolvers!)
(query/query {} [:ai/all-models])
(query/query {:ai.model/key :gpt-4o} [:ai.model/data])
(query/query {:ai/provider :anthropic} [:ai/provider-models])
```

### λ clj-kondo Hooks Must Be Imported Per Component

Each Polylith component has its own `.clj-kondo/` dir.  When a component
gains a new dependency whose macros need kondo hooks (e.g. pathom3's
`pco/defresolver`), the hooks must be imported **in that component's dir**:

```bash
cd components/<name>
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

The component `deps.edn` must already declare the dep for it to appear
on the classpath.  Symptom of missing import: "Unresolved symbol" for
every var/binding the macro generates.

### λ Stub Provider Pattern for Tests

Use a stub provider closure to drive streaming tests without HTTP:

```clojure
(defn stub-provider [text]
  {:name   :stub
   :stream (fn [_conv _model _opts consume-fn]
             (consume-fn {:type :start})
             (consume-fn {:type :text-delta :delta text})
             (consume-fn {:type :done :reason :stop ...}))})
```

Swap it into the registry for the test, restore afterward.

---

## 2026-02-25 - Nullable Pattern / Testing Without Mocks

### λ Nullable Pattern in Clojure — Isolated Context Factory

The Nullable pattern replaces global-atom resets and mock/spy setups with
isolated context factories.  Every component that owns mutable state gets a
`create-context` (or `create-registry`, `create-query-context`) factory that
returns a plain map of fresh atoms:

```clojure
(defn create-context []
  {:engines           (atom {})
   :system-state      (atom nil)
   :state-transitions (atom [])
   :sc-env            (atom nil)})
```

All mutable functions gain a `*-in` context-aware variant taking the context
as first arg.  The global (singleton) API becomes thin wrappers via a
`global-context` helper that returns the `defonce` atoms:

```clojure
(defn- global-context []
  {:engines engines :system-state system-state ...})

(defn create-engine [engine-id config]
  (create-engine-in (global-context) engine-id config))
```

Tests create their own context — no shared state, no cleanup fixtures:

```clojure
(deftest engine-lifecycle-test
  (let [ctx (engine/create-context)
        eng (engine/create-engine-in ctx "test" {})]
    (is (= :initializing (:engine-status eng)))))
```

### λ Isolated Query Context (QueryContext record)

`query/create-query-context` returns a `QueryContext` record with its own
registry + env atom.  Tests register resolvers into it and query against it:

```clojure
(let [ctx (query/create-query-context)]
  (query/register-resolver-in! ctx my-resolver)
  (query/rebuild-env-in! ctx)
  (query/query-in ctx {:user/id 1} [:user/name]))
```

This replaces the `with-clean-query` macro that reset global atoms.

### λ Isolation Tests Are Worth Adding

Adding an explicit test that two contexts are independent catches regressions
if the factory accidentally shares state:

```clojure
(deftest context-isolation-test
  (let [ctx-a (query/create-query-context)
        ctx-b (query/create-query-context)]
    (query/register-resolver-in! ctx-a greeting-resolver)
    (is (= 1 (:resolver-count (query/graph-summary-in ctx-a))))
    (is (= 0 (:resolver-count (query/graph-summary-in ctx-b))))))
```

### λ Nullable Pattern for External Process Infrastructure (git)

When infrastructure is an external process (not mutable state), the Nullable
pattern uses a **context record + embedded temp environment** rather than a
stub closure:

```clojure
;; GitContext — the infrastructure wrapper
(defrecord GitContext [repo-dir])

(defn create-context
  "Production: points at a real repo dir."
  ([] (create-context (System/getProperty "user.dir")))
  ([repo-dir] (->GitContext repo-dir)))

(defn create-null-context
  "Test: builds an isolated temp git repo with seeded commits.
   Real git, controlled data, no shared state, no mocking."
  ([] (create-null-context default-seed-commits))
  ([commits]
   (let [tmp (make-temp-dir)]
     (git-init! tmp)
     (doseq [{:keys [message files]} commits]
       (write-files! tmp files)
       (git-commit! tmp message))
     (->GitContext tmp))))
```

Key points:
- **Real git subprocess** — not a stub. Tests exercise the same code path as production.
- **Seeded data** — commits carry controlled messages with vocabulary symbols.
- **Isolated per test** — each `create-null-context` call gets a fresh temp dir.
- **mkdirs before spit** — files in subdirs need parent dirs created first.
- **No cleanup needed** — JVM temp dirs are cleaned on exit.

Two-context isolation test verifies independence:
```clojure
(deftest two-null-contexts-are-independent
  (let [ctx-a (git/create-null-context [{:message "only in A" :files {"a.txt" "a"}}])
        ctx-b (git/create-null-context [{:message "only in B" :files {"b.txt" "b"}}])]
    (is (not (some #(str/includes? (:git.commit/subject %) "B")
                   (git/log ctx-a {}))))))
```

---

## 2026-02-26 - charm.clj Alt-Screen Bug

### λ charm.clj v0.1.42 enter-alt-screen! Never Fires

`create-renderer` stores `:alt-screen` from opts into the renderer atom.
`enter-alt-screen!` checks `(when-not (:alt-screen @renderer))` — which
short-circuits because the flag is already `true`. Alt-screen is never
actually entered.

**Impact**: TUI runs inline in the main terminal buffer. JLine's `Display`
uses relative cursor tracking in non-fullscreen context. Any content height
change (streaming toggle, notifications, errors) desyncs cursor position.
Symptom: typed text renders after the footer instead of at the prompt.

**Root cause chain**:
1. `create-renderer` stores `:alt-screen true` in atom (from opts)
2. `start!` sees `:alt-screen true`, calls `enter-alt-screen!`
3. `enter-alt-screen!` checks `(when-not (:alt-screen @renderer))` → skip

**Fix**: `alter-var-root` patch (same pattern as keymap fix):
```clojure
(alter-var-root
 #'charm.render.core/enter-alt-screen!
 (constantly
  (fn [renderer]
    (let [terminal (:terminal @renderer)]
      (charm-term/enter-alt-screen terminal)
      (charm-term/clear-screen terminal)
      (charm-term/cursor-home terminal))
    (swap! renderer assoc :alt-screen true))))
```

**Lesson**: When a library stores "desired config" and "current state"
in the same key, idempotency guards can prevent initial setup from
running. Two charm.clj patches now — both `alter-var-root` at load time.

---

## 2026-02-25 - TUI: charm.clj Elm Architecture (Step 5)

### λ charm.clj Replaces Custom Terminal Layer

Custom `ProcessTerminal` with `stty -echo raw` + manual differential
rendering had cursor position desync on macOS. The cursor would move
incorrectly after PTY-level echo, making typed text invisible.

**Fix**: Replace the entire TUI layer with charm.clj's Elm Architecture.
charm.clj uses JLine3 (`jline-terminal-ffm`) which handles raw mode,
input parsing, and rendering correctly via JLine's `Display` differ.

Architecture:
```
init    → [state nil]        ; initial state
update  → (state msg) → [state cmd]  ; pure state transitions
view    → state → string     ; pure render
```

The agent runs in a `future`. Communication uses `LinkedBlockingQueue`:
```
submit → future puts {:kind :done/:error} on queue
poll-cmd → reads queue with 120ms timeout → returns message
poll timeout → advances spinner, issues new poll-cmd
```

No separate timer thread. Spinner is driven by poll ticks.

### λ JLine3 FFM Requires --enable-native-access

charm.clj uses `jline-terminal-ffm` which needs the JVM flag
`--enable-native-access=ALL-UNNAMED` on JDK 22+. Add to `:jvm-opts`
in the `:run` alias.

### λ charm.clj v0.1.42 JLine String vs char[] Bug

`charm.input.keymap/bind-from-capability!` calls `(String. ^chars seq)`
but JLine 3.30+ `KeyMap/key` returns `String`, not `char[]`.
ClassCastException at runtime.

**Fix**: `alter-var-root` the private fn at namespace load time:
```clojure
(alter-var-root
 #'charm.input.keymap/bind-from-capability!
 (constantly
  (fn [^KeyMap keymap ^Terminal terminal cap event]
    (when terminal
      (when-let [seq-val (KeyMap/key terminal cap)]
        (let [^String seq-str (if (string? seq-val)
                                seq-val
                                (String. ^chars seq-val))]
          (when (and (pos? (count seq-str))
                     (= (int (.charAt seq-str 0)) 27))
            (.bind keymap event (subs seq-str 1)))))))))
```

**Lesson**: Add a JLine integration smoke test that creates a real
terminal + keymap. Unit tests exercising pure init/update/view don't
touch JLine and miss this class of bug.

### λ Elm Architecture Patterns for Agent TUI

**Polling command pattern** — when background work runs in a future,
use a command that reads from a queue with a short timeout:
```clojure
(defn poll-cmd [queue]
  (charm/cmd
   (fn []
     (if-let [event (.poll queue 120 TimeUnit/MILLISECONDS)]
       (translate event)
       {:type :agent-poll}))))  ; timeout → keep polling
```

The update function returns a new poll-cmd on each poll/timeout,
creating a self-sustaining loop that ends when the agent is done.

**Avoiding clojure.core/run! collision** — charm.clj's `run` is a
common function name. Don't name your entry point `run!` to avoid
shadowing `clojure.core/run!`. Use `start!` instead.

### λ Always Require, Never Inline-Qualify in Tests

Using `clojure.string/includes?` or `charm.components.text-input/value`
inline (without a `:require`) compiles fine but triggers clj-kondo
"Unresolved namespace" warnings.  This bit us twice in the same session.

**Rule**: always add the namespace to `:require` with an alias:
```clojure
;; ✗ inline — triggers clj-kondo warning
(is (clojure.string/includes? out "hello"))
(is (= "hi" (charm.components.text-input/value (:input s))))

;; ✓ required + aliased
(:require [clojure.string :as str]
          [charm.components.text-input :as text-input])
(is (str/includes? out "hello"))
(is (= "hi" (text-input/value (:input s))))
```

### λ clojure-lsp Caches Stale Diagnostics via Pi Tool

After editing a file, the pi `clojure_lsp` tool may report warnings that
no longer exist in the source.  Verify with `grep` before chasing phantom
errors:
```bash
grep -n 'clojure\.string/' components/tui/test/psi/tui/app_test.clj
```
If grep finds nothing but clojure-lsp still warns, the cache is stale.

### λ clj-kondo Cache Goes Stale After Refactors

After adding new public vars to a namespace, clj-kondo's `.cache/` still
holds the old snapshot → LSP reports "Unresolved var" for the new fns even
though they compile fine.

**Fix**: re-lint the source directories to rebuild the cache:
```bash
clj-kondo --lint components/query/src components/ai/src ...
```
No flags needed — linting source updates the cache in place.

### λ Avoid Redundant `let` for clj-kondo

clj-kondo warns "Redundant let expression" when a `let` has a single binding
(even if it's map destructuring).  Merge the inner `let` into the outer one
to silence it:

```clojure
;; ✗ triggers warning
(let [ctx (create-context)]
  (let [{:keys [future session]} (stream-response-in ctx ...)]
    @future ...))

;; ✓ merge bindings
(let [ctx                       (create-context)
      {bg :future session :session} (stream-response-in ctx ...)]
  @bg ...)
```

---

## 2026-02-28 - Step 7 Graph Emergence Spec Decisions

### λ Resolve One Open Question Now, Defer One Explicitly

For cross-component work (like Step 7 graph emergence), letting major shape
choices "resolve themselves later" causes drift between spec, code, and UI.
A better pattern:

1. Resolve one structural decision now (needed for current step)
2. Defer one deeper modeling decision intentionally (with explicit placeholder)

Applied to Step 7:
- **Resolved now**: attribute links are implicit edge metadata, not first-class
  attribute nodes
- **Deferred**: mutation side-effects stay IO-only for now (`sideEffects = null`)

This keeps implementation moving without committing too early to a heavier
entity model.

### λ Mirror Spec Decisions Into PLAN.md and STATE.md

When a spec decision affects roadmap shape, record it in:
- `PLAN.md` (what Step N now means)
- `STATE.md` (what is currently true)

This prevents "spec-only truth" where decisions are discoverable only by
reading `.allium` files, and keeps future ψ aligned during execution.

---

## 2026-03-01 - Step 11 Feed-Forward Trigger/Approval Policy

### λ Step 11 Trigger Model: Event-Driven, No Background Cadence

For Step 11, choose **explicit/manual + event-driven hooks** and avoid periodic
background cadence initially.  This keeps recursion deterministic and easier to
reason about while the loop is new.

Implementation guardrail in spec:
- `accepted_trigger_types` defines supported trigger classes
- `enabled_trigger_hooks` defines runtime-enabled subset
- disabled hooks are ignored without creating cycles

### λ Approval Policy: Manual by Default, Trusted Local Opt-In for Low Risk

Keep human approval as default policy.  Allow low-risk auto-approval only when
trusted local mode is explicitly enabled:

- `trusted_local_mode_enabled = true`
- `auto_approve_low_risk_in_trusted_local_mode = true`

This preserves safety-by-default while enabling faster local iteration.


- λ psl source=53082d2cb72c2dbd354c790256f1e48b5663f717 at=2026-03-06T20:57:20.413334Z :: ⚒ Δ Simplify PSL to agent-prompt flow with extension prompt telemetry λ

## 2026-03-07 - Live Extension Reload via nREPL

### λ Reload Pattern for Extensions

Extensions are loaded dynamically (not on classpath), so `require :reload`
doesn't work. The correct live-reload sequence:

```clojure
(let [ctx  (#'psi.agent-session.core/global-context)
      reg  (:extension-registry ctx)
      path "/path/to/extension.clj"]
  (psi.agent-session.extensions/unregister-all-in! reg)
  (psi.agent-session.core/add-extension-in! ctx path))
```

- `unregister-all-in!` clears all registered handlers/tools/commands
- `add-extension-in!` re-evaluates the file and re-runs `init`
- nREPL port for the running psi JVM is 8889 (not 8888, which is Node)

## 2026-03-06 - Extension Run-Fn: Bridging Extensions to the Agent Loop

### λ Extensions Need a Live Runner, Not a Queue Stub

`send-extension-prompt-in!` originally called `prompt-in!` (agent-core only) —
this appended a user message but never triggered an LLM call. PSL prompts were
silently orphaned.

**Pattern**: extensions must not call into agent-core directly for prompts.
They need a runtime-provided `(fn [text source])` that:
1. Prepares the user message (expansion, memory hooks, journal)
2. Resolves API key from session oauth context
3. Calls `run-agent-loop-in!` with `sync-on-git-head-change? true`

The atom (`extension-run-fn-atom`) lives on the session context. The runtime
registers it after bootstrap. Extensions remain decoupled — they call
`psi.extension/send-prompt` and the registered runner does the rest.

**Updated pattern (fcf9db3)**: when a run-fn is present and the session is
streaming, `send-extension-prompt-in!` marks delivery as `:deferred` and still
invokes the runner. The runner waits until idle and then executes the prompt,
so PSL no longer depends on an extra UI prompt to trigger queued work.

`follow-up` remains the fallback only when no run-fn is registered.

## 2026-03-06 - PSL Extension 400: Custom-Type Messages Must Not Reach LLM

### λ Extension Transcript Markers Cause Consecutive-Role 400s

`send-message!` (role "assistant", `:custom-type`) appends a display-only marker
to agent-core history. When PSL fires immediately after, the LLM conversation
sequence becomes `[..., assistant(LLM), assistant(marker), user(prompt)]` —
two consecutive assistant messages → Anthropic 400 Bad Request.

**Fix**: filter `:custom-type` messages in `agent-messages->ai-conversation`
(executor) before building the LLM payload. They remain in agent-core history
for TUI/RPC display, but never reach the provider.

**Pattern**: any message injected into agent-core with `:custom-type` is
display-only. The conversation-rebuild layer is the correct filter point —
not the append site — so history fidelity is preserved for all non-LLM consumers.

## 2026-03-08 - Extension Prompt Contributions: Generic, Ordered Prompt Layer

### λ Split base prompt from runtime prompt to keep extension injection deterministic

A robust extension prompt-injection model needs two layers:

- `:base-system-prompt` — canonical runtime-owned prompt assembly
- `:system-prompt` — effective prompt sent to model (`base + contributions`)

If extensions write directly to `:system-prompt`, ordering and ownership become
fragile (reloads, tool-set changes, and session bootstrap can stomp content).
Keeping a stable base plus a recomputed effective layer makes behavior
predictable and testable.

### λ Prompt contributions should be generic extension capabilities, not domain-specific

The right primitive is a generic `PromptContribution` owned by extension path + id,
not an agent-specific mechanism.  This supports many use cases (agent catalogs,
policy hints, workflow instructions, UI affordances) through one consistent API.

Implemented mutation surface:

- `psi.extension/register-prompt-contribution`
- `psi.extension/update-prompt-contribution`
- `psi.extension/unregister-prompt-contribution`

with deterministic render order by `(priority, ext-path, id)`.

### λ Extension API should expose high-level prompt contribution helpers

Extensions should not need to craft raw mutation calls for prompt layers.
Adding API helpers (`:register-prompt-contribution`, `:update-prompt-contribution`,
`:unregister-prompt-contribution`, `:list-prompt-contributions`) keeps extension
code concise and preserves extension-path scoping automatically.

### λ Extension reload must clear extension-owned prompt layer before re-init

On reload, stale prompt contributions can survive if the registry clears handlers/tools
but leaves contribution state untouched. Clearing `:prompt-contributions` before reload,
then recomputing prompt after extensions re-init, prevents orphaned prompt fragments.

### λ Keep contribution copy concise (token-efficient) and action-oriented

Capability advertisements in system prompt should be compact and operational.
For subagent-widget, concise tool signatures and flow guidance (`create → list → continue/remove`)
communicate enough for tool invocation without bloating context.

## 2026-03-08 - Resolver List Deduplication

### λ Keep domain resolver lists in one place — the domain component

When a component (e.g. `ai`) defines its resolver set, that set belongs
exclusively to the component.  Callers that need to register those resolvers
should use the component's public API (`all-resolvers` var or
`register-resolvers-in!` fn), not re-enumerate the resolvers at the call site.

**Anti-pattern** (what was fixed in commit `f8727db`):
```clojure
;; introspection/core.clj — hand-listing ai resolvers (duplicated)
(register-resolver-if-missing! ai/ai-model-resolver)
(register-resolver-if-missing! ai/ai-model-list-resolver)
(register-resolver-if-missing! ai/ai-provider-models-resolver)
(register-resolver-if-missing! ai/ai-provider-registry-resolver)
```

**Fix**:
1. Make `all-resolvers` public in the owning component (`ai/core.clj`).
2. Callers iterate over it:
```clojure
(doseq [r ai/all-resolvers]
  (register-resolver-if-missing! r))
```

**Why it matters**: the hand-listed form has two copies (global and isolated
registration paths) and both silently drift when a new resolver is added to
the `ai` component.  The single-source form fails loudly (compilation error)
if the var is removed.

### λ `^:private` on a shared collection is a maintenance trap

If a `def` is marked `^:private` but callers in other namespaces need to
iterate it, they will hand-enumerate instead — creating the duplication
described above.  Make the canonical collection public with a doc string
describing its purpose and consumers.

## 2026-03-12 - README as index, `doc/` as user-doc surface

### λ Consolidating on one docs root (`doc/`) reduces drift and broken links

Mixing `docs/` and `doc/` increases link churn and review noise. A single canonical
user-doc root (`doc/`) keeps references stable across README, AGENTS guidance,
and follow-up edits.

### λ Keep top-level README short; move operational detail into focused docs

When README carries deep operational sections (CLI switches, UI workflows,
architecture internals, extension API details), it becomes hard to scan and easy
to stale. Treat README as an entry/index surface and move detailed behavior to
focused docs (`doc/cli.md`, `doc/tui.md`, `doc/emacs-ui.md`,
`doc/architecture.md`, `doc/extension-api.md`, `doc/extensions.md`,
`doc/psi-project-config.md`).

### λ Built-in tool listings and tool-specific contracts can be split intentionally

A concise built-in tools list in README is useful for orientation, but deeper tool
contracts (for example `app-query-tool` usage/discovery flow) should live in a
project-config/reference doc. This preserves quick discoverability while keeping
high-detail guidance maintainable.

## 2026-03-12 - Run lifecycle UX should include explicit removal operations

### λ Workflow-backed lists need symmetric remove affordances in both tool and command surfaces

For `agent-chain`, listing and running were available, but removing stale/finished
runs required indirect cleanup (`reload`/session switch). Adding an explicit remove
path keeps lifecycle operations complete and predictable:
- tool: `agent-chain(action="remove", id="run-<n>")`
- command: `/chain-rm <run-id>`

Symmetry matters: if a capability is exposed to the model via tool schema, operators
should also get a direct slash command path.

### λ Remove must clear both runtime workflow and projection cache

Agent-chain tracks runs in two places:
1. workflow registry (`psi.extension.workflow/*`)
2. extension-local `:runs` cache used for widget projection

A remove operation that only deletes the workflow leaves stale rows in widget output.
`remove-chain-run!` should mutate workflow removal, dissoc local run cache entry, and
refresh widget state in one path.

### λ Text-projected Emacs UI favors command affordances over faux interactivity

Current extension widget API is line-oriented text, not per-line actionable buttons.
In Emacs projection, the stable pattern is to show deterministic text and pair it with
commands (`/subrm`, `/chain-rm`) rather than invent pseudo-click controls. This keeps
the UX consistent with existing projection constraints while still supporting quick run
cleanup.

## 2026-03-13 - PSL follow-up for 578a2a7 should preserve command-backed widget action semantics

### λ "Clickable" in Emacs projection is command metadata over text, not a new interaction primitive

The `578a2a7` subagent-widget change is best treated as convergence on the existing
projection contract: action rows remain text-first and become "clickable" by attaching
explicit command action metadata (`/subrm <id>`), mirroring agent-chain behavior.

This avoids introducing bespoke row-button semantics, keeps render parity with current
widget infrastructure, and preserves one operator mental model for run cleanup across
extensions.
