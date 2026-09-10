# Session handoff — 2026-09-10 — The wrong diagnosis in three places, and not one model call

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the decisions; **§3 is what this session actually did — it corrected records rather
> than code, and §3.4 is the one new defect it found**; §4 is every defect, gap, problem and trap in
> the project, carried and new; §6 is what a fresh session must not redo.
>
> **Nothing was measured here, and no model was called.** Not one API request, not one conversation,
> not one cent. Every number in this document is quoted from the previous handoff or computed
> arithmetically. Treat it as a bookkeeping session and read [the previous one][prev] for the
> evidence.
>
> **The dirty tree is down to one file, and it is still the unmeasured candidate.** §1.2.
>
> **`dev` is three ahead of `origin` and unpushed, and the full build has not run since before
> phase 09 merged.** §1.1.

[prev]: ./2026-09-10-the-saturday-that-never-left.md

---

## 1. Where the project stands

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | **`aa5d22d`** — the phase 09 merge commit. Untouched this session |
| `dev` | **`a7f493e`** — **three commits ahead of `origin/dev`, unpushed.** CI has seen none of them |
| Working tree | **DIRTY — one modified file, and it is the unmeasured one.** §1.2 |
| Backend | **801 tests** — **carried, not counted here.** The full build did not run. §5 |
| Level 3 | 10 tests. **Not run this session** |
| Frontend | **Untouched.** No frontend file was opened |
| Migrations | **V7**, unchanged. No migration |
| Issues open | **#15 only — and its body is now correct.** §3.1 |
| Phase 09 | Complete. **G3 still unticked**, and nothing this session changed that. §4.3 |

### 1.1 What this session did

Four things, none of which changed how the product behaves:

1. **Surveyed what is open** across the docs, the phase plans, every handoff and the tracker, and
   found the register in [the previous handoff][prev] §4 accurate and complete.
2. **Corrected issue #15** — title, body and a comment recording the correction. §3.1.
3. **Corrected the same wrong claim in the source**, at `SystemPromptBuilder.java:169`. §3.2.
4. **Committed the previous session's orphaned work**, which had been sitting untracked. §3.3.

**Three commits, all on `dev`, none pushed:**

```
5eb735f  Read the weekday sample size from the environment
585705b  Record the session that merged phase 09 and re-measured #15
a7f493e  Correct what the seven-day list's comment says is left
```

### 1.2 The dirty tree — one file, unchanged, still unmeasured

```
 M backend/src/main/java/dev/reception/ai/tools/FindAvailableSlotsTool.java   UNMEASURED
```

**This file was not touched this session and its status has not changed.** It is the candidate
prompt change described in [the previous handoff][prev] §3.4: it points `date_from`'s own schema
description at the seven-day list. **Zero live trials.** Everything the previous handoff said about
it still holds, verbatim:

> Either finish the measurement or revert. Committing it unmeasured would repeat exactly the mistake
> §3 is about.

```bash
git checkout -- backend/src/main/java/dev/reception/ai/tools/FindAvailableSlotsTool.java
```

What *did* change is that it is now **alone** in the tree. Before this session it shared the tree
with two safe files and an untracked document, and a `git commit -a` would have swept it in. That
was the point of §3.3.

---

## 2. Decisions

### 2.1 The principal's — correct #15 rather than close and refile

Both were open. Correcting keeps the issue number, its labels and the thread; refiling would have
left a closed issue carrying a wrong diagnosis that search will still surface. Correcting won.

### 2.2 Mine — rewrite the title too, not only the body

The instruction was "fix the body". The title carried the same wrong claim — *"is sometimes read
from the top, so a named weekday resolves to tomorrow"* — and a body contradicting its own heading
is worse than either alone. The new title names neither mode: *"A named weekday resolves to the
wrong row of the seven-day list about 5% of the time"*. **Reported as done rather than done
quietly.**

### 2.3 Mine — preserve the original body rather than delete it

Under a collapsed `<details>` fold marked *Superseded*, with a line saying the claims below are
wrong. A correction that erases what it corrected cannot be checked, and this project has now made
the same sampling error three times — the record of it is worth more than the tidiness.

