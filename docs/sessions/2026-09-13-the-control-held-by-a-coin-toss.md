# Session handoff — 2026-09-13 — the control held by a coin toss

> **Purpose.** One section of the [06-security.md](../06-security.md) walk, continuing from
> [the previous session][prev]: **§1**, the threat-model table. It is the cheapest remaining section
> and was expected to be the richest, because it is an index into everything else.
>
> **It was very nearly clean, and that is the result.** Seven rows, seven named primary controls,
> and **six of them already resolved to a test that runs.** After three sessions of finding claims
> no file implemented, this is the first section where the walk mostly confirmed the work rather
> than corrected it. The base rate finally moved.
>
> **The seventh is the finding.** *"Rate limiting by IP"* — the control for the **availability**
> row — was **true, and the *by IP* half was asserted by nothing.** Two tests appear to cover it and
> neither can: both drive a single client, so both are equally true of a filter that keys every
> bucket on a constant. The property is claimed four more times in `RateLimitProperties`' own prose
> and checked in none of them.
>
> **The part worth reading is §4.** The control rests on a filter ordering with a margin of **ten**,
> and the plant that tied the two filters left **the behavioural test green** — tied filters are
> sequenced arbitrarily and that run happened to land the right way. Only the assertion on the
> registered orders caught it. That is a different failure from the last three sessions': not an
> instrument that was blind, but one that was *sampling*. Hence the title.
>
> **No production code changed.** Which is the strongest available statement about the finding: the
> system was already correct and the guarantee was never written down.
>
> **Committed, not pushed.** One commit plus this file, leaving `dev` **25 ahead of `origin/main`**
> and 23 ahead of `origin/dev`. `main` untouched. 1027 backend tests, 0 failed. Phase 11 still at
> **61 of 72**.

[prev]: ./2026-09-13-the-tests-that-could-not-see.md
[previous]: ./2026-09-13-the-tests-that-could-not-see.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **25 ahead of `origin/main`**, 23 ahead of `origin/dev`. The work is **`a251417`**; everything after it is this handoff |
| CI | **has still seen none of it.** Six sessions now |
| Backend | **1027 tests, 0 failed, 116 classes** — was 1024 / 115. One new class |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit was spent — **G32 is still unanswered** |
| Gates | `make check-docs` green, 318 links. No new `make check-*` target — the finding was answerable in a test |
| Phase 11 | **61 ticked, 11 open**, unchanged. The security-walk row stays open: **§15 is the last section** |
| E2E stack | **still down.** Not attempted; nothing here needed it |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

| | |
|---|---|
| `a251417` | Assert the "by IP" §1 named and one client could not check |
| this file | The handoff |

Production code changed in **no file**. Test code and two documents.

---

## 2. §1 — six of seven, which is the news

The table maps seven assets to a *"primary control"*. The walk's question for each is the one §14
failed: **does the named control resolve to a test that actually runs?**

| Row | Control it names | Verdict |
|---|---|---|
| Tenant data | Server-derived tenancy + composite FKs | **Resolves.** `TenantIsolationSweepTest`, `EndpointCoverageTest`, `SmuggledBusinessIdTest` — and the first of those was extended last session |
| Customer PII | Code + phone required; aggressive rate limits | **Resolves.** `the_code_alone_is_not_enough` and `the_phone_alone_is_a_schema_rejection`, plus the five-an-hour lookup budget |
| Appointments | Confirmation Code / Manage Link authority | **Resolves.** Including a token for A refused on B's path |
| LLM budget | Rate limits, ceilings, per-business daily cap | **Resolves.** The tool-call ceiling *and* the daily cap, which closes the conversation at `LIMIT_REACHED` — checked separately, because the ceiling test does not cover the cap |
| Credentials | httpOnly cookies, redaction, BCrypt | **Resolves.** `AuthCookieSecurityTest`, `AppenderRedactionTest`, and the `$2a$12$` prefix in `RegistrationTest` |
| Availability | **Rate limiting by IP** | **Does not resolve.** §3 |
| Integrity | Database exclusion constraint | **Resolves.** Twenty threads, since phase 06 |

Six of seven is worth stating plainly because the previous two sessions were the opposite, and the
temptation after a run like that is to keep finding things. §1 did not need correcting. The table is
now annotated with what asserts each row, so the next person checks a list rather than repeating the
search.

---

## 3. The seventh — "by IP", claimed five times and checked none

Two tests look like coverage:

- `RateLimitCoverageTest` derives the public surface from the security filter chain and proves every
  endpoint is matched by **a policy**. It is a good test and it says nothing about the bucket key.
- `RateLimitTest` turns the limits on and proves one **bites**.

