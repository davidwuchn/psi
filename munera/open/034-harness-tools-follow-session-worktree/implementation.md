# Implementation notes

No implementation yet.

## Seed context

This task comes from a failure where:
- live psi runtime session reported worktree-path = `033-psi-tool-scheduler`
- actual harness file edits/commits occurred in `refactor`

The key architectural insight is that there are two distinct execution layers:

1. **psi runtime tool execution**
   - already uses session `:worktree-path`

2. **direct harness coding tools**
   - used by the assistant in this environment
   - not automatically scoped by psi session `:worktree-path`

This task is about reconciling those two layers for the common coding path.
