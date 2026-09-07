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

None required. Mailpit's own UI at `:8025` is the demo surface, and a link to it in the README is more
useful than a screen.

## Testing

### Unit
- [ ] Reminder scheduled at `starts_at − 24h`
- [ ] No reminder when the appointment is less than 24 h away
- [ ] Manage token round-trips; a tampered token fails; an expired token fails
- [ ] Backoff schedule is correct per attempt count
- [ ] Templates render the code, times in the business timezone, and the link

### Integration
- [ ] Booking enqueues exactly two rows in the booking transaction
- [ ] A rolled-back booking enqueues nothing
- [ ] The poller sends due rows and marks them `SENT` with `sent_at`
- [ ] The poller ignores rows scheduled in the future
- [ ] A send failure increments `attempts` and records `last_error`
- [ ] `FAILED` after 5 attempts; other rows still send
- [ ] Cancellation cancels pending rows and enqueues a cancellation email
- [ ] Reschedule cancels the old reminder and enqueues a new one
- [ ] Duplicate enqueue is rejected by the partial unique index
- [ ] Two concurrent pollers never send the same row twice
- [ ] Booking without a customer email enqueues nothing and still succeeds
- [ ] Emails are retrievable from Mailpit's API in the local profile

## Definition of Done

- [ ] A booking produces a confirmation email in Mailpit containing the code and a valid Manage Link
- [ ] A reminder is scheduled correctly and skipped when it would be in the past
- [ ] Cancel and reschedule produce the right emails and cancel the right pending rows
- [ ] Failures retry with backoff and cap at `FAILED` without blocking the queue
- [ ] Concurrent pollers cannot double-send
- [ ] Manage tokens verify, expire and resist tampering
- [ ] All tests above pass

## Checklist

### Database
- [ ] `V6__notifications.sql`
- [ ] Partial index on `(status, scheduled_for)`
- [ ] Partial unique index on `(appointment_id, type)`

### Backend
- [ ] `Notification` entity and repository
- [ ] `SKIP LOCKED` native claim query
- [ ] `EmailSender` port
- [ ] `SmtpEmailSender` adapter
- [ ] `NotificationEnqueuer` called inside the three transactions
- [ ] `NotificationPoller` with batch, backoff and failure cap
- [ ] Four email templates (HTML + text)
- [ ] `EmailTemplateRenderer` using business-timezone formatting
- [ ] `ManageTokenService` with constant-time verification
- [ ] Configuration and the test-profile poller kill switch
- [ ] Redact manage tokens and codes from logs

### Infrastructure
- [ ] Mailpit service in compose, UI on `:8025`
- [ ] SMTP variables in `.env.example`
- [ ] README note pointing at the Mailpit UI

### Testing
- [ ] All unit tests above
- [ ] All integration tests above
- [ ] Concurrent-poller test
