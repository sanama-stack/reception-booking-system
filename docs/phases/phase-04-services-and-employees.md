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
- [x] Duration validation: bounds, multiple of 5
- [x] Buffer bounds
- [x] Price non-negative; zero allowed
- [x] Schedule overlap detection; adjacent intervals allowed
- [x] Phone normalisation using the business country
- [x] Service-name uniqueness is case-insensitive

### Integration
- [x] Service CRUD; duplicate name → `422`
- [x] Deactivating a service hides it from listings that filter on active
- [x] Deleting a booked service → `409 SERVICE_IN_USE` (test written now, exercised fully in phase 06)
- [x] Employee CRUD; deactivation reports affected future appointments
- [x] Assignment replace is idempotent and removes stale rows
- [x] Schedule whole-week replace is atomic
- [x] Time-off CRUD; inverted range → `422`
- [x] **Cross-tenant assignment rejected by the database** on a native insert
- [x] Assigning another tenant's service through the API → `404`
- [x] Onboarding flags flip correctly as configuration completes
- [x] Isolation probes for every new endpoint

## Definition of Done

- [x] Services exist with distinct durations, buffers and prices, and can be deactivated
- [x] Employees exist, can be deactivated, and have per-day schedules
- [x] Employees are assignable to a subset of services
- [x] Time off is recordable
- [x] The database itself rejects cross-tenant assignments
- [x] The onboarding checklist reaches "your page is ready" state
- [x] All tests above pass

## Checklist

### Database
- [x] `V4__catalog_and_staff.sql`
- [x] `services` with constraints and indexes
- [x] `employees` with nullable `user_id`
- [x] `employee_services` with **both composite FKs**
- [x] `employee_schedules` with overlap-preventing unique constraint
- [x] `employee_time_off` with range check
- [x] `UNIQUE (business_id, id)` on both parents

### Backend
- [x] Entities and tenant-shaped repositories for all five tables
- [x] `ServiceCatalogService` incl. delete-guard and assignment replace
- [x] `EmployeeService` incl. deactivation impact report
- [x] `EmployeeScheduleService`
- [x] `TimeOffService`
- [x] Controllers for services, employees, assignments, schedules, time off
- [x] All validators listed above
- [x] Extend `OnboardingService`
- [x] Error codes: `SERVICE_IN_USE`

### Frontend
- [x] `/services` list with empty state
- [x] Service create/edit form with long-duration warning
- [x] Employee multi-select on the service form
- [x] `/employees` list with empty state
- [x] Employee detail with assignments, schedule editor, time off
- [x] Weekly schedule editor component (shared shape with business hours)
- [x] Deactivation confirmation showing affected appointments
- [x] Empty, loading and error states throughout

### Testing
- [x] All unit tests above
- [x] All integration tests above
- [x] The native-insert cross-tenant rejection test
- [x] Isolation probes for all new endpoints

---

## Notes from the build

*Built in two sittings: the backend first, so the API contract could be reviewed before screens
were built on it — the same split phase 03 used — and the five screens second. Everything below the
horizontal rule under "The frontend half" was written in the second sitting.*

### `publicPageReady` reaches `true`, and has now been watched

`OnboardingProgressionTest` walks the checklist from a freshly registered business to
`publicPageReady: true` one configuration step at a time, and back out again three ways. The
dashboard checklist phase 03 built needs no change to display it — it already reads these flags.

The frontend sitting watched it happen: a business configured through the five new screens reached
"Everything is configured. Your booking page is ready to take appointments.", and deactivating its
only service took it back to "3 of 5 done" without a line of checklist code changing. That is the
payoff of the backend sitting publishing the final response shape up front.

### The stub was deleted, not rewired

`EmptyCatalogReadiness` is gone, replaced by `catalog.DatabaseCatalogReadiness`. That is what its
TODO asked for and it matters: two beans implementing one interface make the context refuse to
start, which is loud. A stub left as a fallback fails quietly instead, by going on answering "no"
after the catalog exists.

`EmptyAppointmentImpact` **stays** — phase 06 owes that one. It grew three methods this phase, and
each still answers "nothing".

### `AppointmentImpact` grew rather than multiplying

Phase 04 needs three things from a table that does not exist: how many upcoming appointments a
service deactivation affects, the same for an employee, and whether a service has *ever* been
booked. Each could have been its own port in its own package. One port with three methods means one
stub, and therefore one class for phase 06 to delete — three stubs is three chances to leave one
behind, and a forgotten one fails silently by answering zero forever.

