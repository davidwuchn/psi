# Learning

Accumulated discoveries from ψ evolution.

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

## 2026-03-08 - Prompt contributions should carry the catalog, not just the capability name

### λ A tool schema tells the model what arguments to supply; a prompt contribution tells it what values are valid

`agent_chain` tool schema advertises `action`, `chain`, and `task` parameters.
But the schema alone cannot enumerate valid `chain` values — those come from
`.psi/agents/agent-chain.edn` at runtime. Without the catalog in the system
prompt, the model must call `action="list"` as a discovery step before every
`action="run"` call. That is an avoidable round-trip.

The prompt contribution closes this gap:
```
tool: agent_chain
available chains:
- plan-build-review: Plan, build, and review code changes
- prompt-build: Build prompts
```

The model can now select the correct chain name directly from context.

### λ Contribution content is runtime state — it must be kept in sync with the underlying data

Registering a static contribution at init is insufficient if the underlying
data can change. `agent_chain` chains can be reloaded via `action="reload"` or
on session switch. Every path that mutates chain definitions must call
`sync-prompt-contribution!` immediately after. Missing one update path leaves
the model working from a stale catalog.

Sync points for agent_chain:
1. `init` — initial registration
2. `action-reload` — after disk rescan
3. `session_switch` event — after session-scoped rescan

### λ Priority and section placement are part of the contribution contract

`priority=200` places agent_chain below subagent-widget (`priority=250`) in
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
With: `agent_chain(action="run", chain="<name>", task="...")`

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
- `agent_chain(action="run", chain=..., task=...)` — run
- `agent_chain(action="list")` — inspect
- `agent_chain(action="reload")` — reset

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
