# 037 — Scheduler-triggered fresh top-level sessions with explicit session-config

## Goal

Extend the scheduler so a scheduled item can create and invoke a fresh top-level session using explicit session configuration.

## Context

The current scheduler surface intentionally only supports delayed prompt injection back into the creating session. That was the right v1 cut, and task `033` explicitly left **scheduled creation of new sessions** as a future direction.

Since then, psi has gained richer and more intentional session-shaping surfaces:
- scheduler already models delayed intent and canonical delivery timing
- top-level and child-session execution paths already support prompt/config shaping
- workflow and agent-tool follow-on work rely on explicit configuration rather than ambient session inference

The scheduler still cannot use those ideas to create a fresh session later and run work there. This blocks delayed/background patterns such as:
- "in 10 minutes, start a fresh helper session with these tools/skills/model settings"
- "tomorrow morning, create a new session with a specific prompt stack and run this task there"
- "schedule detached follow-on work instead of waking the current session"

## Problem statement

Scheduler-triggered work is currently constrained to "wake the same session later with a message".
That is too narrow for delayed work that should run in a separate fresh session with its own execution config.

The missing capability is specifically:
- create a fresh top-level session at fire time
- shape that session with an explicit validated config payload
- submit the scheduled prompt to that new session through the canonical prompt path
- preserve provenance back to the originating schedule/session

Without this, delayed isolated execution requires awkward choreography instead of using the runtime's canonical session-creation and prompt-submission machinery.

## Intent

Add a scheduler action that can create a fresh top-level session and prompt it, with explicit `:session-config` and stable scheduler semantics.

## Decisions

The following design decisions are fixed for this task:

1. **Fresh top-level session, not child session**
   - `:kind :session` creates a new top-level session
   - it is not a child session and should not imply parent/child execution lineage

2. **Explicit action kind**
   - scheduler create uses an explicit `:kind`
   - initial kinds are `:message` and `:session`

3. **Nested config payload**
   - the fresh-session kind carries nested `:session-config`
   - `:session-config` is a validated subset, not an open-ended arbitrary metadata bag

4. **No focus switching**
   - scheduled fresh-session creation never switches the active/focused session automatically

5. **Provenance is persisted**
   - the created session records explicit provenance linking it back to the originating schedule and origin session

## In scope

- add a scheduler kind for creating and invoking a fresh top-level session at fire time
- define and validate the v1 `:session-config` subset for scheduled fresh-session creation
- define how the scheduler stores and projects the richer scheduled action shape
- ensure fire-time delivery uses canonical session creation and canonical prompt submission paths
- preserve origin-session provenance on both the schedule and the created session
- expose the new scheduled kind coherently through `psi-tool` scheduler create/list/cancel
- update EQL/background-job/public projections as needed so scheduled fresh-session items remain introspectable
- define focused acceptance criteria for config carriage, session creation, prompt submission, and failure semantics

## Out of scope

- recurring schedules
- persistence across process restarts
- creating child sessions through the scheduler
- automatic session selection/focus switching on fire
- arbitrary existing-session targeting
- inventing a scheduler-only session configuration model
- broad scheduler-native workflow semantics beyond "create configured session, then prompt it"
- redesigning the general session subsystem

## Minimum concepts

### 1. Scheduled kind
The scheduler distinguishes at least two kinds:
- `:message` — delayed prompt injection into the originating session
- `:session` — delayed creation of a fresh top-level session, then prompt submission there

This keeps existing behavior intact while making the new capability explicit.

### 2. Session config payload
The `:session` kind carries nested `:session-config`.
This payload is a validated subset of canonical session configuration for top-level session creation.
It is not an unbounded arbitrary config map.

Semantically, `:session-config` should be treated as **overrides on canonical fresh top-level session initialization**, not as a scheduler-specific parallel session model.
The subset should be drawn from the overlap between canonical new-session state and the existing prompt/config shaping fields already used elsewhere.

Initial v1 subset is explicitly:
- `:session-name`
- `:system-prompt`
- `:model`
- `:thinking-level`
- `:skills`
- `:tool-defs`
- `:developer-prompt`
- `:developer-prompt-source`
- `:preloaded-messages`
- `:cache-breakpoints`
- `:prompt-component-selection`

