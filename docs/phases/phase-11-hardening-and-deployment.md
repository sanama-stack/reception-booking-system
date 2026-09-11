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

The single Playwright flow from [08-testing-strategy.md](../08-testing-strategy.md) §8, run against the
scripted model so it is deterministic in CI:

register → configure → book classic → book by chat → assert both emails in Mailpit → follow the Manage Link
→ cancel → verify in the dashboard → mark completed → verify analytics.

Plus a 360 px run of the public page.

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

### The revenue remainder

[ADR-0010](../adr/0010-revenue-reports-one-currency-and-names-the-remainder.md), decided 2026-09-11.
`GET /analytics/summary` gains `excluded` on its `revenue` object — one entry per other currency found
among the same COMPLETED Appointments in the same range, each with its own sum, empty when there are
none — and `/analytics` renders it as a footnote rather than a second headline.

The existing test *"after a currency change, revenue reports the new currency only"* becomes wrong as
written and is where the new behaviour gets asserted. It was a record of what the endpoint did, never an
argument that it was right.

### Documentation

- `README.md`: what it is, prerequisites, `make up`, seeded credentials, URLs (app `:8080`, Mailpit `:8025`,
  API docs `/api/docs`), and a **step-by-step demo script** a stranger can follow
- `.env.example` complete and accurate
- `docs/deployment.md`: one documented path (single VPS behind Caddy with TLS), including what would have to
  change first — externalised rate limiting, real SMTP, backups, secret rotation

## Testing

- [ ] Reflection-driven isolation suite over every tenant-scoped endpoint
- [ ] Cross-tenant native-insert rejection
- [ ] AI tool schemas contain no tenant parameter
- [ ] Full E2E flow green in CI against the scripted model
- [ ] 360 px public-page run
- [ ] Rate limits verified for every public endpoint
- [ ] Security-header test
- [ ] Log redaction test
- [ ] `prod` profile refuses default secrets
- [ ] The three performance checks
- [ ] Frontend unit tests green in CI, including the timezone counterfactual
- [ ] `ai_message` retention purges past the window and spares what is inside it
- [ ] Revenue reports the remainder after a currency change
- [ ] Full suite green from a clean clone

## Definition of Done

- [ ] `git clone && make up && make seed` produces a fully working, populated system
- [ ] The demo script in the README runs end to end without deviation
- [ ] Every tenant-scoped endpoint is probed by the isolation suite
- [ ] The E2E flow passes in CI
- [ ] The concurrency test passes repeatedly
- [ ] All security items are implemented or explicitly listed as accepted risks
- [ ] No secret is in the repository or its history
- [ ] The three performance checks pass
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
- [ ] Reflection-based endpoint discovery for the isolation suite
- [ ] Isolation probe per endpoint asserting `404`
- [ ] `business_id`-in-request rejection tests
- [ ] Public cross-tenant leakage tests
- [ ] Native cross-tenant insert test
- [ ] AI schema tenant-parameter assertion
- [ ] Playwright E2E flow
- [ ] Mailpit API assertions inside E2E
- [ ] Mobile-viewport E2E run
- [ ] Rate-limit tests for every public endpoint
- [ ] Security-header test
- [ ] Log-redaction test
- [ ] Vitest + Testing Library wired into the Frontend CI job
- [ ] Timezone rendering test, proven against its counterfactual
- [ ] Empty / loading / error state tests per screen
- [ ] 360 px width assertions replacing the hand-run sweep

### Seed
- [ ] `make seed`, `local`-profile-guarded
- [ ] Salon Aria with services, employees, schedules, time off, FAQs, appointments
- [ ] Dato's Auto in a different timezone and currency, with a closure and a buffered long service
- [ ] Past and future appointments in mixed statuses
- [ ] Credentials printed and documented

### Security
- [ ] Walk [06-security.md](../06-security.md) and verify each control
- [ ] Redaction filter verified across appenders
- [ ] `prod` default-secret refusal
- [ ] Security headers in Caddy
- [ ] Full-history secret scan
- [ ] Error-response leakage review
- [ ] `ai_message` retention window, documented and enforced by a scheduled purge

### Observability
- [ ] JSON logging with request id and `business_id`
- [ ] LLM call metrics without content
- [ ] Health endpoint covering database and mail
- [ ] Slow-query logging in `local`

### Performance
- [ ] Availability benchmark
- [ ] Appointment list benchmark at 10 000 rows
- [ ] Analytics benchmark
- [ ] ~~Add indexes for any miss~~ — fix any miss at its cause, bound or index (see *Performance sanity*)
- [ ] Perf dataset generator committed as a script, and `reception_perf` dropped
- [ ] G15 — the calendar query re-measured on Hibernate's own statement
- [ ] G16 — at least one cold-cache reading

### Documentation
- [ ] `README.md` with prerequisites, commands, URLs and credentials
- [ ] Demo script
- [ ] `.env.example` audited against the compose file
- [ ] `docs/deployment.md`
- [ ] Final consistency pass over `/docs` and `CONTEXT.md`
