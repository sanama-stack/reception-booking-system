# Session handoff — 2026-09-13 — the controls that kept passing

> **Purpose.** One carried feature and four sections of the [06-security.md](../06-security.md)
> walk. Three of those sections described a control **no file implemented**, and a fourth described
> one the suite is **structurally unable to check**.
>
> **§6 is the finding.** *"Manage tokens are excluded from access logs"* was true of nothing. Caddy
> is the only access log in the system and it wrote every Manage Link token to stdout verbatim — a
> bearer capability for one appointment, good until 24 hours after it ends. Measured against the
> running container, §2.
>
> **But the part worth reading is §7.** [T89][prev] arrived three more times, and twice it beat me:
> a control satisfied by a **stale log line from a previous container**, a derivation that reported
> **all 59 fields unbounded and none bounded**, and a walk that silently dropped **the one record
> the test was written for**. Each of those passed a plant before the control was strengthened. The
> rule that comes out of it is narrower than "write a positive control": **the control has to fail
> when the derivation stops seeing, not when it sees something wrong.**
>
> **Committed, not pushed.** Five commits on `dev`, now **14 ahead of `origin/main`** and 12 ahead
> of `origin/dev`. `main` untouched. 999 backend tests, 0 failed. Phase 11 at **61 of 72**.

[prev]: ./2026-09-12-the-empty-set-that-passed.md
[previous]: ./2026-09-12-the-empty-set-that-passed.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. No pull request was opened — **the principal said explicitly to skip push and PR this session** |
| `dev` | **14 ahead of `origin/main`**, 12 ahead of `origin/dev`. The work ends at **`d511544`** |
| CI | **has still seen none of it.** Four sessions now |
| Backend | **999 tests, 0 failed, 112 classes** — was 985 / 109. Three new classes |
| Frontend | **82 tests, 0 failed** — was 80. Two new, in the analytics screen |
| Migrations | **`V10`**, unchanged. New ADR: none — [ADR-0010](../adr/0010-revenue-reports-one-currency-and-names-the-remainder.md) was *updated*, not added |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit was spent |
| Gates | `make check-docs` green — 291 links, 279 `§N` references. **`make check-access-log` is new** and runs in CI's compose smoke job |
| Phase 11 | **61 ticked, 11 open.** Two ticked here; the security-walk row is deliberately still open |
| E2E stack | **still down.** Still the hotspot, still `172.20.10.3`. Not attempted |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

The five commits:

| | |
|---|---|
| `8646127` | Name the revenue remainder, and prove the empty set cannot pass |
| `beb83b0` | Take the Manage Link tokens out of the access log |
| `61e9b8c` | Assert the cookie attribute the suite runs at the wrong setting |
| `8f16cc9` | Build the CSRF layer §13 claimed and two thirds of the surface had |
| `d511544` | Bound the three public strings §7 said were already bounded |

Production code changed in nine files: three in `analytics`, `ErrorCode`, the new
`JsonOnlyWriteFilter`, `PublicRequests`, the `Caddyfile`, the `Makefile` and `ci.yml`.

---

## 2. The access log was a file of live capability tokens

[06-security.md](../06-security.md) §6: *"Manage tokens are excluded from access logs and never
appear in an error message."* Written in the design phase. Implemented by no file.

A Manage Link is a bearer capability — whoever holds the token can read, reschedule and cancel that
appointment until 24 hours after it ends — and it travels **in a URL**:

- as a **path segment** in `/manage/{token}`, the page the Customer opens, built by
  `NotificationEnqueuer#manageUrlFor`;
- as a **query parameter** on `/api/public/appointments/manage` and `…/manage/availability`, the two
  calls that page makes.

Caddy's `log` block was `format json` with no filter, so all three went to stdout verbatim. Measured
rather than inferred — a probe against the running container returned:

```
"uri":"/manage/PROBE-7f3a9c2e1b4d6a8f-NOT-A-REAL-TOKEN"
```

This is the third time the walk has found this exact shape (§13's headers in phase 11, §12's
database port, now §6), and it survives for the same reason each time: **the natural way to check a
config claim is to read the config**, and a test written that way certifies the absence.

