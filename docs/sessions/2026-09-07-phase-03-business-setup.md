# Session handoff — 2026-09-07 — Phase 03 (Business Setup), backend half

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done and
> what is not; §6 is the part that will save you the most time; §7 is the part that will stop you
> assuming coverage that is not there. **Phase 03 is half finished by design** — read §1 before
> anything else.

---

## 1. Where the project stands

**The phase 03 backend is complete and its suite is green. The five settings screens and the
dashboard checklist are not built.** That split was the project owner's choice, taken at the start
of the session so the API contract could be reviewed before screens were built on it. The phase
document's frontend checklist boxes are deliberately unticked.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — the tested branch |
| Working branch | **`dev`** — **11 commits ahead of `main`**, and **not pushed** |
| Backend tests | **234**, up from 101 |
| CI | **Not run for this work.** `dev` has not been pushed since the phase 02 handoff |
| Frontend | Untouched this session |

### The commits

```text
4397bf3  Phase 03 — the test suite, and the HTTP client trap it uncovered
641cbfb  Phase 03 — the business configuration backend
```

**Nothing is pushed.** The owner was offered `git push origin dev` and had not answered when the
session ended. Pushing is the first thing to do, because CI has never seen any of this — and §6.1 is
a defect this suite found in code CI *had* seen and passed.

---

## 2. Running it

Unchanged from the phase 02 handoff.

```bash
make up                       # Postgres, Mailpit, Caddy
# then, from the IDE: backend ReceptionApplication, frontend `pnpm dev`
```

Open **http://localhost:9080**, never 9082. To run Gradle from a terminal you must point `JAVA_HOME`
at a JDK 21 — the system JDK is 25 and Gradle 8.14 dies on it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd backend && ./gradlew build
```

The full suite now takes about **90 seconds**. If it runs much longer than that with no output,
read §6.1 before doing anything else — that is exactly what the symptom looks like.

---

## 3. What exists now

**Database** — `V3__businesses.sql`: `ALTER TABLE businesses` adding description, address, city,
country, phone, email, website, the five booking-policy columns and the three AI columns, each with
its `CHECK`; `business_closures` and `business_faqs`; and the
`UNIQUE (business_id, day_of_week, opens_at)` that V2 deliberately deferred until a writer existed
that could respect it.

**Backend**, all in `dev.reception.business`:

| | |
|---|---|
| `BusinessService` | read, patch, slug change with collision handling |
| `BusinessHoursService` | whole-week replace; `validateWeek` is `static` and package-private |
| `ClosureService` | local dates → instants, the one such conversion in the system |
| `FaqService` | CRUD and the 50-row ceiling |
| `OnboardingService` | the derived checklist |
| `BusinessValidation` | IANA zone, ISO-4217, ISO-3166, slug format — collected, not thrown one at a time |
| `BusinessDefaults` | what registration creates, in one file |
| `BusinessPatch` | the domain patch, separate from the wire DTO |
| `CatalogReadiness`, `AppointmentImpact` | the two ports described in §4 |
| `business/web/` | `BusinessController` and the request/response DTOs |

**Endpoints** — the nine from `04-api-overview.md` §5, all `@PreAuthorize("hasAnyRole('OWNER',
'ADMIN')")` declared once on the controller class:

```text
GET    /business            PATCH  /business
GET    /business/hours      PUT    /business/hours
GET    /business/closures   POST   /business/closures   DELETE /business/closures/{id}
GET    /business/faqs       POST   /business/faqs
PATCH  /business/faqs/{id}  DELETE /business/faqs/{id}
GET    /business/onboarding
```

**Tests** — nine new classes. Four unit (`BusinessHoursValidationTest`, `BusinessValidationTest`,
`OnboardingDerivationTest`, `ClosureConversionTest`) and five integration
(`BusinessSettingsTest`, `BusinessHoursEndpointTest`, `ClosureEndpointTest`, `FaqEndpointTest`,
`OnboardingEndpointTest`), plus `BusinessIsolationTest`.

