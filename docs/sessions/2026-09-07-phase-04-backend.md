# Session handoff — 2026-09-07 — Phase 04 (Services and Employees), backend half

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done and
> what is not; §5 is the part that will save you the most time; §6 is the part that will stop you
> assuming coverage that is not there. **Phase 04 is half finished by design** — read §1 first.

---

## 1. Where the project stands

**The phase 04 backend is complete and its suite is green. The services and employees screens are
not built.** That split was the project owner's choice, taken at the start of the session for the
same reason it was taken in phase 03: so the API contract can be reviewed before screens are built
on it. The phase document's Frontend checklist is deliberately unticked.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — **still 15 commits behind `dev`** |
| Working branch | **`dev`** — pushed, and CI has seen it |
| Backend tests | **416**, up from 234 |
| CI | **Green on `dev`** — for phase 03, and again for this phase's commit (5m14s) |
| Frontend | Untouched this session |

### What changed about the state the last three handoffs described

**`dev` is pushed.** It had been unpushed since the phase 02 handoff and was flagged as the
highest-value next action three times. It went first this session, CI came back green on the whole
of phase 03 in 4m27s, and phase 04 was built on top of a branch CI had actually seen.

**`main` is still behind.** Pushing `dev` is not merging it. The pull request has now been offered
four times across four handoffs and has not been opened:

```bash
gh pr create --base main --head dev --fill
gh pr checks --watch
gh pr merge --squash
```

**Branch protection is still not enabled.** Offered four times now, never accepted.

---

## 2. Running it

Unchanged. `make up`, then the backend from the IDE and `pnpm dev` from a terminal. Open
**http://localhost:9080**, never 9082.

To run Gradle from a terminal you must point `JAVA_HOME` at a JDK 21 — the system JDK is 25 and
Gradle 8.14 dies on it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd backend && ./gradlew build
```

**The full suite now takes about 2m30s**, up from 90 seconds — phase 04 adds two more Spring
contexts (`ServiceInUseTest` replaces a bean, which gives it a context of its own). If it runs much
longer than that with no output, read §6.1 of the phase-03 backend handoff before doing anything
else: a hanging suite with no failing test is almost always something sleeping.

**`timeout` does not exist on this machine.** `timeout 900 ./gradlew test` fails with "command not
found" and the shell reports it as an empty result, which reads exactly like a test run that
produced no output. Ten seconds of confusion, avoidable.

---

## 3. What exists now

**Database** — `V4__catalog_and_staff.sql`: `services`, `employees`, `employee_services`,
`employee_schedules`, `employee_time_off`. Every tenant-owned table carries
`UNIQUE (business_id, id)`, and `employee_services` uses it — see §4.

**Backend**, in two new packages:

| `dev.reception.catalog` | |
|---|---|
| `Service` | the entity. Duration, buffers, price, currency, active |
| `ServiceCatalogService` | CRUD, activate/deactivate, the delete-guard |
| `AssignmentService` | **both** directions of `employee_services` — see §4 |
| `ServiceValidation` | duration grid and bounds, buffer bounds, price sign and scale |
| `DatabaseCatalogReadiness` | the real `CatalogReadiness`, one native query |
| `catalog/web/` | `ServiceController` and its DTOs |

| `dev.reception.staff` | |
|---|---|
| `Employee`, `EmployeeSchedule`, `EmployeeTimeOff` | the entities |
| `EmployeeService` | CRUD, activate/deactivate, the deactivation impact report |
| `EmployeeScheduleService` | whole-week replace; `validateWeek` is `static` and package-private |
| `TimeOffService` | inclusive local dates in, half-open instants stored |
| `PhoneNumbers` | E.164 via libphonenumber, pure and static |
| `staff/web/` | `EmployeeController` and its DTOs |

**Endpoints** — the fifteen from `04-api-overview.md` §5, all
`@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")` declared once per controller class:

```text
GET  /services?active=     POST /services
GET  /services/{id}        PATCH /services/{id}        DELETE /services/{id}
POST /services/{id}/activate | /deactivate
PUT  /services/{id}/employees

GET  /employees?active=    POST /employees
GET  /employees/{id}       PATCH /employees/{id}
POST /employees/{id}/activate | /deactivate
PUT  /employees/{id}/services
GET  /employees/{id}/schedule    PUT /employees/{id}/schedule
GET  /employees/{id}/time-off    POST /employees/{id}/time-off
DELETE /employees/{id}/time-off/{offId}
```

