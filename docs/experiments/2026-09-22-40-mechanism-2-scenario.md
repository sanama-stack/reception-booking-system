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

## 9. Result

*Empty. Nothing is written here until both arms have run.*
