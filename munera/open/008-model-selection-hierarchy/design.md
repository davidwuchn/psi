Goal: advance the model-selection hierarchy from design note to implementation slices.

Context:
- `doc/design-model-selection-hierarchy.md` exists
- next active work is task-class-based selection, hard capability filtering vs soft preference ranking, use in auto-session-name helper execution, and explainable selection results

Acceptance:
- helper/background work can request models by task class
- capability filtering is separated from preference ranking
- auto-session-name can use the selector as an early adopter
- selection results are explainable enough to debug fallback behavior
