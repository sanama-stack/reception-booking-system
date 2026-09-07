# Phase 06 — Appointments

## Goal

Appointments can be created, cancelled, rescheduled and progressed through their lifecycle — with double
booking made **structurally impossible** by the database, proven by a concurrency test.

## Scope

**In:** `customers`, `appointments` with the exclusion constraint, `appointment_events`, booking /
cancel / reschedule / status services, dashboard booking and list screens.

**Out:** notifications (phase 07), public booking (phase 08), AI (phase 09), calendar view (phase 10).

## Dependencies

Phase 05 — booking validates against the availability engine before writing.

## Technical work

### The constraint is the correctness mechanism

```sql
ALTER TABLE appointments ADD CONSTRAINT appointments_no_overlap
  EXCLUDE USING gist (
    employee_id WITH =,
    tstzrange(blocked_from, blocked_to, '[)') WITH &&
  ) WHERE (status = 'CONFIRMED');
```

The application pre-check exists for a good error message. **The constraint exists for correctness.** The
pre-check can be raced; the constraint cannot. Full reasoning in
[ADR-0002](../adr/0002-exclusion-constraint-for-booking-conflicts.md).

Two details that are easy to get wrong and expensive to discover later:
- `'[)'` — with `'[]'`, a 15:00–16:00 and a 16:00–17:00 appointment would collide, silently forbidding
  back-to-back bookings.
- `WHERE status = 'CONFIRMED'` — without it, a cancelled appointment would block its own slot forever.

Spring maps the violation via `DataIntegrityViolationException`; the handler inspects the constraint name
and returns `409 SLOT_UNAVAILABLE`. Catching the generic exception without checking the name would mask
unrelated integrity errors.

### The write path

Per [02-product-architecture.md](../02-product-architecture.md) §5. One transaction covering: validation,
availability re-check, customer find-or-create, buffer computation, price snapshot, code generation, insert,
audit event. Phase 07 adds notification rows to the same transaction.

### Price snapshot

`price_amount` and `currency` are copied onto the appointment at booking. Without this, changing a service
price rewrites historical revenue — the reason is recorded here because the column looks like needless
denormalisation to a reader who does not know why.

### Customer identity

Find-or-create by `(business_id, normalised phone)`. If the name differs from the stored one, the existing
record is **not** overwritten — the appointment records the name given. Silently renaming a customer because
someone booked for a family member is a data-loss bug.

### Reschedule is an update

One transaction updating times in place, keeping the id and Confirmation Code. Cancel-plus-create can lose
the original slot to a concurrent booking and leave the customer with nothing. Optimistic `version` handles
two concurrent reschedules of the same appointment.

### State machine

`CONFIRMED → {COMPLETED, NO_SHOW, CANCELLED}`. Terminal states are terminal. Anything else is
`422 INVALID_STATUS_TRANSITION`. Implemented as an explicit transition table, not scattered `if`s.

## Database work

`V5__customers_and_appointments.sql`:
- `customers` — `UNIQUE (business_id, phone)`, `UNIQUE (business_id, id)`, name search index
- `appointments` — full column set, composite FKs to employee, service and customer, `version`
- The **exclusion constraint**, plus `appointments_time_order`, `appointments_code_unique`,
  `appointments_cancel_fields`
- Four indexes, all led by `business_id`
- `appointment_events` + index `(appointment_id, created_at)`

## Backend work

- `Customer` entity, repository, `CustomerService.findOrCreate`
- `Appointment` entity with `@Version`
- `AppointmentStatus` transition table
- `ConfirmationCodeGenerator` — Crockford base32 without `I`/`L`/`O`/`U`, retry on collision
- `BookingService.book` — the full transactional path
- `CancellationService.cancel` — window check for customers, exempt for the business
- `RescheduleService.reschedule` — transactional in-place update
- `AppointmentStatusService`
- `AppointmentQueryService` — filtered, paginated listing
- `AppointmentEventRecorder` — one call per transition
- `AppointmentController`, `CustomerController`
- Constraint-violation → `409 SLOT_UNAVAILABLE` mapping, keyed on constraint name

## Frontend work