**Tests** — nine new classes. Three unit (`ServiceValidationTest`,
`EmployeeScheduleValidationTest`, `PhoneNumbersTest`) and six integration
(`ServiceEndpointTest`, `ServiceInUseTest`, `AssignmentEndpointTest`,
`EmployeeEndpointTest`, `EmployeeScheduleEndpointTest`, `TimeOffEndpointTest`), plus
`CrossTenantAssignmentTest`, `CatalogIsolationTest` and `OnboardingProgressionTest`.

**One new dependency** — `com.googlecode.libphonenumber:libphonenumber:9.0.7`, the first added since
phase 01. See §4.

---

## 4. The decisions that will shape phase 05 onward

### The isolation backstop is now a constraint, not a convention

`employee_services` references both parents by `(business_id, id)`. A row pairing Salon Aria's
employee with Datos Auto's service is not merely refused by application code — it is
unrepresentable.

`CrossTenantAssignmentTest` proves this by writing straight to Postgres with `JdbcTemplate`,
bypassing the service layer, the tenant filter and Hibernate entirely. **It carries a control case:**
a legitimate same-tenant insert that must succeed. Without one, the three refusal tests would pass
just as well against a table nothing can be written to at all, and would be proving nothing.

**What this means for you:** phases 05 and 06 add `appointments`, which references *three* tenant-owned
parents. Use the same composite form. `docs/03-data-model.md` §1 already shows the constraint.

### One writer for `employee_services`, in `catalog`

`PUT /services/{id}/employees` and `PUT /employees/{id}/services` are two views of one table, so both
live in `AssignmentService` rather than being split between the catalog and the staff services as the
phase document's Backend-work list suggests. Two writers would each have to know the other's rules
about what a valid pair is, and the day they disagreed the disagreement would be a cross-tenant row.

It is also a **replace**, never an add-and-remove. That is what makes it idempotent, and it matches
what a multi-select control actually knows: which boxes are ticked now, not which ones changed.

**A submitted id that resolves to nothing is a `404`, not a silently shorter set.** Dropping it would
let an owner believe they had assigned someone they had not, and the symptom would arrive much later
as a service nobody can book.

### `AppointmentImpact` grew rather than multiplying

Phase 04 needs three things from a table that does not exist yet: how many upcoming appointments a
service deactivation affects, the same for an employee, and whether a service has **ever** been
booked. All three are on the one port, so phase 06 has **one class to delete** — `EmptyAppointmentImpact`.
Three separate ports would be three stubs and three chances to leave one behind, and a forgotten one
fails silently by answering zero forever.

The third question is deliberately not the first two. A hard delete is refused by *history*, not by
the calendar: an appointment from last year still names the service it was for.

**`EmptyCatalogReadiness` is deleted**, replaced by `catalog.DatabaseCatalogReadiness`. That is what
its TODO asked for. **`EmptyAppointmentImpact` stays** — phase 06 owes that one, and its TODO says so.

### The readiness snapshot is one statement, not four

`CatalogReadiness.Snapshot` hands back four booleans precisely so an implementation can answer them
from a single view of the data. Four `exists` calls under `READ COMMITTED` would each see their own
snapshot, and the checklist could show a state that was never true. `DatabaseCatalogReadiness` is one
native query with four correlated `EXISTS` clauses.

**What this means for phase 05:** `hasBookableService` is the conjunction that actually decides
whether anyone can book — an active service, with an active assigned employee, who has a schedule.
The other three flags only approximate it. If the engine ever disagrees with that flag, one of them
is wrong.

### A Working Schedule is stored as given, even wider than Business Hours

There is no clamping. Phase 05 intersects the two. "When is this person willing to work" and "when
are we open" are genuinely different facts, and storing only their intersection would silently re-cut
every employee's week whenever the opening hours moved.

`EmployeeScheduleEndpointTest` asserts both halves: a 07:00–21:00 Monday against 09:00–17:00 opening
hours is stored verbatim, and so is a Sunday shift at a business closed on Sundays.

### The schedule editor is the hours editor, on purpose

`EmployeeScheduleService` is deliberately the same shape as `BusinessHoursService`, down to the
validation, the flush and the error keys. One control serves both, and phase 05 intersects two lists
that mean the same kind of thing. The only differences on the wire are three field names:
`hours`/`opensAt`/`closesAt` become `schedule`/`startsAt`/`endsAt`, and errors are keyed
`schedule[2].startsAt`.

