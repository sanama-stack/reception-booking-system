# Session handoff — 2026-09-11 — Phase 10, Calendar and Analytics, backend half

> **Purpose.** Enough context to build phase 10's frontend half without re-reading anything. §1 says
> where things stand; §2 is what the two endpoints are and what each test actually proves; §3 is the
> five decisions taken, **one of which is not mine to take**; §4 is a rationale I had to withdraw
> before it shipped; §5 is what a fresh session must not assume is done.
>
> **Read §3.5 first if you read nothing else.** Revenue is filtered to a single currency, and that
> is a decision waiting on the principal rather than a defect to fix.
>
> The other half of this session parked [#17] after a third rejected candidate; that is a separate
> document, [the guard that locked in the drift][guard], and nothing here depends on it. **Nothing
> is committed.** The working tree carries this phase, that session's documentation, and the
> previous session's `stats.py`.

[guard]: ./2026-09-11-the-guard-that-locked-in-the-drift.md
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 1. Where the project stands

**Phase 10's backend half is complete and green.** Every box under *Backend* in the phase document
is ticked except the index verification, and every box under *Unit* and *Integration* is ticked
except the two that belong to screens. `/calendar`, `/analytics`, the dashboard home and the polish
sweep are **not started** — the same split phases 03, 04, 05, 06, 08 and 09 all took.

| | |
|---|---|
| `origin/main` | **`a80ffad`** — pull request #21, CI green on all three jobs |
| Working tree | **everything below is uncommitted**, alongside two sessions' documentation |
| Backend | **827 tests, built green** (804 before, so 23 new) |
| Frontend gates | Not re-run: **no frontend file changed this session** (G9 still unrun) |
| Migrations | **None.** Analytics aggregates over tables that already exist; the calendar is three reads the services already owned |
| New ADR | None |
| Issues | **#15 and #17 open**, neither touched by this half |

### 1.1 What was added

| File | |
|---|---|
| `analytics/AnalyticsRepository.java` | four aggregates, nothing that loads an Appointment |
| `analytics/AnalyticsService.java` | boundaries, validation, assembly |
| `analytics/web/AnalyticsController.java`, `AnalyticsResponses.java` | `GET /analytics/summary` |
| `calendar/CalendarService.java`, `CalendarView.java` | one read, composed from the services that own the rows |
| `calendar/web/CalendarController.java`, `CalendarResponses.java` | `GET /calendar` |
| `analytics/AnalyticsSummaryTest.java` | **14 tests** |
| `calendar/CalendarViewTest.java` | **9 tests** |

Four existing files gained a range read each — `AppointmentRepository`, `AppointmentQueryService`,
`BusinessClosureRepository` + `ClosureService`, `EmployeeTimeOffRepository` + `TimeOffService`.
**No existing behaviour changed.**

---

## 2. The two endpoints

### 2.1 `GET /analytics/summary?from=&to=`

Both dates inclusive, resolved in the Business's timezone. The body is exactly the one fixed in
[../04-api-overview.md](../04-api-overview.md) §5.

**The three things the phase document calls easy to get wrong are each tested against their
counterfactual rather than merely exercised** — which is the difference between a test that passes
and a test that could have failed:

| | how it is proven |
|---|---|
| Revenue is the **snapshot** | the price is **edited after the appointment completes**, and the figure is re-read. An implementation that joined to the catalog passes every other test in the class and fails this one |
| Rates are `null`, not `0` | read over an **empty range**, where `0` is the plausible wrong answer and the one every naive implementation gives |
| Boundaries are the **Business's** | the Business is **moved to `Pacific/Auckland`**, where 16:00 Tbilisi is midnight the following day. The appointment does not move; the calendar under it does. A report cutting days in UTC answers identically both times |

The timezone box in the phase document asked for two businesses in two zones compared against the
same instants. **Moving one business is the sharper form** and is what is written; the phase
document's checklist now says so rather than being ticked as though it asked for this.

### 2.2 `GET /calendar?from=&to=`

Appointments, closures and time off, in one call, capped at 35 days.

**One request per view is a correctness requirement, not a performance one.** Four fetches are four
different moments: a booking made between the first and the last renders as a block with no
employee, or an employee with no block. The closures and the time off are in the same answer for a
reason of the same kind — an empty Tuesday looks identical whether the business was shut, the only
eligible employee was away, or nobody booked, and the owner acts differently on each.

Two of the nine tests assert what the endpoint **does not** send. See §3.3 and §3.4.

---

## 3. Decisions

### 3.1 The periods ignore the requested range

The contract does not say whether `today`/`thisWeek`/`thisMonth` are bounded by `from`/`to`. Bounded,
a report on last September answers *"today: 0"* for every business on earth. Unbounded, they answer
what the dashboard home is asking. **Unbounded**, and asserted by requesting a range that contains
nothing at all and watching the month still count.

### 3.2 The week starts on Monday

ISO-8601, for everybody, not the viewer's locale. A locale-dependent week start makes one business's
numbers differ by who is looking at them, which is worse than being unfamiliar to some of them.

### 3.3 The calendar does not reuse `AppointmentResponses.AppointmentDetail`

That record carries the confirmation code, the customer's phone and email, the agreed price and the
audit timestamps — everything the detail drawer needs and none of what a coloured rectangle needs.
Reusing it would ship a week of confirmation codes and phone numbers to a browser to draw blocks.

A calendar appointment carries the customer's **name** and nothing else about them, and **a test
asserts the fixture's phone number appears nowhere in the response**. The drawer fetches the full
row from `GET /appointments/{id}` when an appointment is actually opened, which is what the phase
document's click-through already described.

### 3.4 Cancelled appointments are not drawn

Their time was released the instant they were cancelled. A block over free time is how a double
booking starts. The listing endpoint still filters by status for anyone who wants to see them.

### 3.5 [PRINCIPAL] Revenue is filtered to a single currency

**This one is a decision, not a defect, and it is open.**

Appointments keep the currency they were priced in. A Business that switches currency in Settings
therefore has older revenue in a currency the summary no longer reports — and summing across them
would add lari to euros and call the result money.

The endpoint answers for the Business's **current** currency alone. It is never wrong about what it
claims: the figure stated in GEL really is the GEL revenue. It is also not the whole truth, and the
contract has **one** `revenue` object with nowhere to report the remainder.

Pinned by a test — *"after a currency change, revenue reports the new currency only"* — so the
behaviour is visible the day somebody hits it rather than discovered as a bug. **Which way it should
go needs a person:** report the remainder somewhere the contract does not currently have, report in
the currency the rows actually carry, or leave it. The test is the record of what it does today, not
an argument that today is right.

---

## 4. A rationale withdrawn before it shipped

The calendar query matches Appointments that **overlap** the range rather than start inside it. I
first justified that in the code with *"a four-hour service booked at 23:00 starts on Monday and is
still running at 01:00 on Tuesday"*.

**That case cannot happen.** `AvailabilityEngine` requires the whole appointment to be *contained*
in one of that date's opening intervals, and intervals do not span days — so nothing can cross
midnight, and for day-aligned ranges the overlap form and the starts-inside form return exactly the
same rows.

The overlap form is **still the right one**: written the other way it would be correct by
coincidence and would start dropping blocks off the top of the view the day overnight hours become
expressible. But the comment now says that, instead of defending a correct decision with a false
example.

**T24, met in the wild rather than read about**: a decision can be right and the argument for it
wrong, and the two have to be checked separately. It is worth noting the failure mode — the example
was vivid, plausible, and I did not check it until I went to write a test for it. *A rationale you
cannot write a test for is a rationale you have not checked.*

---

## 5. What is NOT done

**Do not read the ticked boxes in the phase document as more than they are.**

| | |
|---|---|
| **Indexes at 10 000 appointments** | the one *Backend* box still open. The queries are aggregate SQL over `business_id` + `starts_at`; whether the existing indexes serve them **has not been measured**, and the phase document says a miss is fixed with an index in this phase |
| `/calendar` | day and week views, employee columns, duration-proportional blocks, current-time line, detail drawer, click-empty-to-create, closure and time-off regions, AI badge |
| `/analytics` | range picker, metric cards, status breakdown, top-services table, empty state |
| Dashboard home | today's appointments, next appointment, quick stats, onboarding checklist |
| **The polish sweep** | empty/loading/error states, confirmation dialogs, 360 px, and the timezone-formatting audit across **every screen from phases 02–09** — the largest single item left in the phase, and the one most easily under-estimated because it is spread across twenty screens rather than concentrated in one |

The backend gives the frontend everything those screens need in two calls. **`GET /calendar` returns
times already converted to the Business's zone, offset included**, so the client must not convert
again — that is the phase's *"all times everywhere display in the business timezone"* requirement,
decided on the server precisely so the client cannot get it wrong.

---

## 6. Every open item

### 6.1 Issues

| # | State |
|---|---|
| **[#17]** | **OPEN, unfixed, deliberately parked** by the principal. Three candidates screened and rejected, two harmful. See [the other handoff][guard] §5 |
| **[#15]** | **OPEN, unchanged** |

### 6.2 Gaps

Carried: **G1** (no inline tool activity), **G3** (phase-09 DoD box unticked — #17 is open, so it
stays that way), **G8** ("Any available" never rendered), **G9** (`pnpm build` still unrun — no
frontend file touched again), **G10** (the weekday arm still has one phrasing), **G11**
(`requested_date` never recorded, [guard] §4).

New: **G12** — the analytics and calendar indexes are unverified at scale, §5.

### 6.3 Traps

Carried: T1–T25. Nothing new here; §4 is T24 met in practice rather than a new trap.

### 6.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged.** Not used by this half at all, but 100 live conversations
were spent by the other one. Still the key pasted into a chat transcript, still not rotated.

New, and small: **the calendar endpoint is the first read that deliberately narrows what it returns
for privacy reasons rather than for size** (§3.3). If a later change reuses the detail record there
to save a class, that test is the thing that will fail, and it should be believed rather than
relaxed.

### 6.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; nothing deletes an `ai_message`; rate-limit buckets are in memory;
`make seed` is a placeholder; no frontend test runner; phase 11 unstarted.

---

## 7. Next steps, in order

### P0

1. **Phase 10's frontend half**, starting with `/calendar` — it is the phase's centrepiece and the
   only screen with real layout risk (duration-proportional blocks, employee columns, a current-time
   line, and closures and time off drawn as distinct non-bookable regions).
2. **Decide §3.5**, the revenue currency. One question, and the answer changes a response shape.

### P1

3. **Verify the indexes at 10 000 appointments** (G12).
4. **Return `service_id` from `lookup_appointment`** — still unfiled, still one field, still a wasted
   round trip on 100% of reschedule conversations.
5. **Run `pnpm build`** (G9) — it will be run by the frontend half anyway.

### P2

6. **[#17]** by one of [the other handoff][guard] §5's three routes · **[#15]** · `ai_message`
   retention · phase 11.

---

## 8. Commands

```bash
# From backend/. The whole suite, ~6 minutes. Probe and level-3 tags are excluded by default.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# Just this phase.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test \
  --tests '*AnalyticsSummaryTest' --tests '*CalendarViewTest'
```

**Do not run `./gradlew` twice at once against this tree.** Two builds share `build/`, and a suite
run that overlapped a compile this session had to be discarded and re-run — the second daemon starts
happily and says nothing about what it is stepping on.

Counting the suite is worth doing from the XML rather than the console, because the console total
does not distinguish a stale result file from a fresh one:

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(t, f, e)"
```

---

## 9. Confidence

**High — the three correctness properties in §2.1.** Each is asserted after the operation that would
break it, not before, so each test had a way to fail.

**High — the isolation probes.** Both endpoints take only dates, so what is probed is the aggregate
itself: a `group by` that forgot its `business_id` returns a perfectly well-formed summary of
somebody else's business. Both tests build a real second tenant with real rows rather than probing
with a random id, which is the implementation they exist to catch.

**High — §4.** It is a reading of `AvailabilityEngine`'s containment check, not a measurement, but
the check is four lines and unambiguous.

**None — performance.** G12 is open and nothing here has been run against more than a handful of
rows. The queries are shaped to be indexable; that is not the same as knowing they are indexed.

**None — the frontend.** No screen exists. Everything in §5 is a plan, not a state.