Initial v1 exclusions are explicitly:
- `:workflow-run-id`
- `:workflow-step-id`
- `:workflow-attempt-id`
- `:workflow-owned?`
- `:extensions`
- `:prompt-templates`
- `:prompt-contributions`
- `:scoped-models`
- `:context-tokens`
- `:context-window`
- `:tool-output-overrides`
- `:ui-type`
- `:auto-retry-enabled`
- `:auto-compaction-enabled`
- `:nucleus-prelude-override`
- `:prompt-mode`
- `:system-prompt-build-opts`
- fields whose meaning depends on child-session lineage
- fields that imply adapter-local focus or active-session mutation
- open-ended unknown keys

This split keeps the v1 surface focused on high-value execution shaping while avoiding broader runtime-policy or lineage semantics.
### 3. Prompt payload
A scheduled fresh session still needs the text to run after creation.
The scheduler continues to use `:message` as the prompt content field for both kinds:
- for `:message`, it is injected into the originating session
- for `:session`, it is submitted to the created fresh session

### 4. Provenance model
The schedule remains owned by/scoped to the originating session.
The created session is a normal fresh top-level session in the same context/worktree, but it records provenance back to:
- the originating session
- the schedule id
- optionally the schedule label

Recommended created-session provenance fields are:
- `:scheduled-origin-session-id`
- `:scheduled-from-schedule-id`
- `:scheduled-from-label`

This is provenance, not child-session lineage.

## Architectural fit

This should follow existing psi architecture:
- scheduler owns delayed intent and timing
- dispatch/runtime own state mutation and side effects
- fresh session creation must flow through canonical session creation helpers/handlers
- prompt submission into the created session must flow through the canonical prompt path
- public scheduler projections remain projections of canonical scheduler state

Most importantly, this task should not invent a parallel session-creation or prompt-execution path.
The current user-facing fresh-session path includes active-session switching semantics; scheduler delivery needs the same initialization machinery through a distinct non-switching contract.

## Public scheduler surface

### Canonical kinds
- `:message`
- `:session`

### psi-tool scheduler ops
The top-level scheduler ops remain:
- `create`
- `list`
- `cancel`

This task extends `create` with explicit kind-aware semantics while keeping the same top-level scheduler operation model.

### Conceptual create shape

```clojure
{:action "scheduler"
 :op "create"
 :kind :message | :session
 :delay-ms ... ;; or :at ...
 :label "..."
 :message "..."
 ;; only for :session
 :session-config {...}}
```

Wire encoding can still be constrained by the tool surface, but semantically the canonical kind values are keywords.
Tool input may accept string forms such as `"message"` and `"session"`, but result payloads and internal/public semantic projections should use keyword values.

### Validation rules

#### `:kind :message`
Required:
- `:message`
- one of `:delay-ms` or `:at`

Forbidden:
- `:session-config`

#### `:kind :session`
Required:
- `:message`
- `:session-config`
- one of `:delay-ms` or `:at`

Validation requirements:
- `:kind` is required explicitly; no inference from field presence
- `:session-config` must conform to the supported subset
- unsupported or unknown fields error explicitly
- `list` and `cancel` do not need kind-specific request fields, but their result summaries must preserve schedule kind

### psi-tool projection policy
Default `psi-tool` scheduler projections should be compact and kind-aware.

For all schedule summaries, include at least:
- `:schedule-id`
- `:kind`
- `:label`
- `:status`
- `:origin-session-id`
- `:fire-at`
- truncated message preview

For `:kind :session`, include a `:session-config-summary` rather than full `:session-config`.

Recommended `:session-config-summary` fields are:
- `:session-name`
- `:model`
- `:thinking-level`
- `:skill-count`
- `:tool-count`
- `:has-system-prompt?`
- `:has-developer-prompt?`
- `:preloaded-message-count`
- `:has-prompt-component-selection?`

Default scheduler create/list/cancel projections should avoid returning full prompt/config payloads unless a future dedicated inspection surface is added intentionally.
## Fresh-session creation semantics

For `:kind :session`, when the schedule fires:

1. resolve the originating session and its canonical context/worktree
2. create a fresh top-level session in that same context/worktree through a canonical **non-switching top-level session creation** path
3. apply the validated `:session-config`
4. persist schedule provenance on the created session
5. if `:session-config` includes `:preloaded-messages`, seed them onto the created session before prompt submission
6. submit the scheduled `:message` to that created session through the canonical prompt path
7. record delivery outcome on the schedule