### 2.1 The error logger, which is a different logger

The first fix filtered the site's `log` block and looked complete. It was not.

A site's `log` directive configures `http.log.access.logN` **only**. Caddy writes
`http.log.error.logN` from the `reverse_proxy` handler on any transport error — refused connection,
timeout, 502 — and that line carries the whole request including its `uri`. So the access-log-only
filter passed against a healthy stack and **leaked every token the moment the backend was down**,
which is precisely when somebody is reading the logs.

Found only because the probe ran with the backend stopped. The fix needs a global logger *and* an
exclusion:

```
log default { exclude http.log.error }
log errors  { include http.log.error; output stdout; format filter { … } }
```

The `exclude` is load-bearing. Caddy sends an entry to **every** logger whose rules accept it, so
adding a second logger *adds* a line rather than replacing one — without the exclusion the
unfiltered copy is still written.

### 2.2 What was never affected

The backend. `HttpServletRequest#getRequestURI()` excludes the query string, so the `instance` in
every problem body and every path the application logs stops at `/public/appointments/manage`. That
is **load-bearing and accidental**: `getRequestURL()` plus `getQueryString()` would leak the lot, and
nothing says so at the call sites. Now recorded in §6.

---

## 3. `make check-access-log`, and the control a stale line answered

No JVM test can see a reverse proxy's log, so this is a live probe: request a URL carrying a
sentinel, read `docker compose logs caddy`, assert the sentinel is absent.

**That assertion is worth nothing on its own.** It passes when the log is empty, when the service
name is wrong, when the container was never restarted, and when a filter redacts the entire `uri`
field. So the check also asserts a control request arrives **with its uri intact**.

The first version of that control was defective and a plant proved it: a filter rewriting every
`uri` to `REDACTED` **passed**. The control grepped for a fixed `/api/health`, and `docker compose
logs --tail` spans container restarts — a line from an earlier run satisfied it while nothing from
that run did.

Both sentinels now carry `$$`, so no stale line can answer either. Shown red three ways afterwards:
access filter removed, error logger unfiltered, whole `uri` redacted.

> This is a general hazard for any check that reads container logs, not a one-off. `--tail` is not
> scoped to your probe. If a future check greps a log, **make what it greps for unique per run.**

---

## 4. The cookie attribute the suite runs at the wrong setting

§2: *"Both delivered as `httpOnly; SameSite=Lax; Path=/`, `Secure` outside the `local` profile."*

`RegistrationTest` asserts the first three against a real HTTP response. It **cannot** assert the
fourth: the whole suite runs the `test` profile, where `app.security.cookies.secure` is deliberately
`false` because the origin is plain http. Every existing assertion about these cookies was therefore
made at the one setting where the attribute is supposed to be absent, and nothing had read the value
that ships.

`SecretsGuard`'s shape exactly — a control whose only live configuration is one no test starts.
`application-prod.yml` set to `secure: false` left the entire suite green.

`AuthCookieSecurityTest` (7 tests) reads `application-prod.yml` through
`ConfigDataApplicationContextInitializer` rather than by regex, so an overriding property elsewhere
would still fail it. Shown red three ways: the prod file flipped, `.secure(...)` deleted from the
builder, the `@Value` default flipped.

**A premise I asserted and had to correct.** I first wrote *"with no profile at all the default is
`Secure`"*. It failed. `application.yml` sets `spring.profiles.default: local`, so an empty profile
list **is** `local` and resolves to a plain cookie — the `@Value` fallback is unreachable through the
config files as they stand. What the fallback actually governs is a profile nobody has written a file
for yet, so the test probes `staging`. Both facts are in §2 now, because the one that reads like the
safe default is not the one that is.

---

## 5. A CSRF layer that covered two thirds of the write surface

§13 said state-changing requests *"additionally require `Content-Type: application/json`, which
blocks the form-post CSRF shape."*

True wherever a `@RequestBody` exists — Spring answers `415` because no converter turns a form body
into a DTO. **False at the ten endpoints that take no body**, where there is nothing to convert and
so nothing to refuse. Measured before writing anything:

