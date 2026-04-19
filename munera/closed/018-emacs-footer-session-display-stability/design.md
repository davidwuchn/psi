Goal: stabilize the session display in the Emacs footer so the session list remains present consistently instead of disappearing intermittently and reappearing later.

Context:
- The current Emacs footer session display is semantically correct when it appears.
- The problem is not primarily incorrect content; it is unstable presence/visibility.
- Users observe that displayed sessions often disappear and later reappear.
- This suggests a projection, event ordering, rendering, visibility, or state ownership problem rather than a pure formatting problem.
- Recent convergence work has already moved more shared UI semantics into backend/app-runtime ownership, including structured footer fields and shared session summary fragments.
- The footer is therefore a shared semantic surface where backend ownership and Emacs rendering responsibilities must remain clearly separated.

Problem statement:
The Emacs footer does not reliably display session information continuously. The same session display can be correct at one moment and absent at another, which makes the footer feel flaky and reduces trust in session visibility.

Desired outcome:
- Session display in the Emacs footer is stable across normal session/runtime updates.
- Once session information is available, it should not disappear unless there is a real semantic reason for it to be absent.
- Rendering should remain correct when state changes, rerenders occur, sessions switch, or related UI updates arrive.
- The fix should preserve canonical backend/app-runtime ownership of shared footer semantics where appropriate.

Scope:
This task covers investigation and repair of intermittent disappearance of the session display in the Emacs footer.

In scope:
- tracing the data path for footer session information from backend/app-runtime projection through RPC/event payloads into Emacs rendering
- identifying whether the disappearance is caused by missing backend data, event sequencing, frontend state replacement, conditional rendering, stale caches, or rerender boundary issues
- implementing the minimal fix that makes footer session display stable
- adding proof in tests at the level(s) where the regression actually lives

Out of scope unless investigation proves otherwise:
- broad redesign of footer UX
- unrelated footer formatting changes
- unrelated session tree or header rendering changes
- speculative restructuring beyond what is required to restore stable display

Design constraints:
- Prefer one clear ownership path for footer session display semantics.
- If backend/app-runtime already owns the canonical semantic fragments, Emacs should render those fragments rather than reconstructing or conditionally suppressing them unnecessarily.
- Avoid introducing new compatibility branches unless strictly required.
- Preserve explicit-session-routing and canonical shared-projection direction.
- Keep the change narrowly focused on the disappearing/reappearing behavior.

Investigation questions:
- Is the footer session display absent because backend-projected footer/session data is genuinely missing in some updates?
- Is Emacs replacing a richer footer model with a partial payload during certain event sequences?
- Are some updates clearing or overwriting cached footer/session state unintentionally?
- Is the render path incorrectly treating empty/partial intermediate state as authoritative and removing the session display?
- Are context/session/session-summary/footer update events arriving in an order that exposes a transient blanking bug?
- Is the session display being gated by visibility conditions that are too aggressive?

Acceptance:
- A munera task exists for the Emacs footer session-display instability.
- The task clearly describes that the display is correct when present but intermittently absent.
- Placeholder `plan.md` and `steps.md` exist for later refinement.
- The task is ready for later investigation/implementation without overcommitting to a guessed root cause.
