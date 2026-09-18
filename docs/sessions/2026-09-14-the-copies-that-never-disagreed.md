# Session handoff — 2026-09-14 — the copies that never disagreed

> **Candidate 3 from [the previous sitting][prev]'s review.** The mapped-endpoint surface was derived
> eight times: **six byte-identical copies of `patternsOf`**, nine copies of the `dev.reception`
> package filter, three near-identical `Endpoint` records, and one derivation with no null guard at
> all.
>
> **The copies never disagreed with each other. They agreed — about what they could not see.** Every
> one of them paired a pattern with a verb by iterating `info.getMethodsCondition().getMethods()`, so
> a mapping declaring no verb yielded an empty loop and contributed nothing. It was not filtered out;
> *it never arrived*. `MappedSurfaceTest` is the bill for that, and it was paid three sittings ago
> without the cause being removed.
>
> **`ResourceSurface`'s javadoc held the judgement this overturns** — *"the repository has five copies
> of `patternsOf` and they have been harmless; this one produces a **value**, which is a different
> risk."* The distinction was real and the conclusion was wrong. A copied *filter* is not safer than a
> copied *generator* when all the copies share a blind spot: it is one blind spot with six witnesses,
> and none of them can see it, because a blind spot is exactly where a control reports nothing.
>
> **One derivation now: `dev.reception.support.MappedSurface`.** It hands a methodless mapping back
> under the verb `ANY` instead of swallowing it, so every control keyed by verb **names its narrowing
> at its own call site** — `declaringAMethod()`, or an `answering(…)` list that cannot contain `ANY`
> — and writes down what that costs. The silent drop became a written decision; a control that
> forgets to narrow gets `ANY` endpoints in its results rather than a quietly shorter list.
>
> **The counterfactual is the part to keep.** Reverting `MappedSurface` to the shape the six copies
> had turns **3 of `MappedSurfaceTest`'s 12 red** — and leaves **nine green, including the two
> assertions written about this exact blind spot**. §3.

[prev]: ./2026-09-13-the-handler-the-receptionist-never-reached.md

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`1cc0848`, unchanged.** This sitting's work is in the working tree and **not committed** — §10 item 1 |
| Backend | **1083 tests, 0 failed**, on a forced full run (`--rerun-tasks`) |
| Frontend | Untouched — no frontend file changed this sitting |
| Production code | **Untouched.** Test sources and two documents only |
| New | `dev.reception.support.MappedSurface`; one new test in `MappedSurfaceTest` |
| Changed | Nine test classes, `docs/06-security.md`, `ResourceSurface`'s javadoc |
| E2E | **Still not run.** Unchanged and uncleared for a fourth sitting |
| Review | **3 of 7 candidates done.** Four remain, in §9 |

**Nothing here is pushed and CI has not seen it.** The previous handoff ended with an empty §1 and
asked for it to be kept that way; this sitting has not kept it, deliberately — the work is finished
and verified locally, and committing was left to the principal. It is one commit away.

---

## 2. What was actually duplicated

Counted before touching anything, because "eight test classes" was the review's phrase and the shapes
underneath it are not all the same thing.

| Derived thing | Copies | Where |
|---|---|---|
| `patternsOf(RequestMappingInfo)` | **6, byte-identical** | `AuthEventLoggingTest`, `EndpointCoverageTest`, `MappedSurfaceTest`, `NoCorsConfigurationTest`, `FormPostRejectionTest`, `RateLimitCoverageTest` |
| `getPackageName().startsWith("dev.reception")` | **9 occurrences, 7 classes** | including one *inverted* copy in `ApiDocumentationExposureTest` |
| `record Endpoint(method, pattern)` + `samplePath`/`signature`/`compareTo` | **3** | `FormPostRejectionTest`, `NoCorsConfigurationTest`, `RateLimitCoverageTest` |
| the `getHandlerMethods().forEach` pattern×verb loop | **~11** | all of the above |
| no guard at all | **1** | `PublicFieldAllowListTest:455` called `.getPathPatternsCondition().getPatternValues()` with no null check |

