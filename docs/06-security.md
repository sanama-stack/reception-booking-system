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

The three Manage Link rows were added in phase 08, which built the page they serve; the Definition of Done
requires every public endpoint to be limited, and an unlimited write is an unlimited write. Cancel and
reschedule are looser than booking because they cannot create anything — each needs a proof that already names
one existing appointment.

**Order is significant and is tested.** The filter takes the first matching policy, so a wider pattern above a
narrower one makes the tight limit unreachable and leaves the endpoint it was written for guarded by the loose
one — silently, with nothing failing. `RateLimitPolicyOrderTest` pins which policy each public path lands on.

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
  start** in the `prod` profile if any secret still holds its local default.
- Rotation: JWT and HMAC secrets are single values in MVP; a versioned-key scheme is a documented V1.1 item.

## 10. Logging and PII

Structured JSON logs with a request id and, for tenant operations, `business_id`.

**Never logged:** passwords (any form), access or refresh tokens, Manage Link tokens, Confirmation Codes,
OpenAI API keys, full customer message bodies, customer email addresses or phone numbers in plain form.

Customer identifiers appear in logs only as `customer_id`. Log entries for AI turns record token counts,
latency, tool names and outcomes — not message content. A redaction filter is applied at the appender, so
correctness does not depend on every call site remembering.

## 11. Error responses

- One `@RestControllerAdvice` produces every error body. No handler writes its own.
- Stack traces, SQL text, class names and framework internals never reach a response.
- `detail` is written for a human and reveals nothing about system internals.
- Unhandled exceptions become a generic `500` carrying only the request id, which is the key to the log.

## 12. Database security

- The application connects as a role with `DML` and no `DDL` rights outside migrations; Flyway uses a
  separate role.
- No superuser in any application connection string.
- The database port is exposed to the host only in the `local` compose profile.
- Backups are out of scope for MVP and stated as such rather than assumed.

## 13. Transport and browser

- Single origin, so **no CORS configuration exists** — the safest configuration is the absent one.
- `SameSite=Lax` cookies plus a same-origin-only API removes classic CSRF for the cookie-authenticated
  surface; state-changing requests additionally require `Content-Type: application/json`, which blocks the
  form-post CSRF shape.
- Security headers via Caddy: `Strict-Transport-Security` (non-local), `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, and a Content-Security-Policy
  with no `unsafe-eval`.

## 14. Auditability

- `appointment_events` is an append-only record of who changed what and when, including whether the actor
  was the AI.
- `ai_messages` records every tool call and result.
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
