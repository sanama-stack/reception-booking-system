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
- Eight tools, **plus a ninth kept without acceptance** (`resolve_date`, ruled 2026-09-15, [#17](https://github.com/sanama-stack/reception-booking-system/issues/17)); the model can do nothing else
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
> **Updated 2026-09-18 — the demo-script row has closed, and the list now stands at 27 ticked, 3
> open.** The numbers above are left as the 2026-09-13 walk found them, because they are that walk's
> record and not a running total: two of its six closed after it, when the clean-checkout walk and
> CI were **run** rather than carried. The demo script is the third, followed end to end on the
> topology its own step 1 names. **Counted rather than reasoned** — the first draft of this note
> said 25 and 5, by carrying the walk's own figures forward as if nothing had closed since.
>
> **The three that remain are the three Receptionist rows, and they are now one question, not
> three.** All three turn on [#17], which was measured and fixed on 2026-09-17 — 22% → 98% correct
> landing — but 98% is not 100%, the residual is bounded at 10.6%, and the rate is for one phrasing
> at one distance. Nothing about the demo-script walk decides them: one conversation that books the
> day it was asked about is one trial, which is the error [#40] was reopened for.
>
> ---
>
> ## **Closed 2026-09-22 — 30 ticked, 0 open, on three rulings by the principal**
>
> The question those three rows were held open for was never an engineering question. It was
> *what counts as fixed*, and the issues had said so in writing for eleven days.
>
> | | Ruling |
> |---|---|
> | **[#17]** | **98% on the measured scenario is the bar.** The three rows tick against it; the defect ships knowingly at 2% |
> | **[#40]** | **Accepted at ~1.3% of conversations.** A targeted arm was declined *for now* on sample-size grounds — at that rate fifty trials see it about half the time — so the scenario design comes before the budget |
> | **[#15]** | ***Not detected at 150 in the cell customers actually hit* is the bar.** Closed against it. A second Thursday arm was declined: it tightens one cell and leaves six untouched |
>
> **Ticking a box and closing a defect are different acts, and only one of them happened to [#17]
> and [#40].** Both remain open under *Accepted, measured, open defects*, carrying their rates.
> That is the whole design of this document: a box is ticked against evidence **or** the defect it
> covers is named below with a rate, a date and an issue — and here, for the first time, both are
> true at once. The rows tick because the principal set a bar; the entries stay because 98% is not
> 100% and saying otherwise would be the rounding-up this list was audited to stop.
>
> **What no ruling touched has since been measured.** Rule 13 had **zero trials** against relative
> phrasing, which made it the largest known unknown in the system and a box on no list. It was
> pre-registered and run the same day: **46/50 = 92%**, zero wrong writes, and **phrasing no longer
> separates the arms at all** (ISO 48/50 vs WEEKDAY 46/50, p = 0.34, against p = 3.9e-05 in
> September). [The arm](experiments/2026-09-22-17-relative-phrasing-after-rule-13.md).
>
> Two things that survive it. **The 55.2% this document carried until 2026-09-22 was always the
> wrong figure** — it describes a prompt without `resolve_date`, which this repository ships — and
> the correction is recorded in §2 of that experiment. And **the measurement is one distance and one
> phrase**: 13 days, *"the Monday after next"*.
>
> **A short-distance arm was proposed here, authorised, withdrawn, and then replaced by one that
> works.** *"Next Monday at three days out"* cannot test [#17] — a bare weekday can only name a date
> inside seven days, and every observed failure had the model searching `[tomorrow, tomorrow+6]`, so
> the target sits **inside the window the model wrongly substitutes** and the arm would come back
> near-perfect while proving nothing. The harness's javadoc had said so all along (§8.6). **What ran
> instead was ISO at three days, scored on `searched the requested date` rather than on landing** —
> the one metric that can tell *did the right thing* from *got away with it* at that distance. It
> returns **47/50 against 48/50 at thirteen days, p = 0.50**: distance is not the variable (§9).
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
- [x] The Receptionist answers a business question using only configured information, and says it does
      not know when the information is absent — **and states no slot, price or policy that did not come
      from a tool.** *Widened 2026-09-11; **ticked 2026-09-22 on the principal's ruling that 98% on the
      measured scenario is the bar**.* The unprompted assertion this row was widened for —
      *"the earliest I can reschedule your appointment for is tomorrow"* — was [#17]'s invented policy,
      and the mechanism behind it is gone: *searched the requested day* went **18% → 100%** over two
      fifty-trial arms, so there is no longer a wrong answer for the model to explain away.
      `SystemPromptSafetyTest` carries the fence, `ToolSchemaTest` that no tool can be asked about
      another tenant, and the live corpus has been green twice.
      **What the tick does not cover, stated rather than implied**: *never* is a universal and the
      corpus runs each case once, so it can show a behaviour is reachable and can never show it is
      gone. The residual is [#17]'s, bounded at 10.6% and carried under *Accepted, measured, open
      defects*
- [x] The Receptionist offers only slots returned by the availability engine — **for the date the
      customer actually named.** *Widened 2026-09-11; **ticked 2026-09-22 on the principal's ruling**.*
      The first clause was never in doubt — `ConversationLoopTest` *"a booking populates
      appointmentCreated from the tool result, not from the prose"*. The clause it was widened for is
      the one rule 13 fixed: **`date_from` aimed at the day the customer named, 18% → 100%**
      (p = 1.2e-19), and correct landing **22% → 98%** (p = 1.4e-16), over two fifty-trial arms with
      0 errored in either. Observed in the running application on 2026-09-18 and again on 2026-09-22.
      **The limits are gone, and what replaces them is smaller and harder.** Both phrasings and both
      distances were measured on 2026-09-22 and rule 13 holds across all of them — relative phrasing
      46/50 with zero wrong writes, ISO indistinguishable at p = 0.34, and three days
      indistinguishable from thirteen at p = 0.50 on the first arm to control distance at all. The
      residual is **2–6%**, it writes a date no tool produced, and it is the same defect at every
      point measured; see [#17]'s entry
- [x] The Receptionist reschedules and cancels an appointment after the customer proves ownership —
      **and the write lands on the date the customer named.** *Widened 2026-09-11; **ticked 2026-09-22
      on the principal's ruling**.* The **ownership half was always proven** and is enforced server-side
      rather than by the model: `PublicAppointmentAuthorityTest` drives code-plus-phone and Manage-Link
      authority through reschedule and cancel, and `ConversationLoopTest` *"a model that invents an
      appointment id cannot cancel with it, and gains no authority"*. The **date half** is rule 13 at
      **98%**, which is what this ruling declares the bar. **The limits are [#17]'s three**: 98% is not
      100%, the residual is bounded at 10.6%, and the rate is for one phrasing at one distance
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
- [x] `README.md` contains a demo script a stranger can follow — *ticked 2026-09-18, by following
      it.* All ten steps, on step 1's own topology (`make up`, both applications from the terminal,
      `make seed`), with the running backend asserted to carry rule 13 first. **Step 9's first half
      is what had been missing and it now runs**: the Receptionist quoted 40.00 GEL and the real
      afternoon slots for the date it was asked about, booked 23 September at 16:00 as `RT7QVP05`,
      and the confirmation card was rendered from the booking rather than from the sentence — with
      the whole transcript and `create_appointment`'s `SENT`/`RETURNED` payload readable under
      **Conversations**. The 2026-09-13 reading of this row was right about its cause: the credits
      returned on 2026-09-15 and nothing re-walked the script for three days. **Two deviations are
      recorded on [phase 11](phases/phase-11-hardening-and-deployment.md)'s twin of this row** — a
      sign-out standing in for step 5's *private window*, and step 3's own wrong sentence about the
      Week view, corrected in the same commit

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
| **Rate** | **2% wrong on the measured scenario** after the fifth candidate — 1 of 50, 2026-09-17. Before it, on that same scenario, **78% wrong** (11/50 correct). The 10.6% this row carried was a *different* scenario measured 2026-09-11 and is not comparable — **T194**, distance to the horizon is uncontrolled across every rate this project recorded before that date |
| **Worst case** | **Measured 2026-09-22 across both phrasings and both distances, and it is no longer the worst case.** Relative phrasing lands **46/50 = 92%** against a pre-fix 35/50, Fisher **p = 0.0047**, with **zero wrong writes in fifty** — all four non-landings took the further reading of an ambiguous English phrase by asking `resolve_date` and using its answer, which is not this defect. **Phrasing no longer separates the arms**: ISO 48/50 against WEEKDAY 46/50 on one day and one harness, **p = 0.34**, where the same contrast was p = 3.9e-05 on 2026-09-11. **Neither does distance**: the first arm this project has run that isolates it returns **47/50 exact search at 3 days against 48/50 at 13**, p = 0.50. §8 and §9 of [the record](experiments/2026-09-22-17-relative-phrasing-after-rule-13.md). **This row's three limits are now none of the three it carried** — what is left is a property of the defect, not of the measurement: it persists at **2–6%**, it writes a date no tool produced, and nothing measured today moves it |
| **Measured** | 2026-09-17 — [the fifth candidate's record](experiments/2026-09-17-17-reschedule-searches-the-requested-date.md). Earlier: 2026-09-11 — [the resolver's](experiments/2026-09-11-17-deterministic-date-resolution.md) |
| **Ruling** | **2026-09-22 — 98% on the measured scenario is the bar, and the three Receptionist rows tick against it.** The defect ships knowingly at 2%, which is what this section is for. Earlier: 2026-09-11 — phase 09's hallucination box **not ticked at today's rates**, and a fourth candidate authorised. That ruling is discharged; this one replaces it |
| **Status** | **Accepted and shipped knowingly.** The fifth candidate was accepted on 2026-09-17 — one prompt rule telling the model that a move searches from the *requested* date. Two fifty-trial arms, one day, one distance, 0 errored in both: correct landing **22% → 98%** (p = 1.4e-16), and the mechanism itself, *searched the requested day*, **18% → 100%** (p = 1.2e-19). Neither pre-registered veto fired. §8 of [the experiment record](experiments/2026-09-17-17-reschedule-searches-the-requested-date.md). **The issue stays open**, because 98% is not 100% and the rows ticking is a decision about the bar rather than a claim the defect is gone — which is the distinction this whole section exists to keep. **The named residual**: one trial never moved the appointment, the bound is 10.6%, and relative phrasing has zero trials |
| **Issue** | [#17](https://github.com/sanama-stack/reception-booking-system/issues/17) |

**This is what Functional boxes 9, 10 and 11 rest on**, and it is why all three were widened rather
than left to tick on wording written before the defect class was known. The customer-visible shape is
not a crash: ownership is proven, a real slot is returned by the real engine, a real appointment is
written, and the only thing wrong is the day — which is the one part nothing downstream can check.

### [#40] — the Receptionist refuses a legitimate move it was offered the slot for

| | |
|---|---|
| **Rate** | **≈1.3% of conversations unconditionally, and ≥22% at the decision point** — the two numbers divide by different things and the second is the one this issue is about. **Corrected 2026-09-22**, in [the scenario design](experiments/2026-09-22-40-mechanism-2-scenario.md) §3: conditioned on the Receptionist having searched the requested day at all, the 2026-09-17 baseline refused with the slot offered in **2 of 9** trials, CI [2.8%, 60.0%], against **0 of 50** on the shipped prompt, CI [0%, 7.1%] — Fisher one-sided p = 0.021. The unconditional figure below is kept because it is what a caller experiences; it is not the rate to design an arm against. Derived rather than measured directly: **2 of 23 pooled refusals** had the requested slot offered on the requested day (8.7% of refusals), and refusals ran at ~15% of trials. Across the three arms that is **2 observations in 150 conversations**. Two observations cannot characterise a distribution — the lesson this project has now paid for four times — so this is an order of magnitude, not a rate, and it is written here as one |
| **Worst case** | **Every observation is of a prompt this repository no longer ships.** Trials 38 and 40 are in the fifth candidate's **baseline** arm, one tree apart from rule 13. On the shipped prompt the candidate arm refused **0 of 50**, which bounds the refusal rate at **5.8%** and establishes nothing about this sub-mode specifically. Whether rule 13 touches it at all is **an open question with zero targeted trials** |
| **Measured** | 2026-09-17 — [the reopening comment](https://github.com/sanama-stack/reception-booking-system/issues/40#issuecomment-5625346280) and §8.5 of [the fifth candidate's record](experiments/2026-09-17-17-reschedule-searches-the-requested-date.md) |
| **Ruling** | **2026-09-22 — accepted, and shipped knowingly at an order of magnitude of ~1.3% of conversations.** The alternative considered and declined was buying a targeted arm now: at this rate fifty trials would see the defect about half the time, so the arm would most likely return a clean result that means nothing. **The scenario design comes before the budget**, which is this project's own lesson paid for four times. This entry now carries what every other one in this section carries |
| **Status** | Open as an accepted, measured defect, and **not** closed by [#17]'s fix. 21 of the 23 refusals descend from the wrong-day search and rule 13 removes them; these two do not, because the day was right, the slot was offered and the search was not truncated. ~~**What would move it** is a scenario where the wrong-day search *cannot* occur, so mechanism 2 can be counted alone.~~ **Superseded 2026-09-22 by [the scenario design](experiments/2026-09-22-40-mechanism-2-scenario.md) §2: both observations searched `booked + 1` *before* they searched the requested day, so a scenario that forbids the wrong-day search removes the only condition under which the defect has ever been seen.** The design that replaces it runs two arms — one that forbids it, one that induces the denial deliberately — and it names three instrument changes, none of which cost a credit, that must land first. **Mechanism 2 has two observations and not one word of what the Receptionist said**, because no harness has ever recorded the prose. **Every observation is of a prompt this repository no longer ships**, and on the shipped prompt the arm refused 0 of 50 |
| **Issue** | [#40](https://github.com/sanama-stack/reception-booking-system/issues/40) |

**Why this entry exists at all, stated against this section's own rule.** From 2026-09-17 to
2026-09-18 [#40] was in neither this section nor *Gates that cannot be run* — it had been reopened,
and the sentence below *Gates* saying it "does not yet qualify" was written when it was **one
trial**. It is now two observations in a hundred and fifty, which is a denominator. A defect in
neither place is *unnoticed*, which is the state this section exists to make impossible, and it had
been in that state for a day while the README was being corrected to name it.

### [#15] — a named weekday can resolve to the wrong row of the seven-day list

| | |
|---|---|
| **Rate** | **Not detected in 150 trials**, 2026-09-17, in the cell the old number was taken in — Thursday asking about Monday, **row 4 of seven**. 150/150, 0 errored, CI [97.6%, 100%], and the residual bounded at **[0%, 2.43%]**. This is a **bound, not an absence**: a true rate of 2% yields a clean 150 about one run in twenty |
| **Worst case** | **Six of the seven asking-days have never been measured.** The two observed modes — the first row, and the SATURDAY row — do not sit the same distance from row 4 as from row 1 or row 7, so nothing here transfers to the other cells. The superseded 142/150 = 94.7% was measured 2026-09-10, before `resolve_date` shipped, and describes a prompt this repository no longer ships |
| **Measured** | 2026-09-17 — [the re-baseline](experiments/2026-09-17-15-weekday-rate-rebaseline.md) §7, with `PROBE_ASKED_ON` and `make rebaseline-weekday` **refusing to run on any day but Thursday**, because a rate from another cell comes out in the same format and the same range. The first attempt was on a Tuesday and would have produced exactly that |
| **Ruling** | **2026-09-22 — *not detected at 150 trials in the cell customers actually hit* is the bar, and the issue is closed against it.** The question the issue had always deferred is answered. The alternative considered and declined was a second Thursday arm: it costs another 150 live conversations and would tighten only the same cell, leaving the six unmeasured ones exactly as they are. No cause is attributed — several prompt changes landed between the arms and this was a re-baseline, not a candidate arm |
| **Status** | **Closed 2026-09-22.** The entry is kept rather than deleted, because what it records is a **bound and not an absence** and a reader deciding whether to trust a weekday resolution should see the shape of the evidence rather than a closed issue number. **What the closure does not claim**: that the defect is gone. A true rate of 2% yields a clean 150 about one run in twenty, the residual is bounded at [0%, 2.43%], and six of the seven asking-days have never been measured. **What would reopen it** is an observation in any cell |
| **Issue** | [#15](https://github.com/sanama-stack/reception-booking-system/issues/15) |

**This entry replaces a sentence that had expired under it.** From 2026-09-11 this document said
[#15] was *"not listed as accepted here, because its title still names a diagnosis a later session
disproved and it therefore has no trustworthy rate to carry"*, and that fixing the title was a
prerequisite. **The title had already been corrected the day before that sentence was written**, on
2026-09-10, and was corrected again on 2026-09-16 to carry its own limits. The prerequisite was met
twice and the sentence was never re-read — which is the same failure as [#40] sitting in neither
section, and as the demo script being carried as credit-blocked for three days after the credits
returned. A carried claim reads as settled; the condition it names is what has to be re-checked.

*Title sharpened again on 2026-09-19*: it said **undetectable** in row 4, which reads as a property
of the defect, where what was established is a property of the sample. It now says **not detected in
150 trials**. The distinction is the whole of this issue's own correction notice, one level down.

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
| **Last ran** | **2026-09-16** — **12 of 12**. Before it, 2026-09-15 — **11 of 12**, the failure being [#40]. Before that, 2026-09-10, green — [the session record](sessions/2026-09-10-the-gap-that-was-hiding-a-wrong-write.md) |
| **Why it could not run** | The OpenAI account had no credits: `429`, `insufficient_quota`, `credit_balance_exhausted`, confirmed 2026-09-13. **Credits were restored on 2026-09-15 and the gate was run the same day** |
| **Result** | One failure across two runs: `a customer who proves the appointment is theirs can move it`, the Receptionist declining an authorised move to a free slot. Filed as [#40], measured over two fifty-trial arms, and closed into [#17] on 2026-09-16 — the refusal read as #17's wrong-day search taking a different branch. **Reopened 2026-09-17, and that closure is retracted**: a third arm recorded `target searched` and found two refusals where the day *was* right and the slot *was* offered. 21 of 23 refusals are #17; two are not. It now has its own entry under *Accepted, measured, open defects* above |
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
>
> **Superseded 2026-09-18 — the instrument was built and [#40] is now filed above.** The paragraph
> is kept because its reasoning was right and is the reason the entry above states an order of
> magnitude rather than a rate. What changed is the denominator: one trial became **two observations
> in a hundred and fifty**, across three arms, once `target searched` was recorded per refusal.
> **The gap it left is the lesson.** Between the reopening on 2026-09-17 and this entry, [#40] sat
> in neither section — which this document defines as *unnoticed* — because "does not yet qualify"
> was written of one trial and never re-read after the third arm made it false.

**What this does and does not change.** The three Receptionist rows in *Functional* stay unticked —
but for a materially better reason than before. They were unverifiable; box *"It never states a slot,
price or policy that did not come from a tool"* now has a **concrete counter-example on a transcript**
rather than an absent instrument. "Cannot be checked" has become "checked, and reachable".

⚠️ **One green run does not tick these boxes, and the second corpus run was green.** The corpus runs
each case once, so it can show a behaviour is reachable and can never show it is gone — the trap
[#15](https://github.com/sanama-stack/reception-booking-system/issues/15) was re-opened for, one
level up.

**What the corpus did here is exactly what it is for, and what it cannot do is exactly why the rate
harness exists.** It made a defect reachable in one transcript; two fifty-trial arms then established
that the defect was [#17] wearing a different face, and [#40] closed into it — **a closure a third
arm then retracted**, 21 of its 23 refusals being #17 and two being their own defect. **These rows
tick when [#17]'s `date_from` is fixed and measured**, not when a single-shot corpus comes back
green — and on the scenario those arms used, [#17] is wrong **38 of 48 trials, 79.2%**, CI [65.0%,
89.5%]. *Since 2026-09-17 it is fixed and measured at 98% on one phrasing at one distance, and the
rows still do not tick: see [#17]'s entry above for the three limits that keep them open.*

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

