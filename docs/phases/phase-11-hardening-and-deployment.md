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

**Green in CI 2026-09-12**, run `34702330928`: **21 routes, every one `360/360`**. The `mobile`
project grew from one route to **twenty-one**
— three signed out, seventeen behind the sign-in, and the public booking page — replacing the
phase-10 sweep that was measured by hand.

Its first step **plants a 900 px element and requires the measurement to notice**. Every other
assertion in it is an *absence*, and an absence is also what a broken measurement reports; the plant
runs on every execution, so this sweep cannot quietly become vacuous the way the step above it did.
Three conditions hold before anything is measured — the URL is still the route asked for, something
rendered, and nothing is still loading — each ruling out a way to pass having measured nothing.

**It prints what it measured, and the reason is worth keeping.** Its first CI run passed in **3.9
seconds** — for a registration and twenty-one page loads — and nothing in the log could say whether
it had swept anything, because the `list` reporter prints the test and not its steps. The time was
honest; a warm production build over localhost really is that fast. But a green tick could not
establish that, so the sweep now prints every route's `scrollWidth/clientWidth` and **asserts the
number of routes measured against the number declared**, which an empty loop cannot satisfy. The
plant proves the measurement can fail; this proves it was taken.

**Extended to the five id-taking routes on 2026-09-12 — G25.** `/appointments/{id}`,
`/customers/{id}`, `/services/{id}`, `/employees/{id}` and `/conversations/{id}` were outside it
because each needs a row the freshly-registered tenant does not have, and they are the routes most
likely to be too wide: they render history tables, a week of schedule, and — on the conversation —
each tool call's arguments and results as pretty-printed JSON, the widest content this application
draws anywhere. `seedDetailRows` creates them through the **API** rather than through five forms,
because driving the forms would spend the run re-proving what the flow already proves and would fail
for form reasons in a spec that measures layout. The conversation is the one that cannot be created
by a plain write — it has to be *talked* into existing, against the fake provider. **Twenty-six
routes declared; the count assertion moves with them.**

**Green in CI 2026-09-12**, run `34704699908`: **26 routes, every one `360/360`**, the five
`/{id}` rows among them, read off the per-route table in the *The flow* step's log rather than off
the job's colour. The sweep took **5.4s** against the twenty-one-route run's 3.9 — the seeding and
five more page loads — and the count assertion is what makes that number mean something.

Before that run the helper's requests had been verified against a live stack, registration through
to all five detail endpoints answering `200` and a transcript holding eight messages including
`TOOL` rows with `toolArguments` and `toolResult`. That established the requests and **not** the
measurement, because no browser can run here; the row stayed open until CI printed twenty-six. Worth
keeping as the shape of the thing: a verified fixture is not a verified assertion.

**It cannot be run on a developer machine here.** Chrome starts and is `SIGKILL`ed by the sandbox,
re-confirmed on 2026-09-12. CI is the only place it runs.

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

#### Transcript retention — **built 2026-09-12**

`V10`, `TranscriptPurge` (the batch) and `TranscriptPurgeJob` (the hourly timer), split for the
reason `NotificationDispatcher` is split from `NotificationPoller`: a `@Scheduled` method calling a
`@Transactional` one on `this` bypasses the proxy and runs with no transaction at all.

**Ninety days was not chosen here.** [05-ai-architecture.md](../05-ai-architecture.md) §11 has
carried it since phase 09 and deferred only the job — *"the purge job itself is V1.1"*. This
closes that sentence rather than answering a new question.

**The window is measured from the conversation's last activity, not each message's own age**, and
that is the load-bearing choice. Anchored per message, a conversation straddling the boundary loses
its opening turns and keeps the rest — a transcript beginning mid-sentence, which is exactly the
half-scrubbed state the bullet above rejects. `TranscriptRetentionTest` pins it with a conversation
whose messages are four hundred days old and whose last turn was yesterday: nothing is deleted.

**The `ai_conversations` row is kept, marked** — principal decision, 2026-09-12. It carries counters
and a cost estimate and no free text, and it is the record of what the Receptionist cost the
business. What made this more than a bookkeeping choice is what it does to the *screen*:
`message_count` is denormalised and the purge does not decrement it, so a purged conversation would
have rendered "Rows 8" directly above the transcript's existing empty state — *"This conversation
was opened but nothing was ever said in it."* A contradiction, and a lie. `messages_purged_at` is
on the wire so the screen can say which of the two states it is looking at, and
`transcript.test.tsx` asserts the old sentence is **absent** on a purged conversation.