```
POST /auth/logout   application/x-www-form-urlencoded  -> 204 NO_CONTENT
POST /auth/login    application/x-www-form-urlencoded  -> 415 UNSUPPORTED_MEDIA_TYPE
```

**Not an exploit**, because `SameSite=Lax` stops the browser attaching cookies to a cross-site form
post. That is also why it mattered: two layers were documented, one existed, and the absent one is
what a reader counts on the day `SameSite` is relaxed for something.

`JsonOnlyWriteFilter` refuses the **three content types an HTML form's `enctype` can produce** rather
than requiring JSON positively — a request with no `Content-Type` cannot have come from a form
either, and body-less `POST`s from legitimate clients often send none. It runs before authentication,
so a forged request is refused for its shape and not for the credentials it lacks; a `401` would mean
the same request from a signed-in victim went through.

`FormPostRejectionTest` derives all **36** writes from `RequestMappingHandlerMapping`. Shown red four
ways; the fourth is the control — a filter refusing *every* content type passes "nothing answered
other than 415" and is caught only by sending the same endpoints JSON.

§13 is **reworded**, because "require JSON" is not what the control does and is the change a future
reader would otherwise make.

---

## 6. Three unbounded strings on the unauthenticated surface

§7: *"Every string field has a maximum length. Unbounded text is a denial-of-service vector."*
Nothing checked it, and **no global request-size cap exists to fall back on** —
`max-http-form-post-size` does not apply to a JSON body and nothing else sets one, so the per-field
bound is the only bound there is.

`RequestFieldLengthTest` derives the request bodies from `RequestMappingHandlerMapping` and walks
nested records and collection elements. It found `PublicRequests.Authority`: `manageToken`,
`confirmationCode` and `phone` as bare strings on `POST /public/appointments/{id}/cancel` and
`/reschedule`, **both unauthenticated**.

Each has a bounded twin a few lines away — `Lookup` bounds the same code at 16 and the same number at
30, `StartSession` bounds the same token at 500. They now carry those.

**The cause is legible in the record's own comment.** It explains why none of the three is
`@NotBlank`: *"exactly one of these" is not expressible as a field annotation*. That argument is
correct, it is about **presence**, and it quietly took the length bound with it. Length was never
part of it.

### 6.1 Two controls that had to be taught to fail

This is the section worth reading twice. Two of the three plants **passed** against the test as first
written, and both failures are of the same kind: the derivation stopped *seeing*, and every assertion
about an empty set went green.

**`@Size` is not on the record component.** Its `@Target` has no `RECORD_COMPONENT`, so javac
propagates the annotation to the field, the accessor and the constructor parameter and the component
carries nothing. This was not hypothetical — my first derivation read
`RecordComponent.getAnnotation(Size.class)` and reported **all 59 fields unbounded and 0 bounded**,
including several whose `@Size` is three lines up in the source. I noticed only because I printed the
bounded set too. **Written the other way round — "is this one bounded?" — the identical bug would
have reported every field bounded and passed silently forever.**

**A walk that stops at nested records** drops `Authority`, which is the record the class exists to
catch, and then reports nothing unbounded.

Both are closed by naming a **nested** field in the *bounded* set:
`"CancelAppointment.authority.confirmationCode (max 16)"`. That single line is what makes the control
fail when the derivation goes blind.

> **The rule, stated more precisely than [the previous handoff][previous] managed.** A positive
> control is not "assert something is non-empty". It is: **assert the specific thing whose absence
> would make the main assertion vacuous.** For a derived list that means naming an element that only
> a fully working derivation can produce — the deepest one, not the easiest one.

---

## 7. The revenue remainder (ADR-0010), carried from the previous handoff

The one non-security item, and the last substantial pair that needed neither the E2E stack nor a real
connection. `revenue` keeps its single figure and gains `excluded` beside it: one sum per other
currency among the same COMPLETED appointments, `[]` when there are none, never summed.

