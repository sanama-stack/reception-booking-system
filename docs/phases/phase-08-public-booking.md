# Phase 08 — Public Booking (Classic Flow)

## Goal

A stranger with a link books an appointment without an account, receives a confirmation email, and can
manage that appointment from the link inside it — all through endpoints the Receptionist will later call.

## Scope

**In:** public read endpoints, public booking endpoint, appointment lookup, manage-token endpoints, public
cancel/reschedule, the `/book/{slug}` page with the Classic Flow, the `/manage/{token}` page, public rate
limiting.

**Out:** the Receptionist (phase 09). The chat panel's *space* is laid out here; the chat itself is not.

## Dependencies

Phase 07 — the confirmation email and Manage Link must already work.

## Progress

**Phase 08 is complete** (2026-09-09). The backend half, `/book/{slug}` and `/manage/{token}` are
all built and browser-verified — see [the booking page's
handoff](../sessions/2026-09-09-phase-08-booking-page.md) and [the Manage Link page's
handoff](../sessions/2026-09-09-phase-08-manage-page.md).

One box below stays unticked and is **not** an oversight: "Any available" with more than one
eligible Employee has still never been **rendered**. As of 2026-09-10 it is no longer unexercised —
`PublicBookingTest` builds the two-Employee fixture and asserts the grid's behaviour through the
public API — but nobody has seen the page draw it, and that is what the box asks.

**How the Frontend testing boxes were satisfied, because it is not what the heading implies.** This
repository has no frontend test harness — `frontend/package.json` carries no `test` script and no
test dependency, and the whole frontend gate is `lint`, `typecheck`, `format:check`, `build`. The
boxes under *Testing → Frontend* were therefore ticked by driving a real browser against a live
stack, and **are not enforced by CI**. A regression in the booking page will not be caught by the
pipeline. Standing a harness up is phase-11 work and is deliberately not in this phase's scope.

Two things were added beyond the plan below, both recorded in
[the session handoff](../sessions/2026-09-09-phase-08-backend.md):

- **`GET /public/appointments/manage/availability`.** A rescheduling Customer needs a grid that excludes
  their own appointment, and the public availability endpoint must not take an appointment id from an
  anonymous caller — the difference between the two answers would say whether that appointment exists.
- **`GET /availability` gained `excludeAppointmentId`**, which is safe there because the endpoint is
  authenticated and tenant-scoped.

## Why the Classic Flow comes before the AI

It proves the entire public API surface is correct **before** a nondeterministic layer sits on top. Built in
the other order, an AI misbehaviour and an API bug are indistinguishable. It is also the permanent fallback
for every AI failure mode in [05-ai-architecture.md](../05-ai-architecture.md) §8 — which is only credible
if it is genuinely complete.

## Technical work

### The public module

Public controllers live in their own module (`publicapi`), so "what is reachable without authentication" is
a directory you can read rather than a property inferred from annotations.

### Response minimisation

Public DTOs are written by hand, never mapped from entities. Absent by construction: employee email and
phone, internal settings, cost caps, other customers, and any id not needed to complete a booking. A test
asserts public responses contain no field outside an allow-list — mapping an entity by accident is the
easiest way to leak, so it is tested rather than trusted.

### Tenant resolution from the slug

`TenantContext` is populated from the slug for public requests. Same seam, different source. An unknown
slug is `404` before any other work happens.

### Authority for cancel and reschedule

Two accepted proofs, converging on the same authorisation concept:
1. A valid **Manage Link** token for that appointment
2. A successful **lookup** with Confirmation Code **and** phone number

`POST /public/appointments/lookup` is a `POST` because a phone number must never enter a URL, a log line or
a referrer header. It is the most aggressively rate-limited endpoint in the system (5/hour/IP): it is what
an attacker would brute-force.

The customer cancellation window applies here; the dashboard path remains exempt.

### The 409 experience

A slot can be taken between rendering the grid and submitting. The page must refresh availability, explain
plainly, and keep the customer's entered details. A silent failure or a raw error is a defect.

## Database work

None. Existing tables and services are reused, which is itself the demonstration that the public surface
adds no privileged path.

## Backend work

- `PublicBusinessController` — profile, services, employees
- `PublicAvailabilityController` — the phase 05 engine, unchanged
- `PublicBookingController` — create with `source = CLASSIC`
- `PublicAppointmentController` — lookup, manage-token resolve, cancel, reschedule
- Public DTOs, hand-written
- Slug-based tenant resolution filter
- `RateLimitFilter` (Bucket4j) with the per-endpoint limits from [06-security.md](../06-security.md) §5
- `429` responses carrying `Retry-After`

