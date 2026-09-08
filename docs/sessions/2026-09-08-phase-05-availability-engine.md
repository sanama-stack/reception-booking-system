# Session handoff — 2026-09-08 — Phase 05 (Availability Engine), backend half

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done and
> what is not; §4 is where the specification was silent and a decision had to be taken; §5 will save
> you the most time; §6 will stop you assuming coverage that is not there.
>
> **Phase 05 is half finished by design** — read §1 first. §5.1 is the one to read before you open
> the next pull request, and §5.2 before you touch anything that converts a wall-clock time.

---

## 1. Where the project stands

**The phase 05 backend is complete and its suite is green. The availability preview screen is not
built.** That split was the project owner's choice, taken at the start of the session for the same
reason it was taken in phases 03 and 04: so the API contract can be reviewed before a screen is
built on it. The phase document's Frontend checklist is deliberately unticked.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — protected, and **now identical to `dev`** |
| Working branch | **`dev`** — pushed, merged, and an **ancestor of `main`** |
| Backend tests | **538**, up from 416 |
| CI | **Green on [#2](https://github.com/sanama-stack/reception-booking-system/pull/2)** — Backend 3m14s, Frontend 1m4s, Compose smoke test 2m24s |
| Frontend | Untouched this session |
| Migrations | **None added.** Phase 05 writes nothing and reads no table that did not exist |

### The commits

```text
a038093  Record that main is already contained in dev
e831e4a  Phase 05 — the availability engine
```

**31 files, +3398/−57** for this session's own two commits. Merged to `main` as `c2ebfec`, which
also carried the previous session's timezone fix — `git diff` between the two branches showed 35
files, and four of them were that.

### Two things about the branches that are now settled

**`main` is up to date, and the merge was a merge commit rather than a squash.** Every previous
merge was a squash, which is what four handoffs prescribed — and §5.1 is what that cost. `dev` is
now a genuine ancestor of `main`, so the next pull request has a real merge base and phase 06 will
not re-collide on every file phase 05 touched.

**The timezone fix from the previous session has now been seen by CI.** It had been sitting on
`dev` unmerged with only a local test run behind it. It went green on a machine that is not this
one, which is the whole point of the exercise.

Branch protection is unchanged: `Backend`, `Frontend` and `Compose smoke test` required, strict, **0
approving reviews**, administrators exempt.

---

## 2. Running it

Unchanged. `make up`, then the backend from the IDE and `pnpm dev` from a terminal. Open
**http://localhost:9080**, never 9082.

`JAVA_HOME` must point at a JDK 21 for Gradle; the system JDK is 25 and Gradle 8.14 dies on it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd backend && ./gradlew build
```

**The full suite now takes about 2m40s**, essentially unchanged despite 122 new tests — almost all
of them are pure and cost nothing. Phase 05 adds no Spring context.

**Nothing in this phase needs the backend restarted to be tested**, because everything worth
checking is in the suite. That is not true of the two open items in §8, both of which do.

---

## 3. What exists now

**`dev.reception.scheduling.domain` — pure, and now enforced as such:**

| | |
|---|---|
| `TimeRange` | half-open `[start, end)` over instants. Union, intersection, subtraction, clip, overlap. **The only range algebra in the system** |
| `WeeklyInterval` | one wall-clock interval on one day of week — a row of Business Hours *or* of a Working Schedule, deliberately one type |
| `WallClock` | package-private. The single place a local time becomes an instant, and the two rules for doing it — see §5.2 |
| `SlotGenerator` | the grid, anchored at local midnight |
| `AvailabilityEngine` | `findSlots` and `isSlotBookable` |
| `AvailabilityQuery`, `BusinessSchedulingConfig`, `EmployeeAvailabilityInput`, `ServiceSpec` | the inputs |
| `AvailabilityResult`, `Slot`, `Slot.Day`, `EmptyReason`, `UnbookableReason` | the outputs |

**`dev.reception.scheduling.application`** — `AvailabilityService`, and `web/AvailabilityController`
plus `web/AvailabilityResponses`.

**One endpoint**, `@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")` like every other tenant surface:

```text
GET /availability?serviceId=&from=&to=&employeeId=
```

**Three new error codes** — `SERVICE_INACTIVE`, `EMPLOYEE_INACTIVE`,
`EMPLOYEE_CANNOT_PERFORM_SERVICE`.

**A fifth method on `AppointmentImpact`** — `blockedRangesFor`, and its `BlockedRange` record. See
§4.1.

**Tests** — five new classes: `TimeRangeTest`, `SlotGeneratorTest`, `AvailabilityEngineTest`,
`SlotBookabilityTest` (all pure, sharing `AvailabilityFixture`), and `AvailabilityEndpointTest` and
`AvailabilityIsolationTest` (integration).

**`LayeringTest` lost an `allowEmptyShould`** — see §4.6.

---

## 4. The decisions that will shape phase 06 onward

### 4.1 The busy-range seam is a fifth question on `AppointmentImpact`, not a new port

The engine needs each Employee's confirmed Appointments as blocked ranges, and `appointments` is a
phase-06 table. Three options were put to the owner; this was the choice.

The reason is the one the phase-04 handoff gave for growing that port rather than multiplying it,
and it is stronger here: **two stubs are two chances to leave one behind, and this one would not
fail loudly.** Two competing beans stop the context from starting. A forgotten `EmptyBusyRanges`
goes on reporting every Employee free forever, and the system offers Slots that are already booked.

**`EmptyAppointmentImpact` is still the single class phase 06 deletes**, and it now has five methods
rather than four. Its TODO says delete, not "change the return values", and
`blockedRangesFor` returning `Map.of()` is the one that turns into a defect the moment appointments
exist.

The cost accepted: the port is now named for one of its five uses. A rename was offered and
declined as churn across phase 03 and 04 code.

### 4.2 One range algebra, over instants only

`open ∩ working` could have been intersected as wall-clock `LocalTime` and converted afterwards. It
is converted **first** and intersected as `TimeRange`, so there is one algebra in the system rather
than two that could drift apart. Closures, Time Off and Appointments are already stored as instants
for the same reason (ADR-0003), and they now all subtract through the same code.

**`TimeRange.union` merges *adjacent* ranges even though `overlaps` says adjacent ranges do not
overlap.** Both are correct and they are not in tension:

- Two Appointments at 15:00–16:00 and 16:00–17:00 are two bookings, and `overlaps` must say false or
  back-to-back stops being bookable. This is the `'[)'` bound on the exclusion constraint, in Java.
- An owner who enters Business Hours as 09:00–12:00 and 12:00–17:00 is open **continuously**, and a
  two-hour Service must be offered at 11:30. If `union` did not merge them, the answer would depend
  on how the owner happened to split the rows.

`TimeRangeTest` asserts both halves. If you ever "fix" one of them, the other test tells you why not.

### 4.3 `CLOSED` is decided against the Service, not against the calendar

The first implementation reported `FULLY_BOOKED` for a two-hour Service in a business open for one
hour. Nothing was booked. A test caught it.

The reason is now set from whether any candidate start *fits* — which is a question about the
Service, not about the window. `EmptyReason`'s precedence is written out in the enum and each value
has its own test:

1. **`NO_ELIGIBLE_EMPLOYEE`** — nobody can perform it at all.
2. **`OUTSIDE_HORIZON`** — wins twice: when no moment in the requested dates is bookable, and when
   the Service *would* have fitted somewhere and every one of those places is out of bounds. Someone
   asking about last Sunday is told the date has passed, not that the shop shuts on Sundays.
3. **`CLOSED`** — nothing in the range could have held it.
4. **`FULLY_BOOKED`** — it fits somewhere and every such place is taken. The only reason that can
   change on its own.

**What this means for you:** phase 09's Receptionist reads these to explain itself. If you add a
fifth, put it in the enum in precedence order and give it its own scenario — the enum's declaration
order *is* the algorithm.

### 4.4 `isSlotBookable` keeps hours and schedule separate; `findSlots` intersects them

The two entry points ask the same questions in different shapes. `findSlots` needs only the
intersection. `isSlotBookable` tests Business Hours and the Working Schedule **separately**, purely
so its refusal can name which one was missed: `OUTSIDE_BUSINESS_HOURS` means try another time,
`OUTSIDE_WORKING_HOURS` means try another person.

`isSlotBookable` returns a domain `UnbookableReason`, not an `ErrorCode`. The engine knows nothing
about HTTP; **phase 06 maps these onto the published codes** at the edge where a status is chosen,
which is the same place every other error body is produced.

It is **not** the last line of defence — the exclusion constraint is (ADR-0002). It is what turns a
lost race into a sentence a person can act on rather than a constraint violation.

### 4.5 The two entry points are tested against each other

`SlotBookabilityTest` closes the loop in both directions: every Slot `findSlots` offers must pass
`isSlotBookable`, and every grid start it withholds must be refused with a reason.

Two entry points into one set of rules is exactly where a system starts contradicting itself, and
the contradiction would otherwise surface in phase 06 as a booking refused seconds after being
offered — which reads as a race and is not one. **Keep these two tests when you change either
method.**

### 4.6 `LayeringTest` lost its `allowEmptyShould` for the engine

The rule that the availability engine performs no I/O was declared in phase 01 against an empty
package, so the commit filling it would arrive already governed. The package is now full and the
flag is gone: from here, an empty result would mean the package had been renamed or emptied and the
rule was passing by finding nothing to check.

Same reasoning as the four rules phase 03 tightened. **Do not add it back to get a red build green.**
`LayeringTest`'s AI rule keeps its flag, because `dev.reception.ai` is still empty until phase 09.

### 4.7 The reads are per Employee, and that is a scale decision worth revisiting

`AvailabilityService` calls the existing `EmployeeScheduleService.read` and `TimeOffService.list`
once per eligible Employee rather than adding range-scoped bulk repository methods. One tenancy
rule, no second copy of the `404` behaviour, and no new query shapes to keep in step with the
`@TenantScoped` rule — at the cost of two queries per Employee.

Appointments are already fetched for **every** Employee in one call, because that is the read that
grows with the calendar rather than with the team.

**What this means for you:** if phase 08 makes this hot on the public booking page, the schedules and
Time Off are the two to batch, and `blockedRangesFor` is already shaped that way to copy.

---

## 5. Traps already paid for

### 5.1 A squash-merged `main` conflicts with `dev` on every file, and none of them are real

**This is the one to read before opening the next pull request.** GitHub reported
[#2](https://github.com/sanama-stack/reception-booking-system/pull/2) as `CONFLICTING` on six files
including `AppointmentImpact.java` and `ErrorCode.java`, three of which this session had not touched
in any conflicting way.

The cause: `main` was squash-merged from `dev`, so it holds those files with **no shared history for
them**. Git cannot find a merge base and reports `add/add` on every one.

The fix is not to resolve them by hand — resolving by hand is how a real difference gets silently
discarded in the noise. Prove there is nothing to resolve instead:

```bash
git rev-parse origin/main^{tree}          # the tree main actually holds
git rev-parse c9c96b5^{tree}              # the dev commit it was squashed from
diff <(git diff origin/main origin/dev) <(git diff c9c96b5 origin/dev)
```

The two tree hashes were identical, and the two diffs were identical, which proves `dev` already
contained everything on `main`. That makes `git merge -s ours origin/main` provably a no-op on
content — **and the way to check is to compare the tree hash before and after the merge commit**,
which this session did. If the tree changes, `-s ours` has thrown something away and you must stop.

**The merge to `main` was then a merge commit, not a squash**, so this cannot happen again: `dev` is
a genuine ancestor of `main` and the next pull request has a real merge base. That reverses what
four earlier handoffs prescribed, and it is the owner's decision, taken for this reason.

### 5.2 `ZonedDateTime.of` moves a non-existent local time instead of dropping it

The biggest technical trap in the phase, and it is invisible at the call site.

On a spring-forward day the local hour 02:00–03:00 does not exist. `ZonedDateTime.of` **shifts a
local time in the gap forward by the length of the gap**, so 02:00 becomes 03:00 and 02:30 becomes
03:30 — landing on candidates that are already there. An interval is not shortened by the missing
hour; it is *moved*, and the duplicate slots it produces look like a grid bug somewhere else
entirely.

`WallClock` is the only place this is handled, and it handles it two different ways on purpose:

| | |
|---|---|
| `exact` — a **candidate start** | returns empty for a gap time. There is no such moment to book |
| `boundary` — an **interval end** | clamps a gap time to the transition instant. A boundary cannot be dropped; an interval needs two ends |

Clamping is what makes the missing hour *disappear from the day* rather than displace the rest of
it. A business open 01:00–09:00 that day is open for seven real hours; one open only 02:00–02:30 is
not open at all, and `WeeklyInterval.on` returns nothing for it.

Both resolve the **repeated** hour on a fall-back day to its earlier occurrence, which is the rule
ADR-0003 states. `ZoneRules.getValidOffsets` returns the pre-transition offset first, and the larger
offset yields the earlier instant, so taking index 0 is that rule.

**If you write another wall-clock conversion, route it through `WallClock`.** A second conversion
would be a second chance to get exactly this wrong, and it would be wrong on two days a year.

### 5.3 `UUID.compareTo` is not lexicographic order

FR-5 specifies the employee tie-break as "fewest appointments that day, then **lexicographic** id".
`UUID.compareTo` compares two *signed* longs, so it is a different order, and the difference is
visible at the sign boundary — verified rather than assumed:

```text
UUID.compareTo puts first:  80000000-0000-0000-0000-000000000000
string order puts first:    7fffffff-ffff-ffff-ffff-ffffffffffff
```

Both are deterministic, so no test of *determinism alone* can tell them apart. Only one matches what
the specification says and what a person comparing two ids would predict.
`AvailabilityEngine.byPreference` compares `employeeId().toString()`.

### 5.4 A suite that runs in UTC cannot see a timezone bug

Carried forward from the previous session's §7 and worth restating because this phase is the reason
it matters. The test JVM runs in `Pacific/Kiritimati` (UTC+14).

The DST cases here do not rely on it — they name `Europe/Berlin` and their dates explicitly, so they
are hostile on every machine and in every month. **That is the standard to hold new time tests to.**
`AvailabilityFixture` exists so no case can accidentally read `LocalDate.now()`; the integration
tests take their dates from the injected `Clock` and pin the *weekday*, because a business open
Monday to Friday has nothing to say about a Sunday and a test that drifted onto one would fail once
a week.

### 5.5 `@JsonInclude` with no argument is a no-op

There is no global `default-property-inclusion` in this application, so Jackson already emits
`null`s and the annotation asserted a setting that does not exist. Removed. `emptyReason` serialises
as an explicit `null`, which is what the client needs: "there is no reason" and "this server did not
answer" must be distinguishable.

### 5.6 Everything from the earlier handoffs still applies

Particularly the stale-backend check (phase-04-frontend §2 and §5.1), `pnpm build` against a tree
running `pnpm dev`, and `noUncheckedIndexedAccess` — all three of which the frontend half will meet.

---

## 6. What is not covered by a test

Stated so it is not mistaken for coverage that exists.

- **The engine has never seen a real Appointment.** Every busy range in every test is either
  hand-made in a unit case or comes from Time Off or a Closure. `EmptyAppointmentImpact
  .blockedRangesFor` returns `Map.of()` unconditionally, so the integration tests exercise the
  port's *contract* and not its answer. **Phase 06 owes the real cases**, and they are the ones the
  exclusion constraint is there to catch disagreeing.
- **The tie-break has never run against a real Appointment either.** "Fewest appointments that day"
  is exercised only in the unit suite, with synthetic ranges. Until phase 06 it is always zero for
  everyone in production, so the rule that actually fires is the lexicographic id.
- **`isSlotBookable` has no caller in production code.** It is fully tested and entirely unused
  until phase 06 calls it. That is deliberate — the phase document asks for it — but it means the
  first time it runs for real, it runs in the write path.
- **Slot intervals 5, 10 and 20 are untested.** The schema permits them; the suite covers 15, 30 and
  60, which is what docs/08-testing-strategy.md §4 asks for. A five-minute grid is the one that
  makes a 31-day range expensive.
- **There is no rate limit on `/availability`.** It is behind a session today, so the exposure is
  bounded; **phase 08 makes this shape public** and the 31-day ceiling is per request, not per
  caller. The filter is general and the policy is a list — this endpoint needs its own entry then.
- **Nothing in this phase has been seen in a browser**, because there is no frontend for it. That is
  why the two Frontend Definition-of-Done boxes are left unticked.
- **No `STAFF` role test**, unchanged since phase 03: `STAFF` has no login in the MVP, so there is
  no way to obtain a session to be refused.
- **`safeNextPath` still has no test file.** Carried forward unchanged since the session-aware-pages
  handoff.
- **The per-Employee reads are untested for scale.** See §4.7.

### What *is* covered, and unusually well

122 new tests. `TimeRange` has its full algebra including the half-open boundary cases; the engine
has every case in docs/08-testing-strategy.md §4 including both DST transitions, a booking that
spans the spring-forward gap and is two real hours while the wall clock says three, a no-DST control
that must answer identically, and every `EmptyReason` from its own scenario. `SlotBookabilityTest`
checks the two entry points against each other in both directions.

---

## 7. Rules that are enforced, not merely written down

Added to the phase 01–03 lists.

| Rule | Enforced by |
|---|---|
| The availability engine performs no I/O | `LayeringTest` — **no longer `allowEmptyShould`** |
| `/availability` refuses another tenant's Service and Employee with `404` | `AvailabilityIsolationTest` |
| An answer is shaped by the caller's own configuration alone | `AvailabilityIsolationTest` |
| Every Slot offered is bookable, and every one withheld is refused with a reason | `SlotBookabilityTest` |

---

## 8. Open items

Everything in §8 of the phase-04-frontend handoff still stands unless listed below. Changed or added:

- **`main` and `dev` are identical, and `dev` is an ancestor of `main`.** The two items that had been
  open across five handoffs — the unmerged branch and the unopened pull request — are both closed,
  and §5.1 says why they will stay easier to close.
- **The phase 05 frontend is not started.** See §9.
- **The five stale `business_hours` rows are still shifted**, and were confirmed so at the end of
  this session: Monday to Friday read `05:00–13:00` for a business that opens at 09:00. **Correct
  them before trusting anything the engine computes from them** — every Slot is derived from these
  rows, so an availability preview against this database will be eight hours wrong and look like an
  engine bug.

  ```bash
  docker compose exec -T postgres psql -U reception -d reception \
    -c "update business_hours set opens_at = opens_at + interval '4 hours', \
        closes_at = closes_at + interval '4 hours';"
  ```

  **The `4 hours` is this machine's own offset**, which is why this is a command run once and not a
  migration. Re-entering the week under `/settings/hours` does the same job.
- **The backend must be restarted** before the previous session's timezone fix takes effect. It was
  running the old configuration then and there is no reason to think it is not still.
- **`EmptyAppointmentImpact` now has five methods and is still the last stub standing.** Phase 06
  deletes it. Do not change its return values — and note that `blockedRangesFor` is the one whose
  wrong answer is silent.
- **`aiEnabled` and `aiDailyCostCapCents` still have no UI.** Phase 09 owes them.
- **Node 20 deprecation warnings in CI** — unchanged, warnings only.

---

## 9. Next: the phase 05 frontend, then phase 06

### The frontend half

Read the **Frontend** section of `docs/phases/phase-05-availability-engine.md` and the **Notes from
the build** appended this session. One screen's worth of work:

- **An availability preview on the employee detail screen.** Pick a service and a date, see the
  computed slots. Minimal by design — this phase exists to prove the engine, not to build UI.
- **Loading, empty and error states**, and the empty state must **show the reason**. That is the
  whole point of `emptyReason` existing, and it is the cheapest way to see the §4.3 precedence
  behaving in a real browser.

What this session leaves you:

1. **The response shape is final** and matches docs/04-api-overview.md §5 exactly. `timezone` is on
   the envelope, times carry the business offset, and every slot has its employee.
2. **`formatIsoDate` and the `lib/time` rule apply unchanged.** The response gives you an offset and
   a zone; do not reach for the browser's locale formatter, which ESLint forbids anyway.
3. **The employee detail screen already stacks four sections** that each hold their own resource and
   save independently. A preview is a fifth of the same shape — `useResource` + `ResourceGate` — and
   it reads rather than writes, so it needs no `changedFields`.
4. **Correct the five rows in §8 first.** Otherwise the preview's first honest answer will look
   wrong.

### Phase 06

Read `docs/phases/phase-06-appointments.md` in full, and §4 of this document before it. Three things
this session leaves you:

1. **Delete `EmptyAppointmentImpact` before writing the real one** — not "change its return values".
   Two candidate beans is the failure you want.
2. **`isSlotBookable` is the check to call**, and `UnbookableReason` is the enum to map onto the
   published error codes. Do not re-derive the rules at the write path; `SlotBookabilityTest` is
   what guarantees they agree today, and a third copy would not be covered by it.
3. **The engine is not the arbiter — the exclusion constraint is** (ADR-0002). `isSlotBookable` is
   what makes a refusal explainable. Both are needed, and neither substitutes for the other.
