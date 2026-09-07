# Phase 02 — Authentication and Tenancy

> **Status: complete.** Verified on 2026-09-07 — an owner registers through the browser and lands
> on the dashboard, the session survives a reload and a rotation, sign-out and sign-in work, the
> auth guard returns an interrupted visitor to where they were going, and `document.cookie` is
> empty on the dashboard: no token is reachable from JavaScript. The backend suite is 101 tests
> (29 before this phase), including registration atomicity under a forced failure, the concurrent
> same-email race, refresh replay revoking a family, and the rate limits.

## Goal

An owner can register, log in and stay logged in; and the mechanism by which **every** later query is
confined to one tenant exists, is tested, and is impossible to bypass.

## Scope

**In:** `users`, `memberships`, `refresh_tokens`, `businesses` (minimal — created by registration),
password hashing, JWT issuance, refresh rotation with replay detection, cookie transport, `TenantContext`,
role-based method security, login/register screens, dashboard shell with an auth guard.

**Out:** business configuration (phase 03), staff login (out of MVP entirely).

## Dependencies

Phase 01 — needs Flyway, the single-origin proxy and the error scaffold.

## Technical work

The two things that matter here are **atomic registration** and **the tenancy seam**. Everything else is
conventional.

### Registration atomicity

One transaction creates `User`, `Business`, `Membership(OWNER)` and default Mon–Fri 09:00–17:00 business
hours. A failure at any step must leave zero rows — tested by forcing a failure at the last step and
asserting the user table is empty.

Default hours exist so a new account is never a blank slate the owner has to decode.

### The tenancy seam

```java
public interface TenantContext { UUID businessId(); }
```

Resolved by a filter from the authenticated Membership and held per-request. From this phase on:

- Repositories for tenant-owned entities expose **only** `findByBusinessIdAndId(...)`-shaped methods.
  `findById` is not defined, so forgetting the tenant filter is not expressible.
- Missing-because-other-tenant and missing-because-absent both raise `NotFoundException` → `404`.

Add an **ArchUnit rule**: no repository interface for a tenant-owned entity may declare a method whose name
does not begin with `findByBusinessId`, `existsByBusinessId`, `countByBusinessId` or `deleteByBusinessId`.
The rule is what makes this a guarantee rather than a convention.

## Database work

`V2__users_and_auth.sql`:
- `users` with `citext` email, `UNIQUE(email)`
- `memberships` with `UNIQUE(user_id, business_id)` and the role `CHECK`
- `refresh_tokens` with `token_hash`, `family_id`, indexes on `(user_id, revoked_at)` and `(token_hash)`
- `businesses` — the columns registration needs now (`id`, `name`, `slug UNIQUE`, `timezone`, `currency`,
  timestamps); phase 03 adds the rest
- `business_hours` — needed for the default week

## Backend work

- `PasswordEncoder` — BCrypt cost 12
- `JwtService` — HS256, 15-minute access tokens, secret from the environment
- `RefreshTokenService` — issue, hash, rotate, detect replay, revoke a family
- `AuthService` — register (atomic), login, refresh, logout
- `SlugService` — derive, sanitise, collision-suffix; handles unicode and punctuation-only names
- `AuthController` — the five endpoints from [04-api-overview.md](../04-api-overview.md) §4
- `SecurityFilterChain` — cookie-bearing JWT resource server, public paths declared explicitly
- `TenantContext` + resolution filter
- `@PreAuthorize("hasRole('OWNER')")` on write endpoints, applied at the service layer
- Login and register rate limits (Bucket4j)

## Frontend work

- `/login`, `/register` with field-level validation surfacing server `fieldErrors`
- Dashboard shell: sidebar, header, sign-out
- Auth guard in the dashboard layout, redirecting to `/login`
- Transparent refresh: on `401 TOKEN_EXPIRED`, call `/auth/refresh` once and retry; on failure, redirect
- `/auth/me` hydrating a user/business context
- Empty dashboard home with a placeholder for the phase-03 onboarding checklist

**No token handling code.** Cookies do it. If the frontend touches a token in this phase, something is wrong.

## Testing

### Unit
- [x] Password hashing and verification
- [x] JWT issue, parse, expiry
- [x] Refresh rotation: new token valid, old revoked
- [x] Replay detection revokes the whole family
- [x] Slug derivation: unicode, punctuation-only, collisions

### Integration
- [x] Registration creates user + business + membership + 5 hour rows in one transaction
- [x] Forced failure at the last step leaves zero rows
- [x] Duplicate email → `409 EMAIL_TAKEN`, nothing created
- [x] Concurrent same-email registration: one succeeds, one `409`
- [x] Login sets both cookies, `httpOnly`
- [x] Wrong password and unknown email return identical responses
- [x] Expired access token → `401 TOKEN_EXPIRED`
- [x] Refresh rotates; replaying the old token → `401 TOKEN_REUSED` and family revoked
- [x] Logout is idempotent
- [x] `/auth/me` requires authentication
- [x] Login rate limit returns `429` with `Retry-After`

### Isolation (start the suite here)
- [x] `TenantContext` resolves from the membership, never from the request
- [x] ArchUnit: no tenant repository method lacking a `businessId` parameter
- [x] ArchUnit: `Instant.now()` appears nowhere outside the `Clock` configuration

## Definition of Done

- [x] An owner registers, is logged in, and reaches the dashboard shell
- [x] Sessions survive a page reload and access-token expiry
- [x] No token is readable from JavaScript
- [x] Passwords appear nowhere but as BCrypt hashes
- [x] `TenantContext` is in place and enforced by ArchUnit
- [x] All tests above pass in CI

## Checklist