---

## 4. The decisions that will shape phase 04 onward

### Two ports stand in for phases that do not exist yet

The onboarding checklist publishes `hasActiveService`, `hasActiveEmployee` and
`hasEmployeeSchedule`; FR-2 requires a new closure to report how many Appointments it covers.
Services and Employees are phase 04, Appointments phase 06.

`CatalogReadiness` and `AppointmentImpact` are the seam. Their phase-03 implementations —
`EmptyCatalogReadiness` and `EmptyAppointmentImpact` — answer *nothing configured* and *zero*, which
are true answers today rather than placeholders. The published response shape is therefore final
from the first commit, and `OnboardingDerivationTest` already covers all sixteen combinations,
including states phase 03 cannot reach through its own API.

**Each stub's TODO says to *delete the class*, not to change its return value.** If phase 04 adds a
real `CatalogReadiness` and leaves the stub, Spring refuses to start with two candidate beans. That
is the intended failure. A stub left as a fallback fails the other way — silently, by going on
answering "no" after the catalog exists.

### A requested slug that is taken is refused, not suffixed

`409 SLUG_TAKEN`. Registration suffixes, because it is deriving a slug nobody asked for and
`salon-aria-2` is a reasonable thing to hand someone who never chose an address. An owner who
*types* `salon-aria` and silently receives `salon-aria-2` has been given a different public URL
than the one they picked, and finds out after printing it on something.

**What this means for you:** a slug change moves the public booking page immediately and the old
slug stops resolving. There is no redirect and no history table. Phase 08 builds the page this
addresses; if the owner needs old URLs to keep working, that is a new decision, not an oversight.

### PATCH: absent leaves, blank clears, a value sets

Stated once, in `Business.apply`. Blank-clears is what an emptied form input already sends, so
clearing an optional field needs no separate gesture, and "set this to the empty string" is not a
state distinct from "not set". The four required fields — name, slug, timezone, currency — accept
only absent-or-value, enforced with `@Size(min = 1)` rather than `@NotBlank`, which gives exactly
that rule and nothing more.

**What this means for you:** every `PATCH` DTO in later phases should follow this, and
`@NotBlank` on a patchable field is almost always a bug — it rejects the absent case, which is the
common one.

### Times are serialised `HH:mm`

`04-api-overview.md` §5 publishes `"09:00"`, and it is what `<input type="time">` both produces and
expects. Jackson's ISO default would emit `"09:00:00"`. The `@JsonFormat` is on the *response* DTO
only; requests stay on the ISO default, which accepts both. Strict in what we send, liberal in what
we accept.

### A closure's `endDate` is inclusive, its stored `ends_at` is not

"Closed the 24th to the 26th" includes the 26th — that is what a person means. The stored range is
half-open, so two adjacent closures meet exactly rather than overlapping or leaving a gap, which is
what the phase 05 engine needs. The translation happens once, at the edge, in `ClosureService`.

**What this means for phase 05:** closures arrive as half-open instant ranges and need no special
handling. Do not re-derive them from dates.

### The checklist is derived on every read

A stored checklist becomes wrong the moment an owner deactivates their last employee, and nothing
would write to it to notice. `publicPageReady` is spelled out as the full five-way conjunction even
though `hasBookableService` implies three of its terms — the shortcut would make that line depend on
a definition kept in another module, and it would keep compiling after that definition changed.

---

## 5. Rules that are enforced, not merely written down

Added to the phase 01 and 02 lists.

| Rule | Enforced by |
|---|---|
| A `@TenantScoped` repository declares only `businessId`-first methods | `TenantRepositoryShapeTest` — **no longer `allowEmptyShould`** |
| No `@TenantScoped` repository declares `findById` | `TenantRepositoryShapeTest` — same |
| No endpoint binds a business id from a path, query or header | `TenantRepositoryShapeTest` — same |
| No request DTO carries a `businessId` field | `TenantRepositoryShapeTest` — same |
| Every phase-03 endpoint refuses another tenant's id with `404` | `BusinessIsolationTest` |

