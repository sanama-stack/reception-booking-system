# Phase 07 — Notifications

## Goal

Booking, cancelling and rescheduling produce real emails, delivered reliably by a database outbox, each
carrying the Confirmation Code and a working Manage Link.

## Scope

**In:** `notifications` outbox, the scheduled poller, `EmailSender` port + SMTP adapter, four email
templates, Manage Link token issuing and verification, Mailpit in compose.

**Out:** the public page that consumes the Manage Link (phase 08), SMS and other channels (out of MVP).

## Dependencies

Phase 06 — notifications reference an appointment and its Confirmation Code.

## Why this precedes public booking and the AI

The brief sequenced notifications after the Receptionist. That ordering has a real dependency bug: the AI's
cancel and reschedule tools require a Confirmation Code, and the code reaches the customer **by email**.
Without this phase first, phase 09 cannot be exercised end to end. The confirmation email is part of what
"an appointment was made" means, not a later garnish.

## Technical work

### Outbox, not a queue

Rows are written **in the same transaction as the state change**. Either the appointment and its
notifications commit together, or neither does. A message broker cannot offer that without a distributed
transaction or an outbox anyway — so the outbox is the whole solution rather than half of one.
See [ADR-0005](../adr/0005-database-outbox-instead-of-queue.md).

### The poller

```sql
SELECT * FROM notifications
 WHERE status = 'PENDING' AND scheduled_for <= now()
 ORDER BY scheduled_for
 LIMIT 50
 FOR UPDATE SKIP LOCKED;
```

`FOR UPDATE SKIP LOCKED` makes the poller multi-instance-safe for free: two instances take disjoint batches
and neither blocks. Runs every 60 seconds via `@Scheduled`.

Retry with exponential backoff on failure; `FAILED` after 5 attempts, recording `last_error`. A failing row
never blocks the others.

### Rendering at enqueue time

Subject and body are rendered **when the row is created**, not when it is sent. A template change therefore
cannot alter a pending message, and the stored row is an exact record of what the customer received.

### Lifecycle rules

| Event | Notification effect |
|---|---|
| Booking | `BOOKING_CONFIRMATION` immediately + `REMINDER_24H` at `starts_at − 24h` |
| Booking less than 24 h ahead | Confirmation only — a reminder in the past is not scheduled |
| Cancellation | Pending rows → `CANCELLED`; a `CANCELLATION` email enqueued |
| Reschedule | Old reminder cancelled; new reminder enqueued; `RESCHEDULE` email sent |
| No customer email | Nothing enqueued; the code is shown on screen; the appointment remains valid |

The partial unique index on `(appointment_id, type)` for non-cancelled rows makes duplicate confirmations
and reminders impossible even if enqueue logic runs twice.

### Manage Link

`HMAC-SHA256(appointmentId + "|" + expiryEpoch, MANAGE_LINK_SECRET)`, base64url-encoded with the payload.
Expires 24 hours after the appointment ends. A single-purpose capability token: it authorises exactly one
appointment and grants nothing else. Excluded from logs and never echoed in errors.

## Database work

`V6__notifications.sql`:
- `notifications` — full column set from [03-data-model.md](../03-data-model.md)
- `CREATE INDEX ON notifications (status, scheduled_for) WHERE status = 'PENDING'`
- `CREATE UNIQUE INDEX ON notifications (appointment_id, type) WHERE status IN ('PENDING','SENT')`

## Backend work

- `Notification` entity and repository with the `SKIP LOCKED` native query
- `EmailSender` port; `SmtpEmailSender` adapter over `JavaMailSender`
- `NotificationEnqueuer` — called from within booking, cancel and reschedule transactions
- `NotificationPoller` — `@Scheduled(fixedDelay = 60_000)`, batch of 50, backoff, failure cap
- `EmailTemplateRenderer` — four templates, HTML + plain text
- `ManageTokenService` — issue and verify, constant-time comparison
- Configuration: SMTP host/port/credentials, `MANAGE_LINK_SECRET`, poller enable flag (off in tests)

## Frontend work

None required. Mailpit's own UI at `:9083` is the demo surface, and a link to it in the README is more
useful than a screen.

## Testing

### Unit
- [x] Reminder scheduled at `starts_at − 24h` *(integration — see §1 of the notes)*
- [x] No reminder when the appointment is less than 24 h away *(integration — see §1)*
- [x] Manage token round-trips; a tampered token fails; an expired token fails
- [x] Backoff schedule is correct per attempt count
- [x] Templates render the code, times in the business timezone, and the link

### Integration
- [x] Booking enqueues exactly two rows in the booking transaction
- [x] A rolled-back booking enqueues nothing
- [x] The poller sends due rows and marks them `SENT` with `sent_at`
- [x] The poller ignores rows scheduled in the future
- [x] A send failure increments `attempts` and records `last_error`
- [x] `FAILED` after 5 attempts; other rows still send
- [x] Cancellation cancels pending rows and enqueues a cancellation email
- [x] Reschedule cancels the old reminder and enqueues a new one
- [x] Duplicate enqueue is rejected by the partial unique index
- [x] Two concurrent pollers never send the same row twice
- [x] Booking without a customer email enqueues nothing and still succeeds
- [x] Emails are retrievable from Mailpit's API in the local profile

## Definition of Done