**The window is a constant, the switch is configuration.** `ConversationLimits.TRANSCRIPT_RETENTION_DAYS`
holds ninety days; `AI_RETENTION_ENABLED` turns the job off for tests and for a developer who does
not want a background thread deleting planted rows. A retention window is a promise about other
people's data, and the difference between widening it in a diff and widening it in an environment
variable on one host is the whole reason for the split. The UI deliberately does **not** name the
number either — it renders the date the server sent — because a `90` written in Java and again in
TypeScript with nothing checking that the copies agree is the coupling the `.env.example` audit
spent a session on.

**Verified on this machine, which the previous handoff expected to be impossible.** §9.1 of it
reasoned that retention needs Java and a migration, that the E2E images cannot be rebuilt here, and
that the assertion would therefore have to be written first and ticked by CI. That is true of the
*containerised* stack and not of the *test* suite: Testcontainers needs `postgres:16-alpine`,
`axllent/mailpit:v1.21` and `testcontainers/ryuk`, all three of which were already in the local
image cache, so nothing had to be pulled or built. 937 backend tests green locally, and the nine
new ones shown red three separate ways.

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

**Built 2026-09-12, and green in CI** — run `34702330928`, 18 files and 68 tests in the Frontend
job beside lint, typecheck, format and the build. **22 files and 76 tests** as of run
`34704699908`, which is where the last four empty states landed.

**The counterfactual is load-bearing and CI proves it.** GitHub's runners are UTC. The timezone
tests assert the ambient zone is +04:00 *before* they assert anything else, so had `env: { TZ }` in
`vitest.config.mts` failed to reach the worker there, the job would have gone red rather than
passing against a business and a browser that happened to agree. Three things are worth carrying forward.

**The timezone row is done, and proven the way it was specified.** The suite runs at
`Asia/Tbilisi` — +04:00, no DST — and every fixture belongs to a business at **UTC**, so a helper
falling back to the ambient zone answers four hours late. Both halves of the calendar are asserted,
because there the zone decides pixels as well as text: the block is labelled `09:00` *and* drawn
60 px down, not at the 300 px the browser's own reading of the same instant would give. Each file
first asserts the counterfactual is in force, so a `TZ` that stopped reaching the worker turns the
suite red instead of turning every assertion into a tautology. Shown able to fail: replacing the
calendar's zone with the browser's turned both assertions red.

**Loading, error and empty are now covered for every screen.** The first two come from
`ResourceGate` everywhere but one screen, so they are the same by construction and are asserted once
at the gate — and `src/test/screens/coverage.test.ts` is what makes that claim true rather than
assumed, failing in both directions when a component reads a resource and is not classified.
`DetailDrawer` is the exception, hand-rolled because both states must sit inside the dialog under a
heading and above a Close button, and is asserted directly. *Empty* cannot be shared and is asserted
screen by screen.

**The last four closed on 2026-09-12, and `UNASSERTED_EMPTY_STATES` is zero.** They were carried
waiting on fixtures the suite did not have; the fixtures are written and shared in
`src/test/fixtures.ts`, because all four wanted the same service and the same person. The constant
stays at zero rather than being deleted with the last row it counted, so the next screen shipping an
unasserted empty state meets the same gate. Two of the four are dead ends and two are open
questions, and they are asserted as different things: a deleted service leaves the move panel
nothing to compute a length from, while a cleared date is one field away from working. Each case
also asserts what is *not* offered, so a screen showing the message and the controls at once is red.

**`useParams` now answers from `src/test/navigation.ts` rather than `{}`.** With `{}` the
appointment detail page asked its server for `/appointments/undefined` — a path the harness matches
by prefix, so the test would have passed *because* the id was missing, and gone on passing had the
page stopped reading its own route. The request path is asserted, and that assertion was shown red
against the old stub.

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
- [x] Rate limits verified for every public endpoint — `RateLimitCoverageTest`, which derives the
      public surface from the handler mapping and the security filter chain rather than from a
      list. Found three endpoints that had never carried a limit; shown red six ways
- [x] Security-header test
- [x] Log redaction test — `AppenderRedactionTest`, at the appenders rather than at the masker.
      Shown red five ways
