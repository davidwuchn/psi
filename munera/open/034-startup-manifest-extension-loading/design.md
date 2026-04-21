# 034 — Startup manifest extension loading for non-local installs

## Goal

Make Psi startup activate enabled manifest-backed extensions for all supported manifest coordinate families:
- `:local/root`
- git (`:git/url` + `:git/sha`)
- mvn (`:mvn/version`)

The startup path must no longer be limited to source-file loading from `:local/root` entries.

## Problem statement

Psi already has a manifest-driven extension install model with structured effective-state and diagnostics. Startup reads that manifest state and exposes configured extensions through introspection, but startup activation currently uses only `activation-plan` `:extension-paths`, which are derived exclusively from `:local/root` entries.

Therefore:
- enabled git and mvn manifest extensions can appear in `:psi.extensions/effective`
- those same extensions are never activated at startup
- the live extension registry remains empty
- startup reports zero loaded extensions and zero startup extension errors

This behavior is incorrect because the manifest install model treats non-local manifest extensions as first-class extension installs.

## Concrete failure shape

When a project contains enabled non-local manifest extensions, startup can currently produce all of the following at once:
- `:psi.extensions/effective` contains entries with `:extension? true`, `:enabled? true`, `:effective? true`, `:status :configured`
- `:psi.extension/details` is `[]`
- `:psi.startup/extension-loaded-count` is `0`
- `:psi.startup/extension-errors` is `[]`

For this task, that state is defined as a bug whenever the manifest entries are valid and their dependencies are realizable in the startup runtime.

## Scope

### In scope

- add startup activation support for enabled manifest-backed git and mvn extensions
- preserve startup activation support for enabled manifest-backed local-root extensions
- introduce one shared manifest-aware extension activation path used by both startup and reload/apply
- define one canonical extension registry identity for manifest-installed non-file-backed extensions
- make startup summary fields reflect actual activation attempts and results
- add targeted tests for startup activation success and failure cases
- update docs describing startup/reload activation semantics for manifest installs

### Out of scope

- changing the manifest schema
- adding new install/list/remove command UX
- inventing a new package or registry mechanism
- changing the conservative dependency realization model beyond what is needed for truthful startup activation
- changing unrelated runtime refresh behavior
- migrating user or project manifests

## Canonical design decisions

This task resolves the following decisions now. They are not left open during implementation.

### 1. One activation abstraction

Psi must support two activation forms under one shared activation layer:
- **path-backed activation** for source-file-backed extensions
- **init-var-backed activation** for manifest-installed library extensions

The shared activation layer is the canonical extension activation abstraction. File-path loading is no longer the top-level model.

### 2. Startup and reload/apply must share activation logic

Startup and `reload-extensions-in!` must use the same manifest-aware activation machinery.

Allowed differences between startup and reload/apply are limited to dependency realization timing and runtime context setup. The extension activation model itself must be the same in both paths.

### 3. Non-local manifest extensions activate by `:psi/init`

For manifest-installed git and mvn extensions, activation must:
1. ensure the dependency basis is realized in the current runtime
2. resolve the `:psi/init` var from that realized runtime
3. call the init var with the canonical extension API

Startup must not require a source file path for git or mvn manifest extensions.

### 4. Canonical registry identity for non-file-backed extensions

Manifest-installed extensions activated by `:psi/init` must register in the live extension registry under this exact identity format:

- `manifest:{lib}`

Examples:
- `manifest:psi/mementum`
- `manifest:psi/workflow-loader`

This identity is the registry path/key for manifest-installed non-file-backed extensions. It must be:
- stable across runs
- derived only from the manifest library symbol
- used consistently in extension details, diagnostics, and registry-backed projections

Local-root extensions that are activated by resolved source file path keep their existing file-path identity.

### 5. Startup summary semantics

Startup summary fields are defined as follows:
- `:psi.startup/extension-loaded-count` = count of successful activation results during startup
- `:psi.startup/extension-errors` = one entry for each failed startup activation attempt
- `:psi.startup/extension-error-count` = count of `:psi.startup/extension-errors`

These fields must describe activation attempts, not merely manifest configuration state.

### 6. Truthfulness rule

After startup, the following invariants must hold:
- every successfully activated startup extension appears in the live extension registry
- every successfully activated startup extension contributes to `:psi.extension/details`
- startup loaded count equals the number of successful startup activation results
- startup error count equals the number of failed startup activation results
- an enabled manifest extension that was not activated successfully must not be counted as loaded

### 7. No silent non-attempt state

If startup sees at least one enabled manifest extension and the runtime can attempt activation, startup must produce either:
- a success result for that extension, or
- an error result for that extension

It must not silently skip non-local manifest extensions while still reporting zero startup extension errors.

## Required runtime behavior

### Startup path

At startup Psi must execute this sequence:
1. read extension manifests
2. compute effective install state
3. build the activation plan
4. realize non-local manifest deps if required for enabled manifest extensions
5. activate all enabled manifest extensions through the shared activation layer
6. persist truthful install/apply state
7. persist truthful startup summary

### Reload/apply path

`reload-extensions-in!` must use the same activation layer as startup for:
- local-root manifest extensions
- git manifest extensions
- mvn manifest extensions

Reload/apply may still return `:restart-required` when dependency realization cannot be completed safely in-process. That restart-required behavior does not change the activation model.

## Failure semantics

### Dependency realization failure

If startup cannot realize the required non-local dependencies for an enabled manifest extension, startup must record an activation failure for that extension.

### Init resolution failure

If startup realizes dependencies but cannot resolve the configured `:psi/init` var, startup must record an activation failure for that extension.

### Init execution failure

If startup resolves `:psi/init` but calling it throws, startup must record an activation failure for that extension.

In all three cases above:
- the extension must not appear as loaded
- the failure must appear in startup error reporting
- the failure must be reflected in registry/introspection convergence tests

## Acceptance criteria

This task is complete only when all of the following are true:

1. **Local-root startup activation still works**
   - an enabled manifest extension declared via `:local/root` activates at startup

2. **Git startup activation works**
   - an enabled manifest extension declared via git coordinates activates at startup without requiring a source file path

3. **Mvn startup activation works**
   - an enabled manifest extension declared via `:mvn/version` activates at startup without requiring a source file path

4. **Shared activation model**
   - startup and reload/apply both use the same manifest-aware activation machinery

5. **Canonical registry identity**
   - a non-file-backed manifest extension appears in the live registry using the exact `manifest:{lib}` identity format

6. **Truthful startup reporting**
   - startup loaded count equals successful startup activations
   - startup error count equals failed startup activations
   - startup errors contain failed activation attempts

7. **Registry/introspection convergence**
   - successfully activated startup extensions appear in `:psi.extension/details`
   - the live extension registry contents agree with startup summary counts

8. **No silent skip bug**
   - enabled non-local manifest extensions are not silently ignored at startup

9. **Tests exist for all required cases**
   - startup local-root success
   - startup git success
   - startup mvn success
   - dependency realization failure at startup
   - init resolution failure at startup
   - init execution failure at startup
   - convergence between startup summary and live registry state

10. **Docs updated**
   - docs explicitly state that manifest-installed local-root, git, and mvn extensions are all startup-activatable
   - docs explain that non-file-backed manifest installs activate by `:psi/init`, not by source-file path discovery

## Notes

This task changes startup activation semantics only. It does not change the install protocol, manifest schema, or extension API contract.
