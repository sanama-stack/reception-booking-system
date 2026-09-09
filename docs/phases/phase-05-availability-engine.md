# Phase 05 — Availability Engine

## Goal

Given a business, a service, a date range and optionally an employee, return exactly the Slots at which that
service can genuinely be performed — correct across DST, buffers, closures, time off and the booking
horizon. **Read-only. Nothing is written in this phase.**

## Scope

**In:** `TimeRange` algebra, the pure `AvailabilityEngine`, `AvailabilityService`, the internal
`GET /availability` endpoint, and an exhaustive unit suite.

**Out:** appointment creation (phase 06), the public endpoint (phase 08), AI tools (phase 09). Existing
appointments are read as busy ranges via a repository method that returns an empty list until phase 06.

## Dependencies

Phase 04 — consumes hours, schedules, services, assignments, closures and time off.

## Why this is its own phase

This is the highest-risk logic in the product and the easiest to get subtly wrong. Isolating it means it can
be tested to exhaustion with no database writes, no HTTP, and no concurrency — and when phase 06 later
produces a wrong slot, the cause is unambiguous.

## Technical work

### The engine is pure

```java
public final class AvailabilityEngine {
    public AvailabilityResult findSlots(AvailabilityQuery query, BusinessSchedulingConfig config,
                                        List<EmployeeAvailabilityInput> employees, Clock clock);
}
```

No repositories, no Spring, no I/O. The application service loads data; the engine computes. This is what
makes the suite in [08-testing-strategy.md](../08-testing-strategy.md) §4 possible.

### Algorithm

Per [01-prd.md](../01-prd.md) FR-5:

1. Resolve `ZoneId` from the business.
2. For each date, for each candidate employee:
   - `open` = union of `business_hours` for that weekday (local times)
   - `working` = union of `employee_schedules` for that weekday (local times)
   - `workable = open ∩ working`, converted to instants **in the business zone**
   - `busy` = confirmed appointments' `[blocked_from, blocked_to)` ∪ time off ∪ closures, clipped to the day
   - candidate starts on the `slot_interval_minutes` grid anchored at **local midnight**
   - keep `t` when `[t, t+duration)` fits inside one `workable` interval **and**
     `[t−buffer_before, t+duration+buffer_after)` intersects no `busy` range
3. Drop anything before `now + min_lead_time` or after `now + max_advance_days`.
4. Merge; when no employee was requested, attach a resolved employee per slot — fewest appointments that
   day, then lexicographic id.

### The decisions encoded here

| Decision | Consequence |
|---|---|
| Half-open `[start, end)` ranges | Back-to-back appointments are both bookable |
| Buffers block, but need not fit inside working hours | The last slot of the day survives |
| Grid anchored at **local** midnight | Slots stay on clean local times across DST |
| Every slot carries its employee | Booking never re-resolves, so no new race is introduced |
| `emptyReason` instead of a bare empty list | Phase 09 can explain *why*, instead of guessing |

### DST

Two cases, both explicitly tested:
- **Spring forward:** local times that do not exist are skipped. `ZonedDateTime.of` would silently shift
  them; the engine must detect and drop them.
- **Fall back:** the repeated local hour appears once, resolved to the earlier (pre-transition) instant.

## Database work

None. One repository method is added: fetch confirmed appointments for an employee within a range —
returning empty until phase 06 creates rows.

## Backend work

- `TimeRange` value object: union, intersection, subtract, overlap, clip — half-open throughout
- `AvailabilityEngine` (pure)
- `SlotGenerator` — grid generation anchored at local midnight, DST-aware
- `AvailabilityQuery`, `AvailabilityResult`, `Slot`, `EmptyReason`
- `AvailabilityService` — loads inputs, calls the engine, maps to DTOs
- `AvailabilityController` — `GET /availability`
- `isSlotBookable(...)` — the single-slot check phase 06 will call

## Frontend work

Minimal by design — this phase exists to prove the engine, not to build UI.

- A developer-facing availability preview on the employee detail screen: pick a service and a date, see the
  computed slots. Useful during the build and genuinely useful to an owner afterwards.

## Testing

