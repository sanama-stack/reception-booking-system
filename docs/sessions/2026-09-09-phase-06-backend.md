# Session handoff — 2026-09-09 — Phase 06 backend, and the constraint finally doing its job

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done;
> §4 is where a decision had to be taken; §5 will save you the most time; §6 will stop you assuming
> coverage that is not there.
>
> **Read §2 before running anything.** This machine could not build the project at all when the
> session started, for a reason that had nothing to do with the code.

---

## 1. Where the project stands

**Phase 06's backend half is complete.** Appointments can be booked, moved, cancelled and closed
out; double booking is refused by PostgreSQL rather than by an application check; and the phase-03
stub every earlier phase has been asking is gone. Every box in
`docs/phases/phase-06-appointments.md` is ticked except the eight Frontend ones and the one
Definition-of-Done line they satisfy.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — protected, and behind `dev` |
| Working branch | **`dev`** — committed, **not pushed**, CI has not seen it |
| Backend tests | **625**, up from 545. All green, full `./gradlew build` |
| Frontend | Untouched this session |
| Migrations | `V5__customers_and_appointments.sql` — three tables, one exclusion constraint |

### What has not happened

**`dev` is still not pushed, and the pull request is still not open.** This is now six commits of
phase 05 plus this phase's, and CI has seen none of it. It was the highest-value action in the
previous handoff and it still is:

```bash
git push origin dev
gh pr create --base main --head dev --fill
gh pr checks --watch
gh pr merge          # a merge commit, not a squash
```

**The five stale `business_hours` rows on the owner's own business are still shifted.** Unchanged
from the last three handoffs, and it needs the owner's own session — open `/settings/hours` and
re-enter Monday to Friday as 09:00–17:00. Until then anything computed from them is four hours out.
None of this session's tests touch that data; they build their own tenants and delete them.

**The transparent-refresh fix is on `dev`.** The previous handoff's §8 said it was unmerged; it is
not. `fd0b339` and `24689fb` are both in. That item is closed.

---

## 2. This machine could not run the build

`./gradlew` failed with a bare `25.0.4.1` and nothing else. The cause: the only JDKs installed were
Temurin 25, and **Gradle 8.14 refuses to run on Java 25**. The toolchain block asks for 21 and
foojay would have provisioned it, but Gradle has to start before it can do that.

`brew install --cask temurin@21` needs `sudo` with a password, which an agent session cannot supply.
The no-sudo route worked and is what is installed now:

```bash
curl -fsSL -o /tmp/temurin21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
tar -xzf /tmp/temurin21.tar.gz -C ~/Library/Java/JavaVirtualMachines/
```

**Every Gradle command in this session was run as:**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

Without that prefix the build fails again, because `java_home` still resolves to 25 by default.
**CI is unaffected** — it pins Temurin 21 through `setup-java`, which is why 545 tests were green on
a machine that could not compile a line.

Worth deciding at some point, and deliberately not decided here: bumping the wrapper to Gradle 9
would let the build run on the JDK that is actually installed. That is build tooling rather than
phase 06, and it belongs in its own commit.

Everything else about running it is unchanged. `make up`, the backend from the IDE, `pnpm dev`,
**http://localhost:9080**.

---

## 3. What exists now

`V5__customers_and_appointments.sql` — `customers`, `appointments`, `appointment_events`, and
`appointments_no_overlap`.

**`dev.reception.customers`** — `Customer`, `CustomerRepository`, `CustomerService`, and
`customers.web` with the controller and its two DTO holders.

**`dev.reception.appointments`** — sixteen classes. The ones worth knowing before you open the
package:

| | |
|---|---|
| `AppointmentStatus` | the transition table, and `holdsTime()` — the constraint's own predicate in Java |
| `Actor` / `CurrentActor` | who is acting, passed as an argument. See §4.1 |
| `AppointmentLookup` | one appointment by id, tenant-scoped. Four services share it so four copies cannot drift |
| `CancellationWindow` | the one piece of arithmetic cancel and reschedule must agree on |
| `BookingRefusal` | `UnbookableReason` → `ErrorCode`, with no `default` branch. See §4.4 |
| `DatabaseAppointmentImpact` | replaces `EmptyAppointmentImpact`, which is deleted |