- [x] `prod` profile refuses default secrets — `SecretsGuardTest`, twelve tests, each starting a
      real context rather than calling the guard, so the `@Profile("prod")` wiring is under test
      too. Shown red six ways
- [x] The three performance checks
- [x] Frontend unit tests green in CI, including the timezone counterfactual — and the runner is
      UTC, so the counterfactual assertion is what proves `TZ` reached the worker
- [x] `ai_message` retention purges past the window and spares what is inside it —
      `TranscriptRetentionTest`, nine tests, and **every deletion assertion is paired with a
      survival assertion** so neither a purge that does nothing nor one that empties the table can
      pass. Shown red three ways: anchored on message age instead of conversation activity (2 red),
      with the `messages_purged_at` guard removed (2 red), and with the purge made a no-op (6 red),
      each reverted byte-identically
- [x] Revenue reports the remainder after a currency change — `revenue.excluded`, one sum per other
      currency among the same COMPLETED appointments, `[]` when there are none. Shown red four ways:
      the derivation returning nothing (1 red), the COMPLETED filter dropped so the remainder
      reports 120.00 (1 red), the currency comparison inverted (2 red), and `null` in place of the
      empty list (1 red), each reverted byte-identically. **The first plant is the point**: it is
      caught only by the assertion on `excluded[*].amount`, because "the list is empty" is what the
      no-remainder test asserts on purpose. A count would not have caught it either
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
- [x] Frontend unit tests run in CI's Frontend job
- [x] `revenue` names its remainder after a currency change, per ADR-0010 — with an amount rather
      than a count, on the screen as well as in the payload, and never summed with the figure above
      it or with itself
- [x] No `ai_message` outlives the documented retention window — ninety days after a
      conversation's last activity, enforced hourly by `TranscriptPurgeJob`. The window is
      `ConversationLimits.TRANSCRIPT_RETENTION_DAYS`, a constant, so widening it is a diff
- [ ] `README.md`, `.env.example` and `docs/deployment.md` are complete
- [ ] **Every box in [07-mvp-scope.md](../07-mvp-scope.md) § MVP Definition of Done is ticked** — or the
      defect it covers is carried under that document's *Accepted, measured, open defects*, which
      requires a rate, a date and an issue, **or the gate that would decide it is carried under that
      document's *Gates that cannot be run***, which requires what the gate checks, when it last ran
      and what must happen for it to run again. *Third state added 2026-09-13:* the level-3 corpus
      cannot run at all, and a box nothing can currently decide is neither a tick nor a measured
      defect. **Do not tick that list as found.** It was audited on
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
- [x] Rate-limit tests for every public endpoint — `RateLimitCoverageTest`. The hand-written
      list it replaces had been missing both chat endpoints since phase 09
- [x] Security-header test
- [x] Log-redaction test — secrets driven through the encoder the real `logback-json.xml` builds,
      and the bytes inspected
- [x] Vitest + Testing Library wired into the Frontend CI job — 18 files, 68 tests, green in run
      `34702330928`, read out of the job log rather than off the job's colour
- [x] Timezone rendering test, proven against its counterfactual — `lib/time/index.test.ts` and
      `calendar/day-view.test.tsx`, the suite at `Asia/Tbilisi` against UTC businesses, the
      counterfactual asserted in force before anything rests on it, and the calendar assertions
      shown red against a planted browser-zone fallback
- [x] Empty / loading / error state tests per screen — **all three, every screen**.
      `UNASSERTED_EMPTY_STATES` is **zero** as of 2026-09-12; the last four were closed by writing
      the fixtures they were waiting for, and the gate was shown red against a title reworded in a
      test and not in the screen
- [x] 360 px width assertions replacing the hand-run sweep — **21 routes, every one `360/360`** in
      run `34702330928`, printed per route in the log. jsdom cannot measure layout at all, so this
      lives in the Playwright `mobile` project; a 900 px plant runs on every execution so the
      sweep cannot become vacuous
- [x] **G25 — the five id-taking routes inside the sweep.** **26 routes, every one `360/360`** in
      run `34704699908`, the five `/{id}` rows printed among them. The conversation's transcript is
      the one that mattered: it draws each tool call's arguments and results as pretty-printed JSON,
      which is the widest content in the application, and it holds inside its own scroll container

