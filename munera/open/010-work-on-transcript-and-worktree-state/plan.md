Approach:
- Treat this as two linked bugs in one command contract:
  1. transcript visibility
  2. runtime session-state coherence
- First inspect the current `/work-on` implementation and extension/runtime APIs to find the narrowest canonical integration points.
- Then update `work-on` so successful outcomes send an assistant message through the canonical extension-message path and update the relevant session `:worktree-path` through the canonical session mutation path.
- Finish by adding focused tests for create, reuse, and switch flows.

Likely steps:
1. add/extend extension API support for explicit session worktree-path mutation if needed
2. update `extensions.work-on/work-on!` flow so the originating session records the resulting worktree-path when appropriate
3. emit assistant-visible outcome text through `psi.extension/send-message` rather than relying only on `println`
4. preserve existing create/switch behavior while making transcript and state coherent
5. add tests covering assistant message emission and worktree-path updates

Risks:
- updating only printed output or RPC/UI projection without journaling an assistant-visible message
- updating only a newly created/switched session while leaving the originating current session stale where the command was interpreted
- conflating frontend focus switching with canonical session `:worktree-path` mutation