**`PhoneNumbers` moved** from `staff` to `common.phone`, and `PhoneField` is new beside it. See §4.2.

**`GlobalExceptionHandler` gained two handlers** — `DataIntegrityViolationException` keyed on
constraint *name*, and `OptimisticLockingFailureException`. Nine error codes were added to
`ErrorCode`, all of which [04-api-overview.md](../04-api-overview.md) §3 already published.

**`AvailabilityService` gained `reasonNotBookable`**, sharing every line of loading and every
eligibility refusal with `find`. Nothing else in phases 03–05 changed.

---

## 4. The decisions that will shape phases 07, 08 and 09

### 4.1 The actor is an argument, never an ambient lookup

`BookingService`, `CancellationService`, `RescheduleService` and `AppointmentStatusService` all take
an `Actor`. None of them reads the `SecurityContext`.

The reason is that all four are called by three different front doors, and only one of those has a
security context to read: the dashboard has a signed-in user, the Classic Flow (phase 08) has a
Customer with no account, and the Receptionist (phase 09) has no user row at all. A service that
reached for the context would work for the caller that has one and silently record the wrong
actor — or none — for the two that do not.

`CurrentActor` is the one class that reads the context, it lives outside the services, and the
controller calls it. **Phase 08 and 09 construct `Actor.customer()` and `Actor.ai()` and never touch
it.**

This is also what makes the Cancellation Window correct without a flag: `Actor.asCancellingParty()`
maps `CUSTOMER` to `CUSTOMER` and everything else to `BUSINESS`, and the Business is never bound
(CONTEXT.md).

### 4.2 `PhoneNumbers` moved, because Customer identity depends on it

A Customer is `(business_id, normalised phone)`. That makes normalisation load-bearing rather than
cosmetic, which its own javadoc predicted in phase 04.

It was in `staff`. `customers` needs it, and so will `publicapi` and `ai`. `common.phone` is where it
belongs, and `PhoneField` — new — holds the throwing half, so `EmployeeService` and `CustomerService`
share one rule and one set of words for failing it.

**Why that matters more than tidiness:** if the booking path and the correction path ever disagreed
about what a number normalises to, one person would become two Customers with two histories, and
nothing anywhere would report an error.

### 4.3 The port grew a parameter rather than a sixth method

`AppointmentImpact.blockedRangesFor` now takes a nullable `excludingAppointmentId`.

Moving a 10:00 booking to 10:15 overlaps the time that booking currently holds. The database does not
care — an exclusion constraint never compares a row with itself — but the pre-check would have
counted the appointment against itself and refused the one move it was being asked to make.

**The consequence to accept, and it is phase 08's to close:** `GET /availability` has no such
parameter, so a reschedule screen shows the appointment's own current slot as unavailable. The move
still succeeds; the slot list is merely one entry short. Adding a query parameter that
[04-api-overview.md](../04-api-overview.md) §5 does not describe was not worth doing on the way past,
and phase 08 needs the same thing for a Customer rescheduling through a Manage Link — decide it there,
for both.

### 4.4 The refusal mapping has no `default` branch

`BookingRefusal.of` switches over all seven `UnbookableReason` values exhaustively. An eighth reason
will fail to compile rather than quietly becoming a generic refusal.

`NOT_ON_SLOT_GRID` maps to `VALIDATION_FAILED` and has no published code of its own: a start time the
business does not offer is a malformed question rather than a scheduling conflict. It is unreachable
from any screen, because every start a client can click came off the grid.

### 4.5 Cancelling is not reachable through the status endpoint

`POST /appointments/{id}/status` refuses `CANCELLED` with a field error pointing at the cancel
action. It carries three things that shape cannot express — a cancelling party, a window check, and
idempotence — and letting it through would write a row the database rejects
(`appointments_cancel_fields`), which is a 500 where an explanation belongs.

The refusal is in `AppointmentStatusService`, not the controller. Phase 08 and 09 reach the service
directly.

### 4.6 Cancelling twice is answered before the state machine is consulted

