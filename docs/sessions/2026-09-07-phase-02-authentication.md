# Session handoff — 2026-09-07 — Phase 02 (Authentication and Tenancy)

> **Purpose.** Enough context to continue without re-reading this session. Start with §1 and §2;
> §5 is the part that will save you the most time.

---

## 1. Where the project stands

**Phase 02 is complete and every Definition-of-Done item is verified**, in the browser as well as
in the suite. Phase 03 (Business Setup) has not been started.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — the tested branch |
| Working branch | **`dev`** — all work happens here |
| Backend tests | **101**, up from 29 |
| Local state | Infrastructure in compose; backend from IntelliJ, frontend from a terminal |

An owner registers at `/register`, lands on the dashboard, survives a reload and a token rotation,
signs out, signs back in, and is returned to the page the guard interrupted. `document.cookie` is
empty on the dashboard — **no token is reachable from JavaScript**, which is the phase's headline
claim and was checked in a real browser rather than inferred.

---

## 2. What exists now

**Database** — `V2__users_and_auth.sql`: `users` (citext email), `businesses` (minimal — phase 03
adds the profile columns by `ALTER`), `memberships`, `refresh_tokens`, `business_hours`.

**Backend**
- `auth/` — `User`, `Membership`, `RefreshToken`, `Role`, `JwtService`, `RefreshTokenService`,
  `RefreshTokenFamilyRevoker`, `AuthService`, `AuthCookies`, `CookieBearerTokenResolver`, the two
  problem+json security handlers, and `web/AuthController` with the five endpoints.
- `business/` — `Business`, `BusinessHours`, `SlugService`, `BusinessProvisioningService`.
- `tenancy/` — `TenantContext`, `TenantContextHolder`, `TenantContextFilter`, `@TenantScoped`.
- `common/ids/` — `UuidV7` (pure) and `IdGenerator` (the bean that feeds it the injected `Clock`).
- `common/persistence/BaseEntity` — assigned-id `Persistable`, so `save()` is one statement.
- `common/ratelimit/` — Bucket4j filter, policy as data.

**Frontend** — `/login`, `/register`, the `(dashboard)` group with `AuthGuard` and
`DashboardShell`, `lib/auth/` session context hydrated from `/auth/me`, and transparent refresh in
`lib/api/client.ts`. **Still no token handling anywhere, and there must never be any.**

---

## 3. The decisions that will shape phase 03 onward

### The tenant is a signed claim

`business_id` is derived from the Membership at token-issue time and signed into the access token.
No request re-reads the membership, and no request path can name a tenant. The cost is bounded by
the 15-minute TTL: a membership revoked mid-window survives until the next refresh, which is the
trade-off a short access token exists to make acceptable.

**What this means for you:** inject `TenantContext` and call `businessId()`. Never take a business
id as a parameter — `TenantRepositoryShapeTest` will fail the build if you try.

### Tenant-owned repositories are marked and enforced

Annotate the repository `@TenantScoped`. Every method it declares must begin with
`findByBusinessId`, `existsByBusinessId`, `countByBusinessId` or `deleteByBusinessId`, and it may
not declare `findById`. Phase 03 adds most of the tenant-owned repositories in the codebase; they
arrive already governed.

The rule currently runs with `allowEmptyShould(true)` because phase 02 declares only one such
repository. **When phase 03 adds several, consider removing that flag** — at that point an empty
result would mean the annotation had been forgotten.

### 401 rather than 404 for unknown paths

An unauthenticated request to a path that does not exist is answered exactly like one to a path
that does. Same reasoning as the cross-tenant `404`: a status that varies with existence enumerates
what it is protecting. `UNAUTHENTICATED` was added to the published error table for this.

---

## 4. Rules that are enforced, not merely written down

Added to the phase 01 list. These already fail the build.

| Rule | Enforced by |
|---|---|
| A `@TenantScoped` repository declares only `businessId`-first methods | `TenantRepositoryShapeTest` |
| No `@TenantScoped` repository declares `findById` | `TenantRepositoryShapeTest` |
| No endpoint binds a business id from a path, query or header | `TenantRepositoryShapeTest` |
| No request DTO carries a `businessId` field | `TenantRepositoryShapeTest` |
| Validation messages are written for a person, not a developer | `RegistrationTest` |

---

## 5. Traps already paid for

**Read this before debugging anything.** Each cost real time in this session.

### 5.1 A `@Transactional` method that throws rolls back its own security response

Replay detection revoked the token family and then threw to refuse the caller — and the rollback
undid the revocation. The response said "this session was ended for security reasons" while the
stolen token stayed live. **Any state change that must survive the exception reporting it needs its
own transaction**, and it must live in a *separate bean*: a `@Transactional` method calling its own
annotated sibling gets no new transaction at all, because Spring proxies from the outside. The
annotation is silently ignored and you get the same bug back, harder to see.
`RefreshTokenFamilyRevoker` exists solely for this.

### 5.2 `citext` and `ddl-auto: validate`

