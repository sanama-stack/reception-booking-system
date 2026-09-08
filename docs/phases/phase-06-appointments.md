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
- [x] Every legal status transition
- [x] Every illegal transition rejected
- [x] Confirmation code alphabet excludes ambiguous characters
- [x] Buffer → blocked-range computation
- [x] Cancellation window boundary: customer refused, business allowed

### Integration
- [x] Booking writes appointment + audit event in one transaction
- [x] Price is snapshotted; changing the service price afterwards does not alter it
- [x] Booking a taken slot → `409 SLOT_UNAVAILABLE`
- [x] Each rejection reason returns its own code (`SERVICE_INACTIVE`, `EMPLOYEE_INACTIVE`,
      `EMPLOYEE_CANNOT_PERFORM_SERVICE`, `BOOKING_IN_PAST`, `BELOW_MIN_LEAD_TIME`, `BEYOND_MAX_ADVANCE`,
      `OUTSIDE_BUSINESS_HOURS`, `OUTSIDE_WORKING_HOURS`)
- [x] A failed booking writes nothing — verified by row counts
- [x] Same phone twice reuses one customer; the stored name is not overwritten
- [x] Cancellation records `cancelled_by`, `cancelled_at`, `reason`
- [x] Cancelling twice is idempotent
- [x] Customer cancelling inside the window → `422`; business cancelling succeeds
- [x] Reschedule keeps id and code; audit records old times
- [x] Reschedule into a taken slot → `409`, original intact
- [x] Concurrent reschedules of one appointment → one `409 VERSION_CONFLICT`
- [x] A cancelled appointment's slot becomes bookable again
- [x] Deleting a booked service → `409 SERVICE_IN_USE`
- [x] Isolation probes for every appointment and customer endpoint

### Concurrency — required
- [x] 20 threads book the same slot: exactly one `201`, nineteen `409`, exactly one row

## Definition of Done

- [x] The exclusion constraint is live and mapped to `409 SLOT_UNAVAILABLE`
- [x] The concurrency test passes repeatedly
- [x] Back-to-back appointments with no buffer are both bookable
- [x] A cancelled appointment frees its slot
- [x] Every state change writes an audit event
- [x] Price snapshots are immune to later price changes
- [ ] Owners can book, cancel, reschedule and set status from the dashboard
- [x] All tests above pass

## Checklist

### Database
- [x] `V5__customers_and_appointments.sql`
- [x] `customers` with unique phone per business
- [x] `appointments` with all columns incl. `blocked_from`/`blocked_to` and `version`
- [x] **Exclusion constraint** with `'[)'` and the `CONFIRMED` predicate
- [x] Time-order, code-uniqueness and cancellation-field checks
- [x] Composite FKs to employee, service, customer
- [x] Four `business_id`-led indexes
- [x] `appointment_events`

### Backend
- [x] `Customer` entity, repository, `findOrCreate`
- [x] `Appointment` entity with `@Version`
- [x] Status transition table
- [x] `ConfirmationCodeGenerator`
- [x] `BookingService.book`
- [x] `CancellationService`
- [x] `RescheduleService`
- [x] `AppointmentStatusService`
- [x] `AppointmentQueryService` with filters and pagination
- [x] `AppointmentEventRecorder`
- [x] Controllers for appointments and customers
- [x] Constraint-name-keyed `409` mapping
- [x] Error codes: `SLOT_UNAVAILABLE`, `VERSION_CONFLICT`, `INVALID_STATUS_TRANSITION`,
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
- [x] All unit tests above
- [x] All integration tests above
- [x] The 20-thread concurrency test
- [x] Isolation probes for all new endpoints

---

## Notes from the build

Appended by the session that built the backend half. Decisions the checklist above does not record,
and two places where what was built differs from what was written.

### The port grew a parameter: `blockedRangesFor(..., excludingAppointmentId)`

Rescheduling a 10:00 booking to 10:15 overlaps the time that booking currently holds. The database
has no difficulty with this — an exclusion constraint never compares a row with itself — but the
application pre-check would have counted the appointment against itself and refused the one move it
was being asked to make.

So `AppointmentImpact.blockedRangesFor` takes a nullable appointment id to leave out. `null` for an
ordinary availability read; the appointment being moved during a reschedule.

**What this does not cover:** `GET /availability` still has no such parameter, so the reschedule
screen phase 06's frontend builds will show the appointment's own current slot as unavailable. That
is a wrinkle rather than a defect — the move still succeeds — and adding a query parameter that
[04-api-overview.md](../04-api-overview.md) §5 does not describe was not worth doing on the way past.
Phase 08 needs the same thing for a Customer rescheduling through a Manage Link, and should decide
it there for both.

### `PhoneNumbers` moved to `common.phone`, and gained `PhoneField`

It was in `staff`, where phase 04 needed it. Customers are identified by their normalised phone
number, so `customers` needs it too, and a utility living in one caller's package is a dependency in
the wrong direction — phases 08 and 09 would both have inherited it.

`PhoneField` is new and holds the half that throws: normalise against the Business's country, or
fail with a field error in these exact words. `EmployeeService` and `CustomerService` share it, which
matters more than it looks. If the booking path and the correction path ever disagreed about what a
number normalises to, one person would silently become two Customers with two histories.

### Every optional filter in a `@Query` is cast before it is compared to null

`(:from is null or a.startsAt >= :from)` is refused by PostgreSQL with *could not determine data type
of parameter* — at runtime, and only on the path where the filter is absent, which is the default
listing. `cast(:from as Instant)` fixes it.

Worth knowing before writing the next filtered query, and worth a test that exercises the
**no-filter** case specifically: a suite that always passes a date would be green while
`GET /appointments` returned a 500.

### `NOT_ON_SLOT_GRID` has no published code

The engine's seventh `UnbookableReason` has no entry in [04-api-overview.md](../04-api-overview.md)
§3, and it is mapped to `VALIDATION_FAILED`: a start time the business does not offer is a malformed
question rather than a scheduling conflict. It is unreachable from any screen, because every start a
client can click came off the grid.

### The Cancellation Window is tested as a unit, not through an endpoint

The dashboard can only ever produce a `BUSINESS` actor, and the Business is never bound by the
window (CONTEXT.md). Until phase 08 opens a Customer-facing path, `CancellationWindowTest` is the
only place the refusing branch can be exercised at all — leaving it until then would have meant
shipping the rule untested.

### Buffer arithmetic is asserted against the columns, not against a method

The checklist calls it a unit test. It is an integration one: `BookingEndpointTest` reads
`blocked_from` and `blocked_to` back out of the row with a service whose two buffers differ, which
catches a transposition that a unit test of `ServiceSpec.occupancyFor` would also catch **and** the
case where the right value never reached the column.
