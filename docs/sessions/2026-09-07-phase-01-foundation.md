# Session handoff — 2026-09-07 — Phase 01 (Foundation)

> **Purpose.** Enough context to continue without re-reading this session. Start with §1 and §2;
> §5 is the part that will save you the most time.

---

## 1. Where the project stands

**Phase 01 is complete and every Definition-of-Done item is verified**, including CI, which is green
on its first real run. Phase 02 (Authentication and Tenancy) has not been started.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — the tested branch |
| Working branch | **`dev`** — all work happens here (see §7) |
| CI | Green: backend (29 tests), frontend, compose smoke test |
| Local state | Infrastructure runs in compose; both applications run from the IDE |

Nothing in `docs/` other than `phase-01-foundation.md`, `02-product-architecture.md` §1 and this
folder has been modified. The specification is intact; the deviations below are recorded in the
phase document rather than applied silently to the design docs.

---

## 2. How to run it

```bash
make up                       # Postgres, Mailpit, Caddy
# then, from the IDE: backend ReceptionApplication, frontend `pnpm dev`
```

Open **http://localhost:9080** — never 9081 or 9082 (see §3).

| Port | |
|---|---|
| **9080** | **Caddy — the only origin a browser should use** |
| 9081 | backend |
| 9082 | frontend |
| 9083 | Mailpit web UI |
| 9084 | Mailpit SMTP |
| 9085 | Postgres |

`make up-all` instead runs everything in containers — the five-container deployment topology, and
what CI smoke-tests. No hot reload, so it is not for development.

---

## 3. Decisions taken this session

Four departures from what phase 01 literally specified. Each is recorded with its reasoning in
`docs/phases/phase-01-foundation.md`.

### 3.1 The applications run from the IDE, not from compose

Compose starts three containers (Postgres, Mailpit, Caddy). `docker-compose.apps.yml` layers the
backend and frontend back in for `make up-all`. Containerising the applications made every code
change an image rebuild.

**Caddy deliberately stayed in compose**, proxying to the host. The single origin is load-bearing,
not cosmetic: phase 02's cookie authentication does not work across two origins
(ADR-0001, `06-security.md` §2, §13).

### 3.2 Caddy stays — this was asked for explicitly

Removing Caddy was investigated and **rejected by the project owner**. For the record, it was
verified to be technically possible: a Next.js `rewrites` proxy forwards `/api` to the backend and
does preserve `Set-Cookie` with `HttpOnly; SameSite=Lax`. Do not revisit this without asking; the
answer was "no, don't remove it."

### 3.3 Ports occupy 9080–9085

The conventional 3000/8080/5432 defaults collided with **another project the owner runs on the same
machine, which holds 8081, 3000, 3001 and 5432. Those are reserved — do not take them.** The block
is contiguous so it can be checked in one command:

```bash
lsof -nP -iTCP:9080-9085 -sTCP:LISTEN
```

`.env` is the single source for every port. The one exception is the frontend's own port: Next.js
ignores `PORT` from env files (verified, §5.6), so it is pinned in `frontend/package.json` and
`make up` fails if it disagrees with `FRONTEND_PORT`.

### 3.4 Two log formats, one redaction rule

`local` and `test` get Spring Boot's familiar console; every other profile gets JSON. Redaction
applies to both — see §5.3.

---

## 4. What exists in the code

Everything below is real and tested. Every package in the module map exists with a
`package-info.java` describing what will live there and in which phase.

**Backend** (`dev.reception`)
- `common/time/ClockConfig` — the only `Clock`. `Instant.now()` and the whole `now()` family are
  banned outside it, enforced by `NoAmbientClockTest`.
- `common/error/` — `ErrorCode`, `ApiException`, `FieldError`, and one `GlobalExceptionHandler`
  producing RFC 9457 `problem+json`. **Every** error body comes from here (§5.4).
- `common/logging/` — `RequestIdFilter`, `PiiValueMasker` and the two pattern converters.
- `common/config/` — `SecurityConfig` (permits everything, `TODO(phase-02)`), `OpenApiConfig`,
  `SecretsGuard` (refuses to start `prod` while a secret holds its `local-dev-only-` default).
- `common/web/HealthController` — `/api/health`, checks the database and SMTP by *using* them.

**Frontend**
- `components/ui/` — Button, Card, Input, Table, EmptyState, ErrorState, Spinner, Toast.
- `lib/api/client.ts` — typed fetch, `credentials: 'include'`, normalises `problem+json` into
  `{ code, message, fieldErrors }`. **No token handling, and there must never be any.**
- `lib/time/` — every helper *requires* an explicit IANA timezone (ADR-0003). ESLint forbids
  `Intl.DateTimeFormat` and `toLocale*String` outside this module.

**Tests** — 29, all passing, against real Postgres **and** real Mailpit in Testcontainers.
`IntegrationTest` is the base class. Never H2 (`08-testing-strategy.md` §3).

---

## 5. Traps already paid for

**Read this section before debugging anything.** Each cost real time in this session.

### 5.1 Testcontainers must be 2.0.5 or newer
Docker Desktop 29 rejects the unversioned `/info` request that older `docker-java` builds send.
Symptom: *every* integration test fails with "Could not find a valid Docker environment". The
version is pinned in `build.gradle.kts` for this reason. Do not downgrade.

