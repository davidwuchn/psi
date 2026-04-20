2026-04-19
- Task created from user request to add an extension install mechanism.
- Initial design bias captured:
  - simplicity first
  - pay only for what you use
  - support user + project scope
  - likely first slice: manifest-driven installs with `:local-fs` and `:github`
  - defer `:clojars` and marketplace until need is proven

2026-04-19
- Design direction updated after discussion of extensions as ordinary libraries.
- New current bias:
  - hybrid model: psi-specific install manifests + tools.deps loading substrate
  - extensions can bring their own dependencies
  - local installs should use tools.deps local dependency coordinates
  - git/GitHub installs should use git dependency coordinates
  - mvn/Clojars support is now a plausible early follow-on or low-cost inclusion if coherent
  - prefer startup/reload-based activation first over dynamic in-process dependency mutation

2026-04-19
- Design direction refined again.
- New current bias:
  - make the psi extension manifest deps.edn-shaped
  - use normal dependency coordinate keys directly in extension records
  - put psi metadata alongside them under `:psi/*` keys
  - prefer a single extension-centric map over splitting raw `:deps` and extension metadata across separate maps

2026-04-19
- Design direction simplified further.
- New current bias:
  - use a raw top-level `:deps` map rather than a custom `:extensions` map
  - use `:psi/init` as the required extension marker and activation contract
  - use optional `:psi/enabled` as the activation toggle
  - treat any dep entry with `:psi/init` as a psi extension
  - keep the manifest as close as practical to ordinary `deps.edn`

2026-04-19
- Design direction narrowed for slice one.
- New current bias:
  - direct manifest editing is the slice-one install/update/remove surface
  - dedicated command UX is not required initially
  - reload/apply plus validation/introspection are the key operational surfaces

2026-04-19
- Reload/apply stance sharpened.
- New current bias:
  - restart is the canonical guaranteed apply path
  - explicit reload/apply is optional and only desirable if it can be implemented conservatively
  - slice one should not promise arbitrary live in-process dependency graph mutation

2026-04-19
- Duplicate-init policy sharpened.
- New current bias:
  - duplicate effective `:psi/init` claims across distinct library keys are hard configuration errors
  - duplicate-init validation should run before activation begins
  - no claimant should activate while the conflict exists

2026-04-19
- Git dependency policy sharpened.
- New current bias:
  - git extension deps should follow standard pinned deps.edn expectations
  - require `:git/sha` in both user and project manifests for git extension deps
  - avoid psi-specific divergence between user and project git coordinate rules

2026-04-19
- Standard deps-form support clarified.
- New current bias:
  - local, git, and mvn dependency forms are part of the base model
  - mvn/Clojars should not be treated as a separate conceptual follow-on unless implementation reality forces a temporary restriction

2026-04-19
- Diagnostics/introspection surface clarified.
- New current bias:
  - structured extension validation, effective config, and load status should live on the existing canonical read/introspection surface
  - logs and human-readable summaries are secondary aids, not the primary diagnostic surface

2026-04-19
- Reload/apply importance updated.
- New current bias:
  - explicit reload/apply should be included in slice one because it matters for extension development
  - restart remains the guaranteed fallback apply path
  - reload/apply should remain conservative about runtime mutation guarantees and may degrade to restart-oriented apply when necessary

2026-04-19
- Follow-on implementation landed for non-local install apply behavior.
- Current behavior now is:
  - manifest-backed local-root extensions still resolve to source files and load in-process
  - non-local deps (git/mvn/support deps) are realized via `clojure.repl.deps/sync-deps` when the runtime can safely preserve already-realized non-local deps
  - the safety gate is conservative: in-process apply is allowed only when previously realized non-local deps are still present in the new effective deps set
  - if the runtime reports that deps sync is REPL-only in the current environment, apply degrades cleanly to `:restart-required` rather than surfacing a hard failure
  - hard dependency realization exceptions still surface as load-failure diagnostics
  - effective per-lib statuses now mark non-local extension entries as `:loaded` when deps sync succeeds in-process

2026-04-20
- Wider-suite regression follow-on landed after the non-local apply work.
- Root causes/fixes:
  - extension API `get-service` now falls back to `list-services` instead of failing when no direct `service-fn` is present
  - agent-chain sub-agent execution now uses an explicit agent API/mutate path rather than relying on `extensions.agent` global extension state
  - chain workflow invoke params now carry the API through correctly to the future runner
  - LSP sync no longer spends post-tool timeout budget on a pre-diagnostics sleep; diagnostic request waiting now owns that budget directly
  - LSP diagnostics request handling now accepts both live runtime service response shapes and nullable test-fixture response shapes
  - LSP restart preserves prior sync-kind knowledge when initialize responses do not restate it in a parseable shape
  - nullable extension API now exposes service functions needed by LSP post-tool tests without enabling warm-start-by-default behavior
- Verification:
  - focused extension runtime/LSP/agent-chain suites were repaired incrementally
  - full suite is green again (`1345 tests, 9738 assertions, 0 failures`)

2026-04-20
- Follow-on proof requested for this task beyond the base install mechanism.
- New required proof:
  - migrate repo built-in extensions away from the legacy shared `extensions/src/extensions` + `.psi/extensions/*.clj` symlink layout
  - reshape `extensions/` into one project per built-in extension currently shipped in this repo
  - create `.psi/extensions.edn` matching the currently active built-in extension set (`agent`, `agent_chain`, `auto_session_name`, `commit_checks`, `mementum`, `munera`, `work_on`)
  - verify both startup loading and explicit reload/apply against the migrated structure through the project nREPL
- Important consequence:
  - this moves the task from purely implementing the install mechanism to also using the repo’s own built-in extensions as the canonical dogfood proof for local-root/project-manifest installs

2026-04-20
- Follow-on dogfood proof is now completed.
- Completed proof/results:
  - repo built-in extensions were migrated away from the legacy shared `extensions/src/extensions` + `.psi/extensions/*.clj` symlink layout
  - `extensions/` now provides one project per built-in extension used in this repo
  - project `.psi/extensions.edn` now declares the built-in extension set through the install-manifest model
  - psi startup was verified against the migrated structure through the project nREPL
  - psi explicit reload/apply was verified against the migrated structure through the project nREPL
- Completion consequence:
  - this task now reflects both the install mechanism implementation and successful repository dogfooding/proof of the manifest-backed built-in extension layout