The whole point of the phase. Every case in [08-testing-strategy.md](../08-testing-strategy.md) §4 is
required here, not deferred.

### Unit — `TimeRange`
- [x] Union of overlapping, adjacent and disjoint ranges
- [x] Intersection incl. empty results
- [x] Subtraction producing zero, one and two remainders
- [x] Adjacent ranges do **not** overlap (half-open semantics)

### Unit — engine
- [x] Service fits exactly in the remaining time
- [x] Service one minute too long → no slot
- [x] Working schedule wider than, narrower than, and disjoint from business hours
- [x] Existing appointment before, after, abutting (both offered), containing, partially overlapping
- [x] Buffer blocks the neighbouring slot
- [x] **Trailing buffer past closing does not remove the final slot**
- [x] Buffers on both sides
- [x] Slot intervals 15 / 30 / 60; duration not a multiple of the interval
- [x] Past slots dropped; exactly at `now + lead time` allowed; one minute earlier rejected
- [x] At `max_advance_days` allowed; beyond it rejected
- [x] Time off: full-day, partial-day, multi-day
- [x] Closure removes availability for all employees
- [x] Two employees with different schedules; one on time off
- [x] Tie-break is deterministic across repeated runs
- [x] Every `emptyReason` value produced by its own scenario
- [x] **DST spring-forward:** non-existent local times skipped
- [x] **DST fall-back:** repeated hour appears once
- [x] A zone without DST behaves identically to the base case
- [x] No hours, no schedule, inactive service, inactive employee → correct empty reasons

### Integration
- [x] `GET /availability` returns correct slots for a configured business
- [x] Range over 31 days → `422`
- [x] Another tenant's `serviceId` → `404`
- [x] `employeeId` not assigned to the service → `422 EMPLOYEE_CANNOT_PERFORM_SERVICE`
- [x] Response times carry the correct offset and the business `timezone`

## Definition of Done

- [x] The engine is pure, takes an injected `Clock`, and performs no I/O
- [x] Every unit case above passes
- [x] Both DST transitions are covered by explicit tests
- [x] `GET /availability` returns slots each carrying a resolved employee
- [x] `emptyReason` is populated whenever the result is empty
- [x] No appointment is written anywhere in this phase

## Checklist

### Domain
- [x] `TimeRange` with half-open semantics and full algebra
- [x] `Slot`, `AvailabilityQuery`, `AvailabilityResult`, `EmptyReason`
- [x] `SlotGenerator` anchored at local midnight
- [x] `AvailabilityEngine.findSlots`
- [x] `AvailabilityEngine.isSlotBookable`
- [x] Employee tie-break rule
- [x] DST handling for both transitions

### Backend
- [x] A read for confirmed appointments in a range — a fifth method on `AppointmentImpact`, not a
      repository, because `appointments` does not exist until phase 06 (see notes)
- [x] Reads for schedules, time off and closures over the range — the existing per-employee reads,
      reused rather than duplicated (see notes)
- [x] `AvailabilityService` loading and mapping
- [x] `AvailabilityController`
- [x] Range-length and argument validation
- [x] Error codes: `EMPLOYEE_CANNOT_PERFORM_SERVICE`, `SERVICE_INACTIVE`

### Frontend
- [x] Availability preview on the employee detail screen
- [x] Loading, empty (with reason) and error states

### Testing
- [x] Full `TimeRange` unit suite
- [x] Full engine unit suite, including both DST cases
- [x] Integration tests for the endpoint
- [x] Isolation probes for `/availability`


---

## Notes from the build

*Built as the backend half only, at the project owner's choice — the same split phases 03 and 04
used, so the API contract can be reviewed before the preview screen is built on it. The Frontend
boxes above are deliberately unticked.*

### The busy-range seam is a fifth question on `AppointmentImpact`, not a new port

The engine needs each Employee's confirmed Appointments as blocked ranges, and `appointments` is a
phase-06 table. The alternatives were a new `BusyRanges` port beside the existing stub, or a fifth
method on `AppointmentImpact`.