- [x] A booking produces a confirmation email in Mailpit containing the code and a valid Manage Link
- [x] A reminder is scheduled correctly and skipped when it would be in the past
- [x] Cancel and reschedule produce the right emails and cancel the right pending rows
- [x] Failures retry with backoff and cap at `FAILED` without blocking the queue
- [x] Concurrent pollers cannot double-send
- [x] Manage tokens verify, expire and resist tampering
- [x] All tests above pass

## Checklist

### Database
- [x] `V6__notifications.sql`
- [x] Partial index on `(status, scheduled_for)`
- [x] Partial unique index on `(appointment_id, type)`

### Backend
- [x] `Notification` entity and repository
- [x] `SKIP LOCKED` native claim query
- [x] `EmailSender` port
- [x] `SmtpEmailSender` adapter
- [x] `NotificationEnqueuer` called inside the three transactions
- [x] `NotificationPoller` with batch, backoff and failure cap
- [x] Four email templates (HTML + text)
- [x] `EmailTemplateRenderer` using business-timezone formatting
- [x] `ManageTokenService` with constant-time verification
- [x] Configuration and the test-profile poller kill switch
- [x] Redact manage tokens and codes from logs

### Infrastructure
- [x] Mailpit service in compose, UI on `:9083` (`8025` inside the container)
- [x] SMTP variables in `.env.example`
- [x] README note pointing at the Mailpit UI

### Testing
- [x] All unit tests above
- [x] All integration tests above
- [x] Concurrent-poller test

---

## Notes from the build

Written while building this phase. The checklist above says what was done; these say what had to be
decided, and what a later phase inherits.

### 1. Two "unit" boxes are ticked by integration tests, deliberately

The reminder's schedule is a rule about an Appointment's `starts_at` and the Clock, and the honest
place to assert it is against a real booking: `NotificationEnqueueTest` reads `scheduled_for`
straight out of the column and asserts the gap is exactly 86 400 seconds. A unit test would have had
to construct an `Appointment` by hand, and it would have gone on passing if the enqueuer stopped
being called at all.

The rest of the unit list is genuinely unit-level and runs with no container:
`RetryBackoffTest`, `ManageTokenServiceTest` and `EmailTemplateRendererTest` — 27 tests in
about a second.

### 2. The partial unique index has a type predicate the data model did not specify

`docs/03-data-model.md` writes the index over every type. That also forbids the second
`CANCELLATION` or `RESCHEDULE` row — and a customer who moves an appointment twice is owed two
emails, which this document requires two sections above it. The two statements cannot both hold.

The prose in both documents says what the index is *for*: it makes "duplicate confirmations and
reminders impossible even if enqueue logic runs twice". Those are the two types owed once per
appointment for its whole life. A cancellation and a reschedule are events, and an event that
happens twice is two facts rather than one fact repeated.

Nor could the application have worked around a wider index: superseding the previous `RESCHEDULE`
row would mean setting a `SENT` row to `CANCELLED`, which is a lie and a violation of
`notifications_sent_fields`.

**`V6__notifications.sql` carries the full argument**, and `NotificationEnqueueTest` has a test named
for it — *rescheduling twice sends two reschedule emails*. **`docs/03-data-model.md` still shows the
original and should be corrected.**

### 3. `scheduled_for` means "next due", not "first owed"

The poller's claim query is exactly the one this document specifies — `status = 'PENDING' AND
scheduled_for <= now()` — which leaves only one place for the backoff to live: a failed attempt
pushes `scheduled_for` forward by `RetryBackoff.delayAfter(attempts)`. The alternative was a second
`next_attempt_at` column that the query would have to agree with, and two columns that can disagree
about when a row is due is one more than the problem needs.

The cost is that a row no longer records when it was *first* owed. That is recoverable from
`created_at`, the type, and the appointment's `starts_at`.

### 4. Two repositories over one table

`NotificationRepository` is `@TenantScoped` like every other repository in the application.
`NotificationClaimRepository` holds the poller's claim, which is the one query here that must cross
tenants — the poller is the system delivering mail for every Business at once.

They are separate files rather than one repository with an exception in it, because
`TenantRepositoryShapeTest` enforces the rule by *name prefix*: a cross-tenant method on a
`@TenantScoped` repository either fails that test or teaches the next reader that the rule has
exceptions. Here the exception is the entire purpose of the type and is stated in its name.

### 5. Delivery is at-least-once, and the code says so

One transaction per batch, with `FOR UPDATE SKIP LOCKED` holding the claim for its duration. If the
process dies between the transport accepting a message and the transaction committing, the row is
still `PENDING` and the next poll sends it again. Exactly-once would need the mail server to enlist
in the transaction, which no mail server does — and duplicating a confirmation is the better failure
to choose over losing one.

`ConcurrentPollerTest` proves the property that *is* guaranteed: two pollers draining one queue
never send the same row twice. It is repeated three times, for the same reason
`ConcurrentBookingTest` is.

### 6. What phase 08 inherits

- **The Manage Link is issued and verified; nothing consumes it.** `ManageTokenService.verify`
  returns the Appointment id or empty, and every failure — malformed, tampered, expired, foreign
  secret — returns the same empty. The page at `/manage/{token}` is phase 08's.
- **`Actor.system()` still has no caller.** The poller sends mail; it does not transition
  appointments, so nothing yet writes `SYSTEM` to `actor_type`. Phase 08's no-show sweep, or
  phase 10's, will be the first.
- **The `phone` / `customerPhone` field-name mismatch is untouched**, and still phase 08's to decide
  for both clients.
- **`GET /availability` still cannot exclude an appointment being rescheduled** — unchanged, and the
  reason the reschedule screen says so out loud.
