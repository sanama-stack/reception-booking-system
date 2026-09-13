# Phase 01 — Foundation

> **Status: complete.** Verified on 2026-09-07 — `make up` brings the infrastructure up and both
> applications run from the IDE against it, `localhost:9080` serves the frontend and `/api/health`
> the backend from one origin (`make up-all` does the same with everything in containers),
> Flyway applies `V1` once and is a no-op on the second boot, and the backend suite (26 tests) plus
> the frontend lint/typecheck/format/build all pass. CI is green on its first run: backend,
> frontend and the compose smoke test, the last of which boots all five containers on a clean
> runner and asserts both applications answer on the single origin.

## Goal

A clean checkout runs the entire system with one command: Postgres, Spring Boot, Next.js, Caddy and Mailpit,
all reachable through a **single origin**, with Flyway wired and CI green on an empty-but-real test suite.

## Scope

**In:** repository layout, Gradle and Next.js skeletons, Docker Compose, Caddy single-origin routing, Flyway
baseline, `Clock` bean, error-handling scaffold, structured logging, health endpoint, CI pipeline,
`.env.example`, Makefile.

**Out:** every domain concept. No entities beyond what Flyway needs to exist.

## Dependencies

None. This is the root.

## Why the full topology now

The single-origin cookie decision ([ADR-0001](../adr/0001-self-issued-jwt-over-keycloak.md),
[06-security.md](../06-security.md) §2) is painful to retrofit: doing it later means revisiting every fetch
call, the auth flow and the CI setup. It costs one small container today.

## Technical work

### Repository

```text
/
├── Makefile                 up, down, logs, seed, test, migrate
├── docker-compose.yml
├── .env.example
├── CONTEXT.md
├── README.md
├── docs/
├── infra/
│   ├── caddy/Caddyfile
│   └── postgres/init.sql    extensions only
├── backend/
│   ├── build.gradle.kts
│   └── src/main/{java,resources}
└── frontend/
    ├── package.json
    └── src/app
```

### Backend skeleton

Java 21, Spring Boot 3.x. Starters: `web`, `validation`, `data-jpa`, `security`, `actuator`, `mail`,
Flyway, PostgreSQL driver, springdoc-openapi. Test: JUnit 5, AssertJ, Testcontainers.

Packages created empty but real, matching [02-product-architecture.md](../02-product-architecture.md) §3.

Configuration: `application.yml` plus `application-{local,test,prod}.yml`. Every value that differs by
environment comes from an environment variable with a local default.

### Caddy

```caddyfile
:8080 {
  handle /api/* { reverse_proxy backend:8081 }
  handle        { reverse_proxy frontend:3000 }
  header {
    X-Content-Type-Options nosniff
    Referrer-Policy strict-origin-when-cross-origin
    X-Frame-Options DENY
  }
}
```

### Cross-cutting scaffolding

- **`Clock` bean** — `Clock.systemUTC()` in production, fixed in tests. `Instant.now()` is banned from this
  commit onward; add a Checkstyle/ArchUnit rule so it is enforced rather than remembered.
- **Error handling** — one `@RestControllerAdvice` producing RFC 9457 `problem+json` with the `code`
  extension, plus the `ErrorCode` enum seeded with `VALIDATION_FAILED`, `NOT_FOUND`, `FORBIDDEN`,
  `RATE_LIMITED` and an internal fallback.
- **Logging** — JSON encoder, request-id filter, and the redaction filter listed in
  [06-security.md](../06-security.md) §10, wired now so no later phase can forget it.

## Database work

- `V1__extensions.sql`: `CREATE EXTENSION IF NOT EXISTS citext;` and `btree_gist;`
- Flyway configured with `validate-on-migrate`, no `clean` in any non-local profile.

## Backend work

- Application entry point, health endpoint reporting database and mail connectivity.
- `SecurityFilterChain` permitting everything for now, with a `TODO` referencing phase 02 — an explicit
  placeholder rather than an accidental hole.
- springdoc serving `/api/docs`.

## Frontend work

- Next.js App Router, TypeScript strict, Tailwind, ESLint, Prettier.
- Root layout, a placeholder landing page, and the design-system primitives the dashboard will need
  (`Button`, `Input`, `Card`, `Table`, `EmptyState`, `Spinner`, `ErrorState`, `Toast`).
- `lib/api/client.ts`: typed fetch wrapper with `credentials: 'include'`, normalising `problem+json` into
  `{ code, message, fieldErrors }`.
