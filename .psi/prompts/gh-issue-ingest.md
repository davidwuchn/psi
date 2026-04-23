---
name: gh-issue-ingest
description: Example invocations for the gh-issue-ingest workflow.
lambda: Show how to invoke the gh-issue-ingest workflow to process labeled GitHub enhancement-ingest issues and post a triage reply.
---
Use this prompt when you want to run the `gh-issue-ingest` workflow against GitHub issues labeled for ingest and enhancement triage.

Example invocations:

- Process the next matching issue in the current repository:
  - `Run workflow gh-issue-ingest`
  - `Use gh-issue-ingest`

- Narrow to a specific matching issue number:
  - `Run workflow gh-issue-ingest for issue 123`
  - `Use gh-issue-ingest on 123`

- Repo-qualified issue reference:
  - `Run workflow gh-issue-ingest for hugoduncan/psi#123`

- Full URL:
  - `Run workflow gh-issue-ingest for https://github.com/hugoduncan/psi/issues/123`

Expected outcome:
- the workflow finds an open issue with both `triage` and `enhancement` labels
- analyzes the request with the `issue-feature-triage` skill
- posts a structured triage reply to the GitHub issue
- removes the `triage` label
- adds the `waiting` label

When invoked without an explicit issue reference, the workflow should discover and process the next matching issue automatically.
