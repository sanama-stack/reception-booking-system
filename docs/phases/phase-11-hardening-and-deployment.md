# Phase 11 — Hardening, Verification and Deployment

## Goal

Turn a working system into a demonstrable one: complete the cross-cutting test suites, close the security
items deferred by design, seed a convincing two-tenant demo, and write the documentation that lets a
stranger run and understand it.

## Scope

**In:** the complete tenant-isolation suite, the E2E flow, seed data, observability, security headers,
secret-handling verification, performance sanity checks, README and demo script, one documented deploy path.
**Added 2026-09-11 by principal decision** (see *Decisions folded in* below): a thin frontend test runner,
`ai_message` retention, and the revenue remainder field from
[ADR-0010](../adr/0010-revenue-reports-one-currency-and-names-the-remainder.md).

**Out:** operating an internet-reachable instance (the MVP contract is local compose), monitoring
infrastructure, backups.

## Dependencies

Everything. This phase hardens what exists; it does not add features.

## Why this is not "write the tests now"

Every phase shipped its own tests. What is left is the work that is only possible once the whole system
exists: suites that enumerate *every* endpoint, an E2E that crosses every layer, and a seed that exercises
the whole model at once.

## Decisions folded in

Four decisions were put to the principal on 2026-09-11 and answered. They are listed here because each
adds work to this phase that its original scope did not name, and a reader comparing the checklist
against the phase plan would otherwise find boxes with no source.

| Decision | What it adds here |
|---|---|
| **Revenue currency** — [ADR-0010](../adr/0010-revenue-reports-one-currency-and-names-the-remainder.md) | `excluded` on the `revenue` object, the `/analytics` footnote that renders it, and the rewrite of the test that currently pins single-currency behaviour |
| **A frontend test runner** | Vitest + Testing Library, thin and targeted. Scope growth, accepted deliberately: this is the complete-the-test-suites phase, and *no frontend test runner* has been carried as an open item since phase 02 |
| **`ai_message` retention** | A documented window and a scheduled purge, under *Security completion*. Transcripts hold Customer names and phone numbers and nothing has ever deleted one |
| **Performance honesty** | G15's calendar half and G16's cold-cache reading fold into *Performance sanity*, which was already going to run the queries they concern |

**It contradicts a documented decision, deliberately.**
[08-testing-strategy.md](../08-testing-strategy.md) §11 said the frontend is *type-checked and linted*
with *E2E covering the critical path*, and phase 02 recorded the absence of a runner as by design rather
than as a gap. That row has been widened rather than quietly ignored — the reasoning is at §11 itself.

**The frontend runner is deliberately thin.** Its target is the phase-10 inventory rows that could only
be *counted* rather than asserted — the business-timezone rendering, the empty/loading/error states, and
the 360 px widths that were measured once by hand with `scrollWidth`. It is not an attempt to retrofit
coverage across the whole app, and the E2E flow remains the check that proves the demo works.

## Technical work

### The complete isolation suite

Parameterised over **every** tenant-scoped endpoint in the application, discovered by reflection over
controller mappings so a new endpoint cannot be silently omitted:

- Authenticate as Business A, pass a Business B id → `404` (never `403`, never `200`)
- No endpoint honours a `business_id` in a body or query
- Public endpoints for slug A never return Business B data
- Native cross-tenant inserts are rejected by composite foreign keys
- No AI tool schema declares a tenant parameter

Reflection-based discovery is the point: a test that must be remembered will eventually be forgotten.

### End-to-end

The single Playwright flow from [08-testing-strategy.md](../08-testing-strategy.md) §8, run against a
**fake provider at `app.ai.base-url`** so it is deterministic in CI:

register → configure → book classic → book by chat → assert both emails in Mailpit → follow the Manage Link
→ cancel → verify in the dashboard → mark completed → verify analytics.

Plus a 360 px run of the public page.

### The 360 px sweep

**Written 2026-09-12, and not yet run.** The `mobile` project grew from one route to **twenty-one**
— three signed out, seventeen behind the sign-in, and the public booking page — replacing the
phase-10 sweep that was measured by hand.

Its first step **plants a 900 px element and requires the measurement to notice**. Every other
assertion in it is an *absence*, and an absence is also what a broken measurement reports; the plant
runs on every execution, so this sweep cannot quietly become vacuous the way the step above it did.
Three conditions hold before anything is measured — the URL is still the route asked for, something
rendered, and nothing is still loading — each ruling out a way to pass having measured nothing.