Both drive **one client**. A `clientAddress()` returning a constant passes both — demonstrated, §5.
And the per-address property is not a stray sentence in §1; it is load-bearing prose in
`RateLimitProperties` four more times, most explicitly for `refresh` (*"keyed on the client address
rather than the proxy's, which is what `server.forward-headers-strategy` buys"*) and for `health`
(*"it has a bucket of its own and cannot be starved by traffic arriving through Caddy"*).

### 3.1 The control is true, and stands on three facts nothing checked

Verified before writing anything, because two of the three were things I would otherwise have been
recalling rather than reading:

1. **`server.forward-headers-strategy: framework`** is set in `application.yml` — the **base**
   profile, and neither `application-prod.yml` nor `application-test.yml` declares a `server:` block,
   so it reaches production and the suite alike.
2. **Spring Boot registers `ForwardedHeaderFilter` at `HIGHEST_PRECEDENCE`.** Read off the
   bytecode rather than remembered: `setOrder(-2147483648)` in
   `ServletWebServerFactoryAutoConfiguration$ForwardedHeaderFilterConfiguration`. `RateLimitFilter`
   is `HIGHEST_PRECEDENCE + 10`, so the rewrite happens first — **by ten**.
3. **That filter's wrapper overrides `getRemoteAddr()`.** Also read off the bytecode:
   `ForwardedHeaderFilter$ForwardedHeaderExtractingRequest` declares a `remoteAddress` field and
   overrides `getRemoteAddr()`, `getRemoteHost()` and `getRemotePort()`.

Had **3** been false, this would have been a live defect rather than an unasserted control — every
visitor behind Caddy sharing one bucket in production. It was worth the ten minutes to find out
which session this was going to be.

### 3.2 Why the row is *Availability* and not *Abuse*

The failure mode these admit is the **inverse** of the control. A limiter that pools every visitor
into one bucket does not merely fail to stop an attacker — it lets one stranger spend the budget for
everybody and close a public endpoint to real users. `application.yml` already said it: *"a limit
that locks out real users while stopping nobody."* That sentence was an argument for a configuration
line, and there was no test behind it.

### 3.3 The test, and why it cannot pass vacuously

Every request in the suite arrives from `127.0.0.1`. Two callers can therefore **only** be told
apart if `X-Forwarded-For` is transmitted *and* honoured — so a pass requires the header to have
survived the client (**T99**, which beat the last session), the filter to have run, and the ordering
to have held. There is no arrangement in which `RateLimitAddressTest` passes and the control is
absent. The converse vacuity — a limiter switched off entirely — is closed by making **each**
address exhaust its own budget and be refused in its own right.

---

## 4. The plant that stayed green

Four plants, and three behaved ordinarily. The fourth is the reason this handoff has the title it
has.

`RateLimitFilter` moved from `@Order(Ordered.HIGHEST_PRECEDENCE + 10)` to
`@Order(Ordered.HIGHEST_PRECEDENCE)` — **the obvious edit**, and the one a reviewer would wave
through, because this filter genuinely must precede authentication and that annotation is how you
say so. It closes the margin to zero.

**The behavioural test passed.** Tied filters are sequenced arbitrarily; that run happened to land
the right way. Only the assertion comparing the two registered order values went red.

This is not the failure the last three sessions catalogued. Those were instruments that were
**blind** — a probe that could not see the surface, a sweep answered by a filter first, a client
that dropped a restricted header. This one **could** see, and the thing it was looking at was
non-deterministic. A behavioural probe of a property decided by an undeclared ordering is a
*sample*, and it reports the coin, not the rule.

The general form, which is worth more than the instance: **where the correctness of A depends on A
running before B, and nothing declares that, testing the outcome is sampling.** Assert the ordering
too. A control held by a coin toss does not fail — it stops being a control, silently, and the run
that would have told you is the one you did not do.

---

## 5. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | `forward-headers-strategy` removed from `application.yml` | **Red, correctly.** Alice — who had made no request at all — was refused `429`. The outage, demonstrated rather than argued. The ordering test also went red, naming the missing filter |
| 2 | `RateLimitFilter` at `HIGHEST_PRECEDENCE` (tied) | **Red on the ordering test only.** §4 |
| 3 | `clientAddress()` returning a constant | **Red, correctly.** One global bucket |
| 4 | `clientAddress()` returning a fresh UUID per request | **Red on both behavioural tests.** Caught by the second one, which exists for exactly this: a bucket-per-request passes "two addresses differ" for the wrong reason |

Plant 4 is why `one_address_is_one_bucket` earns its place — it is the only plant that test uniquely
catches, and without it the main assertion is satisfied by a key that varies for *any* reason rather
than by address.

---

## 6. Every open item

### 6.1 Committed, not pushed

**Twenty-five commits, six sessions, no CI.** Unchanged in kind from [the previous handoff][prev]
§7.1 and one commit larger. Compose smoke and End-to-end remain the two jobs a local suite cannot
stand in for, and `check-access-log` has still never executed in either.

Still the largest single risk in the project.

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G28**, **G29**, **G30**, **G31**.

**G32 is unchanged and still the one needing an answer soonest.** The system prompt changed last
session and the level-3 corpus has not been run against it. Nothing this session touched it.

**G33 is new.** *Nothing asserts the ordering of the other filters.* `RateLimitFilter` is now held in
place relative to `ForwardedHeaderFilter`, but the same class of defect is open everywhere else:
`JsonOnlyWriteFilter` must run before authentication, the rate limiter must run before both, and
those relationships live in `@Order` arithmetic that nothing reconciles. §4's lesson generalises and
has been applied in exactly one place.

### 6.3 Carried

Unchanged from [the previous handoff][prev] §7.3, including the Manage Link token still being in a
URL.

### 6.4 The E2E stack is still down

Unchanged and not attempted.

---

## 7. Next steps, in order

1. **Push, and open a pull request.** Twenty-five commits, six sessions, no CI. Deferred twice by the
   principal; it has not stopped growing.
2. **Run the level-3 corpus** against the system prompt changed last session, or decide not to and
   say so. G32.
3. **Finish the walk: §15 is the only section left.** See §8.
4. **G33**, if the principal wants §4's lesson generalised rather than left as one instance.
5. **The full-history secret scan.** Needs a scanner installed. **Not on a tethered connection.**
6. **The end-of-phase gates** — clean clone, `make up && make seed`, the demo script, the concurrency
   test, the Definition of Done sweep. All want the E2E stack and a real connection.
7. **The principal's**: **G32**, G30, G31, G33, G28, G29; credits for [#17]'s remaining arm;
   [#15]'s title.

---

## 8. §15 is the last section, and it is a different job

Eight sections walked across three sessions. §15 is the **accepted-risks table** and it cannot be
"verified" the way the other fourteen were — there is no control to resolve to a test, because the
entry *is* the decision not to build one.

What it can be checked for is the **opposite** defect: an entry that no longer describes reality.

- **A risk since closed that still reads as open.** The clearest candidate: §13 documented a CSRF
  layer the application did not have, that layer was **built two sessions ago**, and §15 was not
  revisited.
- **A risk that is real and missing.** The Manage Link token's location in a URL is a live, accepted
  risk that this table does not list — and §15's own last entry exists precisely because something
  was in neither the table nor the deployment notes, which is *"the state this table exists to make
  impossible."* That sentence is a standing invitation to check the rest of it.
- **An entry whose justification has expired.** *"In-memory rate limiting — single instance in
  MVP"* is the one to read hardest after this session: everything in §3 above is true of one
  process, and the externalisation note is the place that claim is carried.

Cheap, and the last thing standing between this walk and a ticked row.

---

## 9. Traps

- **T109 — a behavioural probe of an ordering-dependent property is a sample, not an assertion.**
  Tied filter orders are resolved arbitrarily; the run that passes proves nothing about the next
  one. Assert the ordering itself alongside the behaviour. §4. **This one passed a plant.**
- **T110 — read a framework's ordering off the bytecode, not off memory.** The whole of §3 turned on
  `setOrder(-2147483648)` and on `ForwardedHeaderExtractingRequest` overriding `getRemoteAddr()`.
  Both were a `javap` away, and being wrong about the second would have meant filing an unasserted
  control as a live production defect. §3.1.
- **T111 — "two callers are distinguished" is passed by a key that varies for any reason.** A bucket
  per request satisfies it. Pair it with the assertion that one caller shares one bucket across
  requests. §5, plant 4.
- **T112 — a control repeated in prose is not a control tested once.** "Keyed by address" appeared in
  five places across two files and had zero assertions. Prose repetition reads like corroboration and
  is the opposite: it is the same unchecked claim, copied. §3.
- Carried and re-confirmed: **T89** (the positive control — every assertion here is paired with one
  that fails if the limiter is off), **T99** (restricted headers; the design of §3.3 is built around
  it), **T105** (tagged tests do not run), **T69**, **T70**.

---

## 10. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's new class. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.ratelimit.RateLimitAddressTest'
```

```bash
# G32: the level-3 corpus against the system prompt changed last session. NEEDS A KEY AND SPENDS
# CREDIT — the principal's call. Without a key the corpus skips itself rather than failing.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline -PincludeTags=llm
```

---

## 11. Confidence

**High** on the finding and on the fix. The gap was measured — two existing tests, one client
between them — and the control is now shown red four ways, including one plant that the behavioural
half could not catch.

**High** on the three facts in §3.1, because two of them were read off the bytecode rather than
recalled, and the one that mattered most (`getRemoteAddr()` being overridden) is the difference
between this session's report and a production incident.

**Medium on the coverage, and the limit is worth naming.** `RateLimitAddressTest` proves the
application honours `X-Forwarded-For` and buckets by it. It says nothing about **Caddy** setting the
header correctly, and nothing about the trust boundary that makes honouring it safe — which is the
compose topology, gated by `make check-bindings` in CI, **where it has never run.** The three facts
are asserted; the fourth, that only Caddy can reach the backend, is asserted somewhere that has not
executed in six sessions. That is §6.1 again, from a different direction.

**High** on §1 being finished, and this is the first section of the walk I would say that about
without a caveat. Six rows resolved without changing anything, one was closed, and the table now
names what asserts each.

**Low on the walk being finished, but much less low than last time.** Eight of fifteen sections, and
**§15 is the only one left** — a read rather than an investigation. §8 says where to start.
