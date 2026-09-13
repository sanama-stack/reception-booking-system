# 06 — Security

Scope: practical controls for an MVP that handles real customer contact details and real money-shaped data.
Not a compliance programme.

## 1. Threat model

| Asset | Threat | Primary control |
|---|---|---|
| Tenant data | Business A reads or writes Business B's data | Server-derived tenancy + composite FKs |
| Customer PII | Enumeration of a business's customers | Code + phone required; aggressive rate limits |
| Appointments | A stranger cancels someone's appointment | Confirmation Code / Manage Link authority |
| LLM budget | Cost exhaustion by an abusive visitor | Rate limits, ceilings, per-business daily cap |
| Credentials | Theft via XSS or logs | httpOnly cookies, redaction, BCrypt |
| Availability | Public endpoint flooding | Rate limiting by IP |
| Integrity | Double booking under race | Database exclusion constraint |

**Every control named above now resolves to a test that runs**, checked row by row in phase 11. This
table is an index into the rest of this document, so a row resolving to nothing is a control the
other sections are entitled to assume and nobody has to supply.

| Row | What asserts it |
|---|---|
| Tenant data | [`TenantIsolationSweepTest`](../backend/src/test/java/dev/reception/tenancy/TenantIsolationSweepTest.java), [`EndpointCoverageTest`](../backend/src/test/java/dev/reception/tenancy/EndpointCoverageTest.java), [`SmuggledBusinessIdTest`](../backend/src/test/java/dev/reception/tenancy/SmuggledBusinessIdTest.java) |
| Customer PII | [`PublicAppointmentAuthorityTest`](../backend/src/test/java/dev/reception/publicapi/PublicAppointmentAuthorityTest.java) — the code alone and the phone alone are each refused — and the five-an-hour lookup budget in [`RateLimitTest`](../backend/src/test/java/dev/reception/common/ratelimit/RateLimitTest.java) |
| Appointments | [`PublicAppointmentAuthorityTest`](../backend/src/test/java/dev/reception/publicapi/PublicAppointmentAuthorityTest.java), including a Manage Link token refused on another appointment's path |
| LLM budget | [`ConversationLoopTest`](../backend/src/test/java/dev/reception/ai/application/ConversationLoopTest.java) — the tool-call and message ceilings, and the daily cap closing a conversation at `LIMIT_REACHED` |
| Credentials | [`AuthCookieSecurityTest`](../backend/src/test/java/dev/reception/auth/AuthCookieSecurityTest.java), [`AppenderRedactionTest`](../backend/src/test/java/dev/reception/common/logging/AppenderRedactionTest.java), and the `$2a$12$` prefix asserted in [`RegistrationTest`](../backend/src/test/java/dev/reception/auth/RegistrationTest.java) |
| Availability | [`RateLimitAddressTest`](../backend/src/test/java/dev/reception/common/ratelimit/RateLimitAddressTest.java) — **added in phase 11; see below** |
| Integrity | [`ConcurrentBookingTest`](../backend/src/test/java/dev/reception/appointments/ConcurrentBookingTest.java), twenty threads against the exclusion constraint |

- **"Rate limiting by IP" was true, and the *by IP* half was asserted by nothing.**
  [`RateLimitCoverageTest`](../backend/src/test/java/dev/reception/common/ratelimit/RateLimitCoverageTest.java) proves every public
  endpoint is matched by a policy, and [`RateLimitTest`](../backend/src/test/java/dev/reception/common/ratelimit/RateLimitTest.java)
  proves a limit bites. Both drive one client, so both are equally true of a filter keying every
  bucket on a constant. The per-address property is claimed four more times in prose in
  `RateLimitProperties` — *"keyed on the client address rather than the proxy's"* — and was checked
  in none of them.
- **The control rests on three independent facts, and any one could be undone without a test going
  red.** `server.forward-headers-strategy: framework` is set, in the base profile, so it reaches
  `prod`; Spring Boot registers `ForwardedHeaderFilter` at `HIGHEST_PRECEDENCE`, **ten ahead** of
  `RateLimitFilter`; and `RateLimitFilter` keys on `getRemoteAddr()`, which that filter's wrapper
  overrides. [`RateLimitAddressTest`](../backend/src/test/java/dev/reception/common/ratelimit/RateLimitAddressTest.java) asserts all
  three — the first two behaviourally, because every request in the suite arrives from `127.0.0.1`
  and two callers can only be told apart if `X-Forwarded-For` is transmitted *and* honoured.
- **The failure it admits is the inverse of the control, which is why the row is *Availability*.** A
  limiter that pools every visitor into one bucket does not merely fail to stop an attacker; it lets
  one stranger spend the budget for everybody and close a public endpoint to real users. The
  configuration comment says so in as many words: *"a limit that locks out real users while stopping
  nobody."*

> **A control held by a coin toss does not fail — it stops being a control.** The ordering above is
> load-bearing and the margin is ten. Planted deliberately, `RateLimitFilter` moved to
> `HIGHEST_PRECEDENCE` — the obvious edit for a filter that must precede authentication, and one no
> reviewer would question — ties the two filters, and **the behavioural half of the test stayed
> green**: tied filters are sequenced arbitrarily and that run happened to land the right way. Only
> the assertion on the two registered orders caught it. Where a property is decided by an ordering
> nobody wrote down, probing the behaviour samples the coin; the ordering itself has to be asserted
> as well.

