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

## 2. Authentication

- Email + password. **BCrypt cost 12**; the hash is the only stored form.
- Access token: JWT, 15 minutes, signed HS256 with a secret from the environment.
- Refresh token: opaque random 256-bit value; only its SHA-256 hash is stored.
- **Rotation with replay detection:** each refresh issues a new token and revokes the old. Presenting an
  already-used token revokes the entire `family_id` — the standard response to a stolen refresh token.
- Both delivered as `httpOnly; SameSite=Lax; Path=/`, `Secure` outside the `local` profile.
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
4. **Tests.** A dedicated suite enumerates every tenant-scoped endpoint, calls it as Business A with a
   Business B resource id, and asserts `404`. New endpoints without a probe fail review.

## 5. Public endpoint security

The public surface is unauthenticated and therefore the most exposed part of the system.

| Endpoint | Limit | Rationale |
|---|---|---|
| `GET /public/businesses/{slug}*` | 120 / min / IP | Cheap reads |
| `GET …/availability` | 60 / min / IP | Computation, but read-only |
| `POST …/appointments` | 10 / hour / IP | Writes; spam bookings |
| `POST /public/appointments/lookup` | **5 / hour / IP** | Brute-forcing a Confirmation Code |
| `GET /public/appointments/manage` | 120 / min / IP | Cheap read, and one a customer may refresh |
| `GET …/manage/availability` | 60 / min / IP | The same computation as availability |
| `POST /public/appointments/{id}/*` | 20 / hour / IP | Customer cancel and reschedule |
| `POST …/chat` | 20 / hour / conversation, 60 / hour / IP | Paid calls |
| `POST /auth/login` | 10 / 15 min / IP | Credential stuffing |
| `POST /auth/register` | 5 / hour / IP | Account spam |
| `POST /auth/refresh` | 60 / hour / IP | Session rotation — a database read and write, unauthenticated by construction |
| `POST /auth/logout` | 20 / hour / IP | A write reachable without a token, and nobody logs out more often |
| `GET /health` | 60 / min / IP | Opens a database connection *and an outbound SMTP connection* per call |

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
- Manage tokens are excluded from access logs and never appear in an error message.

**Why not phone-number-only lookup.** It would let anyone who knows a phone number list and cancel that
person's appointments. This was the single largest hole in the original specification and is closed here
deliberately.

## 7. Input validation

- Bean Validation on every DTO for shape, length and format; domain rules in the domain layer.
- Every string field has a maximum length. Unbounded text is a denial-of-service vector.
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
- `SameSite=Lax` cookies plus a same-origin-only API removes classic CSRF for the cookie-authenticated
  surface; state-changing requests additionally require `Content-Type: application/json`, which blocks the
  form-post CSRF shape.
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
- Authentication events (login, refresh, revocation) are logged with user id and IP.

## 15. Explicitly accepted risks

Stated rather than silently carried:

| Risk | Why accepted for MVP |
|---|---|
| No email verification at signup | Blocks the demo; no user-visible data depends on address ownership |
| No account lockout | Rate limiting instead; lockout is a DoS vector against a known email |
| In-memory rate limiting | Single instance in MVP; externalisation documented as the first scale-out task |
| No key rotation scheme | Single-secret; rotation requires a re-login of all users, acceptable at this stage |
| No backups | Local-only deployment; would be mandatory before any real tenant |
| No 2FA | Out of MVP scope; the account model supports adding it without migration |
| The API documentation is unauthenticated in every profile | `/docs`, `/openapi` and `/swagger-ui` are permitted to everyone, `prod` included ([SecurityConfig](../backend/src/main/java/dev/reception/common/config/SecurityConfig.java)). Correct against the MVP's local-compose contract, where the origin is a developer's own machine — and **the first thing to change on an internet-reachable host**, because it publishes the entire endpoint surface to anyone who asks. Recorded here in phase 11 because it was in neither this table nor the deployment notes, which is the state this table exists to make impossible |
