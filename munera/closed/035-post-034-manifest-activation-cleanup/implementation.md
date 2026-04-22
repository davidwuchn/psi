# Implementation notes

- Created as a follow-up to review of task 034.
- Review findings motivating this task:
  - startup manifest activation orchestration is still too app-runtime-local
  - failed manifest activation registry semantics remain ambiguous
  - startup acceptance tests are behaviorally good but still more procedural than ideal
- This task should remain narrowly scoped to post-034 cleanup and must not absorb unrelated scheduler/statechart/runtime work.
- Cleanup implemented:
  - extracted startup manifest activation orchestration into `psi.agent-session.extension-runtime/bootstrap-manifest-extensions-in!`
  - reduced `psi.app-runtime/bootstrap-runtime-session!` to invoking the extension-runtime helper and merging returned summary deltas
  - chose explicit failed-activation registry semantics: failed manifest activation is rolled back from the live extension registry
  - added `unregister-extension-in!` and wired loader failure rollback for both path and init-var activation
  - shaped startup tests around reusable bootstrap/helpers (`startup-bootstrap-bindings`, `bootstrap-with-manifest`, `startup-registry-paths`, `startup-entry-status`)
  - added mixed-outcome assertion proving failed manifest ids are absent from the live registry while persisted install state still records `:failed`