### Seed
- [x] `make seed`, `local`-profile-guarded
- [x] Salon Aria with services, employees, schedules, time off, FAQs, appointments
- [x] Dato's Auto in a different timezone and currency, with a closure and a buffered long service
- [x] Past and future appointments in mixed statuses
- [x] Credentials printed and documented

### Security
- [x] Walk [06-security.md](../06-security.md) and verify each control — **all fifteen sections done 2026-09-13**. **§12 done 2026-09-12**,
      and it is the shape to expect from the rest of this walk. Its claim that the database port is
      exposed *"only in the `local` compose profile"* was implemented by **no file**; the principal's
      call was that the file moves rather than the sentence. Both compose topologies now bind to
      loopback, the deployed one publishes no database port at all, and `make check-bindings` holds
      it there in CI and on both `up` targets — shown red three ways, including against the
      `ports: []` that *looks* like it removes a mapping and, because Compose appends sequences,
      does not. **§5 done 2026-09-12** and it broke in the same shape: the table listed ten limits
      and the test that checked them listed ten paths, so the two agreed with each other and neither
      agreed with the application. `/auth/refresh`, `/auth/logout` and `/health` had never been in
      either. `RateLimitCoverageTest` now reads the public surface off the security filter chain, so
      the list cannot be short. **§6 done 2026-09-13** and it broke the same way a third
      time: *"Manage tokens are excluded from access logs"* was implemented by no file, and Caddy —
      the only access log in the system — wrote every one of them to stdout verbatim, as a path
      segment in `/manage/{token}` and as a query parameter on the two API calls that page makes.
      Measured against the running container rather than read off the Caddyfile. **The error logger
      was the trap**: a site's `log` directive configures the *access* logger only, so the first fix
      looked complete against a healthy stack and leaked every token the moment the backend was
      down. `make check-access-log` holds both, shown red three ways — and **its own first control
      was defective**, satisfied by a stale `/api/health` line from a previous container because
      `docker compose logs --tail` spans restarts. Both sentinels are now unique per run. **§2 done 2026-09-13**, and its
      cookie sentence had the same gap one layer down: `RegistrationTest` asserts `httpOnly`,
      `SameSite=Lax` and `Path=/` over real HTTP and cannot assert `Secure`, because the suite runs
      the `test` profile where it is deliberately off. `AuthCookieSecurityTest`, seven tests,
      **shown red three ways** — `application-prod.yml` set to `secure: false`, the `.secure(...)`
      call deleted from the builder, and the `@Value` default flipped. The third is caught by one
      test only, and writing it surfaced a wrong premise: *unset* does not reach the `@Value`
      fallback, because `spring.profiles.default: local`. The assertion that fails safe is about a
      profile with no file of its own, which is the next environment somebody adds. **§13's CSRF half done 2026-09-13**,
      by measurement: *"state-changing requests additionally require `Content-Type: application/json`"*
      was true wherever a `@RequestBody` existed and false at the **ten endpoints that take no
      body** — a form-encoded `POST /auth/logout` answered `204`. Two layers documented, one built,
      and the missing one is the layer a reader counts on if `SameSite` is ever relaxed.
      `JsonOnlyWriteFilter` refuses the three content types an `enctype` can produce, before
      authentication so the refusal is about the request's shape and not its credentials;
      `FormPostRejectionTest` derives all 36 writes from `RequestMappingHandlerMapping`. **Shown red
      four ways**, and the fourth is the control: a filter that refuses *every* content type passes
      the main assertion and is caught only by sending the same endpoints JSON. **§7 done 2026-09-13**, derived the
      same way and it found three: `PublicRequests.Authority`'s `manageToken`, `confirmationCode`
      and `phone` were bare strings on the two **unauthenticated** endpoints, each with a bounded
      twin a few lines away (16, 30, 500). The record's own comment says why none is `@NotBlank` —
      *"exactly one of these" is not a field annotation* — and that argument is about **presence**
      and took the length bound with it. No global request-size cap exists to fall back on.
      `RequestFieldLengthTest`, **shown red three ways**, and **two of the three were caught only
      after the control was strengthened**: reading `@Size` off the `RecordComponent` (its `@Target`
      has no `RECORD_COMPONENT`, so every field reads as unbounded — and read the other way round it
      would have passed forever), and a walk that stops at nested records, which silently drops the
      one record the class exists to catch. Naming a nested field in the bounded set is what closes
      both. **§13's CORS half done 2026-09-13**, which completes §13.
      *"No CORS configuration exists"* was asserted by nothing, and `NoCorsConfigurationTest` now
      checks it in two halves: the grant is probed across all 65 mapped endpoints in both shapes,
      and the configuration is **read** off `RequestMappingHandlerMapping` and the annotations,
      because a `@CrossOrigin` naming one partner origin is invisible to any probe. **Shown red five
      ways**, and two of them cost the design. A permissive configuration *plus* a client that
      cannot send `Origin` — both are restricted headers for `HttpURLConnection` — made the
      preflight assertion **pass against an application granting every origin everything**; the
      control is a pair because neither half of it holds alone. And the first version of the
      configuration check was a behavioural sweep that **reported green over the whole authenticated
      surface**: Spring Security answers `401` before MVC's CORS interceptor runs, so a planted
      `@CrossOrigin` on `AnalyticsController` changed no response, while the same annotation on
      `HealthController` was caught at once. **§3 and §4 done 2026-09-13.** The suspicion was that
      `EndpointCatalogue` is a typed list and therefore §5's failure again; it is the opposite — a
      typed judgement reconciled against Spring's routing table in both directions, which is the
      right answer. The defect was one level further in: **classified was not the same as probed.**
      `OWNER_COLLECTION` and `OWNER_SINGLETON` — fifteen of sixty-five endpoints — carried a written
      probe description that **no test in the tree referenced**, so a new collection endpoint was
      classified, the build stayed green, and nothing ran. Both are now swept from the catalogue,
      with a registry check because a `@TestFactory` that yields nothing passes. **`GET
      /availability` was misclassified**, which is worse than unclassified: it has taken a required
      `serviceId` in the query string since phase 05 and was recorded as a collection that "takes no
      id". Now `OWNER_QUERY_ID`. **Shown red five ways**, and two of them are the interesting ones —
      an empty collection fails the *control* rather than the assertion, and the availability probe
      stayed green with `ServiceCatalogService#read` unscoped, because `AssignmentService` checks the
      same id independently; it went red only when both were removed, which is §4's "each
      independently sufficient" demonstrated rather than asserted. **§14 done 2026-09-13**, and its
      last bullet was the §6 shape for the fourth time: *"Authentication events (login, refresh,
      revocation) are logged with user id and IP"* — `AuthService` contained **no log statement at
      all**, and the only authentication line in the application was the replay warning in
      `RefreshTokenFamilyRevoker`, carrying a user id and **no address**. The audit trail began at
      the one event an attacker triggers deliberately and could not say where it came from. The data
      was never missing: `RequestFingerprint` has carried the peer address since phase 02. Logged
      now, registration included, with the events **derived** from the `/auth` surface so a fourth
      endpoint cannot be silently unlogged. **Shown red five ways**, and one of them found a hole in
      the test rather than the code: a password logged from `login()` **passed**, because the
      credential sweep drove register, refresh and logout and not the one endpoint that receives a
      password. **§8 done 2026-09-13**, and **neither of its two claims was checked by anything that
      ran**: the only test referencing `SystemPromptBuilder` is `@Tag("probe")`, excluded from the
      suite, and it asserts nothing. *"Customer text never enters the system prompt"* was true and is
      now asserted as an **equality** across a real turn rather than as an absence — which earns its
      place, since a plant appending the **model's** reply passed the sentinel check and was caught
      only by the equality. *"Business text is delimited and labelled as data"* was **true of one
      field of four, and it was the smallest**: `ai_additional_info` was fenced at 2,000 characters
      while the description (5,000), the cancellation policy (5,000) and up to fifty FAQs at 1,300
      each went in bare — roughly thirty times as much owner free text, and the FAQ is the field
      05-ai-architecture.md §7's own injection table names as an attack. All four go through one
      helper now. **Shown red five ways**, the sharpest being a fence emitted with the text landing
      *after* it closes, which a check that searched for `<<<` would have passed. **This is a system
      prompt change and the level-3 corpus has NOT been run against it** — and it **cannot** be:
      the OpenAI account has no credits, which was confirmed on 2026-09-13 by a run that cost
      nothing because every call was refused. **Ruled the same day: level 3 is recorded as
      unavailable**, under [07-mvp-scope.md](../07-mvp-scope.md) § *Gates that cannot be run*, rather
      than carried as a pending decision. It was never a decision — the credits had run out on
      2026-09-11, two days before this change was made. **§1 done 2026-09-13**, and it is the
      first section of this walk that was very nearly clean: its threat-model table names seven
      primary controls and **six of the seven already resolved to a test that runs**. The seventh
      did not. *"Rate limiting by IP"* is the control for the **availability** row, and the **by
      IP** half was asserted by nothing — `RateLimitCoverageTest` proves every public endpoint is
      matched by a policy, `RateLimitTest` proves a limit bites, and both drive one client, so both
      are equally true of a filter keying every bucket on a constant. The property is claimed four
      further times in `RateLimitProperties`' own prose and checked in none of them. It rests on
      **three independent facts**, any one of which can be undone with the suite staying green: the
      `forward-headers-strategy: framework` line, Spring registering `ForwardedHeaderFilter`
      **ten ahead** of `RateLimitFilter`, and `clientAddress()` reading `getRemoteAddr()`. What they
      admit is the inverse of the control — one bucket for every visitor, so a single stranger
      closes a public endpoint for all of them, which is the outage the row exists to prevent.
      `RateLimitAddressTest`, **no production code changed**, **shown red four ways**. The sharp one
      is the ordering plant: moving `RateLimitFilter` to `HIGHEST_PRECEDENCE` — the obvious edit for
      a filter that must precede authentication — ties the two, and **the behavioural test stayed
      green**, because tied filters are sequenced arbitrarily and that run landed the right way. Only
      the assertion on the registered orders caught it. **§15 done 2026-09-13, which closes the
      walk.** It is a different job from the other fourteen: an accepted risk resolves to no control
      by definition — the entry *is* the decision not to build one — so it is checked for the
      opposite defect, an entry that no longer describes the system. **Three of seven were wrong, each
      differently.** A **justification that was false**: *"the account model supports adding 2FA
      without migration"* — `users` has six columns, none able to hold a secret or an enrolment flag,
      and there is no credentials table, so a reader was being told the wrong price for reversing the
      decision. An entry that **recorded half its risk**: the API documentation row had the
      disclosure and not the **amplification**. `/openapi` answers any anonymous caller with the full
      specification and **no policy matches it** — two hundred consecutive requests, none refused,
      measured. `RateLimitCoverageTest` filters to `dev.reception` deliberately, so a springdoc
      rename cannot break the build; these three paths are mapped outside it and are therefore
      anonymous, unlimited and **invisible to every derived control in the tree**, with Caddy's
      `handle /api/*` proxying all of them. And a **risk that was missing**: the Manage Link's
      residual property, a bearer capability in a URL, discussed at length in §6 as a fact about the
      token and never carried into §15 as a decision. `ApiDocumentationExposureTest`, **shown red
      three ways** — and the third is the lesson for the third time this walk: with the limiter
      switched off, *"the documentation is unlimited"* **passed**, completely vacuously, and only the
      positive control caught it. **Closing the documentation exposure is an open decision** — a
      policy over the paths, or an `UNLIMITED_ON_PURPOSE` exemption with its reason. **Closed 2026-09-13, on the
      principal's call.** Three policies — `/openapi/**` at 30/min, `/swagger-ui/**` and `/docs/**`
      at 60/min, sized against a real page load of about seven asset requests. **The policies were
      the easy half.** `RateLimitCoverageTest`'s derivation filtered handlers to `dev.reception`,
      inherited from `EndpointCoverageTest` where the reason is sound, and rate limiting is a
      different question from tenancy — so the filter did not just hide the gap, **it rejected the
      fix**: a policy for `/openapi` matched nothing derived and was reported as a dead policy. The
      derivation now sees every mapped endpoint. **Shown red five ways**, and two changed the work.
      Restoring the old filter turns the new policies back into orphans, which is the finding
      restated as a test. And a policy written for `/openapi.yaml` **was wrong** — the coverage test
      stayed green without it, because `permitAll` lists `/openapi/**`, which matches children and
      not siblings, so the YAML rendering answers `401` while the JSON one answers `200`. Nobody
      decided that; the pattern did. The policy was removed and the asymmetry pinned instead, because
      the tidy-up that makes the two patterns consistent is one character wide and publishes a
      document currently behind authentication. **One exclusion could not be closed**: `/swagger-ui/**`
      is served by a resource handler and can never appear in a derivation built on
      `RequestMappingHandlerMapping`, so its policy is required by a written list whose entries the
      orphan check verifies by probing the running application. **G28 closed 2026-09-13**, and it found the
      drift it was written to prevent. §5's limits table and `RateLimitProperties` agreed **by hand**:
      coverage was derived, the numbers were not, and changing `refresh` to ten an hour left §5
      saying sixty with nothing failing. `RateLimitTableTest` reconciles the table against the policy
      list in both directions. **Phase 09 had already drifted**: it added two chat policies and §5
      gained one row, reading *"20 / hour / conversation, 60 / hour / IP"* — the sixty right, the
      twenty belonging to `public-chat-session`, **a policy with no row at all**, and attributed to a
      mechanism that has no hourly limit, since the conversation ceilings are five tool calls a turn,
      forty messages and a twenty-message window in `ConversationLimits`. One policy undocumented and
      one number filed under the wrong control, read past by two sessions of this walk. The table's
      paths were abbreviated with `…` and are now the patterns the code declares, which is what makes
      reconciliation possible at all. **Shown red four ways**, including G28's own worked example, and
      the fourth is T89 for the fourth time in this walk: reformatting the table so the parse matches
      nothing left *"§5 documents no limit the code does not enforce"* **green**, over an empty table
      — an emptiness assertion satisfied by a parser that had stopped working
