Goal: add support for `.psi/project.local.edn` as a writable, higher-precedence project-local config layer above `.psi/project.edn`, so committed/shared project defaults and uncommitted developer-local overrides can coexist cleanly.

Context:
- today, project-scoped config is effectively treated as a single file at `.psi/project.edn`
- this means shared defaults and developer-local overrides share the same file
- `/model` and `/thinking` currently persist updates into `.psi/project.edn`
- that creates git noise and makes local experimentation awkward
- the intended project config model is:
  - `.psi/project.edn` — shared, committed project defaults
  - `.psi/project.local.edn` — developer-local, uncommitted overrides

Problem statement:
- there is currently no canonical project-local override file above `.psi/project.edn`
- project config cannot cleanly separate shared defaults from local overrides
- command-driven preference changes mutate the shared file
- project config consumers cannot consistently distinguish committed project defaults from local developer preferences

Desired outcome:
- psi supports a two-layer project config model:
  - `.psi/project.edn` remains the shared/committed project config surface
  - `.psi/project.local.edn` becomes the writable local override surface
- effective project config is resolved by deep-merging shared then local, with local taking precedence
- project preference persistence targets only the local layer:
  - `/model` updates `.psi/project.local.edn`
  - `/thinking` updates `.psi/project.local.edn`
- the broader rule is general rather than command-specific:
  - any project config consumer resolves `.psi/project.local.edn` over `.psi/project.edn`
  - any persisted project preference update writes the local file rather than rewriting the shared file

Canonical precedence:
1. session runtime overrides
2. `.psi/project.local.edn`
3. `.psi/project.edn`
4. `~/.psi/agent/config.edn`
5. system defaults

This task changes only the project layer by splitting it into shared and local files.

Scope:
In scope:
- add explicit support for `.psi/project.local.edn`
- make project config resolution read both `.psi/project.edn` and `.psi/project.local.edn`
- deep-merge local after shared so local takes precedence for overlapping keys and nested paths
- keep the externally visible config shape unchanged
- make persisted project preference updates write only `.psi/project.local.edn`
- ensure `/model` and `/thinking` use the local write path
- ensure other project config consumers, including project nREPL config, use the same shared-then-local deep-merge rule
- emit warnings for malformed shared/local project config files while preserving best-effort fallback
- update user-facing/operator-facing text that references writable project config paths
- gitignore `.psi/project.local.edn`
- add focused tests for precedence, deep-merge behavior, warning/fallback behavior, and write-target behavior

Out of scope:
- migrating existing `.psi/project.edn` content into `.psi/project.local.edn`
- adding commands to clear local overrides
- adding commands to promote local overrides into shared config
- redesigning user config precedence
- changing `.psi/models.edn` behavior
- changing the config data model beyond the addition of the local file layer

Read semantics:
- project config is read from two files:
  - shared: `.psi/project.edn`
  - local: `.psi/project.local.edn`
- the effective project config is computed by deep-merging:
  - defaults
  - shared file
  - local file
- local takes precedence over shared for overlapping keys and nested paths
- config may live in either file
- missing files remain valid:
  - if only shared exists, behavior matches today except for the new layered model
  - if only local exists, local is used
  - if neither exists, defaults apply as today
- malformed files are handled best-effort:
  - an invalid local file should not block fallback to shared config
  - an invalid shared file should not block use of a valid local file
  - if both files are invalid, effective resolution falls back to defaults
  - malformed file handling should emit a warning rather than hard-failing the effective config resolution path

Write semantics:
- persisted project preference writes target `.psi/project.local.edn`
- writes must not rewrite `.psi/project.edn`
- writes should:
  - create `.psi/` if needed
  - create `.psi/project.local.edn` on first write if absent
  - merge into existing local content rather than replacing unrelated keys
- if existing `.psi/project.local.edn` is malformed:
  - emit a warning
  - treat its contents as empty for the purposes of the write
  - do not rewrite or normalize the malformed file as part of warning/fallback handling alone
  - the explicit write operation may still write the requested updated preferences as the new local file content
- this task does not add unset-local-override behavior

Merge semantics:
- this slice should preserve the existing config shape while introducing layered project config
- effective project config should be computed using deep merge so nested maps can be split across shared and local files
- `.psi/project.local.edn` is merged after `.psi/project.edn`
- non-overlapping config from either file should survive in the effective config
- overlapping nested paths should prefer the local value
- when both values are maps, merge recursively
- when values are not both maps, the later/local value replaces the earlier/shared value
- vectors and other non-map values are replaced, not concatenated or structurally merged

User-facing path semantics:
- when psi tells the user where to place editable project-local overrides, it should refer to `.psi/project.local.edn`
- when psi refers to the shared/committed project config layer, it may refer to `.psi/project.edn`
- help and error text should reflect that distinction clearly
- project nREPL help/error text should be included in this review because project nREPL is a project-config consumer

Acceptance:
- effective project config is computed by deep-merging `.psi/project.edn` and then `.psi/project.local.edn`
- `.psi/project.local.edn` takes priority for overlapping keys and nested paths
- config may live in either file
- missing `.psi/project.local.edn` preserves current shared-file behavior
- malformed project config files do not hard-fail effective config resolution when another valid layer is available
- malformed file handling emits a warning
- malformed shared + valid local falls back to local with warning
- malformed local + valid shared falls back to shared with warning
- malformed shared + malformed local falls back to defaults with warnings
- project preference writes create/update `.psi/project.local.edn` only
- `/model` persists to `.psi/project.local.edn`
- `/thinking` persists to `.psi/project.local.edn`
- reads after those writes reflect the local override
- if `.psi/project.local.edn` is malformed when a write occurs, the write warns and treats existing local content as empty input
- all project config consumers, including project nREPL config, use the same shared-then-local deep-merge rule
- `.psi/project.local.edn` is gitignored
- focused tests cover:
  - local-over-shared precedence
  - deep merge of nested config
  - non-map replacement semantics during deep merge
  - shared-only fallback
  - local-only fallback
  - malformed-local warning + fallback
  - malformed-shared warning + fallback
  - both-invalid fallback to defaults
  - `/model` local write target
  - `/thinking` local write target
  - preservation of unrelated local keys during writes
  - project nREPL layered config resolution