## 2. Authentication

- Email + password. **BCrypt cost 12**; the hash is the only stored form.
- Access token: JWT, 15 minutes, signed HS256 with a secret from the environment.
- Refresh token: opaque random 256-bit value; only its SHA-256 hash is stored.
- **Rotation with replay detection:** each refresh issues a new token and revokes the old. Presenting an
  already-used token revokes the entire `family_id` — the standard response to a stolen refresh token.
- Both delivered as `httpOnly; SameSite=Lax; Path=/`, `Secure` outside the `local` profile.
  `RegistrationTest` asserts the first three over real HTTP and **structurally cannot assert the
  fourth**: the suite runs the `test` profile, where `secure` is `false` because the origin is plain
  http, so every assertion about these cookies was made at the one setting where the attribute is
  meant to be missing. `AuthCookieSecurityTest` reads the value that ships — `application-prod.yml`
  resolved through Boot's own config loading rather than a regex over the file — and pins that a
  profile with no file of its own gets `Secure` by omission. Note that *unset* is not that case:
  `spring.profiles.default: local` means an empty profile list resolves to `local`, and therefore to
  a plain cookie.
- Login failures return one message for both "no such user" and "wrong password".
- No account lockout in MVP (it is a denial-of-service vector against a known email); rate limiting on
  `/auth/login` by IP instead.

**Why no token in JavaScript.** With everything on one origin, cookies are sent automatically, so there is
no reason to expose a token to script. An XSS bug then cannot steal a session token, because the token is
not reachable from the DOM. This is the security payoff of the single-origin decision.

## 3. Authorization

Two independent checks on every tenant endpoint:

1. **Role check** — `OWNER` for configuration and writes. `ADMIN` is treated as `OWNER` in MVP; `STAFF`
   has no login. Declared with method security, not scattered `if`s.
2. **Tenant check** — implicit in the query. Repositories for tenant-owned entities expose
   `findByBusinessIdAndId(...)` and nothing else, so "forgetting" the tenant filter is not expressible.

**Cross-tenant access returns `404`, never `403`.** A `403` confirms the resource exists, which is itself a
leak. The tests assert `404` explicitly.

## 4. Tenant isolation

Four layers, each independently sufficient to catch a bug in the others:

1. **Derivation.** `business_id` comes from the Membership (authenticated), the slug (public), or the
   conversation record (AI). It is never a request parameter, body field, header, or tool argument.
2. **Query shape.** Every tenant-owned repository method takes `businessId` first.
3. **Database.** Composite foreign keys `(business_id, id)` make a cross-tenant row physically unwritable.
4. **Tests.** A dedicated suite enumerates every tenant-scoped endpoint and probes it in the shape its
   own classification calls for. `EndpointCatalogue` records one judgement per endpoint — what isolation
   means here, and which of Business B's rows the probe borrows — and `EndpointCoverageTest` holds it
   against Spring's routing table in both directions, so a new controller method breaks the build until
   somebody classifies it. **Classify or fail.**

> **Corrected in phase 11: classified was not the same as probed.** This section said "a dedicated suite
> enumerates every tenant-scoped endpoint, calls it as Business A with a Business B resource id, and
> asserts `404`" — which describes the 27 endpoints that take a path id and was never true of the other
> kinds. Two of the catalogue's six classifications, `OWNER_COLLECTION` and `OWNER_SINGLETON`, **were
> driven by nothing**: fifteen endpoints carried a written probe description — *"A's response must contain
> nothing of B's"* — that no test in the tree referenced. The guarantee was that every endpoint is
> classified, not that every classification runs. `TenantIsolationSweepTest` now sweeps both from the
> catalogue, and a registry check fails if a catalogued endpoint has no control, because a
> `@TestFactory` that yields nothing passes.
>
> **`GET /availability` was misclassified**, which is worse than unclassified. It has taken a required
> `serviceId` and two optional ids in the query string since phase 05, and was recorded as a collection
> that "takes no id" with nothing to borrow — a written judgement that closed the question wrongly and
> would stop the next reader looking. It is now `OWNER_QUERY_ID` and probed with B's real id. The reads
> behind it were correct all along; nothing had ever asked.
>
> **And the probe for it needed two layers removed before it would go red**, which is this section's own
> claim measured rather than asserted. Unscoping `ServiceCatalogService#read` alone left it green,
> because `AssignmentService#employeesFor` runs an independent tenant check on the same id. It failed
> only when both were removed. "Each independently sufficient to catch a bug in the others" is a real
> property of this path, demonstrated by plant.

## 5. Public endpoint security

The public surface is unauthenticated and therefore the most exposed part of the system.

