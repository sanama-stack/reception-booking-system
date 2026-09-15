# 07 — MVP Scope

The scope test, applied to every candidate feature:

> **Does this directly contribute to proving that a customer can book, reschedule and cancel an
> appointment through an AI receptionist, against a booking engine the AI cannot bypass, inside a
> tenant that cannot see another tenant's data?**

If no, it is in [future/future-features.md](./future/future-features.md).

---

## In scope

### Accounts and tenancy
- Email + password registration that atomically creates `User` + `Business` + `OWNER` Membership + default business hours
- Login, refresh-token rotation, logout
- Roles `OWNER`, `ADMIN`, `STAFF` exist in the model; **only `OWNER` is a working login in MVP**
- Every tenant-scoped request derives `business_id` from the authenticated Membership — never from a URL or body

### Business configuration
- Profile: name, slug, description, address, phone, email, website, timezone, currency
- Business Hours per day of week (schema supports multiple intervals per day; MVP UI exposes one)
- Booking settings: slot interval, minimum lead time, maximum advance, cancellation window
- Business Closures (date ranges)
- Cancellation policy text
- Receptionist knowledge: FAQ entries + one bounded free-text field

### Services
- CRUD: name, description, duration, buffer before/after, price, currency, active flag
- Assignment of Employees to Services

### Employees
- CRUD: name, email, phone, job title, active flag
- Working Schedule per day of week
- Time Off (date-time ranges)
- **Employee is a bookable resource, not a login** (nullable `user_id` link exists, unused in MVP)

### Availability engine
- Given business + service + date range (+ optional employee), returns bookable Slots
- Respects: business hours, working schedules, existing appointments, service duration, buffers,
  time off, closures, minimum lead time, maximum advance, the current instant, and the business timezone
- Pure, deterministic, `Clock`-injected, unit-tested including DST transitions

### Appointments
- Create, cancel, reschedule, status transitions (`CONFIRMED` → `COMPLETED` / `NO_SHOW` / `CANCELLED`)
- Double-booking prevented by a Postgres exclusion constraint, surfaced as `409`
- Price snapshotted onto the appointment at booking time
- Audit trail of every state change

### Notifications
- Booking confirmation email (carries Confirmation Code + Manage Link)
- 24-hour reminder email
- Cancellation and reschedule emails
- Database outbox drained by a scheduled poller; Mailpit locally

### Public booking page (`/book/{slug}`)
- Business profile, services with prices and durations
- **Classic Flow**: service → employee (or any) → date → slot → confirm
- **Receptionist**: conversational booking, rescheduling, cancelling, and business Q&A
- Manage Link landing page for a customer arriving from an email