The third method is deliberately a different question from the first two. A hard delete is refused
by *history*, not by the calendar: an appointment from last year still names the service it was
for.

### One writer for `employee_services`

`PUT /services/{id}/employees` and `PUT /employees/{id}/services` are two views of one table, so
both live in `catalog.AssignmentService` rather than being split between the catalog and the staff
services as the Backend-work list above suggests. Two writers would each need to know the other's
rules about what a valid pair is, and the day they disagreed the disagreement would be a
cross-tenant row.

### A submitted id that resolves to nothing is a 404, not a shorter set

A replace that silently dropped an unknown id would let an owner believe they had assigned someone
they had not, and the symptom would surface much later as a service nobody can book. The composite
foreign key would refuse the row anyway; the lookup is what turns that into a sentence instead of a
500.

### Currency is stamped from the Business at creation and never re-stamped

A Service carries its own `currency` column, copied from the Business when the service is created
and never accepted from the caller. It is also never updated afterwards, including when the Business
changes currency — because 60.00 USD is not 60.00 GEL, and re-denominating a price automatically
would change what a customer is charged without anyone deciding to.

**The consequence to accept:** a Business that switches currency has services still priced in the
old code until the owner edits them. That is visible rather than silent, and re-pricing is a
deliberate act. **The frontend half should show the currency per row** rather than assuming one for
the whole list, and phase 09's settings screen is where a "you have services priced in USD" notice
would belong if this ever proves annoying in practice.

### libphonenumber, and why a dependency rather than a table

The first third-party dependency added since phase 01. E.164 normalisation needs to know that
`555 12 34 56` in Georgia is `+995555123456`, and a hand-written table of calling codes is the same
mistake `BusinessValidation` exists to avoid for timezones and currencies — with the extra problem
that it could only reformat a number, never tell a real one from a typo.

It is load-bearing from phase 06, where a Customer is identified by `(business_id, normalised
phone)`: two spellings of one number that normalise differently become two customers with two
separate histories, and nothing notices. `PhoneNumbersTest` asserts idempotence for exactly that
reason.

`isValidNumber`, not `isPossibleNumber` — length alone would accept a typo nobody can be reached on
and store it as though it were a way to contact someone.

**A business with no country set can only accept the international form**, and the error message
says so rather than saying the number is invalid, because the fix is in Settings and not in the
field the owner is looking at.

### Rules the schema now enforces on its own

| Rule | Enforced by |
|---|---|
| A cross-tenant Employee-to-Service assignment | Both composite FKs on `employee_services` |
| Two services one owner cannot tell apart | `services_business_name_unique` on `(business_id, lower(name))` |
| A duration off the five-minute grid | `services_duration_check` |
| One login mapped to two employee records | `employees_user_unique`, partial on `user_id IS NOT NULL` |
| Two schedule intervals starting at once on one day | `employee_schedules_day_start_unique` |

`CrossTenantAssignmentTest` proves the first by writing straight to Postgres with `JdbcTemplate`,
bypassing the service layer, the tenant filter and Hibernate. It carries a **control case** — a
legitimate same-tenant insert that must succeed — because without one the three refusal tests would
pass just as well against a table nothing can be written to at all.

### Traps

**A whole-set replace needs a flush between the delete and the inserts.** Same trap
`BusinessHoursService.replaceWeek` pays: Hibernate orders operations by entity type, not by the
order they were requested in, so the inserts can reach the database before the deletes and collide
with the key they are about to free. Submitting the same set twice is the case that fails without
it, and both `AssignmentService` and `EmployeeScheduleService` do it.

**A price has to be stored at the column's own scale before it is returned.** `numeric(12,2)` gives
back `60.00`, but the in-memory entity a create returns still holds whatever scale the caller sent —
so a create would answer `"60"` and a later read `"60.00"`, and a form that round-trips the value
would show a different string each time. `setScale(2)` at the write, after validation has already
refused anything finer.

**`Service` is the domain's noun, so `@Service` is fully qualified in that package.** CONTEXT.md
makes the vocabulary binding; renaming the catalog's central noun to dodge a Spring annotation would
put a framework detail into the language. The cost is one fully-qualified annotation in each of
three files.

### What the frontend half inherits

