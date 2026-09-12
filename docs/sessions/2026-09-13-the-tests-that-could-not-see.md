# Session handoff — 2026-09-13 — the tests that could not see

> **Purpose.** Three sections of the [06-security.md](../06-security.md) walk, continuing from
> [the previous session][prev]: **§13's CORS claim**, **§3 and §4**, and **§14**. All three were
> asserted by nothing, and §14's was true of nothing.
>
> **§14 is the finding.** *"Authentication events (login, refresh, revocation) are logged with user
> id and IP."* `AuthService` contained **no log statement at all**. The only authentication line in
> the application was the replay warning in `RefreshTokenFamilyRevoker`, carrying a user id and **no
> address** — so the audit trail began at the one event an attacker triggers deliberately and could
> not say where it came from.
>
> **But the part worth reading is §6.** [T89][prev] arrived three more times and **beat me every
> time**. A preflight assertion passed against an application granting every origin everything with
> credentials. A tenancy sweep reported green over the entire authenticated surface while seeing
> none of it. A credential sweep passed a password written straight into a log line. In all three
> the code was fine and **the instrument was the defect** — which is a different failure from the
> one the last two sessions catalogued, and the reason for this handoff's title.
>
> **Committed, not pushed.** Three commits on `dev`, now **18 ahead of `origin/main`** and 16 ahead
> of `origin/dev`. `main` untouched. 1022 backend tests, 0 failed. Phase 11 still at **61 of 72**.

[prev]: ./2026-09-13-the-controls-that-kept-passing.md
[previous]: ./2026-09-13-the-controls-that-kept-passing.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **18 ahead of `origin/main`**, 16 ahead of `origin/dev`. The work ends at **`ab1f588`** |
| CI | **has still seen none of it.** Five sessions now |
| Backend | **1022 tests, 0 failed, 114 classes** — was 999 / 112. Two new classes |
| Frontend | **82 tests, 0 failed**, untouched |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit was spent |
| Gates | `make check-docs` green. No new gate this session — deliberately, see §7 |
| Phase 11 | **61 ticked, 11 open.** The security-walk row is still open: §1, §8 and §15 remain |
| E2E stack | **still down.** Not attempted; nothing here needed it |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

The three commits:

| | |
|---|---|
| `cdcf773` | Assert the CORS claim §13 made and nothing checked |
| `1b0c0cb` | Probe the fifteen endpoints §4 classified and nothing swept |
| `ab1f588` | Log the authentication events §14 said were already logged |

Production code changed in **four files, all in `auth`**, and only for §14. §13 and §3/§4 changed
test code only — which is itself the result, since both claims turned out to be true of the system
and false of the suite.

---

## 2. §13's CORS claim — true, and asserted by nothing

*"Single origin, so no CORS configuration exists — the safest configuration is the absent one."*

`.cors(cors -> cors.disable())` is **one line** that could become `Customizer.withDefaults()` with a
permissive source beside it, and a single `@CrossOrigin` on one controller would have left 999 tests
green. An absent configuration is the one nothing defends, because there is no file to review.

The sentence makes two promises and they fail differently:

- **Nothing is granted** is probed across all 65 mapped endpoints in both shapes — a simple
  cross-origin request, where the response either carries `Access-Control-Allow-Origin` or the
  caller cannot read it, and a preflight, which never reaches a handler and is refused `403` by
  `DefaultCorsProcessor`.
- **No configuration exists** is stronger and is the sentence actually written. A `@CrossOrigin`
  naming one partner origin grants a hostile origin nothing, so it is invisible to any probe. It is
  read instead off the two objects that can hold it — `RequestMappingHandlerMapping`'s configuration
  source, which is what `WebMvcConfigurer#addCorsMappings` populates, and the annotation itself.

Five plants. Three were ordinary. The two that were not are §6.1 and §6.2.

---

## 3. §3 and §4 — the catalogue was right, and its guarantee stopped short

[The previous handoff][previous] §10 suspected `EndpointCatalogue` of being another typed list, and
therefore §5's failure again. **It is the opposite** — a typed *judgement* reconciled against
Spring's routing table in both directions, so a new controller method breaks the build until
somebody classifies it. That design is right and was left alone.

The defect is one level in. **Classified is not probed.** The catalogue has six classifications;
`OWNER_RESOURCE_ID` is swept, the two public kinds are swept *and* reconciled, `NO_TENANT` carries an
asserted reason — and `OWNER_COLLECTION` and `OWNER_SINGLETON`, **fifteen of sixty-five endpoints**,
were referenced by **no test in the tree**. Each carried a written probe description — *"A's response
must contain nothing of B's"* — that nothing ran. `EndpointCoverageTest` guarantees every endpoint is
classified. It never guaranteed a classification does anything.

### 3.1 `GET /availability` was misclassified, which is worse than unclassified

It has taken a required `serviceId` and two optional ids **in the query string** since phase 05, and
was catalogued as a collection that *"takes no id"* with `Borrowed.NONE` — so the id sweep skipped it
by construction. A written judgement that closes the question wrongly is the sentence that stops the
next person looking. New `Isolation` constant `OWNER_QUERY_ID`, probed with B's real id.

