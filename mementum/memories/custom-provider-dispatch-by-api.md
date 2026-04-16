🔁 custom-provider-dispatch-by-api

Custom providers reuse existing transport implementations via `:api` protocol fallback in `psi.ai.core/resolve-provider`. A provider like `:local` with `:api :openai-completions` dispatches through the built-in OpenAI transport — no new streaming code needed. Resolution: exact `:provider` match first, then `:api` key fallback. The provider-registry has both provider-keyed and api-keyed entries. This pattern means adding a new provider is purely config — zero code for any OpenAI-compatible endpoint.