| Endpoint | Limit | Rationale |
|---|---|---|
| `GET /public/businesses/**` | 120 / min / IP | Cheap reads |
| `GET /public/businesses/*/availability` | 60 / min / IP | Computation, but read-only |
| `POST /public/businesses/*/appointments` | 10 / hour / IP | Writes; spam bookings |
| `POST /public/appointments/lookup` | **5 / hour / IP** | Brute-forcing a Confirmation Code |
| `GET /public/appointments/manage` | 120 / min / IP | Cheap read, and one a customer may refresh |
| `GET /public/appointments/manage/availability` | 60 / min / IP | The same computation as availability |
| `POST /public/appointments/*/**` | 20 / hour / IP | Customer cancel and reschedule |
| `POST /public/businesses/*/chat` | 60 / hour / IP | Paid calls — the only request in this system that spends money every time |
| `POST /public/businesses/*/chat/session` | 20 / hour / IP | Opening a conversation. Tighter than a turn: cycling sessions is how you would retry a Confirmation Code past the conversation ceiling |
| `POST /auth/login` | 10 / 15 min / IP | Credential stuffing |
| `POST /auth/register` | 5 / hour / IP | Account spam |
| `POST /auth/refresh` | 60 / hour / IP | Session rotation — a database read and write, unauthenticated by construction |
| `POST /auth/logout` | 20 / hour / IP | A write reachable without a token, and nobody logs out more often |
| `GET /health` | 60 / min / IP | Opens a database connection *and an outbound SMTP connection* per call |
| `GET /openapi/**` | 30 / min / IP | The specification, ~45 KB, served to anyone. §15 |
| `GET /swagger-ui/**` | 60 / min / IP | The page's assets — `swagger-ui-bundle.js` alone is 1.4 MB |
| `GET /docs/**` | 60 / min / IP | The entry point that redirects to them |

**The chat rows are rate limits and nothing else.** The Receptionist's other bounds — five tool calls
a turn, forty messages a conversation, a twenty-message window — are per *conversation*, live in
`ConversationLimits`, and are not enforced by `RateLimitFilter`. Until phase 11 this table carried
*"20 / hour / conversation"* in the chat row, which was neither: the conversation ceilings are not
hourly, and the twenty is the session policy's budget, which had no row of its own. Both are fixed
above, and the table is now reconciled against the code rather than agreed with it by hand — see the
note below the rationale.

The three Manage Link rows were added in phase 08, which built the page they serve; the Definition of Done
requires every public endpoint to be limited, and an unlimited write is an unlimited write. Cancel and
reschedule are looser than booking because they cannot create anything — each needs a proof that already names
one existing appointment.

The last three rows were added in phase 11, by a test rather than by a reading. Until then "every public
endpoint is limited" was checked against a list of ten paths typed into a test file — which had been wrong
since phase 09, having never gained the chat endpoints. `/auth/refresh`, `/auth/logout` and `/health` had
carried no limit since the phase that introduced them. `/health` is the one worth naming: it is the only
endpoint in this system where an unauthenticated caller causes an *outbound* connection, which makes an
unlimited one an amplifier aimed at our own mail server.

**Order is significant and is tested.** The filter takes the first matching policy, so a wider pattern above a
narrower one makes the tight limit unreachable and leaves the endpoint it was written for guarded by the loose
one — silently, with nothing failing. `RateLimitPolicyOrderTest` pins which policy each public path lands on.

**Coverage is tested too, and derived rather than listed.** `RateLimitCoverageTest` enumerates the endpoints
from Spring's own `RequestMappingHandlerMapping` and asks the `AuthorizationManager` inside the running
security filter chain which of them an anonymous caller may reach — the same object that decides it in
production. An endpoint added under a `permitAll` pattern is in that test the moment it is mapped, and a
public endpoint with no policy fails the build. An exemption is possible and there are none; it would have to
carry a written reason.

Exceeding a limit returns `429` with `Retry-After`. Bucket4j in-memory for MVP; the externalisation path
(Redis) is documented and is the first change required when running more than one instance.

**Response minimisation.** Public responses are built from dedicated DTOs, never from entities. Employee
email and phone, internal settings, cost caps, other customers, and any id not needed to complete a booking
are absent by construction rather than by filtering.

## 6. Customer appointment authority

Customers have no accounts, so authority is proven per appointment:

- **Confirmation Code:** 8 characters of Crockford base32 (no `I`/`L`/`O`/`U`), ~40 bits. Never sufficient
  alone — a lookup requires **code + phone number**, and is limited to 5 attempts per hour per IP.
- **Manage Link:** HMAC-SHA256 over `appointmentId|expiry` with a server secret, expiring 24 hours after the
  appointment ends. A single-purpose capability token: it authorises one appointment and grants nothing else.
- Both paths converge on the same `authorizedAppointmentIds` concept used by the Receptionist.
- Manage tokens are excluded from the access log and never appear in an error message.

> **Added in phase 11, and until then this line described a control that did not exist.** The token
> is a bearer capability in a URL — a path segment in `/manage/{token}`, the page the Customer
> opens, and a query parameter on the two API calls that page makes — and Caddy's log is the only
> access log in the system. It was unfiltered, so every Manage Link a Customer clicked was written
> to stdout verbatim: measured against the running container, not inferred. Two log filters in
> [the Caddyfile](../infra/caddy/Caddyfile) redact it now, and `make check-access-log` holds them
> in CI.
>
> **The error logger was the trap.** A site's `log` directive configures the *access* logger only;
> Caddy writes `http.log.error` from the proxy handler on any transport error, carrying the whole
> request. A filter on the access log alone looked complete against a healthy stack and leaked
> every token the moment the backend was down — which is when somebody is reading the logs. It was
> found by probing with the backend stopped.
>
> The backend never had this problem: `getRequestURI()` excludes the query string, so the `instance`
> in an error body and every path the application logs stop at `/public/appointments/manage`. That
> is load-bearing and accidental — `getRequestURL()` plus `getQueryString()` would leak the lot.


