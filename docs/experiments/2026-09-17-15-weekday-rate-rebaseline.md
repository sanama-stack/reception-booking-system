# Pre-registration — [#15], re-baseline of the weekday resolution rate

**Written on 2026-09-15, before the run and before any number exists.** This is not a candidate
arm: nothing is being changed and nothing is being judged. It re-measures the *shipped* prompt in
the cell the existing baseline was taken in, because that baseline no longer describes what this
repository ships.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 1. Why the existing number has to be replaced

[#15] carries **142/150 = 94.7%**, measured on 2026-09-10. `resolve_date` shipped on 2026-09-11 and
the prompt has changed since. `WeekdayResolutionRateTest`'s own javadoc has admitted this in writing
for days: the headline number describes a prompt nobody can run any more.

A stale rate is worse than a missing one. It is quoted, compared against, and used to size the
residual — and every one of those uses silently assumes a prompt that is gone.

## 2. Conditions, fixed now

| | |
|---|---|
| **Asked on** | THURSDAY, 2026-09-17, Asia/Tbilisi |
| **Day spoken** | MONDAY — resolving to 2026-09-21, **row 4** of the seven-day list |
| **Trials** | 150 |
| **Command** | `make rebaseline-weekday` |

**The asking day is half the question and is enforced, not documented.** The row the model must find
is the distance between the day it is asked on and the day it is asked about. 48/50 and 142/150 were
both taken on a Thursday asking about Monday. A run on any other day measures a different cell of a
7×7 grid and prints it in the same format, in the same range, with the same summary line — so
`PROBE_ASKED_ON` refuses the run rather than producing a number that looks comparable and is not.
This was not hypothetical: the re-baseline was first attempted on a Tuesday, which is row 6.

## 3. What this run can and cannot detect

Fisher's exact test, one-sided, against 142/150, at α = 0.05. The power here is poor and saying so
in advance is the point. Computed before the run, with
`backend/tools/receptionist-probe/stats.py` — the committed helper, whose `python3 stats.py`
self-validation reproduces all eight values this project has published, including the pinned
42/50-against-48/50 pair at 0.0458. Do not re-derive it: the docstring records that it was rewritten
from scratch in three consecutive sessions before it was committed, and drafting this section made
that four.

| Outcome | p vs 142/150 | |
|---|---|---|
| **150/150** | 0.0035 | improvement, resolved |
| **149/150** | 0.0181 | improvement, resolved |
| 148/150 | 0.0517 | **no result** — and note how close this sits to the line |
| 147/150 | 0.1089 | no result |
| 144/150 | 0.3927 | no result |
| 140/150 | 0.4044 | no result |
| 136/150 | 0.1339 | no result |
| 134/150 | 0.0675 | no result |
| **132/150** | 0.0315 | regression, resolved |
| **130/150** | 0.0138 | regression, resolved |

**The dead band is 133 to 148 inclusive.** Any result in it is consistent with no change and must be
reported as that rather than as a movement — including 148/150, which is a six-trial swing that
still does not reach the line. Only **≥ 149/150** or **≤ 132/150** resolves anything.

⚠️ **These are not the p-values [#15] carries.** That issue's table — 0.015 for a perfect run,
0.14 for a partial improvement — is computed against **144/150**, a different baseline from the
**142/150** it reports as the pooled headline. The numbers above are against 142/150 and are the
ones that apply to this run. Reusing a p-value computed under other conditions is how the first
draft of this section got it wrong.

## 4. The interpretation rule that must be fixed before the number

The harness reports `resolve_date` calls per trial. **That count changes what the rate means**, and
the two readings point in opposite directions:

- **Resolver rarely or never called.** The rate is a measurement of list-scanning, directly
  comparable with 142/150, and it replaces the headline.
- **Resolver called in a substantial share of trials.** The arithmetic has partly left the model,
  and the number is no longer measuring the same mechanism 142/150 measured — even though it is the
  same command, the same cell and the same format. It replaces the headline **only with that stated
  beside it**.

The prompt tells the model to look a date up inside the seven-day list rather than resolve it, so
every resolver call here is a deviation from the rule — possibly a beneficial one, since this
issue's defect is mis-scanning that list. Either way it is reportable and must not be discovered
after the rate has been quoted.

**Prior, recorded now:** a 2-trial smoke run on 2026-09-15 (row 6, not this cell) called the
resolver **0 of 2**. Two trials establish nothing; it is written down so it cannot later be
remembered as more than it was.

## 5. What the result does to [#15]

- It replaces the headline number, with the conditions and the resolver count beside it.
- It does **not** decide what "fixed" means for [#15]. The issue says someone has to, and this run
  does not do it.
- It does **not** evaluate any candidate. The schema-description candidate in [#15] still has zero
  trials and still must not be committed unmeasured.
- It says nothing about any other cell of the 7×7 grid. Six of the seven asking-days remain
  unmeasured, as they were before.

## 6. What could make the run worthless

**A non-zero ERRORED count.** The summary prints it on the same line as the sample size and shouts
at the end, because a partial run reads as a behavioural collapse — T36. Read that number first; if
it is not zero, there is no measurement, only a partial one.


---

## 7. Result — 2026-09-17

Run at 00:04 Tbilisi on the Thursday, `make rebaseline-weekday`, the arm refusing any other day.

```
150 of 150 conversations resolved MONDAY correctly (150 searched at all, 0 ERRORED)
resolve_date was called in 0 of 150 trials; asked: {}
asked on a THURSDAY, where MONDAY was 2026-09-21 — row 4 of seven.
```

| | |
|---|---|
| **Primary** | **150/150 = 100%**, CI [97.6%, 100%] |
| Against 142/150 | **p = 0.0035**, one-sided |
| Pre-registered threshold | ≥ 149/150 |
| Errored | **0** — it is a measurement, not a partial run |
| `resolve_date` | **0 of 150** |

**Both §4 conditions are met and the dead band is cleared, not grazed.** §3's rule resolves only at
≥ 149/150 or ≤ 132/150; this is 150.

**§4's interpretation rule lands on the clean branch.** The resolver was called **zero** times, so the
arithmetic did not leave the model: this measures list-scanning, exactly what 142/150 measured, and
the comparison is like-for-like. **The headline number is replaced without a caveat about its
meaning.**

### 7.1 What this does not establish

**Not that the defect is gone.** Zero failures in 150 bounds the residual at **[0%, 2.43%]**, against
the old 8/150 = 5.3% [2.3%, 10.2%]. A true rate of 2% yields zero failures in 150 trials about 5% of
the time. The statement this arm supports is *not detectable at this sample in this cell* — precisely
the wall §3 said it would hit.

**Not a cause.** Several prompt changes landed between 2026-09-10 and 2026-09-17 and this arm
attributes the improvement to none of them. It is a re-baseline, not a candidate arm.

**And this project has measured the trap it sits in.** The 89.4% → 25.0% gap is the same fixture at
the same distance scoring differently four days apart, **p = 5.0 × 10⁻⁹, still unexplained**. One
clean arm against that background is evidence, not proof. The cell was enforced rather than assumed,
which is more than any previous arm here can say — and it is still one arm.

**Six of the seven asking-days remain unmeasured**, exactly as before this run.