**The three `Endpoint` records diverged in exactly one character sequence** and it was load-bearing in
one direction. Two filled template variables with a UUID; `RateLimitCoverageTest` filled them with
`"sample"`. Either works for `AntPathMatcher` and the security matchers, which is all that class does
with the path — and `"sample"` would have been a `400` in the two classes that issue real HTTP
requests, because Spring binds `{id}` to a `UUID` before the handler runs. The UUID is the value that
is correct in all three places rather than the one two of them happened to use. §5.

### 2.1 What the derivation now is

`MappedSurface.of(mappings)` returns every mapping, narrowed by nothing. Narrowings are named and
chained: `.ours()` / `.framework()`, `.declaringAMethod()`, `.under(prefix)`, `.answering(verbs…)`,
terminating in `.endpoints()` / `.signatures()` / `.patterns()` / `.handlers()`.

```java
// EndpointCoverageTest, in full
return MappedSurface.of(mappings).ours().declaringAMethod().signatures();
```

Two smaller properties came with it, both deliberate:

- **`patternsOf` throws instead of returning the empty set** for a mapping with no path patterns.
  Under `PathPatternParser` — Boot's default since 3.0, not overridden here — there is no such
  mapping; a null would mean routing had moved to the deprecated `PatternsRequestCondition` and every
  derivation in the repository would step over it. The six copies all returned `Set.of()` there, so
  that day would have arrived as silence. Same reasoning as `MappedSurfaceTest.surfaceOf`, which
  refuses to treat "could not be asked" as "serves nothing".
- **`PublicFieldAllowListTest` had no guard at all** and now has that one. It would have thrown
  `NullPointerException`; it now throws a sentence.
- **`answering(verbs…)` refuses a verb no mapping can declare**, checked against `RequestMethod`'s own
  values rather than a typed list. Every caller feeds the result to an emptiness assertion, and an
  empty set produced by a misspelt verb passes one exactly as well as a clean surface does — the same
  blindness this class exists to remove, arriving through a typo rather than through a loop.

---

## 3. The counterfactual, which is the part to keep

`MappedSurface.of` reverted to the shape the six copies had — methodless mappings swallowed rather
than reported under `ANY` — then `MappedSurfaceTest` re-run under `--rerun-tasks`.

**3 of 12 failed. Nine passed.** Both halves matter.

| | |
|---|---|
| `the mappings with no HTTP method are the ones named here` | **FAILED** — the set is empty, `MAPPED_WITHOUT_A_METHOD` says `/error` |
| `the endpoints outside dev.reception are the ones named here` | **FAILED** — `ANY /error` has left the framework surface |
| `the narrowing every derivation makes still removes something, and only this` | **FAILED** — new this sitting; `declaringAMethod()` removed nothing |

The first two pin sets by equality and so notice that `/error` has gone. **They report the symptom;
the new one reports the cause.** A set that lost an entry reads like the application changed — the
first thing a reader does is check whether `/error` is still mapped — where "the narrowing stopped
narrowing" is the sentence that leads back to the derivation. That is the whole of what the third
test adds, and it is worth being precise that it is not what makes the regression detectable.

**The nine that stayed green are the more interesting half, because two of them are about this exact
blind spot:**

- `no endpoint of ours is invisible for want of a declared HTTP method` asserts a set is empty — and
  an empty set is precisely what a blind derivation returns. Correct in both worlds, useless for
  telling them apart.
- `nothing invisible to the derivations is reachable without authentication` walks the hand-written
  `MAPPED_WITHOUT_A_METHOD`, not the derivation, so the derivation can go blind underneath it without
  moving the result.

**An assertion written about a blind spot is not automatically an assertion that can see the blind
spot come back.** T175.

### 3.1 The Gradle trap was avoided, not paid

[The previous handoff][prev] §8 and the one before it both record it: restore a file after a
counterfactual and the tree hashes identical to the version that already passed, so
`BUILD SUCCESSFUL in 1s` prints and nothing runs. Every run this sitting used `--rerun-tasks`, and the
task counts were checked rather than the wall-clock (`5 actionable tasks: 5 executed`).

---

## 4. What the fix deliberately is not

