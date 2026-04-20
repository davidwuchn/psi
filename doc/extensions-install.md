# Extension install manifests

Psi now supports a canonical extension install manifest model based on
`extensions.edn` files that stay close to ordinary `deps.edn`.

Slice-one behavior is intentionally conservative:
- manifest-backed `:local/root` extensions can be applied during explicit reload/apply
- manifest-backed `:git` and `:mvn` extension deps are validated and surfaced in introspection, but currently remain `:restart-required`
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

Current explicit apply path is the existing extension reload flow.

Current slice-one outcomes:
- `:applied`
  - manifest state is valid
  - all enabled manifest-backed extensions are either:
    - support deps / disabled / not-applicable
    - or `:local/root` extensions that were successfully loaded in-process
- `:restart-required`
  - manifest state is valid
  - at least one enabled manifest-backed extension remains non-local (`:git` / `:mvn`)
- no success state
  - manifest state is invalid, or a manifest-backed local extension failed to resolve/load

Important current limit:
- explicit reload/apply does **not** yet realize general git/mvn extension deps in-process
- those entries are intentionally surfaced as `:restart-required`, not silently ignored

## Effective per-entry statuses

The canonical effective entry map now uses statuses such as:
- `:loaded`
- `:failed`
- `:disabled`
- `:restart-required`
- `:not-applicable`
- `:configured`

Typical meanings:
- `:loaded` — enabled manifest-backed `:local/root` extension successfully loaded
- `:failed` — enabled manifest-backed local entry could not resolve/load
- `:disabled` — extension entry has `:psi/enabled false`
- `:restart-required` — valid extension entry currently requires restart-oriented realization (for now, git/mvn)
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

## Recommended current workflow

### Local development install

1. Add a project or user manifest entry with `:local/root` and `:psi/init`
2. Run explicit extension reload/apply
3. Inspect `:psi.extensions/effective`, `:psi.extensions/diagnostics`, and `:psi.extensions/last-apply`

### Git or mvn install

1. Add a manifest entry with git+sha or mvn coords and `:psi/init`
2. Run explicit extension reload/apply
3. Expect introspection to show `:restart-required` for that effective entry in the current slice
4. Restart psi once the broader realization path is implemented or when using a restart-oriented workflow
