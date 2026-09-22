# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

Infer the repo from `git remote -v`; `gh` does this automatically when run inside a clone.

### Reference issues without closing verbs

GitHub auto-closes an issue when a commit message or a PR body merged to the default branch contains
`Fix #N`, `Closes #N`, `Resolves #N` or any of their variants. **This repo does not want that.** A fix
landing is not a decision that the issue is done: a defect here stays open carrying a rate until
somebody rules on it, and the ruling is the principal's.

Three issues were closed by this mechanism on 2026-09-18/19 while every document still described them
as open — the state `docs/07-mvp-scope.md` calls *unnoticed*, arriving from the other direction.

**Every example below writes the number as `<n>`**, deliberately, so that copying a line out of this
file into a commit message cannot close anything. See *The examples are written inert* at the end.

- **Write `#<n>`, not `Fix #<n>`** — in commit subjects, commit bodies and PR descriptions. Say what
  the commit does and put the issue in parentheses: `Search from the date the customer asked for (#<n>)`.
- **The verb fires from anywhere in the text, including inside a possessive.** PR #43's body bound a
  closing verb to issue 15's *title*. The sentence named the **title**. GitHub closed the **issue**.
- **The verb does not expire when its reasoning does.** A subject reading *"close 40 into 17"*
  re-closed issue 40 on merge two days after that closure had been retracted on the issue — from the
  same PR that carried the retraction's own evidence.
- **Backticks and quotation marks are not an escape.** A verb inside a code span, a blockquote or a
  quoted sentence still fires. There is no way to write the live string safely.
- **Close deliberately instead**: `gh issue close <n> --comment "<the ruling>"`. A close with no
  comment is a close nobody can audit, which is what each of the three above looked like.

### Describing one of these incidents without causing another

**Paraphrase the verb. Never quote it.** An incident report that quotes the string that caused the
incident is executable, and it runs on merge.

This is not hypothetical and it is the reason this subsection exists. **The commit that first added
this rule closed three issues with its own message** (`05f8a2a`, merged in #44 on 2026-09-22). It
explained the three incidents above by quoting them verbatim, so its body carried three live verbs
into the default branch. The PR description had been checked and was clean; the commit messages had
not been — although the first bullet above names **commit subjects, commit bodies and PR
descriptions**, and only the third was ever scrubbed.

Write *"a closing verb bound to issue 15's title"*, or spell the number without its `#`. Both read
fine and neither closes anything.

### Check before you merge, not after

Both surfaces, every time. The PR body is the one people remember; the commit messages are the one
that actually caused the incident above.

```sh
# every commit going to the default branch, plus the PR body
git log --format=%B origin/main..HEAD | grep -inE '\b(fix(e[sd])?|clos(e[sd]?)|resolv(e[sd]?))\b[[:space:]:]*#[0-9]+'
gh pr view <n> --json body -q .body            | grep -inE '\b(fix(e[sd])?|clos(e[sd]?)|resolv(e[sd]?))\b[[:space:]:]*#[0-9]+'
```

No output is the passing result. **A hit in a commit message that has already been pushed cannot be
edited away** — rewriting it means a force-push, so the realistic recovery is to merge, then reopen
each issue with a comment saying the closure was a parser and not a ruling.

### The examples are written inert

Every issue reference in this section is `#<n>` or a bare number rather than `#` plus digits. That is
not stylistic: the failure above happened because a document describing the defect was summarised
into a commit message, carrying the live strings with it. **A rule written in a form that is
dangerous to quote will eventually be quoted.** Keep it that way when editing this file.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests; `/triage` reads this flag.)_

When set to `yes`, PRs run through the same labels and states as issues, using the `gh pr` equivalents:

- **Read a PR**: `gh pr view <number> --comments` and `gh pr diff <number>` for the diff.
- **List external PRs for triage**: `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments` then keep only `authorAssociation` of `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, or `NONE` (drop `OWNER`/`MEMBER`/`COLLABORATOR`).
- **Comment / label / close**: `gh pr comment`, `gh pr edit --add-label`/`--remove-label`, `gh pr close`.

GitHub shares one number space across issues and PRs, so a bare `#42` may be either: resolve with `gh pr view 42` and fall back to `gh issue view 42`.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: a single issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body. `gh issue create --label wayfinder:map`.
- **Child ticket**: an issue linked to the map as a GitHub sub-issue (`gh api` on the sub-issues endpoint). Where sub-issues aren't enabled, add the child to a task list in the map body and put `Part of #<map>` at the top of the child body. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`). Once claimed, the ticket is assigned to the driving dev.
- **Blocking**: GitHub's **native issue dependencies**, the canonical, UI-visible representation. Add an edge with `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, where `<blocker-db-id>` is the blocker's numeric **database id** (`gh api repos/<owner>/<repo>/issues/<n> --jq .id`, _not_ the `#number` or `node_id`). GitHub reports `issue_dependencies_summary.blocked_by` (open blockers only, the live gate). Where dependencies aren't available, fall back to a `Blocked by: #<n>, #<n>` line at the top of the child body. A ticket is unblocked when every blocker is closed.
- **Frontier query**: list the map's open children (`gh issue list --state open`, scoped to the map's sub-issues / task list), drop any with an open blocker (`issue_dependencies_summary.blocked_by > 0`, or an open issue in the `Blocked by` line) or an assignee; first in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me`, the session's first write.
- **Resolve**: `gh issue comment <n> --body "<answer>"`, then `gh issue close <n>`, then append a context pointer (gist + link) to the map's Decisions-so-far.
