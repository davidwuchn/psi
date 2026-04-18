Diagnosis and fix summary

This task initially captured `/work-on` as a possible no-op regression. Focused reproduction showed the command itself was not failing: the extension created/switched the worktree/session and emitted the intended visible assistant message through the extension transcript output path (now canonically `psi.extension/notify`; at the time this flowed through the older `psi.extension/send-message` compatibility surface).

Actual failure boundary

The confusing behavior was in RPC extension-command result delivery, not in `/work-on` command registration or worktree mutation:

- `/work-on` executes as an extension command
- the extension handler itself does not print stdout on success
- RPC command-result handling wrapped extension command execution and converted blank stdout into the synthetic placeholder text:
  `[extension command returned no output]`
- because `/work-on` had already emitted its own assistant-visible success message, the placeholder appeared as a second misleading line, making the command feel broken or redundant from the user perspective

Implemented change

Updated `components/rpc/src/psi/rpc/session/command_results.clj` so that:

- `extension-command-output` returns `nil` for blank stdout
- legacy prompt-path extension command handling emits nothing when stdout is blank
- canonical command-result extension command handling emits nothing when stdout is blank
- real handler errors still surface as deterministic error text

Regression coverage

Added `components/rpc/test/psi/rpc_command_results_test.clj` covering:

- extension command stdout is still returned when present
- blank stdout returns `nil`
- canonical command-result handling does not emit a placeholder for blank extension stdout
- legacy prompt-path handling also does not emit a placeholder for blank extension stdout
- handler exceptions still surface deterministically

Outcome

`/work-on` should now present the intended single visible success message in RPC/Emacs instead of appending the spurious no-output placeholder.