- [x] Redaction filter verified across appenders — **2026-09-12.** Three tests already covered this
      ground and **none could catch the failure that matters**: deleting the `<jsonGeneratorDecorator>`
      from the appender that runs in production left `PiiValueMaskerTest`, `ConsoleRedactionTest` and
      `JsonLoggingConfigurationTest` all green and every deployed log unmasked — demonstrated, not
      argued. `AppenderRedactionTest` drives real secrets through the encoder the real file builds.
      The console half stays a file check, deliberately: its root binding lives inside `<springProfile>`,
      which only Spring Boot's package-private configurator reads, and initialising the real logging
      system would reconfigure the JVM the rest of the suite logs through. What the file check found
      is the subtle one — **Boot's own `defaults.xml` binds `wEx` too**, so the `<include>` must stay
      above our rules or every stack trace renders through Boot's converter, unredacted, with the file
      looking exactly as intended
- [x] `prod` default-secret refusal — **2026-09-12.** The guard was written in phase 01 and had
      never been executed by anything: it is `@Profile("prod")`, and no test in the suite starts
      that profile. `SecretsGuardTest` starts it, twelve tests, **shown red six ways** — including
      against a local default in `application.yml` that loses its `local-dev-only-` prefix, which
      is the one way the guard can stop protecting with every other test still green. The walk
      also found the audit's correction had been applied to **one file of five**: `.env.example`
      said three secrets, while §9 itself, the guard's own javadoc, `application-prod.yml` and
      phase 01 all still said *any secret*. All four corrected
