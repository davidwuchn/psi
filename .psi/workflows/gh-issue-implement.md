---
name: gh-issue-implement
description: Find labeled GitHub implementation requests, create an issue worktree, design a Munera task, implement when design is clean, and create a PR
---
{:tools ["read" "bash" "edit" "write" "work-on"]
 :skills ["munera-task-design" "work-independently"]
 :thinking-level :high}

You are executing a focused GitHub-issue implementation workflow in this repository.

Goal:
- Find an open GitHub issue labeled `implement`.
- Refresh `origin/master` and create an issue-specific worktree using the `work-on` tool based on `origin/master`.
- In that worktree, review the issue request and create a new Munera task.
- Iteratively refine and review the task design with the `munera-task-design` skill until forgotten aspects are covered and ambiguities are resolved.
- If the design cannot be made complete and unambiguous, commit the design work, push the branch, and create a PR that references the original issue.
- If the design is clean, execute the task autonomously using the `work-independently` skill, then push and create a PR that references the original issue.

Use the `munera-task-design` skill when shaping and reviewing the Munera task design.
Use the `work-independently` skill when implementation begins.

Primary selection rule:
- Look for open GitHub issues carrying the `implement` label.
- If there are no matching issues, stop and report that there is nothing to process.
- If multiple matching issues exist, process them in ascending issue-number order unless the input narrows the target.

Input expectations:
- `$INPUT` is optional.
- If provided, treat it as an optional narrowing hint such as an issue number, repo-qualified issue reference, full issue URL, or a short instruction that identifies a specific matching issue.
- If `$INPUT` is absent, discover candidate issues from labels.

Required procedure:

1. Discover and select the issue.
   - Use `gh issue list` with JSON output to find open issues with the `implement` label.
   - Retrieve enough list information to identify candidates, including at least: number, title, labels, state, and URL.
   - Treat the current repository as authoritative unless the input explicitly identifies another repository.
   - If the request input narrows the target, use it to select exactly one matching issue.

2. Read the selected issue.
   - Use `gh issue view` with JSON output.
   - Retrieve enough detail to understand the request, including at least: number, title, body, labels, author, assignees, state, and URL.
   - If the issue cannot be read, stop and report the failure instead of fabricating details.

3. Refresh the base branch.
   - Run `git fetch origin master`.
   - Treat `origin/master` as the authoritative base for the implementation worktree.
   - If the fetch fails, stop and report the failure rather than proceeding on a stale base.

4. Create the issue worktree.
   - Use the `work-on` tool, not manual `git worktree` shell commands.
   - Base the worktree on `origin/master`.
   - Use a short issue-derived description, such as the issue number plus a concise title fragment.
   - After `work-on`, treat the resulting worktree path as authoritative for all repository edits, git commands, and PR work.
   - If the `work-on` tool is unavailable, stop and report that limitation instead of improvising a different mechanism.

5. Create the Munera task in the worktree.
   - Orient in Munera by reading `munera/plan.md` and inspecting `munera/open/` and `munera/closed/`.
   - Allocate the next canonical `NNN-slug` task id.
   - Create a new task directory under `munera/open/NNN-slug/`.
   - Write at least:
     - `design.md`
     - `steps.md`
     - `implementation.md`
   - Include issue provenance in the task files, especially the issue number and URL.
   - Keep `design.md` focused on what and why, not code-level implementation.

6. Refine the task design until it is clean or clearly blocked.
   - Use the `munera-task-design` skill to review the design repeatedly.
   - Continue refining until forgotten aspects are covered and ambiguities are resolved.
   - A clean design should be complete and unambiguous enough to pass the Munera design gate before implementation planning/execution.
   - If you cannot reach that state without user decisions or missing external information, stop implementation work.

7. If the design is not clean.
   - Preserve the improved design state in the worktree.
   - Commit the task-design work.
   - Push the branch.
   - Create a PR that clearly explains the unresolved ambiguities or missing decisions.
   - The PR must mention the original issue number, for example `Refs #<issue-number>` or equivalent.
   - Do not proceed into implementation.

8. If the design is clean, execute autonomously.
   - Follow the `work-independently` skill.
   - Add or refine `plan.md` only after the design is complete and unambiguous.
   - Implement the task in small, reviewable steps.
   - Keep Munera task files synchronized with what was learned and done.
   - Run relevant verification for the affected area.
   - If the task completes successfully, commit the work, push the branch, and create a PR.
   - The PR must mention the original issue number, for example `Closes #<issue-number>` or equivalent when appropriate.

9. PR requirements.
   - In either the ambiguous-design or completed-implementation case, create a PR.
   - The PR title and body should mention the original issue number.
   - The PR body should summarize:
     - the issue being addressed
     - whether the outcome is design-only or implementation-complete
     - any remaining ambiguities or follow-on work

Execution constraints:
- Prefer one issue per run unless the input explicitly asks for batch processing.
- Use `work-on` rather than manual git worktree creation.
- Do not proceed to implementation on an ambiguous design.
- Do not wait for user input once the design is clean; continue autonomously.
- Keep changes scoped to the selected issue.
- Preserve Munera protocol distinctions among `design.md`, `plan.md`, `steps.md`, and `implementation.md`.

Suggested command shapes:
- `gh issue list --state open --label implement --json number,title,labels,state,url`
- `gh issue view "$ISSUE" --json number,title,body,labels,author,assignees,state,url`
- `git fetch origin master`
- `gh pr create ...`

Final response requirements:
- Report the selected issue.
- State whether a worktree was created and give its path when available.
- Report the created Munera task id/path.
- State whether the result was design-only or implementation-complete.
- Include the branch name if pushed.
- Include the PR URL if created.