`CancellationService` returns the appointment unchanged on a repeat, writes no second event, and
keeps the **first** reason. Only then does it ask `AppointmentStatus` whether the move is legal.

Those two never have to agree about what "already cancelled" means, which is the point: the state
machine says a terminal state is terminal, and idempotence says repeating a move you already made is
not an error. Phase 07 must keep this order — a second cancellation must not enqueue a second email.

---

## 5. Traps already paid for

### 5.1 An optional parameter compared to `null` in JPQL is refused by PostgreSQL

`(:from is null or a.startsAt >= :from)` produces **`could not determine data type of parameter $2`**
at runtime — and only on the path where the filter is absent, which is the default listing. The whole
statement is refused, so it is a 500, not a wrong result.

`cast(:from as Instant)` fixes it, and every optional filter in `AppointmentRepository` now has one.

**The lesson for the next filtered query is the test rather than the cast:** exercise the
**no-filter** case explicitly. A suite that always passed a date would have been green while
`GET /appointments` returned a 500, which is exactly what happened here — it was caught by the
isolation test, not by the listing test, and only because that test happened to call the endpoint
bare.

### 5.2 `char(3)` needs `@JdbcTypeCode(SqlTypes.CHAR)`, and the failure is at context startup

`appointments.currency` is `char(3)`. Hibernate expects `varchar` and `ddl-auto: validate` refuses to
build the `SessionFactory`. The symptom is **every integration test in the suite failing at once**
with a `BeanCreationException` — including tests that have nothing to do with appointments.

`Business` and `Service` already carried the annotation; the new entity did not. A whole suite red at
once is a context failure, not sixty real ones, and the message is in the first cause.

### 5.3 A "past" date derived from a "future" one is not in the past

`monday` in the test fixture is *today + 7, rounded forward to Monday* — which is 7 to 13 days out
depending on the weekday the suite runs. `monday.minusDays(7)` is therefore anywhere from yesterday
to six days out, and a `BOOKING_IN_PAST` assertion written that way passes on Tuesdays and fails on
Wednesdays.

`BookingScenario` now derives `pastMonday` independently. **Derive each date from the clock, never
from another test date.**

### 5.4 `OffsetDateTime.toString()` omits zero seconds and Jackson does not

`2026-09-21T10:00+04:00` from Java, `2026-09-21T10:00:00+04:00` on the wire. Every start time in this
system lands on a minute boundary, so comparing the two forms fails on every single case.
`BookingScenario.wireTime` exists for exactly this and should be used for any assertion against a
time in a response body.

### 5.5 A booking removes more slots than it occupies

A 60-minute service on a 15-minute grid, booked 10:00–11:00, removes **seven** starts, not one:
everything from 09:15 to 10:45 would overlap it. 09:00 and 11:00 survive, which is the `'[)'` bound
visible from the outside.

Worth knowing before writing an assertion about slot counts, and worth knowing when an owner asks why
one booking "used up the whole morning".

### 5.6 Everything from the earlier handoffs still applies

Particularly `pnpm build` against a tree running `pnpm dev`, and the hidden-Browser-pane rules in the
phase-05 frontend handoff §5.1 — the frontend half will meet all of them.

---

## 6. What is not covered by a test

- **Nothing in `appointments.web` or `customers.web` is unit-tested.** Every assertion goes through
  real HTTP against real PostgreSQL, which is the project's choice and is why the count grew by 80
  rather than 200.
- **The `CUSTOMER` cancellation path is tested only as a unit.** `CancellationWindowTest` exercises
  the refusing branch with a mocked Business and a fixed Clock, because the dashboard cannot produce
  a Customer actor. The endpoint half arrives with phase 08's Manage Link.
- **`Actor.ai()` and `Actor.system()` are constructed by nothing.** They exist for phases 09 and 07
  and are unreachable today, so the `AI` and `SYSTEM` values of `actor_type` have never been written.
- **The `409` is never produced by a *reschedule* losing to the constraint under real concurrency.**
  `ConcurrentRescheduleTest` contests the record, not the time; `ConcurrentBookingTest` contests the
  time, but only through `POST /appointments`. The reschedule-versus-booking race is the one gap in
  the concurrency story.
