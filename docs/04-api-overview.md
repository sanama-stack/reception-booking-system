# 04 — API Overview

All paths are relative to `/api` behind the single origin. JSON in, JSON out, UTF-8.

## 1. Three surfaces

| Surface | Prefix | Authentication | Tenant resolved from |
|---|---|---|---|
| Auth | `/auth/*` | none / refresh cookie | n/a |
| Tenant | `/business`, `/services`, `/employees`, `/appointments`, `/customers`, `/analytics`, `/availability`, `/conversations` | access cookie | the authenticated Membership |
| Public | `/public/*` | none | the `{slug}` in the path |

**No endpoint anywhere accepts `business_id` from the caller.** This is the rule the whole isolation design
rests on; an endpoint that breaks it is a defect regardless of what checks it performs afterwards.

## 2. Conventions

- **Auth transport:** httpOnly cookies (`access_token`, `refresh_token`), `SameSite=Lax`, `Secure` outside
  `local`. No `Authorization` header, no token in JavaScript.
- **Datetimes:** ISO-8601 with offset, plus an explicit `timezone` field on any response whose times need
  interpreting. Example: `"2026-09-08T17:00:00+04:00"` with `"timezone": "Asia/Tbilisi"`.
- **Dates:** `YYYY-MM-DD`, always meaning a calendar date **in the business timezone**.
- **Money:** `{ "amount": "60.00", "currency": "GEL" }` — a decimal string, never a float.
- **Ids:** UUID strings.
- **Pagination:** `?page=0&size=20`, response `{ content, page, size, totalElements, totalPages }`.
- **Idempotency:** cancellation is idempotent; repeated calls return `200` without re-sending email.
- **Personal data never travels in a query string.** Appointment lookup is a `POST` for exactly this reason.

## 3. Error contract

RFC 9457 `application/problem+json`, extended with a machine-readable `code`:

```json
{
  "type": "https://reception.dev/errors/slot-unavailable",
  "title": "Slot unavailable",
  "status": 409,
  "detail": "That time was booked while you were deciding.",
  "instance": "/api/public/businesses/salon-aria/appointments",
  "code": "SLOT_UNAVAILABLE",
  "errors": []
}
```

Validation failures add entries to `errors`: `{ "field": "durationMinutes", "message": "must be a multiple of 5" }`.

### Error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 422 | Request shape or field rules violated |
| `EMAIL_TAKEN` | 409 | Registration on an existing address |
| `SLUG_TAKEN` | 409 | Requested slug is in use |
| `INVALID_CREDENTIALS` | 401 | Login failed — deliberately does not distinguish cause |
| `UNAUTHENTICATED` | 401 | No credential presented and no refresh cookie either, or a credential that is not one this server issued. The client should sign in rather than refresh. An *unknown* path is answered exactly like a known one — which of the three 401 codes below you get depends on the cookies you sent and never on whether the path exists, so the endpoint map cannot be enumerated |
| `TOKEN_EXPIRED` | 401 | Access token expired; client should refresh |
| `SESSION_REFRESHABLE` | 401 | No access token presented, but the caller still holds a refresh cookie — client should refresh, exactly as for `TOKEN_EXPIRED`. This, not `TOKEN_EXPIRED`, is what a *browser* meets at the 15-minute mark: the access cookie's max age matches the token's lifetime, so the cookie is deleted rather than presented and rejected |
| `TOKEN_REUSED` | 401 | Refresh replay detected; the token family was revoked |
| `FORBIDDEN` | 403 | Authenticated but the role is insufficient |
| `NOT_FOUND` | 404 | Does not exist **or** belongs to another tenant — indistinguishable by design |
| `SLOT_UNAVAILABLE` | 409 | Exclusion constraint rejected the booking |
| `SERVICE_IN_USE` | 409 | Hard delete refused; deactivate instead |
| `VERSION_CONFLICT` | 409 | Concurrent modification of the same appointment |
| `SERVICE_INACTIVE` | 422 | Service is not bookable |
| `EMPLOYEE_INACTIVE` | 422 | Employee is not bookable |
| `EMPLOYEE_CANNOT_PERFORM_SERVICE` | 422 | No assignment between employee and service |
| `OUTSIDE_BUSINESS_HOURS` | 422 | Requested time is outside opening hours |
| `OUTSIDE_WORKING_HOURS` | 422 | Requested time is outside the employee's schedule |
| `BOOKING_IN_PAST` | 422 | Requested start is in the past |
| `BELOW_MIN_LEAD_TIME` | 422 | Too soon under the business's lead-time policy |
| `BEYOND_MAX_ADVANCE` | 422 | Beyond the booking horizon |
| `CANCELLATION_WINDOW_CLOSED` | 422 | Customer cancelling too late; the business is exempt |
| `INVALID_STATUS_TRANSITION` | 422 | Not a legal move in the appointment state machine |
| `INVALID_CONFIRMATION_CODE` | 404 | Code and phone did not match — same response as "no such appointment" |
| `MANAGE_TOKEN_INVALID` | 401 | Manage Link expired or tampered with |
| `RATE_LIMITED` | 429 | Includes `Retry-After` |
| `AI_UNAVAILABLE` | 503 | Model provider failed; client should offer the Classic Flow |
| `AI_LIMIT_REACHED` | 429 | Conversation or daily business cost ceiling reached |

