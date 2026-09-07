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
- [x] Hours overlap detection, including adjacent intervals that must be allowed
- [x] `closes_at <= opens_at` rejected
- [x] Timezone validation accepts real zones, rejects `Europe/Atlantis`
- [x] Onboarding derivation for every combination of configured state
- [x] Closure local-date → instant conversion, including across a DST boundary

### Integration
- [x] Patch each profile field and read it back
- [x] Slug change updates the public URL; the old slug stops resolving
- [x] Duplicate slug → `409 SLUG_TAKEN`
- [x] Whole-week hours replace is atomic — invalid day 5 leaves days 1–4 unchanged
- [x] A day with no row reads back as closed
- [x] Closure CRUD, and a closure over existing appointments reports the affected count — *the field is reported and covered; the count itself is `0` until phase 06 gives appointments somewhere to exist (see notes)*
- [x] FAQ CRUD; the 51st FAQ is rejected
- [x] `ai_additional_info` over 2000 chars rejected
- [x] Every setting outside its `CHECK` range rejected with `VALIDATION_FAILED`
- [x] **Isolation:** every endpoint here probed with another tenant's id → `404`

## Definition of Done

- [ ] An owner configures every business field, including timezone
- [ ] Hours are editable per day and a closed day is unambiguous
- [ ] Closures and FAQs are manageable
- [ ] All booking settings are editable and range-validated
- [ ] The onboarding checklist reflects real state and links to the next step
- [x] Isolation probes added for all new endpoints

## Checklist

### Database
- [x] `V3__businesses.sql` with all columns and `CHECK`s
- [x] `business_closures` table + index
- [x] `business_faqs` table
- [x] `business_hours` unique constraint

### Backend
- [x] Extend the `Business` entity
- [x] `BusinessClosure`, `BusinessFaq` entities and repositories
- [x] `BusinessService` read/patch/slug-change
- [x] `BusinessHoursService` whole-week replace with validation
- [x] `ClosureService`, `FaqService`
- [x] `OnboardingService`
- [x] Controllers for business, hours, closures, FAQs, onboarding
- [x] Timezone, currency, country and slug validators
- [x] FAQ count and text-length limits
- [x] Error codes: `SLUG_TAKEN`

### Frontend
- [ ] `/settings/profile` with timezone confirmation dialog
- [ ] `/settings/hours` seven-day editor with copy-to-all
- [ ] `/settings/closures`
- [ ] `/settings/booking` with explanatory copy
- [ ] `/settings/faqs` with reordering
- [ ] Onboarding checklist on the dashboard home
- [ ] Empty, loading and error states on every screen

### Testing
- [x] All unit tests above
- [x] All integration tests above
- [x] Isolation probes for six new endpoint groups

---

## Notes from the build

*The backend half. The settings screens and the dashboard checklist are not built yet, and their
checklist boxes above are unticked accordingly.*

### Two dependencies on phases that do not exist yet

The onboarding checklist publishes `hasActiveService`, `hasActiveEmployee` and `hasEmployeeSchedule`,
and FR-2 requires a new closure to report how many Appointments it covers. Services and Employees
arrive in phase 04; Appointments in phase 06.

Both are bridged by a **port** — `CatalogReadiness` and `AppointmentImpact` — declared in
`business` and implemented there by `EmptyCatalogReadiness` and `EmptyAppointmentImpact`, which
answer *nothing configured* and *zero*. Those answers are true today rather than placeholders.

The alternative, omitting the fields and adding them later, changes a published response shape twice
and forces the dashboard checklist to be rebuilt. This way the shape is final from the first commit,
and the derivation is testable across all sixteen combinations today —
`OnboardingDerivationTest` covers states phase 03 cannot even reach through its own API.

**Each stub says `TODO(phase-04)` / `TODO(phase-06)`: delete this class.** Not "replace the return
value" — delete. If phase 04 adds its implementation and leaves the stub, Spring refuses to start
with two candidate beans, which is the failure we want. A stub left as a fallback fails the other
way: silently, by continuing to answer "no" after the catalog exists.

### Decisions taken