**Why not phone-number-only lookup.** It would let anyone who knows a phone number list and cancel that
person's appointments. This was the single largest hole in the original specification and is closed here
deliberately.

## 7. Input validation

- Bean Validation on every DTO for shape, length and format; domain rules in the domain layer.
- Every string field has a maximum length. Unbounded text is a denial-of-service vector.
  `RequestFieldLengthTest` derives the request bodies from `RequestMappingHandlerMapping` and walks
  nested records and collection elements, so a DTO is covered the moment a controller takes it. There
  is no global request-size cap to fall back on — `max-http-form-post-size` does not apply to a JSON
  body and nothing else sets one, so the per-field bound is the only bound.

> **Added in phase 11, and it found three.** `PublicRequests.Authority` carried `manageToken`,
> `confirmationCode` and `phone` as bare strings on the two **unauthenticated** customer-authority
> endpoints, each with a bounded twin a few lines away: `Lookup` bounds the same code at 16 and the
> same number at 30, and `StartSession` bounds the same token at 500. The record's comment explains
> why none of them is `@NotBlank` — "exactly one of these" is not expressible as a field annotation —
> and that argument is sound, is about *presence*, and quietly took the length bound with it. They
> now carry their twins' maxima.
- Phone numbers normalised to E.164 using the business's country; unparseable input is rejected at entry.
- Timezone strings validated against the IANA database.
- UUIDs parsed, never interpolated.
- All persistence through JPA or parameterised native queries; no string-built SQL anywhere.
- Rich text is not accepted anywhere; all output is escaped by React, and email bodies are escaped at
  render time.

## 8. AI-specific security

Covered fully in [05-ai-architecture.md](./05-ai-architecture.md) §7. The security-relevant summary:

- No tool accepts a tenant identifier — cross-tenant access is not expressible.
- Write tools require the appointment id to be pre-authorised in the conversation.
- No tool performs bulk operations, accepts a filter expression, or executes anything resembling a query.
- Customer text never enters the system prompt; business text is delimited and labelled as data.
  Both halves asserted by
  [`SystemPromptSafetyTest`](../backend/src/test/java/dev/reception/ai/application/SystemPromptSafetyTest.java),
  which fills every owner-writable free-text field with its own sentinel and requires each to land
  *between* a pair of data markers — a behavioural check, because a test that read the builder for
  the string `<<<` would pass for a marker emitted somewhere the text is not.

> **Corrected in phase 11: the second half was true of one field of four, and it was the smallest.**
> `ai_additional_info` was delimited and labelled at 2,000 characters. The business description
> (5,000), the cancellation policy (5,000) and up to fifty FAQs at 1,300 characters each went into
> the prompt as **bare text under a heading** — some thirty times as much owner-written free text as
> the field that was fenced, and [05-ai-architecture.md](./05-ai-architecture.md) §7's own injection
> table names *"injection stored in an FAQ answer by a malicious owner"* as an attack. Its answer
> there is blast radius, which is the **containment** argument; this section claims the **labelling**
> one, and that was not built. All four now go through one `appendDataRegion` helper.
>
> **Neither claim was checked by anything that ran.** The only test referencing `SystemPromptBuilder`
> was `ProbeFixtureDumpTest`, which is `@Tag("probe")` — excluded from the suite by `build.gradle.kts`
> — and which writes a file rather than asserting anything.
>
> **The first half was true, and is now asserted as an equality rather than an absence.** *"The
> customer's words are not in the prompt"* is also true of a prompt that failed to build, of one
> truncated before the section, and of a turn that never happened. Two builds that are byte-identical
> across a real conversation is the property itself. It earns its place: a plant that appended the
> **model's** reply rather than the customer's passed the sentinel check and was caught only by the
> equality.
>
> **One edge is named rather than handled.** Truncation at `MAX_PROMPT_CHARACTERS` can cut a region
> before its closing marker. The sections that can reach the cap are these ones; a prompt that long
> means a business configured past the budget, and the honest fix is the budget rather than a guess
> about which marker to close.
- Per-conversation and per-business ceilings bound both blast radius and cost.
- The confirmation UI renders from API data, so a model that claims a booking that did not happen produces
  a visible absence rather than a convincing lie.

## 9. Secret management

- Every secret comes from an environment variable. `.env.example` lists all of them with dummy values.
- `.env` is git-ignored; the repository is scanned before the first push and secrets are never pasted into
  documents, issues or commit messages.
- Secrets in MVP: database password, JWT signing secret, Manage Link HMAC secret, OpenAI API key, SMTP
  credentials.
- Local defaults are obviously non-production values (`local-dev-only-…`), and the application **refuses to
  start** in the `prod` profile while **`JWT_SECRET`, `MANAGE_LINK_SECRET` or `DB_PASSWORD`** still holds its
  local default — blank, prefixed `local-dev-only-`, or (for the two signing secrets) under 32 characters.
  `MAIL_PASSWORD` and `OPENAI_API_KEY` are **deliberately not checked**: an empty SMTP password is legitimate,
  so the guard cannot tell "no auth needed" from "forgot it", and a placeholder OpenAI key is a working
  application — the Receptionist degrades to the Classic Flow. `SecretsGuardTest` holds all of this.