- [x] Security headers in Caddy
- [ ] Full-history secret scan
- [x] Error-response leakage review — **2026-09-12**, as `ErrorLeakageTest` rather than as a
      reading. `ProblemJsonTest` had asserted the contract against an unknown endpoint — a 404
      raised by the dispatcher, which never had a stack trace, a statement or a class name to
      give away. §11's claims are about an exception escaping a controller, and nothing
      exercised that path. Six tests, **shown red six ways**, driving real exceptions through
      the real chain: the catch-all, a nested cause chain, an unmapped integrity violation
      carrying the failing statement, and the mapped overlap one. The throwing controller is
      registered in that test's context alone, so `EndpointCoverageTest` and
      `PublicSurfaceSweepTest` would fail loudly rather than the public surface widening quietly.
      Two things the plants settled. **Dropping the catch-all does not leak — it lies**: the
      exception reaches the servlet error dispatch, which is not in the permitAll list, and the
      client is told **401**. And the suite has a floor, because every leakage assertion is a
      `doesNotContain` and a 404 satisfies all of them
- [x] `ai_message` retention window, documented and enforced by a scheduled purge — `V10`,
      `TranscriptPurge` and `TranscriptPurgeJob`. Documented in
      [06-security.md](../06-security.md) §14 and [05-ai-architecture.md](../05-ai-architecture.md)
      §11, whose "the purge job itself is V1.1" this closes