### 5.2 `withInitScript` is broken in Testcontainers 2.0.x
It throws `ClassNotFoundException: ...shaded...IOUtils`. The extensions are created by
`V1__extensions.sql` instead, which is better anyway: the migration compose relies on is the one
under test.

### 5.3 Redaction rules live in Java, never in the Logback XML
The Logback configurator silently strips backslashes out of an XML regex, turning `\s` into `s` and
producing an unparseable character class. It failed only outside the `test` profile — as a
container that refused to start. `PiiValueMasker` is compiled and unit-tested instead, and bound to
both output formats:
- JSON → the encoder's masking decorator
- console → `PiiMessageConverter` (`%m`) and `PiiThrowableConverter` (`%wEx`)

Both are registered against *every* message and throwable conversion word, so a pattern written
later is covered without anyone remembering. `ConsoleRedactionTest` also fails on Logback startup
warnings. **If you add a log appender, redaction is your problem — check it.**

### 5.4 Unmatched `/api` paths bypass the exception handler by default
They reach Spring's static resource handler, which writes its own `about:blank` body. Fixed by
overriding `handleNoResourceFoundException` *and* setting `spring.web.resources.add-mappings: false`.
Without both, the claim that one advice writes every error body is false.

### 5.5 Configuration must not depend on the launcher
`.vscode/launch.json` with `envFile` does **not** work: the Spring Boot Dashboard and the Run button
above `main()` ignore `launch.json`, and each launcher uses a different working directory. The
application reads `.env` itself — `application-local.yml` imports it via `spring.config.import` from
both plausible working directories, both `optional:`, and `spring.profiles.default: local` means an
IDE that sets nothing still lands in the development profile.

Verified by launching the way the IDE does: repo root, plain `java -cp`, `env -i`, no variables.
**If you add configuration, put it where the application can find it, not where a launcher injects it.**

### 5.6 Next.js ignores `PORT` from env files
Tested: with `PORT=9082` in `.env.local`, `next dev` auto-picked 3003. The port is pinned on the
command line in `package.json`; `make check-ports` guards the resulting drift risk against
`FRONTEND_PORT`, and the guard has been proven to fire.

### 5.7 A `.gitignore` rule is only proven by a clone
`*.jar` silently excluded `backend/gradle/wrapper/gradle-wrapper.jar`, and the
`!gradle/wrapper/gradle-wrapper.jar` exception never fired — a pattern without a leading `**/` is
anchored to the directory holding the `.gitignore`. It worked on the machine that wrote it and would
have broken **every** clone, with CI dying at checkout. Fixed, and verified by cloning the commit to
a temp directory and running `./gradlew --version` and `compileJava` there.

---

## 6. Rules that are enforced, not merely written down

These already fail the build. Do not work around them; they are the point.

| Rule | Enforced by |
|---|---|
| No ambient clock anywhere but `ClockConfig` | `NoAmbientClockTest` |
| The availability engine performs no I/O | `LayeringTest` |
| The AI layer never touches persistence | `LayeringTest` |
| No `@Transactional` on a controller | `LayeringTest` |
| Secrets never reach a log, in either format | `PiiValueMaskerTest`, `ConsoleRedactionTest` |
| No browser-locale date formatting | ESLint `no-restricted-*` |
| Frontend and Caddy agree on a port | `make check-ports` |

`LayeringTest` rules use `allowEmptyShould(true)` because their packages are still empty — that is
deliberate, so the commit that fills them is already governed.

---

## 7. Branching

`main` is the tested branch; **work on `dev`**.

```bash
git checkout dev
# ... build, test, commit ...
git push origin dev            # CI runs on every branch and every pull request
```

Merge only when CI is green and the change has been *exercised*, not merely compiled:

```bash
gh pr create --base main --head dev --fill
gh pr checks --watch
gh pr merge --squash
```

`main` should always be a commit a stranger could clone and run.

---

## 8. Next: Phase 02 — Authentication and Tenancy

Read `docs/phases/phase-02-authentication.md` in full. The two things that actually matter:

1. **Registration atomicity.** One transaction creates `User` + `Business` + `Membership(OWNER)` +
   five default business-hour rows. A forced failure at the last step must leave *zero* rows, and
   that is a required test.
2. **The tenancy seam.** `TenantContext.businessId()` resolved from the Membership by a filter,
   never from the request. Tenant-owned repositories expose only `findByBusinessIdAndId`-shaped
   methods, so forgetting the tenant filter is *not expressible*. A new ArchUnit rule makes that a
   guarantee rather than a convention.

Phase 02 is where the single origin starts earning its keep: tokens travel as `httpOnly` cookies and
**no token-handling code may appear in the frontend at all.** If the frontend touches a token,
something is wrong.

`SecurityConfig` currently permits everything and carries a `TODO(phase-02)` — that is the entry
point.

---

## 9. Open items

- **Node 20 deprecation warnings in CI.** `actions/checkout@v4`, `setup-node@v4` and
  `pnpm/action-setup@v4` target Node 20. Warnings only, nothing fails. Bump to `@v5` when convenient
  or fold into phase 11.
- **Branch protection is not enabled.** Nothing currently *stops* a direct push to `main`; the
  discipline in §7 is intent, not enforcement. It was offered and not yet accepted — ask before
  applying it, since it changes what the owner can do with their own repository.
- **`make seed`** prints a placeholder; seed data ships in phase 11.
- **`.env` is git-ignored** and holds only `local-dev-only-*` placeholders. A fresh clone gets one
  from `.env.example` on the first `make up`.
