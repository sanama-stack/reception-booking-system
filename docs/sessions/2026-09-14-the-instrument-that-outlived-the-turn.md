# Session handoff — 2026-09-14 — the instrument that outlived the turn

> **Candidate 4 from [the review][prev1], taken the sitting after [candidate 3][prev2].** `probe.py`
> replays `ConversationService.runTurn` in Python, and the review's charge was that `MODEL` and
> `MAX_ROUNDS` were hand-synced and "already divergent (5 against 6)".
>
> **The premise was wrong and the defect was worse.** The two numbers were not a stale copy of each
> other. They were **never in the same unit**: the application counts tool *calls*, the probe counted
> *rounds*, and the comment reconciling them — *"the application's own limit is
> `ConversationLimits.MAX_TOOL_CALLS_PER_TURN`; this only has to be no smaller"* — compares two
> quantities that cannot be ordered. Six rounds is not "no smaller" than five calls; it is a
> different measurement of a different thing.
>
> **Measured, against one response asking for eight tools at once: 48 tool calls and 6 model calls,
> where a turn makes 5 and 1.** The probe scores the `date_from` of every search it sees, with
> `all()`. Nine and a half times the calls, every one of them past the point the application would
> have stopped and handed off. Neither side sets `parallel_tool_calls`, so a batched response is
> on by default on both.
>
> **The bound is now derived, not retyped.** `ProbeFixtureDumpTest` writes `fixtures/loop.json` out
> of `AiProperties` and `ConversationLimits`; `MODEL`, `MAX_ROUNDS` and `ENDPOINT` are gone from the
> probe. What a fixture cannot carry is the loop's *shape*, so that got a self-test instead —
> `python3 probe.py --self-test`, `make check-probe`, and a non-required CI job.
>
> **The counterfactual is the part to keep.** Reverting the loop to the round budget turns **4 of the
> self-test's 13 red** — and the one check whose label names the ceiling **stays green**, because
> both loops do end at a ceiling, just not the same one. §3.

[prev1]: ./2026-09-13-the-handler-the-receptionist-never-reached.md
[prev2]: ./2026-09-14-the-copies-that-never-disagreed.md

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`1cc0848`, unchanged.** Two sittings are now in the working tree, uncommitted — §10 item 1 |
| Backend | **1083 tests, 0 failed**, on a forced full run (`--rerun-tasks`) |
| Frontend | Untouched — no frontend file changed in either sitting |
| Production code | **Untouched, for a second sitting.** Test sources, an instrument, the Makefile, CI and documents |
| New | `fixtures/loop.json` (an output); `probe.py --self-test`; `make check-probe`; CI job `loop-parity` |
| Changed | `probe.py`, its README, `ProbeFixtureDumpTest`, `Makefile`, `.github/workflows/ci.yml` |
| E2E | **Still not run.** Unchanged and uncleared for a fifth sitting |
| Review | **4 of 7 candidates done.** Three remain, in §9 — and none of them is Strong |

**The two sittings are separable by path.** Candidate 3 touched nine test classes, `ResourceSurface`
and `docs/06-security.md`; this one touched the five files above. Nothing overlaps, so they are two
commits whenever somebody wants them to be.

---

## 2. What the two loops actually did

Counted from `ConversationService.runTurn` and `probe.py.trial` side by side, because "two
implementations" was the review's phrase and the interesting part is *which* parts diverged.

| | `runTurn` | `trial`, before |
|---|---|---|
| budget unit | tool **calls** | **rounds** |
| budget value | `MAX_TOOL_CALLS_PER_TURN = 5` | `MAX_ROUNDS = 6` |
| checked | before each model call **and between the calls of one response** | before each model call only |
| a response with more calls than budget | executes what is left, drops the rest | **executes all of them** |
| model | `AiProperties.getModel()` | a retyped constant |
| endpoint | `AiProperties.getBaseUrl()` + a path | a retyped constant |
| tools, `strict: true` | `OpenAiChatModel.request` | `fixtures/tools.json`, already derived |
| ceiling outcome | `TOOL_CEILING_FALLBACK`, a sentence | `"(tool ceiling)"`, a marker |

