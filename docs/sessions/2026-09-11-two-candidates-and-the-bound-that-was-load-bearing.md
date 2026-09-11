# Session handoff — 2026-09-11 — Two candidates, and the bound that was load-bearing

> **Purpose.** The first session to attempt a **fix** for [#17] rather than measure it. Two
> candidates were screened against a control measured the same day. **Both were rejected**, and the
> second was actively harmful. No product code survives this session; `src/main` is byte-for-byte
> as it was.
>
> **§2.3 is the one to carry.** Lengthening the dated list from seven days to fourteen did not
> extend the lookup — it **amplified the SATURDAY mode eightfold** and doubled-and-a-half the rate
> at which the model gives up without writing. The seven-day bound is load-bearing, for a reason
> the comment defending it does not give.
>
> **§3 is a retraction of my own reasoning**, written before the arm ran and wrong.
>
> **§4 is why the fix proposed in #17's own body could never have been screened as written** — it
> is inert on one arm and points at a list that cannot contain the answer on the other. Anyone
> picking this up next needs §4 before they need anything else.
>
> Continuous with [the previous handoff][prev]; that one measured #17, this one failed to fix it.

[prev]: ./2026-09-11-the-rate-and-the-experiment-that-could-not-work.md
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`a80ffad`** — pull request #21, CI green on all three jobs |
| `dev` | one merge commit behind `main` (`a80ffad` carries no content — **T21 applies to the next PR**) |
| Working tree | one file: the probe harness gained a counter, §5.2 |
| Backend | **804 tests** — the build was run after the revert and is green; see §6 |
| `src/main` | **unchanged.** Both candidates reverted after measurement |
| Issues open | **#15 and #17.** #17 now additionally carries two rejected candidates |
| Live conversations spent | **152** (2 smoke + three arms of 50) |

---

## 2. What was measured

One instrument throughout: `RescheduleDateFidelityRateTest`, `PROBE_DATE_STYLE=WEEKDAY`, n=50 per
arm. The weekday arm was chosen because the previous handoff showed it sits at ~45%, far from any
ceiling and separable at n=50, where #15's ~5% needs a perfect candidate to show anything.

**The threshold was pre-registered before any model call**, and amended once — before either arm —
when an n=2 smoke run showed the endpoint I had chosen was a lossy proxy (§5.1). The
pre-registration, the amendment and both rejections are in the scratchpad file quoted in §8.

### 2.1 The control replicates the previous session

Re-measured today rather than reused: the previous arm ran with the business-timezone "today" at
2026-09-10, putting the target 11 days out; today it is 10, and every date in the prompt differs.

| | today | previous session |
|---|---|---|
| strict landing | **14/30 = 46.7%** | 13/29 = 44.8% |
| `date_from` exactly on target | 11/50 = 22% | 10/50 = 20% |
| never wrote | 8/50 = 16% | 10/50 = 20% |

Close enough that day-to-day drift is not material here, which is what makes the comparisons below
worth anything. **Decision threshold against 14/30: roughly 70% of scoreable writes**, p < 0.05.

### 2.2 Candidate 1 — an instruction at the decode point. Rejected

Gave `date_from`'s schema description a selection rule where it had none, and lifted the seven-day
clamp inside the tool's own contract rather than by weakening rule 11.

| | control | candidate 1 |
|---|---|---|
| **strict landing** | 14/30 = 46.7% | **16/36 = 44.4%** — Fisher **p = 0.666**, FAIL |
| `date_from` exact | 11/50 = 22% | 13/50 = 26% |
| window covered the target | 17/50 = 34% | **17/50 = 34%** — unmoved |

**Not re-scored on another endpoint to rescue it**, per the pre-registered rule.

**The informative part is post-hoc and is labelled as such.** Wrong landings moved from before the
target to beyond it — 5 of 16 became 13 of 20, p = 0.046. The `09-18` landings, the seven-day
list's last row, halved from 10 to 5. **The clamp is real and the clause lifted it. Lifting it did
not help: the model stopped clamping and started overshooting.** Same wrongness, redistributed.

### 2.3 Candidate 2 — the dated list extended to fourteen days. Rejected, and harmful

The reasoning was that this repo has twice measured data shape beating instruction force, and that
a lookup rule pointed at a list which stops at day 7 has no answer for a target 10 days out. Shape,
field order and rule 11's mechanism were left alone; only the list's reach changed.

| | control | candidate 2 | |
|---|---|---|---|
| **strict landing** | 14/30 = 46.7% | **7/25 = 28.0%** | Fisher p = 0.956, FAIL |
| `date_from` exact | 11/50 = 22% | **2/50 = 4%** | p = 0.0073 |
| **never wrote** | 8/50 = 16% | **21/50 = 42%** | **p = 0.0038** |

**And the mechanism is this repository's oldest failure mode.** The 14-day list runs 09-12 to
09-25. **2026-09-26 is a Saturday, one day off the end of it.**

| mentions of `2026-09-26` in the run log | |
|---|---|
| control | **12** |
| candidate 2 | **100** |

Nine of the twenty-one no-write trials searched `2026-09-26` twice, were told CLOSED by a tool that
was entirely right, and gave up. **That is #13's original symptom verbatim**, reintroduced at eight
times the rate by making the list longer. The remaining wrong landings cluster on `09-22` — Tuesday,
the row *after* the target — and `09-25`, the list's new last row.

**Lengthening the list does not extend the lookup. It adds rows to get wrong and pushes the model
off the end into the Saturday.** The pre-registration predicted a wrong-row regression as a risk to
check afterwards with #15's instrument; it did not need checking afterwards, because it turned up
inside this arm.

---

## 3. What I got wrong

**"The documented seven-day rationale is contradicted by measurement and worth overturning."**
Written into the pre-registration before the arm ran, and wrong.

The comment above the list justifies seven days as *"the range a spoken weekday can mean … anything
further out, a customer says as a date."* I argued #17's weekday arm refutes the premise, since
customers plainly do say "the Monday after next" and it is ten days out.

**The premise is still questionable. The conclusion was wrong anyway.** The seven-day bound is
load-bearing — not for the reason the comment gives, but because the list's *length* is itself a
cost: every extra row is another row the model can take the wrong one of, and the row past the end
is a Saturday. A rationale can be poorly stated and the decision it defends still correct, and
finding a hole in the reasoning is not the same as having evidence for the alternative. **I had
none until I ran the arm, and the arm said the opposite.**

---

## 4. The candidate in #17's body cannot be screened as written

**Read this before attempting #17 again.** #17's body carries a diff described as "the obvious
candidate, already written and reverted unmeasured". It could not have been measured on either arm
of the instrument that exists:

- Its clause is conditioned on *"if the customer named a day rather than a date"*. On the **ISO
  arm** the customer names a date, so the sentence is **inert by its own terms**.
- It says *"this is the date on that day's line in the seven-day list above — copy it from there."*
  `BookingScenario.monday` is `today.plusDays(7).with(nextOrSame(MONDAY))`, so the target is **8 to
  14 days out** — today, ten. The seven-day list covers `today+1 … today+7`. **The target is never
  on it.** On the weekday arm the instruction is followable and *wrong*: copying from the list
  produces a date the customer did not name.

It was never tried, so nothing is retracted — but it should not be picked up as though it were a
screened hypothesis awaiting a sample. It needs rewriting before it needs measuring.

---

## 5. Two new traps

### 5.1 `date_from` exactness is a lossy proxy for the mechanism

The n=2 smoke run turned up a trial that searched `[2026-09-18, 2026-09-21]` — `date_from` **not**
on the target, the window containing it — and landed correctly. §2.1 of the previous handoff
reported a perfect 42/42-vs-0/8 correlation on `date_from` exactness, which is true and is not
challenged; but a fix that works by **widening the window** rather than by moving `date_from` would
score zero on that measure while working. The harness now counts both. In the control they differ
by twelve points: 22% exact against 34% covered.

### 5.2 The probe is unreachable on Mondays

`monday = today.plusDays(7).with(nextOrSame(MONDAY))` is 8–14 days out on six days of the week —
**and exactly 7 days out when the run happens on a Monday.** Every observed failure of #17 has the
model substituting `[today+1, today+7]`, so on a Monday the target sits *inside the very window the
defect substitutes*, the failure mode is unreachable by construction, and the arm returns
near-perfect while proving nothing.

This is §2.3 of the previous handoff — *a fixture that cannot express the failing input measures the
fixture* — one level further in, and worse, because here **the calendar decides**, not the author.
A run on a Monday is not a result. Check the day before believing an arm.

---

## 6. Every open item

### 6.1 Issues

| # | State |
|---|---|
| **[#17]** | **OPEN, unfixed.** Rate and mechanism measured (previous session). **Two candidates now screened and rejected**, one harmful. §4 says why the body's own candidate is not a screened hypothesis |
| **[#15]** | **OPEN, unchanged.** Not worked this session. §2.3 is indirect evidence that its wrong-row mode is sensitive to list length |

### 6.2 Gaps

Carried unchanged: **G1** (no inline tool activity), **G3** (phase-09 DoD box unticked — #17 is
still unfixed, so still no reason to tick it), **G8** ("Any available" never rendered), **G9**
(`pnpm build` still unrun — no frontend file touched again this session), **G10** (the weekday arm
still has one phrasing).

### 6.3 Traps

Carried: T1–T21. New here: **T22** — `date_from` exactness is a lossy proxy, §5.1. **T23** — the
probe is unreachable on Mondays, §5.2. **T24** — *a badly argued decision can still be the right
decision*; §3. Finding the hole in a rationale is not evidence for the alternative.

### 6.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged and was used heavily again**: 152 live conversations this
session, on top of the previous session's ~110. Still the key that was pasted into a chat
transcript, still not rotated, still a known accepted risk.

### 6.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; the allow-list sweep
only sees paths the fixture produces; `sessionStorage` holds a customer's name and number; nothing
deletes an `ai_message`; rate-limit buckets are in memory; `make seed` is a placeholder; no frontend
test runner; phases 10 and 11 unstarted.

---

## 7. Next steps, in order

### P0

1. **Decide whether #17 is a prompt problem at all.** Two shaped candidates failed — one
   instruction at the decode point, one data-shape change with precedent — and the second made
   things measurably worse. That is weak evidence for a third prompt variant and reasonable
   evidence for a **structural** fix. The options are a principal's call, not an agent's:
   - have `find_available_slots` **echo the range it actually searched** into its result, so the
     model's own context carries the discrepancy;
   - have `reschedule_appointment` **refuse a date no tool returned for the date the customer
     named** — the hallucination control's shape, applied to dates;
   - accept the rate and **surface the date for confirmation** in the reply before writing.

### P1

2. **Rewrite #17's body candidate or delete it** (§4), so the next reader does not spend an arm on
   it.
3. **Return `service_id` from `lookup_appointment`** — still unfiled, still one field, still a
   wasted round trip on 100% of reschedule conversations, and still immediately before the step
   that goes wrong.
4. **Decide G3.** Unchanged.
5. **Run `pnpm build`** (G9).

### P2

6. More phrasings (G10) · **phase 10** · `ai_message` retention.

---

## 8. Commands

```bash
# From backend/. ~8-10 min for fifty conversations. PROBE_DATE_STYLE is ISO or WEEKDAY.
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../.env | cut -d= -f2-)
PROBE_CONVERSATIONS=50 PROBE_DATE_STYLE=WEEKDAY JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew test -PincludeTags=probe --tests '*RescheduleDateFidelityRateTest' --rerun
```

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for x in glob.glob('build/test-results/test/*RescheduleDateFidelityRateTest*.xml'):
    print(ET.parse(x).getroot().find('system-out').text)"
```

**Copy the XML out before the next `--rerun` eats it** (T5). Both arms and the pre-registration
from this session were kept that way; the numbers in §2 are read from those files, not from memory.

**The stats helper was written fresh and validated before use** (T18): it reproduces all four of
this project's recorded Clopper–Pearson intervals and both recorded Fisher values exactly, in both
directions. It is *not* committed — it has been rewritten from scratch three sessions running, and
that is now worth fixing.

---

## 9. Confidence

**High — both rejections.** Same-day control, same harness, pre-registered endpoint and threshold,
and the control replicated the previous session to within two points.

**High — that candidate 2 is harmful.** Three independent measures moved against it, two at
p < 0.01, and the mechanism is visible in the log rather than inferred from totals: 100 mentions of
the Saturday against the control's 12.

**High — §4 and §5.2.** Both are arithmetic on the fixture's own dates, not measurements.

**Moderate — that the clamp is real** (§2.2). The landing shift is p = 0.046 and **post-hoc**. It is
the best available explanation of candidate 1's null result, not an established finding.

**None — that any prompt-level fix for #17 exists.** Two have now failed. That is not proof one
cannot work, and §7's P0 is a decision about where to look next, not a conclusion that the search
is over.
