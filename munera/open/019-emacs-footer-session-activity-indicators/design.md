Goal: improve the session display in the Emacs footer so it clearly shows which sessions are active and which are idle.

Context:
- The Emacs footer already presents session information, but it does not yet make session activity state visible enough for quick scanning.
- Psi already has canonical notions of session activity/streaming state in shared app-runtime projections and context/session summaries.
- The footer is a compact status surface, so any activity indicator must be concise, stable, and derived from canonical backend semantics rather than adapter-local guesswork.
- This task is distinct from footer session-display stability: that task is about intermittent disappearance, while this task is about improving the semantic content of the footer when it is shown.

Problem statement:
The current Emacs footer session display does not clearly indicate which sessions are active and which are idle. Users need to be able to glance at the footer and distinguish sessions that are currently doing work from sessions that are present but inactive.

Desired outcome:
- The Emacs footer session display distinguishes active sessions from idle sessions clearly.
- The indicators are compact enough for footer use.
- The indicators are based on canonical shared session/activity semantics.
- The rendering remains understandable when multiple sessions are present.

Scope:
This task covers improving the Emacs footer session display to include visible active-vs-idle state.

In scope:
- deciding the canonical semantic source for active/idle state
- deciding how that state should be represented in the footer
- implementing the footer rendering change
- adding tests proving the intended display behavior

Out of scope unless later required:
- broader footer redesign
- unrelated session tree/header activity rendering
- speculative new activity states beyond what the runtime canonically exposes

Design constraints:
- Prefer backend/app-runtime-owned activity semantics over Emacs-local inference.
- Keep the footer representation terse and legible.
- Avoid duplicating session-state derivation logic in Emacs if canonical fragments already exist or can be extended minimally.
- Distinguish this task from task 018; both may touch nearby code, but the goals are separate.

Investigation/design questions:
- Does the footer already receive enough canonical session activity information, or should shared projection data be extended?
- Should the footer show only active sessions distinctly, or explicitly label both active and idle sessions?
- What compact representation best fits the existing footer constraints?
- How should streaming/current-session/selected-session semantics relate to active-vs-idle indicators if they differ?

Acceptance:
- A munera task exists for improving Emacs footer session activity visibility.
- The task clearly states the goal of distinguishing active and idle sessions in the footer.
- The task is framed as a semantic/display improvement rather than a stability fix.
- Planning and execution surfaces exist for later refinement.
