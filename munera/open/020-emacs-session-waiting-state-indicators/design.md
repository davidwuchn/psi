Goal: make Emacs session state indicators unambiguous so users can tell which session is current, which session is actively running, and which sessions are waiting for user input.

Context:
- The current session tree and footer session activity surfaces conflate or blur three distinct concepts:
  - current/focused session in the UI
  - actively running/streaming session
  - idle session that is waiting for user input
- Today the session tree uses `← active` for the currently selected session, even when that session is idle. This is semantically misleading because users reasonably read `active` as runtime activity.
- Today the footer session activity line groups sessions as `active` vs `idle`, where `idle` currently means only `not streaming`, not necessarily canonical `phase :idle` / waiting for input.
- Psi already has canonical backend session phase/state semantics:
  - `:idle` — ready for user input
  - `:streaming` — actively running
  - `:compacting` — background compaction in progress
  - `:retrying` — retry workflow in progress
- Emacs already supports face-based rendering via dedicated `defface` definitions.

Problem statement:
Users need the Emacs session surfaces to answer, at a glance:
1. Which session is the current one I am looking at?
2. Which sessions are actively doing work right now?
3. Which sessions are idle and ready for my next input?
4. What state are the other sessions in, without any sessions disappearing from the status summary?

The current wording does not answer those questions cleanly because `active` is overloaded, and the current visual emphasis does not prioritize the sessions that most need user attention.

Desired outcome:
- The current-session marker is distinct from runtime activity.
- Waiting-for-input is shown explicitly from canonical backend semantics, not inferred as merely `not streaming`.
- Running/streaming remains visible.
- All sessions are represented in the footer status summary; no canonical phase is silently dropped.
- Idle/waiting sessions are visually more prominent than running sessions, because they are the ones that need user attention next.
- Color may be used as reinforcement, but text labels must remain sufficient without color.
- Footer and session-tree wording are aligned enough that users do not need to reinterpret the same term differently in different places.

Scope:
This task covers the design and implementation of clearer session-state indicators in Emacs-facing session surfaces.

In scope:
- renaming the current-session marker in the session tree
- defining canonical semantics for user-facing runtime state labels
- deciding how the footer session-activity line should describe all session states
- deciding whether session-tree rows should show explicit runtime badges
- using Emacs faces/color to direct attention toward waiting sessions
- tests proving the chosen wording/semantics

Out of scope unless later required:
- broad redesign of the entire footer/projection layout
- inventing new runtime phases beyond the canonical statechart
- TUI-specific rendering changes unless they naturally share backend semantics later

Design constraints:
- Backend/shared runtime semantics are authoritative; Emacs should not invent alternate meanings locally.
- `current` and runtime activity must not share the same word.
- `waiting` should mean truly ready for user input, i.e. canonical `phase :idle`.
- Text must stand on its own; color cannot be the only carrier of meaning.
- The compact footer line must remain readable with multiple sessions.
- Visual emphasis should prioritize the next-user-action signal, not merely system motion.
- The footer must account for every session in the context; no state may vanish from the summary because it is inconvenient to classify.
- Emacs must not recover state semantics by regex-parsing human-rendered text if structured backend/shared data can carry those semantics directly.

Canonical user-facing vocabulary:
- `← current` = the session currently selected/focused in Emacs
- `[waiting]` = canonical `phase :idle`
- `[running]` = canonical `phase :streaming`
- `[retrying]` = canonical `phase :retrying`
- `[compacting]` = canonical `phase :compacting`

Naming decision:
- Use `running` in user-facing UI text.
- Keep `streaming` as an internal/runtime term.
- Rationale:
  - `running` is more user-comprehensible than `streaming`
  - `waiting` / `running` are a clearer pair than `idle` / `streaming`

Refined semantic model:

1. Session identity vs runtime state are separate axes
- Session identity axis:
  - `← current` means this is the session currently selected/focused in Emacs.
- Runtime-state axis:
  - `[waiting]` means canonical `phase :idle`
  - `[running]` means canonical `phase :streaming`
  - `[retrying]` means canonical `phase :retrying`
  - `[compacting]` means canonical `phase :compacting`
- A session may show both identity and runtime state at once.
  - Example: `← current [waiting]`

2. Canonical source of truth
- Use backend/shared phase semantics as the single source of truth.
- State mapping for this slice:
  - `:idle`       -> `waiting`
  - `:streaming`  -> `running`
  - `:retrying`   -> `retrying`
  - `:compacting` -> `compacting`
- The existing boolean `:is-streaming` remains useful, but it is not sufficient to derive waiting state.
- This slice must derive waiting from canonical phase, not from `not is-streaming`.

3. Coverage requirement for all sessions
- The footer status summary must represent all sessions in the context.
- Therefore:
  - no session may be omitted from the footer grouped line solely because its phase is not `:idle` or `:streaming`
  - `:retrying` and `:compacting` sessions must remain visible as such
- The session tree should also show a runtime badge for every canonical phase in this slice, so no session appears state-less when it is in a meaningful non-idle phase.

Proposed textual contract:

Session tree:
- rename `← active` to `← current`
- append explicit runtime badges derived from canonical phase
- badge vocabulary:
  - `[waiting]`
  - `[running]`
  - `[retrying]`
  - `[compacting]`
- examples:
  - `Refining Emacs footer waiting indicators [ed4bf4ec] — 22:42 / 22:57 — /repo ← current [waiting]`
  - `auto-session-name [b1812e15] — 22:48 / 22:48 — /repo [running]`
  - `helper [abcd1234] — 22:58 / 22:59 — /repo [retrying]`

Footer session activity line:
- stop using `idle` to mean `not streaming`
- use phase-derived buckets instead
- preferred wording:
  - `waiting ...`
  - `running ...`
  - `retrying ...`
  - `compacting ...`