The driver reports `citext` as `Types#OTHER`, which does not match the `VARCHAR` Hibernate maps a
`String` to, so the context refuses to start. **Declaring `@JdbcTypeCode(SqlTypes.OTHER)` is the
trap**: it satisfies the validator and then fails every insert with "Could not convert
`java.lang.String` to `[B`" — turning a loud startup failure into a quiet runtime one. Use
`@Column(columnDefinition = "citext")` instead. Same class of problem for `char(3)`, which needs
`@JdbcTypeCode(SqlTypes.CHAR)` — that one is genuinely a type-code mismatch and the fix does work.

### 5.3 Never run `pnpm build` while `pnpm dev` is running

Both write `frontend/.next`. The production build leaves the dev server returning 404 with
`text/plain` for every chunk; the page renders with no CSS and the console fills with "Refused to
apply style". It does not recover on its own. Stop the dev server, `rm -rf .next`, start it again.

### 5.4 Browsing the frontend's own port is now a broken app, not a quirk

Opening `localhost:9082` instead of `localhost:9080` means Next.js serves the pages and nothing
routes `/api/*`, so every call 404s inside React and surfaces as a stack trace that says nothing
about the cause. From this phase it is worse than an inconvenience: two origins means cookie
authentication cannot work at all, so even a reachable API would not keep you signed in.

`DevOriginGuard` now catches it — a development-only banner naming the right URL, with the current
path preserved. It reads both ports from the repository's `.env` through `next.config.ts`, the same
way the backend reads that file rather than trusting its launcher (§5.5 of the phase 01 handoff), so
it cannot start naming a port Caddy has stopped using.

It compares the **port**, not the whole origin: reaching Caddy over a LAN address or a hostname alias
is legitimate, and comparing origins would flag it.

A production build contains none of its copy or logic — verified by building and grepping, not
assumed. What survives is an empty function, because a client component's module reference is
registered in the client manifest whether or not the server renders it.

### 5.5 Gradle 8.14 cannot run on Java 25

`./gradlew` from a shell dies with `IllegalArgumentException: 25.0.4.1` out of the embedded Kotlin
compiler. The system JDK on this machine is 25, so **the shell build does not work out of the box** —
which is why it had not been noticed: IntelliJ runs the backend on its own JDK.

There is a JDK 21 at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (the one
IntelliJ uses; `/usr/libexec/java_home` does not list it). Export that as `JAVA_HOME` before running
Gradle from a terminal. **CI is unaffected** — it pins JDK 21. Upgrading the wrapper to Gradle 9.x
would fix it properly and is worth folding into phase 11.

### 5.6 Spring picks no constructor when a class declares two

`NoSuchMethodException: <init>()`. The implicit single-constructor rule does not apply. `@Autowired`
the one Spring should use. `SlugService` and `IdGenerator` both have a second constructor so a test
can inject deterministic randomness.

### 5.7 `useSearchParams` needs a `Suspense` boundary

Or `next build` fails on a statically rendered page. The login form reads `?next=`, so its page
wraps it.

### 5.8 Integration tests that drive real HTTP cannot rely on rollback

The request runs on the server's own thread and commits. `DatabaseCleaner` truncates every
application table, discovering them from `information_schema` so a phase that adds a table does not
also have to remember to add it to a list.

---

## 6. Open items

- **Rate-limit buckets are in memory**, keyed by policy and address, and never evicted. Correct for
  one instance and documented as the first thing to externalise ([06-security.md](../06-security.md)
  §15). `RateLimitFilter.reset()` is package-private and exists only so the rate-limit tests do not
  inherit each other's spent budgets.
- **`business.timezone` defaults to `UTC` and `currency` to `USD`** at registration. UTC is the
  honest placeholder — visibly not a guess about where the owner is, unlike the server's zone, which
  would be one. Phase 03 makes both editable, and ADR-0003 requires the UI to confirm before a
  timezone change because it moves every displayed time.
- **Node 20 deprecation warnings in CI** — carried over from phase 01, still warnings only.
- **Branch protection is still not enabled.** Unchanged from phase 01: offered, not yet accepted.
- **`make seed`** still prints a placeholder; seed data ships in phase 11.

---

## 7. Next: Phase 03 — Business Setup

Read `docs/phases/phase-03-business-setup.md` in full. What actually matters:

1. **Timezone becomes load-bearing.** Validate against `ZoneId.getAvailableZoneIds()` on write, and
   the UI must confirm before saving because changing it moves every displayed time without moving
   a single stored instant (ADR-0003).
2. **Hours are replaced a whole week at a time**, so a partial edit cannot leave the week
   inconsistent. `business_hours` exists already, seeded Mon–Fri 09:00–17:00 by registration; V3
   adds its `UNIQUE (business_id, day_of_week, opens_at)` constraint, which is deliberately absent
   until the endpoint that respects it exists.
3. **The onboarding checklist is derived, never persisted**, so it cannot drift from reality.
4. **This is the first phase with tenant-scoped resource endpoints**, which means it is the first
   phase that adds isolation probes — one per endpoint group, in the same commit as the endpoint.