The last row is a deliberate difference and always was. The first four are the defect, and they are
one defect: **a budget in the wrong unit is not a budget that is slightly too large.**

### 2.1 Why "no smaller" could not have been checked

The reconciling comment asks the reader to verify `6 >= 5`. There is no arrangement of the two
systems in which that inequality means anything. A single round can spend the whole of the
application's budget; six rounds can spend forty-eight of it. The comment is not stale — it was
never capable of being true or false, which is why nobody reading it ever noticed.

**`fixtures/tools.json` was derived from the start and that is the contrast worth keeping.** The
tool JSON had a test writing it, a gitignore entry calling it an output, a README rule in capitals
and a measured story about what hand-writing it costs. The model name and the ceiling sat four
lines further up the same file, retyped, with a comment where the derivation should have been.
**The rule was known and applied to the payload, and the bounds were never thought of as payload.**

---

## 3. The counterfactual, which is the part to keep

The self-test is thirteen checks. Reverting `trial` to the round budget — the loop exactly as it
shipped, `for _ in range(MAX_ROUNDS)` with every call in a response executed — and running the same
thirteen:

| | new loop | round budget |
|---|---|---|
| one response asking for eight: searches executed | 5 | **48** |
| one response asking for eight: model calls | 1 | **6** |
| one call at a time: searches executed | 5 | **6** |
| one call at a time: model calls | 5 | **6** |
| one response asking for eight: **turn ended at the ceiling** | `(tool ceiling)` | `(tool ceiling)` — **green** |
| a text answer ends the turn (3 checks) | green | green |
| the fixture's model reaches the wire | green | green |
| `loop.json` refuses what cannot bound a turn (3 checks) | green | green |

**4 of 13 red.** The separating case is the batched response, and it separates by a factor of nine
and a half rather than by one — the overrun compounds, because a round budget keeps buying rounds.

**The check that stays green is the one that names the ceiling.** `turn ended at the ceiling` reads
`(tool ceiling)` under both loops: both of them stop somewhere and report having stopped. It is the
checks counting *what happened before the stop* that can see which ceiling it was.

---

## 4. What the fix deliberately is not

**It is not a second derivation of the loop.** `trial` is still a hand-written replay and will stay
one — it has to run without a database, without persistence and without Spring, which is the whole
reason the instrument costs a second per trial instead of four minutes. What changed is that its
*budget* is now the application's, and everything it does not replay is **written down in its own
header as a list**: the context window, persistence, the forty-message ceiling, the cost cap, the
Offered-Slot check, the hand-off sentence. An absence nobody wrote down is indistinguishable from a
bug, and six of them were previously unwritten.

**It is not a parity checker.** A script comparing `probe.py`'s constants against
`ConversationLimits.java` would have been the obvious move and it would have been the wrong one:
it keeps two copies and adds an alarm. There are no constants left to compare. What could not be
derived — the loop's shape — got a self-test, which is the only instrument that can see a unit
error, because a unit error is invisible to anything that reads both sides as numbers.

**It does not touch production.** `loop.json` is written by a test out of public constants. No
class, method or field changed in `backend/src/main`.

---

## 5. Three delegated calls, recorded

1. **The budget's semantics were changed without asking.** `probe.py`'s first line says it replays
   the Receptionist's conversation loop. Replaying a different bound is a defect against the file's
   own stated contract, not a design choice about it.
2. **The CI job is not in the required-checks set**, matching the `docs` and `parity` jobs and their
   comments. Adding it to the set is the principal's call and is still open. It runs from the root
   with no `working-directory`, because `tools/pipeline-parity/check.py` fails on a fourth directory
   by design — verified green afterwards, and it now reads 7 jobs where it read 6.
3. **`endpoint` is composed in the Java test** as `getBaseUrl() + "/chat/completions"`. The path is
   the one fragment still hand-written on either side; `OpenAiChatModel` reaches it through
   `RestClient`'s base URL, so there is no constant to read. It is written in the file that already
   owes the reason, and said so in a comment, rather than being moved into Python.