### Important invariants
- the created session is top-level, not child
- the created session never becomes active/focused automatically due to scheduler delivery
- originating session busy/idle state must not block `:kind :session` fire-time delivery
- no scheduler-specific prompt/session lifecycle bypass is introduced
- scheduled fresh-session delivery must use a canonical non-switching top-level creation path, not a switching helper followed by focus restoration or switch rollback
- shared fresh-session initialization logic should be reused; scheduler-specific reimplementation of top-level session bootstrapping is not the desired architecture

## Delivery and queueing semantics

### `:kind :message`
Existing scheduler semantics remain intact, including any current queueing behavior when the originating session is busy.

### `:kind :session`
The fresh-session kind should not queue behind the originating session's busy state.
Once its timer fires, it should attempt fresh-session creation immediately because it targets a separate new top-level session.

## Schedule state and failure semantics

The current schedule lifecycle should be extended as needed to represent fresh-session delivery accurately.
Terminal failure must be representable as an explicit status.

Expected statuses:
- `:pending`
- `:queued` (where relevant for `:message`)
- `:delivered`
- `:cancelled`
- `:failed`

`:failed` is the canonical terminal non-success status for schedules that fired but did not complete their intended action.
A schedule that fails to complete must not be projected as `:delivered` with only an attached error payload.

For `:kind :session`, failure reporting should preserve phase information such as:
- `:create-session`
- `:prompt-submit`

Partial success must be representable:
- if fresh-session creation succeeds but prompt submission fails, the schedule records `:failed`
- the created session id remains recorded on the schedule
- the error summary identifies the failing phase

## Provenance persistence

### On schedule records
The schedule should preserve enough information to identify:
- `:origin-session-id`
- schedule id
- schedule kind
- `:created-session-id`, when available
- `:delivery-phase`, when relevant
- `:error-summary`, when relevant
- failure phase and/or delivery outcome summary

### On created sessions
The created session should persist scheduler provenance fields conveying at least:
- `:scheduled-origin-session-id`
- `:scheduled-from-schedule-id`
- optionally `:scheduled-from-label`

These field names should be queryable and stable.

## Projection and introspection policy

Scheduled fresh-session items must remain introspectable without dumping oversized payloads by default.

### List/summary surfaces should include
- `:kind`
- `:schedule-id`
- `:label`
- `:status`
- `:fire-at`
- `:origin-session-id`
- `:created-session-id` when present
- selected `:session-config` summary fields such as:
  - `:session-name`
  - `:model`
  - `:thinking-level`
  - counts or summaries for skills/tools if helpful
- truncated message preview

### List/summary surfaces should avoid by default
- full system prompt bodies
- full developer prompt bodies
- full preloaded message vectors
- full tool-def payloads

If richer inspection is needed later, it should be exposed intentionally rather than by overloading default list projections.

## EQL and public attribute naming

Current scheduler projections use a narrow prompt-only shape centered on attrs such as:
- `:psi.scheduler/schedule-id`
- `:psi.scheduler/label`
- `:psi.scheduler/message`
- `:psi.scheduler/source`
- `:psi.scheduler/created-at`
- `:psi.scheduler/fire-at`
- `:psi.scheduler/status`
- `:psi.scheduler/session-id`

This task should evolve that public shape explicitly rather than overloading existing attrs ambiguously.

### Recommended canonical public attrs

Retain:
- `:psi.scheduler/schedule-id`
- `:psi.scheduler/label`
- `:psi.scheduler/message`
- `:psi.scheduler/source`
- `:psi.scheduler/created-at`
- `:psi.scheduler/fire-at`
- `:psi.scheduler/status`

Add:
- `:psi.scheduler/kind`
- `:psi.scheduler/origin-session-id`
- `:psi.scheduler/created-session-id`
- `:psi.scheduler/delivery-phase`
- `:psi.scheduler/error-summary`
- `:psi.scheduler/session-config-summary`

### Naming decisions

- Prefer `:psi.scheduler/origin-session-id` over reusing `:psi.scheduler/session-id` in new public projections. Once schedules can create other sessions, `session-id` becomes ambiguous.
- Use `:psi.scheduler/created-session-id` only when a `:kind :session` delivery has created a fresh session.
- Use `:psi.scheduler/delivery-phase` for delivery lifecycle detail such as `:create-session` and `:prompt-submit`.
- Use `:psi.scheduler/error-summary` for compact public failure information; richer internal throwable data should remain internal unless a dedicated inspection surface is added.
- Use `:psi.scheduler/session-config-summary` for public scheduler summaries rather than exposing full `:session-config` by default.