- **It does not refuse a methodless mapping by construction.** That was the third option and it is
  wrong: `/error` is legitimately methodless, and `MappedSurfaceTest`'s job is to *observe* it. A
  derivation that threw would move the exemption list into the shared class and break the one test
  written to look at the thing.
- **It holds no judgement.** `EndpointCatalogue` says what isolation means per endpoint;
  `MappedSurfaceTest` says what sits outside this mapping altogether. Both are prose somebody has to
  write, and neither belongs in a derivation. `MappedSurface` answers one question: what does
  `RequestMappingInfoHandlerMapping` route.
- **It is not a line-count win and should not be sold as one.** Roughly −240/+136 across the nine test
  classes, against a new 242-line class. `MappedSurfaceTest` did not shrink; it grew by a test. The
  win is that there is one derivation, and one place where its exclusions are argued.

---

## 5. Two delegated calls, both recorded

Small, made rather than escalated, per the ritual's split.

1. **`samplePath()`'s filler is now the UUID everywhere**, where `RateLimitCoverageTest` used
   `"sample"`. Reason in §2. *Verified rather than reasoned*: every assertion in `RateLimitCoverageTest`
   — the coverage sweep, the orphan check, the public-surface control — is green with the new filler.
2. **`MappedSurface.of` takes `RequestMappingInfoHandlerMapping`, not `RequestMappingHandlerMapping`.**
   The actuator's `WebMvcEndpointHandlerMapping` is keyed by `RequestMappingInfo` too, and
   `MappedSurfaceTest` pins its surface; widening the parameter lets that pin use the same derivation
   the controllers get instead of a seventh copy of the loop. Verified: *the actuator mounts what it
   is said to mount* is green, so the actuator's mappings do carry path patterns.

---

## 6. Two written claims were false by the end, and are corrected in place

Both were true when written. This change is what made them false, so leaving them would have been the
defect, not the tidying.

- **`docs/06-security.md` §15** said a methodless mapping *"does not arrive to be excluded"*. It now
  arrives and is excluded by name. The paragraph says which of the three exclusion classes was closed
  at its source and which two were not.
- **`ResourceSurface`'s javadoc** carried the judgement that the `patternsOf` copies *"have been
  harmless"*. The old sentence is quoted in the new one rather than deleted, because the distinction
  it drew — a helper that reads a value survives copying, a helper that constructs one does not — is
  still right, and it is the conclusion that was wrong. It was written in
  [the list that was one short](./2026-09-13-the-list-that-was-one-short.md) §3.

---

## 7. What is not verified, stated plainly

- **`MappedSurfaceTest` is not smaller and candidate 3's premise is only one-third true.** It exists
  for *three* exclusion classes and this closed one. The other two — that every derivation reads one
  `HandlerMapping` out of the eight this application builds, and that all of them are scoped to
  `dev.reception` — are untouched, and the assertions about them are the bulk of the file.
- **The new `patternsOf` throw has never fired.** It cannot today: `PathPatternParser` is the default
  and every mapping has patterns. It is an unexercised branch whose value is entirely in the day it
  is wrong.
- **The E2E sweep is still not run**, now for a fourth sitting, and the named risk — the filter button
  outside the `overflow-x-auto` wrapper on `/conversations` at 360 px — is exactly as unverified. No
  frontend file changed here either.