- `lib/time/`: formatting helpers that **require** an explicit timezone argument, so a business-timezone
  bug is a compile error rather than a runtime surprise.

## Testing

- One integration test that boots the context against Testcontainers Postgres and asserts Flyway ran.
- One test asserting the health endpoint returns `UP`.
- Frontend: lint, typecheck and build all pass.

## Definition of Done

- [x] `make up` starts five containers from a clean clone with no manual steps
- [x] `http://localhost:9080/` serves the frontend and `/api/health` the backend, same origin
- [x] Flyway applies `V1` on first boot; a second boot is a no-op
- [x] CI runs backend build, frontend lint/typecheck/build, and passes
- [x] `.env.example` lists every variable the compose file reads
- [x] `README.md` documents prerequisites and the one command
- [x] No secret is committed

## Checklist

### Infrastructure
- [x] Create repository layout
- [x] Write `docker-compose.yml` (postgres, backend, frontend, caddy, mailpit)
- [x] Add named volume for Postgres data
- [x] Add healthchecks and `depends_on: condition: service_healthy`
- [x] Write `infra/caddy/Caddyfile` with `/api/*` split and security headers
- [x] Write backend `Dockerfile` (multi-stage, JRE base)
- [x] Write frontend `Dockerfile` (multi-stage, standalone output)
- [x] Write `Makefile`: `up`, `down`, `logs`, `test`, `migrate`, `seed`
- [x] Write `.env.example` with dummy values for every variable
- [x] Add `.gitignore` covering `.env`, build output, `node_modules`

### Backend
- [x] Initialise Gradle project, Java 21 toolchain
- [x] Add dependencies and configure the test task
- [x] Create the empty package structure
- [x] Write `application.yml` and the three profile files
- [x] Configure the datasource from environment variables
- [x] Configure Flyway
- [x] Add the `Clock` bean and an ArchUnit rule banning `Instant.now()` elsewhere
- [x] Add `ErrorCode` enum and `problem+json` `@RestControllerAdvice`
- [x] Add request-id filter and JSON logging encoder
- [x] Add the log redaction filter
- [x] Add the health endpoint
- [x] Add placeholder `SecurityFilterChain` with a `TODO(phase-02)`
- [x] Configure springdoc at `/api/docs`

### Database
- [x] Write `V1__extensions.sql`
- [x] Verify `btree_gist` is available in the chosen Postgres image

### Frontend
- [x] Initialise Next.js with TypeScript strict mode
- [x] Configure Tailwind, ESLint, Prettier
- [x] Build the design-system primitives listed above
- [x] Write the typed API client with `problem+json` normalisation
- [x] Write timezone-explicit formatting helpers
- [x] Add a placeholder landing page

### CI
- [x] GitHub Actions workflow on push and pull request
- [x] Backend job: `./gradlew build`
- [x] Frontend job: lint, typecheck, build
- [x] Cache Gradle and pnpm
- [x] Fail the build on any test failure

### Testing
- [x] Testcontainers base class for integration tests
- [x] Context-loads test asserting migrations applied
- [x] Health endpoint test


## Notes from the build

Four things worth carrying forward, each of which cost time here and would cost more later:

- **Testcontainers 2.0.5, not 1.20.x.** Docker Desktop 29 rejects the unversioned `/info` request
  older `docker-java` builds send, so every integration test failed with "Could not find a valid
  Docker environment". The version is pinned in `build.gradle.kts` for this reason.
- **The extensions are created by `V1`, not by a container init script.** `withInitScript` is broken
  in Testcontainers 2.0.x, and dropping it turned out to be the better arrangement: the migration
  compose relies on is now the one under test.
- **Two log formats, one redaction rule.** JSON everywhere (docs/06-security.md §10) made the
  console unreadable in an IDE, which is where the backend is now actually run. The `local` and
  `test` profiles get Spring Boot's familiar colourised console; every other profile gets JSON. The
  trap is that only the JSON encoder redacts on its own — a pretty console would otherwise have
  been the single place a secret could escape. `PiiMessageConverter` and `PiiThrowableConverter`
  bind the same `PiiValueMasker` to `%m` and `%wEx`, registered against every message and throwable
  conversion word so a pattern written later is covered without anyone remembering to. Asserted by
  `ConsoleRedactionTest`, which also fails on Logback startup warnings — a warning nobody needs is
  how people learn to ignore the ones that matter.
