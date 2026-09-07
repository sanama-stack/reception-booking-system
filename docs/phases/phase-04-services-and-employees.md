# Phase 04 — Services and Employees

## Goal

An owner defines what is sold and who can perform it, when. After this phase every input the availability
engine needs exists in the database.

## Scope

**In:** `services` CRUD with duration, buffers and price; `employees` CRUD; `employee_services`
assignments; `employee_schedules`; `employee_time_off`; the management screens.

**Out:** availability computation (phase 05). Nothing in this phase reads a schedule for booking purposes.

## Dependencies

Phase 03 — services and employees are tenant-owned and schedules intersect business hours.

## Technical work

### Composite foreign keys

This is the phase where the isolation backstop from [03-data-model.md](../03-data-model.md) §1 is built.
`employee_services` carries `business_id` and references both parents with composite keys:

```sql
FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id),
FOREIGN KEY (business_id, service_id)  REFERENCES services  (business_id, id)
```

A cross-tenant assignment is now rejected by Postgres, not by application code. **Test this directly** with
a native insert that bypasses the service layer — that test is the proof the backstop exists.

### Deactivate, never delete

Services and employees are referenced by appointments forever. Hard delete is refused once a row has been
booked (`409 SERVICE_IN_USE`); `active = false` is the supported path. Deactivation responses report
`affectedFutureAppointments` so the owner decides deliberately; nothing is auto-cancelled.

### Schedules may exceed business hours

An employee's Working Schedule is stored as given, even if wider than opening hours. Phase 05 intersects the
two. This lets an owner change opening hours without re-editing every employee, and it keeps "when is this
person willing to work" separate from "when are we open" — two facts that genuinely differ.

### A warning, not an error

A service whose duration exceeds the longest open interval on any day can be saved, but the UI must warn at
save time. Discovering it later as mysteriously empty availability is a much worse experience than a
sentence at the point of the decision.

## Database work

`V4__catalog_and_staff.sql`:
- `services` — full column set, `UNIQUE (business_id, lower(name))`, `UNIQUE (business_id, id)`,
  index `(business_id, active)`, `CHECK duration_minutes % 5 = 0`
- `employees` — full set, nullable `user_id` with `UNIQUE` where not null, `UNIQUE (business_id, id)`
- `employee_services` — composite PK and both composite FKs, index `(business_id, service_id)`
- `employee_schedules` — composite FK, day and time `CHECK`s, `UNIQUE (employee_id, day_of_week, starts_at)`
- `employee_time_off` — composite FK, range `CHECK`, index

## Backend work

- `ServiceCatalogService`: CRUD, activate/deactivate, delete-guard, employee-set replace
- `EmployeeService`: CRUD, activate/deactivate, service-set replace
- `EmployeeScheduleService`: whole-week replace with overlap validation
- `TimeOffService`: CRUD
- Controllers per [04-api-overview.md](../04-api-overview.md) §5
- Validation: name uniqueness, duration bounds and multiple-of-5, buffer bounds, non-negative price,
  E.164 phone, schedule overlap
- Extend `OnboardingService` — `hasActiveService`, `hasActiveEmployee`, `hasEmployeeSchedule`,
  `hasBookableService`

## Frontend work

- `/services` — list with active toggle, duration and price; empty state pointing at "Add your first service"
- `/services/new`, `/services/[id]` — form with duration, buffers, price, and the long-duration warning
- Employee assignment control on the service form (multi-select)
- `/employees` — list with active toggle and job title
- `/employees/[id]` — profile, service assignments, weekly schedule editor, time-off list
- Weekly schedule editor mirroring the business-hours editor for consistency
- Time-off form with date-range picker

## Testing

### Unit
- [ ] Duration validation: bounds, multiple of 5
- [ ] Buffer bounds
- [ ] Price non-negative; zero allowed
- [ ] Schedule overlap detection; adjacent intervals allowed
- [ ] Phone normalisation using the business country
- [ ] Service-name uniqueness is case-insensitive

### Integration
- [ ] Service CRUD; duplicate name → `422`
- [ ] Deactivating a service hides it from listings that filter on active
- [ ] Deleting a booked service → `409 SERVICE_IN_USE` (test written now, exercised fully in phase 06)
- [ ] Employee CRUD; deactivation reports affected future appointments
- [ ] Assignment replace is idempotent and removes stale rows
- [ ] Schedule whole-week replace is atomic
- [ ] Time-off CRUD; inverted range → `422`
- [ ] **Cross-tenant assignment rejected by the database** on a native insert
- [ ] Assigning another tenant's service through the API → `404`
- [ ] Onboarding flags flip correctly as configuration completes
- [ ] Isolation probes for every new endpoint

## Definition of Done

- [ ] Services exist with distinct durations, buffers and prices, and can be deactivated
- [ ] Employees exist, can be deactivated, and have per-day schedules
- [ ] Employees are assignable to a subset of services
- [ ] Time off is recordable
- [ ] The database itself rejects cross-tenant assignments
- [ ] The onboarding checklist reaches "your page is ready" state
- [ ] All tests above pass

## Checklist

### Database
- [ ] `V4__catalog_and_staff.sql`
- [ ] `services` with constraints and indexes
- [ ] `employees` with nullable `user_id`
- [ ] `employee_services` with **both composite FKs**
- [ ] `employee_schedules` with overlap-preventing unique constraint
- [ ] `employee_time_off` with range check
- [ ] `UNIQUE (business_id, id)` on both parents

### Backend
- [ ] Entities and tenant-shaped repositories for all five tables
- [ ] `ServiceCatalogService` incl. delete-guard and assignment replace
- [ ] `EmployeeService` incl. deactivation impact report
- [ ] `EmployeeScheduleService`
- [ ] `TimeOffService`
- [ ] Controllers for services, employees, assignments, schedules, time off
- [ ] All validators listed above
- [ ] Extend `OnboardingService`
- [ ] Error codes: `SERVICE_IN_USE`

### Frontend
- [ ] `/services` list with empty state
- [ ] Service create/edit form with long-duration warning
- [ ] Employee multi-select on the service form
- [ ] `/employees` list with empty state
- [ ] Employee detail with assignments, schedule editor, time off
- [ ] Weekly schedule editor component (shared shape with business hours)
- [ ] Deactivation confirmation showing affected appointments
- [ ] Empty, loading and error states throughout

### Testing
- [ ] All unit tests above
- [ ] All integration tests above
- [ ] The native-insert cross-tenant rejection test
- [ ] Isolation probes for all new endpoints
