# Phase 11 — Hardening, Verification and Deployment

## Goal

Turn a working system into a demonstrable one: complete the cross-cutting test suites, close the security
items deferred by design, seed a convincing two-tenant demo, and write the documentation that lets a
stranger run and understand it.

## Scope

**In:** the complete tenant-isolation suite, the E2E flow, seed data, observability, security headers,
secret-handling verification, performance sanity checks, README and demo script, one documented deploy path.

**Out:** operating an internet-reachable instance (the MVP contract is local compose), monitoring
infrastructure, backups.

## Dependencies

Everything. This phase hardens what exists; it does not add features.

## Why this is not "write the tests now"

Every phase shipped its own tests. What is left is the work that is only possible once the whole system
exists: suites that enumerate *every* endpoint, an E2E that crosses every layer, and a seed that exercises
the whole model at once.

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

Any miss is fixed with an index in this phase.

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
- [ ] `README.md`, `.env.example` and `docs/deployment.md` are complete
- [ ] **Every box in [07-mvp-scope.md](../07-mvp-scope.md) § MVP Definition of Done is ticked**

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

### Observability
- [ ] JSON logging with request id and `business_id`
- [ ] LLM call metrics without content
- [ ] Health endpoint covering database and mail
- [ ] Slow-query logging in `local`

### Performance
- [ ] Availability benchmark
- [ ] Appointment list benchmark at 10 000 rows
- [ ] Analytics benchmark
- [ ] Add indexes for any miss

### Documentation
- [ ] `README.md` with prerequisites, commands, URLs and credentials
- [ ] Demo script
- [ ] `.env.example` audited against the compose file
- [ ] `docs/deployment.md`
- [ ] Final consistency pass over `/docs` and `CONTEXT.md`
