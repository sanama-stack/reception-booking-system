# Pre-registration — [#40]: counting mechanism 2 on its own

> **Written 2026-09-22, before any conversation is bought.** Endpoints, decision rules and vetoes are
> fixed below. Nothing in §3–§6 may be edited after the first trial runs; §9 is where the result goes.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#40]: https://github.com/sanama-stack/reception-booking-system/issues/40

---

## 1. The question

**Given that the Receptionist has been handed the requested slot, on the requested day, does it ever
decline to write it?** That is mechanism 2 — the only part of [#40] that survives rule 13, and the
only thing in this repository still described as *invention*.

It has **two observations and no text**. Both are in the fifth candidate's baseline arm, 2026-09-17,
target `2026-09-29` at `today + 12`:

```
38  REFUSED  15:00 offered=true  truncated=false  offered=39 slots  searched=[2026-09-30, 2026-09-30, 2026-09-29]  target searched=true
40  REFUSED  15:00 offered=true  truncated=true   offered=77 slots  searched=[2026-09-30, 2026-09-29, 2026-09-30]  target searched=true
```

## 2. The issue's own next step would produce a clean arm that means nothing

[#40]'s ruling of 2026-09-22 and its reopening comment before it both say the same thing:

> A scenario in which the wrong-day search *cannot* occur, so mechanism 2 can be counted alone.

**Read the two observations again.** Both searched `booked + 1` **before** they searched the day the
Customer named. Neither refusal happened in a conversation where the wrong-day search was absent — it
is present in 2 of 2. A scenario built to make that search impossible removes the only condition
under which the defect has ever been seen, and is close to guaranteed to return zero.

⚠️ **This is a hypothesis with two data points and no control, and it must not be read as more.** The
harness prints `searched` for **refusals only** — `MOVED` prints nothing and `ELSEWHERE` prints the
landing and the tool names. So whether the 7 non-refusing trials that also searched the requested day
share the same wrong-day-first signature **is not recorded anywhere and cannot be recovered**. T195:
a signature consistent with two explanations is evidence for neither. §7 is the instrument change
that makes it checkable, and it must land before an arm is bought.

What follows from §2 is not "the prior search causes it". It is only: **a design that forbids the
prior search is a design that has never observed the defect, and it needs a companion arm that
permits it.**

## 3. What the evidence already says, once the denominator is right  🔒

**The `≈1.3% of conversations` in `07-mvp-scope.md` is an unconditional rate, and it divides by the
wrong thing.** 2 events in 150 conversations is arithmetic over three arms in which the Receptionist
mostly never reached the decision point at all — it searched the wrong day, so it could not have been
offered the slot, so it could not have refused one. That fraction measures how often [#17] fired, as
much as it measures [#40].

Conditioned on reaching the decision point — **the requested day searched** — the same arms say:

| Arm, 2026-09-17, `PROBE_TARGET_DAYS=12` | searched the requested day | refused with the slot offered | rate | Clopper-Pearson |
|---|---|---|---|---|
| Baseline, no rule 13 | 9/50 | **2** | **≥ 22.2%** | [2.8%, 60.0%] |
| Candidate, rule 13 — the shipped prompt | 50/50 | **0** | 0% | [0.0%, 7.1%] |

Fisher one-sided, alternative *baseline higher*: **p = 0.0210**.

Three things about that table, each of which limits it:

1. **`≥` is deliberate.** The 9 is an upper bound on the denominator — `offered` was printed for
   refusals only, so how many of the 9 were actually handed 15:00 is unknown. A smaller true
   denominator makes the rate **higher**, not lower.
2. **The candidate arm's 50 is assumed, not verified**, for the same reason. 49 of 50 landed
   correctly, so it is very likely every trial was offered the slot; it is not recorded.
3. **p = 0.021 is not a fix.** Rule 13 changed the search path, and §2 says the observed instances sat
   downstream of a wrong-day search. A rule that removes the wrong-day search would remove those
   instances **whether or not it touches the refusal itself**. The two explanations are not separated
   by this table and nothing in hand separates them.

**The consequence for sample size is the point of this section.** At the conditional rate the
baseline suggests, the defect is common, not rare:

| true rate at the decision point | trials for a 95% chance of seeing ≥ 1 |
|---|---|
| 22.2% | **12** |
| 10% | 28 |
| 5% | 58 |
| 3% | 98 |
| 1.3% — the unconditional figure | 229 |

The 229 in the issue's *"fifty trials is underpowered"* is the last row. **The right row is one of the
first three**, and fifty trials per arm is ample for all of them.

## 4. The scenario — two arms  🔒

Both arms are four turns, run on one day at one distance, pinned with `PROBE_TARGET_DAYS`. `D` is the
day the appointment is on; it must fall Monday–Thursday so that `D + 1` is also a working day.

### Arm A — *offered and asked*. The control, and the design [#40] asks for

The Customer **never names a date**. The wrong-day search cannot occur because no other day is ever
mentioned, and the requested slot is in the tool's own answer by construction.

| turn | Customer |
|---|---|
| 1 | "I would like to move my appointment. Confirmation code `…`, booked with `…`." |
| 2 | "What else have you got later that same day?" |
| 3 | "Please move it to **`HH:MM`**." — a time read out of `OFFERED_SLOT_STARTS` |
| 4 | "Yes — **`HH:MM`** please. Go ahead and move it." |

The time in turn 3 is chosen by the harness, **from the database and not from the model's prose**:
the first slot in `OFFERED_SLOT_STARTS` that falls on `D` at or after 14:00 — two hours clear of the
12:00 appointment, so "later that day" is unambiguous. If the search offered no such slot, the trial
is **UNREACHED**: printed, counted, and outside the denominator (§5).

Turn 4 re-names the time rather than saying "yes", which is T16 — a bare acceptance refers to nothing
and ends the conversation a turn short of the write, and would be scored here as a refusal it is not.

### Arm B — *denied, then free*. The treatment, and the condition §2 found

Reproduces the one signature both observations share: a denial the Receptionist makes **truthfully**,
followed by the same clock time being genuinely available.

The fixture books **two** appointments: the Customer's at 12:00 on `D`, and a **blocking appointment
at 15:00 on `D`** — one employee, one service, so 15:00 on `D` is really gone.

| turn | Customer |
|---|---|
| 1 | "I would like to move my appointment. Confirmation code `…`, booked with `…`." |
| 2 | "Please move it to 15:00 on `D`." → the Receptionist searches `D`, 15:00 is **not** there, and declining is **correct** |
| 3 | "Then 15:00 on `D + 1`, please." → free by construction |
| 4 | "Yes — 15:00 on `D + 1`. Go ahead and move it." |

To write turn 3 the Receptionist must state a time it has just told the Customer it cannot have. The
turn-2 decline is not scored and is not a defect; it exists only to put the model in the state the
two observations were in.

**Arm B does not satisfy [#40]'s "the wrong-day search cannot occur", and that is deliberate.** A
turn-3 search of `D` instead of `D + 1` is [#17] and is counted apart, not pooled.

## 5. Endpoints  🔒

**Primary, per arm — the mechanism-2 rate, with the denominator this project has never printed:**

> **refusals ÷ trials that reached the decision point**, where a trial reached the decision point if
> `SEARCHED_DATES` contains the requested day **and** `OFFERED_SLOT_STARTS` contains the requested
> start, and a refusal is a trial in which `reschedule_appointment` was never called.

Reported with Clopper-Pearson. Trials inside that denominator split three ways and all three are
printed: **wrote the requested slot**, **wrote elsewhere** ([#17]), **refused** (the numerator).

**Secondary — does the prior denial matter?** Arm B against arm A, Fisher one-sided, alternative
*B higher*. Pre-registered readings, so the number cannot be interpreted after it is seen:

| outcome | reading |
|---|---|
| p ≤ 0.05 | The induced denial is implicated. [#40] gets a reproducible condition and an entry saying so |
| p > 0.05, both arms non-zero | One finding. Pool for a tighter interval; mechanism 2 does not need the prior denial |
| both arms zero | **0/100 at the decision point, upper bound 3.62%.** Not detectable on the shipped prompt at a hundred trials. The `≈1.3%` entry stands as a property of a prompt no longer shipped, and so does the 2026-09-22 ruling |

With arm A at 0/50, arm B needs **5/50** to reach p ≤ 0.05; at 1/50 it needs 7/50, at 2/50 it needs
8/50. Stated now, as a number, rather than discovered against whatever comes back.

**Also recorded, every trial:** the refusal's own words (§7), the searched days, the offered count,
`truncated`, the landing, and `ERRORED` on its own line and never folded into a denominator — T36.

**This asserts nothing**, like its three siblings. It prints, and a person reads the number.
`docs/08-testing-strategy.md` §7 keeps thresholds on a nondeterministic system out of the pipeline,
and T198 says an absolute veto on one is rejection with extra steps.

## 6. What voids an arm, and the cost  🔒

| veto | fires when | why |
|---|---|---|
| **Instrument** | `ERRORED > 5` in either arm | T36. A provider outage reads as the model refusing every write |
| **Control unreached** | arm A reaches the decision point in **< 45/50** trials | Arm A's whole claim is that it reaches it by construction. If it does not, the comparison is unreadable and the arm is a measurement of the harness |
| **Arm B off-target** | arm B's turn-3 search is `D` rather than `D + 1` in **> 25/50** trials | Then arm B measured [#17] under rule 13, which is a different and already-answered question |

**Cost.** 100 conversations, four turns each plus tool loops. Roughly forty minutes of wall clock and
a few dollars — the same order as the fifth-candidate experiment, which was 100 conversations at
three turns. **Both arms run on one day, at one distance, one tree apart from nothing**: there is no
prompt change here, only a scenario, so the two arms differ in the fixture and the script alone.

## 7. The instrument must change first, and none of it costs a credit — **done 2026-09-22**

Three changes to `RescheduleRefusalRateTest` / `ProbeQueries`, all CI-provable through
`ProbeInstrumentationTest`, all of which must land **before** the arm is bought. G17: an instrument
that has not been proven is not an instrument, and this repository has paid for that three times on
[#17] and once inside the harness built to close [#40].

1. **Print `searched`, `offered`, `truncated` and the landing for every trial**, not only for
   refusals. This is what §2 needed and could not get: with it, the wrong-day-first signature has a
   control; without it, every future arm reproduces the same hole.
2. **A new projection for the assistant's prose** — `role = 'ASSISTANT'` and `content is not null`,
   for the trial's last two turns. **Mechanism 2 currently has two observations and not one word of
   what the Receptionist actually said.** "That time is already booked" and "I am not able to move it
   myself" are different defects with different fixes, and nothing in hand distinguishes them.
3. **Print the decision-point denominator** defined in §5. No arm has ever printed it, which is why
   the rate in `07-mvp-scope.md` is the unconditional one.

### All three landed before anything was bought

| change | where | proven by |
|---|---|---|
| Per-trial `searched` / `offered` / `truncated` / landing on **every** branch | `RescheduleRefusalRateTest` | Not provable in CI — the harness is `@Tag("probe")` and needs a funded key. **Stated rather than claimed**: the format strings and counters are read by eye, and the SQL underneath them is what CI proves |
| `ProbeQueries.ASSISTANT_PROSE` — the Receptionist's own words, whitespace flattened, cut at 240 with the marker | `ProbeQueries` | `ProbeInstrumentationTest`, two cases, **run on every push** |
| The decision-point denominator and its three-way split | `RescheduleRefusalRateTest` | As the first row |

**The two new CI cases were counterfactually checked, not merely run.** Replacing `ASSISTANT_PROSE`
with a bare `select content …` — which drops the flattening, the cap and the `content is not null`
guard at once — turns **both** of them red and leaves the other nine green. A projection test that
passes against a broken projection is the shape this repository has already been caught by; a green
run on its own is not evidence that it was looked at.

**What is still unproven, and it is the first row.** The harness's printing and counting cannot run
in CI, so a format-string or counter error would surface at spend time. That is the standing
condition of every `probe`-tagged harness here and it is not fixed by this change — it is the reason
`ProbeQueries` exists, and the reason the new SQL went into it rather than into the harness.

## 8. What this cannot answer

- **It is not a rate for production traffic.** It is a rate at the decision point, under a fixture
  that reaches the decision point on purpose. Those are different numbers and the entry must say
  which one it carries.
- **One distance, one phrasing, ISO dates.** T194 forbids comparing either arm with an arm at another
  distance.
- **It cannot show the defect is gone.** 0/100 bounds it at 3.62% and means "not detectable at a
  hundred trials", never "absent". That is the wall [#15] hit and it is not a flaw in the instrument.
- **It does not touch `MAX_SLOTS = 30`**, still real, still unfiled, and irrelevant here: in both
  observations the slot was offered, so no cap hid it.
- **Arm B's turn-2 decline is not evidence of anything.** It is correct behaviour, staged.

## 9. Result — 2026-09-22. **Both arms zero.**

Both arms at `PROBE_TARGET_DAYS=13`, target **2026-10-05** (Monday), on one tree, `RefusalAtTheDecisionPointTest`.

| | Arm A — *offered and asked* | Arm B — *denied, then free* |
|---|---|---|
| Configured | 50 | 50 |
| ERRORED / UNREACHED | **5** / 0 | **0** / 0 |
| Scored | 45 | 50 |
| **Reached the decision point** | **45 of 45** | **50 of 50** |
| Wrote the requested slot | 45 | 48 |
| Wrote elsewhere | 0 | 2 |
| **REFUSED — mechanism 2** | **0** | **0** |
| | CI [0%, 7.87%] | CI [0%, 7.11%] |

**Pooled: 0/95, CI [0%, 3.81%].** Fisher one-sided between the arms, p = 1.000.

**§5's third pre-registered reading applies**, and it was written before any of this was known:

> both arms zero → **0/100 at the decision point, upper bound 3.62%.** Not detectable on the shipped
> prompt at a hundred trials. The `≈1.3%` entry stands as a property of a prompt no longer shipped,
> and so does the 2026-09-22 ruling.

The denominator is 95 rather than 100 and the bound is 3.81% rather than 3.62%, for the reason in
§9.3. Nothing else about the reading changes.

### 9.1 Arm B induced the condition in **every** trial, and the defect still did not fire

This is the part worth more than the rate. §2's hypothesis was that mechanism 2 needs a prior
wrong-day search, because both observations had one. Arm B stages that on purpose, and the searched
days say it worked in all fifty trials:

| what the model searched | n |
|---|---|
| `[2026-10-05, 2026-10-06]` | 35 |
| `[2026-10-05, 2026-10-06, 2026-10-06]` | 11 |
| `[2026-10-05, 2026-10-06, 2026-10-05]` | 3 |
| `[2026-10-05, 2026-10-05, 2026-10-06]` | 1 |

**Every trial searched the appointment's own day before the requested one** — the exact shape of
trials 38 and 40, reproduced fifty times out of fifty rather than twice in a hundred and fifty. The
Receptionist truthfully declined 15:00 on the 5th, was then asked for 15:00 on the 6th, searched it,
was handed it, and **wrote it in 48 of 50**. It refused **none**.

So the condition is not sufficient. That does not make it unnecessary — the two observations sat on a
prompt without rule 13, and this arm cannot separate "the condition does not cause it" from "rule 13
removed it". **What it does close is the design question**: the scenario now exists, it reaches the
decision point in 95 of 95 scored trials, and it is repeatable for a few dollars.

### 9.2 Arm A is the control and it behaved exactly as designed

45 of 45 searched **only** the requested day, every one picked 14:00 out of the tool's own offered
set, every one wrote it. No wrong-day search occurred anywhere in the arm, which is what makes arm A
the thing §2 said it was: a scenario that cannot produce the defect's only observed precondition —
and, accordingly, produced nothing.

### 9.3 What the numbers exclude, and what they do not

| hypothesis | P(zero in 95) | |
|---|---|---|
| 22.2% — the conditional estimate in §3 that motivated this whole design | 0.0000 | **excluded** |
| 10% | 0.0000 | excluded |
| 5% | 0.0077 | excluded at the usual bar |
| 3% | 0.0554 | borderline |
| **1.3% — the unconditional figure the entry carries** | **0.2885** | **not excluded** |

⚠️ **A clean arm is a bound, not an absence.** At the rate `07-mvp-scope.md` actually records, a
ninety-five-trial clean run happens about **twice in seven**. This is the wall [#15] hit and the
arithmetic is the same.

⚠️ **The comparison against §3's 2/9 is not clean and must not be quoted as a fix.** Fisher gives
p = 0.0067 against the pooled zero, but the two measurements are from **different scenarios, different
scripts and different harnesses**, and T194's logic applies one level up: the 2/9 came from
`RescheduleRefusalRateTest` asking for a named ISO date on a prompt without rule 13. Only the
direction is shared. **No causal claim is made here and none is available from this arm.**

### 9.4 Two instrument failures, both recorded rather than smoothed

**Arm A lost trials 46–50 to a network outage** — five contiguous `ResourceAccessException`s at the
tail, the same signature §8.3 of the fifth candidate's record already paid for. The pre-registered
veto is `> 5`; five is not six, so it does not fire, and the arm is reported over **45** rather than
over 50. The trials that ran completed before the outage and are unaffected by it.

**Arm B's first attempt died at the fixture, and the bug was mine.** The blocker booking carried a
copy of `BookingScenario.bookedAt`'s refresh-and-retry that **discarded the refresh's own response**,
so when the refresh failed the retry re-sent the same expired token and the arm threw at trial 2 with
a 401 whose cause was not in the message. It is fixed — the refresh is checked, a failed refresh
falls back to logging in again, and both bodies go into the failure message — and the arm was
re-run in full. **This would have happened in a healthy run too**, around trial 35 when the
fifteen-minute access token expires; the outage merely found it at trial 2. The first attempt is not
reported as a result: one trial is not a denominator §4 names.

### 9.5 Two incidental observations in arm B, neither of them mechanism 2

Both are in the `ELSEWHERE` column — the model wrote, but not what was asked for.

**Trial 49 said one time and wrote another.** It reported *"successfully rescheduled to October 6,
2026, at 3:00 PM"* and the appointment landed at **14:00** on the 6th. If the tool was called with
14:00, then the sentence states a time no tool returned — the phase 09 Definition-of-Done box *"It
never states a slot, price or policy that did not come from a tool"*.

**Trial 33 claimed to be moving it and it never moved**, landing on the original `2026-10-05 12:00`
after `reschedule_appointment` was called.

⚠️ **Neither is established, and the reason was an instrument gap this arm found.** The harness
printed tool **names** for an `ELSEWHERE` trial, not their arguments, so whether trial 49's write was
sent as 14:00 or sent as 15:00 and applied wrongly **cannot be recovered** — the database went with
the container. `ProbeQueries.ALL_TOOL_CALLS` had carried the arguments since 2026-09-15 and was not
read here, which is **G17 again**, in a harness written the same afternoon to close a G17. Two
observations, no mechanism, and no issue filed on two trials: that is this repository's own rule
about what a filing needs.

**Closed for the next arm, not for these two.** `ProbeQueries.TOOL_CALLS_IN_CONVERSATION` and
`ProbeQueries.WRITE_CALLS` were added on 2026-09-22 and are read by **both** rate harnesses: every
trial now prints the write's own arguments, and a trial that refused or wrote elsewhere prints its
whole call sequence with what was sent. `ALL_TOOL_CALLS` could not be used — it is deliberately
unfiltered, correct only where the database holds one test's rows, and a rate harness pours fifty
conversations into one. Both new projections are proven by `ProbeInstrumentationTest` on every push
and were counterfactually checked: disabling the conversation filter and the write filter turns those
two cases red and leaves the other eleven green. Verified live at two trials, where the line now
reads `new_starts_at: 2026-10-06T15:00:00+04:00` beside the landing.

**Trials 49 and 33 stay unexplained, and no claim is made about them.** An instrument added after
the run does not reach back into it.

### 9.6 What this does to [#40]

Its entry in `07-mvp-scope.md` keeps its ruling. What changes is that the sub-mode now has
**targeted trials against the shipped prompt** where it previously had none, in a scenario built to
produce it — and that the **≥22% conditional reading in §3 is not reproduced**. The order of
magnitude the entry carries survives this arm; so does the reason the entry exists.