### A Service's currency is stamped at creation and never re-stamped

Copied from the Business when the service is created, never accepted from the caller, and **never
updated afterwards** — including when the Business changes currency. 60.00 USD is not 60.00 GEL, and
re-denominating a price automatically would change what a customer is charged without anyone
deciding to.

**The consequence to accept:** a Business that switches currency has services still priced in the old
code until the owner edits them. That is visible rather than silent. **The frontend half should
render the currency per row** rather than assuming one for the whole list.

### libphonenumber, and why a dependency rather than a table

The first third-party dependency since phase 01, and the project owner's call. E.164 normalisation
needs to know that `555 12 34 56` in Georgia is `+995555123456`. A hand-written table of calling
codes is the same mistake `BusinessValidation` exists to avoid for timezones and currencies — with the
extra problem that it could only reformat a number, never tell a real one from a typo.

It is load-bearing from **phase 06**, where a Customer is identified by `(business_id, normalised
phone)`. Two spellings of one number that normalise differently become two customers with two
separate histories, and nothing notices. `PhoneNumbersTest` asserts idempotence for exactly that
reason.

`isValidNumber`, not `isPossibleNumber`: length alone would accept a typo nobody can be reached on
and store it as though it were a way to contact someone.

**A Business with no country set can only accept the international form**, and the message says so
rather than saying the number is invalid — the fix is in Settings, not in the field the owner is
looking at.

---

## 5. Traps already paid for

### 5.1 A whole-set replace needs a flush between the delete and the inserts

The same trap `BusinessHoursService.replaceWeek` paid in phase 03, and it applies twice more here.
Hibernate orders operations by entity type, not by the order they were requested in, so the inserts
can reach the database before the deletes and collide with the key they are about to free.

Both `AssignmentService.replaceEmployeesFor`/`replaceServicesFor` and
`EmployeeScheduleService.replaceWeek` call `flush()` between the two. **Submitting the same set twice
is the case that fails without it**, and both have a test named for it.

**Assume this applies to every replace-the-whole-set operation you write.** It is now three for three.

### 5.2 A price must be stored at the column's own scale before it is returned

`numeric(12,2)` gives back `60.00`, but the in-memory entity a create returns still holds whatever
scale the caller sent. So a create would answer `"60"` and a later read `"60.00"`, and a form that
round-trips the value would show a different string each time — a bug that is invisible until someone
looks at two screens at once.

`ServiceCatalogService` calls `setScale(2)` at the write, after validation has already refused
anything finer than two places, so it rounds nothing.

### 5.3 `Service` is the domain's noun, so `@Service` is fully qualified in that package

CONTEXT.md makes the vocabulary binding. Renaming the catalog's central noun to dodge a Spring
annotation would put a framework detail into the language the whole project speaks. The cost is
`@org.springframework.stereotype.Service` in three files in `dev.reception.catalog`, and a compile
error with a clear message if anyone forgets.

The `staff` package has no such clash: `EmployeeService` is an application service and `@Service`
resolves normally there.

### 5.4 `@MockitoBean` gives a test class its own Spring context

`ServiceInUseTest` replaces `AppointmentImpact` to exercise the `409 SERVICE_IN_USE` path that cannot
be reached otherwise. That changes the context cache key, so the suite boots a second application
context — about 20 seconds. Worth it here; not worth doing casually.

### 5.5 `char(3)` still needs `@JdbcTypeCode(SqlTypes.CHAR)`

Carried over from phase 02 §6.2 and it bit again on `services.currency`. `@Column(length = 3)` alone
fails `ddl-auto: validate` at startup.

### 5.6 Everything from the earlier handoffs still applies

Particularly the two build traps around `pnpm dev` and `pnpm build`, and `noUncheckedIndexedAccess`
being on — both of which the frontend half of this phase will meet.

---

## 6. What is not covered by a test

Stated so it is not mistaken for coverage that exists.

- **`affectedFutureAppointments` and `everBooked` are exercised against a stub, not against
  appointments.** `ServiceInUseTest` replaces `AppointmentImpact` with a mock, which proves the
  controller, the status code, the error code and the message — everything phase 04 owns. What it
  cannot prove is that phase 06's real implementation answers the same questions. **Phase 06 owes the
  real cases**, and if it changes any of these answers this test fails and says so.
