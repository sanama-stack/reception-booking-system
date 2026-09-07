# Phase 02 — Authentication and Tenancy

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
- [ ] Password hashing and verification
- [ ] JWT issue, parse, expiry
- [ ] Refresh rotation: new token valid, old revoked
- [ ] Replay detection revokes the whole family
- [ ] Slug derivation: unicode, punctuation-only, collisions

### Integration
- [ ] Registration creates user + business + membership + 5 hour rows in one transaction
- [ ] Forced failure at the last step leaves zero rows
- [ ] Duplicate email → `409 EMAIL_TAKEN`, nothing created
- [ ] Concurrent same-email registration: one succeeds, one `409`
- [ ] Login sets both cookies, `httpOnly`
- [ ] Wrong password and unknown email return identical responses
- [ ] Expired access token → `401 TOKEN_EXPIRED`
- [ ] Refresh rotates; replaying the old token → `401 TOKEN_REUSED` and family revoked
- [ ] Logout is idempotent
- [ ] `/auth/me` requires authentication
- [ ] Login rate limit returns `429` with `Retry-After`

### Isolation (start the suite here)
- [ ] `TenantContext` resolves from the membership, never from the request
- [ ] ArchUnit: no tenant repository method lacking a `businessId` parameter
- [ ] ArchUnit: `Instant.now()` appears nowhere outside the `Clock` configuration

## Definition of Done

- [ ] An owner registers, is logged in, and reaches the dashboard shell
- [ ] Sessions survive a page reload and access-token expiry
- [ ] No token is readable from JavaScript
- [ ] Passwords appear nowhere but as BCrypt hashes
- [ ] `TenantContext` is in place and enforced by ArchUnit
- [ ] All tests above pass in CI

## Checklist

### Database
- [ ] Write `V2__users_and_auth.sql`
- [ ] `users`, `memberships`, `refresh_tokens`, minimal `businesses`, `business_hours`
- [ ] Unique constraints: email, slug, `(user_id, business_id)`
- [ ] Role and day-of-week `CHECK` constraints
- [ ] Indexes on token hash and `(user_id, revoked_at)`

### Backend
- [ ] JPA entities for the five tables
- [ ] Repositories, tenant-shaped where applicable
- [ ] `PasswordEncoder` bean
- [ ] `JwtService`
- [ ] `RefreshTokenService` with rotation and family revocation
- [ ] `SlugService`
- [ ] `AuthService.register` — atomic, with default hours
- [ ] `AuthService.login` / `refresh` / `logout`
- [ ] `AuthController`
- [ ] Cookie writer (httpOnly, SameSite, profile-dependent Secure)
- [ ] `SecurityFilterChain` with explicit public paths
- [ ] `TenantContext` + filter
- [ ] Method security on writes
- [ ] Rate limits on login and register
- [ ] Error codes: `EMAIL_TAKEN`, `INVALID_CREDENTIALS`, `TOKEN_EXPIRED`, `TOKEN_REUSED`

### Frontend
- [ ] `/register` with validation and server error display
- [ ] `/login`
- [ ] Dashboard layout with auth guard
- [ ] Sidebar and header with sign-out
- [ ] Transparent refresh interceptor
- [ ] User/business context from `/auth/me`
- [ ] Loading and error states on both forms

### Testing
- [ ] All unit tests above
- [ ] All integration tests above
- [ ] ArchUnit rules for tenancy and `Clock`
