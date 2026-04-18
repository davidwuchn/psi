Approach:
- Implement the selector in a way that can be adopted first by helper/background flows before broader agent usage.
- Keep filtering and ranking separate so fallback behavior is intelligible.
- Provide enough explanation data to support debugging and introspection.

Likely steps:
1. define task-class-based selection requests
2. implement hard capability filtering
3. implement soft preference ranking
4. expose explainable selection outcomes
5. wire auto-session-name helper execution to the selector

Risks:
- coupling the selector too tightly to one caller
- opaque selection heuristics that are hard to debug