The test that asserted the old behaviour was **deleted rather than patched** — *"after a currency
change, revenue reports the new currency only — and says so by omission"* was explicit that the
remainder was a decision for the principal, and the decision had since been made, so its premise had
expired.

**A trap worth carrying:** a Service stamps the Business's currency **at creation and never re-stamps
it**, so `PATCH /business {currency}` alone leaves every future appointment in the old currency. A
test that only switches the business can never produce a second currency and would pass against an
implementation that does nothing. The test creates a Service *after* the switch. Recorded in the ADR.

Four plants. The first is the familiar one: a derivation returning nothing is caught **only** by the
assertion on `excluded[*].amount`, because "the list is empty" is what the no-remainder test asserts
on purpose.

---

## 8. Every open item

### 8.1 Committed, not pushed

Fourteen commits on `dev` ahead of `origin/main`, twelve ahead of `origin/dev`. **CI has seen none of
it**, across four sessions now. Compose smoke and End-to-end are the two jobs a local suite cannot
stand in for, and **this session added a step to the first of them** (`check-access-log`) that has
consequently never run in CI.

The principal explicitly deferred the push this session. It is still the oldest open item and it is
still growing.

### 8.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G28**.

**G29 is new.** *A check that reads container logs can be answered by a stale line.* Fixed in
`check-access-log` by making both sentinels unique per run (§3), but nothing stops the next such
check from being written the naive way. Named rather than gated.

**G27 is unchanged and this session is more evidence for it.** `check-bindings` reads
`docker compose config` and cannot see a running system that contradicts it. `check-access-log` is
the opposite instrument — it reads what the running container actually wrote — and it is the one that
found something.

### 8.3 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; rate-limit buckets are in memory; no key rotation; no backups;
`AI_RETENTION_ENABLED=false` silently disables the purge; the logged model name is the configured one
rather than the provider's.

**New to this list:** the Manage Link token is still *in a URL*. §2's filters take it out of our own
logs, and CSP `default-src 'self'` means the page loads nothing third-party that could carry a
`Referer` — but browser history, a user's own screenshot and any future proxy still see it. The
deeper fix is the token's location, which is phase 08's design and was not reopened.

### 8.4 The E2E stack is still down

Unchanged, and not attempted. `ipconfig getifaddr en0` still reports `172.20.10.3`. Nothing in this
session needed it and nothing in it was verified by it — with one exception worth naming:
`check-access-log` was developed against the **`make up`** topology, where postgres, mailpit and
caddy run and the applications do not. That is why the backend-down probe happened at all, and it is
also why the check has never been seen against `up-all`.

### 8.5 The question, still the principal's

`check-headers`, `check-ports`, `check-bindings`, `check-docs`, and now **`check-access-log`** — the
fifth, which [the previous handoff][previous] §8.5 warned about.

It is worth separating the two kinds, because this session used both. `check-bindings`, `check-ports`
and `check-docs` **read configuration**; G27 is about exactly their weakness. `check-headers` and
`check-access-log` **probe the running system**, and they are the ones that have found real defects.
The §7 answer — *derive the list instead of gating it* — was also used here twice
(`FormPostRejectionTest`, `RequestFieldLengthTest`) and both found something.

So the shape that is accumulating is not "too many gates". It is: **derive it in a test where you
can; probe the running system where you cannot; read configuration only when neither is possible.**
Still the principal's to rule on, but this session is three data points for it.

---

## 9. Next steps, in order

1. **Push, and open a pull request.** Fourteen commits, four sessions, no CI — and a new compose-smoke
   step that has never executed there. Deferred by the principal once; worth re-asking.
2. **Finish the [06-security.md](../06-security.md) walk.** Remaining: **§1**, **§3**, **§4**, **§8**,
   **§14**, **§15**, and **§13's CORS claim**. See §10 below for where to start.
3. **The full-history secret scan.** Needs a scanner installed. **Not on a tethered connection.**
4. **The end-of-phase gates** — clean clone, `make up && make seed`, the demo script, the concurrency
   test, the Definition of Done sweep. All want the E2E stack and a real connection.
