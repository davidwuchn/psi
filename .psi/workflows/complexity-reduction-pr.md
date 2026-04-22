---
name: complexity-reduction-pr
description: Find the highest-complexity source unit over CC 25, refactor it in a worktree, and push a PR
---
{:tools ["read" "bash" "edit" "write"]
 :skills ["gordian" "rama-cc" "code-shaper" "git-workflow-guidelines"]
 :thinking-level :high}

You are executing a focused complexity-reduction workflow in this repository.

Goal:
- Find the single most complex source unit with cyclomatic complexity strictly over 25.
- If no such unit exists, stop immediately and report that there is no qualifying unit.
- If such a unit exists, create a dedicated worktree with the `work-on` extension, analyze the target and surrounding code with the `code-shaper` skill, refactor conservatively, verify the result, push the branch, and create a PR.

Required procedure:

1. Discover the target with a reduced-output Gordian complexity query.
   - Run:
     `bb gordian complexity . --source-only --min cc=25 --top 1 --sort cc-risk --edn`
   - Use this result as the authoritative target-selection step.
   - Identify the single most complex qualifying unit from the report.

2. Stop early when no target exists.
   - If the Gordian result contains no qualifying unit, stop.
   - Do not edit files.
   - Do not create a worktree, branch, push, or PR.
   - Final response should clearly say that no source unit exceeded CC 25.

3. Create an isolated worktree for the refactor.
   - Use the `work-on` extension command, not manual `git worktree` shell commands.
   - Derive a short description from the target unit, for example:
     `/work-on reduce complexity of <target>`
   - If the `work-on` command is unavailable in the current session, stop and report that limitation instead of improvising a different mechanism.

4. Analyze before changing code.
   - Use the `code-shaper` skill to assess the target unit and nearby supporting code.
   - Prefer small structural simplifications that lower complexity while preserving behavior.
   - Refactor surrounding helpers when that is the simplest way to reduce the target's complexity.
   - Avoid unrelated cleanup.

5. Verify the refactor.
   - Re-run a focused complexity check to confirm the hotspot improved.
   - Run relevant tests for the affected area when you can identify them.
   - If there is an obvious project-wide verification command that is cheap and relevant, run it too.
   - If verification fails, stop and report the failure rather than pushing broken work.

6. Prepare the branch for review.
   - Summarize what changed and why.
   - Push the current branch to origin with upstream tracking.
   - Create a pull request using `gh pr create`.
   - The PR title and body should mention the reduced-complexity target and the main refactoring approach.

Execution constraints:
- Work only on the single top qualifying unit from the Gordian output.
- Prefer root-cause simplification over superficial extraction.
- Keep changes minimal, local, and behavior-preserving.
- Do not continue to push/PR when no qualifying unit exists or verification fails.

Final response requirements:
- Report the selected target and its before/after complexity when available.
- State whether a worktree was created.
- State which verification steps ran and their results.
- If a branch was pushed, include the branch name.
- If a PR was created, include the PR URL.