- **No test asserts what happens when the confirmation code collides for real.** The generator's
  retry is tested with an injected predicate; the database's `appointments_code_unique` has never
  fired, and if it did it would surface as a logged 500 rather than a retry.
- **`AppointmentQueryService.hydrate` lists every service and every employee per page.** Correct and
  fast for a salon; nothing measures it, and nothing fails if a business ever has a thousand
  services.
- **`customer_note` is accepted, stored and returned, and no test asserts any of that.**
- **Everything the phase-05 handoff §6 listed is still uncovered**, except the two items this phase
  closed: `FULLY_BOOKED` now has a cause other than time off, and `blockedRangesFor` is no longer a
  stub.

### What was verified beyond the assertions

**The concurrency test was run repeatedly.** `@RepeatedTest(3)`, and the whole class run several
times over the session: exactly one `201`, nineteen `409 SLOT_UNAVAILABLE`, one row and one audit
event, every time. A check-then-write implementation passes this occasionally, which is why it
repeats rather than running once.

**The version conflict fires every time, not sometimes.** The reschedule test was first written to
tolerate both writes succeeding and then tightened to
`containsExactlyInAnyOrder(OK, CONFLICT)` — it passed three repetitions in that stricter form, so
`@Version` is genuinely doing the work rather than the two requests happening to serialise.

**The stub's removal was checked from the outside.** `AppointmentImpactTest` asserts closure counts
and deactivation counts through their own endpoints, and `BookingEndpointTest` watches slots
disappear from `GET /availability` with no change to that endpoint at all — which is what the
phase-05 handoff §9 asked for.

---

## 7. Open items

Everything in §8 of the phase-05 frontend handoff still stands unless listed below. Changed or added:

- **`dev` is unpushed and the pull request is not open.** Now the largest it has ever been. This is
  the highest-value next action.
- **The transparent-refresh item is closed.** It is on `dev`.
- **`EmptyAppointmentImpact` is gone.** The last stub is deleted, and there is no stub left in the
  codebase.
- **The five stale `business_hours` rows are still shifted.** Owner's own session; see §1.
- **Gradle 8.14 cannot run on this machine's default JDK.** Prefix every command with
  `JAVA_HOME=$(/usr/libexec/java_home -v 21)`, or take the wrapper to Gradle 9 in a commit of its
  own. See §2.
- **`GET /availability` cannot exclude an appointment being rescheduled.** See §4.3. Phase 08's to
  decide.
- **`aiEnabled` and `aiDailyCostCapCents` still have no UI.** Phase 09 owes them.
- **Node 20 deprecation warnings in CI** — unchanged, warnings only.

---

## 8. Next: the phase 06 frontend

Read the **Frontend** section of `docs/phases/phase-06-appointments.md` and the **Notes from the
build** appended to it. Six screens' worth of work, and the eight unticked boxes are the list.

What this session leaves you:

1. **The response shapes are final.** `AppointmentPage`, `AppointmentWithHistory` and
   `BookedAppointment` all carry `timezone` on the envelope and every time at the Business's offset,
   exactly as `GET /availability` does. `blockedFrom` and `blockedTo` are deliberately not on the
   wire — a screen showing them would show a customer ten minutes of cleanup as if it were part of
   their appointment.
2. **`409` is the case the checklist singles out, and it has two codes.** `SLOT_UNAVAILABLE` means
   re-read availability and offer what is left; `VERSION_CONFLICT` means reload this appointment.
   Both must be handled, and neither may be a silent failure.
3. **The new-appointment flow reuses phase 05's availability read.** `lib/scheduling/api.ts` builds
   the path; `AvailableSlot` already carries the Employee who would perform it, so booking never has
   to resolve one. Resolving again would be a second chance to choose differently from what the
   customer was shown.
4. **`lib/scheduling/` is the model to copy for `lib/appointments/`** — types beside a path builder,
   and the §4.2 keying rule from the phase-05 frontend handoff applies to any read whose question
   changes while the screen is open. The appointment list, with its four filters, is exactly that.
5. **The availability preview on the employee screen is the fastest way to see this working.** Book
   something and watch seven slots vanish (§5.5). Nothing on that screen changed.
EOF
