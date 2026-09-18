# Session handoff — 2026-09-14 — the reason that was not the reason

> **Candidate 5 from [the review][prev1], the third sitting in a row on a number written twice.**
> The charge was that scheduling rules leak into `FindAvailableSlotsTool`: "a second Booking Horizon
> (`MAX_DAYS = 14` under a comment claiming it matches the engine's, which is 31), and an invented
> `OUTSIDE_REQUESTED_TIMES` the `EmptyReason` enum does not contain".
>
> **Both halves were wrong about what they had found, and both pointed at something real.**
>
> `MAX_DAYS = 14` is not a Booking Horizon. The Booking Horizon is `minLeadTimeMinutes` and
> `maxAdvanceDays`, business configuration, a third concept entirely. **The number is right and its
> stated reason was false** — *"matches the engine's own ceiling, so a wider ask is refused here
> rather than deeper down"* is wrong three ways over: the engine's ceiling is
> `AvailabilityService.MAX_RANGE_DAYS = 31`, it is a *cost* control where this is a *context*
> budget, and it **refuses** with a 400 where this **narrows** and says so. The class javadoc four
> lines above says the true thing. **The false sentence had already propagated** into
> `ToolExecutionTest`'s javadoc, where it read as confirmation.
>
> `OUTSIDE_REQUESTED_TIMES` is a fifth value in a four-value vocabulary, declared nowhere, tested
> nowhere, documented nowhere — **and correct.** `AvailabilityResult.of` is the only constructor and
> it sets `emptyReason` to null exactly when a Slot was found, so a null reason beside an empty list
> can only be the time filter. The defect was never the value; it was that nothing said what it
> rests on and nothing could see it move.
>
> **And the leak runs the other way.** Nothing scheduling leaked *in* — the range cap, the slot cap
> and the time filter are all about the model, and the class javadoc says so. What crosses is the
> tool writing a word into the *domain's* vocabulary.
>
> **Two planted defects, each caught by exactly one of the two new behavioural tests, neither
> overlapping.** §3. **No behaviour changed**: the model's tool description is byte-identical,
> verified by regenerating the fixture rather than by reading the string.

[prev1]: ./2026-09-13-the-handler-the-receptionist-never-reached.md
[prev2]: ./2026-09-14-the-instrument-that-outlived-the-turn.md

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`3db23f4`.** The previous two sittings are committed; this one is in the working tree |
| Backend | **1086 tests, 0 failed**, on a forced full run (`--rerun-tasks`) — see §6 for the run before it |
| Frontend | Untouched — no frontend file has changed in three sittings |
| Production code | **Changed, for the first time in three sittings** — `FindAvailableSlotsTool`, comments and one constant. **No behaviour changed** |
| New | Three tests in `ToolExecutionTest`; one declared constant |
| E2E | **Still not run.** A sixth sitting |
| Review | **5 of 7 candidates done.** Two remain, in §8 |

---

## 2. The three numbers, and which of them was a copy

| | what it bounds | who owns it | what happens at the edge |
|---|---|---|---|
| `minLeadTimeMinutes` / `maxAdvanceDays` | **the Booking Horizon** — how soon and how far ahead a Slot may be | the Business, as configuration | the engine answers `OUTSIDE_HORIZON` |
| `AvailabilityService.MAX_RANGE_DAYS = 31` | how many days one query may span | the engine, as a **cost** control (Employees × days × grid, docs/01-prd.md FR-5) | **refuses**, 400 `VALIDATION_FAILED` |
| `FindAvailableSlotsTool.MAX_DAYS = 14` | how much of an answer fits in a turn | the tool, as a **context** budget | **narrows**, and reports `range_narrowed_to_days` |

Three bounds, three owners, three behaviours at the edge. The review collapsed the first and third;
the javadoc collapsed the second and third. **`MAX_DAYS` is the stricter of the two range bounds at
every value it has ever had**, so the engine never sees a range it would reject from this path —
which is a consequence of the number, not a reason for it, and is now written down as such.

### 2.1 What was actually written twice

**`14`, in the constant and in the model-facing tool description** — `"Covers at most 14 days per
call."`, a string that reaches the model through `tools.json`. Changing the constant would have left
the model told the old number. It is `+ MAX_DAYS +` now, and the regenerated fixture is
byte-identical, which is how that was checked rather than by reading the concatenation.

**`31`, nowhere — and I nearly wrote it twice.** The first draft of the corrected javadoc spelled it
out in prose, in two places, which is the defect being fixed. Both are `{@value
AvailabilityService#MAX_RANGE_DAYS}` now, as is the `14` in the class javadoc. Verified by running
`./gradlew javadoc` and reading the rendered page: *"caps the range at 14 days"*, *"its own ceiling
is 31"*.

---

## 3. The counterfactual

`OUTSIDE_REQUESTED_TIMES` had never been executed by a test. Two plants, run against the three new
tests:

| plant | `filter removes every slot` | `engine reason not renamed` | `cannot collide` |
|---|---|---|---|
| the fallback answers `EmptyReason.CLOSED` instead of the tool's word | **FAILED** | passed | passed |
| every empty answer is blamed on the time filter | passed | **FAILED** | passed |

**Each plant is caught by exactly one test and no plant is caught by two.** The pair is load-bearing
in both directions: one holds that the tool does not borrow the engine's vocabulary to explain its
own filter, the other that it does not lend its own word to the engine's silence.

The third test is the one with no plant, because what it forbids is not a code change but a
*tidying*: moving `OUTSIDE_REQUESTED_TIMES` onto `EmptyReason` so the field has one type behind it.
That would make the enum's own contract false — the engine produces every constant on it, and never
this one — and would make the two rows above indistinguishable to anything reading `empty_reason`.

---

## 4. What was checked and found sound

Written down because a sitting that reports only what it changed reads as though everything it
touched was broken.

- **`ResolveDateTool.MAX_WEEKS_AHEAD = 8`** and its *"No horizon check here"* javadoc. The claim
  that the horizon *"belongs to `find_available_slots`, which already enforces it"* is true: the
  tool reaches the engine, the engine decides, and `empty_reason` carries the verdict. The word
  "refuses" is loose — it answers empty-with-a-reason — but the substance, that one place decides,
  holds. **Not changed.**
- **`MAX_SLOTS = 30`** and its javadoc. Accurate, and about the model, where it belongs.
- **`AvailabilityResult.of`'s invariant.** Constructor-enforced and documented on the record itself:
  *"the two fields cannot disagree, because `of` is the only way to build this."* It is what makes
  the tool's fallback correct, and it is now named at the line that depends on it.

---

## 5. What is not verified, stated plainly

- **No measurement of the model.** Nothing here changed a byte of what the model reads, which is
  exactly why none was needed — and also why this sitting proves nothing about whether 14 is the
  right budget. That question needs a probe batch and has never been asked.
- **The field javadoc's `{@value}` tags are unrendered.** `MAX_DAYS` is package-private, so the
  javadoc task's default visibility skips it. Both reference forms are verified in the rendered
  *class* javadoc, and the field's copies use those same two forms — but the field's own page does
  not exist to check.
- **The E2E sweep is still not run**, a sixth sitting, same named risk.
- **G48 and G49** are both untouched and both want decisions — [two][prev1] [handoffs][prev2].

---

## 6. The full run failed once, and it was not this

The first forced full run came back **3 failing: `ConcurrentPollerTest`, all three repetitions**, on
`ResourceAccessException: HTTP/1.1 header parser received no bytes` against `/api/auth/register` — a
transport failure in the notifications poller, nowhere near anything this sitting touched.

It passes in isolation, three of three. A second forced full run came back **1086, 0 failed**. The
machine was at **load average 315** with `next dev`, two `pnpm dev` and an IDE-launched backend
running, and **this repository has the precedent written down**:
[2026-09-09][flake] records a concurrency test flaking under exactly that and passing with the dev
servers stopped.

**Recorded rather than dismissed.** Two forced full runs, one red on three repetitions of one
unrelated concurrency test and one wholly green, is consistent with load and is not evidence of
anything else — but it is also not a clean bill of health for `ConcurrentPollerTest`, and the next
person to see it should know it has now been seen twice.

[flake]: ./2026-09-09-copy-states-and-the-stranded-reminder.md

---

## 7. Lessons

**T179 — a constant can be right while the sentence above it is false, and nothing will report it.**
`MAX_DAYS = 14` was correct: a context budget, chosen for the reason the class javadoc gives. The
sentence on the constant explained it as matching the engine's ceiling, which it never did. No test
could fail, because nothing was broken — and the false sentence was then **copied into a test's
javadoc**, where a reader meets it a second time and takes the repetition for confirmation. **Check
a stated reason against the thing it names, not against the behaviour: behaviour can be right for a
reason nobody wrote.**

**T180 — settle which direction a coupling runs before naming it.** "Scheduling rules leak into the
tool" and "the tool writes into the scheduling vocabulary" describe the same file and want opposite
fixes: the first moves a rule out, the second declares a boundary and pins it. Read the wrong way,
the obvious repair here was to move `MAX_DAYS` into the scheduling layer — which would have put a
*context* budget next to a *cost* ceiling and made the confusion structural. **The fix that a
misnamed coupling suggests is usually the one that entrenches it.**

**T181 — javadoc has `{@value}` and this repository was retyping constants into prose.** `{@value
#FIELD}` and `{@value Other#FIELD}` both work, both were verified in the rendered output, and both
turn a prose copy into something that cannot go stale. Three of this sitting's four number-copies
were in comments, and **two of them were mine, written while fixing the first**. The reflex to
retype a number into a sentence survives knowing better; the tag is the only thing that stops it.

---

## 8. The two candidates left

| | Candidate | Strength |
|---|---|---|
| 6 | `ErrorCode` is declared twice in two languages and **has already drifted** — `UNSUPPORTED_MEDIA_TYPE` is emitted by `JsonOnlyWriteFilter:78` and absent from the TS union, and `client.ts:125` casts it in | Worth exploring |
| 7 | Reads have `use-resource`; writes have twelve hand-rolled copies, and the coverage gate keys on the read module's name so it cannot see them | Speculative |

**Candidate 6 is the one to take next**, and it is the only one left with a drift already measured.
It also crosses the language boundary, which is where [the previous sitting][prev2] found the rule
that could not reach: `SLOT_UNAVAILABLE` and `VERSION_CONFLICT` now reach the model too, so the
vocabulary has three consumers and two definitions.

### 8.1 Loose ends, unchanged

All five from [the previous handoff][prev2] §9.2 stand.

---

## 9. If you are picking this up

1. **Commit this.** One commit; the tree holds nothing else.
2. **`make e2e`**, or carry the risk forward explicitly for a seventh sitting.
3. **Take candidate 6.** §8.
4. **Do not move `MAX_DAYS` into the scheduling layer.** §2's table is why, and T180 is the general
   form. It is a context budget; it belongs beside the thing whose context it is budgeting.
5. **Do not add `OUTSIDE_REQUESTED_TIMES` to `EmptyReason`.** §3, and there is a test named for it.
6. **Stop the dev servers before trusting a full run.** §6.
