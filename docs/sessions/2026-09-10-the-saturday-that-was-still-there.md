# Session handoff — 2026-09-10 — The Saturday that was still there, and the three instruments

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the three decisions the principal made; **§3 is the finding that matters most — the
> corpus was re-run, and the fix the last session measured at 10 of 10 was still failing**; §4 is
> every defect, gap and trap; §6 is what a fresh session must not redo.
>
> **G5 is closed.** The level-3 corpus has now run against the changed prompt, thirteen times.
>
> **Nothing was pushed.** `dev` is **ten** ahead of `origin/dev` — nine, plus this document — and
> the hold on the pull request has not been lifted.
>
> **The probe harness is committed at last**, after two sessions of being rebuilt from a handoff.

---

## 1. Where the project stands

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | `3efcfd2` — a phase behind |
| `dev` | **`b7a4258`** — four commits added here, working tree clean, **not pushed, ten ahead** |
| Backend | **801 tests**, 0 failures, 0 errors, **0 skipped** — unchanged in count |
| Level 3 | **10 tests, 0 skipped**, run thirteen times. Green in eleven; §4.1 is the two |
| Frontend | **Untouched.** No frontend file was opened |
| Migrations | **V7**, unchanged. No migration |
| Issues open | **#13, #14** — both fixed on `dev`, both closing on merge — and **#15**, new and open |
| Phase 09 | Complete. **G3 still unticked**, and §4.3 says why that is now a deliberate call |

### The commits

```
17c24b8  Let the booking test's customer name a time
6b2a2dc  Put the date before the day name in the seven-day list
7f5412b  Commit the probe, and say what it is not for
b7a4258  Record the session that re-ran the corpus and found the Saturday still there
```

### The one thing that is genuinely new to know

**There are three instruments, they disagree, and knowing which one to believe is most of what this
session learned.** The corpus tells you a case is broken and can never tell you a rate. The probe
tells you which candidate is better and lies about how much. Only a fifty-conversation run tells you
the truth. All three are now committed, with a README that leads on exactly this
(`backend/tools/receptionist-probe/README.md`).

---

## 2. Decisions

### 2.1 The principal's — the flaky booking test

Fix the fixture, rather than change the prompt to propose one slot, accept the flake, or investigate
whether it pre-dated the prompt change. §3.1 is what the fixture fix was.

### 2.2 The principal's — the residual on #13

Chase it with the harness rather than record the ratio and move on. That decision is the reason §3.2
exists, and it was the right call: what looked like a 12% tail turned out to be a 16% one with a
findable cause.

### 2.3 The principal's — commit, and file the residual separately

