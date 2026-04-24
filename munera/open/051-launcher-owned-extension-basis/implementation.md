2026-04-24 — Slice 1

- Added pure launcher planning namespace `bases/main/src/psi/launcher.clj`.
- Added babashka launcher entrypoint `bb/psi.clj`.
- Launcher now consumes `--cwd` and `--launcher-debug` and forwards remaining args to `psi.main` unchanged.
- Initial handoff uses `clojure -M -m psi.main ...` from the selected cwd so startup no longer depends on a user-defined `:psi` alias for repo-local invocation.
- Added focused launcher tests for arg separation, cwd resolution, command construction, and launch-plan shaping.
- Full startup basis synthesis and manifest participation remain for later slices.
