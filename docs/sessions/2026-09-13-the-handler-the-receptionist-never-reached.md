# Session handoff — 2026-09-13 — the handler the Receptionist never reached

> **Candidate 2 from [the previous sitting][prev]'s review, and it was exactly the defect it was
> reported to be.** `appointments_no_overlap` deciding a race was translated into
> `409 SLOT_UNAVAILABLE` by `GlobalExceptionHandler` — correctly, completely, and **only there**.
> The Receptionist reaches the same services through `ToolRegistry` and never through a controller,
> so its `catch (RuntimeException)` took the violation first and answered *"Something went wrong on
> my end. Let me try that another way."* The one account of the event that is false: nothing went
> wrong, the time went. The `@Version` check had the same gap.
>
> **One commit.** `e47f9c6`. `PersistenceRefusal` is now the single definition of which database
> failures are answers, and both edges ask it.
>
> **The defect was reproduced on the real path before it was fixed**, which is the part worth
> keeping. `ConcurrentToolRescheduleTest` moves two appointments onto one time through
> `reschedule_appointment`; with the translation removed the loser is handed `TOOL_ERROR` verbatim,
> three repetitions out of three. With it, `SLOT_UNAVAILABLE`, **12 of 12**.
>
> **And that reproduction found something the review had not.** The path that reaches the exclusion
> constraint in ordinary use is **reschedule**, and the reason is an absence: `BookingService` takes
> an advisory lock per Employee *before* its re-check, so two bookings queue and the loser is
> refused by name. `RescheduleService` takes no such lock and has no `DeadlockRetry`. Opened as
> **G48**, not fixed — adding the lock is a concurrency-design call, and it is the principal's.
>
> **A deadlock was expected there and did not appear.** Issue #7's deadlock is the same shape, so
> two movers were expected to abort each other; twelve repetitions produced twelve clean constraint
> violations instead. **Measured at two movers and never above two** — §7.
>
> **`ToolExecutionTest`'s taken-slot case reads like coverage of this and is not.** It books
> sequentially, so the slot is committed before the call and the *availability re-check* refuses —
> a different branch, correctly asserted, for eleven phases. **T171.**