- **Redaction rules live in Java, not in the Logback XML.** The configurator silently strips
  backslashes out of an XML regex, which turned `\s` into `s` and produced an unparseable character
  class — a failure that appeared only outside the `test` profile, as a container that refused to
  start. `PiiValueMasker` is compiled and unit-tested instead, and `JsonLoggingConfigurationTest`
  loads the production appender definition so the same class of failure cannot hide again.
- **Unmatched `/api` paths reach the static resource handler**, which writes Spring's own
  `about:blank` body. `handleNoResourceFoundException` is overridden and
  `spring.web.resources.add-mappings` is `false`, so the claim that one advice writes every error
  body is actually true.

### The .gitignore that would have broken every clone

Publishing surfaced a defect nothing local could have caught: `*.jar` silently excluded
`backend/gradle/wrapper/gradle-wrapper.jar`, and the `!gradle/wrapper/gradle-wrapper.jar` exception
written to save it never fired, because a pattern without a leading `**/` is anchored to the
directory holding the `.gitignore` rather than matching at any depth. On the machine that wrote it
everything worked; on any clone `./gradlew` would have failed at once and CI would have died at
checkout. The lesson is that an ignore rule is only proven by a clone, so the fix was verified by
cloning the commit to a temporary directory and running `./gradlew --version` and `compileJava`
there rather than by re-reading the pattern.

### Deviation: the applications run from the IDE, not from compose

The checklist says compose starts five containers. It now starts three — Postgres, Mailpit and
Caddy — and the backend and frontend run on the developer's machine, launched from
[.vscode/launch.json](../../.vscode/launch.json). Containerising the two applications made every
code change a rebuild, which is the wrong trade for the ten phases still to come.

What did **not** change is the part that matters: **Caddy stays in compose**, proxying to the host.
The single origin is load-bearing, not cosmetic — cookie authentication arrives in phase 02 and
does not work across two origins (ADR-0001, [06-security.md](../06-security.md) §2). Running the
applications on their own ports without Caddy would have quietly broken that.

The five-container topology of [02-product-architecture.md](../02-product-architecture.md) §1 is
still real and still exercised: `docker-compose.apps.yml` layers the two application containers
back in, `make up-all` runs it, and that is the shape the CI smoke test asserts and phase 11
deploys. The Dockerfiles therefore cannot rot.

One consequence worth knowing: the host ports now matter. `SERVER_PORT` and `PORT` in `.env` are
the single source for each application's port, and compose derives Caddy's upstreams from them, so
the two cannot drift.

**Ports live in one block, 9080-9085.** The conventional 3000/8080/5432 defaults collided with
another project running on the same machine, which is the normal case rather than the exception.
The block is contiguous so the whole thing can be checked with one `lsof`, and `.env` is the single
place to change any of them — with one honest exception: Next.js takes its port only from the
command line, never from an env file (verified, not assumed), so the frontend's port is pinned in
`frontend/package.json` and `make up` fails loudly if it disagrees with `FRONTEND_PORT`.

**Configuration must not depend on the launcher.** The first attempt put the development
environment in `.vscode/launch.json` via `envFile`, which broke immediately: the Spring Boot
Dashboard and the Run button above `main()` do not read `launch.json`, so the application started
with no profile, on the default port, and authenticated against whatever happened to be on
`localhost:5432` — a different project's database. Each launcher also uses a different working
directory. The fix is that the application reads `.env` itself: `application-local.yml` imports it
with `spring.config.import` from both plausible working directories, both `optional:`, and
`spring.profiles.default: local` means an IDE that sets nothing still lands in the development
profile rather than a nameless one. Verified by launching the way the IDE does — repo root, plain
`java -cp`, no environment variables at all.

### Beyond the checklist

Three additions the checklist did not ask for, each cheap now and awkward later:

- `LayeringTest` declares the architecture rules from
  [02-product-architecture.md](../02-product-architecture.md) §2 while the packages are still empty,
  so the commit that fills them is already governed.
- `PiiValueMaskerTest` turns the redaction list in [06-security.md](../06-security.md) §10 from a
  promise into a check.
- `SecretsGuard` refuses to start the `prod` profile while `JWT_SECRET`, `MANAGE_LINK_SECRET` or
  `DB_PASSWORD` still holds its `local-dev-only-` default — three of the five secrets
  [06-security.md](../06-security.md) §9 lists, the other two deliberately excluded.
