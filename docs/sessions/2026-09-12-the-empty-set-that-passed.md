# Session handoff — 2026-09-12 — the empty set that passed

> **Purpose.** Six phase-11 rows, and the same failure twice: **a derivation that silently produced
> nothing, and an assertion that passed because there was nothing to check.** Neither was a plant.
> Both were caught by a positive control written minutes earlier for exactly that reason — §2 and
> §7. That is [T89](./2026-09-12-the-controls-that-had-never-run.md) from the previous session
> arriving twice more, and it is the part of this handoff worth reading if you read one part.
>
> **§2 and §3 are the finding.** "Every public endpoint is rate limited" was checked against a list
> of ten paths somebody typed, which had been wrong since phase 09. Derived from the security filter
> chain instead, it found **three endpoints that had never carried a limit** — one of which opens an
> outbound SMTP connection for anyone who asks.
>
> **§4 to §7 are the observability block.** Two controls written and never proven, two rows never
> built at all. The pattern the previous session named has not run out.
>
> **Committed, not pushed.** Two authored commits on `dev`, which is now **8 ahead of `origin/main`**
> and 6 ahead of `origin/dev`. `main` untouched. 985 backend tests, 0 failed. Phase 11 at **59 of 72**.

[previous]: ./2026-09-12-the-controls-that-had-never-run.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. No pull request was opened |
| `dev` | **8 ahead of `origin/main`**, 6 ahead of `origin/dev` — this session's 2, the previous session's 4, and 2 inherited before that. The work ends at **`df9279d`** |
| CI | **has still seen none of it.** Nothing was pushed |
| Backend | **985 tests, 0 failed, 109 classes** — was 961 / 105. Four new classes, 24 net new tests, one deleted |
| Frontend | **unchanged, and not run.** No frontend file was touched |
| Migrations | **`V10`**, unchanged. New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called** — `ScriptedChatModel` throughout; no credit was spent |
| Gates | `make check-docs` green — 281 links, 272 `§N` references. `make check-bindings` green, and `docker ps` agrees with it this time |
| Phase 11 | **59 ticked, 13 open.** Six rows ticked here |
| E2E stack | **still down.** Unchanged from [the previous handoff][previous] §6.4, and for the same reason: the machine is still on the hotspot, still `172.20.10.3` |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

The two commits:

| | |
|---|---|
| `f6333b2` | Derive the public surface, and limit the three it found |
| `df9279d` | Close the observability block: two proofs and two things that did not exist |

Production code changed in three files: `RateLimitProperties` (three policies), `ConversationService`
(the model-call and tool-call log lines), `application-local.yml` (slow-query threshold). Everything
else is tests and documents.

---

## 2. The public surface was a list somebody typed

`f6333b2`. `RateLimitPolicyOrderTest` carried a test called `nothing_public_is_unlimited`, and it
was a `List.of` with ten paths in it. **It had been wrong since phase 09**: neither chat endpoint was
ever added, so the two endpoints in this application that spend money on every call were covered by
policies no test required to exist.

This is not an oversight to tut at. A hand-written surface **cannot fail for the case that matters**,
because forgetting the endpoint in the controller and forgetting it in the list are the same act of
forgetting, performed by the same person in the same hour.

`RateLimitCoverageTest` writes down neither side:

- the endpoints come from `RequestMappingHandlerMapping` — the object Spring routes requests with,
  so there is no second source of truth to drift from;
- **whether an endpoint is public comes from the `AuthorizationManager` inside the running security
  filter chain**, asked the same question `AuthorizationFilter` asks in production.

Widen `permitAll` in `SecurityConfig` and every endpoint that becomes reachable arrives in the test
with it. The plant that proves this is the one to keep: adding `/appointments/**` to the permitted
list made the test name all six endpoints that became reachable, with no change to the test at all.

The old test is gone rather than kept alongside. A weaker, already-drifted copy of the same claim is
not a second opinion; it is the thing a reader trusts instead of the real one. What stayed in
`RateLimitPolicyOrderTest` is the question that genuinely needs a typed expectation: not *whether* a
path is covered but *which* of two overlapping policies covers it.

### 2.1 The control that caught it, before any plant

The first run reported `nothing_public_is_unlimited` **PASSED** and the positive control FAILED: the
derived public surface was **empty**. Every endpoint, including `POST /auth/login`, was coming back
denied.