---

## 4. Auth endpoints

| Method | Path | Purpose | Auth |
|---|---|---|---|
| POST | `/auth/register` | Create user + business + owner membership + default hours | none |
| POST | `/auth/login` | Issue cookies | none |
| POST | `/auth/refresh` | Rotate refresh, reissue access | refresh cookie |
| POST | `/auth/logout` | Revoke refresh token, clear cookies | any |
| GET | `/auth/me` | Current user, business summary, role | access cookie |

**POST `/auth/register`**

```json
{ "email": "nino@aria.ge", "password": "…", "fullName": "Nino K.", "businessName": "Salon Aria" }
```
→ `201` `{ "user": {…}, "business": { "id", "name", "slug", "timezone" } }`
Errors: `VALIDATION_FAILED`, `EMAIL_TAKEN`.

---

## 5. Tenant endpoints

### Business

| Method | Path | Purpose |
|---|---|---|
| GET | `/business` | Full profile and settings |
| PATCH | `/business` | Update any subset of profile/settings |
| GET | `/business/hours` | Whole-week hours |
| PUT | `/business/hours` | Replace the whole week atomically |
| GET/POST | `/business/closures` | List / create |
| DELETE | `/business/closures/{id}` | Remove |
| GET/POST | `/business/faqs` | List / create |
| PATCH/DELETE | `/business/faqs/{id}` | Update / remove |
| GET | `/business/onboarding` | Checklist state driving the dashboard |

`PUT /business/hours` takes the entire week so partial-week edits cannot leave inconsistent state:

```json
{ "hours": [ { "dayOfWeek": 1, "opensAt": "09:00", "closesAt": "18:00" }, … ] }
```
Errors: `VALIDATION_FAILED` (overlap, `closesAt <= opensAt`, bad day).

### Services

| Method | Path | Purpose |
|---|---|---|
| GET | `/services?active=` | List |
| POST | `/services` | Create |
| GET/PATCH | `/services/{id}` | Read / update |
| POST | `/services/{id}/activate` \| `/deactivate` | Toggle bookability |
| DELETE | `/services/{id}` | Hard delete, refused once booked |
| PUT | `/services/{id}/employees` | Replace the eligible-employee set |

### Employees

| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/employees` | List / create |
| GET/PATCH | `/employees/{id}` | Read / update |
| POST | `/employees/{id}/activate` \| `/deactivate` | Toggle |
| PUT | `/employees/{id}/services` | Replace the service set |
| GET/PUT | `/employees/{id}/schedule` | Read / replace the whole week |
| GET/POST | `/employees/{id}/time-off` | List / create |
| DELETE | `/employees/{id}/time-off/{offId}` | Remove |

Deactivation responses report `{ "affectedFutureAppointments": 3 }` so the UI can prompt deliberately.
Nothing is auto-cancelled.

### Availability (internal)

| Method | Path | Purpose |
|---|---|---|
| GET | `/availability?serviceId=&from=&to=&employeeId=&excludeAppointmentId=` | Same engine the public API and AI tools use |

`excludeAppointmentId` is optional and only meaningful while rescheduling: it leaves the named Appointment
out of the occupancy the grid is computed against, so the time it currently holds is offered back instead of
counted against itself. Accepted from the caller here because the endpoint is authenticated and
tenant-scoped — another business's id excludes nothing, because those rows were never in scope. **The public
availability endpoint deliberately does not take it**; a rescheduling Customer uses
`GET /public/appointments/manage/availability`, where the exclusion is the appointment their Manage Link
authorises and cannot be anything else.

Response:

```json
{
  "timezone": "Asia/Tbilisi",
  "days": [
    { "date": "2026-09-08",
      "slots": [
        { "startsAt": "2026-09-08T17:00:00+04:00",
          "endsAt":   "2026-09-08T18:00:00+04:00",
          "employee": { "id": "…", "fullName": "Lika" } }
      ] }
  ],
  "emptyReason": null
}
```

`emptyReason` ∈ `null | CLOSED | NO_ELIGIBLE_EMPLOYEE | FULLY_BOOKED | OUTSIDE_HORIZON`. Returning a reason
rather than an empty array is what lets the Receptionist explain *why* instead of guessing.

Every slot carries its employee even when none was requested — resolving the employee later would
reintroduce the race the exclusion constraint eliminates.

### Appointments

| Method | Path | Purpose |
|---|---|---|
| GET | `/appointments?from=&to=&status=&employeeId=&page=&size=` | Filtered list |
| GET | `/appointments/{id}` | Detail incl. audit history |
| POST | `/appointments` | Book from the dashboard (`source=DASHBOARD`) |
| POST | `/appointments/{id}/cancel` | Cancel; `cancelledBy=BUSINESS`, exempt from the window |
| POST | `/appointments/{id}/reschedule` | New time; transactional update in place |
| POST | `/appointments/{id}/status` | `COMPLETED` \| `NO_SHOW` |

### Customers

| Method | Path | Purpose |
|---|---|---|
| GET | `/customers?q=&page=&size=` | List / search |
| GET | `/customers/{id}` | Detail |
| PATCH | `/customers/{id}` | Correct name or email |
| GET | `/customers/{id}/appointments` | Full history |

### Analytics

| Method | Path | Purpose |
|---|---|---|
| GET | `/analytics/summary?from=&to=` | The single metrics endpoint |

```json
{
  "range": { "from": "2026-09-01", "to": "2026-09-30", "timezone": "Asia/Tbilisi" },
  "counts": { "confirmed": 42, "completed": 31, "cancelled": 6, "noShow": 3, "total": 82 },
  "periods": { "today": 4, "thisWeek": 19, "thisMonth": 82 },
  "revenue": { "amount": "3410.00", "currency": "GEL", "basis": "COMPLETED_ONLY" },
  "rates": { "cancellation": 0.073, "noShow": 0.037 },
  "topServices": [ { "serviceId": "…", "name": "Women's Cut", "count": 26 } ]
}
```

`rates` values are `null`, not `0`, when the denominator is zero.

### Conversations (read-only)

| Method | Path | Purpose |
|---|---|---|
| GET | `/conversations?page=&size=` | Receptionist transcripts for this business |
| GET | `/conversations/{id}` | Messages and tool calls |

---

## 6. Public endpoints

Rate limited by IP. Responses contain **only** what the booking page needs — no employee contact details,
no internal settings, no other customers.

| Method | Path | Purpose |
|---|---|---|
| GET | `/public/businesses/{slug}` | Profile, hours, policy, `aiEnabled` |
| GET | `/public/businesses/{slug}/services` | Active services with duration and price |
| GET | `/public/businesses/{slug}/employees?serviceId=` | Names and job titles only |
| GET | `/public/businesses/{slug}/availability?serviceId=&from=&to=&employeeId=` | Same engine, same shape |
| POST | `/public/businesses/{slug}/appointments` | Book (`source=CLASSIC`) |
| POST | `/public/appointments/lookup` | `{ confirmationCode, phone }` → appointment |
| GET | `/public/appointments/manage?token=` | Resolve a Manage Link |
| GET | `/public/appointments/manage/availability?token=&from=&to=&employeeId=` | Slots to move to, excluding this appointment |
| POST | `/public/appointments/{id}/cancel` | Requires manage token or verified lookup |
| POST | `/public/appointments/{id}/reschedule` | Same authority requirement |
| POST | `/public/businesses/{slug}/chat/session` | Start a conversation (optionally seeded by a Manage Link) |
| POST | `/public/businesses/{slug}/chat` | One turn |

**POST `/public/businesses/{slug}/appointments`**

```json
{
  "serviceId": "…", "employeeId": "…",
  "startsAt": "2026-09-08T17:00:00+04:00",
  "customer": { "fullName": "Ana", "phone": "+995555123456", "email": "ana@example.com" },
  "note": "First visit"
}
```
→ `201`
```json
{ "id": "…", "confirmationCode": "7QK4M2XR",
  "startsAt": "…", "endsAt": "…", "timezone": "Asia/Tbilisi",
  "service": { "name": "Colour", "durationMinutes": 150 },
  "employee": { "fullName": "Lika" },
  "price": { "amount": "220.00", "currency": "GEL" },
  "confirmationSent": true }
