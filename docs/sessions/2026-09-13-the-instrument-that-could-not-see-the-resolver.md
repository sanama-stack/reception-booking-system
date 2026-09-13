# Session handoff — 2026-09-13 — the instrument that could not see the resolver

> **Both issues were taken, and neither could be worked the way its body says.** The account has no
> credits, and #15 and #17 are measurement-bound at their core. What was available instead turned out
> to matter more than another candidate would have.
>
> **[#15]'s only working instrument has been blind to `resolve_date` since the resolver existed.**
> `WeekdayResolutionRateTest` reads `find_available_slots` and no other tool — which is **G17
> exactly**, the gap that cost [#17]'s fourth candidate its own veto. G17 was closed in the
> reschedule harness the day it was found, **and never carried across.** The next paid
> 150-conversation run would have produced a rate nobody could interpret, for the second time, at
> twelve minutes and real money.
>
> **T36 was waiting in the same loop.** An errored trial hit a bare `continue` while the denominator
> stayed at `CONVERSATIONS`, so an outage would have printed the resolution rate collapsing. That is
> the false finding the credit exhaustion already produced once, next door.
>
> **And the reason it was missed twice is a copy.** The resolver SQL was duplicated between the two
> harnesses, so a fix applied to one left the other silently blind. It now lives once, in
> `ProbeQueries`, and `ProbeInstrumentationTest` — **untagged, so CI runs it** — proves those
> constants against a scripted conversation that really calls the resolver. Proven non-vacuous by
> planting.
>
> **[#15]'s recorded baseline is stale and nobody had noticed.** 142/150 was measured **2026-09-10**.
> The resolver landed **2026-09-11** and the prompt changed again on **2026-09-13**. The issue still
> tells the next reader to beat 142/150; doing that would compare a candidate across two prompt
> changes. **The first credited run must re-baseline, not test a candidate.**
>
> **[#17]'s tracker is a fourth candidate behind.** Its last comment, 2026-09-11, says two candidates
> were rejected and the surface looks structural. The fourth shipped that same day and **won** — the
> customer-visible outcome doubled — and **that was never posted.**
>
> **`src/main` is untouched.** No prompt changed, nothing was measured, no model was called. 1063
> backend tests, 0 failed. **Committed, not pushed** — `dev` is now **5 ahead of `origin/dev`**.

[#13]: https://github.com/sanama-stack/reception-booking-system/issues/13
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[prev]: ./2026-09-13-neither-fix-was-the-defect.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`26e09eb`** — unchanged |
| `origin/dev` | **`02463a0`** — unchanged. Local `dev` is **5 ahead** |
| CI | **Has still seen none of it.** [prev] §11's warning stands, and now covers one more commit |
| Backend | **1063 tests, 0 failed** — 1059 plus the four in `ProbeInstrumentationTest` |
| Frontend | 84 tests, unchanged. Not touched this sitting |
| Migrations | `V10`, unchanged. No new ADR |
| Issues | **[#15] and [#17] open and still open.** Both credit-blocked at the core; no comment posted |
| Phase 11 | **62 ticked, 10 open.** No box moved |
| Credits | **Still none.** Sixth handoff to carry it |

---

## 2. What was actually wrong, and it was not in `src/main`

[#15] names three instruments and says only one of them can see the problem. That one could not see
the mechanism the current prompt depends on.

```java
// WeekdayResolutionRateTest, as it stood
List<String> dates = jdbc.queryForList(
        "select tool_arguments->>'date_from' from ai_messages "
                + "where role = 'TOOL' and tool_name = 'find_available_slots' "
                ...
```

`find_available_slots`, and nothing else. `resolve_date` has been in the prompt since **`c81d312`**
on 2026-09-11, and this harness could not report whether it was called, what it was asked, or what
it answered.

**That is G17, verbatim.** The experiment doc's own words: *"This is the second time an experiment in
this repository has been unable to say whether its own mechanism fired."* It was closed in
`RescheduleDateFidelityRateTest` immediately — and the other harness was never touched, because the
two carried the same SQL **by copy** rather than by reference.

### 2.1 Why it matters here specifically, and it is not the same as next door

The resolver was built for [#17]: days **beyond** the seven-day list. The day this harness asks about
is **inside** it — `PROBE_WEEKDAY=MONDAY`, row 4 on a Thursday — where the prompt says to look the
date up rather than resolve it.

So a resolver call here is a **deviation from the prompt's own rule**, and possibly a beneficial one:
[#15]'s defect is mis-scanning the list, and the resolver does that arithmetic in code. Either way it
has to be visible, because

> a rate that moved because the arithmetic left the model, and a rate that moved because the list got
> easier to scan, are **the same number with opposite meanings**.

Wrong searches are now classified `GAVE` / `NEVER GAVE` per trial, the same distinction the
reschedule harness draws.

### 2.2 T36, in the same loop

```java
} catch (RuntimeException e) {
    System.out.printf("%2d  ERROR %s%n", trial, e.getClass().getSimpleName());
    continue;               // not counted, and CONVERSATIONS is still the denominator
}
```

Thirty-five errored trials would have read as the rate cratering — the exact shape the outage
produced next door, where *"35 of 50 never wrote"* described an API failure rather than a model.
Errors are now counted, printed **with the exception's message** (a read timeout and *"no credits
remaining"* are both `RuntimeException`), and a partial run says so in a warning under the rate.

---

## 3. The instruments are now proven without spending anything

The rate harnesses are the code here **most likely to be wrong and least likely to be caught**: tagged
`probe`, never run by CI, and only run at all by someone with a funded key and twelve minutes.

`ProbeInstrumentationTest` is **deliberately untagged**. It drives a scripted conversation that really
calls `resolve_date` and `find_available_slots`, then executes `ProbeQueries`' **own constants**
against the rows that conversation wrote. Referencing the constant is the point — a test that re-typed
the SQL would prove a copy.

| What it pins | |
|---|---|
| `MONDAY+1 -> 2026-09-21` renders, arguments **and** answer | the shape both harnesses print |
| a refused call → `ERR VALIDATION_FAILED`, and **no** date | a rejected call is not a date the resolver gave |
| no resolver call → empty, not a phantom row | the `NEVER GAVE` branch |
| several calls come back **in order** | the runs that matter are the ones where it asked twice |

**Proven non-vacuous by planting.** Pointing the constant at `resolve_dateX`:

| | |
|---|---|
| three of four | **red** |
| the emptiness test | **green — and it cannot go red** |

That fourth is the *empty set that passed* shape, and it is **said out loud in its own Javadoc**
rather than left to be rediscovered. It is safe only because its three siblings prove the same
constant finds rows when rows exist.

---

## 4. [#15] — the baseline is two prompt changes stale

Not noticed before this sitting, and it invalidates the issue's own instruction to beat 142/150.

| | |
|---|---|
| 142/150 measured | **2026-09-10** |
| `c81d312` — the resolver lands | **2026-09-11** |
| *Fence the owner text* — prompt changes again | **2026-09-13** |

The issue says *"the current wording is 48/50, so a candidate needs a real sample to beat it."* There
is no current measurement of the current wording. A candidate arm compared against 142/150 would be
comparing across two prompt changes and calling the difference a fix.

**So the first credited run on [#15] is a re-baseline, not a candidate.** That is a change to how the
issue says to work, and it is cheap: one 150-conversation arm, ~12 minutes, on an instrument that can
now interpret its own result.

### 4.1 Three claims in [#15]'s body are now false

| The body says | Actually |
|---|---|
| the comment at `SystemPromptBuilder.java:169` carries the superseded claim | **corrected** on 2026-09-10 — it now reads *"WHAT IS LEFT IS THE SAME MISTAKE"* |
| `PROBE_CONVERSATIONS` *"is not committed yet"* | **committed**, with Javadoc, at `WeekdayResolutionRateTest:79` |
| the `date_from` schema candidate *"has never been run"* | **run and rejected** — [#17]'s candidate 1, p = 0.666 |

And `resolve_date` is not mentioned in [#15] at all.

---

## 5. [#17] — the tracker is a candidate behind, and it is the one that worked

[#17]'s last comment is **2026-09-11**: two candidates rejected, one harmful, *"reasonable evidence
that the surface is structural"*, three options that are all decisions.

`c81d312` landed **the same day** and the pre-registered result is in
`docs/experiments/2026-09-11-17-deterministic-date-resolution.md` §9:

| | Baseline | `resolve_date` | |
|---|---|---|---|
| **Primary — window covered the named date** | 9/50 = 18% | **29/50 = 58%** | p = 3.5e-05 |
| Secondary — strict landing | 8/20 = 40% | **40/49 = 81.6%** | p = 0.0011 |
| Never wrote | 6/50 | **0/50** | |

Threshold was 19/50, fixed before the arm ran. It reached 29.

**None of that is on the issue.** Anyone opening [#17] today reads that the surface is structural and
picks one of three options, not knowing a fourth candidate shipped and roughly doubled the
customer-visible outcome.

### 5.1 What is genuinely left on [#17]

The veto in §4 of the pre-registration — *reject if any landing appears one step off the resolver's
output* — **is unresolved.** Seven landings on `2026-09-28` raised it; the instrumented re-run that
would settle it **got fifteen trials in before the credits ran out**.

Those fifteen lean clearly toward the veto not applying:

| | n | |
|---|---|---|
| `resolve_date` called | **15 of 15** | never bypassed |
| wrong writes on a date the resolver **GAVE** | **6** | the interpretation floor §2 predicted |
| wrong writes on a date it **NEVER GAVE** | **0** | the original defect: absent |

But n=15, they are the first fifteen rather than a sample, and §9's trial 40 — searched `09-21`
twice, landed `09-28` — still points the other way and this run cannot speak to it.

**35 trials finish it.** The instrument is built, proven, committed, and now CI-proven too.

---

## 6. Traps

**T161 — a lesson fixed in one copy of a duplicated thing is not fixed.** G17 was closed the day it
was found, in the harness where it was found, and the sibling harness carried the same SQL by copy
and stayed blind for two days. The fix that actually closes it is the *de-duplication*, not the
patch. Neither harness's tests would have caught it, because neither harness has tests — which is
T162.

**T162 — the code least likely to be run is the code most likely to be wrong.** Both rate harnesses
are `probe`-tagged: no CI, a funded key, twelve minutes. Their SQL had never been executed by anything
that could fail. An instrument used to spend money needs a cheap proof that runs on every push, or it
is trusted in exact proportion to how rarely it is checked.

**T163 — an emptiness assertion cannot detect the query that returns nothing by mistake.** Planting a
wrong tool name turned three of four tests red and left the fourth green, because green is what it
asserts. It is not worthless — it exercises a branch — but it is **not evidence**, and it is only safe
beside siblings that prove the same query finds rows. Fourth instance of this shape here.

**T164 — a recorded baseline expires when the thing it measured changes.** [#15] carries 142/150 as
*the current wording* across a new tool and a prompt edit. Nothing marks a measurement as stale, so a
number that was carefully made and correctly recorded quietly became a trap for the next reader who
does exactly what the issue says.

---

## 7. Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**, **G39**, **G40**, **G43**, **G44**.

**G17 is closed properly.** It was closed in one harness on 2026-09-11; it is now closed in both, and
the duplication that let it reopen silently is gone.

**G45 is new and open.** *A measurement has no expiry.* Rates are recorded in issue bodies and code
comments with a date, and nothing connects them to the commits that would invalidate them. [#15]'s
142/150 survived the resolver and a prompt edit still described as *current*. A convention would do —
naming the commit a rate was taken at, so a reader can check whether the prompt has moved since.

---

## 8. Commands

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew test --tests '*ProbeInstrumentationTest' --rerun
```

When there are credits, and **in this order**:

```bash
# 1. [#15] -- RE-BASELINE the current prompt. Not a candidate. ~12 min.
PROBE_CONVERSATIONS=150 ./gradlew test -PincludeTags=probe \
  --tests '*WeekdayResolutionRateTest' --rerun

# 2. [#17] -- finish the arm the outage cut at 15. ~25 min.
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY ./gradlew test -PincludeTags=probe \
  --tests '*RescheduleDateFidelityRateTest' --rerun
```

Gradle does not stream these; the log and the rate are in `build/test-results/test/*.xml` under
`system-out`. **Preserve the XML per run** — the next `--rerun` overwrites it. Always `--rerun`.

---

## 9. Confidence

**Certain the harness was blind.** The query is quoted above and the commit dates bracket it.

**Certain the new instrumentation works**, and it is the only claim here proven by running rather
than by reading: four tests, planted, three red and one that cannot be.

**Certain the baseline is stale** — three dates from `git log`, all in §4.

**Certain [#17]'s resolver result was never posted**: the issue has three comments, the last dated
2026-09-11, and none of them contains §9's table.

**Explicitly unproven: anything about the model.** No conversation was run. The resolution rate under
the current prompt is **unknown** — not unchanged, unknown — and the new `GAVE`/`NEVER GAVE`
classifier has never seen a live trial. It is proven against scripted rows, which proves the SQL and
not the finding.

**Explicitly unproven: CI has seen none of this**, now across five commits.

---

## 10. What is the principal's

1. **Credits.** Sixth handoff. Both issues are blocked at the core on the same thing, and §8 says
   exactly what to spend them on and in what order. ~40 minutes and a few dollars ends [#17]'s veto
   and gives [#15] a baseline that means something.
2. **Whether to post §4.1 and §5 to the tracker.** Both issues currently mislead a reader in ways
   that would cost a session. Nothing was posted this sitting.
3. Carried: **G43** (branch protection), and [prev]'s push.