- `/appointments` — filterable, paginated list (date range, status, employee)
- `/appointments/[id]` — detail with customer, service, price snapshot, source, audit history
- "New appointment" — service → employee → date → slot (reusing phase 05's availability) → customer details
- Cancel, reschedule and status actions with confirmation dialogs
- `/customers` — list and search
- `/customers/[id]` — profile and full appointment history
- All times rendered through the business-timezone helpers

## Testing

### Unit
- [ ] Every legal status transition
- [ ] Every illegal transition rejected
- [ ] Confirmation code alphabet excludes ambiguous characters
- [ ] Buffer → blocked-range computation
- [ ] Cancellation window boundary: customer refused, business allowed

### Integration
- [ ] Booking writes appointment + audit event in one transaction
- [ ] Price is snapshotted; changing the service price afterwards does not alter it
- [ ] Booking a taken slot → `409 SLOT_UNAVAILABLE`
- [ ] Each rejection reason returns its own code (`SERVICE_INACTIVE`, `EMPLOYEE_INACTIVE`,
      `EMPLOYEE_CANNOT_PERFORM_SERVICE`, `BOOKING_IN_PAST`, `BELOW_MIN_LEAD_TIME`, `BEYOND_MAX_ADVANCE`,
      `OUTSIDE_BUSINESS_HOURS`, `OUTSIDE_WORKING_HOURS`)
- [ ] A failed booking writes nothing — verified by row counts
- [ ] Same phone twice reuses one customer; the stored name is not overwritten
- [ ] Cancellation records `cancelled_by`, `cancelled_at`, `reason`
- [ ] Cancelling twice is idempotent
- [ ] Customer cancelling inside the window → `422`; business cancelling succeeds
- [ ] Reschedule keeps id and code; audit records old times
- [ ] Reschedule into a taken slot → `409`, original intact
- [ ] Concurrent reschedules of one appointment → one `409 VERSION_CONFLICT`
- [ ] A cancelled appointment's slot becomes bookable again
- [ ] Deleting a booked service → `409 SERVICE_IN_USE`
- [ ] Isolation probes for every appointment and customer endpoint

### Concurrency — required
- [ ] 20 threads book the same slot: exactly one `201`, nineteen `409`, exactly one row

## Definition of Done

- [ ] The exclusion constraint is live and mapped to `409 SLOT_UNAVAILABLE`
- [ ] The concurrency test passes repeatedly
- [ ] Back-to-back appointments with no buffer are both bookable
- [ ] A cancelled appointment frees its slot
- [ ] Every state change writes an audit event
- [ ] Price snapshots are immune to later price changes
- [ ] Owners can book, cancel, reschedule and set status from the dashboard
- [ ] All tests above pass

## Checklist

### Database
- [ ] `V5__customers_and_appointments.sql`
- [ ] `customers` with unique phone per business
- [ ] `appointments` with all columns incl. `blocked_from`/`blocked_to` and `version`
- [ ] **Exclusion constraint** with `'[)'` and the `CONFIRMED` predicate
- [ ] Time-order, code-uniqueness and cancellation-field checks
- [ ] Composite FKs to employee, service, customer
- [ ] Four `business_id`-led indexes
- [ ] `appointment_events`

### Backend
- [ ] `Customer` entity, repository, `findOrCreate`
- [ ] `Appointment` entity with `@Version`
- [ ] Status transition table
- [ ] `ConfirmationCodeGenerator`
- [ ] `BookingService.book`
- [ ] `CancellationService`
- [ ] `RescheduleService`
- [ ] `AppointmentStatusService`
- [ ] `AppointmentQueryService` with filters and pagination
- [ ] `AppointmentEventRecorder`
- [ ] Controllers for appointments and customers
- [ ] Constraint-name-keyed `409` mapping
- [ ] Error codes: `SLOT_UNAVAILABLE`, `VERSION_CONFLICT`, `INVALID_STATUS_TRANSITION`,
      `CANCELLATION_WINDOW_CLOSED`, and the booking rejection codes

### Frontend
- [ ] `/appointments` list with filters and pagination
- [ ] `/appointments/[id]` detail with audit history
- [ ] New-appointment flow reusing availability
- [ ] Cancel / reschedule / status actions with confirmations
- [ ] `/customers` list and search
- [ ] `/customers/[id]` history
- [ ] `409` handled with a refreshed slot list, never a silent failure
- [ ] Empty, loading and error states throughout

### Testing
- [ ] All unit tests above
- [ ] All integration tests above
- [ ] The 20-thread concurrency test
- [ ] Isolation probes for all new endpoints
