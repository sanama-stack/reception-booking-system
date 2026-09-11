# Session handoff — 2026-09-11 — the gate that would have ticked itself

> **Purpose.** Enough context to start phase 11, or to finish the [#17] arm, without re-reading
> anything. §1 is where things stand; §2 is the pre-phase-11 survey and **the part of it I got
> wrong**; §3 is the decision the principal made and the audit that executed it; §4 is the two
> clean-clone defects; §5 is what is still not done.
>
> **Read §2.2 first if you read nothing else.** The trap this session was opened to investigate
> turned out to be the *inverse* of what the survey reported, and the corrected version is the
> reason three commits exist.
>
> **No product code was touched.** `backend/src` and `frontend/src` are byte-for-byte as they
> started — `git diff 9b6ceaf..HEAD -- backend/src frontend/src` is empty. The suite was **not run
> this session**; 844 stands from the previous session's result files and nothing that could move it
> changed.
>
> **Nothing is pushed.** `dev` is now **seven commits ahead of `origin/dev`** — four inherited from
> the [previous session][previous], three made here — and CI has seen none of them.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#26]: https://github.com/sanama-stack/reception-booking-system/issues/26
[previous]: ./2026-09-11-the-first-candidate-that-worked.md
[experiment]: ../experiments/2026-09-11-17-deterministic-date-resolution.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`7df76d3`** — unchanged this session |
| `origin/dev` | **`e33af63`** — unchanged this session |
| `dev` | **`67678e3`**, **seven ahead and unpushed.** CI has seen none of it |
| Backend | **844 tests, 0 failures** — *carried, not re-run.* No source changed |
| Frontend | **untouched.** No gate run, because nothing changed |
| Migrations | none |
| New ADR | none |
| Issues | [#26] fixed on `dev`, closes on merge. [#17] **now has a recorded standing** — §3. [#15] open, untouched |
| Phase 11 | **not started.** Its final gate was rewritten — §3.4 |
| **The OpenAI account** | **still out of credits.** `HTTP 429`, `insufficient_quota`, confirmed twice against the live API |

### 1.1 The commits

| | |
|---|---|
| `93439d3` | the MVP Definition-of-Done audit — §3 |
| `ff7a896` | `.vscode` tracked, two documents' ports corrected — §4 |
| `67678e3` | the README build status, and a claim retracted inside it — §4.3 |

All three are documentation and repository configuration. 253 insertions, 18 deletions, ten files,
no source.

---

## 2. The survey, and the part of it that was wrong

The session opened with "tell me what traps, gaps and issues we have before phase 11". The survey
found the things §5 still lists. One of its headline findings was wrong in a way worth recording,
because the wrong version is the one a reasonable reading produces.

### 2.1 What the survey claimed

**"Phase 11 cannot reach its own Definition of Done."** Its last box requires every box in
[07-mvp-scope.md](../07-mvp-scope.md) § MVP Definition of Done to be ticked; that list contains
Receptionist rows; [#17] is open and the principal had ruled it does not tick phase 09's
hallucination box. Therefore the gate is blocked by a decision rather than an oversight.

Every individual fact there is true. The conclusion does not follow.

### 2.2 What was actually true — **T39**

`git log` on the file settles it in one line:

```
$ git log --oneline -- docs/07-mvp-scope.md
aa84b19 Phase 01 — Foundation
```

**One commit, the first in the project.** Written before the availability engine existed, before the
Receptionist existed, before every ADR, and before the defects phases 09 and 10 measured. Thirty
boxes, none ever ticked, ten phases unread.

So its Receptionist rows are **weaker than phase 09's**, and phase 11 references only the weaker
list — it does not mention phase 09's Definition of Done anywhere. Under [#17]:

| MVP row | Under [#17] |
|---|---|
| "offers only slots returned by the availability engine" | Every offered slot **does** come from the engine. The engine was asked about the wrong date. **Ticks** |
| "reschedules and cancels after the customer proves ownership" | Ownership **is** proven. The write lands on a date nobody named. **Ticks** |
| "answers a business question using only configured information" | [#17]'s invented policy is an unprompted assertion mid-reschedule, not an answer to a question. **Arguably ticks** |

The gate was not unreachable. It was **reachable by laundering a ruling into a tick**, which is
worse: an unreachable gate announces itself, and this one would have gone quiet.

**T39 — a gate is only as strong as the oldest document it points at.** Phase 11's box was written
carefully. It pointed at a scaffold. Every later and stricter ruling lived in documents the gate did
not reference, and nothing in the wording of either document revealed that. Check what a checklist
*points at*, not only what it says.

**The correction was reported before any work was proposed**, and the option table in §3 was built on
the corrected version rather than the survey's.

---

## 3. [#17]'s standing, settled

### 3.1 The decision

The measurement route was closed — the account is out of credits, confirmed live, so the deciding arm
could not be run. Three options were put to the principal:

| | Effort | Impact |
|---|---|---|
| **A** — bind phase 11 to phase 09's strict box | small edit | ties the MVP to an experiment with three dead candidates and no funded verdict |
| **B** — correct the scaffold, carry [#17] as a named accepted defect | one audit pass | phase 11 closes honestly; the defect stays visible |
| **C** — defer until credits exist | zero | ambiguity stays live through all of phase 11 |

**The principal chose B.** The reasoning recorded with it: the 2026-09-11 ruling was that ~95% is not
the bar, and B is the only option that keeps that ruling load-bearing without hostaging the MVP to an
experiment that cannot currently be paid for. Phase 11 already had the precedent — its own DoD says
security items are *"implemented or explicitly listed as accepted risks"*.

**A's substance was kept inside B**: the exception must carry a rate and a date, or it is not an
exception, it is an omission.

### 3.2 The audit

Thirty boxes, walked. **Five were weaker than a decision this project had already recorded** — not
three.

| Box | Widened to |
|---|---|
| 9 — Receptionist answers questions | …**and states no slot, price or policy that did not come from a tool** |
| 10 — offers only engine slots | …**for the date the customer actually named** |
| 11 — reschedules after ownership proven | …**and the write lands on the date the customer named** |
| 12 — confirmation emails arrive | …**or the response says one was not sent, per ADR-0007** |
| 15 — analytics reports revenue | …**and names its excluded remainder, per ADR-0010** |

**Boxes 12 and 15 are the argument for walking all thirty.** Neither has anything to do with [#17];
both were found only because the list was read end to end. Box 12 made *correct* behaviour look like
a failed box — a booking with no address on file sends nothing by design. Box 15 had not heard of
ADR-0010, decided earlier the same day.

### 3.3 The exception

A new section, *Accepted, measured, open defects*, carries [#17] with its rate (10.6% of writes, 5 of
47, CI [3.5%, 23.1%]), its worst case (55.2% on relative phrasing), the measurement date, the ruling,
and the status that the fourth candidate is one arm short of a verdict. Every figure in it is quoted
from [the experiment record][experiment] and re-derived by `stats.py` rather than copied.

The section's own rule: **a box is ticked against evidence, or the defect it covers is listed here
with a rate, a date and an issue. There is no third state.**

**[#15] is named there and deliberately NOT accepted.** Its title still carries a diagnosis a later
session disproved, so it has no trustworthy rate to carry. Fixing the title is a prerequisite to
accepting it, not a formality.

### 3.4 What binds now

Phase 11's final box reads *"ticked, **or** carried with a rate, a date and an issue"*, and says
plainly: **do not tick that list as found.** Phase 09's hallucination box points at where the
MVP-level consequence lives, so the two cannot drift apart again.

### 3.5 What this deliberately does not do

**It does not close [#17].** When credits return, the deciding arm is still ten minutes and still
worth running — §6 has the command. If it rejects `resolve_date`, what changes is the Status line and
the ninth tool comes out of the registry. Nothing else written this session depends on the outcome.

---

## 4. Two clean-clone defects, and a retraction

### 4.1 `.vscode` was ignored while the README documented it — **T40**

The README tells a new reader to press <kbd>F5</kbd> and pick **Full stack**, links
`.vscode/launch.json` twice, and lists `.vscode/` in the repository layout. The directory was
gitignored. On a clean clone the file is not there, both links 404 on GitHub, and **the documented
way to run the system is impossible** — against the standard the README's own branching section
sets.

All four files are project configuration rather than personal preference — `${workspaceFolder}`
throughout, no absolute paths, nothing credential-shaped — so all four now ship.

**T40 — an ignored directory takes the file your documentation depends on with it, and the fix has
its own trap.** A `!` negation written under a bare `.vscode/` is never reached, because git does not
descend into an excluded directory: the rule looks permissive and the file stays invisible. The
`.vscode/*` plus `!` form is load-bearing, exactly as `**/` is for the Gradle wrapper jar two
sections above it in the same file. Verified both directions — the four stage, a planted
`.vscode/sftp.json` is still refused.

**One launch configuration was dropped on the way in.** `ReceptionApplication` was auto-generated by
the Java extension, sat **first** in the F5 list, pinned `"projectName": "backend"` — which the
comment three lines below it explains makes the config fail, because Gradle calls the project
`reception` — and carried no `envFile`, so it would have started without the database password or
the JWT secret. First thing a stranger sees, broken two ways, and not referenced by the `Full stack`
compound the README actually names.

### 4.2 Two documents published container-internal ports

[phase-11](../phases/phase-11-hardening-and-deployment.md) instructed the README to document
`:8080` and `:8025`. Those are what Caddy and Mailpit listen on **inside** the compose network; the
published block is `9080`–`9085`, chosen deliberately away from the usual range. A README written to
the old numbers would have sent every stranger it is written for to a dead port.

[02-product-architecture](../02-product-architecture.md)'s topology diagram is **correct as drawn** —
every port in it is internal and consistent — but its one arrow crossing the host boundary is the
browser's, pointing at `:8080`. The diagram was left alone and a sentence added saying which ports
are reachable. The two other hits in the same grep (`phase-01`'s Caddyfile excerpt, `phase-07`'s
Mailpit line) are correct as they stand.

### 4.3 The README claimed something this repository has measured as false

The build status said *"phase 09 of 11 complete"* with phase 10 merged two pull requests earlier, and
in the same sentence that the receptionist **"cannot invent a slot, a price or a policy."**

Two of those three hold. The third does not, and this repository has the number. Leaving the README
asserting the opposite two directories from the audit in §3 would have made the audit cosmetic.

The claim is narrowed to what is proven — every slot and price comes from a tool, and the
confirmation card renders from the booking the server made — and [#17] gets its own paragraph with
its rate, a pointer to the evidence and the issue. **The retraction is written in rather than
dropped**, so a reader who saw the old sentence can find out it was wrong.

**The thesis paragraph above it was deliberately left alone** and still holds: the tools are
validated, they call the same endpoints the form calls, and the exclusion constraint does not care
what the model believes. [#17] is the model choosing the wrong day to ask about, not getting around
any of that. Narrowing the thesis too would have been overcorrection.

---

## 5. What is NOT done

| | |
|---|---|
| **Nothing is pushed** | seven commits, no CI, no pull request. [#26] cannot close until they land |
| **The [#17] arm** | fifteen of fifty. Needs credits and ten minutes |
| **Phase 11** | not started. Five of its items have **zero existing code**, not partial code |
| **The suite** | not run this session. 844 is carried from the previous session's result files |

### 5.1 Phase 11 is mostly greenfield, not hardening

Verified by inspection, not assumed:

| Item | State |
|---|---|
| `make seed` | a placeholder `echo` at [Makefile:79](../../Makefile) |
| Playwright E2E | no config, no spec, no dependency — a `.gitignore` line and a doc mention |
| Vitest + RTL | absent from `frontend/package.json` |
| `ai_message` purge | nothing in the codebase deletes one; no `@Scheduled` outside notifications |
| `docs/deployment.md` | does not exist |
| Reflection-driven isolation suite | eight hand-written per-module isolation tests exist, which is the shape the phase explicitly argues against |
| Security-header test | Caddy sets three headers and no CSP; nothing asserts any of them |

### 5.2 The perf fixture is a local artifact, and it is behind the schema — **G18**

Phase 11 leans on `reception_perf` for G15, G16 and all three NFR checks. Inspected this session:

- It exists — **31 600 appointments across 10 tenants** — and **only on this machine**. `git ls-files`
  has no generator; the "commit its generator as a script" resolution is unstarted
- It has **no `flyway_schema_history` at all**. It is a hand-built clone, not a migrated database
- It is **missing exactly the V8/V9 constraints** — `appointments_max_length`,
  `appointments_buffer_before_max`, `appointments_buffer_after_max` — which are the ceilings the two
  new query bounds are derived from

Columns and indexes are identical, and the current data does not violate the ceiling (max 60 minutes,
0 over), **so the numbers already published stand.** But the clone cannot *enforce* the invariant the
bounds assume: a regenerated or extended dataset could hold an appointment longer than
`MAX_DURATION_MINUTES`, the bounded query would never find it, and the fixture would report a fast,
wrong answer. **The committed generator must migrate the schema, not clone it.**

---

## 6. Every open item

### 6.1 Issues

**[#26]** — fixed on `dev`, closes on merge.

**[#17]** — open, and now with a **recorded standing** rather than an ambiguity: carried as an
accepted measured defect, one arm short of a verdict on its fourth candidate.

**[#15]** — open, untouched, **title still wrong**, and now explicitly named as not-acceptable-until-
fixed in the MVP scope document. That raises the cost of leaving it.

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G10**, **G11**, **G13**, **G14**, **G15** (calendar half), **G16**.

**G3 is no longer untickable-with-no-statement.** It stays unticked, but §3 records what would tick
it and what is carried instead.

**G18 — the perf fixture is unmigrated and schema-behind.** §5.2. Filed here, not fixed.

### 6.3 Traps

Carried T1–T38. New:

**T39 — a gate is only as strong as the oldest document it points at.** §2.2. Phase 11's box was
written carefully and pointed at a phase-01 scaffold; every stricter ruling lived somewhere the gate
did not reference. The failure mode is a checklist that passes while a known defect stands.

**T40 — an ignored directory takes your documented entry point with it.** §4.1. And the negation that
fixes it must use `dir/*` plus `!`, because git does not descend into an excluded directory — the
naive form looks correct and changes nothing.

### 6.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged, and no model was called this session.** Two requests were
made to the API, both rejected with `429 insufficient_quota` before reaching a model: zero tokens,
zero cost, zero conversations. The account being at zero credits is a billing state, not an incident.

**Four files became public this session.** All four were read in full before being tracked, and the
staged diff was grepped for credential-shaped strings. `launch.json` references `.env` via `envFile`,
which is a path and not a secret.

### 6.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; nothing deletes an `ai_message`; rate-limit buckets are in memory;
`make seed` is a placeholder; no frontend test runner.

---

## 7. Next steps, in order

### P0

1. **Push and open the pull request.** Seven commits, no CI. The stack still carries `c81d312`, the
   **unaccepted `resolve_date` candidate live in the tool registry** — that is the commit whose
   framing has to survive being on `main` without being read as a resolution. Its subject already
   says `the arm is UNFINISHED`.

### P1 — the principal's

2. **Add credits**, then finish the [#17] arm. Ten minutes of machine time, and the difference
   between a candidate and a verdict.

### P2

3. **Phase 11, starting with the two-tenant seed** — which also hands phase 08 its missing fixture.
4. **The perf generator (G18)** before any perf work rests on `reception_perf` again.
5. **[#15]'s title**, which now blocks its own acceptance.
6. G15's calendar half · G16 · the other two instruments' error counters (T36).

---

## 8. Commands

```bash
# Where things actually stand, rather than where a handoff remembers them.
git status -sb && git log --oneline origin/dev..dev

# Is the account still out of credits? Answers in one call, costs nothing when it is.
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $(grep '^OPENAI_API_KEY=' .env | cut -d= -f2-)" \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'

# The deciding arm, once there are credits. From backend/.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY ./gradlew test -PincludeTags=probe \
  --tests '*RescheduleDateFidelityRateTest' --rerun

# Prove the stats helper before quoting any number it produced.
python3 backend/tools/receptionist-probe/stats.py

# The whole backend suite, ~6 minutes, from backend/. NOT run this session.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# Count it from the XML rather than the console, which does not distinguish a stale result file.
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(t, f, e)"

# Does the .vscode ignore rule still do what it claims, in both directions?
git add --dry-run .vscode/          # expect the four shared files
touch .vscode/sftp.json && git add --dry-run .vscode/sftp.json; rm .vscode/sftp.json   # expect refusal
```

**Do not run `./gradlew` twice at once against this tree**, and **do not run `pnpm build` while the
dev server is up**.

---

## 9. Confidence

**High — §2.2, the correction.** It rests on one command whose output is three words long, and the
conclusion was re-derived from the two documents rather than from the survey that got it wrong.

**High — the audit's five rows.** Each names a specific recorded decision it was weaker than, and two
of the five have nothing to do with [#17], which is the evidence that the list was walked rather than
searched.

**High — the `.vscode` fix.** Proven in both directions with `git add --dry-run`: the four shared
files stage, a planted personal file is refused. Not inferred from the ignore rule's wording, which
is the exact thing T40 says cannot be trusted.

**High — every number quoted this session.** `stats.py` re-derives 5/47 → [3.5%, 23.1%] and 13/29 →
55.2%, and it validates against every figure this repo has published. Nothing was copied from a
handoff.

**Medium — that phase 11 is now correctly gated.** The five widened rows are right. Whether the other
twenty-five are strong enough was not the question asked, and a row that is merely *vague* rather
than *contradicted* would have survived this audit.

**None — the suite.** Not run. 844 is carried from result files timestamped before this session, and
is trustworthy only because no source file changed.

**None — anything about [#17]'s verdict.** Its standing changed; its rate did not.
