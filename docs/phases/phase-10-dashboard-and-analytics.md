# Phase 10 — Calendar, Analytics and Dashboard Polish

## Goal

The owner can run the business from one screen: a real calendar, meaningful numbers, and every screen in
the product finished to the same standard.

## Scope

**In:** day/week calendar, the analytics summary endpoint and screen, dashboard home, cross-cutting
empty/loading/error polish, responsive passes.

**Out:** custom report builders, exports, cohort analysis, charts beyond what the summary needs.

## Dependencies

Phase 06 for appointments; placed after phase 09 so AI-created appointments are visible in the calendar and
counted in the analytics from day one.

## Why this phase is small

Most dashboard screens shipped with their backing phase — services with the services API, employees with
employees, appointments with appointments. Concentrating them here would have made phases 3–6 impossible to
exercise by hand and made this phase larger than any other. What remains is genuinely what could not be
built earlier.

## Technical work

### The calendar

Day and week views, appointments positioned and **sized by real duration** — a 150-minute colour must look
three times a 50-minute cut, or the view is decorative.

- Columns per employee in day view; days across in week view
- Always rendered in the **business** timezone, regardless of the browser's
- Current-time indicator
- Click an appointment → detail drawer; click an empty region → pre-filled new-appointment flow
- Closures and time off rendered as distinct non-bookable regions, so an empty column is explicable
- One request per view: `GET /appointments?from=&to=` — no per-day fetching

### Analytics

One endpoint, one screen. The shape is fixed in [04-api-overview.md](../04-api-overview.md) §5.

Three correctness points that are easy to get wrong:
- **Revenue counts `COMPLETED` only**, from the snapshotted price — never a join to the live service price
- **Date boundaries follow the business timezone**, not the server's or the browser's
- **Rates are `null`, not `0`, when the denominator is zero** — "0% cancellation" from zero appointments is
  a lie the owner will act on

Implemented as aggregate SQL, not by loading appointments into memory.

### Polish pass

A sweep across every screen built in phases 02–09:

| Standard | Applies to |
|---|---|
| Empty state naming the next action | Every list |
| Skeleton or spinner | Every async region |
| Server-message error state with a retry | Every mutation |
| Confirmation dialog | Every destructive action |
| Optimistic update or a clear pending state | Every toggle |
| Keyboard-navigable, labelled forms | Every form |
| Usable at 360 px | Every screen |

## Database work

None. Analytics uses aggregate queries over existing tables; if a query is slow at 10 000 appointments, the
fix is an index in this phase, not a new table.

## Backend work

- `AnalyticsService` — aggregate SQL for counts, periods, revenue, rates, top services
- `AnalyticsController` — `GET /analytics/summary`
- Range validation (≤ 366 days, `from ≤ to`), all boundaries resolved in the business timezone
- Calendar-range query returning appointments with employee, service, customer and status in one call
- Endpoints returning closures and time off for a range, for calendar rendering

## Frontend work

- `/calendar` — day and week views, employee columns, duration-sized blocks, current-time line
- Detail drawer, click-to-create, closure and time-off regions
- `/analytics` — metric cards, a status breakdown, top-services table, range picker
- Dashboard home — today's appointments, next appointment, quick stats, onboarding checklist while incomplete
- The polish sweep across all earlier screens
- Consistent business-timezone formatting audited across the whole app

## Testing

### Unit
- [x] Revenue sums only `COMPLETED`, using snapshotted prices
- [x] Rates are `null` when the denominator is zero
- [x] Period boundaries (today / this week / this month) computed in the business timezone
- [x] Top services ordered correctly, ties broken deterministically

### Integration
- [x] Summary counts are correct for a seeded range
- [x] Changing a service price afterwards does not change historical revenue
- [x] An empty range returns zeroes and `null` rates, not an error
- [x] Range over 366 days → `422`
- [x] A business in `Pacific/Auckland` gets different daily boundaries than one in `America/New_York` for
      the same instants — **proven by moving one business rather than comparing two**: 16:00 in Tbilisi
      is midnight the following day in Auckland, so the same instant changes date with no appointment
      moving. Sharper than the two-business form, because a UTC-cut report answers identically twice
- [x] The calendar range query returns everything the view needs in one call
- [x] Isolation probes for the analytics and calendar endpoints

### Frontend
- [ ] Appointment blocks are proportional to duration
- [ ] Times render in the business timezone with the browser set to a different one
- [ ] Every list has an empty state
- [ ] Every mutation has loading and error states
- [ ] Calendar and analytics are usable at 360 px

## Definition of Done

- [ ] Day and week calendar views render appointments sized by real duration
- [ ] AI-created appointments are visibly badged
- [ ] Closures and time off are visible as non-bookable regions
- [ ] Analytics reports counts, periods, revenue, rates and top services correctly
- [ ] Revenue is immune to later price changes
- [ ] Every screen in the product has empty, loading and error states
- [ ] Every screen is usable at 360 px
- [ ] All times everywhere display in the business timezone

## Checklist

### Backend
- [x] `AnalyticsService` with aggregate SQL
- [x] `AnalyticsController`
- [x] Range validation with business-timezone boundaries
- [x] Calendar-range appointment query
- [x] Closure and time-off range endpoints
- [ ] Indexes verified against a 10 000-appointment dataset

### Frontend — calendar
- [ ] Day view with employee columns
- [ ] Week view
- [ ] Duration-proportional blocks
- [ ] Current-time indicator
- [ ] Detail drawer
- [ ] Click-empty-region to create
- [ ] Closure and time-off regions
- [ ] AI source badge

### Frontend — analytics
- [ ] Range picker with sensible presets
- [ ] Metric cards
- [ ] Status breakdown
- [ ] Top services table
- [ ] Empty state for ranges with no data

### Frontend — home and polish
- [ ] Dashboard home with today's appointments and quick stats
- [ ] Onboarding checklist while incomplete
- [ ] Empty-state sweep
- [ ] Loading-state sweep
- [ ] Error-state sweep
- [ ] Confirmation dialogs on destructive actions
- [ ] Responsive sweep at 360 px
- [ ] Timezone-formatting audit across every screen

### Testing
- [ ] All unit tests above
- [ ] All integration tests above
- [ ] Isolation probes for new endpoints
