Approach:
- Treat this as a concrete prompt-lifecycle follow-on centered on child-session seeding.
- First fix the cache-breakpoint shape, then decide whether the synthetic prelude markers need stronger structure.
- Add introspection exposure only if it clearly helps debugging.

Likely steps:
1. inspect current preloaded-message and cache-breakpoint behavior
2. implement the intended 2-breakpoint shape
3. decide whether the acknowledgement becomes a canonical marker
4. document or expose metadata as needed

Risks:
- overcomplicating a currently effective synthetic contract
- exposing introspection metadata that becomes a maintenance burden
