# Session handoff — 2026-09-14 — the charge was never the defect

> **A synthesis, not a replacement.** Three handoffs cover the sittings themselves and each stands on
> its own; this is the argument they turn out to be. Read this first and the three for the evidence.
>
> **Six commits, and the backlog is gone.** Two sittings were sitting uncommitted when this one
> started — the previous two handoffs both open with `dev` unchanged and a §1 that is a backlog,
> and both asked for that to be cleared. It is. **Those two §1 lines are now false**, deliberately
> left standing as the point-in-time records they are; §1 below is the true one.
>
> **Three review candidates, and the charge was wrong every time — in the same direction.** The
> review named the right file three times running and the wrong defect three times running, and
> each real defect sat one level more abstract than the charge:
>
> | | the charge | what was there |
> |---|---|---|
> | **3** | the mapped surface is derived eight times, so the copies may drift | they never drifted. **They agreed — about what none of them could see** |
> | **4** | `MODEL` and `MAX_ROUNDS` are hand-synced and already divergent, 5 against 6 | not a stale copy. **The two numbers were never in the same unit**; 48 calls where a turn makes 5 |
> | **5** | a second Booking Horizon, and an invented `EmptyReason` | not a Booking Horizon, and not invented wrongly. **The number was right and its stated reason was false**; the invented value was correct |
>
> **That progression is the finding.** Duplication a checker could catch, then incomparability no
> checker can, then a false justification over a correct value — which nothing mechanical can reach
> at all, because nothing is broken. §2.
>
> **Every fix took the same two moves, and the second is the one that counts**: derive rather than
> alarm, then *prove the control by breaking the thing it guards*. Seven plants and reverts across
> the three; §4. **Two of them exonerated code the charge had condemned**, which is the strongest
> evidence available that the sittings were not shaped to confirm the review.
>
> **Zero measurements of the model, for a third sitting**, on a product whose centre is a model. §5.

| The three | Commit |
|---|---|
| [the copies that never disagreed](./2026-09-14-the-copies-that-never-disagreed.md) — candidate 3 | `f1db0bf` |
| [the instrument that outlived the turn](./2026-09-14-the-instrument-that-outlived-the-turn.md) — candidate 4 | `8b9d2a6` |
| [the reason that was not the reason](./2026-09-14-the-reason-that-was-not-the-reason.md) — candidate 5 | `3efb15f` |

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`2a6cad0`.** Six commits, working tree clean |
| **Pushed** | **No. All six are local, and CI has seen none of them** — including a new job |
| Backend | **1086 tests, 0 failed**, forced full run (`--rerun-tasks`) |
| Frontend | **Untouched across all three sittings** |
| Production code | One file, `FindAvailableSlotsTool` — comments, one constant, **no behaviour change** |
| E2E | **Not run. A sixth sitting** |
| Review | **5 of 7 candidates done.** Two remain, neither Strong |
| Gaps | **G48** and **G49** both open, both wanting decisions rather than patches |

**The commits are work-then-handoff pairs**, matching this repository's history, and split by path so
each sitting's code is reviewable apart from its narrative.

---

## 2. The thread: what each candidate turned out to be about

### Candidate 3 — copies that agree

Six byte-identical `patternsOf` methods, nine copies of a package filter. The review expected drift.
There was none, and there could not have been: all six paired a pattern with a verb, so a mapping
declaring no verb contributed nothing to any of them. **A blind spot shared by six controls is
invisible from inside every one of them**, because a blind spot is exactly where a control reports
nothing. `ResourceSurface`'s javadoc had argued the opposite in advance — that a copied *filter* is
safer than a copied *generator* — and the argument was careful and the conclusion was wrong.

### Candidate 4 — numbers that cannot be compared

`probe.py` held `MAX_ROUNDS = 6` beside a comment naming `MAX_TOOL_CALLS_PER_TURN = 5` and asking a
reader to check that six is "no smaller" than five. **The application counts tool calls; the probe
counted rounds.** There is no arrangement of the two systems in which that inequality means
anything, so the sentence was never capable of being true or false — which is precisely why nobody
reading it ever looked. Measured against a model asking for eight tools at once: **48 calls and 6
model calls, where a turn makes 5 and 1.**

A parity checker over those two numbers would have gone green. That is the step up from candidate 3:
the copies there could in principle have disagreed, and here they could not, because they were not
the same kind of thing.

### Candidate 5 — a right number under a false reason

`MAX_DAYS = 14` is correct — a context budget, for the reason the class javadoc gives four lines
above. The sentence on the constant said it matched the engine's ceiling: wrong in the number (31),
wrong in the kind (a cost control, not a context one), wrong in the verb (it refuses; this narrows).
**Nothing was broken, so nothing could fail**, and the false sentence had already been copied into a
test's javadoc, where a reader meets it twice and takes the repetition for confirmation.

That is the step up again: there is no mechanical check for a correct value wearing a false
justification. Only reading it against the thing it names.

### 2.1 What the three have in common, which is not duplication

All three were sold as duplication and none was fixed by de-duplicating alone. **What actually
failed in each was a written claim about a relationship** — "these copies are harmless", "this only
has to be no smaller", "matches the engine's own ceiling". Each claim was load-bearing, each was
checkable by a person and by nothing else, and each had stopped the last reader from looking.

---

## 3. And in every one, the assertion named after the property was the blind one

Measured three times, independently:

- **Candidate 3**: of `MappedSurfaceTest`'s twelve, the two written *about* methodless mappings both
  stay green when the blindness returns. The assertions that caught it pinned a derived set by
  equality against a written one. (**T175**)
