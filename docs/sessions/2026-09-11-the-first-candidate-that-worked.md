# Session handoff — 2026-09-11 — the first candidate that worked, and could not be accepted

> **Purpose.** Enough context to finish the [#17] experiment, or to start phase 11, without
> re-reading anything. §1 is where things stand; §2 is the eight decisions the principal made and
> where each one landed; §3 is [#26], done; §4 is the [#17] experiment, which is **unfinished**; §5
> is what this session got wrong.
>
> **Read §4.3 first if you read nothing else.** The best-performing candidate this issue has ever
> had is sitting on `dev` unaccepted, and the single measurement that would settle it needs credits
> on the OpenAI account and ten minutes.
>
> **Nothing is pushed.** `dev` is **three commits ahead of `origin/dev`** and CI has seen none of
> them. The build is **844 tests, 0 failures**, `./gradlew build` green locally.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#26]: https://github.com/sanama-stack/reception-booking-system/issues/26
[previous]: ./2026-09-11-the-ceiling-that-was-two-ceilings.md
[experiment]: ../experiments/2026-09-11-17-deterministic-date-resolution.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`7df76d3`** — unchanged this session |
| `origin/dev` | **`e33af63`** — unchanged this session |
| `dev` | **`c81d312`**, **three ahead and unpushed**. CI has seen none of it |
| Backend | **844 tests, 0 failures**, counted from the XML. Was 837; the seven are §3 and §4 |
| `./gradlew build` | green locally — the whole of what CI's Backend job runs |
| Frontend | **untouched.** No gate run, because nothing changed |
| Migrations | none |
| New ADR | **ADR-0010**, the revenue currency — §2 |
| New docs | **`docs/experiments/`**, a directory that did not exist |
| Issues | **[#26] filed and fixed** (closes on merge). [#17] and [#15] open; [#17] is **no longer parked** |
| Phase 11 | not started. Its scope grew by four decisions — §2 |
| **The OpenAI account** | **out of credits.** `HTTP 429`, `insufficient_quota`, confirmed against the API |

### 1.1 The commits

| | |
|---|---|
| `1c1dd73` | the eight decisions, ADR-0010, and the phase-doc edits |
| `eb610f4` | [#26] — the ids from `lookup_appointment` |
| `c81d312` | the [#17] candidate, the experiment record, and the harness instrumentation |

---

## 2. Eight decisions, made and recorded

Every one had been carried by a previous session as a question rather than a task. The revenue
currency was **six handoffs old**, and three separate sessions had each had to re-explain it.

| Decision | Chosen | Where it lives now |
|---|---|---|
| Revenue currency | current currency **+ an `excluded` remainder list** | **ADR-0010**, and phase 11's checklist |
| The phase-09 hallucination box | **not ticked** at today's rates; a fourth candidate authorised | phase 09 §DoD, rewritten |
| [#17] approach | **deterministic date resolution** | phase 09, *Deterministic date resolution* |
| The stale boxes | strike the tool-activity indicator; **verify** the phase-08 grid | phases 08 and 09 |
| Frontend tests | a thin Vitest + RTL runner, **inside phase 11** | phase 11, and `08-testing-strategy.md` §11 |
| Phase 11 entry point | **the two-tenant seed first** | §9 below |
| `ai_message` retention | a purge job, under phase 11's security completion | phase 11 |
| The small items | all four | phase 11, and [#26] |

### 2.1 Two of them are corrections, not additions

**Phase 11's "any miss is fixed with an index" is struck.** Phase 10 hit two slow queries and fixed
neither with an index; in the calendar's case an index *could not* have helped, because the query had
no lower bound on `starts_at`. The sentence is left in place struck through, with the evidence.

**`08-testing-strategy.md` §11's Frontend row was widened, not ignored.** It said type-checked and
linted with E2E on the critical path, and phase 02 recorded the absent runner as **by design**.
Adding Vitest contradicts a real decision, so the row is dated and the reasoning written beside it —
the repo's own rule for an output that contradicts a recorded one.

### 2.2 The `reception_perf` conflict, and how it resolved

The principal chose both *drop `reception_perf`* and *take a cold-cache reading*, and the scratch
database is the fixture the cold reading needs. Resolved as: **commit its generator as a script, use
it for G15, G16 and the three NFR checks, then drop the ad-hoc database.** The capability becomes
reproducible from a clean clone instead of a local artefact three handoffs have had to explain.

---

## 3. [#26], filed and fixed

`LookupAppointmentTool` read `appointment.serviceId()` in order to fetch the name and then returned
only the name, while `find_available_slots` **requires the id**. So every reschedule ran
`get_services` and had the model match `"Colour"` back to an id — over a catalog a Business may fill
with "Colour", "Colour (long)" and "Root Colour", where a wrong match is a perfectly valid id for the
wrong Service. It books the wrong duration and nothing downstream can tell it from a right one.

Same class as [#17]: an inference the model should never have been asked to make, on a path where
being wrong looks exactly like being right. Two lines.

`employee_id` went too, and `find_available_slots`'s `service_id` description now names the lookup as
a source — the model reads those descriptions, and adding the field without pointing at it would have
left the round trip where it was.

**Both tests were shown to fail without the change**, and only those two: reverting the two
`result.put` lines turned exactly the two new tests red and left the other 45 in those classes green.

### 3.1 The level-2 test the issue specified could not have worked

[#26]'s body asked for a test that *"a reschedule conversation reaches `find_available_slots` without
calling `get_services`"*. Level 2 runs against `ScriptedChatModel`, and **the script chooses the tool
calls** — the test would assert its own arrangement and could never fail while the source was broken.
The correction is posted to the issue. What landed instead asserts the thing that can fail: the id
survives the loop into the TOOL message the model reads next.

---

## 4. [#17] — the experiment, and it is not finished

The whole of it is in [the experiment record][experiment], written in the order it happened:
pre-registration, then the baseline, then the threshold instantiated against it, then the result.
That ordering is the point.

### 4.1 The candidate

A ninth tool, `resolve_date(weekday, weeks_ahead)`. The model supplies which weekday it heard and
whether the customer meant the coming one or a later one; **the counting happens in code**.

This is the first candidate that gives the model a **capability it did not have**, rather than
another instruction about one it lacks. All three rejections were prompt- or guard-side, and all
three implicated the seven-day clamp — which the fourteen-day candidate proved is load-bearing, not
incidental. The prompt's list is a lookup table seven days long; past that the model had nothing to
look up.

**It still interprets "after next".** The pre-registration says so before any number was collected,
and calls it the floor of the approach.

### 4.2 It worked, on both endpoints

Two arms of fifty live conversations, same fixture, same tree apart from the candidate.

| | Baseline | Candidate | |
|---|---|---|---|
| **Primary — a search window covered the named date** | 9/50 = **18%** | 29/50 = **58%** | **p = 3.5e-05** (threshold was 19/50) |
| Secondary — strict landing rate | 8/20 = **40%** | 40/49 = **81.6%** | p = 0.0011 |
| Other reading of the phrase | 24 | **1** | |
| Never wrote | 6 | **0** | |
| Wrong writes | 12 | 9 | p = 0.31, not significant |

The collapse of *other reading* from 24 to 1 is the clearest single sign the mechanism did what it
was meant to: the English ambiguity did not go away, but the model stopped resolving it by guessing.

### 4.3 Why it is not accepted, and what would settle it

**A pre-registered veto fired.** Seven writes landed on `2026-09-28` — one resolver-step past the
target, and a date absent from the baseline. Read as a total the overshoot is **flat**, 9 against 8:
it concentrated rather than grew. Which reading is right could not be settled from that arm, because
**the harness logged `find_available_slots` and no other tool** — the same blindness as **G11**, one
candidate later. Filed as **G17**.

G17 is closed in the harness: every `resolve_date` call now logs its **arguments and its answer**,
and each wrong write is classified as landing on a date the resolver **GAVE** or one it **NEVER
GAVE**. The instrument was proven on two conversations before the arm was paid for.

**The deciding arm then stopped at fifteen trials: the account ran out of credits.** What those
fifteen say:

| | n |
|---|---|
| Asked `MONDAY + 1` → `2026-09-21`, landed correctly | 9 of 9 |
| Asked `MONDAY + 2` → `2026-09-28`, landed there | 6 of 6 |
| **Wrong writes on a date `resolve_date` GAVE** | **6** |
| **Wrong writes on a date it NEVER GAVE** | **0** |

`resolve_date` was called in 15 of 15. Every wrong landing was a date the model **asked the resolver
for** — the residual named in advance, not the defect the veto was written to catch.

**It does not settle it.** Fifteen trials, the first fifteen of one run rather than a random sample,
and a different run from the one that raised the veto. And §9's **trial 40 is still unexplained**: it
searched `2026-09-21` twice, searched nothing else, and landed on `2026-09-28`.

**To finish: add credits, run one arm.** Nothing else is blocking; the instrument is built, proven,
committed and green.

```bash
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY ./gradlew test -PincludeTags=probe \
  --tests '*RescheduleDateFidelityRateTest' --rerun
```

### 4.4 The candidate is on `dev` and must not be read as a fix

`resolve_date` ships in the registry on this branch, so `ToolSchemaTest` says **nine** and
`05-ai-architecture.md` carries a ninth row. Both say in place that it is a candidate under test, and
the commit subject says `the arm is UNFINISHED`. **If the completed arm rejects it, the row comes out
again.**

---

## 5. What this session got wrong

**Said `e33af63` was unpushed. It was not** — `origin/dev` was already on it. Corrected the moment
`git status -sb` was read rather than remembered.

**Nearly reported a behavioural collapse that never happened.** The instrumented arm's summary read
**"35 of 50 never wrote"**, which looks like the model refusing to act in seven trials out of ten. It
was an outage: the harness counted an errored trial as *never wrote*, and no model had been reached
at all. Caught by noticing the run took three minutes where the previous one took nine.

**Overstated a prerequisite.** Told the principal the [#17] baseline had to be measured after
`eb610f4` reached `main`. It did not — what the comparison requires is that both arms run on the
*same tree*, which they did. Being on `main` was never the point.

---

## 6. What is NOT done

| | |
|---|---|
| **Nothing is pushed** | three commits, no CI, no pull request. [#26] cannot close until they land |
| **The [#17] arm** | fifteen of fifty. Needs credits and ten minutes — §4.3 |
| **Phase 11** | not started |
| **The frontend** | untouched, no gate run |
| **The phase-08 grid** | still unticked, and deliberately waiting for phase 11's seed |

---

## 7. Every open item

### 7.1 Issues

**[#26]** — fixed on `dev`, closes on merge. Carries a posted correction to its own testing section.

**[#17]** — **no longer parked.** A fourth candidate exists, is measured, and is one arm short of a
verdict. This is the furthest this issue has ever got.

**[#15]** — open, untouched, and its **title still names a diagnosis a later session disproved**. If
[#17]'s candidate is accepted, [#15] plausibly closes with it — the two share the seven-day clamp.
That is a prediction and **not a measurement**; do not write it down as one.

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G10**, **G11**, **G13**, **G14**, **G15** (calendar half),
**G16**.

**G17 — an instrument that cannot see its own mechanism.** Filed and **closed in the same session**:
the harness logged one tool and the veto turned on another. Second occurrence of this exact shape
after G11.

### 7.3 Traps

Carried T1–T35. New:

**T36 — an uncounted error is reported as the behaviour you were measuring.** A harness that folds
"the call failed" into "the model declined to act" hands you a dramatic and entirely false finding on
the day your provider has an outage. Every instrument with a failure branch needs a counter on it.
Fixed in `RescheduleDateFidelityRateTest`; worth auditing the other two instruments for the same
shape.

**T37 — a test that chooses the behaviour cannot measure it.** Twice this session a specified level-2
test would have asserted its own arrangement, because `ScriptedChatModel` scripts the tool calls
([#26]'s, and the absent one for `resolve_date`). Against a scripted model, assert what the *loop*
did with the result — not what the model chose to call.

**T38 — a fast run is a symptom.** The outage's first visible sign was wall clock: three and a half
minutes where the previous identical arm took nine and a half. Neither the exit code nor `BUILD
SUCCESSFUL` said anything was wrong.

### 7.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged**, and was used this session: **117 live conversations**
across four runs (50 baseline, 50 candidate, 2 smoke, 15 before the credits ran out). The account is
now at zero credits, which is a billing state and not an incident.

### 7.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; nothing deletes an `ai_message` (**now scheduled** — §2);
rate-limit buckets are in memory; `make seed` is a placeholder; **no frontend test runner** (**now
scheduled** — §2).

---

## 8. Next steps, in order

### P0

1. **Push and open the pull request.** Three commits, no CI. [#26] closes with it, and the [#17]
   candidate's *unfinished* framing is written to survive being on `main` without being mistaken for
   a resolution.

### P1 — the principal's

2. **Add credits to the OpenAI account**, then finish the [#17] arm (§4.3). Ten minutes of machine
   time. It is the difference between a candidate and a verdict, and this issue has already burned
   three candidates that looked fine until a control was run.

### P2

3. **Phase 11**, starting with the two-tenant seed — which also hands phase 08 its missing fixture.
4. **G15's calendar half**, cheap now the recipe has been run twice.
5. [#15] · G16 · the other two instruments' error counters (T36).

---

## 9. Commands

```bash
# The whole backend suite, ~6 minutes, from backend/.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# Count it from the XML rather than the console, which does not distinguish a stale result file.
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(t, f, e)"

# Exactly what CI's Backend job runs.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build --no-daemon

# The resolver's own tests, no model, no money.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests "*ToolExecutionTest" --rerun-tasks

# Prove the stats helper before quoting it. It re-derives every number this repo has published.
python3 backend/tools/receptionist-probe/stats.py

# Is the account actually out of credits? Answers in one call, and costs nothing when it is.
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $(grep '^OPENAI_API_KEY=' .env | cut -d= -f2-)" \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'
```

**Do not run `./gradlew` twice at once against this tree**, and **do not run `pnpm build` while the
dev server is up**.

---

## 10. Confidence

**High — [#26] is correct and its tests earn their place.** Both were shown to fail without the
change, and only those two out of 47 in the same classes.

**High — the [#17] candidate improves the measured outcome.** Two arms, fifty each, thresholds fixed
before the numbers existed, primary at p = 3.5e-05 against a threshold it cleared by ten trials. The
helper that produced those figures re-validates against every number this repo has published.

**Medium — the candidate should be accepted.** The veto fired and the evidence now leans toward it
not applying, on fifteen trials with a 6/0 split. Trial 40 is unexplained. **This is exactly the
state three previous candidates were in before a control killed them**, which is the argument for
finishing the arm rather than merging on a lean.

**High — the decisions in §2 are recorded where they will be found.** Each sits in the document whose
checklist it changes, not only in this handoff.

**None — anything about [#15].** Untouched, and its title is still wrong.