**It has never executed.** A browser cannot be launched on this machine: Chrome starts and is
`SIGKILL`ed by the sandbox, re-confirmed on 2026-09-12. CI is the only place it runs.

**Green in CI 2026-09-12**, run `34697065351`: both specs pass, the flow in 30.4s. It lives in
`e2e/`, runs against `make up-e2e` — an isolated compose project with its own database — and the
Receptionist leg calls no model. Three gaps close with it: **G19** (the deterministic model is built
*and consumed*), **G22** (the E2E topology has been started), and **G21** — a real browser has now
driven registration, hydration, forms, the chat panel and the calendar against a **production build
under the strict CSP**, which is exactly what that gap said had never happened (`CSP_SCRIPT_EXTRA`
is `""` in `docker-compose.apps.yml`, which the E2E overlay does not override).

**Corrected 2026-09-12.** The line above said *"run against the scripted model"*, which named a
mechanism that cannot be reached: `ScriptedChatModel` is on the test classpath and Playwright drives
a booted application. [ADR-0011](../adr/0011-the-e2e-fake-provider-lives-behind-the-base-url.md)
records the decision, and the part it warns about: **a queue of canned replies cannot work here**,
because the conversation must name a `service_id` and a slot that `make seed` generates fresh on
every run. The fake provider answers from the message list it is sent, including the earlier tool
results — which is what makes the ids reachable.

### Seed data

`make seed`, guarded to the `local` profile so it can never run elsewhere. Two businesses in different
verticals, deliberately:

**Salon Aria** (`Asia/Tbilisi`, GEL) — 4 services from 30 to 150 minutes, 3 employees on different
schedules, one with Colour unassigned, one on time off next week, FAQs about parking and payment,
~20 appointments across past and future in mixed statuses.

**Dato's Auto** (`Europe/Berlin`, EUR) — oil change 30 min, diagnostics 60 min, full service 240 min with a
30-minute buffer, 2 mechanics, a public-holiday closure, ~15 appointments.

Two tenants in two timezones and two currencies, so **tenant isolation and timezone correctness are
demonstrable rather than claimed** — a reviewer can log into one and watch the other's data stay invisible.

Demo credentials printed at the end of the seed and listed in the README.

### Security completion

- Verify every item in [06-security.md](../06-security.md) is implemented or explicitly listed as accepted
- Confirm the redaction filter suppresses tokens, codes and manage links across all appenders
- Confirm the `prod` profile refuses to start with any default secret
- Security headers verified by an automated test against Caddy
- Full-history secret scan before the first push
- Confirm error responses leak no stack trace, SQL or class name
- **`ai_message` retention** — a documented window and a scheduled purge. A transcript holds the
  Customer's name and phone number as they typed them, and nothing in the system has ever deleted one.
  Purge rather than redact: the owner's audit need is served by `/conversations`, which is read while
  the conversation is recent, and a half-scrubbed transcript is harder to reason about than an absent one

### Observability

- Structured JSON logs carrying request id and `business_id`
- LLM calls logged with model, latency, tokens and estimated cost — never content
- Health endpoint reporting database and mail
- Slow-query logging enabled in `local` to catch a missing index during the demo build

### Performance sanity

Not a load-testing programme — three checks against the NFRs in [01-prd.md](../01-prd.md) §5:

- Availability over 7 days with 3 employees and 500 existing appointments: p95 < 300 ms
- Appointment list at 10 000 rows for one tenant: p95 < 200 ms
- Analytics summary over a 90-day range: p95 < 500 ms

~~Any miss is fixed with an index in this phase.~~ **This sentence was wrong and is struck.** Phase 10
hit two slow queries and neither was fixed with an index: the calendar's overlap query and the
availability engine's read both needed a *bound* derived from a ceiling, plus a migration (`V8`, `V9`)
holding the ceiling the bound depends on. In the calendar's case an index could not have helped, because
the query had no lower bound on `starts_at` and its work was proportional to the tenant's whole history.
Expect a miss to be a missing bound at least as often as a missing index.

Two carried gaps fold in here, because this section runs the queries they concern anyway:

- **G15's calendar half.** The calendar fix's 46x and 83x are hand-written-SQL numbers. Re-measure on
  Hibernate's own generated statement under a generic plan, which is the recipe already run once for
  `findByBusinessIdAndEmployeesOverlapping` — captured from the SQL log, `PREPARE`/`EXECUTE` past the
  sixth execution, after `VACUUM (ANALYZE)`