1. **`DashboardShell` marks Services and Employees `available: false`.** Flip them, the same way
   Settings was flipped in phase 03.
2. **The dashboard checklist's two `href: null` entries** point nowhere. Give them `/services` and
   `/employees` and drop the `arrives` copy.
3. **The long-duration warning is a frontend job and needs no new endpoint.** `/business/hours` and
   the service's duration are both already on the client; the warning is a comparison, and it warns
   rather than blocks.
4. **`GET /services` and `GET /employees` already carry the assignment sets**, so a list screen needs
   no request per row.
5. **The schedule editor is the business-hours editor with three renamed fields** —
   `hours`/`opensAt`/`closesAt` become `schedule`/`startsAt`/`endsAt`, and errors are keyed
   `schedule[2].startsAt`. The index-mapping trap from the phase 03 handoff §5.1 applies unchanged.
6. **Time Off is the closures form.** Inclusive local dates in, both representations plus the zone
   back out.


---

## The frontend half

*The five screens, written in a second sitting against the contract above.*

### The seven-day editor is now one component, not two

`components/week-editor.tsx` holds the control; `HoursEditor` and `ScheduleSection` are wrappers
supplying the words, the key shape (`hours[2].opensAt` against `schedule[2].startsAt`) and the
write. The alternative — copying the editor and renaming three fields — would have copied the
index-mapping trap with it, and the copy is exactly the kind of code that gets fixed in one place.

The mapping was re-verified in both guises: two overlapping intervals on Tuesday put the server's
message on *Tuesday interval 2* on the hours screen and on *Tuesday shift 2* on the schedule
screen, with `aria-invalid` on that input and nothing on Monday's.

### One assignment control, because there is one table

`employee_services` has a single writer on the server so that two writers can never disagree about
what a valid pair is. `components/assignment-picker.tsx` is the same idea on this side: the service
form's "who provides it" and the employee page's "what they provide" are one component, so the two
screens cannot drift into meaning different things by a tick.

### The employee page stacks, where Settings tabs

Four concerns on one page rather than four sub-routes. Settings is five screens an owner visits one
at a time, months apart; this is four steps an owner works through in one sitting the first time
they add someone, and three of them are the difference between a person who can be booked and one
who cannot. Tabs would hide exactly the steps the onboarding checklist is pushing them towards.

Each section loads and saves independently, so one failing does not take the others with it.

### The long-duration warning measures the duration alone

Not duration plus buffers. The buffers also occupy the calendar, but whether padding may spill past
closing time is phase 05's decision and has not been taken — warning on the total would be
asserting an answer this screen does not have. It compares against the longest single *stretch* the
business is open, not a day's total: two intervals with a lunch break between them cannot hold an
appointment that spans both.

### A create cannot carry its assignments

`POST /services` then `PUT /services/{id}/employees`, because the second call needs an id the first
one produces. If the second fails the service still exists, so the owner is told exactly that and
sent to the detail screen, rather than being left on a form for something that has already been
created.

### A third tone

`--color-warning` is new. A service longer than the opening hours saves fine and cannot be booked —
`danger` would say it was refused, and `ink-muted` would let it pass unread.

### Verified in a browser

Against the real backend, in a throwaway second tenant that was afterwards deleted row by row with
the owner's own business confirmed untouched: both empty states; a create refused with `422` putting
"Use a multiple of 5 minutes" and "Use at most two decimal places" on their own fields and nothing
in the banner; the long-duration warning naming 10 hr against an 8 hr Monday; `25` typed and stored
and re-read as `25.00 USD`; the phone `555 12 34 56` refused with the message that names Settings
rather than the field, and `+995 555 12 34 56` normalised to `+995555123456`; the assignment saving
in both directions; the schedule overlap landing on the right row; time off round-tripping
24–26 December as inclusive dates over a half-open `2026-12-27T00:00Z`; deactivation confirming,
reporting and reversing; a delete returning `204`; and the checklist reaching ready and falling back
out again.

### Not covered

- **`409 SERVICE_IN_USE` was not exercised**, and cannot be until appointments exist. The screen
  renders the server's message verbatim, which is the part that matters, but nothing has produced
  one.
- **`affectedFutureAppointments` was only ever `0`.** The dialog promises nothing is cancelled and
  the result reports the count when it is non-zero; phase 06 is the first time it can be.
- **Still no frontend test runner**, by design. Everything above was verified by hand.