### Receptionist (AI)
- OpenAI tool calling with strict JSON schemas
- Eight tools, **plus a ninth under test** (`resolve_date`, [#17](https://github.com/sanama-stack/reception-booking-system/issues/17)); the model can do nothing else
- Conversation persisted per business
- Rate limits, turn ceilings, tool-call ceilings, per-business daily spend cap with graceful degradation

### Dashboard
- Onboarding checklist
- Services, employees, schedules, closures, FAQs, settings (each shipped with its backend phase)
- Appointment list and calendar view
- Customer list and per-customer history
- Analytics summary
- Read-only Receptionist transcripts

### Operations
- `docker compose up` → Postgres, backend, frontend, Caddy, Mailpit
- Single origin, httpOnly cookies, no CORS
- Flyway migrations, seeded two-tenant demo data
- CI running the full test suite

---

## Out of scope (with the reason)

| Excluded | Why |
|---|---|
| `PENDING` status / owner approval queue | Implies notifications, expiry and a queue UI nobody specified. V1.1 behind a `requires_approval` flag |
| Email verification at signup | Puts a wall in front of the demo; adds a flow with no architectural interest |
| Employee (`STAFF`) login | Role exists; the login flow adds authorization surface without proving anything new |
| Keycloak / enterprise SSO | See [ADR-0001](./adr/0001-self-issued-jwt-over-keycloak.md) |
| Payments, Stripe, subscriptions, deposits | Orthogonal to the thesis; large surface |
| SMS / WhatsApp / push / voice | Email proves the notification architecture; channels are adapters behind a port |
| Google / Outlook Calendar sync | Two-way sync is a project of its own |
| Customer accounts and login | Confirmation Code + Manage Link deliberately replace them |
| Multiple locations, franchises | Would add a second tenancy axis to every query |
| Vector search / RAG | FAQ volume never justifies it; bounded context is the better hallucination control |
| Multi-language AI | One more axis on every prompt and every test |
| Recurring appointments, waitlists, group bookings | Each changes the availability engine's shape |
| Advanced BI, cohort analytics | The summary endpoint proves business value |
| Internet-reachable deployment | MVP contract is local compose; a deploy path is *documented*, not operated |
| Redis, message queue, Kafka | Nothing in MVP needs them; see [ADR-0005](./adr/0005-database-outbox-instead-of-queue.md) |

---

## MVP Definition of Done

The MVP is complete when **all** of the following are true. Each line is verifiable by running the system.

> **Audited and corrected on 2026-09-11.** Everything below this line was written in `aa84b19`, the
> phase-01 commit, and was never opened again — before the availability engine existed, before the
> Receptionist existed, before every ADR, and before the defects phases 09 and 10 measured. Five rows
> turned out to be **weaker than a decision this project had already recorded**, so a defect the
> principal had explicitly ruled on would have ticked them on a technicality. Those five are widened
> in place below, each naming what it now rests on.
>
> This is the same treatment [08-testing-strategy.md](08-testing-strategy.md) §11's Frontend row got
> on the same day, and for the same reason: a scaffold that contradicts a ruling is corrected with the
> reasoning written beside it, not quietly satisfied.
>
> **A box is ticked against evidence, or the defect it covers is listed under *Accepted, measured,
> open defects* with a rate, a date and an issue.** At the time of the audit none of the thirty had
> ever been ticked, and the list had survived ten phases unread.
>
> **There turned out to be a third state, and pretending otherwise is what let it hide.** A box can
> also be *unverifiable* — the instrument that would decide it cannot be run. That is neither a tick
> nor a measured defect: there is nothing to tick against and no rate to carry. It is recorded under
> *Gates that cannot be run* below, which requires what the gate would have checked, when it last
> ran, what has changed since, and what must happen for it to run again. **An unverifiable box is not
> a failing one and it is not a passing one**, and the sign-off has to say which boxes are in that
> state rather than leave a reader to infer it from silence.
>
> **Walked 2026-09-13 — the first time the thirty were actually gone through.** The audit above
> corrected the wording and recorded that none had ever been ticked; it did not tick them. This pass
> did, against named evidence, and the headline is that **the list was carrying far less risk than
> its zero suggested**: twenty-four rows already had evidence and were waiting only for somebody to
> look. **24 ticked, 6 open**, and the six are three distinct reasons rather than six pieces of
> outstanding work:
>
> | | Rows | Why |
> |---|---|---|
> | **Unverifiable** | the three Receptionist behaviour rows | [#17], and the corpus under *Gates that cannot be run*. **Not credits any more** — the corpus is runnable and unrun |
> | **Needs a run** | `make up` from a clean checkout | Nobody has done the clone-to-running walk, and it cannot be done from a working copy |
> | **Needs CI, not a fix** | every phase's tests pass in CI; the README demo script | CI has not run since `6321485`; the demo needs a key |
>
> **No row was ticked by reading alone.** Each names the test that carries it, and two were measured
> during the walk rather than cited: `.env.example` by comparing every `${VAR}` placeholder against
> its keys (35 against 35), and the full-history secret scan by running it (239 commits, clean).
> Where a row is only partly proven it says so in place — the ownership half of the reschedule row
> is ticked in its own text while the date half stays with [#17], and the conversational-booking row
> records that the E2E's Receptionist is the fake provider.

### Functional
- [x] A new owner registers and lands on a dashboard with an onboarding checklist — *ticked
      2026-09-13.* `RegistrationTest`, `OnboardingProgressionTest` (the checklist reaches *"your
      page is ready"* one step at a time, and each deactivation takes it back out), and the E2E's
      `register an owner` step
- [x] The owner configures profile, timezone, business hours and booking settings — *ticked
      2026-09-13.* `BusinessSettingsTest` — every profile and settings field patched and read back,
      and a patch naming one field leaves the other sixteen alone — with `BusinessHoursEndpointTest`
      and `BusinessHoursValidationTest`
- [x] The owner creates services with distinct durations and prices — *ticked 2026-09-13.*
      `ServiceEndpointTest`, `ServiceValidationTest`, and the E2E's `add a service the employee can
      perform`
- [x] The owner creates employees with *different* working schedules and assigns services to them —
      *ticked 2026-09-13.* `EmployeeEndpointTest`, `EmployeeScheduleEndpointTest`,
      `AssignmentEndpointTest`, and the E2E's `add an employee and give them a working schedule`.
      `DemoSeedTest` holds the *different* clause: the barber performs neither Colour nor Balayage
- [x] The owner records a business closure and an employee's time off — *ticked 2026-09-13.*
      `ClosureEndpointTest` and `TimeOffEndpointTest`, including the half-open storage of both
- [x] `/book/{slug}` is publicly reachable and shows the business, services, prices and durations —
      *ticked 2026-09-13.* `PublicBookingTest` — the page shows the business, its hours and its
      policy; services carry a duration and a price, and inactive ones are not offered
- [x] A customer completes a booking through the Classic Flow — *ticked 2026-09-13.*
      `PublicBookingTest`, *"a stranger books end to end and the booking is sourced CLASSIC"*, and
      the E2E's `a stranger books through the Classic Flow`
- [x] A customer completes a booking by conversation with the Receptionist — *ticked 2026-09-13,
      and read narrowly.* `PublicChatTest` and the E2E's `a stranger books by talking to the
      Receptionist`. **The E2E runs against the fake provider behind the base URL
      ([ADR-0011](adr/0011-the-e2e-fake-provider-lives-behind-the-base-url.md)), so what is ticked
      is that the conversational path books** — session, tool dispatch, the card, the row. Whether
      the *model* chooses correctly is the next three rows, and they are not ticked
- [ ] The Receptionist answers a business question using only configured information, and says it does
      not know when the information is absent — **and states no slot, price or policy that did not come
      from a tool.** *Widened 2026-09-11.* The original reaches answers to questions; [#17] is an
      **unprompted** assertion made mid-reschedule — *"the earliest I can reschedule your appointment
      for is tomorrow"* — which is false, came from no tool, and the original wording does not cover.
      That clause is phase 09's own Definition-of-Done box, which the principal ruled **not ticked at
      today's rates**. *Walked 2026-09-13:* **unverifiable** — carried under *Gates that cannot be
      run*, and the corpus that would decide it needs credits
- [ ] The Receptionist offers only slots returned by the availability engine — **for the date the
      customer actually named.** *Widened 2026-09-11.* Under [#17] every offered slot does come from the
      engine; the engine was asked about the wrong date. The original ticks on that, which is exactly
      why it is no longer the whole test. *Walked 2026-09-13:* **open defect**, carried under [#17]
      with a rate — and the arm that would settle `resolve_date` is fifteen trials of fifty short
- [ ] The Receptionist reschedules and cancels an appointment after the customer proves ownership —
      **and the write lands on the date the customer named.** *Widened 2026-09-11.* Ownership proof was
      never what [#17] breaks: the writes that land on a date nobody said had ownership correctly proven
      first, which is what makes a wrong one indistinguishable from a right one. *Walked 2026-09-13:*
      the **ownership half is proven** — `PublicAppointmentAuthorityTest` drives code-plus-phone and
      Manage-Link authority through reschedule and cancel — and the **date half is [#17]**
- [x] Confirmation and reminder emails arrive in Mailpit with a working Manage Link — **or the response
      says one was not sent, per [ADR-0007](adr/0007-booking-response-says-whether-a-confirmation-was-sent.md).**
      *Widened 2026-09-11.* A booking with no address on file sends nothing, by design; a flat "arrive"
      makes the correct behaviour look like a failed box. *Ticked 2026-09-13.* `NotificationDeliveryTest`
      — a real email carrying the code and a working Manage Link, and *"a customer with no email causes
      no message and no failure"* — with `PublicBookingTest` on the ADR-0007 clause in both directions,
      and the E2E's `one confirmation arrives, not two, and the Manage Link resolves`
- [x] The appointment appears in the owner's dashboard list and calendar, marked as AI-sourced —
      *ticked 2026-09-13.* `AppointmentListingTest` and `CalendarViewTest`, and the E2E's `the owner
      sees both bookings, one badged AI and one cancelled` — which is the badge on screen rather than
      the column in the payload
- [x] The owner changes an appointment to `COMPLETED` and to `NO_SHOW` — *ticked 2026-09-13.*
      `AppointmentStatusTest` on the transition rules, `AppointmentLifecycleTest` on the endpoint, and
      the E2E's `marking one completed moves analytics revenue`
- [x] The analytics summary reports counts, revenue from completed appointments, and top services —
      **and names its excluded remainder, per [ADR-0010](adr/0010-revenue-reports-one-currency-and-names-the-remainder.md).**
      *Widened 2026-09-11.* Revenue is filtered to one currency, so a business that switched sees a
      number smaller than its real revenue; ADR-0010 decided the remainder is reported, not dropped.
      *Ticked 2026-09-13.* `AnalyticsSummaryTest` carries all four clauses, the remainder one twice —
      after a currency change it is named beside the revenue, and with one currency it is an empty list
      rather than null or absent

### Correctness
- [x] Availability excludes: past slots, slots inside the lead time, slots beyond the horizon, slots overlapping existing appointments or buffers, closures, time off, and any slot the service duration does not fully fit inside
      — *ticked 2026-09-13.* `AvailabilityEngineTest` and `SlotBookabilityTest` name every exclusion in
      this row separately, and `SlotBookabilityTest` closes the pair both ways: *"every slot findSlots
      offers passes isSlotBookable"* **and** *"every start it does NOT offer is refused with a reason"*
- [x] Two concurrent bookings of the same slot result in exactly one appointment and one `409` —
      *ticked 2026-09-13.* `ConcurrentBookingTest`: *"twenty threads book one slot: one 201, nineteen
      409, exactly one row"*, which is stronger than the row asks for. `ConcurrentRescheduleTest` and
      `DeadlockRetryTest` cover the same constraint on the other write paths
- [x] A booking that crosses a DST transition produces correct local times — *ticked 2026-09-13.*
      `AvailabilityEngineTest`'s DST nest, where *"a booking spanning the gap is two real hours, not
      three"* — 01:00 to 04:00 on the wall clock — with `SlotGeneratorTest` on the grid either side of
      both transitions and `ClosureConversionTest` on the 23- and 25-hour days. Each has a no-DST zone
      as its control
- [x] A customer cancelling inside the cancellation window is refused; the owner cancelling is not —
      *ticked 2026-09-13.* `CancellationWindowTest` on the boundary, including a zero-hour window, and
      `PublicAppointmentAuthorityTest`'s *"inside the window a customer is refused, and the business is
      not"* — which is the row's second clause, and the half a domain test cannot reach

### Isolation and security
- [x] Every tenant-scoped endpoint returns `404` when given another tenant's resource id — *ticked
      2026-09-13.* `TenantIsolationSweepTest`, whose first assertion is that **every catalogued
      collection and singleton has a control registered for it**, so the sweep cannot pass by covering
      nothing. `SmuggledBusinessIdTest` asserts the catalogue matches what is actually mapped
- [x] No endpoint accepts `business_id` from the client — *ticked 2026-09-13.* `SmuggledBusinessIdTest`
      — *"a businessId in a request body does not move the row it creates"* — with
      `PublicFieldAllowListTest` and `PublicSurfaceSweepTest`
- [x] No AI tool accepts `business_id` from the model — *ticked 2026-09-13.* `ToolSchemaTest`: *"no
      published schema contains a business_id, anywhere, at any depth"*. Re-run green on 2026-09-13
- [x] Appointment lookup requires Confirmation Code + phone; a phone number alone reveals nothing —
      *ticked 2026-09-13.* `PublicAppointmentAuthorityTest`: the right code with the wrong number proves
      nothing, and **a phone number on its own is refused by the schema, before it costs an attempt**
- [x] Public endpoints are rate limited; exceeding the limit returns `429` — *ticked 2026-09-13.*
      `RateLimitTest` on the refusal and its retry-after, and `RateLimitCoverageTest` on the harder
      half: **every endpoint an anonymous caller can reach is covered by a policy**, the public surface
      is derived rather than empty, and every exemption carries a reason

### Engineering
- [x] `make up` brings the whole system up from a clean checkout — *ticked 2026-09-13, by doing it.*
      A fresh `git clone` of `origin/dev` at `82fc678` into a scratch directory, **not** this working
      copy: `make up` created `.env` from `.env.example` and brought up Postgres, Mailpit and Caddy on
      fresh volumes; `make up-all` then built backend and frontend **from the clone's own context** —
      compose declares no `image:`, so it could not reuse an image built here — and all five containers
      reported healthy. `/api/health` answered `{"status":"UP","components":{"database":"UP","mail":"UP"}}`,
      `/book/salon-aria` answered `200`, and the public API returned the business and its services. The
      clone's database held **exactly the two demo tenants**, which is what proves the volume was
      genuinely fresh rather than the five-tenant one on this machine. Torn down with `-v` afterwards;
      the pre-existing volumes were untouched. **Two deviations were required and both are recorded as
      gaps — G46 and G47**
- [x] Seed data creates two businesses in different verticals — *ticked 2026-09-13.* `DemoSeedTest`:
      **Salon Aria** (Asia/Tbilisi, GEL, GE) and **Dato's Auto** (Europe/Berlin, EUR, DE) — two
      verticals, two timezones, two currencies — with every seeded appointment asserted inside both the
      business's hours and the employee's schedule, and seeding twice leaving one copy
- [x] Every phase's tests pass in CI, including the concurrency test and the tenant-isolation suite —
      *ticked 2026-09-13.* The seven commits were pushed and CI ran on `82fc678`: **all six jobs green**
      — Backend (`ConcurrentBookingTest` and `TenantIsolationSweepTest` among 1063), Frontend,
      Documentation consistency, Compose smoke test, End-to-end, and **`Pipeline parity` on its first
      ever run on GitHub**. That last one is why this row is worth more than it was: the Makefile and the
      workflow are now asserted to run the same suites, in both directions, by a job rather than by hand
- [x] One Playwright E2E run covers chat → booking → dashboard — *ticked 2026-09-13 for coverage.*
      `e2e/tests/flow.spec.ts` is a single run whose steps are: register, employee, service, **Classic
      booking**, **Receptionist booking**, confirmation and Manage Link, **the owner's dashboard with
      the AI badge**, and analytics. It last passed in CI at `6321485`; the row above carries the fact
      that CI has not run since
- [x] `.env.example` documents every configuration variable — *ticked 2026-09-13, and measured rather
      than read.* Every `${VAR}` placeholder in `application*.yml` and the three compose files was
      compared against `.env.example`'s keys: **35 used, 35 documented, none missing**. Worth repeating
      as a check rather than a reading — it is a `comm` over two sorted lists
- [ ] `README.md` contains a demo script a stranger can follow — *walked 2026-09-13:* **blocked on the
      same credits.** The script exists and step 9's second half was browser-verified on 2026-09-13
      (*"without a key the panel says so"*). Its first half needs a working `OPENAI_API_KEY`, so the
      script cannot be followed end to end without deviation — which is phase 11's own row, carried
      under *Gates that cannot be run*

---

## Accepted, measured, open defects

A box above is ticked against evidence, or the defect it covers is named here with a **rate**, a
**date** and an **issue**. A defect that is in neither place is not accepted — it is unnoticed, which
is the state this section exists to make impossible.

A box whose *instrument* cannot be run is a different thing and belongs in *Gates that cannot be run*
at the end of this document. It has no rate, because nothing was measured.

Carried by the principal's ruling of **2026-09-11**: the alternative was to gate the MVP on an
unfinished experiment, and the ruling was that a measured defect shipped knowingly beats a box that
went quiet. The rate is part of the entry precisely so that shipping stays a decision somebody made.

### [#17] — the Receptionist writes a reschedule to a date the customer did not name

| | |
|---|---|
| **Rate** | **10.6%** of writes land on a date the customer never named — 5 of 47, CI [3.5%, 23.1%] |
| **Worst case** | **55.2% wrong** when the customer phrases the date relatively ("the Monday after next") rather than reading out an ISO date — against 10.6% when they read one out, Fisher p = 3.9e-05 |
| **Measured** | 2026-09-11 — [the experiment record](experiments/2026-09-11-17-deterministic-date-resolution.md) |
| **Ruling** | 2026-09-11 — phase 09's hallucination box is **not ticked at today's rates**, and a fourth candidate was authorised |
| **Status** | **Open.** Three candidates rejected, two of them measurably harmful. The fourth, `resolve_date`, moved the primary measure from 18% to 58% (p = 3.5e-05) and is **one arm short of a verdict** — the deciding run stopped at fifteen trials of fifty when the OpenAI account ran out of credits |
| **Issue** | [#17](https://github.com/sanama-stack/reception-booking-system/issues/17) |

**This is what Functional boxes 9, 10 and 11 rest on**, and it is why all three were widened rather
than left to tick on wording written before the defect class was known. The customer-visible shape is
not a crash: ownership is proven, a real slot is returned by the real engine, a real appointment is
written, and the only thing wrong is the day — which is the one part nothing downstream can check.

**[#15]** ([issue](https://github.com/sanama-stack/reception-booking-system/issues/15)) is related and
also open. It is **not** listed as accepted here, because its title still names a diagnosis a later
session disproved and it therefore has no trustworthy rate to carry. Fixing the title is a
prerequisite to accepting it, not a formality.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#40]: https://github.com/sanama-stack/reception-booking-system/issues/40

---

## Gates that cannot be run

A gate recorded here is neither passing nor failing: it has not been exercised, and it cannot be. An
entry needs **what the gate checks**, **when it last ran**, **what has changed since**, and **what
must happen for it to run again**. It carries no rate, because nothing was measured — that is the
whole of the difference between this section and the one above.

An entry leaves this section in one direction only: the gate runs, and its result becomes a tick or a
measured defect.

### Level 3 — the live-model corpus. **Ran on 2026-09-15. This entry has left this section**

| | |
|---|---|
| **What it checks** | `LiveReceptionistTest` — twelve conversations against the real model, asserting tool sequences and database state. **The only instrument in this project that can evaluate the system prompt or a tool description**, because a scripted model reads neither ([08-testing-strategy.md](08-testing-strategy.md) §7) |
| **Last ran** | **2026-09-15** — **11 of 12 passed**. Previously 2026-09-10, green — [the session record](sessions/2026-09-10-the-gap-that-was-hiding-a-wrong-write.md) |
| **Why it could not run** | The OpenAI account had no credits: `429`, `insufficient_quota`, `credit_balance_exhausted`, confirmed 2026-09-13. **Credits were restored on 2026-09-15 and the gate was run the same day** |
| **Result** | One failure: `a customer who proves the appointment is theirs can move it`. The Receptionist told the Customer a free 15:00 slot was "already booked" — a statement no tool produced — and refused an authorised write on the strength of it. [#40] |
| **Ruling** | **2026-09-13** — recorded as unavailable. **Discharged on 2026-09-15 by running the gate**, which is the one exit this section allows |

> **This entry is kept, struck through by its own result, rather than deleted.** The rule at the top
> of this section is that an entry leaves in one direction only — the gate runs, and its result
> becomes a tick or a measured defect. The gate ran. What it produced is recorded above and on [#40].
>
> ⚠️ **The result does not yet qualify for *Accepted, measured, open defects*, and has deliberately
> not been filed there.** That section requires a **rate**, and this is **one trial**. The corpus runs
> each case once, so it can establish that a behaviour is reachable and can never establish how often
> — the same limitation [#15] carries in writing. Naming a rate from a single observation is the error
> that section exists to prevent, so the defect sits on [#40] with no rate until an instrument that
> can measure one is built.

**What this does and does not change.** The three Receptionist rows in *Functional* stay unticked —
but for a materially better reason than before. They were unverifiable; they are now **measured and
failing**, on a transcript, and box *"It never states a slot, price or policy that did not come from a
tool"* has a concrete counter-example rather than an absent instrument. "Cannot be checked" has become
"checked, and red", which is the direction this document wants even though the box did not move.

**The exposure, stated narrowly.** §8's fencing could have made the Receptionist *less willing to use
the FAQs it fences*, and nothing in levels 1 and 2 would say so — `SystemPromptSafetyTest` asserts the
fence is emitted, not that the model still reads through it. It cannot have widened the injection
surface, because the fence only ever adds delimiters. `resolve_date` was measured when it shipped and
is [#17]'s open arm, one measurement short of a verdict, stopped by the same credits.

> **This was knowable for two days before it was asked.** The credit exhaustion was written into this
> document on **2026-09-11**, under [#17]'s *Status* — *"stopped at fifteen trials of fifty when the
> OpenAI account ran out of credits"* — and phase 11's §8 note said `-PincludeTags=llm` *"needs a key
> and credits"*. The corpus question was then raised on 2026-09-13 and carried through seven handoffs
> as *the principal's call*, listed each time beside *"credits for #17"* without either being read
> against the other. **Nobody tried to run it.**
>
> The lesson is not about credits. **An item escalated to a decision should be attempted first**,
> because "which would you prefer" and "this is impossible" are answered by the same five-minute
> experiment, and only one of them is a question worth a principal's time.