**The reads behind it were correct all along. Nothing had ever asked.**

### 3.2 The plant that proved §4's own claim

Unscoping `ServiceCatalogService#read` left the availability probe **green**, because
`AssignmentService#employeesFor` runs an independent tenant check on the same id. It went red only
when **both** were removed.

§4 says *"four layers, each independently sufficient to catch a bug in the others."* That is normally
the kind of sentence a walk cannot verify. Here it was demonstrated by accident, and the lesson
generalises: **a plant that stays green can mean the system is right rather than the test wrong**,
and the two are told apart by removing the next layer rather than by weakening the probe.

---

## 4. §14 — an audit trail that began at the attack

`AuthService` contained no log statement. Not a weak one, not a wrong one: none. The only
authentication line in the application was `RefreshTokenFamilyRevoker`'s replay warning, which
carried a user id and no address.

**The data was never missing.** `RequestFingerprint` has carried the user agent and the peer address
since phase 02 and the refresh token row stores both. It simply never reached a log. `revoke()`
returned `void`, so logout could not name whom it was about; it now returns the user id, empty for an
unknown token — which is a successful logout and not an event about anybody.

**The user id and not the email.** The id is stable and is what every other record joins on; the
address is a customer-grade identifier that `PiiValueMasker` redacts out of log output anyway, so
logging it would produce a line that names nobody. The IP is deliberately **not** masked: an
authentication record without an origin cannot answer the question it exists for. Worth knowing that
the masker redacts emails and phone numbers and **not** IPs, so this needed no exemption.

**Registration is logged although §14 named three events**, because registration is where a session
first exists and omitting it puts the hole in the trail exactly at the moment an account is created.

The event list is **derived** from the `/auth` surface off `RequestMappingHandlerMapping`, and every
write on it must be either a recorded event or an exemption carrying a reason — `EndpointCatalogue`'s
classify-or-fail, one section over.

> **Not done, and it is a question rather than an omission: failed logins are not logged.** §14 names
> login, refresh and revocation, and a failed login is none of the three. It is also the event a
> brute-force attempt consists of. Logging it means writing a line for every wrong password, keyed on
> an address, and the identifier available is the email — the field the masker redacts. **The
> principal's call**, and G30.

---

## 5. Every plant, and the three that beat me

Fifteen plants across the three sections. These are the ones worth carrying.

### 5.1 The CORS probe that went blind, against an application granting everything

`Origin` and `Access-Control-Request-Method` are both on `HttpURLConnection`'s restricted-header
list. `TestRestTemplate` falls back to it when no HTTP client is on the test classpath. Planted
deliberately — a permissive CORS configuration wired into the filter chain **and** a client that
could not send the headers — the preflight assertion **passed**, against an application granting
every origin everything with credentials.

The control that catches it **is a pair and has to be**: the preflight must vary on `Origin` and a
bare `OPTIONS` to the same path must not. Neither half holds alone —

- *"the preflight varies on Origin"* is also true of a blind probe against an application that **has**
  a configuration source, because the source alone makes the processor run;
- *"the bare OPTIONS does not vary on Origin"* is also true of a blind probe against this application
  as it stands, because then neither request is a preflight.

### 5.2 The sweep that reported green over a surface it could not see

The first version of §13's configuration check was a behavioural sweep for `Vary: Origin`. A
`@CrossOrigin` planted on `AnalyticsController` changed **no response at all** — Spring Security
answers `401` in the filter chain and MVC's CORS interceptor never runs. The same annotation on
`HealthController` was caught at once.

A sweep of that shape covers the public surface and **silently covers nothing else**. Replaced by
reading the configuration, which no filter can hide. This is the generalisation of §6's lesson from
[the previous handoff][previous]: a probe is blind wherever a layer above it answers first, and the
authenticated surface is most of this application.

### 5.3 The credential sweep that did not drive the endpoint receiving the credential

A password written straight into a log line from `login()` **passed**. The sweep drove register,
refresh and logout — not login, the one endpoint that receives a password. It now drives every write
on the surface, and a reconciliation holds it there.

### 5.4 And one that is the opposite

An empty collection fails the **control**, not the assertion. *"Contains none of Datos Auto's ids"* is
equally true of an empty list, of a `400` for a missing parameter, and of a body that failed to
serialise. Every collection probe therefore creates a row of Aria's own first and requires **that**
id back. Planted by returning `List.of()` from the services list: the control fired, with its own
message, exactly as designed. **That one worked because the last two sessions had already paid for
it.**

---

## 6. Every open item

### 6.1 Committed, not pushed

**Eighteen commits, five sessions, no CI.** Compose smoke and End-to-end are the two jobs a local
suite cannot stand in for, and `check-access-log` — added two sessions ago to the first of them — has
still never executed there.

This is the oldest open item, it is still growing, and it is now the largest single risk in the
project: eighteen commits is no longer a change set anybody can review after a red pipeline.

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G28**, **G29**.