- Rotation: JWT and HMAC secrets are single values in MVP; a versioned-key scheme is a documented V1.1 item.

## 10. Logging and PII

Structured JSON logs with a request id and, for tenant operations, `business_id`.

**Never logged:** passwords (any form), access or refresh tokens, Manage Link tokens, Confirmation Codes,
OpenAI API keys, full customer message bodies, customer email addresses or phone numbers in plain form.

Customer identifiers appear in logs only as `customer_id`. Log entries for AI turns record token counts,
latency, tool names and outcomes — not message content. A redaction filter is applied at the appender, so
correctness does not depend on every call site remembering.

Those AI entries were a specification and nothing else until phase 11: the orchestration loop carried no log
statement about a model call at all, and the cost `CostTracker` computed for the daily cap was discarded
after it was checked. `AiCallLoggingTest` now runs real turns through the loop and asserts both halves — the
line carries the model, the latency, both token counts, the cost the cap was charged and each tool's name and
outcome, as structured fields rather than prose; and the customer's own words appear in no line anywhere,
proven against two plants that deliberately leak them. Tool *arguments* are never logged, because they are
where a customer's name and number arrive.

`RequestLoggingTest` covers the first sentence of this section the same way — real HTTP, and the MDC read off
the events the application logged, for both the token's business and the slug's.

**Both appenders, and checked at both.** `PiiValueMasker` holds the rules; the JSON appender applies it as
the encoder's masking decorator and the console applies it through the `%m` / `%wEx` conversion rules.
`AppenderRedactionTest` drives secrets through the encoder the real configuration builds and reads the
bytes — because a test that asserts the masker masks, or that the appender is of the right *type*, stays
green when the masking is deleted from the appender that ships.

## 11. Error responses

- One `@RestControllerAdvice` produces every error body. No handler writes its own.
- Stack traces, SQL text, class names and framework internals never reach a response.
- `detail` is written for a human and reveals nothing about system internals.
- Unhandled exceptions become a generic `500` carrying only the request id, which is the key to the log.

`ErrorLeakageTest` drives real exceptions through the real chain and asserts each of these, because the
only path that can leak is an exception escaping a controller — a `404` from the dispatcher never had a
stack trace to give away. The request id in the body is asserted **equal to the `X-Request-Id` header**:
one that differs from the header is worse than none, since it is the whole of what a `500` gives a caller.

## 12. Database security

- The application connects as a role with `DML` and no `DDL` rights outside migrations; Flyway uses a
  separate role.
- No superuser in any application connection string.
- **The database port is published only in the local IDE topology, and only to loopback.**
  `docker-compose.yml` binds it `127.0.0.1:${POSTGRES_PORT}:5432` so the backend running in an IDE
  can reach it; `docker-compose.apps.yml` removes the mapping outright, because a containerised
  backend resolves `postgres:5432` on the compose network and nothing on the host needs it. Mailpit
  is bound the same way — SMTP only in the IDE topology, the UI on loopback in all of them, because
  the E2E flow asserts against it.
- **`127.0.0.1` and not an unqualified mapping, because a host firewall does not cover the
  difference.** Docker writes its own rules in the `DOCKER-USER` chain, consulted below `ufw`, so an
  unqualified published port is reachable from the network while `ufw status` reads as though it is
  not. Measured on 2026-09-12 with the two bindings side by side on one machine: unqualified, the
  Mailpit UI answered `200` and Postgres accepted a connection on the host's LAN address; bound to
  loopback, both refused.
- **`make check-bindings` asserts this, and CI runs it.** Until 2026-09-12 the line above described
  a conditionality **no file implemented** — the second instance of the shape §13 records about the
  security headers, which were documented for ten phases before anything set them. The sentence was
  not weakened to match the files; the files were changed and a gate now holds them there.
- Backups are out of scope for MVP and stated as such rather than assumed.

## 13. Transport and browser

- Single origin, so **no CORS configuration exists** — the safest configuration is the absent one.
  [`NoCorsConfigurationTest`](../backend/src/test/java/dev/reception/common/web/NoCorsConfigurationTest.java)
  asserts it, because until phase 11 nothing did: `.cors(cors -> cors.disable())` is one line in
  `SecurityConfig` that could become `Customizer.withDefaults()` with a permissive source beside it,
  and a single `@CrossOrigin` on one controller would have left the whole suite green. An absent
  configuration is the one nothing defends, because there is no file to review.
- **The claim is checked in two halves, because it makes two different promises.** *Nothing is
  granted* is probed across all 65 mapped endpoints in both shapes — a simple cross-origin request,
  where the response either carries `Access-Control-Allow-Origin` or the caller cannot read it, and
  a preflight, which never reaches a handler and is refused `403` by `DefaultCorsProcessor`. *No
  configuration exists* is stronger and is the sentence written here: a `@CrossOrigin` naming one
  partner origin grants a hostile origin nothing, so it is invisible to any probe, and it is read
  instead off the two objects that can hold it — `RequestMappingHandlerMapping`'s configuration
  source, which is what `WebMvcConfigurer#addCorsMappings` populates, and the annotation itself.
  Shown red five ways, and two of them are worth naming.
- `SameSite=Lax` cookies plus a same-origin-only API removes classic CSRF for the cookie-authenticated
  surface; state-changing requests additionally **refuse the three content types an HTML form can send** —
  `application/x-www-form-urlencoded`, `multipart/form-data` and `text/plain`, the values `enctype` accepts —
  which blocks the form-post CSRF shape. A request with no `Content-Type` at all is allowed through: no form
  omits the header, and body-less `POST`s from legitimate clients often do.

