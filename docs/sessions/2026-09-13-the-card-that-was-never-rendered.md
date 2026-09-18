# Session handoff — 2026-09-13 — the card that was never rendered

> **An architecture review of the whole repository found seven deepening candidates, and the first
> one turned out to be a defect hiding in plain sight.** `reschedule_appointment` has returned the
> `starts_at` the server landed on since phase 09. The orchestration loop dropped it. So after a
> move, **the model's prose was the only account of the new time a Customer could read** — and
> docs/05-ai-architecture.md §6 calls that exact control *"the single most effective hallucination
> control in the system"*. It covered booking and not moving, which is the one path [#17] measures.
>
> **Two commits, in an order that lets the first be reverted without the second.**
> `04fd8a3` renders the card. `58510c7` adds `OfferedSlots`, which compares every Appointment write
> against the Slots the Conversation actually quoted and counts the mismatches on the row.
>
> **The check refuses nothing, and [ADR-0012](../adr/0012-writes-are-checked-against-offered-slots-and-never-refused.md)
> is mostly about why.** [#17]'s third candidate was a guard, and it took wrong writes from 28.0% to
> 44.0%. Its lesson — **T25**, *a guard is only as good as the intent it is given* — does not reach
> this check, because it compared two **model-authored** fields and an Offered Slot is authored by
> the availability engine. That distinction is the whole reason an ADR exists rather than a comment.
>
> **A defect was caught inside this session's own change, before it compiled.** The shared projection
> read `confirmation_email_sent`; reschedule returns `reschedule_email_sent`. `path()` on an absent
> key yields a missing node and `asBoolean()` on that is `false` — so **every moving Customer would
> have been told no email was coming**, which is precisely what ADR-0007 and ADR-0008 exist to
> prevent, arriving through a shared projection. The key is now a parameter, absence throws, and the
> test asserts the *true* case because it is the only one that can fail.
>
> **`receptionist-panel.tsx` got its first test in eleven phases** — 493 lines, the largest untested
> component in the repository, and the coverage gate could not see it because it discovers by
> `useResource` and `<EmptyState>` and the panel uses neither.
>
> **Nothing here has met a live model.** Everything is `ScriptedChatModel`, real Postgres and jsdom.
> The production rate `OfferedSlots` exists to produce is still **zero measurements**, and that is
> the point of it: the number no longer needs a funded arm to ask for.

[#10]: https://github.com/sanama-stack/reception-booking-system/issues/10
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[prev]: ./2026-09-13-the-list-nobody-had-walked.md

---

## 1. Where the project stands

| | |
|---|---|
| `dev` | **`58510c7`** — two commits this sitting. **Both unpushed.** CI has not seen either |
| Backend | **1070 tests, 0 failed**, on a forced full run (`--rerun-tasks`) |
| Frontend | **26 files, 94 tests**, typecheck, lint and prettier green |
| New | `OfferedSlots`, `V11__offered_slot_counters.sql`, ADR-0012, `CONTEXT.md` gains **Offered Slot** |
| E2E | **Not run.** See §6 — there is one specific unverified risk and it is named |
| Issues | [#17] unchanged and still credit-blocked. **This closes none of it** |
| Review | **1 of 7 candidates done.** The other six are listed in §7 |

```text
04fd8a3  Render a card when the Receptionist moves an appointment
58510c7  Count the writes that landed on a time nobody was offered
```

---

## 2. Why there were two commits and not one

The card stands on its own merits: it is the only part of this sitting a **Customer** sees, and it
would be worth having if `OfferedSlots` were abandoned tomorrow. The counters are an internal
measurement nobody outside the dashboard will ever notice.

So they are separable, and the split was made **the harder way on purpose**: three files carry both
changes — `ConversationService.java`, `docs/04-api-overview.md`, `docs/05-ai-architecture.md` — and
each was reduced to its card-only state, committed, and then restored.

**That split introduced a bug, and the check for it is the reason this paragraph is not an
embarrassment.** Excising the `OfferedSlots` block from the loop took the `for` loop's closing brace
with it. Commit 1 was then verified *in isolation* — `git stash -u`, build, test, pop — and failed
to compile at `ConversationService.java:348`. Amended, re-verified, green.

**A commit nobody has built is a commit nobody has checked.** Both of these have been built at their
own tree, not only at the tip.

---

## 3. The defect inside the change

`PublicChatResponses.bookedAppointment` is the projection that turns a flat `snake_case` tool result
into the nested `BookedAppointment` a card renders. Widening it to serve moves as well as bookings
looked like a one-line change.

It was not, and the reason is a Jackson default:

```java
toolResult.path("confirmation_email_sent").asBoolean()   // reschedule returns reschedule_email_sent
```

`path()` on an absent key returns a `MissingNode`, and `asBoolean()` on that is `false`. Every
rescheduling Customer with an address on file would have been told **no confirmation was coming**,
on a screen whose entire design history is about not doing that.

The tools name that fact per action on purpose — `confirmation_email_sent`,
`reschedule_email_sent`, `cancellation_email_sent` — because those names are read by a model, which
is better served by the specific one. So the key is a **parameter**, and an absent key **throws**
rather than defaulting:

> a missing key is a programming error in the same repository, and the honest failure is a 500 the
> suite catches — not a card quietly telling a Customer nothing is on its way.

**The test asserts the `true` case**, because `false` is what a wrong key produces and a test written
against a Customer with no address would have passed against the defect. It books with an address
first. Reintroducing the hard-coded key was tried, and it fails.

---

## 4. `OfferedSlots` — what it is and what it deliberately is not

One method. `landingOf(businessId, conversationId, toolName, result)` → `Optional<Landing>`. Empty
means *not a write*, so a caller counts exactly what it is handed.

| Decision | Settled as | Why |
|---|---|---|
| Behaviour | **detect, never refuse** | ADR-0012, §5 below |
| Left-hand side | exact **Offered Slot** instants | a Slot is the only thing the server ever *offered*; a window is what was *searched* |
| Lookback | the whole Conversation | the failure is cross-turn by construction — a resolver answer in turn two, a write in turn three |
| Timing | **inline**, in the turn | one place knows which Tool calls are writes; keeps a refusal one branch away |
| Storage | dates from the Transcript, **two counters** on the Conversation | V10's split allows counters; a date a Customer asked about is closer to content than to cost |
| Denominator | `create_appointment` **and** `reschedule_appointment` | a booking to a never-shown time is the same defect; `cancel` carries no time |

**The time is read from the tool *result*, not the arguments.** The argument is what the model asked
for; the result is what the server did. It also means one key rather than knowing that one Tool says
`starts_at` and the other `new_starts_at`.

**The check sits inside a `try`/`catch` that logs and swallows.** A fault in an observation must not
be able to fail a Customer's booking. The booking is committed by then either way; losing the count
is the cheaper failure.

### 4.1 Two numbers that are known to be wrong in one direction

`find_available_slots` caps its answer at `MAX_SLOTS` and sets `truncated`. A real Slot truncated out
of an answer and then booked reads here as **unoffered**. The counts therefore run slightly high.

That was not fixed by loosening the comparison. `offers_truncated` is logged beside every verdict, so
**the overcount is measurable rather than assumed away** — which is the same discipline that produced
G17 and T36 in this repository, arrived at before the first number rather than after it.

---

## 5. Why it refuses nothing, and why that needed an ADR

This is the part a future session will otherwise re-suggest within a week, because a guard is the
obvious design and it is already **built and rejected** —
`docs/sessions/2026-09-11-the-guard-that-locked-in-the-drift.md`.

| | control | candidate 3 (the guard) |
|---|---|---|
| wrong writes | 14/50 = 28.0% | **22/50 = 44.0%**, Fisher p = 0.9700 |
| never wrote | 6/50 | **4/50 — it fell** |

Never-wrote *falling* is the tell: a safety net converts wrong writes into refusals, so it must
climb. **T25 — a guard is only as good as the intent it is given. Cross-checking two model-authored
fields against each other proves they agree, not that either is right.**

**T25 does not reach this check.** Candidate 3 compared `requested_date` against `new_starts_at`,
both authored by the model and capable of being wrong together. An Offered Slot is authored by the
availability engine, so the comparison is between what the server offered and what it was asked to
write. Different check, not a rehabilitated one.

Two risks **do** transfer, and are why this stops at detection:

- candidate 3's harm came from making the model **declare** something new, which forced early
  commitment. Nothing here asks the model for anything.
- *a candidate that improves its primary by refusing to act is not a fix* — the experiment's own §4
  veto. A detector cannot trip it; a refuser would have to be measured against it first.

Also rejected, and worth knowing: **echoing the offered times back into the model's context is
already shipped and already insufficient.** `FindAvailableSlotsTool` has put `searched_from` and
`searched_to` into every result since `7d99200`, the first AI commit, and [#17] happens anyway.

---

## 6. What is not verified, stated plainly

**`e2e/tests/mobile.spec.ts` sweeps `/conversations` for sideways scroll at 360 px, and this sitting
added a sixth column and a filter button to that screen.** The sweep was not run.

- **The column is safe by construction.** `Table` wraps its `<table>` in `overflow-x-auto`, so a
  wider table scrolls inside its own container and cannot widen `document.documentElement.scrollWidth`
  — which is what `measureOverflow` reads.
- **The button is not proven.** It sits outside that wrapper, in a `flex justify-end`. At roughly
  175 px inside a ~328 px content width it should be comfortable, but that is an estimate and the
  phase-10 lesson (**T26**) is that this class of defect is *measured, not looked at*.

`make e2e` closes it. The repo records a Playwright install hang, which is why it was not run
unprompted rather than why it should not be run.

**Nothing met a live model.** `ScriptedChatModel`, real Postgres, jsdom. No prompt changed and no
model was called, so no [#17] number moved.

---

## 7. The six candidates not taken

The review that produced this sitting found seven. In its own ranking:

| | Candidate | Strength |
|---|---|---|
| 2 | **One database constraint, two translations — and the Receptionist gets neither.** `BookingService:183` states in a comment that `GlobalExceptionHandler` turns the exclusion-constraint violation into `409 SLOT_UNAVAILABLE`. It is false for the Receptionist: `ToolRegistry.execute` catches the `RuntimeException` first, so a Customer who loses a race is told *"Something went wrong on my end"* rather than that the time went. `ToolExecutionTest:317` books sequentially and cannot see it | **Strong** |
| 3 | **The mapped-endpoint surface is derived eight times.** `EndpointCatalogue` is deep for *judgement*; the *derivation* is copy-paste across eight test classes, and `MappedSurfaceTest` (556 lines) exists because of what the copies miss | **Strong** |
| 4 | **The orchestration loop has two implementations.** `probe.py` reimplements it in Python with `MODEL` and `MAX_ROUNDS` hand-synced — and already divergent (5 against 6). Its own docstring records it reporting 73% and 99% where the live system was 84% and 96% | **Strong** |
| 5 | Scheduling rules leak into `FindAvailableSlotsTool`: a second Booking Horizon (`MAX_DAYS = 14` under a comment claiming it matches the engine's, which is 31), and an invented `"OUTSIDE_REQUESTED_TIMES"` the `EmptyReason` enum does not contain | Worth exploring |
| 6 | `ErrorCode` is declared twice in two languages and **has already drifted** — `UNSUPPORTED_MEDIA_TYPE` is emitted by `JsonOnlyWriteFilter:78` and absent from the TS union, and `client.ts:125` casts it in | Worth exploring |
| 7 | Reads have `use-resource`; writes have twelve hand-rolled copies, and the coverage gate keys on the read module's name so it cannot see them | Speculative |

**Candidate 2 is the one to take next.** It is a real, customer-visible defect with a small blast
radius, and it is cheap.

### 7.1 Loose ends the review found and this sitting did not fix

- `ConversationStore.identify` has **no caller anywhere**, so `customer_id` is permanently null — and
  is still projected to the dashboard at `ConversationResponses:50`.
- `ToolContext:15-18` says *"Tools do not read it directly"*; `LookupAppointmentTool:85` reads
  `context.businessId()` directly.
- `AiProperties:103` hard-codes `"sk-local-dev-only"`, an OpenAI-shaped prefix, in `application/` —
  which ADR-0009 confines to `OpenAiChatModel`.
- `ToolRegistry.has(String)` and `AuthorizedAppointments.toArray()` have no callers.
- `RescheduleDateFidelityRateTest` re-types SQL inline at `:192`, `:203` and `:240` while using
  `ProbeQueries` at `:216` and `:227` — the class built to stop exactly that is half-adopted.

---

## 8. Traps paid for in this sitting

- **A Gradle re-run after a counterfactual replays the cached pass.** Break the code, watch it fail,
  restore it — and the tree now hashes identical to the version that already passed, so
  `BUILD SUCCESSFUL in 1s` prints and **nothing runs**. It happened twice. `--rerun-tasks`, and treat
  a suspiciously fast green with no per-test lines as *nothing ran*.
- **`queryByText(/unoffered/i)` matched the filter button's own label**, not the table row, so two
  new tests passed for the wrong reason. Match the badge's actual shape.
- **The screen coverage gate caught a conditional empty-state title** and demanded `catalogue.ts` say
  where the copy comes from. Working as designed; registered with the reason.
- **A commit built only at the tip is a commit that has not been built.** §2.

---

## 9. If you are picking this up

1. **Push.** Two commits, CI has seen neither, and the previous handoff ended with nothing unpushed.
2. **`make e2e`**, or accept §6's named risk explicitly.
3. **Take candidate 2.** §7.
4. **Do not re-suggest a guard on the write path** without reading ADR-0012 and §5 first.
5. The first real `unoffered_writes` number will arrive the first time the Receptionist runs against
   a funded key. **It will not be comparable to [#17]'s figures** — different denominator, on purpose,
   recorded in the ADR.
