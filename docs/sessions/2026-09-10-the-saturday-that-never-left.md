# Session handoff — 2026-09-10 — The Saturday that never left, and a residual filed on two failures

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the decisions; **§3 is the finding that matters most — issue #15's diagnosis is
> wrong, and 150 live conversations say so**; §4 is every defect, gap, problem and trap in the
> project, carried and new; §6 is what a fresh session must not redo.
>
> **Phase 09 is merged.** Pull request [#16] landed on `main` as `aa5d22d`, eleven commits, all
> three required checks green. `main` is current for the first time since phase 08.
>
> **The working tree is dirty and one of the two changes in it is UNMEASURED.** §1.2. Read that
> before running anything.
>
> **No fix was shipped for #15.** The measurement that would have decided it was stopped by the
> principal partway through, so this session ends with a better question and no answer.

[#16]: https://github.com/sanama-stack/reception-booking-system/pull/16

---

## 1. Where the project stands

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | **`aa5d22d`** — the phase 09 merge commit. Current |
| `dev` | **`a0a3305`** — clean of commits, **in sync with `origin/dev`**, one merge commit behind `main` |
| Working tree | **DIRTY — two modified files, one of them unmeasured.** §1.2 |
| Backend | **801 tests**, 0 failures, 0 errors, 0 skipped — unchanged, and not re-run this session |
| Level 3 | 10 tests, 0 skipped. **Not run this session** |
| Frontend | **Untouched.** No frontend file was opened |
| Migrations | **V7**, unchanged. No migration |
| Issues open | **#15 only.** #13 and #14 closed by their trailers when [#16] merged |
| Phase 09 | Complete. **G3 still unticked** — see §4.3 |

### 1.1 What this session did

1. Pushed ten commits, opened [#16], watched CI, and merged it with a merge commit.
2. Took #15, and **did not fix it**. What it produced instead is §3: a measurement that says the
   issue describes the wrong bug, and a demonstration that one of the project's three instruments
   cannot see this class of problem at all.

### 1.2 The dirty tree, and which half is safe

```
 M backend/src/main/java/dev/reception/ai/tools/FindAvailableSlotsTool.java     UNMEASURED
 M backend/src/test/java/dev/reception/ai/probe/WeekdayResolutionRateTest.java  safe
```

**`WeekdayResolutionRateTest` is safe to commit.** It reads `PROBE_CONVERSATIONS` from the
environment, defaulting to the same 50 it always used. Nothing else changed. The environment rather
than a system property because Gradle hands the test JVM the former and not the latter.

**`FindAvailableSlotsTool` is a candidate prompt change that has never been measured, and it must
not be committed as it stands.** It points `date_from`'s own schema description at the seven-day
list:

```
"First date to search, as YYYY-MM-DD in the business's own timezone. If the customer named a day
 rather than a date, this is the date on that day's line in the seven-day list above — copy it
 from there."
```

The reasoning behind it is in §3.4 and it is still the right candidate. **Zero live trials were
run against it** — the 150-conversation arm was stopped before it wrote a result, so there is not
even a partial number. Either finish the measurement or revert:

```bash
git checkout -- backend/src/main/java/dev/reception/ai/tools/FindAvailableSlotsTool.java
```

**Committing it unmeasured would repeat exactly the mistake §3 is about.**

---

## 2. Decisions

### 2.1 The principal's — lift the hold on the pull request

Push and merge, after two sessions of holding. Done: [#16], `aa5d22d`.

### 2.2 The principal's — measure #15 properly rather than ship on judgment

Offered four ways to spend the budget — measure at ~150 per arm, ship on judgment with a 50-run
sanity check, try a stronger model first, or accept 96% and close — the principal chose to measure.
That decision is what produced §3.1, which no cheaper option would have found.

### 2.3 Mine — the merge is a merge commit, not a squash

Matching `3efcfd2` and every merge since phase 05. Squashing is what caused the conflicts phase 05
ended.

### 2.4 Mine — no `--admin` bypass

The first merge attempt was refused: a third required check, **Compose smoke test**, had not
reported yet. `gh` offered `--admin`. It was not used; the check finished, the state went `BLOCKED`
→ `CLEAN`, and the merge went through normally. **The refusal was a real race, not a broken gate.**

---

## 3. The findings

### 3.1 The Saturday never left, and #15 describes the wrong bug

This is the finding to carry furthest.

Issue #15 says the Saturday failure mode is gone and the residual is the model taking the **first**
row of the seven-day list. It says so on the strength of **two failures** in fifty conversations,
both of which were the first row.

Re-measured on the same day, the same prompt, the same model, at three times the sample:

| Run | Trials | Correct | Failures, by which row |
|---|---|---|---|
| A | 50 | 48 | `2026-09-11` ×2 — the first row |
| B | 100 | 94 | **`2026-09-12` ×5 — the SATURDAY row** — and `2026-09-11` ×1 |
| **Pooled** | **150** | **142 (94.7%)** | **Saturday ×5, first row ×3** |

Per-trial, so the evidence survives this document: run A failed at trials 9 and 29; run B at 5, 61,
65, 73, 75 and 94, of which only 61 was the first row.

**The Saturday is not gone. It is still the majority failure mode.** The pooled 94.7% is consistent
with the recorded 96% — that half of #15 holds. What does not hold is the story about *which*
mistake remains, and therefore the whole framing of the issue as "a different and rarer mistake".

Two failures can establish that a mode exists. They cannot establish that it is the only one, and
they certainly cannot establish that another mode is gone. **#13 was closed on 10 of 10. The
residual was filed as a separate bug on 2 of 50. This is the same error twice, one level apart, and
this is the third time the project has paid for it.**

### 3.2 The probe cannot see this at all

Before spending live budget, the probe was run against the **current** prompt — the arm now known
to fail 5% of the time — across four utterances at thirty trials each:

| Utterance | Expected | Probe result |
|---|---|---|
| "What have you got free next Monday?" | MONDAY | 30 / 30 |
| "Do you have anything on Wednesday?" | WEDNESDAY | 30 / 30 |
| "Anything free on Thursday?" | THURSDAY | 30 / 30 |
| "What about Sunday?" | SUNDAY | 29 / 29 that searched (1 asked a question first) |

**119 of 119.** The instrument whose job is to choose between candidates reports a flawless score
for a prompt that fails once in twenty live.

This is sharper than the trap T2b already records. The README says the probe *exaggerates*; what it
does at this end of the scale is **saturate** — it has no resolution left, so no candidate can beat
the baseline and the screen is not merely optimistic but unusable. Any future work on a failure
mode rarer than about one in ten cannot be screened by the probe, and the session must budget for
live runs from the start.

### 3.3 Fifty trials cannot detect an improvement from 96%

Computed with Fisher's exact test, one-sided, and the helper validated against the recorded
42/50-against-48/50 pair, which it reproduces at p = 0.0458:

| Comparison | p |
|---|---|
| 48/50 vs a perfect 50/50 | **0.25** — no result |
| 144/150 vs a perfect 150/150 | **0.015** |
| 144/150 vs 148/150 — a real but partial improvement | **0.14** — no result |

Two things follow. The issue's own budget — "a probe batch, then one fifty-conversation run, under
ten minutes" — **cannot decide this question**, and a session that spends it will get a green number
that means nothing. And even at 150 per arm the experiment can only prove a candidate **perfect**;
a genuine improvement from 95% to 98% is invisible at any sample this project can afford. That
should shape what counts as a fix here.

### 3.4 The candidate, and why it is aimed where it is

`date_from`'s schema description said only *"First date to search, as YYYY-MM-DD in the business's
own timezone."* It is silent about the seven-day list, and it sits at the exact point the
constrained decoder fills the slot — forty lines below where the list lives.

The probe README's own hardest-won lesson is that *"the model fills six arguments at once under a
constrained decoder, and that is where it goes wrong"*. The list is in the system prompt; nothing at
the decode point points at it. So the candidate puts the instruction in the argument description
itself. It names neither failure mode, which is what makes it a reasonable aim at both — and §3.1
means a candidate must now address both.

**It is unmeasured. It is a hypothesis, not a fix.**

---

## 4. Every defect, gap, problem and trap

### 4.1 Open issues

| # | State | What |
|---|---|---|
| **[#15]** | **OPEN, and its diagnosis is wrong** | Titled and written as "read from the top, so a weekday resolves to tomorrow". §3.1 shows the dominant residual is still the SATURDAY mis-scan. **The issue body needs correcting before anyone works from it** — it currently sends a reader after the rarer of two modes and tells them the commoner one is fixed |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15

Closed by [#16]'s merge: **#13** (the prompt stated today's date without its weekday) and **#14**
(tool errors dropped their field detail).

### 4.2 Found here, unfiled

| # | Severity | Where | What |
|---|---|---|---|
| **D1** | **High, methodological** | `tools/receptionist-probe/README.md` | The README says the probe exaggerates. It also **saturates** — 119/119 on a 95% prompt — and is therefore useless below roughly a one-in-ten failure rate. The README should say so; a future session will otherwise budget a probe screen it cannot use. §3.2 |
| **D2** | **High, methodological** | #15's body | Filed on two failures, and wrong. §3.1. Not a code defect, but it is a defect in the tracker and it will cost the next session a run |
| **D3** | Low | `WeekdayResolutionRateTest` | Measures one utterance, "next Monday", on whatever weekday the run happens to fall. Both measurements here landed on a Thursday, where Monday is row 4. **The rate is not known for any other day of the week, and the list shifts daily** |

### 4.3 Gaps

| # | Where | What, and why it is open |
|---|---|---|
| **G1** | Phase doc, *Frontend* | **No inline tool activity.** Carried, still deliberate — needs streaming first |
| **G2** | Phase doc, *Level 3* | **No live cancel or reschedule.** Carried. The only remaining phase-09 test gap |
| **G3** | Phase doc, *DoD* | **Still unticked, and the case for ticking it got weaker.** It waits on a corpus you can trust. §3.1 says the weekday case fails ~5% of the time, so the corpus assertion goes red about **1 run in 20**. Tick it only by deciding explicitly that ~95% is the bar — "when #15 closes" is no longer a cheap route, because §3.3 says #15 may not be closable at any sample this project can afford |
| **G4** | Level 3 corpus | Closed, carried |
| **G5** | Level 3 corpus | Closed, carried |
| **G6** | **NEW** | **The candidate in §1.2 has zero trials.** The work is set up and not done |

### 4.4 Checked, and not defects

- **`ConversationService.start()` writes no messages.** Verified by reading it: the live message
  list at the first `respond()` is `[system, user]`, the same shape the probe replays. This was
  checked only to rule out a structural explanation for §3.2 before accepting it, and it is **not**
  a reopening of the live/probe fidelity question, which stays closed.
- **The probe's "no search" trials.** Carried. The model asks which service first. Reported, never
  scored.
- **The first merge refusal on [#16].** A required check that had not reported yet, not a broken
  gate. §2.4.

### 4.5 Traps

| # | Trap |
|---|---|
| **T1** | **One live-model result cannot judge a prompt change.** Carried, and §3.1 is the third time |
| **T2** | **A probe that simplifies the tool set lies.** Carried |
| **T2b** | **A faithful probe still lies about magnitude.** Carried |
| **T2c** | **NEW, and the sharpest form: at a low failure rate the probe saturates and lies about direction being detectable at all.** 119/119 on a prompt that fails 5% live. Below one in ten, do not screen — measure. §3.2 |
| **T3** | **A green level-3 run proves nothing until `skipped` is checked.** Carried |
| **T4** | **A test that only ever passes in isolation may be failing in the corpus.** Carried |
| **T5** | **Preserve the result XML per run.** Carried, and honoured — both baseline runs were copied out before the next `--rerun` ate them. The copies were in a session scratchpad and are gone; §3.1 has the numbers because they were written down here |
| **T6** | **The IDE-launched backend does not re-read `.env`.** Carried |
| **T7** | **NEW: `ProbeFixtureDumpTest` comes back `FROM-CACHE` and silently regenerates nothing.** It reported `BUILD SUCCESSFUL` in 1s having done no work, leaving day-old fixtures in place. **Always pass `--rerun`** |
| **T8** | **NEW: Gradle does not stream the rate test's output.** `WeekdayResolutionRateTest` prints to stdout and the console shows only `BUILD SUCCESSFUL`. The per-trial log and the final rate are in `build/test-results/test/*.xml` under `system-out`, and a grep over the console output silently returns nothing |
| **T9** | **NEW: two failures do not characterise a distribution.** A sample large enough to *find* a mode is not large enough to say it is the *only* one, or that another is gone. §3.1 |

### 4.6 Security

**S1 — the `OPENAI_API_KEY` is still the one that was pasted into a chat transcript.** Unchanged,
still a known accepted risk: the principal decided against rotation. It was used for every
measurement here. `.env` untracked, `.env.example` still holds the placeholder. **Do not assume it
was rotated.**

### 4.7 Carried, and still carried

- **The advisory lock's cost under ordinary load is unmeasured.**
- **There is no "find my booking" page.** Phase 10 or 11.
- **The value half of `PublicFieldAllowListTest` still only catches the four planted strings.**
- **The sweep only sees paths the fixture actually produces.**
- **`sessionStorage` holds a transcript containing a customer's name and number.**
- **Nothing deletes an `ai_message`**, and nothing is meant to yet. Retention is unaddressed.
- **The prompt is 12 rules and has been changed by three consecutive sessions**, every time forced
  by a model limitation. §7 now puts a number on why that matters.

---

## 5. What is verified, and how

**Measured against a live model, 150 conversations.** §3.1's two baseline runs went through the
real `ConversationService`, reading `tool_arguments->>'date_from'` back out of `ai_messages`. The
50-run arm reproduced the previously recorded 48/50 exactly, including the failure dates — an
independent replication, on a different day's run, of a number this project had recorded once.

**The statistics helper validated against a known input.** It reproduces the recorded
42/50-against-48/50 pair at p = 0.0458 before being trusted on anything new.

**The merge verified by inspection, not by the absence of an error.** `main` is `aa5d22d` with both
parents present, the PR reports `MERGED`, and the tracker went from three open issues to one.

**Not verified.** The candidate in §1.2 — **zero trials**. The full build was not re-run this
session, so 801 is carried from the last one rather than counted here. Level 3 was not run. The
frontend was not opened. `pnpm build`, unrun as ever.

---

## 6. What a fresh session must not redo

- **Do not commit `FindAvailableSlotsTool` as it stands.** §1.2. It is a hypothesis with no trials.
- **Do not screen a rare failure mode with the probe.** §3.2. Below about one in ten it saturates.
  This is the specific mistake that would waste the first twenty minutes of the next session.
- **Do not judge a weekday prompt change on fifty conversations.** §3.3. It cannot see the effect.
- **Do not trust #15's body.** §3.1. Correct it first, or work from this document instead.
- **Do not reverse the seven-day list lines.** `2026-09-14 is a MONDAY`, not `MONDAY 2026-09-14`.
  Measured at 48/50 against 42/50, with a test asserting the order.
- **Do not simplify the seven-day list out of the prompt**, and **do not reword rule 11 or rule 3.**
- **Do not chase the live/probe fidelity gap.** Prompt, model, tools and messages were verified
  byte-identical a session ago; `start()` writing no messages was confirmed here. There is nothing
  there. The gap is sampling, not fidelity.
- **Do not re-derive the confirmation card from `reply`, add a tool-activity indicator, pass
  `email={null}`, or put `Button`'s size back to `sm`.** Carried; no frontend file was touched.
- **Do not assume the `OPENAI_API_KEY` was rotated.** It was not, deliberately.
- **Do not run `pnpm build` while a dev server is up**, and do not run the corpus and the probe
  tests together.

---

## 7. Next steps, in order

### P0

1. **Deal with the dirty tree.** Either finish the §1.2 measurement or revert the candidate. Leaving
   an unmeasured model-behaviour change in the working tree is how it gets committed by accident.
2. **Correct #15's body**, or close it and refile. As written it points the next reader at the
   rarer of two modes and tells them the commoner one is fixed. §3.1 has the replacement numbers.

### P1

3. **Decide what "fixed" means here, before measuring anything else.** §3.3 is the uncomfortable
   part of this session: at 150 conversations per arm — twelve minutes and real money — the only
   detectable outcome is a **perfect** candidate. A change from 95% to 98% is real, worth having,
   and invisible. Someone has to decide whether this project chases a mode it cannot measure.
4. **The §8 question, now with a number on it.** Three consecutive sessions have changed the prompt,
   every time forced by a model limitation, and the fourth attempt cannot be measured at a sane cost.
   `gpt-4o-mini` failing one weekday resolution in twenty is the whole reason this thread exists.
   **Measuring a stronger model against the same 150-conversation harness is the same cost as one
   more candidate arm and might end the thread instead of extending it.** That is the highest-value
   experiment available, and it has never been run.
5. **Decide G3** — tick it by stating ~95% as the bar, or leave it open deliberately. Do not leave
   it waiting on #15, which may not be closable.
6. **A live cancel and reschedule test** (G2). The only remaining phase-09 test gap.

### P2

7. **Phase 10** — calendar, analytics, dashboard polish. Nothing blocks it, and `main` is current.
8. **Retention.** Nothing deletes an `ai_message`.

---

## 8. Files, and what changed

Committed: **nothing.** No commit was made this session.

Merged to `main`: [#16], eleven commits, `aa5d22d`.

Modified and uncommitted:

```
backend/src/main/java/dev/reception/ai/tools/FindAvailableSlotsTool.java   UNMEASURED candidate
backend/src/test/java/dev/reception/ai/probe/WeekdayResolutionRateTest.java  PROBE_CONVERSATIONS
```

Added: this document. No migration. No frontend file.

---

## 9. Commands, and what they last returned

### 9.1 The gates

```bash
# From backend/. What CI runs. ~5 minutes. Not run this session; 801 is carried.
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

### 9.2 The rate test — the only instrument that works on this problem

```bash
# From backend/. 50 conversations ~5 min; 150 ~12 min. PROBE_CONVERSATIONS is new.
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

Last returned: **48 of 50**, then **94 of 100**. §3.1.

### 9.3 The probe — and what it is no longer good for

```bash
# From backend/. ALWAYS --rerun; without it the dump silently does nothing (T7).
./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest' --rerun

# From backend/tools/receptionist-probe/.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../../../.env | cut -d= -f2-)
./probe.py "What have you got free next Monday?" MONDAY 30
```

Last returned **30/30 on a prompt that fails 5% of the time live.** §3.2. Useful for a mode that
fails often; blind to one that fails rarely.

### 9.4 Fisher's exact test

One-sided, comparing two arms. Validated against the recorded 42/50-vs-48/50 pair at p = 0.0458:

```python
from math import comb
def fisher(a_ok, a_n, b_ok, b_n):
    a_bad, b_bad = a_n - a_ok, b_n - b_ok
    total, n = a_bad + b_bad, a_n + b_n
    return sum(comb(b_n, k) * comb(a_n, total - k)
               for k in range(0, b_bad + 1) if 0 <= total - k <= a_n) / comb(n, total)
```

### 9.5 The database

```bash
docker exec -e PGPASSWORD=reception reception-postgres-1 psql -U reception -d reception -c '…'
```
There are **three** Postgres containers on this machine; `docker ps | grep postgres | head -1` picks
the wrong one. Not used this session.

---

## 10. Confidence

**High — the merge.** Inspected rather than inferred: `aa5d22d`, both parents, `MERGED`, three
required checks green, tracker down to one issue.

**High — §3.1.** 150 live conversations, and the 50-run arm independently reproduced a previously
recorded number including its failure dates. The Saturday's reappearance is not a fluke of one run;
it is five failures in one hundred.

**High — §3.2.** 119 of 119, four utterances, against a prompt measured at ~95% live in the same
hour.

**High — §3.3.** Arithmetic, with the helper checked against a known answer first.

**Moderate — that the pooled 94.7% is the true rate.** It is one day, one utterance, one weekday
position in the list. D3.

**None — the candidate.** Zero trials. Nothing about it is known.

**None — the frontend, `pnpm build`, level 3, and the full build.** Not run.

---

## 11. The verification tenant

**`Phase 06 Scratch` was not touched.** Nothing was driven in a browser. Every live model call ran
against Testcontainers or against the API directly from the probe, and no application server was
started. The counts the earlier handoffs recorded still stand.