[#7]: https://github.com/sanama-stack/reception-booking-system/issues/7
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[prev]: ./2026-09-13-the-card-that-was-never-rendered.md

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`e47f9c6`**, pushed. **CI green**, every job including the compose smoke test |
| Backend | **1082 tests, 0 failed**, on a forced full run (`--rerun-tasks`) |
| Frontend | Untouched — no frontend file changed this sitting |
| New | `PersistenceRefusal`, three test classes, one row in `docs/05-ai-architecture.md` §8 |
| E2E | **Still not run.** [The previous handoff][prev] §6's named risk is unchanged and uncleared |
| Issues | [#17] unchanged and still credit-blocked. This closes none of it |
| Review | **2 of 7 candidates done.** Five remain, in §8 |

```text
e47f9c6  Tell a Customer the time went, not that the Receptionist broke
```

**The previous handoff's §9 item 1 is discharged.** Its three commits were pushed at the start of
this sitting and CI was green on them before any new work began.

---

## 2. What was actually wrong

`GlobalExceptionHandler` held the mapping, and held it well: keyed on the constraint **name** rather
than on `DataIntegrityViolationException`, with a comment explaining that catching the type broadly
would report a null column as a lost race and make the real defect invisible. All of that was right.

It was right at one edge. `BookingService:183` said so in a comment —

```java
// GlobalExceptionHandler turns the violation into 409 SLOT_UNAVAILABLE by constraint name.
```

— and that sentence is true of every caller that passes through a controller. The Receptionist is
not one. `ToolRegistry.execute` wraps every tool call in `catch (ApiException)` and
`catch (RuntimeException)`, and a `DataIntegrityViolationException` is the second, so the translation
never ran. The customer heard that the Receptionist had broken.

**A comment is a claim about a caller it cannot see.** That one was accurate when written, in phase
06, when there was one edge. Phase 09 added a second and nothing re-read the sentence — and nothing
could, because a comment has no test. This is the whole of **T172**.

### 2.1 What changed

`common/error/PersistenceRefusal.java`. One method, `of(Throwable) → Optional<ApiException>`.

| | |
|---|---|
| `DataIntegrityViolationException` **naming `appointments_no_overlap`** | `SLOT_UNAVAILABLE` |
| `OptimisticLockingFailureException` | `VERSION_CONFLICT` |
| anything else, **including any other integrity violation** | empty — the caller fails as it fails |

`GlobalExceptionHandler` and `ToolRegistry` both ask it. The handler lost the constant, the cause-chain
walk and eleven lines; the registry gained four. Neither decides anything any more.

**The cause-chain walk moved intact and is still load-bearing.** Spring's exception does not carry a
constraint name and Hibernate's only sometimes parses one out; the name arrives in the driver's
message, several causes down. `PersistenceRefusalTest` asserts the nested case specifically, because
a translation reading only `getMessage()` passes a hand-built positive and fails in production.

---

## 3. The reproduction, which is the part to keep

The fix is four lines and would have been believable without evidence. The evidence is better than
the fix.

`ConcurrentToolRescheduleTest` books two appointments for one Employee, then moves both onto a third
time at once through `reschedule_appointment` — the real registry, the real tool, the real service,
real Postgres, two threads on a `CyclicBarrier`. Both moves pass their availability re-checks,
because neither can see the other's uncommitted row. One lands. The other meets the constraint.

**With the translation removed, the loser is handed this:**

```json
{"error":"TOOL_ERROR","message":"Something went wrong on my end. Let me try that another way."}
```

Three repetitions, three failures, the same sentence each time — the sentence [the review][prev]
predicted, arriving from the real path rather than from a stub. With the translation in place:
`SLOT_UNAVAILABLE`, and the assertion also pins that exactly one row holds the contested time.

**Twelve of twelve green**, in four separate `--rerun-tasks` runs. Three repetitions of a passing
concurrency test demonstrates very little — `ConcurrentBookingTest` says so in its own javadoc — so
the class was run four times rather than once.

### 3.1 The Gradle trap from [the previous handoff][prev] §8 was paid once and then avoided

Every counterfactual in this sitting used `--rerun-tasks`. Without it, restoring the file returns
the tree to a hash that has already passed and `BUILD SUCCESSFUL` prints over nothing having run.
The lesson was read before it was re-paid.

---

## 4. What the fix deliberately is not

**It does not catch `DataIntegrityViolationException`.** That is the obvious implementation, it is
four characters shorter, and it converts every unmapped integrity failure into a cheerful *"that
time was booked while you were deciding"* — a null in a column nobody noticed, reported as an
ordinary busy Tuesday, with the real defect invisible because the response looked normal.

`PersistenceRefusalTest` and `ToolRefusalTest` each assert that counterfactual directly: an unmapped
violation stays `TOOL_ERROR` over the tool path and `INTERNAL_ERROR` over HTTP, and leaks no SQL.
**Both of those tests fail if someone widens the catch**, which is the point of writing them.

**It does not retry.** A deadlock victim (`CannotAcquireLockException`) is neither refusal — Postgres
aborted a transaction that may well have been entitled to the time, and `DeadlockRetry` is what
answers it (#7). `PersistenceRefusalTest` pins that too, so a future widening has to argue with a
test rather than with a comment.

---

## 5. One copy change, and it was a delegated call

`VERSION_CONFLICT`'s message was:

> Someone else changed this appointment while you were editing it. **Reload and try again.**

Written when only a browser could read it. Routing it to the tool path would have had the
Receptionist say *"reload"* to somebody holding a telephone. It is now:

> Someone else changed this appointment a moment ago. Check it again and try once more.

**Taken as a delegated call rather than escalated**: one sentence, one definition, no test pinned
it, and the dashboard's behaviour is unchanged — `reschedule-section.tsx` still calls `onReload()`
on the code and renders whatever detail the server sends. Recorded here because a copy change nobody
recorded is a copy change that gets reverted by the next person who reads the old screenshot.

`SLOT_UNAVAILABLE`'s sentence was already true on both surfaces and was not touched. `ErrorLeakageTest`
still pins it by substring.

---

## 6. G48 — reschedule takes no lock, and has no retry. **Opened, not fixed**

Found by the reproduction, not by the review.

| | booking | reschedule |
|---|---|---|
| advisory lock per Employee, before the re-check | **yes** | **no** |
| `DeadlockRetry` around the transaction | **yes** | **no** |
| what a loser meets | the re-check, refused **by name** | **the exclusion constraint** |

`AppointmentLockRepository`'s javadoc explains why booking has the lock: the constraint settles the
race correctly but *impolitely*, each transaction waiting on the other's uncommitted tuple, and two
of those is a deadlock rather than a conflict. At twenty racers, retry alone still lost roughly a
third of runs and every loser got a 500 (#7).

`RescheduleService` never got either. The consequence is not currently a bug — this sitting's fix is
exactly what makes the constraint's verdict readable — but it means **reschedule is the path that
takes the ugly route by construction**, and the ugly route's known failure mode above two racers is
a deadlock that nothing retries.

**Not fixed here, on purpose.** Adding `locks.lock(...)` to `RescheduleService` changes the
concurrency design of a write path: it serialises moves per Employee, it interacts with the
`@Version` check, and it would make this sitting's translation a backstop rather than the answer.
That is a principal's call, and the ritual says so. It is also cheap to take once decided — the lock,
the retry wrapper and the programmatic transaction boundary all already exist in `BookingService`,
written and commented.

---

## 7. What is not verified, stated plainly

- **The deadlock was measured at two movers and never above two.** Twelve repetitions produced
  twelve clean constraint violations and no `CannotAcquireLockException`, which is a real result and
  a narrow one: #7's deadlock needed twenty racers to show its shape. **Do not read "no deadlock" as
  a property of the reschedule path.** Read it as: at two, it does not happen. G48 is where the rest
  of that measurement belongs.
- **No live model, again.** Everything is `ScriptedChatModel` or no model at all. No prompt changed,
  no [#17] number moved, and `unoffered_writes` is still zero measurements.
- **The E2E sweep is still not run**, and [the previous handoff][prev] §6's named risk — the filter
  button outside the `overflow-x-auto` wrapper on `/conversations` at 360 px — is exactly as
  unverified as it was. This sitting touched no frontend file, so it neither cleared nor worsened it.
- **The `VERSION_CONFLICT` branch still has no end-to-end test through a tool.** `ToolRefusalTest`
  proves the translation; `ConcurrentToolRescheduleTest` reaches the *constraint*, not the version
  check. Reaching the version check through the Receptionist needs two writers on **one** appointment
  where only one of them is a tool, and it was not built. The frontend's own note says this branch
  has never been exercised either ([phase 06 frontend handoff](./2026-09-09-phase-06-frontend.md) §
  "the one branch never exercised").

---

## 8. Lessons

**T171 — a test can assert the right code and reach the wrong branch, and its name will not say
so.** `ToolExecutionTest`'s *"create_appointment on a taken slot returns SLOT_UNAVAILABLE rather
than throwing"* is correct, passes, and asserts a real guarantee. It books sequentially, so the
conflicting row is committed and visible and the **availability re-check** refuses — the branch that
was never broken. The constraint branch returned `TOOL_ERROR` for eleven phases underneath a test
whose name covered it. Same family as **T150** (*a target's name is an assertion that nothing runs*),
with a sharper edge: T150's target executed nothing, and this test executed the wrong thing while
passing honestly. The javadoc on that method now says which branch it reaches and where the other
one is tested; **when a test's name is broader than its reach, write the reach down, because the name
will keep being read as coverage.**

**T172 — a translation that lives at one edge is a translation the other edges do not have.** The
constraint mapping sat in `GlobalExceptionHandler`, which is not a shared place — it is *the HTTP
place*. Every caller arriving another way got the untranslated failure, and the comment pointing at
the handler made the gap harder to see rather than easier, because it read as a citation. The test
for this is not "is the mapping correct" but **"how many edges reach the services, and does each one
ask?"** Two today: `GlobalExceptionHandler` and `ToolRegistry`. A third would be a third caller of
`PersistenceRefusal`, and it is now a class rather than a method body precisely so that the question
has somewhere to be asked.

---

## 9. The five candidates not taken

Carried forward from [the previous handoff][prev] §7, unchanged in content and renumbered by nothing
— candidate 1 was that sitting's work and candidate 2 was this one's.

| | Candidate | Strength |
|---|---|---|
| 3 | **The mapped-endpoint surface is derived eight times.** `EndpointCatalogue` is deep for *judgement*; the *derivation* is copy-paste across eight test classes, and `MappedSurfaceTest` (556 lines) exists because of what the copies miss | **Strong** |
| 4 | **The orchestration loop has two implementations.** `probe.py` reimplements it in Python with `MODEL` and `MAX_ROUNDS` hand-synced — and already divergent (5 against 6). Its own docstring records it reporting 73% and 99% where the live system was 84% and 96% | **Strong** |
| 5 | Scheduling rules leak into `FindAvailableSlotsTool`: a second Booking Horizon (`MAX_DAYS = 14` under a comment claiming it matches the engine's, which is 31), and an invented `"OUTSIDE_REQUESTED_TIMES"` the `EmptyReason` enum does not contain | Worth exploring |
| 6 | `ErrorCode` is declared twice in two languages and **has already drifted** — `UNSUPPORTED_MEDIA_TYPE` is emitted by `JsonOnlyWriteFilter:78` and absent from the TS union, and `client.ts:125` casts it in | Worth exploring |
| 7 | Reads have `use-resource`; writes have twelve hand-rolled copies, and the coverage gate keys on the read module's name so it cannot see them | Speculative |

**Candidate 3 is the one to take next**, and candidate 6 is now more interesting than its ranking
suggests: this sitting put a second consumer on `ErrorCode`'s *Java* side, and the drift it names is
between Java and TypeScript. The two are independent, but a session taking 6 should know that
`SLOT_UNAVAILABLE` and `VERSION_CONFLICT` now reach a surface the TS union does not describe at all —
the model. Nothing is broken by that; it is simply a third consumer of a vocabulary that already has
two definitions.

### 9.1 Loose ends, unchanged

All five from [the previous handoff][prev] §7.1 are untouched:

- `ConversationStore.identify` has **no caller anywhere**, so `customer_id` is permanently null — and
  is still projected to the dashboard at `ConversationResponses:50`.
- `ToolContext:15-18` says *"Tools do not read it directly"*; `LookupAppointmentTool:85` reads
  `context.businessId()` directly.
- `AiProperties:103` hard-codes `"sk-local-dev-only"` in `application/`, which ADR-0009 confines to
  `OpenAiChatModel`.
- `ToolRegistry.has(String)` and `AuthorizedAppointments.toArray()` have no callers. **Both were read
  again this sitting and both are still uncalled** — `ToolRefusalTest` constructs a `ToolRegistry`
  directly and needed neither.
- `RescheduleDateFidelityRateTest` re-types SQL inline at `:192`, `:203` and `:240` while using
  `ProbeQueries` at `:216` and `:227`.

---

## 10. If you are picking this up

1. **Nothing is unpushed and CI is green.** For the first time in three handoffs, §1 has no backlog
   in it. Keep it that way rather than discovering it later.
2. **`make e2e`**, or carry [the previous handoff][prev] §6's risk forward explicitly for a third
   sitting. It has now survived two handoffs on the strength of "safe by construction" for the half
   that is, and an estimate for the half that is not.
3. **Take candidate 3.** §9.
4. **G48 needs a decision, not a patch.** §6 lays out both halves. Do not add the advisory lock to
   `RescheduleService` without one — and if it is added, read `AppointmentLockRepository`'s javadoc
   first, because the lock's position relative to the re-check is the whole of why it works.
5. **Do not widen `PersistenceRefusal` to catch a type.** §4, and two tests will say so.