The cause is [T91](#10-traps) and it is worth internalising. Every `permitAll` rule is a
`DeferredRequestMatcher`, which chooses between its MVC and its Ant candidate by reading the
**servlet registrations of the `ServletContext` the request carries**. A `MockHttpServletRequest`
built the obvious way carries `MockServletContext`, which has none — so every pattern reports no
match, including the rule naming the exact path being asked about. Nothing throws. The request falls
through to `anyRequest().authenticated()`, an anonymous caller is denied, and the surface is empty.

Built from the real embedded server's `ServletContext`, it matches. The fix is two lines; the point
is that **without the control the suite would have reported "nothing is unlimited" forever**, and
would have been believed, because that is the answer everyone wants.

---

## 3. Three endpoints that had never carried a limit

What the derivation found: `POST /auth/refresh`, `POST /auth/logout`, `GET /health`. Absent from the
policy list, and absent from [06-security.md](../06-security.md) §5's table too — **the table and the
test agreed with each other and neither agreed with the application**, which is what happens when
both are written by hand on the same afternoon.

`GET /health` is the one worth naming. It opens a database connection *and an outbound SMTP
connection* on every call, and it is reachable without authentication — so unlimited, it is an
amplifier a stranger can aim at our own mail server. Nothing had ever said so because nothing had
ever asked the question in a form that could produce an answer.

Limits added on the principal's call — 60/hour, 20/hour, 60/min per address, each with its reasoning
in the code — and §5 now lists thirteen rows. The container healthcheck polls `/health` every ten
seconds from *inside* the container, so it keeps a bucket of its own and cannot be starved by
traffic arriving through Caddy.

---

## 4. The health endpoint's only test could not fail

`df9279d`. `HealthEndpointTest` held one test: both dependencies reachable, both reported `UP`.
Replace the body of `checkDatabase()` with `return UP;` and it stays green. So does an endpoint that
has stopped checking anything and simply says so. **Two handoffs declined to tick this row on the
strength of it and were right to** — a monitor that cannot report `DOWN` is worse than no monitor,
because something is watching it.

Each dependency now fails against a **real refused connection** — a `DriverManagerDataSource` and a
`JavaMailSenderImpl` pointed at ports taken by binding and releasing them — rather than a stub that
throws what the test expects.

**Every failing case asserts the other component is still `UP`.** That is not thoroughness; it is
the symmetrical vacuity. A controller that reported `DOWN` unconditionally would satisfy every
"is it DOWN?" assertion ever written, and only the healthy half can catch it.

The fourth test is the reason this endpoint is hand-written instead of Actuator's: a failure names
the component and nothing else — no JDBC URL, no host, no exception text, and exactly two keys in
the body.

---

## 5. A request id nothing traced, and a business id nothing carried

`RequestIdFilter` and both tenant filters had been putting keys in the MDC since phase 02, and
nothing connected a real request to a real log line. The one test that mentioned `RequestIdFilter`
was about error responses; `JsonLoggingConfigurationTest` asserts the appender's *type*;
`AppenderRedactionTest` proves a hand-built MDC survives the encoder. **Delete `MDC.put` from either
filter and the whole suite stayed green** — and every production log would lose the only path back
from a customer's report to the request that caused it.

`RequestLoggingTest` drives real HTTP and reads the MDC off the events the application itself
logged. Both tenant filters are covered, and the second one had never been touched by any test:

- an **authenticated** request carries the business from its token (`TenantContextFilter`);
- a **public** request carries the business its path names (`SlugTenantContextFilter`), with nobody
  authenticated at all. Public traffic is where abuse arrives, so this is the half a log reader
  actually needs;
- a request that resolved no tenant carries **no** `businessId` — nothing invents one.

It also pins what the header validation is for. A supplied `X-Request-Id` is honoured so a trace
crosses the proxy hop, but an id is written verbatim into every line the request produces: a caller
who can put arbitrary text there can forge whatever reads the log. Both rejection paths — malformed
and oversized — are asserted, and the forged text is checked against every captured line.

---

## 6. The model call that left no trace

"LLM calls logged with model, latency, tokens and estimated cost" was not an untested control. **It
did not exist.** `OpenAiChatModel` contained no log statement at all, the orchestration loop logged
only ceilings and failures, and the cost `CostTracker` computes for the daily cap was discarded the
moment it had been checked. An owner asking why their bill moved, or why a turn took eleven seconds,
had nothing to read.

Now emitted from `ConversationService`, where everything needed is already in scope: model, latency,
both token counts, **the same cost estimate the cap is charged**, each tool's name and outcome, and
the conversation id that joins the lines to each other.

Written as `StructuredArguments.kv` rather than interpolated into the message, so a day's spend is a
sum rather than a regular expression. That the arguments become real JSON fields — and not merely
words in `message` — is asserted through the encoder the shipped `logback-json.xml` builds, because
"structured" is exactly the kind of claim that is true of the intention and false of the output.

### 6.1 The half that is a security control

[06-security.md](../06-security.md) §10: *token counts, latency, tool names and outcomes — not
message content.* The easiest way to log a useful AI trace is to log the conversation, and the
conversation is the customer's name, their number and what they wanted.

So every test here runs a real turn carrying a distinctive phrase and then looks for it in every
captured line, in the message and in the arguments. **Tool arguments are never logged** — that is
where a name and a phone number arrive. Two of the five plants leak content deliberately, one from
the tool call and one from the reply, because a rule about what must not be logged is only a rule if
something fails when it is broken.

**A limitation, stated rather than hidden:** the model name logged is the *configured* one, not the
one the provider reports. The `ChatModel` port does not carry it, and widening `ChatResponse` to
make a log line more precise would change every implementation and every double for a field the
operator already knows. If the provider ever starts resolving an alias to a dated snapshot that
matters for billing, that is the line to revisit.

---

## 7. Slow-query logging, and a test that was flaky by construction

Not configured, and nothing would have said so. A setting like this one is the easiest kind of claim
to leave untrue, because **its entire output is absence**: no line is what a working threshold and a
missing setting both look like.

`hibernate.log_slow_query: 100` in `local` alone. The mechanism is asserted separately from the
wiring — one test lowers the threshold and proves Hibernate still emits on `org.hibernate.SQL_SLOW`
(the half that breaks silently on an upgrade that renames the property), another reads the shipped
`application-local.yml`, because a mechanism nothing enables is not a feature. That second test
rejects a threshold above a second as well as a zero: both read as configured and report nothing.

A third test earns its place because this is **the only setting in the repository that prints SQL by
default**. Hibernate logs the prepared statement with its placeholders; if a version ever started
inlining bound values, every developer's console would quietly become a PII sink.

### 7.1 The second empty set

The first version used a one-millisecond threshold and an ordinary `count`. It passed. Then it
failed. Then it passed — because at one millisecond an ordinary count is slow enough **the first
time**, while the statement is being prepared and the connection warmed, and whichever test ran
second found nothing. Not a flaky environment: [T93](#10-traps), flaky by construction, and it would
have been "fixed" by a retry annotation by anyone who did not look.

It now sleeps fifty milliseconds in a **scalar subquery** rather than in the `where` clause, because
against an empty table a predicate may never be evaluated at all. Run three times to confirm.

---

## 8. Every open item

### 8.1 Committed, not pushed

Eight commits on `dev` ahead of `origin/main`, six ahead of `origin/dev`. **CI has seen none of it.**
The two jobs a local suite cannot stand in for — Compose smoke and End-to-end — have now not run
against three sessions of work. This is the oldest open item in the project and it grows.

### 8.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**.

**G28 is new.** *The §5 limits table and `RateLimitProperties` agree by hand.* Coverage is derived
now — a public endpoint with no policy fails the build — but the **numbers** are not. Change
`refresh` to ten an hour and [06-security.md](../06-security.md) §5 still says sixty, with nothing
failing. This is the same shape as the list §2 deleted, one level down: the thing that was checked
is no longer the thing that can drift. Named rather than fixed, because the fix is another bespoke
gate and that is §8.5's question.

**G27 is unchanged.** A gate that reads configuration still cannot see a running system that
contradicts it. Nothing was added for it; `docker ps` and `make check-bindings` happened to agree
this session, which is a measurement and not a guarantee.

### 8.3 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; rate-limit buckets are in memory; no key rotation; no backups;
`AI_RETENTION_ENABLED=false` silently disables the purge.

**New to this list:** the logged model name is the configured one, not the provider's (§6).

### 8.4 The E2E stack is still down

Unchanged from [the previous handoff][previous] §6.4, and not attempted here. The machine is still on
the hotspot — `ipconfig getifaddr en0` still reports `172.20.10.3` — and that handoff is explicit
that three attempts on this link cost fifteen minutes and produced nothing. The backend image layer
is cached; only the frontend install remains.

**Nothing in this session needed it, and nothing in it was verified by it.**

### 8.5 The question, now raised by four sessions

`check-headers`, `check-ports`, `check-bindings`, `check-docs` — and G28 wants a fifth. This session
added no gate and found one more thing that wants one. The alternative this session actually
demonstrates is worth weighing against another bespoke check: **derive the list instead of gating
it.** `RateLimitCoverageTest` is not a gate over a document; it is a test that reads the system and
cannot be short. It cost about as much as a gate and it cannot drift.

Still the principal's call.

---

## 9. Next steps, in order

1. **Push, and open a pull request.** Eight commits, three sessions, no CI. Everything below is worth
   less than this.
2. **`revenue` names its remainder after a currency change**, per [ADR-0010](../adr/0010-revenue-reports-one-currency-and-names-the-remainder.md).
   Two rows, offline, self-contained — the last substantial pair that does not need the E2E stack.
3. **The rest of the [06-security.md](../06-security.md) walk.** §5 and §12 are done and both broke
   the same way; the remaining sections have not been walked with the same suspicion.
4. **The full-history secret scan.** Needs a scanner installed. **Not on a tethered connection.**
5. **The end-of-phase gates** — clean clone, `make up && make seed`, the demo script, the concurrency
   test, the Definition of Done sweep. All of these want the E2E stack and a real connection.
6. **The principal's**: G28 and G27's shape (§8.5), credits for [#17]'s remaining arm, [#15]'s title.

---

## 10. Traps

**T91 — a `MockHttpServletRequest` without the real `ServletContext` makes every Spring Security
`permitAll` matcher report no match.** `DeferredRequestMatcher` decides between its MVC and Ant
candidates by reading the context's servlet registrations, and `MockServletContext` has none.
Nothing throws; every request simply falls through to `anyRequest()`. Build the request from
`WebApplicationContext.getServletContext()`. §2.1.

**T92 — a test that asserts "the set of bad things is empty" passes hardest when the derivation is
broken.** Twice this session. Every such test needs one assertion that it can still see its subject,
and that assertion has to name specific members, not just a non-zero count.

**T93 — a one-millisecond threshold is met by statement preparation, once.** The first query through
a fresh statement and connection is slow; the second is not. A threshold test whose subject is only
*sometimes* over the line is flaky by construction and will be misread as a flaky environment. Make
the subject reliably slow. §7.1.

**T94 — a fixture rejected before the code under test runs makes a log assertion vacuous.** The
anonymous-request test sent `code`/`contact` where the endpoint wanted `confirmationCode`/`phone`;
validation rejected it, nothing logged, and the test would have asserted over an empty list. The
control said "captured 0 events" and named the problem.

**T95 — `pg_sleep` in a `where` clause may never be evaluated.** Against an empty table there is no
row to test the predicate on. Put it in a scalar subquery, which runs regardless of what the filter
matches.

**T96 — `StructuredArguments.kv` with no placeholder in the message vanishes from the console.** The
field still reaches the JSON, so a test that only reads the encoder passes while a human tailing the
log sees a bare sentence. Give the message a `{}` per argument.

**T97 — Hibernate's slow-query logger is `org.hibernate.SQL_SLOW`, and it only sees Hibernate.** The
same query issued through `JdbcTemplate` reports nothing, which reads exactly like a threshold that
is not working.

**T98 — restoring a logger's level in `@AfterEach` is not optional.** These tests raise
`dev.reception` to DEBUG to capture what a request logged. Left raised, every later test in the same
JVM emits differently, and the one that then fails will be somewhere else entirely.

---

## 11. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's four classes alone. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.ratelimit.RateLimitCoverageTest' --tests 'dev.reception.common.logging.RequestLoggingTest' --tests 'dev.reception.common.logging.SlowQueryLoggingTest' --tests 'dev.reception.ai.application.AiCallLoggingTest'
```

```bash
# What is actually published right now — the check G27 is about.
docker ps --format '{{.Names}}\t{{.Ports}}'
```

```bash
# Finish the previous handoff's §6.4. Do not attempt this on a tethered link.
make up-e2e
```

---

## 12. Confidence

**High** on all four test groups. Twenty-three plants across them — six, four, five, five and three
— every one shown red, reverted, and `cmp`-verified byte-identical, with the full suite green at 985
tests afterwards.

**High** on the three missing rate limits and on the slow-query mechanism, both established by
running the thing rather than reading it: the endpoints were named by the derivation, and the
threshold was observed emitting `Slow query took 2 milliseconds [select count(b1_0.id) from
businesses b1_0]` before anything was configured.

**Moderate** on the chosen limit *values* (§3) and the slow-query threshold (§7). They are
defensible and their reasoning is in the code, but no load was applied and no demo build was run
against them. The first `429` a real user sees will be better evidence than any argument here.

**Not established:** anything CI does. Nothing was pushed. The Compose smoke test and the E2E job
have not run against these changes, and the E2E topology has not been built since the images
changed.

**Corrected in flight:** the first version of the AI logging dropped `conversationId` from the
failure line that already had it — a change that made the log *worse* while adding fields to it. Now
on all three lines and pinned by an assertion.