---

## 6. What is not verified, stated plainly

- **The overrun has never been observed live.** 48-against-5 is measured against scripted responses,
  not against a model. What is verified from the code is that it is *reachable*: neither
  `OpenAiChatModel.request` nor the probe sets `parallel_tool_calls`, so batched responses are
  enabled on both. **How often the live model batches enough calls to reach the old ceiling is
  unmeasured**, and measuring it costs a key and a batch.
- **Therefore no recorded probe number is retracted here.** The 73%/99% against 84%/96% in the
  README are still attributed to what that section attributes them to. This sitting found a second
  mechanism that pushes the same way and did not measure its contribution. **Do not go back and
  re-explain those numbers with this finding** — that would be the anecdote-as-rate mistake the
  README itself was written about.
- **The tool payload is still duplicated, and now it is the only copy left.**
  `ProbeFixtureDumpTest` rebuilds `OpenAiChatModel.request`'s four fields and `strict: true` by
  hand; its javadoc has admitted this since it was written and nothing checks it. **G49**, §9.
- **`make e2e` is still not run**, now for a fifth sitting, with the same named risk: the filter
  button outside the `overflow-x-auto` wrapper on `/conversations` at 360 px.
- **G48 is untouched** and still wants a decision — [two handoffs back][prev1] §6.

---

## 7. Lessons

**T176 — two ceilings in different units cannot be reconciled by a comment, and the comment that
tries reads exactly like one that checks.** *"This only has to be no smaller"* is the shape of a
verified invariant: it names the other value, states a relation and invites the reader to confirm
it. The reader confirms `6 >= 5` and moves on. Nothing in that sentence says one number counts
rounds and the other counts calls, and **the sentence is what stopped anyone looking**. Ask of any
cross-system bound not "is this value still right" but **"what does each side count, and would the
same event increment both".**

**T177 — this repository already knew instruments need controls, and the rule stopped at the
language boundary.** The lesson is not new here. `ProbeInstrumentationTest` exists for exactly it,
carries its own gap (**G17**) and its own story — an experiment that could not decide its own veto
because the harness read one tool and the money ran out before a re-run — and it is *deliberately
untagged* so that it runs in CI while the harnesses it guards do not. That reasoning is written out
in its javadoc and it is correct.

It covers `ProbeQueries`' SQL. It could never have covered `probe.py`, which is Python: off the
classpath, out of the build, invisible to the mechanism that proved the other instruments. So the
probe chose between system prompts for several phases while a green suite said nothing, and nothing
was wrong with the suite. **A rule applied to every instrument in one language is not a rule the
project has; check whether its enforcement can even reach the other ones.** The self-test and its
CI job are G17's answer, finally given in Python.

**T178 — T175 again, from the other side, and the tell is the label.** Last sitting measured that an
assertion written about a blind spot need not see the blind spot return. Here it recurs
independently: of the three checks on the separating case, the one that stays green under the broken
loop is `turn ended at the ceiling`, the one whose *name* is the property under test. It reads a
value both loops produce. The two that caught it count what happened before the stop. **A check
named after the bound is the one most likely to be reading the bound's shadow rather than the bound.**

---

## 8. Two claims of mine were wrong in progress, and are corrected in place

- A comment in the self-test predicted the round budget would produce **8** searches on the batched
  case. It produces **48** — the overrun compounds across rounds, which I had not thought through.
  The comment now carries the measured pair and says the overrun compounds. The header's earlier
  "six rounds is thirty calls" was replaced by the measured figure for the same reason.
- I had expected `MAX_ROUNDS = 6` to be a stale copy of a 5 that had moved. It never was. §2.1.

Both were caught by running the counterfactual rather than by reasoning about it, which is the only
reason they are corrections and not published numbers.

---

## 9. The three candidates not taken, and one new gap

Carried from [the first review][prev1] §9. Candidates 1, 2, 3 and 4 are done; **none of what is left
was rated Strong.**