#13 keeps its `Closes` trailer and closes on merge, because what it names is genuinely fixed. The
residual is a different mistake and became [#15].

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15

---

## 3. The findings

### 3.1 The booking test was stalling one turn short of the write

`a customer books by conversation` wrote no row in two corpus runs of four. A transcript dump said
why, and it was the fixture rather than the product.

Asked for "the morning", the model lists **every** slot in it — twelve, at fifteen-minute steps —
and asks which one. The fixture's next line was `"That's fine."`, which refers to nothing when
twelve things were offered, so the model asked again and the conversation ended before
`create_appointment`. **The model was right to refuse to pick a slot on a customer's behalf; the
fixture was wrong to assume it had.**

The accepting turn now names 09:00, which is free by construction, and the case went from **2 of 4**
to **4 of 4**. The named time is asserted as well as the row, read back in the business timezone —
which is coverage the vague phrasing could not give, because it now proves the appointment landed on
the time the customer asked for rather than merely that some appointment landed.

**What was given up**: no corpus case now covers a customer accepting an offer loosely. The Javadoc
says so rather than leaving it to be discovered.

### 3.2 #13's fix worked and did not finish, and 10 of 10 is what hid it

Re-running the corpus for the first time since the prompt changed, "next Monday" still searched
**2026-09-12** — the same Saturday as the original incident — in two live runs of sixteen.

**By this point the model is not doing arithmetic.** It is reading the wrong line out of a list that
already contains the right one, and the line it takes is the `SATURDAY` row two above the `MONDAY`
row it wanted. The last handoff's diagnosis — "a weak adder" — had stopped being true when the list
went in, and the fix that followed from it therefore stopped short.

So the change is to the shape of the data, not the force of the instruction:

```
- MONDAY 2026-09-14          →     - 2026-09-14 is a MONDAY
```

The date goes first, where it is the thing to be **copied**; the day name second, where it is the
thing to be **matched**.

**Fifty live conversations each way, one line different and nothing else:**

| List line | Correct | Every failure was |
|---|---|---|
| `- MONDAY 2026-09-14` | **42 / 50** (84%) | `2026-09-12`, the SATURDAY row |
| `- 2026-09-14 is a MONDAY` | **48 / 50** (96%) | `2026-09-11`, the **first** row |

Fisher one-sided **p = 0.046**. A sterner rule 11 was measured too and reached 19 of 20 against this
line's 20 of 20, which is the argument for changing the data and leaving the rule alone.

### 3.3 The three instruments, and which one to believe

This is the finding worth carrying furthest, because it is not about dates at all.

| | probe | live rate test |
|---|---|---|
| `- MONDAY 2026-09-14` | 29/40 (73%) | 42/50 (84%) |
| `- 2026-09-14 is a MONDAY` | 119/120 (99%) | 48/50 (96%) |

**The probe got the direction and the rough size right and exaggerated both ends.** It also returned
**80 of 80** for a prompt that a fourth batch, against a byte-identical prompt, scored **39 of 40** —
so a single probe batch is not a reliable probe result, never mind a live one.

Hours were spent trying to explain the 119/120 against 14/16 as a fidelity gap. It was not one: the
prompt was dumped from the running builder and diffed byte-for-byte against what the probe replays,
and the model, the tool JSON and the message list all matched. **The probe was simply optimistic and
the live sample was simply too small.** Sixteen runs cannot separate 84% from 96%; fifty can.

---

## 4. Every defect, gap and trap

### 4.1 Fixed here

| # | Severity | Where | What |
|---|---|---|---|
| **C1** | Medium | `LiveReceptionistTest:94` | The booking fixture accepted an offer of twelve slots with "That's fine", and stalled. §3.1 |
| **C2** / [#13] residual | **High** | `SystemPromptBuilder:153` | The seven-day list was read two rows off. 42/50 → 48/50. §3.2 |

[#13]: https://github.com/sanama-stack/reception-booking-system/issues/13

### 4.2 Filed and open

| # | What |
|---|---|
| **[#15]** | The list is sometimes read from the **top** — a named weekday resolves to tomorrow, 2 times in 50. `ready-for-agent`, and its body carries both measurement tables and the instrument rules |

### 4.3 Gaps

| # | Where | What, and why it is open |
|---|---|---|
| **G1** | Phase doc, *Frontend* | **No inline tool activity.** Unchanged, still deliberate — needs streaming first |
| **G2** | Phase doc, *Level 3* | **No live cancel or reschedule.** Unchanged. The only remaining phase-09 test gap |
| **G3** | Phase doc, *DoD* | **Still unticked, and now on purpose.** The attribution box wants a corpus you can trust. The weekday case will still go red about **1 run in 25** — better than 1 in 6, not deterministic. Tick it when #15 closes, or decide explicitly that 96% is the bar |
| **G4** | Level 3 corpus | Closed, unchanged |
| **G5** | Level 3 corpus | **Closed.** The corpus ran thirteen times against the changed prompt, 10 tests and 0 skipped every time |

### 4.4 Checked, and not defects

- **The probe's "no search" trials.** The model asks which service before searching. A legitimate
  turn the single-turn probe cannot continue, not a resolution failure. Reported, never scored.
- **The live/probe disagreement.** Chased hard and it is not a fidelity gap. §3.3.
- **801, not 803.** The two new probe classes are tagged `probe` and excluded, so the corpus count
  and the build count are both unmoved. Verified by running the full build after adding them.

### 4.5 Traps

| # | Trap |
|---|---|
| **T1** | **One live-model result cannot judge a prompt change.** Carried, and it earned its place twice more |
| **T2** | **A probe that simplifies the tool set lies.** Carried. `ProbeFixtureDumpTest` now makes the faithful dump the easy path |
| **T2b** | **NEW, and the sharper form: a faithful probe still lies about magnitude.** 73%/99% against 84%/96%, and an 80/80 that was really 39/40. Screen with it; never quote its ratio as a rate |
| **T3** | **A green level-3 run proves nothing until `skipped` is checked.** Carried |
| **T4** | **A test that only ever passes in isolation may be failing in the corpus, and vice versa.** The booking case was 5 of 5 alone and 2 of 4 in the suite. Measure it where it runs |
| **T5** | **Preserve the result XML per run.** Two failures this session were diagnosed only on a re-run because the next `--rerun` had already overwritten the evidence |
| **T6** | **The IDE-launched backend does not re-read `.env`.** Carried. Irrelevant to Gradle runs |

### 4.6 Security

**S1 — the `OPENAI_API_KEY` is still the one that was pasted into a chat transcript.** Unchanged and
still a known accepted risk: the principal decided against rotation, and it was used for every
measurement here. `.env` untracked, `.env.example` still holds the placeholder.

**The new `.gitignore` entry was checked with `git check-ignore -v`** — the probe fixtures are
outputs, they contain the full system prompt, and they do not enter the repository.

---

## 5. What is verified, and how

**Counted, not believed.** `./gradlew build`, three times across the session, most recently after
the probe classes were added: **801 tests, 0 failures, 0 errors, 0 skipped**, parsed from XML.

**Watched failing first.** The level-2 order assertion was run against a reversed builder and seen
red, then the builder was restored and `diff`-ed byte-identical — because "I put it back" is exactly
the claim that is usually true and occasionally not.

**Measured against a live model.** §3.2's fifty-and-fifty, and §3.3's four probe batches.

**Smoke-tested end to end.** `ProbeFixtureDumpTest` was run and its `prompt.txt` diffed against the
expected new order; `probe.py` was then run against those fixtures, 8 of 8.

**Not verified.** The frontend, untouched. `pnpm build`, for the same reason as ever. And whether
the 96% holds for utterances other than "next Monday" — "tomorrow" and "Wednesday" were 20 of 20
each **in the probe**, which §3.3 says is a screen.

---

## 6. What a fresh session must not redo

- **Do not reverse the seven-day list lines.** `2026-09-14 is a MONDAY`, not `MONDAY 2026-09-14`.
  It is 48 of 50 against 42 of 50, there is a test asserting the order, and both places say why.
- **Do not simplify the seven-day list out of the prompt**, and **do not reword rule 11 or rule 3.**
  All carried, all still measured.
- **Do not quote a probe ratio as a rate.** T2b. It is the mistake that let #13 be closed while its
  symptom was still reachable, one level up.
- **Do not chase the live/probe gap again.** §3.3. Prompt, model, tools and messages were all
  verified identical; there is nothing there.
- **Do not put "That's fine" back into the booking fixture**, or drop the `local_start` assertion.
- **Do not un-tag the probe classes.** They assert nothing and one holds fifty live conversations.
- **Do not re-derive the confirmation card from `reply`, add a tool-activity indicator, pass
  `email={null}`, or put `Button`'s size back to `sm`.** All carried, no frontend file touched.
- **Do not assume the `OPENAI_API_KEY` was rotated.** It was not, deliberately.

---

## 7. Next steps, in order

### P0

1. **Push, and open the pull request.** `dev` is ten ahead and `main` is a phase behind. The hold
   is the principal's and has not been lifted. Merging closes #13 and #14 by their trailers.

### P1

2. **#15** — the first-row residual. `ready-for-agent`, and the instruments to work on it are now in
   the repository rather than in a handoff. Budget: a probe batch to screen candidates, then one
   fifty-conversation run to confirm. Under ten minutes of model time.
3. **Decide G3** — tick it, or state the bar. It is the last thing standing between phase 09 and a
   fully ticked Definition of Done, and it is now a judgment rather than a task.
4. **A live cancel and reschedule test** (G2). Unchanged, and the only remaining phase-09 test gap.

### P2

5. **Phase 10** — calendar, analytics, polish. Nothing blocks it.
6. **Retention.** Unchanged: nothing deletes an `ai_message`, and nothing is meant to yet.

---

## 8. Carried, and still carried

- **The advisory lock's cost under ordinary load is unmeasured.**
- **There is no "find my booking" page.** Phase 10 or 11.
- **The value half of `PublicFieldAllowListTest` still only catches the four planted strings.**
- **The sweep only sees paths the fixture actually produces.**
- **`sessionStorage` holds a transcript containing a customer's name and number.**
- **The prompt is 12 rules and has now been changed by three consecutive sessions**, every time
  forced by a model limitation rather than a product decision. #15 would be the fourth. It is worth
  someone asking out loud whether a stronger model is cheaper than the next four sessions of this.

---

## 9. Files, and what changed

Modified — **backend and repository root, six files**:

```
ai/application/SystemPromptBuilder.java   the list line reversed; the fifty-and-fifty recorded
build.gradle.kts                          the `probe` tag excluded alongside `llm`
.gitignore                                the probe fixtures, which are outputs
```

```
ai/application/ConversationLoopTest.java  asserts the order, not two substrings in any arrangement
ai/application/LiveReceptionistTest.java  the accepting turn names 09:00; local_start asserted
```

Added:

```
ai/probe/ProbeFixtureDumpTest.java        writes the prompt and tool JSON the loop would send
ai/probe/WeekdayResolutionRateTest.java   fifty live conversations; asserts nothing, prints a rate
tools/receptionist-probe/probe.py         the replay harness
tools/receptionist-probe/README.md        the three instruments, and which to believe
```

No migration. No frontend file. No documentation file except this one.

---

## 10. Commands, and what they last returned

### 10.1 The gates

```bash
# From backend/. What CI runs. ~5 minutes.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```
Counted from the XML: **801 tests, 0 failures, 0 errors, 0 skipped.**

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for x in glob.glob('build/test-results/test/*.xml'):
    r = ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors')); s+=int(r.get('skipped'))
print(t,f,e,s)"
```

### 10.2 Level 3

```bash
# From backend/. ~50s, ten tests. Preserve the XML if you are measuring - the next --rerun eats it.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test -PincludeTags=llm --rerun
```
Thirteen runs this session. **10 tests, 0 skipped** every time; two runs red, both diagnosed in §3.

### 10.3 The instruments

```bash
# From backend/. Fixtures first - they are gitignored and must exist before the probe runs.
./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'

# From backend/tools/receptionist-probe/. Screens a candidate. Seconds, pennies.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../../../.env | cut -d= -f2-)
./probe.py "What have you got free next Monday?" MONDAY 20
./probe.py "What have you got free next Monday?" MONDAY 20 --prompt candidate.txt

# From backend/. Confirms it. ~4 minutes, a few cents. This is the number for a commit message.
./gradlew test -PincludeTags=probe --tests '*WeekdayResolutionRateTest'
```

### 10.4 The database

```bash
docker exec -e PGPASSWORD=reception reception-postgres-1 psql -U reception -d reception -c '…'
```
There are **three** Postgres containers on this machine; `docker ps | grep postgres | head -1` picks
the wrong one. Not used this session.

---

## 11. Confidence

**High — measured over a hundred live conversations.** §3.2's two rates are fifty trials each
through the real `ConversationService`, and the difference between them carries a p-value rather
than an impression.

**High — watched failing.** The order assertion was seen red against a reversed builder, and the
restore was diffed byte-for-byte.

**High — counted.** 801/0/0/0, parsed from XML, after the probe classes were added.

**High — the instruments work.** Both were run from a clean checkout of their own committed form,
and the probe was smoke-tested against fixtures the committed dump test produced.

**Moderate.** That 96% is the right number for utterances beyond "next Monday". The probe says
"tomorrow" and "Wednesday" are clean, and the probe is a screen.

**Low.** Any explanation of *why* the field order matters. The effect is measured and the mechanism
is a guess, and both the code comment and this document say so rather than dressing the guess up.

**None.** The frontend, untouched. `pnpm build`, unrun.

---

## 12. The verification tenant

**`Phase 06 Scratch` was not touched.** Nothing was driven in a browser. Every live model call ran
against Testcontainers or against the API directly from the probe, and no application server was
started. The counts the earlier handoffs recorded still stand.