### Observability
- [x] JSON logging with request id and `business_id` — `RequestLoggingTest`, which drives real
      HTTP and reads the MDC off the events the application logged. Both tenant filters covered:
      the token's business and the slug's. Shown red five ways
- [x] LLM call metrics without content — `AiCallLoggingTest`. The loop contained **no log
      statement at all** about a model call before this; model, latency, tokens, cost and tool
      outcomes now emit as structured fields, and every test asserts the customer's words reach no
      line. Shown red five ways, two of them deliberate content leaks
- [x] Health endpoint covering database and mail — `HealthEndpointTest`, now on both sides of
      every check against real refused connections. Each failing case asserts the *other*
      component is still `UP`. Shown red four ways
- [x] Slow-query logging in `local` — `hibernate.log_slow_query: 100`, plus `SlowQueryLoggingTest`
      proving Hibernate still emits on `org.hibernate.SQL_SLOW` and that the statement carries
      placeholders, not bound values. Shown red three ways

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
- [x] `.env.example` audited against the compose files, the backend's `application*.yml`, the
      Caddyfile and the frontend — **2026-09-12, and it was already complete**. The declared set
      and the consumed set match exactly in both directions: nothing is read that is not declared,
      and nothing is declared that nothing reads. `BACKEND_UPSTREAM` and `FRONTEND_UPSTREAM` are
      correctly absent, being set by compose rather than by `.env`, and the `NEXT_PUBLIC_*` pair is
      derived from `APP_PORT` / `FRONTEND_PORT` in `next.config.ts` rather than declared twice.
      Two prose claims were checked by measurement rather than read: the CSP double-quoting note
      is true (`make up` serves `script-src … 'unsafe-eval'`, the containerised topology serves it
      without), and one claim was **wrong** — the header said the `prod` profile refuses to start
      while *any* secret holds its local default, where `SecretsGuard` checks three of the five
      that [06-security.md](../06-security.md) §9 lists. Corrected in place.
      **What the audit actually found is a gap the file could not show:** four ports are written
      down twice and `make check-ports` guarded one of them. It now guards all four — the three
      `.env`-internal pairs only while the matching host is local, since `make up-all` overrides
      `DB_*` and `MAIL_*` — and each was shown red on demand by planting a half-moved port. The
      fifth coupling, `SERVER_PORT` against the hardcoded fallback in `client.ts`, is named in the
      file as unguardable by a gate that reads `.env`. **Both `up` and `up-all` depend on the
      gate**, so it now runs in CI's Compose smoke test as well as on a developer's machine —
      shown to stop `up-all` at the prerequisite, before compose is invoked and with every
      running container untouched