5. **The principal's**: G28, G29, and §8.5's shape; credits for [#17]'s remaining arm; [#15]'s title.

---

## 10. Where the rest of the walk should start

Written from four sections of pattern rather than guessed. The three that produced findings all had
the same tell: **a specific, falsifiable sentence with no file behind it.**

- **§13's CORS claim** is the cheapest and finishes the section. *"No CORS configuration exists
  anywhere"* is asserted by nothing; `.cors(cors -> cors.disable())` in `SecurityConfig` could become
  permissive with all 999 tests green. One test, and the derivation already exists in
  `FormPostRejectionTest`.
- **§3 and §4** claim *"a dedicated suite enumerates every tenant-scoped endpoint"*. Check whether
  that enumeration is **derived or another typed list** — that is the §5 failure verbatim, and
  `EndpointCatalogue.java` in the test tree is a suspicious name.
- **§14** claims authentication events are logged *"with user id and IP"*. The previous session found
  the AI-call log line did not exist at all; this one is the same kind of claim.
- **§15** is the accepted-risks table. It cannot be "verified" — but it can be checked for the
  opposite defect: a risk that has since been closed and still reads as open, or one that is real and
  missing. It gained a row in phase 11 for exactly that reason.
- **§1's** threat-model table maps assets to *"primary control"*. Every control named there should now
  resolve to a named test. The ones that do not are the next §6.

---

## 11. Traps

- **T90 — a log-reading check can be answered by a stale line.** `docker compose logs --tail` spans
  container restarts. Make the sentinel unique per run, and make the *control* sentinel unique too.
  §3. This one passed a plant before it was fixed.
- **T91 — `@Size` is not readable from a `RecordComponent`.** Its `@Target` has no
  `RECORD_COMPONENT`; javac puts it on the field, the accessor and the constructor parameter. Reading
  the component returns `null` for every field in this application. §6.1.
- **T92 — Caddy's error logger is not the access logger.** A site's `log` block filters
  `http.log.access` only. `http.log.error` carries the whole request and fires when an upstream is
  down. Filtering one and not the other looks complete against a healthy stack. §2.1.
- **T93 — a second Caddy logger adds a line, it does not replace one.** `log default { exclude … }`
  is required alongside any named logger, or the unfiltered copy is still written. §2.1.
- **T94 — `spring.profiles.default: local`.** "No profile active" means `local` here, not the
  `@Value` fallback. A test probing the fallback must name a profile that has no file. §4.
- **T95 — a positive control must name the deepest element.** Naming only top-level ones let a walk
  that stopped at nested records pass. §6.1.
- Carried and re-confirmed: **T89** (assert the set could have been non-empty), **T69** (`JAVA_HOME`
  is not optional), **T70** (`--rerun-tasks`, or a restored plant reports a cached pass).

---

## 12. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's three new classes alone. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.auth.AuthCookieSecurityTest' --tests 'dev.reception.common.web.FormPostRejectionTest' --tests 'dev.reception.common.web.RequestFieldLengthTest'
```

```bash
# The new gate. Needs Caddy running; `make up` is enough, and the applications need not be.
make check-access-log
```

```bash
# Re-measure §2 by hand. The uri must come back REDACTED, and /api/health must not.
curl -s -o /dev/null "http://localhost:9080/manage/A-FAKE-TOKEN" && docker compose logs caddy --tail 1 --no-color
```

---

## 13. Confidence

**High** on §2, §5 and §6's findings: each was measured against something running or derived from a
framework API, each was shown red with plants that were reverted and `cmp`-verified, and each has a
control that fails when the derivation goes blind.

**High** on the revenue remainder. Offline, self-contained, four plants.

**Medium** on `check-access-log` in CI. It is correct against the `make up` topology, which is where
it was developed and plant-tested. **It has never run against `up-all`**, and it has never run in CI,
because nothing has been pushed. The failure mode if `up-all` names the caddy service differently is
loud — the check exits on an empty log — but it is untested there.

**Low, and deliberately so, on the walk being finished.** Four sections of fifteen. Three of the four
walked contained a control that did not exist, which is not a reassuring base rate for the eleven
that remain. §10 says where to start and why.
