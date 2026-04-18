No implementation notes yet.
Task created to separate post-reload runtime refresh convergence from raw code reload.

Initial findings captured in design:
- surviving runtime data is often not the problem
- stale executable wiring in registries/hooks/callbacks is the main reload hazard
- task 014 should consume this task’s refresh protocol rather than own the whole architectural concern
