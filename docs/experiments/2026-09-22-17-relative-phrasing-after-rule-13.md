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

- **One distance.** 13 days, the harness default. *(This line said "14 days out, pinned" until
  2026-09-22 — left over from §3's first draft and contradicting §3 and §8.4 of this same document
  once they were corrected. Fixed when it was noticed, which was after the arm had run.)*
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

- **One distance.** 13 days. **A short-distance arm was proposed here and withdrawn on
  2026-09-22 — see §8.6, because the proposal was wrong in a way worth keeping.**
- **One phrase.** *"The Monday after next"*, with its ambiguity counted rather than resolved. This
  measures fidelity to a **named** date, not comprehension of a phrase.
- **It attributes nothing.** Rule 13 is not the only change between 2026-09-15 and now. The
  improvement is a property of the shipped system, not a causal claim about one prompt rule.
- **The 4 further-reading trials are a real customer outcome.** They are not this defect and they are
  not a success either: a customer who meant 5 October and got 12 October has a wrong appointment. It
  is out of scope for [#17] and it is not out of scope for the product.
- **Nothing about [#40]**, whose own ruling of 2026-09-22 puts scenario design before budget.

### 8.6 The short-distance arm was proposed, authorised, and is not runnable

This document and `07-mvp-scope.md` both ended by naming *"next Monday at three days out"* as the
obvious next arm — *"untested, and the commoner customer input"*. It was authorised. **It was then
withdrawn before a single conversation was bought, because it cannot produce evidence.**

Two reasons, and the second is the one that matters.

**The harness cannot phrase it.** `spokenDate` is `"the " + dayName + " after next"` for every
non-ISO style, and `dayName` is derived *from the target*. `PROBE_TARGET_DAYS=3` from a Tuesday gives
a Friday target of `2026-09-25` and the sentence *"the Friday after next"*, which means `2026-10-02`.
The spoken phrase would name a different date than the one being scored, and **every trial would read
as wrong for a reason belonging to the instrument** — T36 exactly.

**And the defect is unreachable at that distance by construction.** `RescheduleDateFidelityRateTest`'s
own javadoc says so, under *Why not a bare weekday*: a bare weekday can only ever name a date within
seven days, and every observed failure of [#17] had the model searching `[tomorrow, tomorrow+6]` — so
a short target sits **inside the very window the model wrongly substitutes**. Such an arm *"would come
back near-perfect while proving nothing"*. **#17 needs a target more than a week out, which a bare
weekday cannot express.**

**The proposal was written without reading the file it was a proposal about** — the same javadoc that
had already corrected this experiment once, by naming 2026-09-15 rather than 2026-09-11 as the
baseline. Twice in one day, from one document, on the same reading.

**What would be a real short-distance question**, kept here so the withdrawal does not read as a dead
end:

- **ISO at three days.** Explicit date, near target, no phrasing ambiguity and no harness change —
  does rule 13's *search from the requested date* hold when the date is close? Runnable today.
- **A bare-weekday style**, added to `DATE_STYLE`. It answers a **product** question — does the
  Receptionist handle *"next Monday"* correctly? — and it is **not** a [#17] arm. Filing it as one
  would be measuring a defect in a place it is known not to occur.

---

## 9. Pre-registration — the distance arm, ISO at three days

> **Written 2026-09-22 after §8, before the arm runs.** A second question on the same tree, reusing
> §8's ISO arm as its control. §9.1–§9.3 are fixed now; §9.4 is where the result goes.

### 9.1 The endpoint is the mechanism, not the landing — and this is the whole point

§8.6 withdrew a bare-weekday arm because the target would sit inside `[tomorrow, tomorrow+6]`, the
window [#17] substitutes, making the failure **unreachable in the outcome**. **That argument applies
to an ISO date at three days just as hard.** A model that ignores the requested date and searches
the coming week still covers `2026-09-25`, still gets offered a real 15:00 slot, and still writes the
right appointment. **Landing would come back near-perfect and mean nothing.**

So landing is *not* the primary here. The primary is **`searched the requested date` exactly** —
`date_from == target` — which the harness already records separately from `window covered`. That
distinction is precisely what separates *did the right thing* from *got away with it*, and at this
distance it is the only thing that can.

It is also the literal text of the rule under test: rule 13 says **"search from the date the customer
asked for"**. This arm asks whether that holds when the date is close enough that nothing would go
wrong if it did not.

**Primary**: exact-search count over 50 trials. **Reported and deciding nothing**: strict landing
(expected at or near ceiling, for the reason above — written here *before* the run so a high number
cannot later be read as a success), `window covered`, and the provenance split.

### 9.2 The control is §8's ISO arm, and the comparison is distance alone

Same day, same tree, same harness, same `PROBE_DATE_STYLE=ISO`, same fixture. **The only variable is
`PROBE_TARGET_DAYS`.** That is the first time this project has isolated distance, which
**T194** has called uncontrolled in every reschedule rate it ever recorded.

Control: **48/50 exact-search** at 13 days (target `2026-10-05`). Arm: three days, target
**`2026-09-25`, a Friday** — the weekend guard passes, so no trial is lost to a closed business.

### 9.3 The decision rule

| Exact search at 3 days | Fisher vs 48/50 | Reading |
|---|---|---|
| **≥ 43/50** | no significant difference | Rule 13 holds at short distance. The mechanism is not distance-dependent |
| **≤ 42/50** | **p ≤ 0.0458**, worse | **The rule degrades when the date is near** — and would have been invisible in every landing-based measurement this project has taken |

**Veto — the arm is void, not interpreted**, if `ERRORED` **+** `NO WRITE` exceeds **5/50**. Both were
0 in both of §8's arms, so anything material here is the fixture behaving differently at a short
distance rather than the model, and a rate computed over a shrunken denominator is the T36 shape this
project keeps paying for.

**Cost**: 50 conversations, roughly 9 minutes, on the same key.

### 9.4 Result — 2026-09-22. **Distance is not the variable.**

| | 13 days (§8's ISO arm) | **3 days** |
|---|---|---|
| **Exact search** — the primary | 48/50 | **47/50**, CI [83.5%, 98.7%] |
| Landing — reported, decides nothing | 48/50 | 47/50 |
| `window covered` | 48/50 | 47/50 |
| `ERRORED` + `NO WRITE` | 0 | **0** — veto does not fire |
| `resolve_date` calls | 0/50 | 0/50 |

§9.3 set **≤ 42/50** as *the rule degrades when the date is near*. The arm returns **47/50**, and
Fisher against the control is **p = 0.50** — as close to no difference as fifty trials can express.

**This is the first arm in this project to isolate distance.** Same day, same tree, same harness,
same `PROBE_DATE_STYLE`, same fixture; only `PROBE_TARGET_DAYS` differs. **T194** has called distance
uncontrolled in every reschedule rate recorded here. Controlled, it does nothing.

#### The pre-registration protected against a failure that did not occur, and that is still the point

§9.1 predicted landing would sit at ceiling and mean nothing, because a wrong `[tomorrow,
tomorrow+6]` search still covers a three-day target — the model would **get away with it**, and a
landing-based primary would read that as success.

**That mechanism never appeared.** Exact search, landing and `window covered` are all **47/50**: the
model either aimed at the requested date or it did not, and no trial was rescued by a sloppy window
that happened to contain the answer.

So the primary and the discounted secondary agree, and **choosing the harder endpoint changed
nothing about the conclusion**. It was still right to choose it. Had landing decided, this arm would
have reached the same verdict *by luck*, and nothing in the result would have revealed the
difference. **A pre-registration that turns out to be unnecessary is not a pre-registration that was
wrong** — it is the only kind whose value can be checked afterwards, and the check is that §9.1's
reasoning was published before the numbers existed and can now be read against them.

#### The three misses are [#17] intact, not a distance effect

```
landed on: {2026-09-25=47, 2026-09-28=3}
of the wrong writes, 0 landed on a date resolve_date GAVE and 3 on one it NEVER GAVE
```

All three went to **`2026-09-28`, the following Monday** — a date **no tool ever produced**, which is
this defect's original signature rather than anything about proximity. 3/50 here against 2/50 at
thirteen days: the same residual, and the difference is noise.

#### What §8 and §9 establish together

Rule 13 holds across **both phrasings and both distances tested** — ISO and relative, three days and
thirteen — with a residual of **2–6%** that looks like one defect rather than several. [#17]'s entry
in [07-mvp-scope.md](../07-mvp-scope.md) carried three limits this morning; §8 removed the phrasing
limit and §9 removes the distance one.

**What is left is not a limit on these arms but a property of the defect**: it persists at a few
percent, it writes a date no tool produced, and nothing measured today moves it. Whether that is
worth a sixth candidate is the principal's, and the ruling of 2026-09-22 — *ship knowingly at 2%* —
already answers it until somebody asks again.

#### What this still cannot say

- **One fixture.** `BookingScenario`, one business, one service, one employee, a booking at 12:00 and
  a request for 15:00 the same day. The *time*-change flow is the one [#17] was observed in, and it
  is the only flow either section measured.
- **Two distances are not a curve.** Three days and thirteen agree; nothing here says what happens at
  forty, near the sixty-day horizon.
- **It says nothing about bare-weekday phrasing**, which §8.6 explains cannot be an arm for this
  defect at all.