**A requested slug that is taken is refused, not suffixed.** Registration suffixes — it is deriving
a slug nobody asked for, and `salon-aria-2` is a reasonable thing to hand someone who never chose an
address. An owner who *types* `salon-aria` and silently receives `salon-aria-2` has been given a
different public URL than the one they picked, and will discover it after printing it on something.
`409 SLUG_TAKEN`.

**`PATCH` semantics: absent leaves, blank clears, a value sets.** The three cases live in one
place, `Business.apply`. Blank-clears is what an emptied form input already sends, so clearing needs
no separate gesture, and "set this to the empty string" is not a state distinct from "not set". The
four required fields — name, slug, timezone, currency — take only absent-or-value, enforced with
`@Size(min = 1)` rather than `@NotBlank`, which gives exactly that rule.

**Times are serialised `HH:mm`, not Jackson's ISO `HH:mm:ss`.** That is the shape
[04-api-overview.md](../04-api-overview.md) §5 publishes and the shape `<input type="time">` both
produces and expects. The request side is left on the ISO default, which accepts both: strict in
what we send, liberal in what we accept.

**Configuration requires `OWNER` or `ADMIN`,** declared once on the controller class rather than
per method ([06-security.md](../06-security.md) §3). `STAFF` has no login in the MVP, so this is the
rule arriving before the role that would exercise it — and there is therefore no test for the
refusal, only for the rule's presence.

**A closure's `endDate` is inclusive; its stored `ends_at` is not.** "Closed the 24th to the 26th"
includes the 26th, which is what a person means. Half-open is what the availability engine needs, so
two adjacent closures meet exactly rather than overlapping or leaving a gap. The translation happens
once, at the edge, in `ClosureService` — not in either party's head.

**`allowEmptyShould(true)` is retired from every `TenantRepositoryShapeTest` rule.** Phase 02's
handoff flagged this for exactly this phase. At one `@TenantScoped` repository an empty result meant
"not written yet"; at three it would mean the annotation had been dropped and the rule was passing
by finding nothing to check.

### The trap: adding an HTTP client changed tests that had nothing to do with this phase

`TestRestTemplate` defaults to `HttpURLConnection`, which **cannot send `PATCH`** — it fails with
"Invalid HTTP method", which reads like a routing bug and is not one. Half of this phase's endpoints
are `PATCH`.

The obvious fix is to put Apache HttpClient 5 on the test classpath; Spring detects it and uses it
automatically, and `PATCH` starts working. That is the trap. Its default retry strategy treats
`429` as retryable **and honours the `Retry-After` header we set ourselves** — so `RateLimitTest`,
whose entire purpose is to collect 429s, stopped being a test and became a sleep. The suite hung for
forty minutes with no failure and no output.

The fix is to choose the factory explicitly rather than let the classpath choose it:
`JdkClientHttpRequestFactory` in `AuthTestClient`'s constructor. It sends `PATCH` and does nothing
else.

**The general lesson: a test-scope dependency added for one endpoint applies to every test in the
suite.** The symptom appeared in a phase-02 test, from a phase-03 change, as a hang rather than a
failure.

### Also

- **Mockito is now used, and its agent is declared.** The inline mock maker self-attaches to its own
  JVM and warns that a future JDK will refuse. `build.gradle.kts` passes `-javaagent` explicitly, so
  the suite does not start failing on a JDK upgrade.
- **`BusinessDefaults`** gathers what registration creates — zone, currency, the opening week and the
  booking policy — so "what does a new account start with" has one answer. The booking-policy values
  are duplicated as column defaults in `V3__businesses.sql`; that copy is what lets the migration
  backfill rows registration had already created.
- **The hours validator is `static` and package-private** so the overlap rules are testable without a
  database, a Spring context or a tenant. Adjacent intervals — `09:00–13:00` then `13:00–17:00` — are
  a split shift and are accepted; a validator written with `<=` rejects them and forbids the most
  natural way to express a lunch break.
- **The whole-week replace flushes between the delete and the inserts.** Hibernate orders operations
  by entity type, not by the order they were requested in, and
  `UNIQUE (business_id, day_of_week, opens_at)` does not care that the row it collides with is about
  to be deleted. `a_replace_can_reuse_the_times_it_is_replacing` is the case that fails without it.