- **G16 — cold cache.** Every performance number in the project is `shared hit`. Take at least one cold
  reading, so a p95 claim means something on a machine that has not just run the query

**Measured 2026-09-11, and all three pass.** `NfrBenchmarkTest`, twenty runs after five discarded,
against 31 600 Appointments with 10 000 for the target tenant:

| Check | Threshold | p50 | **p95** |
|---|---|---|---|
| Availability, 7 days, **five** employees (the NFR says three) | < 300 ms | 102.12 ms | **111.12 ms** |
| Appointment list, first page, no filters, 10 000 rows | < 200 ms | 17.05 ms | **19.33 ms** |
| Analytics summary, 90 days | < 500 ms | 22.99 ms | **45.15 ms** |
| Calendar, one week (G15 — no NFR; the bound's own check) | — | 33.89 ms | **42.64 ms** |

**Nothing was missing and nothing was added.** The interesting part is the distance between these
numbers and the query times phase 10 recorded for the same operations — 0.019 ms to 1.4 ms. The
query was never the cost: what these measure is the operation, hydration and the engine's own
computation included, which is what the NFR is about and what `psql` cannot see.

**G15, and the number it corrects.** Phase 10 recorded **46x** for the calendar's lower bound, from
hand-written SQL on literals. Re-measured as Hibernate issues it, with parameters bound and past the
execution where the driver goes to a server-side prepared statement, the same bound is **1.2x** —
7.55 ms against 8.95 ms at p95. Both numbers are right and the difference is the point: 46x was a
ratio of *queries*, this is a ratio of *operations*, and hydrating a week of Appointments costs the
same on both sides. Measured separately on this dataset the query alone is **0.26 ms against
3.64 ms, 30 buffers against 841** — 14x and 28x.

**Neither ratio is the reason the bound exists.** The unbounded query reads 8 800 rows to return 140,
and 8 800 is every Appointment this tenant has ever had: it is a slope, not a factor. At 30 000 rows
phase 10 watched that plan abandon the index and sequentially scan the whole table. No measurement
at one table size can show a cliff, which is why the committed test asserts only that removing the
bound is worse and records the ratio rather than gating on it.

**G16, the cold reading.** The 366-day status count reads 31 buffers either way: `shared read`,
1.931 ms on the first execution after a PostgreSQL restart, against `shared hit`, 1.026 ms warm. A
restart empties the buffer pool and not the operating system's page cache, so that is a cold
*database* rather than a cold *disk*, and the number is quoted with that limit attached.

**The dataset, and the fixture decision.** All of the above needs the shape T28 requires — ten thousand
Appointments for the target Business *and* tens of thousands across other tenants, because with one
Business in the table `business_id` matches every row, a sequential scan really is cheapest, and a green
tick would say only that the table was small. The `reception_perf` scratch database has carried that
shape on one machine for three sessions. **Commit its generator as a script, use it for the checks above,
then drop the ad-hoc database** — the capability becomes reproducible from a clean clone instead of a
local artifact each handoff has had to explain.

### The frontend test runner

Vitest + Testing Library, added to `frontend/`. There has never been one, and phase 10's sweep could only
*count* what it checked — `scrollWidth` measured once by hand on eighteen routes, and the empty, loading
and error states recorded as inventory rows rather than assertions.

Targeted, not comprehensive. The cases worth having are the ones a person got wrong before:

- Business-timezone rendering, against its counterfactual — a browser at `+04:00` must draw a UTC
  Business's `09:00Z` booking at 09:00, not 13:00
- Empty, loading and error states for each screen that has them, which rule 7 of the phase plan requires
  every screen to ship and nothing has ever checked
- The 360 px widths, asserted rather than eyeballed — the phase-10 sweep found a page-widening `sr-only`
  label no screenshot could have shown

Wired into CI's Frontend job beside the existing gates. **This does not replace the E2E flow**, which
stays the check that proves the demo works end to end.

**Built 2026-09-12.** Vitest 5 and Testing Library, 18 files and 68 tests, green locally with lint,
typecheck and format alongside. Three things are worth carrying forward.

**The timezone row is done, and proven the way it was specified.** The suite runs at
`Asia/Tbilisi` — +04:00, no DST — and every fixture belongs to a business at **UTC**, so a helper
falling back to the ambient zone answers four hours late. Both halves of the calendar are asserted,
because there the zone decides pixels as well as text: the block is labelled `09:00` *and* drawn
60 px down, not at the 300 px the browser's own reading of the same instant would give. Each file
first asserts the counterfactual is in force, so a `TZ` that stopped reaching the worker turns the
suite red instead of turning every assertion into a tautology. Shown able to fail: replacing the
calendar's zone with the browser's turned both assertions red.

**Loading and error are covered for every screen; empty is covered for fifteen of nineteen.** The
first two come from `ResourceGate` everywhere but one screen, so they are the same by construction
and are asserted once at the gate — and `src/test/screens/coverage.test.ts` is what makes that
claim true rather than assumed, failing in both directions when a component reads a resource and is
not classified. `DetailDrawer` is the exception, hand-rolled because both states must sit inside the
dialog under a heading and above a Close button, and is asserted directly. *Empty* cannot be shared
and is asserted screen by screen; **four are carried unasserted**, counted in
`UNASSERTED_EMPTY_STATES` so a fifth turns the gate red rather than joining a number nobody reads.

**The 360 px row could not live here, and that is a measurement.** In jsdom a `div` explicitly
1200 px wide reports `scrollWidth`, `offsetWidth` and `clientWidth` of **0**, so the natural
assertion reads `0 <= 0` and passes for every page forever — a vacuous assertion of exactly the kind
this project has twice paid for (T49, and the mark-completed step). It went to the Playwright
`mobile` project instead, where a real browser already runs at 360 px; see *The 360 px sweep* below.

### The revenue remainder

[ADR-0010](../adr/0010-revenue-reports-one-currency-and-names-the-remainder.md), decided 2026-09-11.
`GET /analytics/summary` gains `excluded` on its `revenue` object — one entry per other currency found
among the same COMPLETED Appointments in the same range, each with its own sum, empty when there are
none — and `/analytics` renders it as a footnote rather than a second headline.

The existing test *"after a currency change, revenue reports the new currency only"* becomes wrong as
written and is where the new behaviour gets asserted. It was a record of what the endpoint did, never an
argument that it was right.

### Documentation

- `README.md`: what it is, prerequisites, `make up`, seeded credentials, URLs (app `:9080`, Mailpit
  `:9083`, API docs `/api/docs`), and a **step-by-step demo script** a stranger can follow.
  **Corrected 2026-09-11**: this line said `:8080` and `:8025`, which are the *container-internal*
  ports — what Caddy and Mailpit listen on inside the compose network, not what a browser can reach.
  The published block is `9080`–`9085`, chosen deliberately away from the usual `3000`/`8080`/`5432`
  range so this project can run beside another. A README written to the old numbers would have sent
  every stranger it is written for to a dead port
- `.env.example` complete and accurate
- `docs/deployment.md`: one documented path (single VPS behind Caddy with TLS), including what would have to
  change first — externalised rate limiting, real SMTP, backups, secret rotation

## Testing

- [x] Reflection-driven isolation suite over every tenant-scoped endpoint — every endpoint
      classified against Spring's routing table, every id endpoint probed with a stolen id, every
      read offered a foreign `businessId`, and all twelve public endpoints probed for their own
      shape
- [x] Cross-tenant native-insert rejection — `CrossTenantAssignmentTest`, with a same-tenant
      control so the three refusals cannot be passing against an unwritable table
- [x] AI tool schemas contain no tenant parameter — `ToolSchemaTest`, over every published schema
      at any depth, in both spellings
- [x] Full E2E flow green in CI against the fake provider (ADR-0011)
- [x] 360 px public-page run
- [ ] Rate limits verified for every public endpoint
- [x] Security-header test
- [ ] Log redaction test
- [ ] `prod` profile refuses default secrets
- [x] The three performance checks
- [ ] Frontend unit tests green in CI, including the timezone counterfactual — green locally,
      never run in CI
- [ ] `ai_message` retention purges past the window and spares what is inside it
- [ ] Revenue reports the remainder after a currency change
- [ ] Full suite green from a clean clone

## Definition of Done

- [ ] `git clone && make up && make seed` produces a fully working, populated system
- [ ] The demo script in the README runs end to end without deviation
- [x] Every tenant-scoped endpoint is probed by the isolation suite
- [x] The E2E flow passes in CI
- [ ] The concurrency test passes repeatedly
- [ ] All security items are implemented or explicitly listed as accepted risks
- [ ] No secret is in the repository or its history
- [x] The three performance checks pass
- [ ] Frontend unit tests run in CI's Frontend job
- [ ] `revenue` names its remainder after a currency change, per ADR-0010
- [ ] No `ai_message` outlives the documented retention window
- [ ] `README.md`, `.env.example` and `docs/deployment.md` are complete
- [ ] **Every box in [07-mvp-scope.md](../07-mvp-scope.md) § MVP Definition of Done is ticked** — or the
      defect it covers is carried under that document's *Accepted, measured, open defects*, which
      requires a rate, a date and an issue. **Do not tick that list as found.** It was audited on
      2026-09-11 and five rows were **weaker than decisions this project had already recorded**: as
      written they would have ticked on a technicality over
      [#17](https://github.com/sanama-stack/reception-booking-system/issues/17) — a defect the
      principal had explicitly ruled on. The widened wording is what this box now means

## Checklist

### Testing
- [x] Reflection-based endpoint discovery for the isolation suite — `EndpointCoverageTest` over
      `RequestMappingHandlerMapping`, failing in both directions
- [x] Isolation probe per endpoint asserting `404` — `TenantIsolationSweepTest`, 27 probes, each
      preceded by a control with the caller's own id
- [x] `business_id`-in-request rejection tests — `SmuggledBusinessIdTest` for the runtime half,
      `TenantRepositoryShapeTest` for the compile-time half
- [x] Public cross-tenant leakage tests — `PublicSurfaceSweepTest`, a probe per public endpoint with
      a registry the catalogue holds complete; `PublicIsolationTest` keeps the hand-written cases
- [x] Native cross-tenant insert test — `CrossTenantAssignmentTest`
- [x] AI schema tenant-parameter assertion — `ToolSchemaTest`
- [x] Playwright E2E flow
- [x] Mailpit API assertions inside E2E
- [x] Mobile-viewport E2E run
- [ ] Rate-limit tests for every public endpoint
- [x] Security-header test
- [ ] Log-redaction test
- [ ] Vitest + Testing Library wired into the Frontend CI job — **the step is written and the
      suite is green locally; CI has not run it yet**
- [x] Timezone rendering test, proven against its counterfactual — `lib/time/index.test.ts` and
      `calendar/day-view.test.tsx`, the suite at `Asia/Tbilisi` against UTC businesses, the
      counterfactual asserted in force before anything rests on it, and the calendar assertions
      shown red against a planted browser-zone fallback
- [ ] Empty / loading / error state tests per screen — **loading and error: every screen. Empty:
      fifteen of nineteen**, the other four counted in `UNASSERTED_EMPTY_STATES` and named in the
      catalogue
- [ ] 360 px width assertions replacing the hand-run sweep — **written, twenty-one routes, never
      run**: jsdom cannot measure layout, so this went to the Playwright `mobile` project, and no
      browser can be launched on this machine

### Seed
- [x] `make seed`, `local`-profile-guarded
- [x] Salon Aria with services, employees, schedules, time off, FAQs, appointments
- [x] Dato's Auto in a different timezone and currency, with a closure and a buffered long service
- [x] Past and future appointments in mixed statuses
- [x] Credentials printed and documented

### Security
- [ ] Walk [06-security.md](../06-security.md) and verify each control
- [ ] Redaction filter verified across appenders
- [ ] `prod` default-secret refusal
- [x] Security headers in Caddy
- [ ] Full-history secret scan
- [ ] Error-response leakage review
- [ ] `ai_message` retention window, documented and enforced by a scheduled purge

### Observability
- [ ] JSON logging with request id and `business_id`
- [ ] LLM call metrics without content
- [ ] Health endpoint covering database and mail
- [ ] Slow-query logging in `local`

### Performance
- [x] Availability benchmark
- [x] Appointment list benchmark at 10 000 rows
- [x] Analytics benchmark
- [ ] ~~Add indexes for any miss~~ — fix any miss at its cause, bound or index (see *Performance sanity*)
- [x] Perf dataset generator committed as a script, and `reception_perf` dropped
- [x] G15 — the calendar query re-measured on Hibernate's own statement
- [x] G16 — at least one cold-cache reading

### Documentation
- [x] `README.md` with prerequisites, commands, URLs and credentials
- [x] Demo script
- [ ] `.env.example` audited against the compose file
- [ ] `docs/deployment.md`
- [ ] Final consistency pass over `/docs` and `CONTEXT.md`
