# Session handoff — 2026-09-12 — the controls that had never run

> **Purpose.** Three security controls were read, reviewed and shipped, and **not one of them had
> ever been executed.** §3, §4 and §5 are those three. They are not a theme I went looking for;
> they are what the phase-11 security block turned out to contain once each row was taken
> seriously, and the pattern is sharp enough to be worth the heading: *a control nothing runs is a
> control nothing has checked, however carefully it was written.*
>
> **§2 is the environment, and it is the part to read if you read only one.** The stack that was
> running contradicted a gate that was green. Not a stale document — a **stale process**, which no
> gate in this repo can see. That is [G27](#62-gaps), and it is new.
>
> **§6 is what is open.** The E2E stack is **down** and could not be rebuilt: three attempts, all
> defeated by the network. That is the one thing here left worse than it was found, and §6.4 says
> exactly how to finish it.
>
> **Committed, not pushed.** Four authored commits on `dev` including this one, `main` untouched. 961 backend tests,
> 0 failed. Phase 11 at **53 of 72**.

[previous]: ./2026-09-12-the-machine-that-could-verify-after-all.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. No pull request was opened |
| `dev` | **6 ahead of `main`** — this session's 4 (three of work, plus this handoff), and the 2 handoff commits [the previous session][previous] left behind. The work ends at **`abe07db`** |
| CI | **has not seen any of it.** Nothing was pushed |
| Backend | **961 tests, 0 failed, 105 classes** — was 937 / 102. Three new classes, 24 new tests |
| Frontend | **unchanged, and not run.** No frontend file was touched |
| Migrations | **`V10`**, unchanged. New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit was spent |
| Gates | `make check-docs` green — 275 links, 268 `§N` references. `make check-bindings` green, **and see §2** |
| Phase 11 | **53 ticked, 19 open.** Six rows ticked here, all in the security block |
| E2E stack | **down.** See §6.4 — this is the open item |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

The three commits:

| | |
|---|---|
| `2d6586e` | Show the prod secrets guard actually refusing |
| `67fdd36` | Check the redaction at the appenders, not at the masker |
| `abe07db` | Assert §11 on the path that can actually leak |

---

## 2. The gate was green and the ports were open

The session began by clearing what [the previous handoff][previous] §6.4 flagged as stale. What it
found is worth more than the tidying.

`make check-bindings` — written *last* session, precisely to stop
[06-security.md](../06-security.md) §12 drifting from the files — was **green**. Measured at the
same moment, against the machine's own LAN address:

| | before | after |
|---|---|---|
| Postgres `9085` | **accepted a connection** | refused |
| Mailpit UI `9083` | **HTTP 200** | refused |
| E2E Postgres `9185` | **accepted a connection** | (stack down) |
| E2E Mailpit UI `9183` | **HTTP 200** | (stack down) |

Both databases were reachable from the network while the gate that exists to prevent exactly that
reported success. Nothing was wrong with the gate. The containers were **created before the
change** and carry the binding they were created with; `docker compose config` describes what the
next `up` would do, not what is running now. `docker compose up -d` detected the drift and
recreated both — the fix is a single command, and the point is that nothing asked for it.

**This is not the documentation-drift shape.** G26 is about a claim no file implements. Here the
file implements it, the gate checks the file, and the *running system* is the thing out of step.
A gate that reads configuration cannot see a process that predates it. Filed as **G27**.

### 2.1 What was measured, and why it needed two probes

The "after" column is only worth something because the "before" column exists. A single probe
against a loopback-bound port cannot distinguish *bound to loopback* from *not running* — both
refuse. This is **T76** from the previous session arriving again in its third costume, and it will
keep arriving: **an assertion that something is unreachable needs a control that is reachable.**

---

## 3. `SecretsGuard` had never been constructed

`2d6586e`. [deployment.md](../deployment.md) §5 already said this row was unticked and why: *"the
guard is written and reads correctly, but there is no test that starts the `prod` context with a
default secret and asserts the refusal."* It had been true since phase 01.

The reason it stayed true is mechanical and worth naming, because it generalises. `SecretsGuard` is
`@Profile("prod")`. Every test in this suite runs under `test` or no profile at all. **No test had
ever constructed the object** — not as an oversight, but because the annotation that makes it a
production-only control also makes it invisible to a suite that never starts production.

`SecretsGuardTest`, twelve tests. Each one starts a real context rather than calling
`afterPropertiesSet()` directly, because half of what can break here is wiring — a guard not
registered under `prod`, or one reading a key nothing sets, refuses nothing at all. Registering the
class through `ApplicationContextRunner.withUserConfiguration` **does** honour `@Profile`, so the
absence-outside-prod case is a real assertion rather than a restatement of the annotation.

### 3.1 The plant that matters

Five of the six plants are the ones you would expect — remove the profile, make the throw a no-op,
delete the prefix check, delete the length floor, put the value in the message. The sixth is the
one to keep:

**A local default in `application.yml` that loses its `local-dev-only-` prefix silently disables
the guard**, and every other test stays green, because they all carry their own literals.
`every_local_default_still_carries_the_prefix_the_guard_matches` reads the real file. The guard
recognises a default by a string prefix; the defaults live somewhere else entirely; nothing had
ever connected the two.

### 3.2 Drift in the keys fails closed, and that is worth not testing

Both `@Value` lookups carry an empty default. Rename the property on either side — in the guard or
in `application.yml` — and the guard sees blank, reports "is not set", and refuses to start. There
is no test for this because the design already fails safe; it is recorded in the class's javadoc so
the next reader does not write one.

### 3.3 The correction that had been applied to one file of five

The audit on 2026-09-12 found `.env.example` claiming the guard checks *any* secret, where it
checks three of the five [06-security.md](../06-security.md) §9 lists. It corrected `.env.example`.

The same sentence was still standing in **four other places**: §9 itself, the guard's own javadoc,
`application-prod.yml`, and [phase-01-foundation.md](../phases/phase-01-foundation.md). All four
corrected here. `make check-docs` could not have caught it — it checks links, section references
and inventories, none of which a wrong sentence disturbs.

---

## 4. The redaction was tested everywhere except where it runs

`67fdd36`. Three tests already covered this ground:

| | what it proves |
|---|---|
| `PiiValueMaskerTest` | the rules redact |
| `ConsoleRedactionTest` | both converters redact, and a **hand-written** configuration loads |
| `JsonLoggingConfigurationTest` | the real `logback-json.xml` loads, and its appender and encoder are of the right **types** |

None of them touches the thing that actually redacts in production. Delete the
`<jsonGeneratorDecorator>` block from the real file — the whole of the masking in the appender that
ships — and **all three stay green while every deployed log goes out unmasked.** That is not an
argument in this handoff; it is Plant A, and it ran.

`AppenderRedactionTest` loads the real `logback-json.xml`, takes the encoder that file builds, pushes
secrets through it and reads the bytes.

### 4.1 The console half is a file check, deliberately

The console appender cannot be executed here. Its root binding lives inside `<springProfile>`, which
only Spring Boot's **package-private** `SpringBootJoranConfigurator` understands, and the public
route — initialising the real `LoggingSystem` — reconfigures the JVM that the rest of the suite is
logging through. Mutating global logging in the middle of a suite to test logging is a trade this
session declined.

So the split is explicit: `ConsoleRedactionTest` proves the converters mask (executed), and
`AppenderRedactionTest` proves the real file binds a redacting converter to every conversion word the
real pattern uses (file check). **Either alone is worthless** — converters that mask, bound to
nothing the pattern renders, redact nothing at all.

### 4.2 What the file check found

**Spring Boot's own `defaults.xml` binds `wEx` too**, to its
`ExtendedWhitespaceThrowableProxyConverter`. Our file includes those defaults and then re-binds `wEx`
to `PiiThrowableConverter`. Logback takes the **last** declaration, so the include has to stay above
the rules. Move it below — a tidy-up any reasonable person might make — and every stack trace renders
through Boot's converter, unredacted, **with the file looking exactly as intended**. Pinned by
`the_boot_defaults_are_included_before_the_redaction_rules_that_override_them`.

---

## 5. §11 was asserted on the one path that cannot leak

`abe07db`. `ProblemJsonTest` had asserted the error contract against an unknown endpoint — a `404`
raised by the dispatcher. A `404` never had a stack trace, a SQL statement or a class name to give
away. Every claim in [06-security.md](../06-security.md) §11 is about an exception **escaping a
controller**, and nothing exercised that path.

`ErrorLeakageTest`, six tests, driving real exceptions through the real chain: the catch-all, a
nested cause chain, an unmapped integrity violation carrying the failing statement verbatim, and the
mapped overlap one.

### 5.1 The throwing controller is scoped to one context

It is registered through a nested `@TestConfiguration`, which gives this class its own context cache
key. That is not tidiness. `EndpointCoverageTest` and `PublicSurfaceSweepTest` both read the
mappings of the context **they** run in, so a route that escaped into theirs fails them loudly
rather than widening the public surface quietly. The failure mode is chosen, not avoided.

### 5.2 Two things the plants settled

**Deleting the catch-all does not leak — it lies.** This handoff's author predicted a leak and was
wrong; the captured body says the request returns **`401 Unauthorized`**. The exception escapes the
dispatcher, the servlet error dispatch re-enters at `/error`, and `/error` is not in
`SecurityConfig`'s permitAll list. A server fault reaches the client as an authentication failure.
No disclosure, and a status that sends anyone debugging it in the wrong direction.

**Treating every constraint as the overlap one** turns an unmapped integrity violation into a
cheerful `409` — *"that time was booked while you were deciding"* — for a defect that has nothing
to do with booking. `GlobalExceptionHandler`'s javadoc warns about exactly this in three sentences.
Nothing enforced it until now.

### 5.3 The suite has a floor

Every leakage assertion is a `doesNotContain`, and a `404` satisfies all of them. Six green tests
asserting nothing is the natural end state of a suite like this, reached the day someone renames a
route. `the_throwing_routes_are_really_mapped` is the floor.

---

## 6. Every open item

### 6.1 Committed, not pushed

Four commits on `dev`, which is now **6 ahead of `main`** — two of those are the handoff commits
[the previous session][previous] wrote after its own pull request merged, and which have never been
in a pull request of their own. **CI has seen none of this.** The two jobs a local suite cannot
stand in for — Compose smoke and End-to-end — have not run.

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**.

**G27 is new.** *A gate that reads configuration cannot see a running system that contradicts it.*
`make check-bindings` was green while both databases answered on the LAN address, because the
containers predated the change (§2). Distinct from G26 — there the file is wrong; here the file is
right and the process is stale. Named rather than fixed, because the fix is a decision: a runtime
probe in `make up`, a check against `docker ps`, or an accepted limitation written into the target's
comment. Whichever it is, it is the third bespoke gate in three sessions, and §6.5 is the question
that raises.

**G26 is unchanged at four instances.** §3.3 corrected four copies of one wrong sentence, which is
the shape again — but nothing new checks them, so the gap stands exactly where it did.

### 6.3 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; rate-limit buckets are in memory; no key rotation; no backups;
`AI_RETENTION_ENABLED=false` silently disables the purge.

### 6.4 The E2E stack is down, and this is how to finish it

`make down-e2e` ran. `make up-e2e` then **failed three times**, every time on the network:

| Attempt | Stage | What timed out |
|---|---|---|
| 1 | backend image | the Gradle wrapper distribution, mid-stream |
| 2 | frontend image | `sharp-libvips-linuxmusl-arm64`, at 412 of 414 packages |
| 3 | frontend image | `next` and `swc-linux-arm64-musl`, again at 412 of 414 |

The machine was on a phone hotspot — its LAN address was `172.20.10.3` — and every failure was a
large binary tarball. **The backend layer is cached now**, so on a normal connection `make up-e2e`
has only the frontend install left and should complete in one go.

**The trade, stated plainly.** The stack that was running was wrong in two ways at once: a backend
image older than `V10`, and Postgres published on `0.0.0.0`. Tearing it down ended the exposure
immediately and deferred only the rebuild. Down is a worse state to inherit than up — but it is an
*honest* one, and `make e2e` refuses to run without a stack rather than running against a stale one.

**Do not retry on a metered or tethered link.** Three attempts cost roughly fifteen minutes and
produced nothing.

### 6.5 The question three sessions have now raised

Bespoke gates, one per finding: `check-headers`, `check-ports`, `check-bindings`, `check-docs`. This
session added no gate and found three more things that want one — the stale-process problem (G27),
the four-copies-of-one-sentence problem (§3.3), and the include-order problem (§4.2, which did get a
test). At some point "add another check" stops being the answer. **It is a decision, and it is the
principal's.**

---

## 7. Next steps, in order

1. **Push, and open a pull request.** Six commits are sitting on `dev` unseen by CI, two of them
   inherited. Nothing here has met the Compose smoke test or the E2E job, and §6.4 means the E2E
   job will be the first thing to exercise that topology since the images changed.
2. **Rate limits for every public endpoint** — the last self-contained row in the security block.
   `RateLimitPolicyOrderTest` already pins which policy each path lands on; the untested half is
   *coverage*, and the shape is `EndpointCoverageTest`'s: enumerate the public endpoints from the
   handler mapping and fail when one lands on no policy, so a new public endpoint without a limit
   cannot ship unlimited. No network, no new tooling.
3. **The full-history secret scan.** Needs a scanner installed and probably a CI job. **Not on a
   tethered connection** — see §6.4.
4. **Observability**, all four rows, still untouched — including the *health endpoint covering
   database and mail* row that two handoffs have now deliberately left unticked because the code was
   read and not tested. Ticking it on a reading is T61.
5. **The principal's**: G27's shape (§6.5), credits for [#17]'s remaining arm, [#15]'s title.

---

## 8. Nine traps

**T82 — a pipe swallows the exit code, and a background task reports success for a failed build.**
`make up-e2e 2>&1 | tail -40` exits with `tail`'s status. The first E2E failure was reported as
**exit code 0** with the words `make: *** Error 1` sitting in its own output. `set -o pipefail`, or
read the text rather than the status.

**T83 — `docker compose config` describes the next `up`, not the current one.** The whole of §2.
A container keeps the port binding it was created with, and every configuration-reading gate in this
repo is blind to it.

**T84 — Logback's `<property>` is LOCAL-scoped, so `context.getProperty` returns `null`.** Spring
Boot's `defaults.xml` defines `CONSOLE_LOG_PATTERN` and asking the `LoggerContext` for it gets
nothing. Joran substitutes variables as it sets an element, so the resolved text is readable off a
probe encoder's `getPattern()`.

**T85 — a hand-built `LoggingEvent` has no SLF4J adapter behind it.** The logstash MDC provider
dereferences one and throws `NullPointerException` from inside the encoder, which reads like a
broken encoder. `setMDCPropertyMap` explicitly. Also: `setMessage` throws `IllegalStateException` on
the second call, so build the event once, with the message it is going to carry.

**T86 — writing the secret the obvious way asserts the wrong rule.** `api_key=sk-proj-…` is matched
by `PiiValueMasker`'s key/value rule, which replaces the whole value, so the `sk-` rule never sees
it. The obvious fixture leaves the rule that catches a key pasted into a sentence **untested**, and
the test still goes green. Write the bare form.

**T87 — Boot's `defaults.xml` binds `wEx` too, and the last declaration wins.** §4.2. The include
order in `logback-spring.xml` is load-bearing and looks like formatting.

**T88 — removing an exception handler changes the status, not just the body.** Deleting the
catch-all yields `401`, because the servlet error dispatch is not in the permitAll list. Predicting
what a plant will do is not the same as running it; this one was predicted wrong here, and the
captured body is what corrected it.

**T89 — a suite of `doesNotContain` assertions is satisfied by a `404`.** Any test whose assertions
are all negative needs one positive assertion that the thing under test is reachable at all, or it
becomes vacuous the day a route is renamed and stays green forever.

**T90 — `@Profile("prod")` is why a control can ship unexecuted for ten phases.** The annotation
that confines a bean to production also confines it out of the test suite. Every
profile-restricted bean in this repo is a candidate for the same audit;
`ApplicationContextRunner.withUserConfiguration` honours `@Profile`, so testing one costs a few
lines and no containers.

---

## 9. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's three classes alone. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.config.SecretsGuardTest' --tests 'dev.reception.common.logging.AppenderRedactionTest' --tests 'dev.reception.common.error.ErrorLeakageTest'
```

```bash
# The published bindings, all three topologies. Green does not mean the running stack agrees (G27).
make check-bindings
```

```bash
# What is actually published right now — the check G27 is about.
docker ps --format '{{.Names}}\t{{.Ports}}'
```

```bash
# Finish §6.4. Only the frontend install is left; do not attempt this on a tethered link.
make up-e2e
```

---

## 10. Confidence

**High** on the three test classes. Each was shown red against plants that were reverted and
`cmp`-verified byte-identical — six, five and six respectively — and the full suite is green at 961
tests with the plants removed.

**High** on §2, which is the only claim here established by measurement against a live control
rather than by reading.

**Not established:** anything CI does. Nothing was pushed, so the Compose smoke test and the E2E job
have not run against these changes. The E2E job is the one that matters, because §6.4 left its
topology unbuilt.

**Corrected in flight, and recorded because the correction is the useful part:** the claim that
Spring Boot's default error path leaks internals when the catch-all is removed. It does not. It
returns `401` (T88).