**G30 is new.** *Failed logins are not logged.* §4's blockquote above. A decision, not an oversight.

**G31 is new.** *Nothing asserts that a classification is exercised.* Fixed for the two kinds that
needed it, and the general property — "every branch of a catalogue is driven by something" — is still
held by convention rather than by a test. The `Isolation` enum could gain a seventh constant tomorrow
with no sweep behind it, and only a reviewer would notice.

### 6.3 Carried

Unchanged from [the previous handoff][previous] §8.3, including the Manage Link token still being in
a URL.

### 6.4 The E2E stack is still down

Unchanged and not attempted. Nothing this session needed it.

---

## 7. No new gate this session, deliberately

[The previous handoff][previous] §8.5 asked whether five `make check-*` targets is too many, and
offered a shape: **derive it in a test where you can; probe the running system where you cannot; read
configuration only when neither is possible.**

All three sections this session were answerable in the first category, and all three are tests. The
shape held. It is still the principal's to rule on, but it now has six data points and no
counter-example.

---

## 8. Next steps, in order

1. **Push, and open a pull request.** Eighteen commits, five sessions, no CI. Deferred once by the
   principal; it has doubled since.
2. **Finish the walk.** Remaining: **§1**, **§8**, **§15**. See §9.
3. **The full-history secret scan.** Needs a scanner installed. **Not on a tethered connection.**
4. **The end-of-phase gates** — clean clone, `make up && make seed`, the demo script, the concurrency
   test, the Definition of Done sweep. All want the E2E stack and a real connection.
5. **The principal's**: G30 (failed logins), G31, G28, G29, §7's shape; credits for [#17]'s remaining
   arm; [#15]'s title.

---

## 9. Where the rest of the walk should start

Six sections walked now, across two sessions, and the base rate has not improved: **five of the six
contained a claim no file implemented or no test could check.**

- **§8** is unwalked and unexamined — start here, because it is the only remaining section whose
  content is still unknown.
- **§1's** threat-model table maps assets to *"primary control"*. Every control named there should
  now resolve to a named test, and after three sessions most of them do. The ones that do not are the
  next §14. This is the cheapest remaining section and the one most likely to find something, because
  it is an index into everything else.
- **§15** is the accepted-risks table and cannot be "verified" — but it can be checked for the
  opposite defect: a risk since closed that still reads as open, or one that is real and missing. Two
  candidates already: the CSRF layer §13 documented and did not have is now built, and the Manage
  Link token's location is a real risk that §15 does not list.

---

## 10. Traps

- **T99 — `Origin` and `Access-Control-Request-Method` are restricted headers for
  `HttpURLConnection`.** A test client built on it drops them silently, and every CORS assertion goes
  green against an application that grants everything. §5.1. This one beat me.
- **T100 — a "no CORS" control must be a pair.** The preflight varies on `Origin` and the bare
  `OPTIONS` does not. Either half alone is satisfied by a blind probe. §5.1.
- **T101 — a behavioural sweep is blind wherever a filter answers first.** Spring Security returns
  `401` before MVC's interceptors run, so anything asserted on an authenticated endpoint's *response*
  sees nothing of MVC. Read the configuration instead. §5.2. This one beat me.
- **T102 — classified is not probed.** A catalogue that fails for an unclassified endpoint still
  passes for a classification nothing drives, and a `@TestFactory` that yields nothing passes. Every
  branch needs a registry check. §3.
- **T103 — a credential sweep must drive the endpoint that receives the credential.** §5.3. This one
  beat me.
- **T104 — a plant that stays green may mean the system is right.** Two independent tenant checks on
  the same id meant one had to be removed before the probe moved. Remove the next layer before
  weakening the probe. §3.2.
- Carried and re-confirmed: **T89** and **T95** (name the deepest element), **T96** (`kv` needs a
  placeholder in the message or it vanishes — respected by every new log line here), **T98** (detach
  the appender in `@AfterEach`), **T69**, **T70**.

---

## 11. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's two new classes and the extended sweep. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.web.NoCorsConfigurationTest' --tests 'dev.reception.auth.AuthEventLoggingTest' --tests 'dev.reception.tenancy.TenantIsolationSweepTest'
```

---

## 12. Confidence

**High** on §14's finding and its fix. The absence was total and trivially verifiable — no logger in
the package — and the fix was shown red five ways, including one plant that found a hole in the test
rather than the code.

**High** on §3/§4. No production code changed, which is the strongest possible statement about the
finding: the system was already correct and fifteen endpoints' worth of written judgement had never
been exercised.

**High** on §13's conclusion and **medium on its coverage**. The probes and the configuration read
together cover MVC-level and security-level CORS. They say nothing about Caddy, which could add
`Access-Control-Allow-Origin` at the edge tomorrow and no JVM test would see it. `make check-headers`
probes the running origin and does **not** assert the absence of that header — a one-line addition,
not made this session because it belongs with the next compose-smoke change and CI has seen none of
the last five.

**Low, and deliberately so, on the walk being finished.** Six of fifteen sections. Five of the six
contained something. §9 says where to start and why.
