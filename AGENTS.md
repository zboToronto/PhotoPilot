# PhotoPilot Git workflow

## Repository and layout
- Repository root: C:/Users/zbo/Desktop/EECS3311.
- Existing remote: https://github.com/zboToronto/PhotoPilot.git (origin).
- Work on the existing main branch, tracking origin/main.
- The Java Maven project is Stage2/. Keep that existing local path.
- Preserve Stage1/ and all existing Git history. Do not modify Stage1 unless explicitly requested.
- Never initialize or clone a repository inside Stage1 or Stage2. Do not create nested repositories.
- Labs/, Project/, Slides/, tmp/ and .photopilot-integration/ are local-only workspace material.

## Authorization
- Do not commit or push automatically. Setup, edits, reviews, and verification alone do not authorize a commit or push.
- When the user explicitly says "上传" or "上传到GitHub" as an upload instruction, inspect the changes, run appropriate verification, create a meaningful commit, and push to the existing origin/main branch.
- Never force push, rewrite history, amend published commits, or reset away local work.

- An explicit upload request authorizes routine inspection, verification, commit, and push without another routine confirmation.
- Commit only meaningful new changes. If there are no changes, report that there is nothing to commit.
- Stop for authentication problems, unresolved merge conflicts, destructive operations, or unexpected repository states.

## Before an authorized upload
1. Confirm the repository root, origin URL, current branch, upstream, and working-tree status. Review diffs and all proposed new files.
2. Fetch origin and compare main with origin/main. Fast-forward only when safe. If histories diverge or conflicts require a decision, report the issue; never rebase or force push.
3. Check ignore rules and scan proposed content for credentials, API keys, private user data, local caches, and build artifacts. Ignore rules do not protect secrets embedded in source or already tracked files. Never print secret values.
4. Run verification appropriate to the change. For Java changes, use JDK 21 and run `./mvnw.cmd test` from Stage2 on Windows (or `sh ./mvnw test` elsewhere). Use the project's existing cache/tool configuration. Do not claim unrun checks passed.
5. Stage only reviewed, intended files using explicit paths. Inspect the staged diff and `git diff --cached --check`. Preserve unrelated user edits.
6. Create a meaningful commit describing the actual changes, then use `git push origin main`. Do not bypass rejected pushes or failed checks.
7. Verify the commit exists on origin/main, branches are synchronized, Stage1 is unchanged, intended Stage2 files are tracked, and no secrets or generated artifacts were included. Report the commit hash and URL, branch, verification results, final Git status, and actual push status. If push fails, retain the local commit and explain the failure.

## Authentication
- Use Git Credential Manager with Windows Credential Manager for HTTPS authentication.
- Let the user complete required browser authorization. Never request tokens in chat, embed credentials in remote URLs, or write secrets into repository files.
- If author identity is missing, ask for the user's intended Git name and email; do not invent them.
