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

2026-04-24 — Slice 4 / Slice 5 / Slice 6 / Slice 7 progression

- Extended launcher planning so startup basis construction now materializes through `-Sdeps` using psi self-basis plus expanded manifest deps.
- Made launcher policy explicit in code shape with separate development vs installed materialization rules.
- Added launcher debug reporting for cwd, manifest presence, merged lib keys, psi-owned default usage, inferred init usage, and basis summary.
- Added `.bbin/metadata.edn` to provide a bbin-installable `psi` command surface.
- Migrated top-level/operator docs to present `psi` as the canonical startup command.
- Updated Emacs/TUI-facing docs and defaults toward launcher-owned startup usage.
- Updated runtime-facing usage comments so launcher-owned startup is the primary story and repo-local `clojure -M:run ...` flows are explicitly development/non-canonical.
- Added `doc/extensions.md` alignment so extension docs now describe launcher-owned startup basis construction and psi-owned defaulting consistently.
- Proved a local `bbin`-installed launcher command can execute the launcher path and emit the canonical debug summary when invoked from a cwd without project manifests.
- During that proof, fixed launcher handling for a leading `--` argument separator, fixed `bb/psi.clj` launcher-root derivation, fixed absolute-cwd resolution, and stripped runtime-only manifest metadata (`:psi/init`, `:psi/enabled`) from launch-time basis deps before `-Sdeps` handoff.
- Local frictionless `bbin install . --as psi-launcher-auto` now succeeds using repo metadata, and the resulting installed command was exercised successfully against the launcher path with `--cwd`, `--launcher-debug`, and `--rpc-edn`.
- Remaining packaging gap is now specifically about the final remote/canonical install path (`bbin install io.github.hugoduncan/psi --as psi`), not local metadata-driven installation behavior.
- Remote install proof was attempted against `io.github.hugoduncan/psi`, `https://github.com/hugoduncan/psi.git`, and `git@github.com:hugoduncan/psi.git`.
- All three remote installs resolved to published commit `2ce48fb08fc9263552ce769a4ee750acecfb0450`, not the current launcher branch work.
- The generated remote wrapper used stale main opts (`["-m" "hugoduncan.psi"]` / similarly incorrect inferred values for raw URL forms), and execution failed because that published commit does not yet contain the launcher entrypoint/package metadata.
- Conclusion: remote canonical install is blocked on publication/merge of the launcher work to the remotely installed ref, not on additional local launcher behavior fixes.
