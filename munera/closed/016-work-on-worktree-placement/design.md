Goal: design the canonical worktree placement rule for `/work-on` across the supported repository layouts before changing implementation.

Context:
- `/work-on <description>` creates or reuses a git worktree for a task and moves work into that workspace context.
- Current behavior assumes one placement rule: create the new worktree as a sibling of the main checkout.
- That rule is too weak because the repository root container and the main checkout are not always the same directory.
- We currently need to support at least these two layouts:

  1. sibling-main layout
     - `project/bare-checkout`
     - `project/worktree-a`
     - `project/worktree-b`

  2. nested-linked layout
     - `project/` is the main checkout
     - `project/worktree-a`
     - `project/worktree-b`

- In layout (2), invoking `/work-on xxx` from `project/worktree-a` should create `project/xxx`, not a sibling of `project/`.
- Therefore the placement rule cannot be derived from the main checkout path alone. It must consider the relationship between the main checkout path and the current worktree path.

Problem statement:
`/work-on` does not yet have an explicit, layout-aware rule for deriving the target worktree directory. Because of that, it creates worktrees at the wrong filesystem level for at least one supported layout.

Observed failure:
- Starting from layout (2):
  - main checkout at `project/`
  - current linked worktree at `project/worktree`
- invoking `/work-on xxx` currently creates `../xxx` relative to `project/`, i.e. a sibling of the project directory
- expected behavior is `project/xxx`, i.e. a sibling of the current linked worktree inside the project container

Why this matters:
- wrong placement creates worktrees outside the expected project container
- downstream orientation and session/worktree semantics become confusing
- users cannot reliably predict where `/work-on` will place work
- the placement rule is part of the `/work-on` contract and should be explicit, testable, and layout-aware

## Inputs to the placement rule

The rule should use canonicalized absolute paths for:
- `main-checkout-path`: the path of `GitWorktreeRegistry.main`
- `current-worktree-path`: the path of `GitWorktreeRegistry.current`

Derived values:
- `main-parent`: `ParentDirectory(main-checkout-path)`
- `current-parent`: `ParentDirectory(current-worktree-path)`

The rule should not depend on:
- the slug alone
- the current working directory string before git worktree resolution
- ad hoc scanning for arbitrary container directories beyond these explicit path relationships

## Canonical placement rule

Define the target container directory as follows:

1. If `current-parent = main-checkout-path`, then the current linked worktree is nested directly under the main checkout.
   - This is the nested-linked layout.
   - The target container is `main-checkout-path`.
   - New `/work-on <slug>` target path is:
     - `main-checkout-path + "/" + slug`

2. Otherwise, the target container is `main-parent`.
   - This covers the sibling-main layout.
   - New `/work-on <slug>` target path is:
     - `main-parent + "/" + slug`

Equivalent phrasing:
- when the current worktree is an immediate child of the main checkout, create new worktrees inside the main checkout directory
- otherwise, create new worktrees beside the main checkout, in the main checkout’s parent directory

This rule is intentionally narrow:
- it uses the main checkout’s location relative to the current worktree’s location
- it avoids broader heuristics like “if any worktree is under main then always nest”
- it gives one deterministic answer from the current invocation context

## Worked examples

### Layout 1 — sibling-main layout

Paths:
- main checkout: `/repos/project/bare-checkout`
- current worktree: `/repos/project/task-a`

Derived:
- `main-parent = /repos/project`
- `current-parent = /repos/project`
- `current-parent != main-checkout-path`

Result:
- target container = `/repos/project`
- `/work-on fix-foo` => `/repos/project/fix-foo`

### Layout 1 from main checkout

Paths:
- main checkout: `/repos/project/bare-checkout`
- current worktree: `/repos/project/bare-checkout`

Derived:
- `main-parent = /repos/project`
- `current-parent = /repos/project`
- `current-parent != main-checkout-path`

Result:
- target container = `/repos/project`
- `/work-on fix-foo` => `/repos/project/fix-foo`

### Layout 2 — nested-linked layout

Paths:
- main checkout: `/repos/project`
- current worktree: `/repos/project/task-a`

Derived:
- `main-parent = /repos`
- `current-parent = /repos/project`
- `current-parent = main-checkout-path`

Result:
- target container = `/repos/project`
- `/work-on fix-foo` => `/repos/project/fix-foo`