The fifth method won, for the reason the phase-04 handoff gave for growing that port rather than
multiplying it: **two stubs are two chances to leave one behind**, and this one would not fail
loudly. Two competing beans stop the context from starting; a forgotten `EmptyBusyRanges` goes on
reporting every Employee free forever, and the system offers Slots that are already booked.
`EmptyAppointmentImpact` remains the single class phase 06 deletes.

The cost is that the port is now named for one of its five uses. That was the owner's call, taken
against a rename that would have churned phase 03 and 04 code.

### The engine gets one range algebra, over instants only

`open ∩ working` could have been intersected as wall-clock `LocalTime` and converted afterwards. It
is converted first and intersected as `TimeRange` instead, so there is exactly one algebra in the
system rather than two that could drift. Business Closures, Time Off and Appointments are already
stored as instants for the same reason (ADR-0003), and they now all subtract through the same code.

`TimeRange.union` merges **adjacent** ranges even though adjacent ranges do not **overlap**. Both are
correct and they are not in tension: touching appointments are two bookings, while an owner who
enters 09:00–12:00 and 12:00–17:00 as two rows is open continuously and a two-hour Service must be
offered at 11:30. `TimeRangeTest` asserts both halves.

### `WallClock` resolves a local time two different ways, on purpose

`ZonedDateTime.of` shifts a non-existent local time *forward by the length of the gap*, so 02:00 and
02:30 on a spring-forward day become 03:00 and 03:30 — an interval that has silently **moved**
rather than shrunk, landing on candidates that are already there. That is the trap `WallClock`
exists to keep out of the engine.

- A **candidate start** in the gap is dropped; there is no such moment to book.
- An **interval boundary** in the gap is clamped to the transition instant, so the missing hour
  disappears from the day instead of displacing the rest of it. A Business open 01:00–09:00 that day
  is open for seven real hours; one open only 02:00–02:30 is not open at all, and
  `WeeklyInterval.on` returns nothing.

Both resolve the *repeated* hour to its earlier occurrence, which is the rule ADR-0003 states.

### `CLOSED` is decided against the Service, not against the calendar

The first implementation reported `FULLY_BOOKED` for a two-hour Service in a business open for one
hour, because there was workable time and no Slot survived. Nothing was booked. The reason is now
set from whether any candidate *fits* — which is about the Service — so that case reports `CLOSED`.

`OUTSIDE_HORIZON` likewise wins over `CLOSED` whenever the Service would have fitted somewhere in
the range and every one of those places is out of bounds. Someone asking about last Sunday is told
the date has passed, not that the shop shuts on Sundays. The precedence is written out in
`EmptyReason` and each value has its own test.

### `isSlotBookable` keeps hours and schedule separate; `findSlots` intersects them

The two entry points ask the same questions in different shapes. `findSlots` needs only the
intersection. `isSlotBookable` tests Business Hours and the Working Schedule separately so its
refusal can say which one was missed — `OUTSIDE_BUSINESS_HOURS` means try another time,
`OUTSIDE_WORKING_HOURS` means try another person, and phase 06 maps both onto published codes.

`SlotBookabilityTest` closes the loop in both directions: every Slot `findSlots` offers must pass
`isSlotBookable`, and every grid start it withholds must be refused with a reason. Two entry points
into one set of rules is exactly where a system starts contradicting itself, and the contradiction
would otherwise surface in phase 06 as a booking refused seconds after being offered.

### The reads are per Employee, and that is a scale decision worth revisiting

`AvailabilityService` calls the existing `EmployeeScheduleService.read` and `TimeOffService.list`
once per eligible Employee rather than adding range-scoped bulk repository methods. One tenancy
rule, no second copy of the `404` behaviour, and no new query shapes to keep in step with the
`@TenantScoped` rule — at the cost of two queries per Employee.

Appointments are already fetched for every Employee in one call, because that is the read that grows
with the calendar rather than with the team. **If the public booking page makes this hot in phase
08, the schedules and Time Off are the two to batch**, and the port is already shaped that way to
copy.

### `LayeringTest` lost its `allowEmptyShould` for the engine

The rule that the availability engine performs no I/O was declared in phase 01 against an empty
package. The package is now full, so the flag is gone — from here, an empty result would mean the
package had been renamed or emptied and the rule was passing by finding nothing to check. Same
reasoning as the four rules phase 03 tightened. Do not add it back to get a red build green.

