# Pre-registration — [#17], fifth candidate: a move searches from the requested date

**Written 2026-09-17, before any model call and before the baseline arm runs.** Four candidates have
been spent on this issue. Three were rejected, **two made things measurably worse**, and the fourth —
`resolve_date` — was ruled not accepted and kept. Nothing below is written after seeing a number.

[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#40]: https://github.com/sanama-stack/reception-booking-system/issues/40

---

## 1. The mechanism being attacked

**On a reschedule, `date_from` is set to the day after the appointment's *current* date, ignoring the
date the Customer named.** That is §14 of the 2026-09-11 experiment record, established by moving one
variable: an arm that booked on 2026-09-22 and asked for 2026-09-25 put **25 of 25** wrong landings on
`2026-09-23` — `booked + 1` — and **none** on `target + 1`.

Two fifty-trial arms on 2026-09-16 then showed the same wrong `date_from` produces **two** visible
failures, which is why [#40] was closed into this issue:

| Outcome, arm 2 (n = 48, 2 errored) | n | Rate |
|---|---|---|
| Wrong date written | 29/48 | 60.4% |
| Refused outright — the Customer is told the time is unavailable | 9/48 | 18.8% |
| Correct | 10/48 | 20.8% |
| **Wrong by either route** | **38/48** | **79.2%** |

On the wrong day the model either finds the requested time and books it there, or `MAX_SLOTS` cuts
the list before it and the model has nothing to offer, so it declines. **All nine refusals searched
`booked + 1` and nothing else; zero searched the day the Customer named.**

**The model is not bad at dates.** `probe.py` copies an explicit ISO date into `date_from` **70 times
out of 70** on a plain availability question — inside the seven-day list, outside it, and with a time.
The capability is there; the anchor is wrong. This candidate moves the anchor.

## 2. The change — exactly one, and which one

A **new prompt rule**, in `SystemPromptBuilder`:

```
13. When moving an appointment, search from the date the customer asked for — never from the date
    the appointment is on now. lookup_appointment tells you where it is; that is not where they
    want it.
```

**Nothing else changes.** Not `find_available_slots`'s `date_from` description, not
`reschedule_appointment`'s schema, not rule 11.

**Why the prompt and not the schema description.** Two reasons, and the second is the binding one:

1. Rule 11 already makes the prompt the home for date-selection policy, and the model demonstrably
   follows rule 11 — it called `resolve_date` in 50 of 50 trials of the weekday arm.
2. **Pointing `date_from`'s own description at the requested date is [#15]'s untried candidate**, word
   for word. Running it here would spend it on this issue and leave neither issue able to read the
   result. The resolver arm already paid for this lesson from the other side: it was *two* changes,
   the bean and two prompt passages, and the counterfactual had to reverse-apply both to mean
   anything.

If this candidate fails, the schema-description route is the sixth candidate and gets its own
pre-registration.

## 3. Endpoints, fixed now

**Primary — the correct landing rate.** The appointment ends at the date *and* time the Customer
asked for, read from the database. Denominator is trials minus errored, never minus refusals: a
refusal is an outcome, not an absence of one.

The upstream metric — whether any search covered the requested day — is **reported and does not
decide anything**, because no baseline for it exists. That is the reason §5 re-measures the baseline
rather than taking arm 2's numbers.

**Why the landing and not the search.** The resolver arm chose an upstream primary because
`date_from` exactness was a lossy proxy for a window that might still cover the target. Here the
customer-visible outcome is available, is unambiguous, and is the thing the issue is about. A
candidate that fixes the search and not the booking has not fixed this.

## 4. The decision rule

Two arms, **50 conversations each**, same tree, same calendar day, same distance, differing only in
the candidate.

**Accept** only if both hold on the primary:

1. Fisher exact, one-sided, **p < 0.05**; and
2. an absolute improvement of **at least 20 percentage points**.

Against an expected baseline of 10/50 = 20%, both clauses bind at exactly **20/50 = 40%**
(p = 0.0243). 18/50 satisfies neither — p = 0.0591, and 36% is a 16-point move.

| Candidate primary | Verdict |
|---|---|
| ≤ 19/50 | **reject** |
| ≥ 20/50 | accept, if neither veto fires |

**Reject regardless if either veto fires.** Both are stated as a **rate with a threshold**, and both
carry the probability they fire on a candidate that is innocent of the thing they test —
**T198: an absolute veto on a nondeterministic system is a rejection with extra steps.** The veto
that sank `resolve_date` was written as "any landing" and would have fired in **87%** of arms at the
overshoot rate actually observed. These are the arithmetic that was not done then:

| Veto | Fires at | False-rejection probability |
|---|---|---|
| **Refusals rise.** A candidate that aims the search correctly and then declines more often has traded one harm for another | **≥ 16/50 refused** (32%, against a baseline of 18.8%) | **1.79%** |
| **Wrong dates rise.** A candidate that converts refusals into wrong bookings has moved a rate and fixed nothing | **≥ 38/50 wrong-date** (76%, against a baseline of 60.4%) | **1.53%** |

Combined, the vetoes reject an innocent candidate about **3.3%** of the time, which is the price of
having them and is stated here rather than discovered afterwards.

**The second veto is the one that matters most**, and it exists because of how [#40] closed: the two
failure modes are branches of one defect, so a change that shifts weight between them looks like
progress on whichever branch you were watching. `RescheduleRefusalRateTest` counts all three outcomes
apart precisely so that cannot pass.

## 5. The baseline is re-measured, not taken from 2026-09-16

Arm 2's numbers are the best evidence this project has, and they are still **not** the control:

- They do not carry the searched-the-requested-day rate over all trials — the harness counted it for
  refusals only. §3 reports that metric, so the control must contain it.
- Arm 2 ran on 2026-09-16. **T194: distance to the horizon is uncontrolled in every reschedule rate
  this project has recorded**, and `BookingScenario.monday` drifts between 7 and 14 days out
  depending on the weekday. Two arms compared across days are comparing the calendar as much as the
  candidate.

So both arms run **on one day, at one distance**, pinned with `PROBE_TARGET_DAYS`. If the re-measured
baseline differs materially from 20.8%, **that is itself a finding** and the comparison still stands,
because both arms ran on the same tree.

## 6. Cost, stated before it is spent

100 live conversations, three turns each plus tool loops. Roughly half an hour of wall clock and a
few dollars. The harness archives its own result XML; `--rerun` overwrites it otherwise.

## 7. What this experiment cannot answer

- **It does not address [#15].** That is the seven-day list's wrong row, a different mechanism, and
  this candidate does not touch rule 11.
- **It does not address `MAX_SLOTS`.** The cap silently truncating a slot list is real and unfiled.
  It decides *which* failure the Customer sees and causes neither; a fix here should make it
  irrelevant to this flow without fixing it.
- **One phrasing and one distance.** ISO dates at `today + 12`. It says nothing about a spoken
  weekday, and T194 forbids comparing its rate with an arm at another distance.
- **It cannot show the defect is gone.** At 50 trials a residual of a few percent is invisible, which
  is the wall [#15] hit. A clean arm means "not detectable at this sample", never "absent".


---

## 8. Result — 2026-09-17. **Accepted.**

Both arms on one day, one tree apart from the rule, `PROBE_TARGET_DAYS=12`, target **2026-09-29**
(Tuesday), fifty trials each, **0 errored in both**.

| | Baseline | Candidate | |
|---|---|---|---|
| **Primary — correct landing** | 11/50 = **22.0%** | **49/50 = 98.0%** | Fisher one-sided **p = 1.4 × 10⁻¹⁶** |
| Refused | 7/50 | **0/50** | Veto 1 threshold was 16/50 — does not fire |
| Wrong date written | 32/50 | **1/50** | Veto 2 threshold was 38/50 — does not fire |
| **Searched the requested day** | 9/50 = 18.0% | **50/50 = 100%** | **p = 1.2 × 10⁻¹⁹** |

§4 required ≥ 20/50 on the primary with neither veto firing. The candidate returns **49/50**.
**Accepted.**

### 8.1 The mechanism moved, and the outcome followed it

The reported metric is what makes this more than a good number. §1 said the defect is `date_from`
anchored to the appointment's current date; the requested day was searched in **9 of 50** trials
without the rule and **50 of 50** with it. The candidate did not improve an outcome by an unclear
route — it moved the exact variable the diagnosis named, and the landing rate followed.

**One trial still failed**, landing on `2026-09-29 12:00` — the appointment never moved. 98% is not
100%, and the CI is [89.4%, 99.9%].

### 8.2 The vetoes could have fired, and that is the point

Both were written as a rate with a threshold and a false-rejection probability computed before the
arm — 1.79% and 1.53%. Neither fires: refusals 0 against 16, wrong dates 1 against 38. **T198's first
application works.** The veto that sank `resolve_date` was written as "any landing" and would have
fired in 87% of arms at the overshoot rate actually observed; these are vetoes a good candidate can
pass and a bad one cannot.

### 8.3 The first candidate arm was thrown away, and why

An earlier attempt returned **22 of 22 correct with 28 of 50 errored** — trials 23 to 50 failed
contiguously on `UnknownHost` and read timeouts, a local network outage rather than anything the
provider or the candidate did. It was **not reported as a result**: the pre-registration specifies
fifty trials, and 22/22 is a different denominator produced by an outage, not the number §4 names.
Archived, and re-run in full once the network returned. **T36 — read the errored count first.**

### 8.4 Rule 13 was verified in the artifact, not inferred

`ProbeFixtureDumpTest` wrote the prompt the model actually receives, and rule 13 is line 57 of it.
Checked because a 22/22 against a 22% baseline is exactly the result that should prompt someone to
confirm the change shipped at all — a class-file grep had returned nothing, and it turned out the
grep was broken rather than the rule missing. The dumped artifact settled it.

### 8.5 What this does not settle

- **[#15] is untouched.** Different mechanism, and rule 11 was not changed.
- **[#40]'s second mechanism survives.** Two of twenty-three refusals across three arms had the
  requested slot offered on the requested day and refused anyway; that is not a date problem and
  this rule does not address it. The candidate arm produced **zero** refusals, which is consistent
  with the sub-mode being rare rather than gone.
- **One distance, one phrasing.** ISO at `today + 12`. T194 forbids comparing this rate with an arm
  at another distance, and a spoken weekday is unmeasured.
- **98% is not proof of 98%.** Fifty trials bound the residual at [0.1%, 10.6%] failing.
