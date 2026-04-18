Approach:
- Continue converging any remaining prompt semantics into request preparation.
- Use the agent skill-prelude follow-on as the concrete driver for cache-breakpoint shaping.
- Keep shared-session lifecycle paths canonical; leave isolated workflow runtimes alone.

Likely steps:
1. inspect current request preparation and skill-prelude composition
2. shape the intended reusable-prelude vs variable-tail cache breakpoints
3. remove or simplify residual seams/comments/hooks
4. add or tighten focused proof

Risks:
- changing cache behavior without good observability
- accidentally breaking non-agent prompt flows while refining agent prelude logic