```
`confirmationSent` is whether a confirmation was actually enqueued, and **not** the address it went to. A
Customer is identified by phone number alone and a returning number keeps the email already on file, so the
`customer.email` in the request is neither necessarily the recipient nor necessarily present — the
confirmation screen has to be told rather than assume. The resolved recipient is never returned: booking is
proved by a phone number, and echoing back the address filed against one would make it readable to whoever
holds it (ADR-0007).
Errors: `SLOT_UNAVAILABLE`, `SERVICE_INACTIVE`, `EMPLOYEE_INACTIVE`,
`EMPLOYEE_CANNOT_PERFORM_SERVICE`, `OUTSIDE_BUSINESS_HOURS`, `OUTSIDE_WORKING_HOURS`, `BOOKING_IN_PAST`,
`BELOW_MIN_LEAD_TIME`, `BEYOND_MAX_ADVANCE`, `VALIDATION_FAILED`, `RATE_LIMITED`.

**POST `/public/appointments/lookup`** — a `POST` because a phone number must never enter a URL, a log line
or a referrer header. Requires code **and** phone; presenting one alone is a schema rejection, and a code with
the wrong number returns `INVALID_CONFIRMATION_CODE`. Rate limited aggressively: this is the endpoint an
attacker would brute-force.

**POST `/public/appointments/{id}/cancel`** and **`/reschedule`** carry their proof in the body, as either
shape of an `authority` object:

```json
{ "authority": { "manageToken": "…" }, "reason": "Changed my mind" }
{ "authority": { "confirmationCode": "7QK4M2XR", "phone": "+995555123456" }, "startsAt": "…" }
```

The proof resolves an Appointment on its own and the `{id}` in the path is then **compared** against it — an
id in a URL is a claim, the token is the evidence. A Manage Link for appointment A presented on B's path is
`404`, not `403`: a distinct code would confirm that B exists. Errors: `MANAGE_TOKEN_INVALID`,
`INVALID_CONFIRMATION_CODE`, `CANCELLATION_WINDOW_CLOSED`, `SLOT_UNAVAILABLE`, `NOT_FOUND`, `RATE_LIMITED`.

All four endpoints that resolve an appointment — `lookup`, `manage`, `cancel` and `reschedule` — return the
same shape, which carries **`emailOnFile`**: whether the Customer has an address on record, and never the
address. After a cancel or a reschedule it is what says a confirmation is coming, because
`NotificationEnqueuer` gates both of those emails on exactly that; on the two read endpoints nothing has been
sent and the field is simply a fact, which is why it is named for one. A Manage Link arrives by email, so an
address existed when the token was issued — but the token is valid until the appointment ends and the
Business can clear the address from the dashboard in between, so the page cannot infer it (ADR-0008). The
address itself is never returned, for the reason `confirmationSent` is not either.

**Manage Link token** — HMAC-SHA256 over `appointmentId|expiry` with a server secret, expiring at
appointment end + 24 h. It is a single-purpose capability token, not an identity: it authorises exactly one
appointment, grants nothing else, and is excluded from logs.

**POST `/public/businesses/{slug}/chat/session`** → `201`
```json
{ "conversationId": "…", "sessionToken": "…" }
```

Optionally takes `{ "manageToken": "…" }`. A valid one seeds the conversation's authority with the single
appointment it authorises, so a customer who arrived from an email can say "move this" without reciting a
Confirmation Code. A stale or foreign token is ignored rather than refused — the conversation opens with no
authority, which is what an ordinary visitor gets anyway.

**The `sessionToken` is returned exactly once.** The row stores only its SHA-256, so a read of
`ai_conversations` does not hand out the ability to continue somebody's conversation.

**POST `/public/businesses/{slug}/chat`**

```json
{ "sessionToken": "…", "message": "can I get a colour tomorrow after 5?" }
```
→ `200`
```json
{ "reply": "I have 17:00 and 18:00 with Lika tomorrow. Which suits you?",
  "conversationStatus": "ACTIVE",
  "messagesRemaining": 34,
  "appointmentCreated": null }