### Database
- [x] Write `V2__users_and_auth.sql`
- [x] `users`, `memberships`, `refresh_tokens`, minimal `businesses`, `business_hours`
- [x] Unique constraints: email, slug, `(user_id, business_id)`
- [x] Role and day-of-week `CHECK` constraints
- [x] Indexes on token hash and `(user_id, revoked_at)`

### Backend
- [x] JPA entities for the five tables
- [x] Repositories, tenant-shaped where applicable
- [x] `PasswordEncoder` bean
- [x] `JwtService`
- [x] `RefreshTokenService` with rotation and family revocation
- [x] `SlugService`
- [x] `AuthService.register` — atomic, with default hours
- [x] `AuthService.login` / `refresh` / `logout`
- [x] `AuthController`
- [x] Cookie writer (httpOnly, SameSite, profile-dependent Secure)
- [x] `SecurityFilterChain` with explicit public paths
- [x] `TenantContext` + filter
- [x] Method security on writes
- [x] Rate limits on login and register
- [x] Error codes: `EMAIL_TAKEN`, `INVALID_CREDENTIALS`, `TOKEN_EXPIRED`, `TOKEN_REUSED`

### Frontend
- [x] `/register` with validation and server error display
- [x] `/login`
- [x] Dashboard layout with auth guard
- [x] Sidebar and header with sign-out
- [x] Transparent refresh interceptor
- [x] User/business context from `/auth/me`
- [x] Loading and error states on both forms

### Testing
- [x] All unit tests above
- [x] All integration tests above
- [x] ArchUnit rules for tenancy and `Clock`


## Notes from the build

### Two bugs the tests found, both invisible by inspection

**Replay detection revoked the family inside the transaction that then threw to refuse the
caller** — so the rollback undid the revocation. The response said "this session was ended for
security reasons" while the stolen token stayed live: the security response was theatre. The
revocation now commits in a transaction of its own, in a *separate bean*, because a
`@Transactional` method calling its own annotated sibling gets no new transaction at all — Spring's
proxying means the annotation is silently ignored, which is the same bug wearing a disguise.

**The `citext` mapping.** The driver reports `citext` as `Types#OTHER`, which does not match the
`VARCHAR` Hibernate maps a `String` to, so `ddl-auto: validate` refused to start. Declaring the JDBC
type as `OTHER` satisfied the validator and then failed *every insert* — a fix that turns a loud
startup failure into a quiet runtime one. Naming the column type in `columnDefinition` instead
satisfies the validator while leaving the value bound as an ordinary string.

### Decisions taken

**The tenant is a signed claim, not a per-request lookup.** `business_id` is derived from the
Membership at token-issue time and signed into the access token, so no request re-reads the
membership and no request path can name a tenant. The cost is bounded by the 15-minute TTL: a
membership revoked mid-window survives until the next refresh. That is the trade-off a short access
token exists to make acceptable, and it is the reason the TTL is short.

**An unauthenticated request to an unknown path returns `401`, not `404`.** The same reasoning that
makes a cross-tenant lookup a `404` rather than a `403` ([06-security.md](../06-security.md) §3): a
status that varies with existence is a way to enumerate the thing it is protecting. Phase 01's test
asserted `404` here, and it was right to at the time — nothing was protected yet. It now asserts the
deliberate contract. A new code, `UNAUTHENTICATED`, is added to
[04-api-overview.md](../04-api-overview.md) §3 so the client can tell "refresh me" from "sign in
again"; getting that distinction wrong produces either a refresh loop or a user bounced to the login
screen every fifteen minutes.

**Rate limits needed `forward-headers-strategy`.** Caddy is the only ingress, so without it every
request appears to come from Caddy and the per-IP limits collapse into one global bucket — a limit
that locks out real users while stopping nobody. The filter still reads the peer address; the
decision to trust the proxy is one visible, revocable line of configuration rather than a header
read buried in a filter.

**Transparent refresh is single-flight.** A screen firing five requests on mount would otherwise
send five refreshes, and because every refresh rotates, four of them would present a token the
server had just revoked — indistinguishable from a stolen token, ending the session the refresh was
meant to save. Sharing the in-flight promise is a correctness requirement here, not an optimisation.

**Ids are UUID v7 generated in the application**, from the injected `Clock`, so the ban on ambient
time holds even in the id generator. Entities extend a `BaseEntity` implementing `Persistable`,
because an assigned id would otherwise make Spring Data issue a `SELECT` before every `INSERT`.

### Traps

- **Never run `pnpm build` while `pnpm dev` is running.** Both write `.next`, and the production
  build leaves the dev server serving 404s for every chunk with a `text/plain` MIME type. The page
  renders unstyled and the console is full of "Refused to apply style". The only fix is to stop the
  dev server, delete `.next`, and start it again. Cost this session: one confused debugging detour.
- **`useSearchParams` needs a `Suspense` boundary** in a statically rendered page, or `next build`
  fails. The login form reads `?next=`, so its page wraps it.
- **A class with two constructors needs `@Autowired` on the one Spring should use.** The implicit
  single-constructor rule does not apply, and the failure is a `NoSuchMethodException` for a
  no-arg constructor that was never meant to exist.

### Left for later, deliberately

- **No tenant-scoped resource endpoint exists yet**, so there is nothing to probe with another
  tenant's id. `TenantRepositoryShapeTest` is declared now with `allowEmptyShould(true)`, following
  the phase 01 precedent, so phase 03 — which adds most of them — is governed from its first commit.
  The isolation *suite* starts in phase 03 and is completed reflectively in phase 11.
- **`@PreAuthorize` is wired but ungated.** `@EnableMethodSecurity` is on and the `role` claim maps
  to a `ROLE_OWNER` authority, but phase 02 has no role-gated endpoint to annotate — the auth
  endpoints are public or self-scoped. Phase 03's writes are the first.
