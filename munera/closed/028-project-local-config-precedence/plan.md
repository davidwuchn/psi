Approach:
- Split project config into two explicit file roles:
  - `.psi/project.edn` as shared/committed project preferences
  - `.psi/project.local.edn` as local/uncommitted override preferences
- Apply one general project-config rule everywhere, not a `/model`/`/thinking` special case.
- Keep the externally visible config shape unchanged; change only layering, merge, warning, and write-target semantics.

Implementation slices:
1. project preference file model
   - add helpers for both project config paths
   - read both files best-effort
   - deep-merge shared then local, with local taking precedence
   - define deep-merge semantics explicitly: recursive for maps, replacement for non-map values
   - emit warnings when either file is malformed while preserving fallback behavior

2. persistence semantics
   - keep read APIs returning one effective project preference map
   - direct writes from agent-session preference updates go to `.psi/project.local.edn`
   - writes merge into existing local content and preserve unrelated keys
   - if the existing local file is malformed, warn and treat it as empty input for the write
   - preserve file creation behavior under `.psi/`

3. general consumer adoption
   - ensure project config consumers use the same shared-then-local deep-merge rule
   - update any code that currently assumes a single project config file
   - explicitly include project nREPL config resolution in the adoption pass

4. command behavior
   - ensure `/model` persists via the local preference write path
   - ensure `/thinking` persists via the local preference write path
   - keep command output stable unless path/help text must change

5. user-facing path/help text
   - update text that tells the user where to place editable project-local overrides so it points at `.psi/project.local.edn`
   - keep shared/default references to `.psi/project.edn` when describing committed project config
   - include project nREPL help/error text in this review
   - prefer warnings/logging for malformed file reporting rather than inventing new UI machinery in this slice

6. git ergonomics
   - add ignore coverage for `.psi/project.local.edn`

7. proof
   - add focused tests for effective deep-merge precedence
   - add tests proving non-map replacement semantics
   - add tests proving write target is `.psi/project.local.edn`
   - add tests for malformed shared/local warning + fallback behavior
   - add tests for both-invalid fallback to defaults
   - add tests for malformed-existing-local write behavior
   - verify shared-only and local-only fallback behavior
   - verify unrelated local keys are preserved on write
   - verify project nREPL layered config resolution

Risks:
- conflating read precedence with write behavior
- missing a project config consumer that still reads only `.psi/project.edn`
- implementing shallow merge where deep merge is required
- surfacing malformed-file warnings inconsistently
- rewriting malformed local files during read/fallback when the intended behavior is warn + treat as empty
- updating some path/help strings but missing others
