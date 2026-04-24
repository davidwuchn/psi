2026-04-24 — Slice 1

- Added pure launcher planning namespace `bases/main/src/psi/launcher.clj`.
- Added babashka launcher entrypoint `bb/psi.clj`.
- Launcher now consumes `--cwd` and `--launcher-debug` and forwards remaining args to `psi.main` unchanged.
- Initial handoff uses `clojure -M -m psi.main ...` from the selected cwd so startup no longer depends on a user-defined `:psi` alias for repo-local invocation.
- Added focused launcher tests for arg separation, cwd resolution, command construction, and launch-plan shaping.
- Full startup basis synthesis and manifest participation remain for later slices.

2026-04-24 — Slice 2 / Slice 3 foundation

- Added launcher-side manifest read/merge/default-expansion namespace `bases/main/src/psi/launcher/extensions.clj`.
- Added an explicit psi-owned extension catalog with deterministic `:psi/init` mappings and explicit development vs installed source-policy slots.
- Added launcher-owned installed defaults for recognized psi-owned extension libs using one explicit git source identity plus one explicit default installed psi version.
- Implemented pure manifest read, project-over-user merge, coordinate-family validation, psi-owned default expansion, and deterministic init inference.
- Added focused tests proving minimal recognized psi-owned syntax expands, explicit fields override defaults, unrecognized libs do not receive psi-owned defaults, and incoherent entries fail before basis synthesis.
