# Pre-registration — [#17]: does rule 13 hold when the date is phrased relatively?

> **Written 2026-09-22, before any conversation is bought.** Endpoints, decision rule and vetoes are
> fixed below. Nothing in §3–§6 may be edited after the first trial runs; §8 is where the result
> goes.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#40]: https://github.com/sanama-stack/reception-booking-system/issues/40

---

## 1. The question, and why it is the last one worth asking about [#17]

Rule 13 was accepted on 2026-09-17 against **an explicit ISO date**: *"move it to 2026-09-21"*. On
that phrasing, correct landing went **22% → 98%**.

**Customers do not say 2026-09-21.** They say *"the Monday after next"*, and that phrasing has its
own failure history: it is what `resolve_date` was built for, what the fourth candidate was
authorised over, and the arm on which every pre-fix measurement of this defect is worst.

**Rule 13 has never been run against it.** Not once. Every list this project keeps is now fully
ticked, [#17] ships knowingly at 2% — and that 2% is a statement about a phrasing customers do not
use. This is the largest unknown left in the system and it is not a box on any list, which is
precisely why it needs a pre-registration rather than a to-do.

**This is not a candidate arm.** Nothing is being changed. It measures a prompt this repository
already ships, on an input it has never been measured against.

---

## 2. The correction this experiment starts from

**A figure was being carried that describes a prompt we do not ship**, and it was carried into three
places in `docs/07-mvp-scope.md` on 2026-09-22 before being caught the same day.

| Arm | Phrasing | Resolver | Rule 13 | Result |
|---|---|---|---|---|
| 2026-09-11 | relative | **no** | no | **55.2% wrong** — the figure that was being quoted |
| 2026-09-11 | relative | yes | no | 81.6% strict landing — **retired**, *"must not be quoted again"* |
| **2026-09-15** | **relative** | **yes** | no | **35/50 = 70% strict landing**, window 19/50, never wrote 0 |
| 2026-09-17 | **ISO** | yes | **yes** | 49/50 = 98% correct landing |
| — | **relative** | yes | **yes** | **never measured. This experiment.** |

`RescheduleDateFidelityRateTest`'s javadoc had said the right thing all along — *"the baseline to
compare against is 2026-09-15, not 2026-09-11"* — and the documents quoted 2026-09-11 anyway. The
lesson is the project's own and it is getting expensive: **a number with a date attached is not the
same as a number that still applies.**

**What this changes about the experiment**: the honest pre-fix reference is 70%, not 44.8%. A rule
that takes relative phrasing from 70% to 90% is a real result; measured against 44.8% it would look
like a triumph it is not.

---

## 3. The comparison is within one harness, and this is the whole design

**The 98% cannot be a control.** It was measured by `RescheduleRefusalRateTest`, which has no
phrasing knob — it is ISO-only. The relative arm must run on
`RescheduleDateFidelityRateTest`, the only harness with `PROBE_DATE_STYLE`. **Two harnesses, and
their metrics are not the same metric:**

| | `RescheduleRefusalRateTest` | `RescheduleDateFidelityRateTest` |
|---|---|---|
| Denominator | trials minus errored; **refusals stay in** | trials; **`NO WRITE` kept out of the rate** |
| Third category | none | **`OTHER READING`**, for the genuine English ambiguity |
| Phrasing | ISO only | `ISO` or `WEEKDAY` |

Comparing 98% from one against a number from the other is **T194 wearing new clothes** — a
difference attributed to the variable under test that in fact comes from the instrument.

**So both arms run on `RescheduleDateFidelityRateTest`**, on one day, at one distance, differing in
`PROBE_DATE_STYLE` and nothing else:

```sh
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=ISO \
  ./gradlew test -PincludeTags=probe --tests '*RescheduleDateFidelityRateTest' --rerun
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY \
  ./gradlew test -PincludeTags=probe --tests '*RescheduleDateFidelityRateTest' --rerun
```

**The ISO arm is not decoration.** It is the control that makes the WEEKDAY number mean something,
and it doubles as a bridge: if it does not land near 98%, the two harnesses disagree about a prompt
neither of them changed, and **that is a finding about the instruments** that must be resolved
before either number is quoted again.

**`PROBE_TARGET_DAYS` is deliberately left unset, and the first draft of this section had it pinned
at 14 — which would have been the confound it was written to prevent.** Corrected before any trial
ran; nothing in this file has been edited after a number was collected.

T194 is not optional: `BookingScenario.monday` is `today + 7` rolled forward to the next Monday, so
it drifts with the weekday, and two arms at different distances compare the calendar as much as the
phrasing. **The baseline this experiment tests against ran on 2026-09-15, a Tuesday, at 13 days out**
— target Monday 2026-09-28, with `nearer = 2026-09-21` and `further = 2026-10-05`, which is exactly
what §11.4 of the resolver's record documents. That is the harness default, not a pinned value.

**Today is also a Tuesday.** The default therefore reproduces the baseline's geometry exactly —
13 days out, a Monday target of 2026-10-05, phrased *"the Monday after next"* — and pinning 14 would
instead have produced a **Tuesday** target at 14 days, a different distance *and* a different weekday
from the only arm it is being compared with. The calendar is doing the work here and it is doing it
by accident; if this experiment had been run a day later it would need a different design, and that
is worth saying out loud rather than relying on again.

---

## 4. Endpoints, fixed now

**Primary — strict landing on the WEEKDAY arm.** The appointment ends at the date *and* time the
Customer named, read from the database. `OTHER READING` counts in neither numerator nor denominator,
in **both** directions: it is neither a success nor this defect, and folding it either way is how a
measurement of an ambiguity the design deliberately left open goes wrong.

**Reported and deciding nothing**: the window-covered rate, the `GIVEN` / `NEVER GIVEN` provenance
split on wrong trials, and the ISO arm's own landing rate. They are diagnosis, not verdict.

---

## 5. The decision rule

There is no candidate here, so there is no accept or reject. **There are three outcomes and they are
named now so that none of them can be argued into being another one afterwards.**

Every threshold below is Fisher one-sided against the 2026-09-15 baseline of **35/50**, computed
with the committed, self-validating `backend/tools/receptionist-probe/stats.py` rather than by hand.
**The first draft of this section was wrong** — it claimed 44/50 and 26/50 from memory, and 26/50 is
**p = 0.0502**, on the wrong side of the line it was being quoted for. That is the fourth time this
project has re-derived statistics it already owns a tool for.

| WEEKDAY strict landing | Fisher vs 35/50 | Reading | What follows |
|---|---|---|---|
| **≥ 43/50 (86%)** | **p = 0.0448**, better | Rule 13 carries to relative phrasing | [#17]'s rate is a property of the defect, not of the phrasing. The 2% shipped knowingly is the honest headline and the entry may say so |
| **26–42/50** | no significant move either way | **Undetermined, and that is a result** | If the point estimate is at or below 35/50, **a sixth candidate is warranted** and this arm is its baseline. [#17]'s entry must stop implying 98% describes customer input |
| **≤ 25/50 (50%)** | **p = 0.0328**, worse | Relative phrasing is worse than before rule 13 | Rule 13 has **traded one input for another**. A regression in shipped behaviour outranks everything else open |

**The middle band is wide, and pretending otherwise would be the error this table exists to
prevent.** At fifty trials against a 70% baseline the smallest detectable improvement is **+16
points** and the smallest detectable regression is **−20 points**. A real move of ten points in
either direction is invisible to this arm, and a result of 40/50 means *"not resolved"*, not
*"unchanged"*. **T192: a clean split in a truncated sample is still a truncated sample.**

**I wanted a regression to be easier to declare than a win, and the arithmetic refused.** Raising the
lower band to 31/50 would have done it — at the cost of a **26.3%** chance of declaring a regression
that is not there. A threshold that fires on one arm in four is not a safeguard, so the band stays at
the exact test and the asymmetry is abandoned rather than smuggled in.

**Veto — the harnesses disagree.** If the **ISO** arm lands **below 40/50 (80%)**, neither number is
reportable and no conclusion about phrasing may be drawn. A prompt measured at 98% five days earlier
should not read as 80% on an instrument that changed nothing about it, and **T195: a signature
consistent with two explanations is evidence for neither**. Against a true 98% this veto fires with
probability **3.7 × 10⁻⁹**, so it costs essentially nothing to carry.

**T198 is respected**: the veto is a rate with a threshold, not an absolute. No single anomalous
trial sinks the arm.

---

## 6. Cost, stated before it is spent

**100 live conversations**, three turns each plus tool loops. This harness is several times slower
per conversation than the weekday one — budget **roughly 40 minutes** of wall clock and a few
dollars. The result XML is not streamed by Gradle; read it out of
`build/test-results/test/TEST-dev.reception.ai.probe.RescheduleDateFidelityRateTest.xml` and archive
both arms before the second `--rerun` overwrites the first.

---

## 7. What this experiment cannot answer

- **One distance.** 14 days out, pinned. Nothing here transfers to *"next Monday"* at 3 days, which
  is a different arm and probably the more common customer input.
- **One reading of one phrase.** *"The Monday after next"* is ambiguous in English and the harness
  counts both readings separately rather than resolving the ambiguity. That is deliberate — see
  `ResolveDateTool`'s javadoc — but it means this arm measures fidelity to a *named* date, not
  comprehension.
- **It cannot attribute.** Rule 13 is not the only thing that changed between 2026-09-15 and now. A
  difference from 35/50 is a difference in the shipped system, not a causal claim about one rule.
- **It says nothing about [#40].** Different mechanism, different harness, and its own ruling of
  2026-09-22 says the scenario design comes before the budget.

---

## 8. Result — 2026-09-22. **Rule 13 carries to relative phrasing.**

Two arms, 100 live conversations, one day, one tree, one distance — 13 days out, target Monday
`2026-10-05` — differing only in `PROBE_DATE_STYLE`. **0 errored and 0 never-wrote in both**, so no
denominator is doing quiet work anywhere below.

| | Baseline 2026-09-15 | **WEEKDAY today** | ISO control today |
|---|---|---|---|
| **Strict landing** | 35/50 = 70.0% | **46/50 = 92.0%** | 48/50 = 96.0% |
| Clopper-Pearson | [55.4%, 82.1%] | **[80.8%, 97.8%]** | — |
| Fisher vs baseline | — | **p = 0.0047** | — |
| Searched the named date | 19/50 window | 43/50 exact, 46/50 window | 48/50 |
| `OTHER READING` | (miscounted — see 8.2) | 0 nearer, **4 further** | 0, 0 |
| `resolve_date` called | 50/50 | **50/50** | **0/50** |

§5 pre-registered **≥ 43/50** as *rule 13 carries*. The arm returns **46/50**.

### 8.1 The rate is not the finding. The absence is.

```
wrong at trials: []
resolve_date was called in 50 of 50 trials
asked: {MONDAY+1 -> 2026-10-05 = 46, MONDAY+2 -> 2026-10-12 = 4}
of the wrong writes, 0 landed on a date resolve_date GAVE and 0 on one it NEVER GAVE
```

**Zero wrong writes in fifty.** All four non-landings took the *further* reading of "the Monday
after next" — `2026-10-12` — and reached it by **asking `resolve_date` and using the answer it
gave**. That is the ambiguity `ResolveDateTool`'s javadoc named and deliberately declined to resolve
in code, on the grounds that "a resolver that picked a reading in code would be guessing with more
confidence than the model, not less". It is **not** [#17], which is a write landing on a date no tool
ever produced.

The provenance line is the proof and it is empty in both columns, because there was nothing to
classify. Clopper-Pearson on the defect rate under relative phrasing: **[0%, 7.1%]**.

**And phrasing no longer separates the arms.** ISO 48/50 against WEEKDAY 46/50, same day, same
harness, same distance: **Fisher p = 0.34**. On 2026-09-11 the same contrast was **p = 3.9 × 10⁻⁵**.
The gap that motivated the fourth candidate is gone — measured, not assumed.

### 8.2 §4 chose a denominator the baseline cannot supply, and I am reporting it rather than using it

§4 fixed the primary as excluding `OTHER READING` from **both** numerator and denominator. On this
arm that gives **46/46 = 100%**, CI [92.3%, 100%].

**That number is not comparable to 35/50 and is not used here.** The 2026-09-15 arm's counter watched
the *nearer* reading, which nobody takes; its ten *further*-reading trials fell into `WRONG` and
stayed there, because §11.4 of [the resolver's record](2026-09-11-17-deterministic-date-resolution.md)
ruled that the arm is **reported as it was scored** and not re-scored under a rule written once its
numbers were visible. That ruling is right and it binds this experiment too.

So the comparison above uses the **baseline's own convention** — other-readings counted against the
arm — which is the harsher reading of today's result and the only honest one. **The verdict is
identical under either**, which is the sole reason this is a paragraph rather than a re-run.

**The lesson is mine to carry: a pre-registered endpoint has to be computable on the control, not
only on the candidate.** §4 was written without checking that, and a decision rule that could only
be evaluated one-sided would have been useless at exactly the moment it mattered.

### 8.3 The ISO control is what makes any of this quotable

The veto in §5 fired at **below 40/50**. The ISO arm landed **48/50**.

That matters more than it looks. The 98% this project has been quoting since 2026-09-17 came from
`RescheduleRefusalRateTest`; today's 96% comes from `RescheduleDateFidelityRateTest`, five days
later, with a different denominator convention and a third outcome category the other harness does
not have. **Two instruments agreeing to within one trial about a prompt neither of them changed** is
what licenses reading the WEEKDAY number as a fact about phrasing rather than about tooling.

`resolve_date` was called **0 times in 50** on the ISO arm and **50 times in 50** on the WEEKDAY arm,
exactly as the harness's javadoc says it must. The control is measuring the explicit-date path and
nothing else.

Both ISO wrong trials (4 and 41) landed on `2026-09-23` — **tomorrow** — which is [#17]'s original
signature, not a phrasing artefact. The 2/50 residual is the same defect that ships knowingly at 2%.

### 8.4 The distance was nearly wrong, and the calendar rescued it by accident

§3's first draft pinned `PROBE_TARGET_DAYS=14`. **Corrected before any trial ran.** The baseline ran
at the harness default — `today + 7` rolled to the next Monday — which from Tuesday 2026-09-15 is
**13 days** to Monday 2026-09-28, confirmed against §11.4's recorded `nearer`/`further` pair of
`2026-09-21` / `2026-10-05`. Pinning 14 would have produced a **Tuesday** target at 14 days: a
different distance *and* a different weekday from the only arm it is compared with, which is T194
with a new hat.

Today is also a Tuesday, so the default reproduced the baseline's geometry exactly. **That is luck,
not design.** A run one day later needs a different design, and the next person should not inherit
the assumption that the default is safe.

### 8.5 What this does not settle

- **One distance.** 13 days. Nothing here transfers to *"next Monday"* at 3 days, which is both
  untested and the more common customer input.
- **One phrase.** *"The Monday after next"*, with its ambiguity counted rather than resolved. This
  measures fidelity to a **named** date, not comprehension of a phrase.
- **It attributes nothing.** Rule 13 is not the only change between 2026-09-15 and now. The
  improvement is a property of the shipped system, not a causal claim about one prompt rule.
- **The 4 further-reading trials are a real customer outcome.** They are not this defect and they are
  not a success either: a customer who meant 5 October and got 12 October has a wrong appointment. It
  is out of scope for [#17] and it is not out of scope for the product.
- **Nothing about [#40]**, whose own ruling of 2026-09-22 puts scenario design before budget.
