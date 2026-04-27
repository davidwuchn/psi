# Changelog

All notable user-visible changes to psi are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Version scheme: `MAJOR.MINOR.PATCH` where PATCH = `git rev-list HEAD --count` at release time.

## [Unreleased]

## [0.1.2026] - 2026-04-27

### Fixed
- `psi` installed via `bbin` from Clojars no longer fails with "Could not find artifact psi:workflow-loader" — psi-owned extensions are bundled in the main jar and no longer requested as separate Maven artifacts.

## [0.1.2021] - 2026-04-27

### Fixed
- `psi` no longer crashes on startup when installed via `bbin` from Clojars — the launcher now correctly skips `jar:` URLs when locating the repo root.

## [0.1.2017] - 2026-04-27

### Fixed
- `bbin install org.hugoduncan/psi` now correctly resolves the `psi` entry point — added `hugoduncan.psi` shim namespace so bbin's Maven installer can locate the entry point it derives from the artifact coordinates.

## [0.1.2008] - 2026-04-27

### Added
- Initial Version

### Changed

### Fixed

<!-- Comparison links -->
[Unreleased]: https://github.com/hugoduncan/psi/compare/v0.1.2026...HEAD
[0.1.2026]: https://github.com/hugoduncan/psi/compare/v0.1.2021...v0.1.2026
[0.1.2021]: https://github.com/hugoduncan/psi/compare/v0.1.2017...v0.1.2021
[0.1.2017]: https://github.com/hugoduncan/psi/compare/v0.1.2013...v0.1.2017
[0.1.2013]: https://github.com/hugoduncan/psi/compare/v0.1.2008...v0.1.2013
[0.1.2008]: https://github.com/hugoduncan/psi/compare/v0.1.2002...v0.1.2008
[0.1.2002]: https://github.com/hugoduncan/psi/compare/v0.1.1998...v0.1.2002
[0.1.1998]: https://github.com/hugoduncan/psi/compare/v0.1.1994...v0.1.1998
[0.1.1994]: https://github.com/hugoduncan/psi/compare/v0.1.1990...v0.1.1994
[0.1.1990]: https://github.com/hugoduncan/psi/compare/v0.1.1987...v0.1.1990
