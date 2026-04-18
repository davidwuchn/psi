Goal: restore `/work-on` to a usable command when invoked from a live session.

Context:
- `/work-on` is documented as a workflow command that creates or reuses a git worktree for a described task and moves work into that workspace context.
- The extension and prior closed task history indicate that `/work-on` is intended to produce a visible workspace transition with a concrete runtime/session effect.
- In current observed behavior, invoking `/work-on` returns nothing and appears to do nothing.

Problem statement:
`/work-on` currently behaves like a no-op from the user’s perspective: no visible result is returned, and no obvious workspace/session transition occurs.

Observed symptom:
- invoking `/work-on <description>` produces no meaningful response
- no visible confirmation/error/usage text is surfaced
- no apparent session/worktree transition follows
- from the user perspective, the command is indistinguishable from being ignored

Why this is a problem:
- `/work-on` is a workflow command, not an optional hidden internal hook; it must produce an observable outcome
- if the command succeeds, the user needs to see what changed: branch/worktree/session/context
- if the command fails, the user needs a concrete error
- if the command is unavailable, misregistered, or blocked by routing/state issues, that must also be visible
- a silent no-op breaks trust in command execution and makes it impossible to tell whether the problem is command dispatch, extension registration, worktree creation, session switching, or result delivery

Scope of the task:
- diagnose why `/work-on` currently returns nothing
- determine whether the regression is in:
  - command registration/discovery
  - command dispatch/routing
  - extension handler execution
  - result/message delivery
  - session/worktree mutation paths
  - adapter projection/update after command completion
- restore a user-visible, diagnosable contract for `/work-on`

Relevant prior context:
- there is prior closed task history for `/work-on` transcript/worktree-state coherence
- that history suggests `/work-on` previously had explicit intended behavior around transcript evidence and session worktree updates
- the current symptom may be a regression in that path or a new break elsewhere in command execution/result projection

Required outcome at the task level:
- `/work-on` must no longer appear to do nothing
- invoking `/work-on <description>` must yield an observable result:
  - either a successful visible workspace/session transition summary
  - or a clear failure/usage/error outcome
- the runtime-visible effects of the command and the user-visible reporting of the command must agree

Non-goals:
- do not redesign the entire worktree workflow surface in this task
- do not broaden the task into all workflow commands unless shared root cause evidence requires it
- do not accept silent success or silent failure as valid behavior
- do not patch only documentation; this is a runtime behavior problem

Acceptance:
- `/work-on <description>` produces a visible result in the user-facing command surface
- when successful, the command causes the intended worktree/session transition and reports it clearly
- when unsuccessful, the command returns a clear error instead of silence
- tests cover the regression so future no-op behavior is caught