- examples:
  - `sessions: waiting rename-main, notes · running auto-session-name`
  - `sessions: waiting main · retrying helper · compacting summarize`
- bucket order:
  1. `waiting`
  2. `running`
  3. `retrying`
  4. `compacting`
- rationale:
  - waiting sessions need the next user action
  - running sessions are useful ongoing activity
  - retrying is more time-sensitive/user-relevant than compacting
  - compacting is still visible but lower-priority

Visual hierarchy and faces:

Principle:
- waiting sessions should be more visible than running sessions, because they are the ones that need user attention.
- current/focus should be visible, but should not visually overpower waiting state.
- all runtime badges should remain readable without color.

Therefore:
- text remains primary
- faces reinforce priority, with `waiting` receiving stronger emphasis than `running`

Face priority:
1. `[waiting]` — strongest non-error emphasis
2. `← current` — clear but subtler than waiting
3. `[running]` — visible but less prominent than waiting
4. `[retrying]` / `[compacting]` — visible but not stronger than waiting

Explicit precedence rule:
- If a session is both current and waiting, `[waiting]` should visually dominate `← current`.
- Rationale:
  - `current` is orientation
  - `waiting` is the next-action signal

Preferred initial face split:
- add dedicated faces for session-state fragments in Emacs rather than relying only on generic footer/status faces
- likely new faces:
  - `psi-emacs-session-current-face`
  - `psi-emacs-session-waiting-face`
  - `psi-emacs-session-running-face`
  - `psi-emacs-session-retrying-face`
  - `psi-emacs-session-compacting-face`
- apply them to badge/marker fragments, not necessarily to entire lines, so labels remain scannable without over-coloring the projection block

Color policy:
- Yes, we can use color.
- Yes, we should use color/faces here.
- But color is secondary to words and ordering.
- Avoid red unless indicating failure/error.
- Avoid making `running` the hottest/highest-contrast state; the emphasis should instead pull attention to `waiting`.
- Faces should inherit from theme-friendly existing faces so they remain readable in light and dark themes.
- Meaning must remain understandable if faces are disabled or muted by theme choice.

Rendering rules:

1. Session tree rows
- Always use `← current` for selected/focused session.
- Always show a runtime badge for canonical runtime phase.
- If a session is both current and waiting, show both:
  - `← current [waiting]`
- If a session is current and running, show both:
  - `← current [running]`
- If a session is current and retrying/compacting, show both accordingly.
- Badge order:
  - identity marker first, runtime badge second
  - rationale: establish “which row is current” before “what state is it in”

2. Footer activity line
- Build compact grouped buckets from canonical phase-derived classifications.
- Bucket order:
  1. `waiting`
  2. `running`
  3. `retrying`
  4. `compacting`
- Within a bucket, preserve existing parent-first/stable ordering rules.
- Omit empty buckets.
- Show no session-activity line when there is only one session; this is intentional to preserve footer compactness, with the single session’s state instead communicated via the session tree/header/session status surfaces.

3. Single-session behavior
- The absence of a grouped multi-session footer line for a single session is intentional, not accidental.
- Single-session runtime state still needs to be visible through existing focused-session surfaces and any tree row if present.
- This task does not require inventing a separate one-session footer summary line.

Implementation direction:

1. Shared/backend semantics first
- shared/backend projections should classify sessions from canonical phase
- Emacs should consume shared/backend semantics and render them, not independently derive meanings from local heuristics

2. Structured rendering seam
- Do not require Emacs to regex-parse rendered strings like `[waiting]` or `sessions: waiting ...` in order to apply faces.
- Prefer extending backend/shared payloads with structured state fragments so Emacs can style badge fragments and grouped footer buckets directly.
- Acceptable shapes include either:
  - per-session canonical state attrs on context snapshot/session-tree inputs, and/or
  - structured footer activity buckets alongside a plain canonical text line
- The exact payload shape can be decided in implementation, but fragment-level styling must not depend on reparsing presentation text.

3. Context/session data requirement
- Current shared context snapshot only carries `:is-streaming` and does not fully expose canonical phase for every session.
- This task therefore likely requires extending shared context/session projection data so all sessions can carry either:
  - canonical `:phase`, or
  - canonical derived state label (`waiting` / `running` / `retrying` / `compacting`)
- Footer projection should be driven from the same canonical per-session state source so tree and footer cannot drift semantically.

Example renderings:

Single current waiting session in tree:
- `main [abcd1234] — 22:42 / 22:57 — /repo ← current [waiting]`

Current running child plus waiting parent:
- `main [abcd1234] — 22:42 / 22:57 — /repo [waiting]`
- `  helper [efgh5678] — 22:58 / 22:58 — /repo ← current [running]`

Footer with mixed states:
- `sessions: waiting main, notes · running helper`

Footer when all sessions are waiting:
- `sessions: waiting main, helper, notes`

Footer with all canonical phase groups represented:
- `sessions: waiting main · running helper · retrying summarize · compacting prune-history`

Acceptance:
- The design distinguishes current-session identity from runtime activity.
- Waiting-for-input is defined as canonical `phase :idle`, not `not streaming`.
- All sessions remain represented in the footer summary; no canonical phase is silently dropped.
- The user-facing vocabulary is settled as `current` / `waiting` / `running` / `retrying` / `compacting`.
- Idle/waiting sessions receive more visual emphasis than running sessions.
- The precedence between `← current` and `[waiting]` is explicit: waiting is visually stronger.
- The design remains text-legible without color, while explicitly permitting faces/color as reinforcement.
- The design commits to structured backend/shared semantics for fragment styling rather than frontend reparsing of rendered text.
- The design is concrete enough to implement with backend/shared semantics plus Emacs rendering changes.