## Frontend work

- `/book/[slug]` — server-rendered: business name, description, address, hours, services with duration and
  price; a two-column layout reserving the chat panel for phase 09
- Classic Flow: service → *Any available* or a named employee → date → slot grid → name/phone/email →
  confirm
- Confirmation screen showing the Confirmation Code prominently, with a note that an email is on its way
- `/manage/[token]` — appointment summary with cancel and reschedule
- Cancellation-window refusal rendered as the business's policy text, not a raw error
- Unknown slug → a designed `404`
- A business with no bookable service → an explanatory page, not an empty grid
- **Mobile first**: fully usable at 360 px; the slot grid must be thumb-friendly

## Testing

### Integration
- [x] Public profile, services and employees return only allow-listed fields
- [x] Public availability matches the internal endpoint exactly
- [x] Booking succeeds with `source = CLASSIC` and enqueues the confirmation email
- [x] Booking a taken slot → `409 SLOT_UNAVAILABLE`
- [x] Inactive service, inactive employee and unassigned employee are each rejected with their own code
- [x] Lookup with correct code **and** phone succeeds
- [x] Correct code, wrong phone → `INVALID_CONFIRMATION_CODE`
- [x] Phone only → schema rejection
- [x] Manage token resolves the right appointment; a tampered or expired token → `401`
- [x] A manage token for appointment A cannot act on appointment B
- [x] Customer cancel inside the window → `422 CANCELLATION_WINDOW_CLOSED`
- [x] Customer cancel outside the window succeeds and sends the email
- [x] Public reschedule validates availability and updates in place
- [x] Rate limits fire and return `429` with `Retry-After`
- [x] Lookup limit is 5/hour/IP
- [x] An unknown slug → `404` everywhere
- [x] **No public endpoint returns another business's data**

### Frontend
- [x] Classic Flow completes end to end
- [x] `409` refreshes the grid and preserves entered details
- [ ] "Any available" shows a specific employee before the slot is chosen — **the branch is now
      exercised, but not on a screen.** The fixture that did not exist now does:
      `PublicBookingTest.secondStylist()` puts two Employees on one Service, and two cases assert
      what the grid does with them — each start offered once, naming somebody specific, and a start
      whose preferred Employee is busy offered as the other one rather than disappearing. That is
      the engine and the wire. **This box is under *Frontend testing* and stays unticked because no
      person has looked at the rendered page with two eligible Employees** — doing so needs a second
      stylist in a real tenant, and the verification tenant was deliberately not altered for it.
      **Decided 2026-09-11: verify it rather than strike it**, and it costs nothing to wait — phase
      11's `make seed` builds Salon Aria with three Employees on differing schedules and one Service
      deliberately unassigned from one of them, which *is* the fixture this box has been missing.
      Tick it by looking at that grid, not by standing up a throwaway tenant
- [x] The page is usable at 360 px

## Definition of Done

- [x] `/book/{slug}` is publicly reachable and shows business, services, prices and durations
- [x] A stranger books end to end with no account
- [x] The confirmation email arrives with a working Manage Link
- [x] The Manage Link page cancels and reschedules
- [x] Lookup requires code **and** phone
- [x] Every public endpoint is rate limited
- [x] No internal or cross-tenant field appears in any public response
- [x] All tests above pass — with the one "Any available" box above named as unexercised rather
      than passing

## Checklist

### Backend
- [x] `publicapi` module with slug-based tenant resolution
- [x] `PublicBusinessController`
- [x] `PublicAvailabilityController`
- [x] `PublicBookingController` (`source = CLASSIC`)
- [x] `PublicAppointmentController` — lookup, manage, cancel, reschedule
- [x] Hand-written public DTOs
- [x] Allow-list test for public response fields
- [x] `RateLimitFilter` with per-endpoint buckets
- [x] `429` + `Retry-After`
- [x] Error codes: `INVALID_CONFIRMATION_CODE`, `MANAGE_TOKEN_INVALID`

### Frontend
- [x] `/book/[slug]` server-rendered page with the chat column reserved
- [x] Service selector
- [x] Employee selector with "Any available" (built; unverified with more than one eligible Employee)
- [x] Date picker and slot grid
- [x] Customer details form
- [x] Confirmation screen with the code
- [x] `/manage/[token]` page with cancel and reschedule
- [x] Policy-aware refusal messaging
- [x] Designed `404` and "not accepting bookings" pages
- [x] Mobile layout verified at 360 px
- [x] Empty, loading and error states throughout

### Testing
- [x] All integration tests above
- [x] Public field allow-list test
- [x] Rate-limit tests
- [x] Mobile-viewport check
