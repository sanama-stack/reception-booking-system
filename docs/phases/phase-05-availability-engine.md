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
- [ ] Union of overlapping, adjacent and disjoint ranges
- [ ] Intersection incl. empty results
- [ ] Subtraction producing zero, one and two remainders
- [ ] Adjacent ranges do **not** overlap (half-open semantics)

### Unit — engine
- [ ] Service fits exactly in the remaining time
- [ ] Service one minute too long → no slot
- [ ] Working schedule wider than, narrower than, and disjoint from business hours
- [ ] Existing appointment before, after, abutting (both offered), containing, partially overlapping
- [ ] Buffer blocks the neighbouring slot
- [ ] **Trailing buffer past closing does not remove the final slot**
- [ ] Buffers on both sides
- [ ] Slot intervals 15 / 30 / 60; duration not a multiple of the interval
- [ ] Past slots dropped; exactly at `now + lead time` allowed; one minute earlier rejected
- [ ] At `max_advance_days` allowed; beyond it rejected
- [ ] Time off: full-day, partial-day, multi-day
- [ ] Closure removes availability for all employees
- [ ] Two employees with different schedules; one on time off
- [ ] Tie-break is deterministic across repeated runs
- [ ] Every `emptyReason` value produced by its own scenario
- [ ] **DST spring-forward:** non-existent local times skipped
- [ ] **DST fall-back:** repeated hour appears once
- [ ] A zone without DST behaves identically to the base case
- [ ] No hours, no schedule, inactive service, inactive employee → correct empty reasons

### Integration
- [ ] `GET /availability` returns correct slots for a configured business
- [ ] Range over 31 days → `422`
- [ ] Another tenant's `serviceId` → `404`
- [ ] `employeeId` not assigned to the service → `422 EMPLOYEE_CANNOT_PERFORM_SERVICE`
- [ ] Response times carry the correct offset and the business `timezone`

## Definition of Done

- [ ] The engine is pure, takes an injected `Clock`, and performs no I/O
- [ ] Every unit case above passes
- [ ] Both DST transitions are covered by explicit tests
- [ ] `GET /availability` returns slots each carrying a resolved employee
- [ ] `emptyReason` is populated whenever the result is empty
- [ ] No appointment is written anywhere in this phase

## Checklist

### Domain
- [ ] `TimeRange` with half-open semantics and full algebra
- [ ] `Slot`, `AvailabilityQuery`, `AvailabilityResult`, `EmptyReason`
- [ ] `SlotGenerator` anchored at local midnight
- [ ] `AvailabilityEngine.findSlots`
- [ ] `AvailabilityEngine.isSlotBookable`
- [ ] Employee tie-break rule
- [ ] DST handling for both transitions

### Backend
- [ ] Repository method for confirmed appointments in a range
- [ ] Repository methods for schedules, time off, closures over a range
- [ ] `AvailabilityService` loading and mapping
- [ ] `AvailabilityController`
- [ ] Range-length and argument validation
- [ ] Error codes: `EMPLOYEE_CANNOT_PERFORM_SERVICE`, `SERVICE_INACTIVE`

### Frontend
- [ ] Availability preview on the employee detail screen
- [ ] Loading, empty (with reason) and error states

### Testing
- [ ] Full `TimeRange` unit suite
- [ ] Full engine unit suite, including both DST cases
- [ ] Integration tests for the endpoint
- [ ] Isolation probes for `/availability`
