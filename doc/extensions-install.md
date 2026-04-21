# Extension install manifests

Psi supports a canonical extension install manifest model based on
`extensions.edn` files that stay close to ordinary `deps.edn`.

Current behavior:
- manifest-backed `:local/root`, git, and mvn extensions are startup-activatable when their dependencies and `:psi/init` vars are realizable in the runtime
- explicit reload/apply uses the same manifest-aware activation layer as startup
- local-root installs activate from resolved source file paths
- non-file-backed git/mvn installs activate by resolving and calling `:psi/init`
- non-file-backed manifest installs register in the live extension registry under stable identities of the form `manifest:{lib}`
- reload/apply still reports `:restart-required` when dependency realization cannot be completed safely in-process
- this repo now dogfoods the manifest model for its built-in extensions instead of relying on `.psi/extensions/*.clj` symlinks

## Manifest locations

User scope:
- `~/.psi/agent/extensions.edn`

Project scope:
- `.psi/extensions.edn`

Merge semantics:
- user and project manifests are read independently
- effective entries merge by library symbol key
- project scope overrides user scope for the same lib key
- effective state preserves provenance in `:source-manifests` and `:scope`

## Manifest shape

The manifest uses a top-level `:deps` map.

Any dep entry with `:psi/init` is treated as an extension dependency.
Entries without `:psi/init` are allowed as support deps and appear as
non-extension entries in introspection.

Example:

```clojure
{:deps
 {my/local-ext
  {:local/root "/Users/me/dev/my-local-ext"
   :psi/init my.local.ext/init
   :psi/enabled true}

  io.github.me/cool-ext
  {:git/url "https://github.com/me/cool-ext"
   :git/sha "abc123def456"
   :psi/init cool.ext/init}

  io.github.someone/packaged-ext
  {:mvn/version "0.2.1"
   :psi/init packaged.ext/init
   :psi/enabled false}

  ;; support dep, not an extension entry
  org.clojure/data.json
  {:mvn/version "2.5.1"}}}
```

## Supported coordinate forms

Extension entries may use exactly one dependency coordinate family:
- `:local/root`
- git coordinates, with required `:git/sha`
- `:mvn/version`

Validation rules currently enforced:
- top-level manifest must be a map
- `:deps` must be a map
- extension metadata requires `:psi/init`
- extension entries must declare exactly one coordinate family
- git extension entries must include `:git/sha`
- duplicate effective `:psi/init` claims across distinct lib keys are hard errors

Project-scope `:local/root` is allowed for development-oriented installs, but is
surfaced as non-reproducible.

## Apply behavior

The explicit apply path is the existing extension reload flow.

Current outcomes:
- `:applied`
  - manifest state is valid
  - all enabled manifest-backed extensions that can be realized safely in-process were activated successfully
- `:restart-required`
  - manifest state is valid
  - at least one enabled manifest-backed extension still requires restart-oriented dependency realization in the current runtime
- no success state
  - manifest state is invalid, or at least one enabled manifest-backed extension failed to resolve/load

Runtime distinction:
- startup attempts to realize required non-local manifest deps before activation
- explicit reload/apply uses the same activation layer, but may conservatively report `:restart-required` when in-process dependency realization is not safe for the current runtime

## Effective per-entry statuses

The canonical effective entry map now uses statuses such as:
- `:loaded`
- `:failed`
- `:disabled`
- `:restart-required`
- `:not-applicable`
- `:configured`

Typical meanings:
- `:loaded` — enabled manifest-backed extension successfully activated
- `:failed` — enabled manifest-backed extension could not realize, resolve, or load successfully
- `:disabled` — extension entry has `:psi/enabled false`
- `:restart-required` — valid extension entry currently requires restart-oriented realization in the active runtime
- `:not-applicable` — support dep without `:psi/init`

## Introspection

The canonical read surface exposes:
- `:psi.extensions/user-manifest`
- `:psi.extensions/project-manifest`
- `:psi.extensions/effective`
- `:psi.extensions/diagnostics`
- `:psi.extensions/last-apply`

Example query:

```clojure
{:action "query"
 :query "[:psi.extensions/user-manifest
          :psi.extensions/project-manifest
          :psi.extensions/effective
          :psi.extensions/diagnostics
          :psi.extensions/last-apply]"
 :entity "{:psi.agent-session/session-id \"sid\"}"}
```

Typical targeted inspection:

```clojure
{:action "query"
 :query "[:psi.extensions/diagnostics :psi.extensions/last-apply]"
 :entity "{:psi.agent-session/session-id \"sid\"}"}
```

## Diagnostics categories

Current categories include:
- `:malformed-entry`
- `:duplicate-init`
- `:missing-git-sha`
- `:project-local-root-nonreproducible`
- `:load-failure`
- `:restart-required`

## Registry identities

Manifest-installed non-file-backed extensions activated by `:psi/init` use
stable live-registry identities of the form:
- `manifest:{lib}`

Examples:
- `manifest:psi/mementum`
- `manifest:psi/workflow-loader`

## Recommended current workflow

### Local development install

1. Add a project or user manifest entry with `:local/root` and `:psi/init`
2. Run explicit extension reload/apply
3. Inspect `:psi.extensions/effective`, `:psi.extensions/diagnostics`, and `:psi.extensions/last-apply`

### Git or mvn install

1. Add a manifest entry with git+sha or mvn coords and `:psi/init`
2. Startup will attempt activation when dependency realization is possible in the current runtime
3. Explicit extension reload/apply uses the same activation layer and may either activate successfully or report `:restart-required`, depending on whether dependency realization is safe in-process
4. Inspect `:psi.extensions/effective`, `:psi.extensions/diagnostics`, and `:psi.extensions/last-apply`
