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

### Functional
- [ ] A new owner registers and lands on a dashboard with an onboarding checklist
- [ ] The owner configures profile, timezone, business hours and booking settings
- [ ] The owner creates services with distinct durations and prices
- [ ] The owner creates employees with *different* working schedules and assigns services to them
- [ ] The owner records a business closure and an employee's time off
- [ ] `/book/{slug}` is publicly reachable and shows the business, services, prices and durations
- [ ] A customer completes a booking through the Classic Flow
- [ ] A customer completes a booking by conversation with the Receptionist
- [ ] The Receptionist answers a business question using only configured information, and says it does not know when the information is absent
- [ ] The Receptionist offers only slots returned by the availability engine
- [ ] The Receptionist reschedules and cancels an appointment after the customer proves ownership
- [ ] Confirmation and reminder emails arrive in Mailpit with a working Manage Link
- [ ] The appointment appears in the owner's dashboard list and calendar, marked as AI-sourced
- [ ] The owner changes an appointment to `COMPLETED` and to `NO_SHOW`
- [ ] The analytics summary reports counts, revenue from completed appointments, and top services

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
