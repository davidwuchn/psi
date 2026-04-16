💡 pi-mono-reference-architecture

`~/src/pi-mono/` is a TypeScript coding agent monorepo with mature model/provider infrastructure. Key reference files for psi design work:
- `packages/coding-agent/src/core/model-registry.ts` — `ModelRegistry` class, `models.json` loading, provider override/merge, auth resolution
- `packages/coding-agent/src/core/model-resolver.ts` — fuzzy model matching, thinking-level parsing, scope resolution
- `packages/coding-agent/examples/sdk/02-custom-model.ts` — SDK custom model example
- `packages/coding-agent/examples/extensions/custom-provider-anthropic/` — full custom provider with OAuth + streaming

Their `models.json` has a rich `compat` schema (`OpenAICompletionsCompatSchema`) for handling local model quirks — we deferred this but may need it when specific servers surface compatibility issues.
