# The Receptionist probe

Four instruments measure the same model, and using the wrong one has cost this project two
sessions. They are not interchangeable.

| | What it answers | Cost | What it cannot do |
|---|---|---|---|
| `LiveReceptionistTest` (level 3, tag `llm`) | Is this case broken at all? | ~1 min, twelve cases, one run each | See a rate. At a 4% failure it is green twenty-four runs out of twenty-five |
| `probe.py` (this directory) | Which candidate prompt is better? | ~1 s per trial, pennies for hundreds | State a rate. It exaggerates at both ends, and below about one in ten it saturates and cannot screen at all |
| `WeekdayResolutionRateTest` (tag `probe`) | How often is a spoken weekday resolved right? | ~4 min for fifty conversations, a few cents | Screen many candidates. Too slow to iterate on |
| `RescheduleDateFidelityRateTest` (tag `probe`) | How often does an authorised reschedule land on the date that was named? | ~13 min for fifty conversations, a few cents | Anything single-turn. This defect needs three turns, which is the second reason the probe cannot screen it |

**A single-turn instrument cannot see a multi-turn defect.** Saturation is not the only limit on
`probe.py`: it replays one user turn, so a failure that only appears after an authorising lookup and
an availability search is invisible to it *by construction*, at any failure rate. That is why issue
#17 got its own rate test rather than a probe batch.

The workflow is: the corpus says something is wrong, the probe finds a candidate fix, the rate test
confirms it, and the number that goes in a commit message is the rate test's.

**That workflow assumes the probe can see the failure at all.** For a mode rarer than roughly one in
ten it cannot, and the middle step has to be dropped — see below.

## Why not just trust the probe

Measured against fifty real conversations on the same two prompts:

| | probe | rate test |
|---|---|---|
| `- MONDAY 2026-09-14` | 29/40 (73%) | 42/50 (84%) |
| `- 2026-09-14 is a MONDAY` | 119/120 (99%) | 48/50 (96%) |

The probe got the direction and the rough size right, which is exactly what a screen is for. It also
once returned 80 of 80 for a prompt a later batch scored 39 of 40 — so a single probe batch is not
even a reliable probe result, let alone a live one. Run at least two.

### Below about one in ten it saturates, and stops being a screen

Exaggeration is the mild failure. Run against the *current* prompt — the one
`WeekdayResolutionRateTest` scores at 142 of 150 live — the probe reported, at thirty trials an
utterance:

| Utterance | Expected | Probe |
|---|---|---|
| "What have you got free next Monday?" | MONDAY | 30 / 30 |
| "Do you have anything on Wednesday?" | WEDNESDAY | 30 / 30 |
| "Anything free on Thursday?" | THURSDAY | 30 / 30 |
| "What about Sunday?" | SUNDAY | 29 / 29 that searched — one asked a question first |

**119 of 119, for a prompt that fails one live conversation in twenty.** This is not the
exaggeration above; it is worse. The instrument has no resolution left, so no candidate can beat
the baseline and the screen cannot rank anything — it is unusable rather than merely optimistic.

So: above roughly a one-in-ten failure rate the probe earns its keep. Below it, **do not screen —
measure**, and budget the live runs from the start. Note that the rate test needs its own budget
there too: fifty conversations cannot separate 96% from 100% (Fisher, one-sided, p = 0.25), and
`PROBE_CONVERSATIONS` exists to raise it.

## Running it

The fixtures are written by a test and are gitignored, because they are outputs:

```bash
cd backend
./gradlew test -PincludeTags=probe --tests '*ProbeFixtureDumpTest'
```

Then, from this directory:

```bash
export OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' ../../../.env | cut -d= -f2-)
./probe.py "What have you got free next Monday?" MONDAY 20
```

To try a candidate wording, copy `fixtures/prompt.txt`, edit the copy, and pass `--prompt`. To
confirm the winner:

```bash
cd backend
./gradlew test -PincludeTags=probe --tests '*WeekdayResolutionRateTest'
```

Compare two rates with Fisher's exact test. Over fifty trials, 42 against 48 is p = 0.046; anything
much closer is not a result yet.

## What a measured rate looks like, and what it costs to skip one

Issue #17 was filed on **two failures in seven trials** — deliberately labelled "not a
rate" — and measured a day later at **fifty**:

| | failure rate | exact 95% CI |
|---|---|---|
| 2 of 7 (the anecdote) | 29% | [3.7%, 71.0%] |
| 5 of 47 writes (measured) | **10.6%** | **[3.5%, 23.1%]** |

The anecdote was roughly a **threefold overestimate**, and it was used to argue that the defect was
frequent enough for `probe.py` to screen. The measurement put it *on* the saturation floor instead,
so that argument was retracted. Both numbers are consistent with each other — the seven-trial
interval is sixty-seven points wide and excludes almost nothing — which is the point: it was never
evidence about the rate, only about the mode existing.

**Compute an interval, not just a point.** Clopper–Pearson, and validate the helper against a
published answer first: 2 of 20 is [0.0123, 0.3170] in any textbook.

## Check that your fixture can even reach the defect

The first design of `RescheduleDateFidelityRateTest`'s weekday arm had the customer say "Monday".
It would have returned a near-perfect score and meant nothing.

Every observed failure of issue #17 has the model searching `[tomorrow, tomorrow+6]` instead
of the date it was given. A **bare weekday can only name a date within seven days** — so its
target is always *inside* the very window the model wrongly substitutes, and the failure mode is
unreachable by construction. The arm was re-phrased to "the Monday after next", which names a date
ten days out, and only then could it measure anything.

**Ask what makes the defect visible before you ask how often it happens.** A fixture that cannot
express the failing input measures the fixture.

## Phrasing is a variable, and a large one

Same harness, same target date, same appointment, fifty conversations each — only the wording
of the date changed:

| How the customer named the date | Landed on it |
|---|---|
| `2026-09-21` | 42/47 = **89.4%** |
| "the Monday after next" | 13/29 = **44.8%** (60.0% allowing the other reading of the phrase) |

Fisher one-sided p = 3.9 × 10⁻⁵. `date_from` was aimed exactly at the target in 84% of the first
arm's trials against 20% of the second's.

So a rate measured with an explicit ISO date is a **best case**, and customers do not talk that way.
State the phrasing beside the number, the way the weekday test states its weekday.

## The rule that produced all of this

**Never hand-write the tool JSON.** A simplified two-tool probe scored 5 of 5 on the very prompt the
faithful eight-tool one scored 2 of 5 on. The model fills six arguments at once under a constrained
decoder, and that is where it goes wrong — a probe without the real tools and `strict: true` is
measuring a different system and will tell you a broken prompt is fine.