**`allowEmptyShould(true)` is gone from all four rules.** The phase 02 handoff flagged this for
exactly this phase: at one `@TenantScoped` repository an empty result meant "not written yet"; at
three it would mean the annotation had been dropped and the rule was passing by finding nothing to
check. **Do not add it back** to get a red build green — an empty result is now the finding.

`LayeringTest` keeps its allowances, because `scheduling.domain` and `ai` are still empty.

---

## 6. Traps already paid for

### 6.1 A test-scope dependency applies to every test in the suite

**This one cost the most time in this session, and the symptom was a hang, not a failure.**

`TestRestTemplate` defaults to `HttpURLConnection`, which **cannot send `PATCH`** — it fails with
"Invalid HTTP method", which reads like a routing bug and is not one. Half of this phase's endpoints
are `PATCH`.

The obvious fix is to put Apache HttpClient 5 on the test classpath. Spring detects it and uses it
automatically, and `PATCH` starts working. **That is the trap.** Its default retry strategy treats
`429` as retryable *and honours the `Retry-After` header the application sets itself* — so
`RateLimitTest`, a **phase 02** test whose entire purpose is to collect 429s, stopped being a test
and became a sleep. The suite ran for forty minutes with zero failures and no output. Every
individual test class passed when run alone.

The fix is to choose the factory explicitly rather than let the classpath choose it:
`AuthTestClient`'s constructor sets `JdkClientHttpRequestFactory`, which sends `PATCH` and does
nothing else. The reasoning is a comment in that constructor.

**Diagnosing this kind of hang:** `jstack` the `Gradle Test Executor` process and grep for
`dev.reception` — the stack named the test in one line. A hanging suite with no failing test is
almost always something sleeping, not something looping.

### 6.2 A whole-week replace must flush between the delete and the inserts

Hibernate orders operations by entity type, not by the order they were requested in, so the inserts
can reach the database before the deletes. `UNIQUE (business_id, day_of_week, opens_at)` does not
care that the row it collides with is about to be removed.
`BusinessHoursService.replaceWeek` calls `hours.flush()` between the two, and
`a_replace_can_reuse_the_times_it_is_replacing` is the case that fails without it — submitting the
same week twice.

### 6.3 Validate before deleting, not just inside a transaction

`replaceWeek` validates the whole payload before it deletes anything. The transaction would roll a
partial write back anyway, but validating first is what makes the guarantee independent of the
transaction: an invalid Friday leaves Monday to Thursday untouched *because they were never
touched*, not because a rollback repaired them.

### 6.4 `Map.of` rejects null values

Three integration tests failed with a bare `NullPointerException` inside `ImmutableCollections`
because a closure with no reason is a legitimate case. Use a `HashMap` in a test helper that has an
optional field. Obvious in hindsight; the stack trace names neither the field nor the test's intent.

### 6.5 Mockito self-attaches its agent

This phase is the first to use Mockito. The inline mock maker attaches an agent to its own JVM and
warns on every run that a future JDK will refuse. `build.gradle.kts` now passes `-javaagent`
explicitly through a `mockitoAgent` configuration and a `CommandLineArgumentProvider` — the provider
rather than `jvmArgs` because resolving a configuration at configuration time is a Gradle warning of
its own.

### 6.6 Everything from the earlier handoffs still applies

Particularly §6.3 of the phase 02 handoff (never `pnpm build` against a tree running `pnpm dev`) and
§6.1 of the session-aware-pages handoff (how to run a production build anyway), both of which the
frontend half of this phase will need.

---

## 7. What is not covered by a test

Stated so it is not mistaken for coverage that exists.

- **`affectedAppointments` is always `0`.** The field is reported and asserted, but the number cannot
  become non-zero until phase 06. `ClosureEndpointTest` says so in a comment. **Phase 06 owes the
  real case**, and it is the one FR-2 actually cares about: a closure laid over booked appointments.
