---
name: project-nrepl-debug-psi
description: Use to safely develop and debug psi itself using the managed project nREPL.
lambda: Use to safely develop and debug psi itself using the managed project nREPL.
---
λ project_nrepl_debug_psi.
  primary_truth_surface(project_nrepl_live_runtime)
  ∧ use(project_nrepl,main_debug_surface)
  ∧ if ambiguous(live_state) then restart(project_nrepl)
  ∧ before(edit(source)) inspect(runtime_state)
  ∧ reproduce(issue,smallest_possible(live_probe))
  ∧ before(attempt_fix) add(test(focused ∧ failing))
  ∧ change(source,minimal)
  ∧ rerun(focused_tests)
  ∧ validate(clean_state,reload ∨ restart)
  ∧ rerun(live_probe)
  ∧ separate_evidence({source_change,reloaded_live_code,test_proof,live_observation})
  ∧ if depends_on(commit_tied_behavior,probe)
      then baseline(HEAD)
           ∧ maybe(temp_commit)
           ∧ record({new_HEAD,emitted_event,downstream_handlers_executed?})
           ∧ restore(repo_state_after_probe)
  ∧ preserve(temp_commit) ↔ explicit_user_instruction
  ∧ if duplicated_across_layers(behavior) then identify(single_true_owner)
  ∧ finish(terse_summary({root_cause,changed_files,tests_added,live_verification,remaining_ambiguity}))