- **Candidate 4**: of the three checks on the separating case, the one that stays green under the
  broken loop is `turn ended at the ceiling` — both loops end at a ceiling, just not the same one.
  The two that caught it count what happened *before* the stop. (**T178**)
- **Candidate 5**: the third new test, the one that forbids moving `OUTSIDE_REQUESTED_TIMES` onto
  `EmptyReason`, has no plant — because what it forbids is a *tidying*, not a code change.

**A check named after a bound tends to read the bound's shadow.** Three sittings found it without
looking for it; it is the most reliable single result of the week.

---

## 4. Every plant and revert, and the two that mattered

| | what was broken | what caught it |
|---|---|---|
| C3 | `MappedSurface` reverted to the six copies' shape | **3 of 12** red; nine green, including both assertions about the blind spot |
| C4 | `trial` reverted to the round budget | **4 of 13** red — 48 searches against 5, 6 model calls against 1 |
| C5 | the empty-reason fallback answers `EmptyReason.CLOSED` | exactly one test, `filter removes every slot` |
| C5 | every empty answer blamed on the time filter | exactly one test, `engine reason not renamed` |

**The two that mattered are candidate 5's**, because they are the pair: each plant is caught by one
test and by no other, in either direction. A single test asserting "the tool says
`OUTSIDE_REQUESTED_TIMES` when everything is filtered" would have passed both plants in one
direction and been useless in the other.

**And two findings exonerated what the review had condemned** — candidate 5's `MAX_DAYS` value and
its `OUTSIDE_REQUESTED_TIMES` value were both correct, and are both still there. A walk that
confirmed every charge would be a walk worth distrusting.

---

## 5. What this session did not do

- **No model was measured. At all.** No probe batch, no live run, no rate test, across three
  sittings — on a product whose centre is a model. Candidate 4 *repaired the instrument* that does
  the measuring and then did not use it, deliberately (its fix needs a key and a batch to show a
  live effect, and no recorded rate was re-explained without one). **This is the largest thing the
  three sittings have in common and the easiest to not notice**, because all three were green.
- **Nothing was pushed.** Six commits, and CI has seen none of them. One of them adds a CI job that
  has therefore never run on a runner.
- **`make e2e` was not run**, a sixth sitting, same named risk: the filter button outside the
  `overflow-x-auto` wrapper on `/conversations` at 360 px.
- **G48** (reschedule takes no advisory lock and has no retry) and **G49** (the tool payload has two
  authors and no referee; `OpenAiChatModel` has no direct test at all) are both open and both are
  decisions, not patches.

---

## 6. What I got wrong, collected

Three, all caught before they were published, all by running something rather than by thinking
harder:

1. **Candidate 4**: predicted the round budget would produce 8 searches on the batched case. It
   produces **48** — the overrun compounds across rounds. Caught by running the counterfactual.
2. **Candidate 4**: wrote that nothing in this repository guarded its instruments.
   `ProbeInstrumentationTest` is exactly that, carries **G17**, and is deliberately untagged so CI
   runs it. The true lesson is narrower and better: the rule existed and **could not reach Python**.
3. **Candidate 5**: spelled `31` into prose twice while fixing a javadoc whose defect was a retyped
   number. Both are `{@value}` now.

**The pattern in all three is the same as the sittings' own subject** — a claim written confidently
beside the thing that disproves it. §7's T181 is the narrow form.

---

## 7. The lessons, T176–T181

Candidate 3's **T173–T175** are in [its own handoff](./2026-09-14-the-copies-that-never-disagreed.md).

| | |
|---|---|
| **T176** | Two ceilings in different units cannot be reconciled by a comment, and the comment that tries reads exactly like one that checks. Ask what each side *counts*, not whether the value is still right |
| **T177** | A rule applied to every instrument in one language is not a rule the project has. `ProbeInstrumentationTest` is G17's answer in Java; nothing could reach `probe.py` |
| **T178** | T175 from the other side: the check named after the bound is the one reading the bound's shadow |
| **T179** | A constant can be right while the sentence above it is false, and nothing will report it. Check a stated reason against the thing it names, not against the behaviour |
| **T180** | Settle which direction a coupling runs before naming it — the fix a misnamed coupling suggests is usually the one that entrenches it |
| **T181** | Javadoc has `{@value}`, and the reflex to retype a number into a sentence survives knowing better |

---

## 8. Next steps, in order

1. **Push.** Six commits, one new CI job, nothing has run on a runner. This is the only step that
   can fail in a way nothing local has already tested.
2. **`make e2e`**, or carry the risk explicitly into a seventh sitting.
3. **Use the instrument that was repaired.** A probe batch — any batch — would end the run of three
   sittings with no measurement. Candidate 4 left `fixtures/loop.json` needing a re-dump first, and
   the probe refuses to start without it, which is the intended failure.
4. **Take candidate 6.** `ErrorCode` declared twice in two languages, drift already measured, and
   it crosses the same language boundary candidate 4 found a rule could not.
5. **G48 and G49.** Both need a decision.

**Before trusting a full run, stop the dev servers.** A forced full run went red on three
repetitions of `ConcurrentPollerTest` at **load average 315** with `next dev`, two `pnpm dev` and an
IDE backend running; it passes in isolation, and a second forced run was wholly green. This
repository already had that precedent written down.

---

## 9. Confidence

**High** that the three defects were real and are fixed: each has a counterfactual, and candidate
5's has two that discriminate.

**High** that no behaviour changed. One production file, no logic; the model's tool description was
verified byte-identical by regenerating the fixture rather than by reading the concatenation.

**Low** about anything concerning the live system. Nothing here was measured against a model, an
E2E, or a runner. Three green sittings in a row is what this looks like either way, and §5 is the
honest account of it.
