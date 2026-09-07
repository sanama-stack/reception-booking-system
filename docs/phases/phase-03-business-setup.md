# Phase 03 — Business Setup

## Goal

An owner can fully configure their business: identity, timezone, opening hours, closures, booking policy
and the knowledge the Receptionist will later use — and the dashboard tells them what is still missing.

## Scope

**In:** full `businesses` columns, `business_hours` management, `business_closures`, `business_faqs`,
booking settings, the onboarding checklist endpoint, and the settings screens.

**Out:** services and employees (phase 04); anything that consumes this configuration (phase 05 onward).

## Dependencies

Phase 02 — tenancy and the business row created at registration.

## Technical work

### Timezone is load-bearing

Every later phase reads `business.timezone`. Validate it against `ZoneId.getAvailableZoneIds()` on write.
Changing it does not move stored instants but **does** change every displayed time, so the UI must confirm
before saving. This is the first place the temporal model from
[ADR-0003](../adr/0003-wall-clock-rules-utc-instants.md) becomes visible.

### Hours are replaced as a whole week

`PUT /business/hours` takes all seven days at once. Partial edits cannot then leave the week inconsistent,
and overlap validation has the whole picture. **A day with no row is closed** — absence is meaningful, not
missing data, and the UI must render it as "Closed" rather than blank.

### Closures are instants

A closure is entered as business-local dates and stored as `timestamptz` instants, so phase 05 can subtract
closures with exactly the same range algebra it uses for appointments and time off. Converting at write
time keeps one representation in the engine.

### Onboarding checklist

`GET /business/onboarding` returns real configuration state, not stored flags:

```json
{ "hoursConfigured": true, "hasActiveService": false,
  "hasActiveEmployee": false, "hasEmployeeSchedule": false,
  "hasBookableService": false, "publicPageReady": false,
  "bookingUrl": "/book/salon-aria" }
```

Derived, never persisted, so it cannot drift from reality. `hasBookableService` requires an active service
with at least one active assigned employee who has a schedule — the condition that actually determines
whether anyone can book. `publicPageReady` is the conjunction.

## Database work

`V3__businesses.sql`:
- `ALTER TABLE businesses` — description, address, city, country, phone, email, website,
  `slot_interval_minutes`, `min_lead_time_minutes`, `max_advance_days`, `cancellation_window_hours`,
  `cancellation_policy`, `ai_enabled`, `ai_additional_info`, `ai_daily_cost_cap_cents`, with all `CHECK`
  constraints from [03-data-model.md](../03-data-model.md)
- `business_closures` + index `(business_id, starts_at, ends_at)`
- `business_faqs`
- `UNIQUE (business_id, day_of_week, opens_at)` on `business_hours`

## Backend work

- `BusinessService`: read, patch, slug change with collision handling
- `BusinessHoursService`: whole-week replace with overlap and ordering validation
- `ClosureService`, `FaqService`: CRUD, tenant-scoped
- `OnboardingService`: derived checklist
- Controllers per [04-api-overview.md](../04-api-overview.md) §5
- Validators: IANA zone, ISO-4217 currency, ISO-3166 country, slug format, FAQ count ceiling

## Frontend work

- `/settings/profile` — identity, address, contacts, **timezone with a confirmation dialog**
- `/settings/hours` — seven-day editor with copy-to-all, closed toggle, inline overlap errors
- `/settings/closures` — list plus a date-range form
- `/settings/booking` — slot interval, lead time, horizon, cancellation window and policy, each with a
  plain-language explanation of its effect
- `/settings/faqs` — CRUD list with reordering
- Dashboard home — onboarding checklist with links to the next incomplete step

## Testing

### Unit
- [ ] Hours overlap detection, including adjacent intervals that must be allowed
- [ ] `closes_at <= opens_at` rejected
- [ ] Timezone validation accepts real zones, rejects `Europe/Atlantis`
- [ ] Onboarding derivation for every combination of configured state
- [ ] Closure local-date → instant conversion, including across a DST boundary

### Integration
- [ ] Patch each profile field and read it back
- [ ] Slug change updates the public URL; the old slug stops resolving
- [ ] Duplicate slug → `409 SLUG_TAKEN`
- [ ] Whole-week hours replace is atomic — invalid day 5 leaves days 1–4 unchanged
- [ ] A day with no row reads back as closed
- [ ] Closure CRUD, and a closure over existing appointments reports the affected count
- [ ] FAQ CRUD; the 51st FAQ is rejected
- [ ] `ai_additional_info` over 2000 chars rejected
- [ ] Every setting outside its `CHECK` range rejected with `VALIDATION_FAILED`
- [ ] **Isolation:** every endpoint here probed with another tenant's id → `404`

## Definition of Done

- [ ] An owner configures every business field, including timezone
- [ ] Hours are editable per day and a closed day is unambiguous
- [ ] Closures and FAQs are manageable
- [ ] All booking settings are editable and range-validated
- [ ] The onboarding checklist reflects real state and links to the next step
- [ ] Isolation probes added for all new endpoints

## Checklist

### Database
- [ ] `V3__businesses.sql` with all columns and `CHECK`s
- [ ] `business_closures` table + index
- [ ] `business_faqs` table
- [ ] `business_hours` unique constraint

### Backend
- [ ] Extend the `Business` entity
- [ ] `BusinessClosure`, `BusinessFaq` entities and repositories
- [ ] `BusinessService` read/patch/slug-change
- [ ] `BusinessHoursService` whole-week replace with validation
- [ ] `ClosureService`, `FaqService`
- [ ] `OnboardingService`
- [ ] Controllers for business, hours, closures, FAQs, onboarding
- [ ] Timezone, currency, country and slug validators
- [ ] FAQ count and text-length limits
- [ ] Error codes: `SLUG_TAKEN`

### Frontend
- [ ] `/settings/profile` with timezone confirmation dialog
- [ ] `/settings/hours` seven-day editor with copy-to-all
- [ ] `/settings/closures`
- [ ] `/settings/booking` with explanatory copy
- [ ] `/settings/faqs` with reordering
- [ ] Onboarding checklist on the dashboard home
- [ ] Empty, loading and error states on every screen

### Testing
- [ ] All unit tests above
- [ ] All integration tests above
- [ ] Isolation probes for six new endpoint groups