---

## Notes from the frontend build

*The half the backend sitting deliberately left. The two Frontend boxes above are now ticked, and
the whole section was exercised in a browser against the real backend.*

### One date at a time, not a range

The endpoint takes up to 31 days; the preview asks for one. `emptyReason` describes the *whole*
result rather than each day in it, so over a range it only appears when every day is empty and it
stops meaning anything precise. A single date is the shape in which a reason means exactly one
thing — which is the point of a screen built to prove the engine. Phase 08's public page is where a
range earns its keep, and the component already renders `days` as a list rather than reaching for
`[0]`, so widening it is not a reshape.

The consequence: nothing on this screen can produce the `422` for a range over 31 days. That case is
covered by `AvailabilityEndpointTest` and by nothing on this side.

### The result is keyed on its own question

`useResource` keeps the data it last loaded when a later load fails, which is right for a screen
reloading one resource and wrong here. A previous answer is about another service or another day,
and leaving it on screen under a changed picker would state something false — and would hide the
loading and error states behind a stale success. The slot list is therefore a child component keyed
on the request path, so a new question remounts it and starts from nothing.

This is the first read in the app whose question changes while the screen is open, which is why
`availabilityPath` is a path builder rather than a call: a different question is a different string,
and the hook re-runs because the question moved and for no other reason.

### The picker resolves the service rather than storing it

The section reads `employee.serviceIds`, and the four editors above it can change that set while the
screen is open. Storing the chosen id in state would leave the picker naming a service the employee
no longer provides, and the server would then refuse a choice the owner never made with
`EMPLOYEE_CANNOT_PERFORM_SERVICE`. The selection is resolved on every render and falls back to the
first assigned service, so the failure is not expressible.

Inactive services are offered and **marked** — `Full day audit · 6 hr · not offered` — rather than
hidden. A service the owner deactivated a moment ago disappearing from the picker is the confusing
answer; the server's own `SERVICE_INACTIVE` sentence is the clear one.

### `Recalculate` exists because the four editors above change the answer

Saving a working schedule does not change the *question*, so nothing about the path moves and the
preview would go on showing an answer computed before the save. Rather than couple the section to
the other four resources, the button bumps a counter that forms part of the key. It is the one
control here that is not a question.

### The empty reason is rendered twice, on purpose

A sentence written for the owner, pointed at the setting that would change it — `OUTSIDE_HORIZON`
links to Booking settings, `CLOSED` to Opening hours — and the raw enum underneath in small type.
The phase document calls this preview developer-facing *and* useful to an owner afterwards; those
are two readers, and the enum is what the first one needs.

`NO_ELIGIBLE_EMPLOYEE` has copy but is unreachable from this screen, and that is a property of the
service rather than an oversight: the preview always names an employee, and both ways to earn that
reason — inactive, or not assigned — are refused earlier by `AvailabilityService` with their own
messages. Confirmed by deactivating the employee and getting `EMPLOYEE_INACTIVE`.

### What was verified in a browser

In a throwaway second tenant, deleted row by row afterwards, with the owner's own business confirmed
unchanged: the unassigned empty state; `CLOSED` for an employee with no working schedule; thirteen
slots on the current day where the minimum lead time cut the first four, and seventeen on a clear
day; the response's own `days[].date` echoed back; `OUTSIDE_HORIZON` for a past date, for a past
*Sunday* — the §4.3 precedence, visible — and for a date beyond `max_advance_days`; `CLOSED` for a
future Sunday and for a six-hour service against a five-hour overlap with nothing booked, which is
the case the engine's first implementation reported as `FULLY_BOOKED`; `FULLY_BOOKED` from a day of
time off; `EMPLOYEE_INACTIVE` and `SERVICE_INACTIVE` in the error state, each rendering the server's
own sentence; the loading spinner; and the cleared-date state.

**The business zone is proven rather than assumed.** The tenant was switched to `America/New_York`
(UTC−4) while the browser ran at UTC+4 — eight hours apart — and the slots read 10:00–15:00, which
is the business's clock and not the browser's.