```

**No `conversationId` in the body, and phase 09 dropped it deliberately.** The session token already names
exactly one conversation — it is uniquely indexed — so a second identifier beside it could only agree
redundantly or disagree, and there is no useful behaviour for the disagreeing case. It is the argument the
Manage Link endpoints make about the id in their path, with the difference that a URL cannot avoid carrying
a claim and a body can simply not carry one.

`appointmentCreated` is populated only when `create_appointment` succeeded, so the UI can render a
confirmation card from **backend data** rather than parsing the model's prose. The provenance is what makes
the field a hallucination control; the casing is what makes it a public response.

It is **the same shape** `POST …/appointments` returns above — the record itself, not a second one with
matching key names — so one component renders a booking whichever door it came in through:

```json
{ "id": "…", "confirmationCode": "7QK4M2XR",
  "startsAt": "…", "endsAt": "…", "timezone": "Asia/Tbilisi",
  "service": { "name": "Colour", "durationMinutes": 150 },
  "employee": { "fullName": "Lika" },
  "price": { "amount": "220.00", "currency": "GEL" },
  "confirmationSent": false }
```

Phase 09 first shipped a second record here whose keys matched and whose types did not — `service` a bare
string beside a `BookedService`, `price` and `currency` flat beside a `Money`, and no `timezone` at all.
Same-named fields of different types are worse than differently-named ones, because a reader assumes they
agree, and a flat allow-list of key names cannot see the difference. `PublicChatTest` now compares the two
responses as structures rather than field by field.

`messagesRemaining` is counted by the server because the ceiling counts tool rows the client never sees; a
panel counting its own bubbles would be wrong, and wrong optimistically.

Errors: `AI_UNAVAILABLE`, `AI_LIMIT_REACHED`, `RATE_LIMITED`, `VALIDATION_FAILED`, and `NOT_FOUND` for a
session token this business does not know.

## 7. What is deliberately not specified yet

Exact DTO field lists for update endpoints, OpenAPI annotations, and response envelopes for rarely used
listings. These are implementation detail; the contract that matters — surfaces, tenancy rule, error codes,
and the shapes of the booking and availability payloads — is fixed above. springdoc-openapi generates the
live reference from the code.