- [x] `docs/deployment.md` — the single-VPS-behind-Caddy-with-TLS path, **written and not walked**:
      no host has run it, and the document says so at the top rather than reading as a report. It
      names three files that cannot deploy as committed — the Caddyfile disables certificate
      issuance and has no domain to request one for, `docker-compose.yml` publishes Postgres and
      Mailpit on `0.0.0.0`, and Mailpit delivers no mail — and it records a measurement taken while
      writing it: the per-IP rate limits key on the real client behind the proxy, shown by a
      different real source getting its own bucket, which a forged `X-Forwarded-For` does not
- [x] Final consistency pass over `/docs` and `CONTEXT.md` — and **as a target rather than an
      event**, because a pass run once is stale the next time somebody renumbers a section.
      `make check-docs` (`docs/tools/consistency/`) is four mechanical checks with its own CI job:
      269 relative links, 265 `<doc> §N` cross-references — **including the ones written in
      `.java`, `.yml` and `.sql`, which cite sections their authors never reopen** — the ADR
      inventory against `docs/agents/domain.md`, and the migration inventory against
      [03-data-model.md](../03-data-model.md). Each prints how many things it looked at and
      **fails on an empty population**: an anchor-link check was written first and removed, because
      it reported that every anchor link resolved, which was true and meaningless — this corpus has
      zero. Each of the four was shown red against a plant. What the pass itself found is in the
      session handoff: the calendar endpoint missing from the API surface since phase 10, an
      architecture paragraph that both claimed only Caddy was published and then named Mailpit's
      port, and four terms used ~220 times across the design docs that the binding glossary never
      defined