### Compatibility guidance

- Existing prompt-only scheduler attrs can remain during the evolution, but the canonical public meaning should move toward the expanded shape above.
- If `:psi.scheduler/session-id` remains temporarily for compatibility, its meaning must be documented explicitly and should converge toward `:psi.scheduler/origin-session-id` or be retired.
- Resolver output, psi-tool summaries, and background-job projections should all converge on the same public naming concepts even when each surface presents a different subset.

## Design options considered

### Option 1 — overload current scheduled message shape with extra metadata
Store `:session-config` on today's scheduled message record and infer fresh-session behavior from field presence.

Benefits:
- smaller surface change

Costs:
- makes action intent implicit
- weakens validation
- muddies list/projection semantics
- encourages accidental scheduler shape drift

Conclusion:
- not preferred

### Option 2 — explicit scheduler kind with dedicated fresh-session semantics
Represent fresh-session creation as `:kind :session`, distinct from `:kind :message`, while keeping the top-level scheduler ops (`create|list|cancel`) the same.

Benefits:
- explicit behavior
- better validation
- clearer public projections
- preserves current delayed-message behavior intact
- leaves room for future scheduler growth without overloading the existing record shape
- makes it natural to introduce a canonical non-switching top-level creation path rather than reusing a switching helper incorrectly

Costs:
- slightly richer scheduler state and summary model
- requires a clearer separation between switching and non-switching fresh-session creation contracts

Conclusion:
- preferred

## Desired shape

After this task:
- scheduler create supports `:kind :message` and `:kind :session`
- `:kind` is explicit and required on create
- `:kind :session` creates a fresh top-level session, never a child session
- `:session-config` is a validated explicit subset
- default scheduler projections are compact and expose `:session-config-summary` rather than full session config for `:kind :session`
- the created session never steals active/focused session state automatically
- scheduler fire-time behavior reuses canonical session-creation and prompt-submission paths
- fresh-session delivery uses a canonical non-switching top-level creation path distinct from user-facing switching creation
- schedules can terminate as `:failed` with explicit delivery-phase/error reporting
- schedules and created sessions both preserve explicit provenance
- list/cancel/introspection clearly distinguish the two kinds
- existing delayed self-message behavior remains intact

## Acceptance criteria

- scheduler supports a new delayed `:kind :session` action that creates and invokes a fresh top-level session
- `:kind` is explicit and validated; no inference from field presence
- `:kind :session` requires `:session-config`
- `:session-config` is validated against the supported v1 subset and rejects unsupported keys explicitly
- fire-time delivery uses canonical session creation code rather than bespoke scheduler-only creation logic
- after session creation, prompt submission uses the canonical prompt path
- scheduled `:kind :session` creation uses a canonical non-switching top-level creation path rather than a switching helper plus rollback/focus restoration
- created sessions never auto-switch active/focused session
- the originating active session remains unchanged before and after scheduled fresh-session creation
- `:kind :session` delivery is not blocked by the originating session's busy state
- schedules and created sessions both preserve scheduler provenance
- scheduler records/projections distinguish delayed self-message items from delayed fresh-session items
- canonical public projections expose explicit attrs for schedule kind, origin session, created session, delivery phase, and failure summary
- `psi-tool` scheduler create/list/cancel support the new kind coherently
- scheduled fresh-session items remain introspectable through graph/background-job/public surfaces at an intentional summary level
- tests prove config carriage into created session state, prompt submission routing, provenance persistence, projection naming/shape, unchanged active-session behavior, partial failure behavior, and non-regression of existing schedule behavior

## Constraints

- preserve scheduler volatility semantics (no restart persistence)
- preserve explicit, stable public semantics over implicit inference
- prefer one obvious session-config vocabulary rather than a scheduler-only variant
- do not introduce a parallel prompt/session lifecycle bypass
- do not make the fresh-session kind silently mutate UI focus/selection
- avoid dumping large opaque payloads in default projections

## Notes

This is a follow-on to the future-direction identified in task `033`, but the design is now specifically grounded in scheduled creation of fresh top-level sessions with explicit `:session-config`, not delayed child-session creation.