> **Reworded in phase 11, because the sentence had been true of two thirds of the surface.** It previously
> said state-changing requests *"require `Content-Type: application/json`"*. That held wherever a
> `@RequestBody` existed — Spring answers `415` because no converter turns a form body into a DTO — and
> **not at the ten endpoints that take no body**, where there is nothing to convert and so nothing to
> refuse. Measured, not inferred: a form-encoded `POST /auth/logout` answered `204`. `SameSite=Lax` was
> carrying that surface alone, so two layers were documented and one existed — which matters precisely
> because the missing one is what a reader would count on if `SameSite` were ever relaxed.
> [`JsonOnlyWriteFilter`](../backend/src/main/java/dev/reception/common/web/JsonOnlyWriteFilter.java) is
> the control now, running before authentication so a forged request is refused on its shape rather than
> on the credentials it lacks. `FormPostRejectionTest` derives the write surface from
> `RequestMappingHandlerMapping`, so an endpoint is covered the moment it is mapped.
>
> *"Running before authentication"* is one of five orderings this application depends on and, like the
> other four, it lived in `@Order` arithmetic that nothing reconciled.
> [`FilterOrderTest`](../backend/src/test/java/dev/reception/common/config/FilterOrderTest.java) reads
> the chain Boot actually registers and the list `FilterChainProxy` actually runs, and pins each pair
> twice: by position, and by **order value**, because filters tied on order are sequenced arbitrarily
> and a position-only assertion passes on the lucky run. Shown red against a tie that ran in the
> correct order anyway.

