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
- Eight tools; the model can do nothing else
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
> open defects* with a rate, a date and an issue. There is no third state.** At the time of the audit
> none of the thirty had ever been ticked, and the list had survived ten phases unread.

### Functional
- [ ] A new owner registers and lands on a dashboard with an onboarding checklist
- [ ] The owner configures profile, timezone, business hours and booking settings
- [ ] The owner creates services with distinct durations and prices
- [ ] The owner creates employees with *different* working schedules and assigns services to them
- [ ] The owner records a business closure and an employee's time off
- [ ] `/book/{slug}` is publicly reachable and shows the business, services, prices and durations
- [ ] A customer completes a booking through the Classic Flow
- [ ] A customer completes a booking by conversation with the Receptionist
- [ ] The Receptionist answers a business question using only configured information, and says it does
      not know when the information is absent — **and states no slot, price or policy that did not come
      from a tool.** *Widened 2026-09-11.* The original reaches answers to questions; [#17] is an
      **unprompted** assertion made mid-reschedule — *"the earliest I can reschedule your appointment
      for is tomorrow"* — which is false, came from no tool, and the original wording does not cover.
      That clause is phase 09's own Definition-of-Done box, which the principal ruled **not ticked at
      today's rates**
- [ ] The Receptionist offers only slots returned by the availability engine — **for the date the
      customer actually named.** *Widened 2026-09-11.* Under [#17] every offered slot does come from the
      engine; the engine was asked about the wrong date. The original ticks on that, which is exactly
      why it is no longer the whole test
- [ ] The Receptionist reschedules and cancels an appointment after the customer proves ownership —
      **and the write lands on the date the customer named.** *Widened 2026-09-11.* Ownership proof was
      never what [#17] breaks: the writes that land on a date nobody said had ownership correctly proven
      first, which is what makes a wrong one indistinguishable from a right one
- [ ] Confirmation and reminder emails arrive in Mailpit with a working Manage Link — **or the response
      says one was not sent, per [ADR-0007](adr/0007-booking-response-says-whether-a-confirmation-was-sent.md).**
      *Widened 2026-09-11.* A booking with no address on file sends nothing, by design; a flat "arrive"
      makes the correct behaviour look like a failed box
- [ ] The appointment appears in the owner's dashboard list and calendar, marked as AI-sourced
- [ ] The owner changes an appointment to `COMPLETED` and to `NO_SHOW`
- [ ] The analytics summary reports counts, revenue from completed appointments, and top services —
      **and names its excluded remainder, per [ADR-0010](adr/0010-revenue-reports-one-currency-and-names-the-remainder.md).**
      *Widened 2026-09-11.* Revenue is filtered to one currency, so a business that switched sees a
      number smaller than its real revenue; ADR-0010 decided the remainder is reported, not dropped

### Correctness
- [ ] Availability excludes: past slots, slots inside the lead time, slots beyond the horizon, slots overlapping existing appointments or buffers, closures, time off, and any slot the service duration does not fully fit inside
- [ ] Two concurrent bookings of the same slot result in exactly one appointment and one `409`
- [ ] A booking that crosses a DST transition produces correct local times
- [ ] A customer cancelling inside the cancellation window is refused; the owner cancelling is not

### Isolation and security
- [ ] Every tenant-scoped endpoint returns `404` when given another tenant's resource id
- [ ] No endpoint accepts `business_id` from the client
- [ ] No AI tool accepts `business_id` from the model
- [ ] Appointment lookup requires Confirmation Code + phone; a phone number alone reveals nothing
- [ ] Public endpoints are rate limited; exceeding the limit returns `429`

### Engineering
- [ ] `make up` brings the whole system up from a clean checkout
- [ ] Seed data creates two businesses in different verticals
- [ ] Every phase's tests pass in CI, including the concurrency test and the tenant-isolation suite
- [ ] One Playwright E2E run covers chat → booking → dashboard
- [ ] `.env.example` documents every configuration variable
- [ ] `README.md` contains a demo script a stranger can follow

---

## Accepted, measured, open defects

A box above is ticked against evidence, or the defect it covers is named here with a **rate**, a
**date** and an **issue**. A defect that is in neither place is not accepted — it is unnoticed, which
is the state this section exists to make impossible.

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