### Layout 2 from main checkout

Paths:
- main checkout: `/repos/project`
- current worktree: `/repos/project`

Derived:
- `main-parent = /repos`
- `current-parent = /repos`
- `current-parent != main-checkout-path`

Result:
- target container = `/repos`
- `/work-on fix-foo` => `/repos/fix-foo`

Decision:
- this is acceptable under the current rule because there is no linked-worktree-relative evidence when invoking directly from the main checkout
- nested placement is only inferred when the current invocation path proves that linked worktrees are immediate children of the main checkout

## Explicit non-goal of the rule

The rule does not attempt to infer a global “project container” from all known worktrees.

Why:
- that would broaden the design from deterministic local placement into repository-layout inference
- it would introduce extra heuristics and edge cases
- the current need is narrower: derive placement from the main checkout relative to the current worktree location

## Supported and unsupported cases

Supported:
- invoking `/work-on` from the main checkout in sibling-main layout
- invoking `/work-on` from a linked worktree in sibling-main layout
- invoking `/work-on` from a linked worktree in nested-linked layout
- reuse behavior when the derived target path already exists as a registered worktree remains unchanged

Defined but potentially surprising:
- invoking `/work-on` from the main checkout in nested-linked layout creates a sibling of the main checkout, not a nested child
- this follows from the narrow canonical rule above
- if this behavior is undesirable, that is a separate design change requiring broader repository-container inference

Unsupported for this task:
- multi-level nesting heuristics such as `main/feature/subfeature`
- placement inference from non-current worktrees in the registry
- arbitrary mixed layouts where some linked worktrees are nested and others are siblings
- placement based on persisted session history rather than live path relationships

Error behavior:
- if either `main-checkout-path` or `current-worktree-path` cannot be resolved, `/work-on` should fail with the existing repo/context error path rather than guessing a placement
- if the derived target path already exists but is not a registered worktree, `/work-on` should continue to return the existing explicit error for that condition

## Design consequences for implementation

Any implementation should introduce a small, explicit helper equivalent to:
- input: `main-checkout-path`, `current-worktree-path`, `slug`
- output: derived target worktree path
- no side effects
- canonicalize paths before comparison
- compare `current-parent` to `main-checkout-path`

The helper should be tested directly for the layout matrix above before changing `/work-on` behavior.

## Spec/doc/test audit — artifacts that currently encode the old rule

The current “sibling of main worktree” assumption appears in multiple places and must be updated when implementation begins:

1. `spec/work-on-extension.allium`
   - currently hard-codes:
     - `let sibling_path = ParentDirectory(main_worktree.path) + "/" + slug.slug`
   - this spec needs a new derived-path concept matching the canonical rule above

2. `spec/git-worktree-mutations.allium`
   - currently states:
     - `@guarantee WorktreeAddCreatesSiblingDirectory`
     - `decision "Worktree paths are sibling directories of the main worktree"`
   - this is too strong and is no longer correct under the designed rule

3. `doc/tui.md`
   - currently says:
     - `/work-on <description> creates a sibling git worktree + branch`
     - `Worktree paths are sibling directories of the main worktree`
   - docs need to say placement is derived from the main/current layout relationship

4. `extensions/src/extensions/work_on.clj`
   - command registration text currently says:
     - `Create a sibling git worktree + branch and continue there`
   - help text should be updated to match the designed rule

5. `extensions/test/extensions/work_on_test.clj`
   - current tests encode only sibling-of-main outcomes
   - we need explicit cases for:
     - sibling-main layout
     - nested-linked layout from a linked worktree
     - possibly the defined main-checkout-in-nested-layout behavior, so it is intentional

Potential secondary audit items when implementation starts:
- `CHANGELOG.md` if user-visible command semantics change materially
- any additional docs or specs that restate the old sibling-only rule

## Decision summary

Decision:
- `/work-on` placement is derived from the relationship between the main checkout path and the current worktree path
- if the current worktree is an immediate child of the main checkout, create the new worktree inside the main checkout
- otherwise create it in the parent directory of the main checkout

Benefits:
- fixes the known broken layout
- preserves current behavior for the existing sibling-main layout
- removes ambiguity by using one deterministic rule
- avoids overfitting with broader, hidden heuristics

Implementation status:
- deferred
- no code changes should be made until this design is accepted