- **The three catalog flags are always `false`.** Same shape, phase 04. `OnboardingDerivationTest`
  covers the *derivation* exhaustively by stubbing the port, so what is untested is the port's real
  implementation, which does not exist yet.
- **No `STAFF` role test.** `@PreAuthorize` requires `OWNER` or `ADMIN`, and `STAFF` has no login in
  the MVP, so there is no way to obtain a `STAFF` session to be refused. The rule is asserted to
  exist by inspection only. A test would need a membership row and a hand-minted token; worth doing
  when a second role first becomes reachable.
- **No frontend coverage of any of this**, because no frontend exists for it yet. Unchanged in kind
  from the earlier handoffs — there is still no frontend test runner by design
  (`08-testing-strategy.md` §11).
- **Concurrency on the slug check is not tested.** `BusinessService` checks `existsBySlug` and then
  catches the integrity violation, so the race is *handled*; it is not exercised. Doing so needs two
  threads and a latch, and the payoff is small — the check loses the race roughly never.

---

## 8. Open items

Everything in §7 of the phase 02 handoff and §8 of the session-aware-pages handoff still stands.
Added or changed:

- **`dev` is 11 commits ahead of `main` and unpushed.** CI has never seen the phase 03 work. This is
  the highest-value next action regardless of what else happens.
- **The phase 03 frontend is not started.** See §9.
- **`CLAUDE.md` and `docs/agents/` are still untracked.** Unchanged from the phase 02 handoff, and
  deliberately left out of this session's commits too. Still the owner's call.
- **Branch protection is still not enabled.** Offered twice now, never accepted.
- **`business.currency` is editable but nothing reads it yet.** Money arrives with services in phase
  04. It is validated against ISO-4217 on write.
- **The `EmptyCatalogReadiness` / `EmptyAppointmentImpact` deletions are the first thing phases 04
  and 06 should do**, before writing their own implementation, so the two-bean failure never happens
  in the first place.

---

## 9. Next: the phase 03 frontend

Read `docs/phases/phase-03-business-setup.md` — the checklist under **Frontend** and the
**Notes from the build** section appended this session. Five screens plus the dashboard checklist:

1. **`/settings/profile`** — identity, address, contacts, and **timezone with a confirmation
   dialog**. The dialog is not polish: changing the zone moves every displayed time without moving a
   single stored instant, and ADR-0003 requires the owner be told that before saving. `ClosureEndpointTest`
   has the proof of the effect — the same closure reads back as a different local date afterwards.
2. **`/settings/hours`** — seven-day editor, copy-to-all, a closed toggle, inline overlap errors.
   **A day with no interval is closed**, and the UI must render that as "Closed" rather than blank.
   The server returns field errors keyed `hours[2].opensAt`, so the form can put each message on the
   row that caused it.
3. **`/settings/closures`** — list plus a date-range form. The response carries both the instants and
   the local dates, plus the zone, so nothing needs re-deriving client-side.
4. **`/settings/booking`** — each setting with a plain-language explanation of its effect.
5. **`/settings/faqs`** — CRUD with reordering. Reordering is a `PATCH` of `sortOrder`; there is no
   separate reorder endpoint.
6. **Dashboard home** — the onboarding checklist, linking to the next incomplete step. Three of its
   flags will read `false` until phase 04, which is correct and should be shown honestly rather than
   hidden.

Three things worth knowing before starting:

- **`DashboardShell`'s navigation marks `/settings/profile` as `available: false`.** Flip it, and
  consider whether Settings needs its own sub-navigation.
- **A slug change invalidates the cached session.** `useSession` holds `business.slug` from
  `/auth/me`, and the profile screen can change it. Call `reload()` after a successful save, or the
  sidebar and the booking URL will disagree with the server until the next page load.
- **Every screen ships with empty, loading and error states** (`09-phase-plan.md` §5, rule 7). They
  are not phase-11 polish, and the phase document lists them as their own checklist item.
