---
name: gh-issue-ingest
description: Find labeled GitHub enhancement-ingest requests, triage them, reply on GitHub, and advance labels
---
{:tools ["read" "bash"]
 :skills ["issue-feature-triage"]
 :thinking-level :high}

You are executing a focused GitHub enhancement-triage workflow in this repository.

Goal:
- Find open GitHub requests marked for ingest and enhancement triage.
- Analyze the request with the `issue-feature-triage` skill.
- Post a structured triage reply to the GitHub issue.
- Remove the `triage` label and add a `waiting` label.

Use the `issue-feature-triage` skill when shaping the analysis.

Primary selection rule:
- Look for open GitHub issues carrying both the `triage` label and the `enhancement` label.
- If there are no matching issues, stop and report that there is nothing to process.
- If multiple matching issues exist, process them in ascending issue-number order unless the input narrows the target.

Input expectations:
- `$INPUT` is optional.
- If provided, treat it as an optional narrowing hint such as an issue number, repo-qualified issue reference, full issue URL, or a short instruction that identifies a specific matching issue.
- If `$INPUT` is absent, discover candidate issues from labels.

Required procedure:

1. Discover candidate issues.
   - Use `gh issue list` with JSON output to find open issues with both `triage` and `enhancement` labels.
   - Retrieve enough list information to identify candidates, including at least: number, title, labels, state, and URL.
   - Treat the current repository as authoritative unless the input explicitly identifies another repository.
   - If the request input narrows the target, use it to select exactly one matching issue.

2. Read the selected issue.
   - Use `gh issue view` with JSON output.
   - Retrieve enough detail to analyze the request, including at least: number, title, body, labels, author, assignees, state, and URL.
   - If the issue cannot be read, stop and report the failure instead of fabricating details.

3. Analyze the request.
   - Use the `issue-feature-triage` skill.
   - Distill the issue into exactly these four surfaces:
     - intent
     - problem statement
     - scope
     - acceptance criteria
   - Keep the analysis faithful to the request.
   - Distinguish in-scope work from adjacent but out-of-scope ideas.
   - If the issue is too ambiguous to produce a grounded triage reply, stop and report the ambiguity rather than inventing requirements.

4. Compose the GitHub reply.
   - Write a concise, structured comment suitable for the issue thread.
   - The comment should clearly present:
     - intent
     - problem statement
     - scope
     - acceptance criteria
   - Keep the tone collaborative and concrete.
   - Avoid implementation planning, code-level prescriptions, or Munera-task creation.
   - Make it clear that the issue has been triaged and is waiting for confirmation or further input.

5. Post the reply.
   - Use `gh issue comment` to post the triage comment to the selected issue.
   - If posting the comment fails, stop and report the failure.

6. Advance labels.
   - Remove the `triage` label from the issue.
   - Add the `waiting` label to the issue.
   - Prefer minimal label edits and preserve unrelated labels.
   - If label changes fail after the comment is posted, report the partial-success state clearly.

Execution constraints:
- Do not create a Munera task.
- Do not edit repository files as part of the issue-processing flow.
- Do not invent requirements beyond what the issue supports.
- Prefer one issue per run unless the input explicitly asks for batch processing.
- If the selected item is not an issue that can be replied to and relabeled through `gh issue` commands, stop and report that mismatch.

Suggested GitHub command shapes:
- `gh issue list --state open --label triage --label enhancement --json number,title,labels,state,url`
- `gh issue view "$ISSUE" --json number,title,body,labels,author,assignees,state,url`
- `gh issue comment "$ISSUE" --body-file <path-or-stdin>`
- `gh issue edit "$ISSUE" --remove-label triage --add-label waiting`

Final response requirements:
- Report the issue that was processed.
- Summarize the posted triage briefly.
- State whether the GitHub comment was posted.
- State whether labels were updated from `triage` to `waiting`.
- If nothing matched, say so clearly.