### 2.4 Mine — comment on the issue rather than edit silently

GitHub keeps an edit history and nobody reads it. The comment states what changed, what was added
that was not there before, and that the first action for whoever takes it is a *decision*, not a
measurement.

### 2.5 Mine — three commits, not one

Matching the last five commits on this branch, each a single idea with a prose body carrying its own
measurements. One commit spanning a test constant, a source comment and a session document would
have hidden all three.

### 2.6 Mine — did not fix the p-value ambiguity in the file I was committing

§3.4. It was offered and not taken up, so the file went in as it stood and the **commit message**
names the ambiguity instead. Editing a file the principal had just asked me to commit as-is would
have been the wrong kind of initiative.

---

## 3. What this session did

### 3.1 #15 rewritten — the tracker no longer points the wrong way

[Issue #15][15] said the SATURDAY failure mode was gone and the residual was the model taking the
**first** row of the seven-day list. It said so on **two failures in fifty**. The previous session
re-measured at 150 and found the Saturday is still the *majority* mode — 5 of 8 failures against the
first row's 3, pooled 142/150 = 94.7%.

The body now carries, in this order: a warning banner; the corrected 150-conversation table with
per-trial failure indices; why the original diagnosis was wrong, generalised to a rule; the location,
**including a pointer to the source comment that carried the same claim**; the instrument rules; the
sample-size arithmetic; the untried candidate; the "do not" list; and the original text folded away.

Two things were added that the original body did not have, and both change how it must be worked:

- **The probe cannot screen this.** 119/119 against the very prompt that fails ~5% live. The
  original body *recommended* using it.
- **Fifty conversations cannot decide it**, and 150/arm can only prove a candidate *perfect*. The
  original body's proposed budget — "a probe batch, then one fifty-conversation run, under ten
  minutes" — would have produced a green number meaning nothing.

Labels unchanged: `bug`, `ready-for-agent`. **It is still open**, and §7 says why that is now a
decision rather than a task.

[15]: https://github.com/sanama-stack/reception-booking-system/issues/15

### 3.2 The same wrong claim in the source, corrected

`SystemPromptBuilder.java:169` ended the seven-day-list comment block with:

> *WHAT IS LEFT is a different mistake and a smaller one: both remaining failures searched
> 2026-09-11, the FIRST row rather than the named one.*

Same claim, same two failures, and **#15's own "Where" section sent readers straight to it**. It is
now the 150-conversation result, the observation that the order fix took the Saturday from eight in
fifty to five in a hundred and fifty rather than removing it, the generalised rule, and four lines
on how to measure a change to that line — because the obvious instrument is the wrong one.

**Comment-only. The prompt output is byte-identical**, and the level-2 test asserting the list's
field order is untouched.

**Swept for the same claim everywhere else it could live** — `backend/src` (the probe's own
README included), `backend/tools`, `docs/phases`, `docs/adr`, `CONTEXT.md` and `README.md`.
**Nothing else repeats it.**
`docs/sessions/` was deliberately excluded: handoffs are dated records of what was believed at the
time, and the newest one is the correction.

### 3.3 The previous session's work, committed

[The previous handoff][prev] ended with no commit at all, leaving its own record **untracked** and
`PROBE_CONVERSATIONS` uncommitted beside the unmeasured candidate. Two consequences, both now gone:

- The handoff existed **only on this disk**. A clone had no record of the 150-conversation finding.
- **#15's own run recipe did not work on a clean clone.** It tells the reader to set
  `PROBE_CONVERSATIONS=150`; without the commit the sample was hardcoded to 50 — the number the same
  issue says is not enough.

**A broken table, found while committing.** The index row for the previous handoff sat behind a
blank line, orphaning it from the markdown table — it would have rendered as a separate one-row
table rather than the bottom of the index. Removed.

### 3.4 The one new defect — a p-value with no tail

`WeekdayResolutionRateTest`'s Javadoc and the corrected prompt comment give **two different numbers
for the same comparison**:

| Where | 48/50 vs a perfect 50/50 |
|---|---|
| `WeekdayResolutionRateTest.java:61` | `p = 0.49` |
| `SystemPromptBuilder.java:185`, and [the previous handoff][prev] §3.3 | `p = 0.25` |

Both are right. **0.2475 one-sided, 0.4950 two-sided** — I recomputed both. The defect is that the
Javadoc states a bare number and the rest of the project is explicitly one-sided, so the two read as
a contradiction to anyone comparing them. One word fixes it. §2.6 says why it was not fixed here.

---

## 4. Every defect, gap, problem and trap

### 4.1 Open issues

| # | State | What |
|---|---|---|
| **[#15]** | **OPEN, body now correct** | §3.1. Was the wrong diagnosis; is now the right one. **What it is not is actionable** — §7 P1 says the next step on it is a decision about what "fixed" means, because §3.3 of the previous handoff shows it may not be closable at any affordable sample |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15

### 4.2 Unfiled defects

| # | Severity | Where | State |
|---|---|---|---|
| **D1** | **High, methodological** | `tools/receptionist-probe/README.md` | **STILL OPEN — carried and not fixed here.** The README says the probe *"exaggerates at both ends"*. It does not say the probe **saturates** — 119/119 on a 95% prompt — and is therefore useless below roughly one-in-ten. **Verified still absent by grep this session.** The next session will otherwise budget a probe screen it cannot use |
| **D2** | High, methodological | #15's body | **FIXED.** §3.1 |
| **D3** | Low | `WeekdayResolutionRateTest` | **STILL OPEN.** Measures one utterance, "next Monday", on whatever weekday the run lands on. Both recorded measurements fell on a **Thursday**, where Monday is row 4. The rate is unknown for every other day, and the list shifts daily |
| **D4** | **NEW**, Low | `WeekdayResolutionRateTest.java:61` | A bare `p = 0.49` where the rest of the project is one-sided and says 0.25. §3.4 |

### 4.3 Gaps

| # | Where | What, and why it is open |
|---|---|---|
| **G1** | Phase doc, *Frontend* | **No inline tool activity.** Carried, still deliberate — needs streaming first |
| **G2** | Phase doc, *Level 3* | **No live cancel or reschedule.** Carried. The only remaining phase-09 test gap |
| **G3** | Phase doc, *DoD* | **Still unticked, and this session did nothing for it.** It waits on a corpus you can trust; the weekday case fails ~5% live, so the corpus assertion goes red about 1 run in 20. Tick it only by deciding explicitly that ~95% is the bar |
| **G4** | Level 3 corpus | Closed, carried |
| **G5** | Level 3 corpus | Closed, carried |
| **G6** | Working tree | **The candidate has zero trials.** Carried, unchanged, and now alone in the tree. §1.2 |
| **G7** | **NEW** | **The full build has not run since before phase 09 merged.** 801 has been carried through three consecutive handoffs without being counted. §5 |

### 4.4 Checked, and not defects

- **The superseded claim elsewhere in the tree.** Swept the code, the tools, the phase docs, the
  ADRs, `CONTEXT.md` and `README.md`; only the corrected line matches. §3.2. Recorded so nobody
  sweeps again.
- **`Optional` in `WeekdayResolutionRateTest`.** The env-var change introduced a call to
  `Optional.ofNullable` and the import was already at line 15. Checked because it was committed
  before the test sources had been compiled — see T11.
- **The prompt output after the comment fix.** Comment-only; the `prompt.append` calls are
  untouched and the level-2 order assertion still holds.

### 4.5 Traps

| # | Trap |
|---|---|
| **T1** | **One live-model result cannot judge a prompt change.** Carried |
| **T2** | **A probe that simplifies the tool set lies.** Carried |
| **T2b** | **A faithful probe still lies about magnitude.** Carried |
| **T2c** | **At a low failure rate the probe saturates.** 119/119 on a prompt that fails 5% live. Below one in ten, do not screen — measure. Carried, and **still not in the probe's own README** (D1) |
| **T3** | **A green level-3 run proves nothing until `skipped` is checked.** Carried |
| **T4** | **A test that only ever passes in isolation may be failing in the corpus.** Carried |
| **T5** | **Preserve the result XML per run.** Carried |
| **T6** | **The IDE-launched backend does not re-read `.env`.** Carried |
| **T7** | **`ProbeFixtureDumpTest` comes back `FROM-CACHE` and silently regenerates nothing.** Carried. Always `--rerun` |
| **T8** | **Gradle does not stream the rate test's output.** Carried. Read the XML |
| **T9** | **Two failures do not characterise a distribution.** Carried, and it is what this whole session was about |
| **T10** | **NEW: `./gradlew compileJava` returns `UP-TO-DATE` and proves nothing.** It is the same family as T7, one task over. A compile that reports `BUILD SUCCESSFUL in 1s` may have done no work at all. Force it with `--rerun-tasks` and confirm the task line has no `UP-TO-DATE` on it; the `.class` mtime against the source mtime is the independent check |
| **T11** | **NEW: `compileJava` does not compile the tests.** A change to a file under `src/test` is unverified until `compileTestJava` runs, and it is easy to commit in between. A missing import in a `probe`-tagged test would not surface until someone runs the probe |
| **T12** | **NEW: a blank line silently breaks a markdown table.** A row separated from the table by an empty line renders as its own one-row table. It looks fine in a diff. §3.3 |

### 4.6 Security

**S1 — the `OPENAI_API_KEY` is still the one that was pasted into a chat transcript.** Unchanged,
still a known accepted risk: the principal decided against rotation. **It was not used this session
— no model was called.** `.env` untracked, `.env.example` still holds the placeholder. **Do not
assume it was rotated.**

### 4.7 Carried, and still carried

Unchanged, none of them touched this session:

- **The advisory lock's cost under ordinary load is unmeasured.**
- **There is no "find my booking" page.** Phase 10 or 11.
- **The value half of `PublicFieldAllowListTest` still only catches four planted strings** —
  verified still true by reading the constants this session.
- **The sweep only sees paths the fixture actually produces.**
- **`sessionStorage` holds a transcript containing a customer's name and number.**
- **Nothing deletes an `ai_message`**, and nothing is meant to yet. Retention is unaddressed —
  verified: no delete path exists in the migrations or the Java.
- **The prompt is 12 rules and has been changed by three consecutive sessions**, every time forced
  by a model limitation.
- **Phases 10 and 11 are unstarted**, 54 and 57 unticked boxes. The whole MVP Definition of Done in
  `07-mvp-scope.md` is unticked, all 30 boxes — that is phase 11's final walkthrough, not 30
  separate defects.
- **Phase 08's *"Any available" shows a specific employee*** box is unticked: no tenant has two
  employees on one service, so the fixture does not exist and that branch has never been exercised.

---

## 5. What is verified, and how

**The compiles, forced rather than believed.** `compileJava --rerun-tasks` and
`compileTestJava --rerun-tasks`, both exit 0, both with a task line carrying no `UP-TO-DATE`. The
first attempt returned `UP-TO-DATE` in 1s and was discarded as evidence — T10. The `.class` mtime
was checked against the source mtime as an independent confirmation (23:06:46 against 23:05:53). The
only warning is pre-existing and unrelated: `ApiException` has no `serialVersionUID`.

**The arithmetic, against a known answer first.** The Fisher helper reproduces the recorded
42/50-against-48/50 pair at p = 0.0458 before being trusted, then gives 0.2475, 0.0148 and 0.1412 —
all three matching [the previous handoff][prev] §3.3, one-sided.

**The issue, read back after writing.** Title, `bug` + `ready-for-agent`, one comment, 11 898-byte
body, and the `Superseded` fold present.

**The sweep, by grep across the code, the tools, the phase docs, the ADRs, `CONTEXT.md` and
`README.md`.** §3.2.

**NOT verified — and this is the weak part of this session:**

- **The full build did not run.** **801 is carried for the fourth consecutive handoff.** The changes
  here are a comment, a test constant defaulting to its previous value, and documents — but nobody
  has counted since before phase 09 merged. G7.
- **Level 3 did not run.** **The corpus has not run since the prompt comment changed** — harmless,
  since only a comment changed, but it has also not run since the merge.
- **The candidate has zero trials.** Unchanged.
- **The frontend was not opened. `pnpm build`, unrun as ever.**
- **Nothing was pushed. CI has seen none of the three commits.**

---

## 6. What a fresh session must not redo

- **Do not commit `FindAvailableSlotsTool` as it stands.** §1.2. Zero trials. Unchanged advice from
  the previous handoff, and it is now the *only* thing in the tree, so `git commit -a` catches it.
- **Do not re-sweep for the superseded first-row claim.** §3.2 did it across code, tools, phase
  docs, ADRs, `CONTEXT.md` and `README.md`. Only
  `SystemPromptBuilder.java:169` had it, and it is fixed.
- **Do not "fix" #15's body again.** It is correct as of this session. Read it before assuming.
- **Do not screen a rare failure mode with the probe.** Below about one in ten it saturates.
- **Do not judge a weekday prompt change on fifty conversations.**
- **Do not reverse the seven-day list lines.** `2026-09-14 is a MONDAY`, not `MONDAY 2026-09-14`.
- **Do not simplify the seven-day list out of the prompt**, and **do not reword rule 11 or rule 3.**
- **Do not chase the live/probe fidelity gap.** There is nothing there. The gap is sampling.
- **Do not trust a `BUILD SUCCESSFUL` that says `UP-TO-DATE`.** T10.
- **Do not re-derive the confirmation card from `reply`, add a tool-activity indicator, pass
  `email={null}`, or put `Button`'s size back to `sm`.** Carried; no frontend file was touched.
- **Do not assume the `OPENAI_API_KEY` was rotated.** It was not, deliberately.
- **Do not run `pnpm build` while a dev server is up**, and do not run the corpus and the probe
  tests together.

---

## 7. Next steps, in order

### P0

1. **Run the full build and push.** `dev` is three ahead, CI has seen none of it, and **801 has been
   carried for four handoffs without being counted** (G7). The three commits are low-risk — a
   comment, a defaulted constant, documents — which is exactly why this is cheap to do and
   embarrassing to skip.
2. **Deal with the candidate.** Finish the measurement or revert. It is now alone in the tree, which
   makes it both safer and easier to lose track of.

### P1

3. **Decide what "fixed" means for #15, before measuring anything.** At 150 conversations per arm —
   twelve minutes and real money — the only detectable outcome is a **perfect** candidate. A change
   from 95% to 98% is real, worth having, and invisible. The issue body now states this plainly, but
   states it as a question. **Someone has to answer it**, and "accept ~95% and close" is a legitimate
   answer.
4. **Measure a stronger model against the same 150-conversation harness.** Same cost as one more
   candidate arm, and it might end this thread instead of extending it. `gpt-4o-mini` failing one
   weekday resolution in twenty is the whole reason four sessions have now touched this prompt.
   **Still never run.**
5. **Fix D1** — the probe README does not say the probe saturates. Two sentences, and it is the one
   thing most likely to waste the next session's first twenty minutes.
6. **Decide G3** — tick it by stating ~95% as the bar, or leave it open deliberately. Do not leave
   it waiting on #15, which may not be closable.
7. **A live cancel and reschedule test** (G2). The only remaining phase-09 test gap.

### P2

8. **Fix D4** — one word, and it stops two files reading as a contradiction.
9. **Phase 10** — calendar, analytics, dashboard polish. Nothing blocks it.
10. **Retention.** Nothing deletes an `ai_message`.

---

## 8. Files, and what changed

Committed, three commits on `dev`:

```
5eb735f  backend/src/test/java/dev/reception/ai/probe/WeekdayResolutionRateTest.java
585705b  docs/sessions/2026-09-10-the-saturday-that-never-left.md   (was untracked)
         docs/sessions/README.md                                    (index row, blank line removed)
a7f493e  backend/src/main/java/dev/reception/ai/application/SystemPromptBuilder.java
```

Modified and uncommitted:

```
backend/src/main/java/dev/reception/ai/tools/FindAvailableSlotsTool.java   UNMEASURED candidate
```

Changed outside the repository: **issue #15's title and body, plus one comment.**

Added: this document, and its index row. No migration. No frontend file. **No production behaviour
changed anywhere** — the only `src/main` edit is a comment.

---

## 9. Commands, and what they last returned

### 9.1 The gates — neither run this session

```bash
# From backend/. What CI runs. ~5 minutes. NOT RUN since before phase 09 merged.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for x in glob.glob('build/test-results/test/*.xml'):
    r = ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors')); s+=int(r.get('skipped'))
print(t,f,e,s)"
```

### 9.2 Verifying a compile actually happened — T10

```bash
# From backend/. UP-TO-DATE means nothing was done; --rerun-tasks forces it.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava compileTestJava --rerun-tasks
```

Confirm the output has a bare `> Task :compileJava` with no `UP-TO-DATE`, and cross-check:

```bash
stat -f "%Sm %N" -t "%H:%M:%S" build/classes/java/main/dev/reception/ai/application/SystemPromptBuilder.class \
                                src/main/java/dev/reception/ai/application/SystemPromptBuilder.java
```

Last returned exit 0 for both, class newer than source.

### 9.3 The rate test — the only instrument that works on the weekday problem

```bash
# From backend/. 50 conversations ~5 min; 150 ~12 min. PROBE_CONVERSATIONS is now COMMITTED.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=150 JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew test -PincludeTags=probe --tests '*WeekdayResolutionRateTest' --rerun
```

**The console shows only `BUILD SUCCESSFUL`** — T8. Read the result out of the XML:

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for x in glob.glob('build/test-results/test/*WeekdayResolutionRateTest*.xml'):
    so = ET.parse(x).getroot().find('system-out').text
    print('\n'.join(l for l in so.splitlines() if 'WRONG' in l or 'resolved' in l))
"
```

Last returned **48 of 50, then 94 of 100** — the previous session. Not run here.

### 9.4 Fisher's exact test — one-sided

Validated against the recorded 42/50-vs-48/50 pair at p = 0.0458 before use, every time:

```python
from math import comb
def fisher(a_ok, a_n, b_ok, b_n):
    a_bad, b_bad = a_n - a_ok, b_n - b_ok
    total, n = a_bad + b_bad, a_n + b_n
    return sum(comb(b_n, k) * comb(a_n, total - k)
               for k in range(0, b_bad + 1) if 0 <= total - k <= a_n) / comb(n, total)
```

Last returned 0.2475 (48/50 vs 50/50), 0.0148 (144/150 vs 150/150), 0.1412 (144/150 vs 148/150).

### 9.5 The tracker

```bash
gh issue view 15 --json number,title,body,labels,comments --jq '...'
gh issue edit 15 --title "..." --body-file <file>
gh issue comment 15 --body "..."
```

Last returned #15 open, `bug` + `ready-for-agent`, one comment, 11 898-byte body.

### 9.6 The database — not used this session

```bash
docker exec -e PGPASSWORD=reception reception-postgres-1 psql -U reception -d reception -c '…'
```
There are **three** Postgres containers on this machine; `docker ps | grep postgres | head -1` picks
the wrong one.

---

## 10. Confidence

**High — the two corrections say what the 150 conversations say.** Both were written from the
previous handoff's §3.1 table, and the arithmetic in both was recomputed rather than copied.

**High — nothing else in the tree carries the superseded claim.** Grep across the code, the tools,
the phase docs, the ADRs, `CONTEXT.md` and `README.md`; the only hit is the corrected line itself.
§3.2.

**High — the compiles.** Forced, exit 0, and cross-checked against the class mtime. §5.

**High — the commits are low-risk.** One comment, one constant that defaults to its previous value,
two documents. No production behaviour changed.

**Moderate — that 801 still holds.** It is four handoffs old. Nothing here should have moved it, but
nobody has counted, and "should not have" is what G7 is about.

**None — the candidate.** Zero trials, unchanged.

**None — the frontend, `pnpm build`, level 3, the full build, and CI.** Not run. Nothing pushed.

**Not applicable — anything about model behaviour.** **No model was called this session.** Every
rate in this document is quoted from [the previous handoff][prev]. If you need a number you can act
on, run §9.3 yourself.

---

## 11. The verification tenant

**`Phase 06 Scratch` was not touched.** Nothing was driven in a browser, no application server was
started, and no API request left this machine except to GitHub. The counts the earlier handoffs
recorded still stand.