> **A CORS probe can go blind without saying so, and this one was proved to.** `Origin` and
> `Access-Control-Request-Method` are both on `HttpURLConnection`'s restricted-header list, so a test
> client built on it drops them silently. Planted deliberately — a permissive CORS configuration
> wired into the filter chain *and* a client that could not send the headers — the preflight
> assertion **passed**, against an application granting every origin everything with credentials.
> The control that catches it is a pair and has to be: the preflight must vary on `Origin` and a
> bare `OPTIONS` to the same path must not. Taken alone neither holds — the first is also true of a
> blind probe against an application that *has* a configuration source, and the second is also true
> of a blind probe against this one.
>
> **The other finding was a test of my own reporting green over a surface it could not see.** A
> behavioural sweep for `Vary: Origin` was written first, and a `@CrossOrigin` planted on
> `AnalyticsController` changed no response at all: Spring Security answers `401` in the filter
> chain and MVC's CORS interceptor never runs. The same annotation on `HealthController` was caught
> at once. A sweep of that shape covers the public surface and silently covers nothing else, which
> is why the configuration is read rather than probed.
- Security headers via Caddy: `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, and a Content-Security-Policy
  with no `unsafe-eval`. `Server` is removed.
- **The Content-Security-Policy, exactly.** `default-src 'self'` with `base-uri 'self'`,
  `form-action 'self'`, `frame-ancestors 'none'` and `object-src 'none'`; `img-src` and `font-src`
  additionally allow `data:`; `style-src` and `script-src` additionally allow `'unsafe-inline'`.
  **`'unsafe-inline'` on `script-src` is a concession and is named here rather than buried**: the App
  Router serves its hydration payload as inline `<script>` elements, and the alternative — a
  per-request nonce — has to be issued where the HTML is rendered, which is Next's middleware and not
  a reverse proxy. `'unsafe-eval'` is absent, which is the part that stops a reflected string from
  becoming code without a tag to carry it.
- **One seam, and it is visible.** `CSP_SCRIPT_EXTRA` is appended to `script-src`. `next dev` compiles
  modules through `eval`, so the development topology sets it to `'unsafe-eval'` in `.env`;
  `docker-compose.apps.yml` — the five-container shape CI smoke-tests and the one that deploys —
  empties it, and the Caddyfile's own default is empty, so a topology that forgets the variable gets
  the strict policy rather than the lenient one.
- **`Strict-Transport-Security` is `max-age=0` locally, and that is not a stub.** A browser honours
  HSTS only on a response that arrived over HTTPS, so on a plain-HTTP origin the header is inert
  whatever it says; `max-age=0` is the value that means "remember no policy for this host", which is
  correct for an origin that will never be HTTPS. A TLS deployment sets `HSTS` to
  `max-age=31536000; includeSubDomains` (docs/deployment.md).

> **Added in phase 11, and until then this section described two controls that did not exist.** The
> `Strict-Transport-Security` and Content-Security-Policy lines above were written in the design phase
> and no file ever set either header; a grep for both across `backend/src`, `frontend/src`,
> `next.config.*` and `infra` returned one hit, and it was `frameOptions().deny()`. The gap survived
> because the natural way to write the header test is to read the Caddyfile and assert what is there —
> which would have gone green and certified the absence. The order has to be *decide, then assert*.

## 14. Auditability

- `appointment_events` is an append-only record of who changed what and when, including whether the actor
  was the AI.
- `ai_messages` records every tool call and result — **for ninety days.** A transcript holds the
  Customer's name and phone number as they typed them, so it is deleted ninety days after the
  conversation's last activity by `TranscriptPurgeJob`, an hourly scheduled purge. The
  `ai_conversations` row survives, marked with `messages_purged_at`: it carries counters and a cost
  estimate and no free text. Purged rather than redacted — a half-scrubbed transcript is harder to
  reason about than an absent one — and the window is a constant in `ConversationLimits`, not an
  environment variable. Proven by `TranscriptRetentionTest`, which asserts both directions: what is
  taken past the window, and what is spared inside it.
- `created_at` / `updated_at` on every table.
- Authentication events (login, refresh, revocation) are logged with user id and IP, by
  `AuthService`, and asserted by
  [`AuthEventLoggingTest`](../backend/src/test/java/dev/reception/auth/AuthEventLoggingTest.java).
  **Registration is logged too**, although this sentence named only three: registration is where a
  session first exists, and leaving it out puts the hole in the trail exactly at the moment an
  account is created.
- **The user id and not the email.** The id is stable and is what every other record joins on; the
  address is a customer-grade identifier that `PiiValueMasker` redacts out of log output anyway, so
  logging it would produce a line that names nobody. The IP is the peer address — the same value the
  refresh token row already stores — and is deliberately not masked, because an authentication record
  without an origin cannot answer the question it exists for.

> **Added in phase 11, because the sentence above was true of nothing.** `AuthService` contained no
> log statement at all. The only authentication line anywhere in the application was the replay
> warning in `RefreshTokenFamilyRevoker`, which carried a user id and **no address** — so the audit
> trail began at the one event an attacker triggers deliberately and could not say where it came
> from. The data was never missing: `RequestFingerprint` has carried the user agent and the peer
> address since phase 02 and the refresh token row stores both. It simply never reached a log.
>
> The list of events is **derived** from the `/auth` surface off `RequestMappingHandlerMapping`, and
> every write on it must be either a recorded event or an exemption with a reason — `EndpointCatalogue`'s
> classify-or-fail, one section over. A typed list of three cannot fail for the fourth authentication
> endpoint somebody adds, which is the endpoint this is about.
>
> **One plant found a hole in the test rather than in the code**, which is the third time this walk
> has gone that way. A password written straight into a log line from `login()` **passed**: the
> credential sweep drove register, refresh and logout, and not the one endpoint that receives a
> password. It now drives every write on the surface, and a reconciliation holds it there.

## 15. Explicitly accepted risks

Stated rather than silently carried:

| Risk | Why accepted for MVP |
|---|---|
| No email verification at signup | Blocks the demo; no user-visible data depends on address ownership |
| No account lockout | Rate limiting instead; lockout is a DoS vector against a known email |
| In-memory rate limiting | Single instance in MVP; externalisation documented as the first scale-out task |
| No key rotation scheme | Single-secret; rotation requires a re-login of all users, acceptable at this stage |
| No backups | Local-only deployment; would be mandatory before any real tenant |
| No 2FA | Out of MVP scope. **Adding it needs a migration** — `users` has six columns and none of them can hold a shared secret or an enrolment flag, and there is no credentials table. This entry read *"the account model supports adding it without migration"* until phase 11, which was wrong, and wrong in the direction that matters: the justification is what a reader prices the reversal with |
| The API documentation is unauthenticated in every profile | `/docs`, `/openapi` and `/swagger-ui` are permitted to everyone, `prod` included ([SecurityConfig](../backend/src/main/java/dev/reception/common/config/SecurityConfig.java)). Correct against the MVP's local-compose contract, where the origin is a developer's own machine — and **the first thing to change on an internet-reachable host**, because it publishes the entire endpoint surface to anyone who asks. Recorded here in phase 11 because it was in neither this table nor the deployment notes, which is the state this table exists to make impossible. The §15 review found it **also unlimited**; that half is now closed (§5), and **the disclosure is what remains accepted**. Note it stops at the JSON rendering: `/openapi.yaml` answers `401`, because `/openapi/**` matches children and not siblings — an accident, and pinned as one |
| A Manage Link is a bearer capability in a URL | Anyone holding the link can cancel or reschedule that one appointment until 24 hours after it ends — a forwarded email, a shared screen or a shared browser's history is enough. Accepted because the alternative is asking a Customer for their code and phone number on every visit to a link we emailed them, which is the friction the link exists to remove. §6 describes the token at length and the access-log leak it caused; **the residual risk was never carried here**, which is the same omission the row above it records |

**Reviewed in phase 11, and the review is a different job from the rest of this walk.** The other
fourteen sections name controls, and the question is whether each resolves to a test. An accepted
risk resolves to nothing by definition — the entry *is* the decision not to build the control. What
it can be checked for is the opposite defect: an entry that no longer describes the system.

Three of the seven were wrong, and each in a different way.

- **A justification that was false.** *"The account model supports adding 2FA without migration"* —
  `users` carries id, email, password hash, full name and two timestamps, there is no credentials
  table, and nothing there can hold a shared secret or an enrolment flag. A reader deciding how
  cheaply this risk could be reversed was being told the wrong number.
- **An entry that recorded half its risk.** The API documentation entry recorded the **disclosure**
  and not the **amplification**. `/openapi` answers any anonymous caller with the complete
  specification — tens of kilobytes — and **no rate-limit policy matches it**, so two hundred
  consecutive requests are served without one refusal. The Definition of Done requires every public
  endpoint to be limited; `RateLimitCoverageTest` enforces that across the surface it can see, and
  it filters to `dev.reception` on purpose, so that a springdoc release renaming its paths cannot
  break the build. These three paths are mapped outside that package. They are anonymous, unlimited,
  and **invisible to every derived control in this repository** — and Caddy's `handle /api/*`
  proxies all of them, so the exposure is the deployed one and not a local-only artefact.
  [`ApiDocumentationExposureTest`](../backend/src/test/java/dev/reception/common/web/ApiDocumentationExposureTest.java)
  pins both halves. **The amplification was closed** — three policies in §5's table — **and the
  disclosure stays accepted**, which is the row above. The two were separate decisions and the test
  keeps them apart, so that closing one later cannot quietly be read as closing the other.
- **A risk that was missing.** The Manage Link's residual risk — a bearer capability living in a URL
  — is discussed at length in §6 as a *fact* about the token, and was never carried here as a
  *decision*. §6 closed the access-log leak; it did not close the property that made the leak matter.

The four remaining entries were checked and are accurate, including the two that make claims about
other documents: the rate-limit externalisation path is genuinely written down in three places, and
the backup position is stated in `deployment.md` rather than assumed.

**Closing the amplification took two changes, and the second is the one worth remembering.** The
policies were the easy half. The derivation in `RateLimitCoverageTest` filtered handlers to
`dev.reception`, inherited from `EndpointCoverageTest` where the reason is sound — a springdoc
release renaming its paths should not break a test about *our* tenancy. Rate limiting is a different
question: springdoc's paths spend exactly what ours do. So the filter did not merely hide the gap,
**it rejected the fix** — a policy written for `/openapi` matched nothing in the derived surface and
was reported as a dead policy. The derivation now sees every mapped endpoint, which also means a
`permitAll` widened to expose a framework path arrives as an uncovered public endpoint rather than
as nothing at all.

> **A guarantee is only as wide as the set it quantifies over, and the set is easy to read past.**
> *"Every endpoint an anonymous caller can reach is rate limited"* was enforced, derived, and shown
> red — and it quietly meant *every endpoint of ours*, while the largest unauthenticated response in
> the system sat outside it. Nothing was wrong with the test; the scope was one line in a private
> method, correct where it was written and wrong where it was inherited. **When a control is copied
> between sections, its exclusions have to be re-argued rather than re-used.**
>
> One exclusion could not be closed and is worth naming: `/swagger-ui/**` is served by a resource
> handler, which declares no handler methods, so it can never appear in a derivation built on
> `RequestMappingHandlerMapping`. Its policy was required by a written list — a policy *name* bound
> by hand to a path — and the orphan check verified that list by **probing the running
> application**.
>
> **The probe asked the wrong question, and the branch was vacuous.** It checked that something was
> mounted at the path; it never checked that the policy keyed to that path *matched* it. Repointing
> `api-docs-ui` at `/nonsense/**` left the swagger-ui assets unlimited and `RateLimitCoverageTest`
> entirely green — the consequence was caught, but by `ApiDocumentationExposureTest` driving real
> requests, not by the control that claims it. The pairing is now computed: the patterns come from
> the resource mapping itself, the paths from the patterns, and the question asked of a path is the
> same one asked of an endpoint. No policy name appears in it. A second assertion covers the
> direction the written list never had — **every path served by a resource handler is matched by
> some policy** — and both are held up by a control that requires each derived path to be served
> with a `200`, because a `401` from the security chain is not evidence that an asset exists.
>
> **That exclusion turned out to be one of three, and the widest had not been written down at all.**
> Every derived control in this repository reads `RequestMappingHandlerMapping` — and this
> application builds **eight** handler mappings. A resource handler holds the swagger-ui assets; the
> actuator holds `GET /actuator`, mapped even though `management.endpoints.web.exposure.include` is
> set to the empty string, because the links document is registered independently of what it has to
> link to; a `RouterFunction` bean would hold a third set that no control here can see at all. A
> mapping that declares no HTTP method is invisible for a different reason again: every one of these
> derivations pairs a pattern with a verb, and Spring's `/error` offers no verb to pair, so it does
> not arrive to be excluded. [`MappedSurfaceTest`](../backend/src/test/java/dev/reception/common/web/MappedSurfaceTest.java)
> enumerates all three classes, asserts by equality that each is still what it says, asserts that the
> five empty mappings are **empty rather than merely described so**, and pins the two invisible
> surfaces by the property that makes them harmless — neither `/error` nor `/actuator` is reachable
> without authentication. Adding `/error` to `permitAll` was measured: it creates an anonymous,
> unlimited, unclassified endpoint and `RateLimitCoverageTest`, `EndpointCoverageTest` and
> `PublicSurfaceSweepTest` **all stay green**.

> **An accepted-risk table is the one place where going out of date is the whole failure.** Every
> other section describes a control, and a stale sentence there is caught the moment somebody tests
> it. Here there is nothing to test, because the entry records an absence — so the only thing holding
> an entry true is that somebody re-reads it, and the entry most likely to drift is the one whose
> subject is a live configuration. That is why the API documentation row is now asserted rather than
> merely written: not to defend the risk, but so that **closing** it cannot happen silently and leave
> this table describing a system that no longer exists. If those assertions fail, the fix is to
> correct the row.