- **No live model.** No prompt changed, [#17] unmoved, `unoffered_writes` still zero measurements.
- **G48 is untouched** and still wants a decision rather than a patch — see [the previous handoff][prev] §6.

---

## 8. Lessons

**T173 — a copied *filter* is as dangerous as a copied *generator* when every copy shares a blind
spot.** `ResourceSurface`'s javadoc argued the opposite and the argument was careful: a generator
copied twice is two generators, and the day they disagree one of them is asserting about paths the
other does not believe exist. True. What it missed is that filters fail the other way. The six
`patternsOf` copies never diverged — they agreed, perfectly, about what none of them could see, and
six controls agreeing on a blind spot is not six confirmations. **Ask of a duplicated derivation not
"will these drift apart" but "what do all of them step over, and who would report it".**

**T174 — "it never arrived" and "it was excluded" are the same empty result and opposite facts.** The
repair for the first is not a better assertion downstream; it is a derivation that hands back what it
was dropping, so that every consumer has to say out loud that it does not want it. The exclusion then
lives at the call site, in the file whose author owes the reason, instead of being an emergent
property of a `forEach` over an empty set. Same family as T172 (*a translation at one edge is one the
other edges do not have*), one level up: this is a *derivation* at one edge.

**T175 — an assertion written about a blind spot is not automatically one that can see it return.**
Measured, §3: of `MappedSurfaceTest`'s twelve, two are specifically about methodless mappings and both
stay green when the blindness comes back — one because it asserts an emptiness a blind derivation also
produces, the other because it walks the hand-written list rather than the derivation. The assertions
that caught it were the ones pinning a *derived set by equality against a written one*. **When a
control guards a blind spot, check which side of it the control reads from.**

---

## 9. The four candidates not taken

Carried from [the previous handoff][prev] §9. Candidate 1 was two sittings ago, 2 was the last one, 3
was this one.

| | Candidate | Strength |
|---|---|---|
| 4 | **The orchestration loop has two implementations.** `probe.py` reimplements it in Python with `MODEL` and `MAX_ROUNDS` hand-synced — and already divergent (5 against 6). Its own docstring records it reporting 73% and 99% where the live system was 84% and 96% | **Strong** |
| 5 | Scheduling rules leak into `FindAvailableSlotsTool`: a second Booking Horizon (`MAX_DAYS = 14` under a comment claiming it matches the engine's, which is 31), and an invented `"OUTSIDE_REQUESTED_TIMES"` the `EmptyReason` enum does not contain | Worth exploring |
| 6 | `ErrorCode` is declared twice in two languages and **has already drifted** — `UNSUPPORTED_MEDIA_TYPE` is emitted by `JsonOnlyWriteFilter:78` and absent from the TS union, and `client.ts:125` casts it in | Worth exploring |
| 7 | Reads have `use-resource`; writes have twelve hand-rolled copies, and the coverage gate keys on the read module's name so it cannot see them | Speculative |

**Candidate 4 is the one to take next** — it is the last Strong, and it is the same shape as this
sitting one layer out: two implementations of one thing, with the divergence already measured and
written in the losing copy's own docstring.

Candidate 6 keeps the note the previous handoff added: `SLOT_UNAVAILABLE` and `VERSION_CONFLICT` now
reach the model, a third consumer of a vocabulary that already has two definitions.

### 9.1 Loose ends, unchanged

All five from [the previous handoff][prev] §9.1 are still untouched. `ConversationStore.identify` has
no caller anywhere, so `customer_id` is permanently null and still projected to the dashboard at
`ConversationResponses:50`; `ToolContext:15-18` still says tools do not read it directly while
`LookupAppointmentTool:85` does; `AiProperties:103` still hard-codes `"sk-local-dev-only"` in
`application/`; `ToolRegistry.has(String)` and `AuthorizedAppointments.toArray()` still have no
callers; `RescheduleDateFidelityRateTest` still re-types SQL inline at `:192`, `:203` and `:240`.

---

## 10. If you are picking this up

1. **Commit and push this first, and let CI see it.** The working tree holds the whole sitting and
   nothing else; the full suite is green locally on exactly this tree. Until that happens §1 is a
   backlog, which is the state the previous two handoffs worked to clear.
2. **`make e2e`**, or carry the risk forward explicitly for a fifth sitting. It has now survived three
   handoffs on the strength of "safe by construction" for the half that is, and an estimate for the
   half that is not.
3. **Take candidate 4.** §9. It is the last Strong one.
4. **G48 still needs a decision, not a patch.** [The previous handoff][prev] §6 lays out both halves.
5. **Do not teach `MappedSurface.of` to swallow methodless mappings.** §3 is the measurement of what
   that costs, and `the narrowing every derivation makes still removes something` is the test that
   says so by name.
6. **Do not add a second derivation.** If a new control needs the mapped surface, it needs a narrowing
   on `MappedSurface`, and the narrowing needs a sentence at the call site saying what it excludes.
   That sentence is the whole point; six classes once had the narrowing and none had the sentence.

[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