- **The three catalog flags are now covered end to end** by `OnboardingProgressionTest`, which walks a
  registered business to `publicPageReady: true` and back out three ways. This closes the gap the
  phase-03 handoff §7 named.
- **Nothing in this phase has been seen in a browser.** There is no frontend for it yet. The
  dashboard checklist built in phase 03 needs no change to display the new flags — it already reads
  them — so `publicPageReady` reaching true should be visible immediately, but nobody has watched it
  happen. That is why the Definition-of-Done box is left unticked.
- **No `STAFF` role test**, unchanged from phase 03: `STAFF` has no login in the MVP, so there is no
  way to obtain a session to be refused. The rule is asserted to exist by inspection only.
- **The name-uniqueness race is handled but not exercised.** `ServiceCatalogService` checks, then
  catches the integrity violation and turns it back into a field error. Testing the race needs two
  threads and a latch; the check loses roughly never.
- **`employees.user_id` is written by nothing.** It is the seam for staff login (ADR-0006) and is
  unused in the MVP. Its partial unique index exists and is untested, because nothing can produce a
  second row to collide.
- **Still no frontend test runner**, by design (`08-testing-strategy.md` §11).

---

## 7. Open items

Everything in §7 of the phase-03 backend handoff and §7 of the phase-03 frontend handoff still
stands unless listed below. Added or changed:

- **`dev` is pushed and CI has seen it.** The three-handoff-old open item is closed.
- **`main` is 15 commits behind and the pull request has not been opened.** Offered four times now.
- **Branch protection is still not enabled.** Offered four times now.
- **The phase 04 frontend is not started.** See §8.
- **`EmptyAppointmentImpact` is the last stub standing.** Phase 06 deletes it, and its TODO says so.
  Do not change its return values.
- **A currency change leaves existing services on the old code.** Deliberate — see §4. Worth a notice
  on the settings screen if it ever proves annoying; not worth building speculatively.
- **The long-duration warning is unbuilt and needs no endpoint.** `/business/hours` and the service's
  duration are both already on the client. It warns, it does not block.
- **`aiEnabled` and `aiDailyCostCapCents` still have no UI.** Unchanged from phase 03; phase 09 owes
  them.
- **Node 20 deprecation warnings in CI** — unchanged, warnings only.

---

## 8. Next: the phase 04 frontend

Read `docs/phases/phase-04-services-and-employees.md` — the **Frontend** checklist and the **Notes
from the build** section appended this session. Five screens:

1. **`/services`** — list with an active toggle, duration and price. `GET /services` already carries
   `employeeIds` on every row, so "nobody assigned" — the most common reason a configured service
   still cannot be booked — needs no request per row. **Render the currency per row**, not once for
   the list.
2. **`/services/new` and `/services/[id]`** — duration, buffers, price, the employee multi-select, and
   the long-duration warning. The warning compares the duration against the longest open interval in
   `/business/hours` and is a sentence at the point of the decision, not a refusal.
3. **`/employees`** — list with an active toggle and job title. Same assignment-set shortcut.
4. **`/employees/[id]`** — profile, service assignments, the weekly schedule editor, the time-off
   list. One screen, four concerns; consider whether it wants sub-navigation the way Settings did.
5. **The deactivation confirmation** — `ConfirmDialog` exists. Both deactivate endpoints return
   `affectedFutureAppointments`, which is `0` until phase 06 and should be shown honestly rather than
   hidden.

What this session leaves you:

- **`DashboardShell` marks Services and Employees `available: false`.** Flip them, the same way
  Settings was flipped in phase 03.
- **The dashboard checklist's two `href: null` entries** point nowhere. Give them `/services` and
  `/employees` and drop the `arrives` copy. Nothing else about the checklist needs changing — the
  backend flipped the flags it already reads.
- **The schedule editor is `HoursEditor` with three renamed fields.** The index-mapping trap from the
  phase-03 frontend handoff §5.1 applies unchanged: the server names a failure by its position in the
  submitted array, which is not a position in the editor.
- **Time Off is the closures form.** Inclusive local dates in; both representations plus the zone come
  back, so nothing is re-derived client-side. `formatIsoDate` is already the right tool and already
  knows why it takes no timezone.
- **The form patterns are established.** `useResource` + `ResourceGate` + `changedFields` + a keyed
  remount on save is what all five settings screens do. `changedFields` lives in
  `lib/business/patch.ts` and is not business-specific in anything but its parameter type.
