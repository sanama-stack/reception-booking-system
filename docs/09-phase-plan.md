# 09 — Phase Plan and Dependency Graph

Eleven phases. Each is independently understandable, ends in a demonstrable state, and carries its own
tests.

## 1. Where this departs from the original outline

Three deliberate changes to the sequence proposed in the brief (§36), each with a reason:

| Change | Reason |
|---|---|
| **Booking split into two phases** (5 availability, 6 appointments) | A read-only pure computation and a concurrent write path have different risk profiles and different Definitions of Done. Bundled, neither gets a DoD you can actually fail |
| **Notifications moved from 8th to 7th — before public booking and before the AI** | The brief's order contains a real dependency bug: the Receptionist's cancel and reschedule tools require a Confirmation Code, which reaches the customer **by email**. Ordered as proposed, the AI phase could not be exercised end to end |
| **Dashboard distributed across phases** rather than concentrated at 9 | Otherwise phases 3–6 have no way to exercise anything by hand, and phase 9 becomes larger than any other phase. Each backend phase ships its own screen; the late phase keeps only the calendar, analytics and polish |

One ordering choice worth stating explicitly: **the Classic Flow ships before the Receptionist.** It proves
the public API surface is correct before a nondeterministic layer sits on top of it. Built in the other
order, an AI misbehaviour and an API bug are indistinguishable.

## 2. Dependency graph

```text
              ┌─────────────────────────────────┐
              │ 01  Foundation                  │  compose, Caddy, Flyway, CI
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 02  Authentication & Tenancy    │  users, memberships, TenantContext
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 03  Business Setup              │  profile, hours, closures, FAQs
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 04  Services & Employees        │  catalog, staff, schedules, time off
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 05  Availability Engine         │  pure, read-only, DST-correct
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 06  Appointments                │  write path, EXCLUDE constraint, 409
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 07  Notifications               │  outbox, poller, Confirmation Code email
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 08  Public Booking (Classic)    │  /book/{slug}, Manage Link
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 09  AI Receptionist             │  tools, orchestration, safety
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 10  Calendar, Analytics, Polish │
              └────────────────┬────────────────┘
                               ▼
              ┌─────────────────────────────────┐
              │ 11  Hardening & Deployment      │  limits, isolation suite, E2E, docs
              └─────────────────────────────────┘
```

The chain is almost entirely linear, which is honest: this system has one long spine and very little that
can genuinely proceed in parallel. The exceptions are noted per phase.

## 3. Why each dependency exists

| Phase | Cannot start until | Because |
|---|---|---|
| 02 | 01 | Needs migrations, compose and the single-origin proxy for cookies |
| 03 | 02 | Business is created by registration; every endpoint is tenant-scoped |
| 04 | 03 | Services and employees are tenant-owned; schedules intersect business hours |
| 05 | 04 | The engine consumes hours, schedules, services, assignments and time off |
| 06 | 05 | Booking validates against availability before writing |
| 07 | 06 | Notifications reference an appointment and its Confirmation Code |
| 08 | 07 | The public flow's confirmation email and Manage Link must already work |
| 09 | 08 | Every AI tool calls an application service the public flow already proved |
| 10 | 06 (calendar), 06 (analytics) | Needs appointments; scheduled after 09 so AI-sourced appointments are visible in it |
| 11 | all | Hardens what exists |

## 4. Phase summary

| # | Phase | Ships | Demo at the end |
|---|---|---|---|
| 01 | [Foundation](./phases/phase-01-foundation.md) | Compose, Caddy, Flyway, CI, health | `make up` → both apps through one origin |
| 02 | [Authentication & Tenancy](./phases/phase-02-authentication.md) | Register, login, refresh, TenantContext | Register → land on an empty dashboard |
| 03 | [Business Setup](./phases/phase-03-business-setup.md) | Profile, hours, closures, FAQs, onboarding | Configure a business fully |
| 04 | [Services & Employees](./phases/phase-04-services-and-employees.md) | Catalog, staff, schedules, time off | Two employees, different schedules |
| 05 | [Availability Engine](./phases/phase-05-availability-engine.md) | The pure engine + read endpoint | Ask for slots, get correct answers |
| 06 | [Appointments](./phases/phase-06-appointments.md) | Booking, cancel, reschedule, status | Book from the dashboard; race it and win once |
| 07 | [Notifications](./phases/phase-07-notifications.md) | Outbox, poller, templates, Manage Link | Emails land in Mailpit |
| 08 | [Public Booking](./phases/phase-08-public-booking.md) | `/book/{slug}`, Classic Flow, manage page | A stranger books without logging in |
| 09 | [AI Receptionist](./phases/phase-09-ai-receptionist.md) | Tools, loop, safety, chat UI | Book by conversation |
| 10 | [Dashboard & Analytics](./phases/phase-10-dashboard-and-analytics.md) | Calendar, analytics, transcripts, polish | Run the business from one screen |
| 11 | [Hardening & Deployment](./phases/phase-11-hardening-and-deployment.md) | Limits, isolation suite, E2E, seed, docs | Clean clone → full demo |

## 5. Rules that apply to every phase

1. **Migrations are additive.** Each phase owns its migration; no phase edits an earlier one.
2. **Tests ship with the phase.** A phase is not done when the code works; it is done when the tests in its
   checklist pass.
3. **Vocabulary is binding.** Class, table and endpoint names follow [../CONTEXT.md](../CONTEXT.md).
4. **No business logic in controllers.** Reviewed per phase.
5. **Every new tenant-scoped endpoint adds a probe** to the isolation suite in the same commit.
6. **`Instant.now()` is banned** outside the `Clock` bean.
7. **Every screen ships with empty, loading and error states.** They are not phase-11 polish.
8. A phase that grows past its scope splits, rather than absorbing the next phase's work.
