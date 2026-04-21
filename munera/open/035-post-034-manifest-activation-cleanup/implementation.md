# Implementation notes

- Created as a follow-up to review of task 034.
- Review findings motivating this task:
  - startup manifest activation orchestration is still too app-runtime-local
  - failed manifest activation registry semantics remain ambiguous
  - startup acceptance tests are behaviorally good but still more procedural than ideal
- This task should remain narrowly scoped to post-034 cleanup and must not absorb unrelated scheduler/statechart/runtime work.