| | Candidate | Strength |
|---|---|---|
| 5 | Scheduling rules leak into `FindAvailableSlotsTool`: a second Booking Horizon (`MAX_DAYS = 14` under a comment claiming it matches the engine's, which is 31), and an invented `"OUTSIDE_REQUESTED_TIMES"` the `EmptyReason` enum does not contain | Worth exploring |
| 6 | `ErrorCode` is declared twice in two languages and **has already drifted** — `UNSUPPORTED_MEDIA_TYPE` is emitted by `JsonOnlyWriteFilter:78` and absent from the TS union, and `client.ts:125` casts it in | Worth exploring |
| 7 | Reads have `use-resource`; writes have twelve hand-rolled copies, and the coverage gate keys on the read module's name so it cannot see them | Speculative |

**Candidate 5 is the one to take next**, and it is this sitting's shape a third time: a bound
restated in a second place, under a comment asserting it matches. That comment is already false —
14 against 31 — so unlike `MAX_ROUNDS` it is a plain stale copy and should be quicker.

Candidate 6 keeps its note: `SLOT_UNAVAILABLE` and `VERSION_CONFLICT` now reach the model, a third
consumer of a vocabulary that already has two definitions.

### 9.1 G49 — the tool payload has two authors and no referee. **Opened, not fixed**

`OpenAiChatModel.request` builds each tool as `{type, function:{name, description, parameters,
strict}}`. `ProbeFixtureDumpTest` builds the same five fields again, and its javadoc says so
plainly: *"if that method's shape changes, this must change with it, or the probe will confidently
measure a request the application never sends."* Nothing enforces it, and `OpenAiChatModel` has **no
direct test at all** — there is no `ai/openai` test package, and no HTTP stubbing anywhere in the
backend suite. It is exercised only by the E2E, through the fake provider, which has not been run in
five sittings and does not assert the request's shape either.

**Not fixed here, on purpose, because both ways out are decisions.** Closing it means either a
production change — inject `RestClient.Builder` so `MockRestServiceServer` can bind to it, which is
shaping the adapter around a diagnostic, the exact thing that javadoc declined to do — or standing
a real socket up in a test, which is a pattern this backend does not currently have anywhere. Note
that the ArchUnit rule is *not* the obstacle: `AiProviderIsolationTest` imports with
`DO_NOT_INCLUDE_TESTS`, so a test may name the adapter package freely.

### 9.2 Loose ends, unchanged

All five from [the previous handoff][prev2] §9.1 are still untouched: `ConversationStore.identify`
has no caller, `ToolContext:15-18` contradicts `LookupAppointmentTool:85`, `AiProperties:103`
hard-codes `"sk-local-dev-only"`, `ToolRegistry.has(String)` and `AuthorizedAppointments.toArray()`
have no callers, and `RescheduleDateFidelityRateTest` re-types SQL inline at `:192`, `:203`, `:240`.

---

## 10. If you are picking this up

1. **Commit and push both sittings, and let CI see them.** They are two commits by path (§1), and
   this one includes a new CI job that has never run on a runner. It needs nothing but a checkout
   and `python3`, and it passes locally, but a CI job's first real proof is a red-or-green run.
2. **`make e2e`**, or carry the risk forward explicitly for a sixth sitting.
3. **Re-run `ProbeFixtureDumpTest` before your next probe batch.** `fixtures/loop.json` is new, and
   a fixtures directory written before this sitting has no such file — the probe will refuse to
   start and tell you so, which is the intended failure, not a bug.
4. **Take candidate 5.** §9. Nothing Strong is left, and 5 is the closest relative of what the last
   two sittings fixed.
5. **G48 still needs a decision, not a patch.** [Two handoffs back][prev1] §6.
6. **Do not add a constant to `probe.py` for anything the application decides.** If the probe needs
   a new one, it goes in `loop.json` and `ProbeFixtureDumpTest` writes it. `read_loop` refuses a
   missing or unusable field rather than defaulting, and that refusal is three of the thirteen
   self-test checks.
7. **Do not re-explain the README's recorded rates with §2's finding.** §6 says why.
