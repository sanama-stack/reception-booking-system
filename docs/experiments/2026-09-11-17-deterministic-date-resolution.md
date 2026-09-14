# Pre-registration — [#17], fourth candidate: deterministic date resolution

**Written before any model call in this session.** Three candidates for [#17] have been rejected and
two of them made things measurably worse, in both cases discovered only because a control was run
beside them. Nothing below is written after seeing a number.

[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15

---

## 1. The mechanism being attacked

`date_from` fully determines the outcome. Over the ISO arm's 50 conversations, every trial that
searched the named date landed on it (42 of 42) and no trial that did not, did (0 of 8). The failing
trials search `[tomorrow, tomorrow+6]` — the default window — and write a slot from inside it.

The weekday arm makes the same thing far worse: `date_from` was aimed at the target in **10 of 50**
trials against ISO's 42, and the landings scatter across seven distinct dates.

**Why the model has no better option today.** The prompt's seven-day list is a lookup table, and it
is a lookup table for seven days. [#17]'s target is more than a week out, so for that class of date
there is nothing to look up and the model must do arithmetic it is measurably bad at. The second
rejected candidate proved the list cannot simply be made longer: at fourteen days, never-wrote went
16% → 42% and the model began using a date one day off the end of the list a hundred times over.

So this candidate gives the model **a capability it does not have**, rather than another instruction
about a capability it lacks. That is what distinguishes it from all three rejections: an instruction
at the decode point, a longer list, and a guard that refused writes.

## 2. The change

A ninth tool, `resolve_date(weekday, weeks_ahead)`, returning a calendar date computed in code.
The model supplies the two things it demonstrably gets right — which weekday the customer named, and
whether they meant the coming one or a later one — and the counting happens in a loop rather than in
a language model. Rule 11 is extended to send it there for any day beyond the seven-day list.

**The model still makes one inference**, from "after next" to `weeks_ahead = 1`. That is deliberate
and it is the floor of this approach: recognising that a phrase names a weekday is a far smaller
surface than computing which date that weekday falls on, but it is not zero.

## 3. Endpoints, fixed now

**Primary — the search window covered the named date.** Counted over **all** trials in the arm, so
the denominator is the arm size and no outcome is excluded. This is the mechanism itself, it is
upstream of the write, and it is not confounded by a trial that never wrote.

Window coverage rather than `date_from` exactness, because the harness's own comment records that
exactness is a lossy proxy: a trial searching `[2026-09-18, 2026-09-21]` has the wrong `date_from`,
the right window, and lands correctly. A fix that worked by widening the window would score zero on
exactness.

**Secondary — the strict landing rate**, `correct / (writes − other-reading)`, the figure [#17]
already carries as 13/29 = 44.8% for this arm. Reported whatever the primary does. It is the
customer-visible outcome and the primary is the reason for it; if they disagree, the disagreement is
the finding.

**Also reported, deciding nothing:** never-wrote count, `date_from` exactness, the distribution of
landing dates, and how often `resolve_date` was called at all.

## 4. The decision rule

Two arms, **50 conversations each**, `PROBE_DATE_STYLE=WEEKDAY`, same fixture, same target date,
differing only in the candidate.

**Accept** only if both hold on the **primary**:

1. Fisher exact, one-sided, **p < 0.05**; and
2. an absolute improvement of **at least 20 percentage points**.

Both, not either. The second clause exists because a statistically clean two-point move is not worth
a ninth tool, and the effect this candidate predicts is large — the ISO arm shows what aiming
`date_from` correctly is worth, and it is a gap of sixty-four points.

**Reject** on anything else, and **reject regardless** if either of these fires:

- **never-wrote rises.** The fourteen-day candidate's tell was a rise from 16% to 42%, and the guard
  candidate's was a fall. A candidate that improves the primary by refusing to act is not a fix.
- **any landing appears at a date one step off the resolver's output**, the fourteen-day candidate's
  signature failure. It is checked for explicitly rather than noticed.

## 5. What would make this inconclusive rather than negative

The baseline arm is being re-measured on the current tree rather than taken from the issue, because
`eb610f4` changed `lookup_appointment` on this exact path — the issue's own third comment records
that every reschedule conversation used to waste a round trip composing a fake `service_id`
immediately before the step that goes wrong, and that round trip is now gone. **If the re-measured
baseline differs materially from the issue's 13/29, that is itself a finding** and the comparison
still stands, because both arms run on the same tree.

## 6. Cost, stated before it is spent

100 live conversations, three turns each plus tool loops. Roughly half an hour of wall clock per arm
and a few dollars of API spend in total.

## 7. What this experiment cannot answer

It measures one phrasing — "the Monday after next" — against one target more than a week out. It
says nothing about [#17]'s ISO arm, whose 10.6% failure is a named date being **ignored** rather
than miscomputed, and which this candidate does not address. A win here narrows [#17]; it does not
close it.

---

## 8. The baseline, and the threshold it fixes

**Added after the baseline arm ran and before the candidate arm ran.** §4's rule was written first;
this only puts the measured number into it.

| Baseline, `WEEKDAY`, 50 conversations, current tree | |
|---|---|
| **Primary — a search window covered the named date** | **9 / 50 = 18%**, CI [8.6%, 31.4%] |
| `date_from` aimed exactly at the target | 4 / 50 = 8% |
| Strict landing rate | 8 / 20 = **40.0%** |
| Other reading (`2026-09-14`) | 24 |
| Never wrote | 6 |
| Landed on | `09-14` ×24, `09-21` ×8 (right), `09-22` ×5, `09-25` ×4, `09-15` ×3 |

It replicates [#17]'s recorded arm: 40.0% strict against the issue's 44.8%, and `date_from`
exactness 8% against 20%. Never-wrote is **lower** — 6 against 10 — which is the direction
`eb610f4` predicts, since the round trip that used to be wasted immediately before this step is gone.

**So the candidate must reach 19 of 50 on the primary.** That is the 20-point clause, and it binds
slightly harder than the p-value clause, which is satisfied from 18/50 (p = 0.035). At 19/50,
Fisher one-sided **p = 0.022**.

| Candidate primary | Verdict |
|---|---|
| ≤ 18 / 50 | **reject** |
| ≥ 19 / 50 | accept, if neither veto in §4 fires |

The vetoes stand unchanged: never-wrote must not rise above 6, and no landing may appear one step
off the resolver's own output.

---

## 9. Result

Both arms, 50 conversations each, `PROBE_DATE_STYLE=WEEKDAY`, same fixture, same tree apart from the
candidate.

| | Baseline | Candidate | |
|---|---|---|---|
| **Primary — window covered the named date** | 9/50 = **18.0%** | 29/50 = **58.0%** | Fisher one-sided **p = 3.5e-05** |
| Secondary — strict landing rate | 8/20 = **40.0%** | 40/49 = **81.6%** | Fisher one-sided **p = 0.0011** |
| `date_from` aimed exactly at the target | 4/50 = 8.0% | 27/50 = 54.0% | |
| Never wrote | 6/50 | **0/50** | |
| Wrong writes | 12/50 | 9/50 | p = 0.31 — not significant |
| Landed on | `09-14` ×24, `09-21` ×8, `09-22` ×5, `09-25` ×4, `09-15` ×3 | **`09-21` ×40**, `09-28` ×7, `09-22` ×1, `09-15` ×1, `09-14` ×1 | |

**The primary clears the threshold with room: 29 against the 19 required.** The secondary — the
customer-visible outcome — doubles. The "other reading" collapses from 24 to 1, which is the clearest
single sign that the resolution moved into code: the ambiguity did not go away, but the model stopped
resolving it by guessing.

### 9.1 The first veto does not fire

Never-wrote went **6 → 0**, not up. The candidate is not buying its primary by refusing to act — it
wrote in every trial and landed correctly in forty of them.

### 9.2 The second veto fires on a literal reading, and the evidence is mixed

> *reject if any landing appears at a date one step off the resolver's output*

**Seven landings on `2026-09-28`** — exactly one `weeks_ahead` step past the target, and a date that
appears **zero** times in the baseline. Read literally, that is the veto.

Read as a total, it is not: landings past the target are **9 in the baseline** (`09-22` ×5, `09-25`
×4) against **8 in the candidate** (`09-28` ×7, `09-22` ×1). The overshoot did not grow. It
**concentrated** — onto one date, one resolver-step out.

**Which of those two readings is right turns on a question this run cannot answer**, because of §9.3.

### 9.3 The instrumentation gap — `resolve_date`'s own calls were never recorded

The harness logs every `find_available_slots` call and no other tool, so **whether a `09-28` trial
asked the resolver for `09-28` or asked for `09-21` and overshot anyway is unknown.** The two have
opposite meanings:

- if the model called `weeks_ahead = 2`, the tool answered correctly and the residual is the
  interpretation §2 said would remain — the floor of this approach, not a regression;
- if it called `weeks_ahead = 1`, got `09-21`, and wrote `09-28`, then #17's core defect is intact
  and merely relocated.

**The searches that were logged point at both.** Trials 6, 20 and 50 searched `2026-09-21` *and*
`2026-09-28`. **Trial 40 searched only `2026-09-21`, twice, and landed on `2026-09-28` — a date it
never searched at all.** That single trial is the original defect, unchanged, in a run that otherwise
improved everything.

This is the second time an experiment in this repository has been unable to say whether its own
mechanism fired: the guard candidate never recorded `requested_date` (**G11**), and this one never
recorded `resolve_date`. **New gap, G17** — log the whole tool call, not the one tool the endpoint
reads.

---

## 10. The instrumented re-run — cut short by the provider, and partially decisive anyway

G17 was closed in the harness: every `resolve_date` call is now logged with **its arguments and its
answer**, and each wrong write is classified as landing on a date the resolver **GAVE** or one it
**NEVER GAVE**. The instrument was proven on two conversations before the arm was paid for — it
printed `resolve_date MONDAY+1 -> 2026-09-21` — because a query that silently returns nothing looks
exactly like a tool that was never called, which is the trap this whole exercise is about.

**The arm then stopped at fifteen trials: the OpenAI account ran out of credits.** Confirmed
directly against the API, not inferred — `HTTP 429`, `"type": "insufficient_quota"`,
*"You have no credits remaining."* The remaining 35 trials errored without reaching the model.

### 10.1 What the fifteen completed trials say

| | n | Landed |
|---|---|---|
| Model asked `MONDAY + 1` → `2026-09-21` | **9** | `2026-09-21` — correct, 9 of 9 |
| Model asked `MONDAY + 2` → `2026-09-28` | **6** | `2026-09-28` — 6 of 6 |
| **Wrong writes landing on a date `resolve_date` GAVE** | **6** | |
| **Wrong writes landing on a date it NEVER GAVE** | **0** | |

`resolve_date` was called in **15 of 15** completed trials, and **every** wrong landing was a date
the model had **asked the resolver for**. In this sample the tool was never bypassed and its answer
was never overshot: the residual is the interpretation §2 named in advance — "after next" read as
`weeks_ahead = 2` — and not the defect the veto was written to catch.

### 10.2 Why that is not yet the answer

- **n = 15, and they are the first fifteen of a run rather than a random sample.** The split is 6/6
  and 0/6, which is as clean as a small sample can be, but it is still fifteen.
- **It is a different run from §9's arm.** The seven `2026-09-28` landings that raised the veto were
  not these six.
- **§9's trial 40 still points the other way** and this run cannot speak to it: it searched
  `2026-09-21` twice, searched nothing else, and landed on `2026-09-28`. If that trial had called
  the resolver and been given `09-21`, it is the veto's failure mode exactly.

**So the veto is unresolved, and the evidence now leans toward it not applying.** Completing the arm
needs credits on the account and nothing else; the instrument is built, proven and committed.

### 10.3 A reporting flaw the outage exposed, and fixed

The harness counted an errored trial as *never wrote*. The summary line therefore read **"35 of 50
never wrote"** — a total behavioural collapse — when what had happened was an outage and no model had
been reached at all. Errors are now counted and reported separately, and the exception's message is
printed rather than only its class name.

**T36 — an uncounted error is reported as the behaviour you were measuring.** A harness that folds
"the call failed" into "the model declined to act" will hand you a dramatic, entirely false finding
on the day your provider has an outage. Every instrument that has a failure branch needs a counter on
it.

---

## 11. The arm completed — 2026-09-15

Credits were added to the account and the deciding arm was run to its end. Fifty conversations,
`PROBE_DATE_STYLE=WEEKDAY`, **0 errored**, `resolve_date` called in **50 of 50**. Result XML
preserved before the next `--rerun` could overwrite it.

```
35 of 50 writes landed on the named date (50 trials, 0 never wrote, 0 ERRORED)
the search included the named date in 19 of 50 trials
a search WINDOW covered the named date in 19 of 50 trials
landed on: {2026-09-28=35, 2026-10-05=12, 2026-10-12=2, 2026-09-29=1}
resolve_date asked: {MONDAY+1 -> 2026-09-28=40, MONDAY+2 -> 2026-10-05=10}
of the wrong writes, 10 landed on a date resolve_date GAVE and 5 on one it NEVER GAVE
```

| | control (2026-09-11) | this arm | Fisher, one-sided |
|---|---|---|---|
| **Window covered the named date** (primary) | 9/50 = 18.0% | **19/50 = 38.0%** | **p = 0.022** |
| Strict landing | 18/44 = 40.9% | **35/50 = 70.0%** | **p = 0.0041** |
| Never wrote | 6 | **0** | — |
| Nearer reading of the phrase | 24 | **0** | — |

### 11.1 The decision rule, applied literally

§8 says **≤ 18/50 reject; ≥ 19/50 accept, if neither veto in §4 fires.** The primary is **19/50** —
the rule's exact minimum. Met, not cleared.

- **Veto 1 — never-wrote must not rise above 6.** It is **0**. Does not fire.
- **Veto 2 — no landing may appear one step off the resolver's own output.** It **fires**, on two
  trials of fifty:

```
31  WRONG  landed=2026-10-05  searched=[2026-09-29]                resolver=[2026-09-28]
44  WRONG  landed=2026-10-05  searched=[2026-09-29]                resolver=[2026-09-28]
```

Both asked `MONDAY+1`, were answered `2026-09-28`, and wrote `2026-10-05`. That is the veto's
failure mode stated exactly: the model was given the right date and overshot it by one resolver
step. Two of fifty, against the seven that raised the veto in §9.

**So the pre-registration, read as written, does not accept this candidate.** Whether a 2-in-50
overshoot should sink a change that doubled the primary and eliminated both never-wrote and the
nearer misreading is a judgement — and §8 deliberately did not delegate it to whoever reads the
numbers afterwards. **`resolve_date` therefore stays shipped-but-under-test until the principal
calls it.**

### 11.2 What §10.1's fifteen trials got right, and what they got wrong

They said **GAVE 6, NEVER GAVE 0** and concluded the evidence "leans toward the veto not applying".
At fifty it is **GAVE 10, NEVER GAVE 5**, and two of those five are the veto exactly. The lean was
in the right direction about the *dominant* residual — the model asking for the wrong week, which
is now the largest single failure at 10 of 50 — and wrong about the veto, which a zero in fifteen
trials could never have established.

**T192 — a clean split in a truncated sample is still a truncated sample.** 6/6 and 0/6 is as tidy
as fifteen trials can look, and the thing it was cleanest about is the thing it had least power to
see: the veto's failure mode appears at about 4%, which fifteen trials miss 54% of the time.

### 11.3 The primary did not replicate

The 2026-09-11 arm recorded **58%** on the window metric; this one records **38%**. Fisher
one-sided **p = 0.036** that the earlier figure was genuinely the better one — so this is a real
gap, not sampling noise, and it is unexplained. Strict landing moved 81.6% → 70%, p = 0.13, which
is no result either way.

**`81.6%` must not be quoted again.** Two arms of the same candidate, a calendar four days apart,
disagreeing at p = 0.036 on the metric the decision rests on, means the figure to carry forward is
this one — and carried with the gap attached.

### 11.4 The counter was watching the reading nobody takes

`OTHER READING` counted the **nearer** occurrence of the weekday — `2026-09-21` here. Across both
arms measured on 2026-09-15, one hundred conversations, it was taken **zero** times. The reading
the model actually takes is the **further** one, `2026-10-05`, ten times in fifty — and it was
being scored as this defect.

`ResolveDateTool`'s javadoc predicted precisely this and declined to fix it in code, on the grounds
that "the Monday after next" is ambiguous in English and "a resolver that picked a reading in code
would be guessing with more confidence than the model, not less". A measurement of a boundary the
design deliberately left open has to be able to see both sides of it.

Both readings are now counted and named separately. **The change lands after this arm and does not
re-score it**: re-reading a completed run under a rule written once its numbers were visible is the
thing pre-registration exists to prevent. The next arm gets the better instrument; this one is
reported as it was scored.

**T193 — a counter aimed at the alternative you thought of measures your imagination.** The
alternative reading was named in the design a week before the arm ran, and the counter still
pointed at the other one.

### 11.5 `2026-09-29` is a magnet, across both arms

Every wrong trial here searched `2026-09-29` **first**, and in the same day's ISO arm 37 of 38
wrong searches went to `2026-09-29` and stopped there. It is `today + 14`, and `date_to` in
`find_available_slots` is documented as "max 14 days".

**This is a hypothesis and has had no arm of its own.** But it is the same shape as the finding
already recorded above — landings concentrating on `today+7`, the last row of the seven-day list —
one horizon further out, and it is now visible in two independent arms on the same day. The
weekday arm recovers from it by calling the resolver; the ISO arm, which never calls the resolver,
writes from it.
