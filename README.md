# Reception — AI Appointment Booking SaaS

A multi-tenant B2B SaaS where appointment-based businesses configure their services, staff and hours, and
their customers book by talking to an AI receptionist on a public booking page.

**The thesis:** the AI is an interface over real backend capabilities, not the system itself. The
Receptionist acts only through validated tools; those tools call the same endpoints the non-AI booking flow
calls; and the database makes double-booking structurally impossible regardless of what any layer above it
believes.

> **Build status: phase 06 of 11 in progress — backend complete.** An owner can configure a
> business, and appointments can be booked, moved, cancelled and closed out through the API, with
> double booking made impossible by the database rather than by a check. The phase 06 dashboard
> screens are not built yet. See [docs/09-phase-plan.md](docs/09-phase-plan.md) for the build order.

## Quick start

Prerequisites: **Docker** with Compose v2, **JDK 21**, **Node 22+**, **pnpm 11**, and **make**.

```bash
make up
```

That starts the infrastructure — Postgres, Mailpit and Caddy. Then run the two applications from
your IDE:

- **VS Code** — press <kbd>F5</kbd> and choose **Full stack**. Both configurations are in
  [.vscode/launch.json](.vscode/launch.json). The Spring Boot Dashboard and the Run button above
  `main()` work too — the backend imports `.env` itself rather than relying on the launcher to
  inject it.
- **Terminal** — `cd backend && ./gradlew bootRun` and `cd frontend && pnpm dev`.

| Surface | URL |
|---|---|
| Application | http://localhost:9080 |
| API health | http://localhost:9080/api/health |
| API docs | http://localhost:9080/api/docs |
| Mailpit | http://localhost:9083 |

**Always use `localhost:9080`.** Caddy puts both applications on that one origin — `/api/*` to the
backend, everything else to the frontend. Hitting the backend or the frontend on its own port puts
them on *different* origins, which is exactly what the single-origin design exists to avoid: it is
what lets authentication use httpOnly cookies with no CORS configuration anywhere. As of phase 02
this is no longer theoretical: sign in through any other port and the cookies will not come back.

`make up` copies `.env.example` to `.env` on first run.

**Ports live in one contiguous block, `9080`–`9085`**, deliberately away from the usual
`3000`/`8080`/`5432` range so this project can run alongside another without colliding:

| Port | |
|---|---|
| `9080` | Caddy — the origin you browse |
| `9081` | backend |
| `9082` | frontend |
| `9083` | Mailpit web UI |
| `9084` | Mailpit SMTP |
| `9085` | Postgres |

Check the whole block at once with `lsof -nP -iTCP:9080-9085 -sTCP:LISTEN`. Change any of them in
`.env`; Caddy derives its upstreams from `SERVER_PORT` and `FRONTEND_PORT`. The one exception is
the frontend's own port, which Next.js will only take from the command line — it is pinned in
`frontend/package.json`, and `make up` fails if the two ever disagree.

```bash
make down      # stop the infrastructure (keeps the database volume)
make logs      # tail container logs
make test      # backend build + frontend lint, typecheck, build
make psql      # psql shell on the running database
make help      # every target
```

### Running everything in containers

The deployment topology of [docs/02-product-architecture.md](docs/02-product-architecture.md) §1 —
five containers, both applications included — is one command away:

```bash
make up-all
```

This builds both application images and layers them in via
[docker-compose.apps.yml](docker-compose.apps.yml). It is what CI smoke-tests and what phase 11
deploys. It has no hot reload, so it is not the way to develop.

## Branching

`main` is the tested branch. Work happens on `dev`, and reaches `main` only once CI is green and
the change has actually been exercised — not merely compiled.

```bash
git checkout dev          # where the work happens
# ... build, test, commit ...
git push origin dev       # CI runs on every branch and every pull request
```

When a phase is done and verified, open a pull request from `dev` into `main` and merge it once the
checks pass:

```bash
gh pr create --base main --head dev --fill
gh pr checks --watch
gh pr merge --squash
```

The rule this encodes: **`main` should always be a commit a stranger could clone and run.** That is
the same standard each phase's Definition of Done is written to, so the branch and the checklist
enforce the same thing from two directions.

## Repository layout

```text
├── Makefile                 up, up-all, down, logs, test, migrate, seed
├── docker-compose.yml       postgres · caddy · mailpit
├── docker-compose.apps.yml  adds backend + frontend as containers (make up-all)
├── .vscode/                 launch configurations for both applications
├── .env.example             every variable the compose file reads
├── CONTEXT.md               the domain glossary all code follows
├── docs/                    the complete implementation documentation
├── infra/
│   ├── caddy/Caddyfile      the single-origin split
│   └── postgres/init.sql    extensions only
├── backend/                 Java 21 · Spring Boot 3 · Flyway · Gradle
└── frontend/                Next.js App Router · TypeScript · Tailwind
```

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Next.js 15 · TypeScript · Tailwind 4 · Caddy ·
Docker Compose · OpenAI tool calling (phase 09)

## Documentation

| If you want to know… | Read |
|---|---|
| What this is and who it's for | [docs/00-project-overview.md](docs/00-project-overview.md) |
| What's in the MVP and what isn't | [docs/07-mvp-scope.md](docs/07-mvp-scope.md) |
| The vocabulary the code must use | [CONTEXT.md](CONTEXT.md) |
| Requirements and acceptance criteria | [docs/01-prd.md](docs/01-prd.md) |
| How it's built | [docs/02-product-architecture.md](docs/02-product-architecture.md) |
| The schema | [docs/03-data-model.md](docs/03-data-model.md) |
| The API surface | [docs/04-api-overview.md](docs/04-api-overview.md) |
| How the AI is constrained | [docs/05-ai-architecture.md](docs/05-ai-architecture.md) |
| Security decisions | [docs/06-security.md](docs/06-security.md) |
| How it's tested | [docs/08-testing-strategy.md](docs/08-testing-strategy.md) |
| What to build, in what order | [docs/09-phase-plan.md](docs/09-phase-plan.md) |
| What happened while building it | [docs/sessions/](docs/sessions/) |
| Why a decision was made | [docs/adr/](docs/adr/) |
| What comes after the MVP | [docs/future/future-features.md](docs/future/future-features.md) |

## Logging

Human-readable in the `local` and `test` profiles — Spring Boot's usual console, plus the request
id in the correlation slot, so a log line can be matched to a response's `X-Request-Id` header.
Structured JSON in every other profile, which is what a container should emit.

Redaction applies to **both**. Passwords, tokens, Manage Link tokens, Confirmation Codes, API keys,
and customer emails and phone numbers are masked at the appender, in the message and in stack
traces, so it does not depend on any call site remembering
([06-security.md §10](docs/06-security.md)).

## Configuration

Every value that differs by environment comes from an environment variable, and
[.env.example](.env.example) is the authoritative list. `.env` is git-ignored and nothing secret is
committed. Local defaults are all prefixed `local-dev-only-` so the `prod` profile can refuse to start
while any of them survives.

## Tests

```bash
cd backend  && ./gradlew build     # unit + integration, real Postgres and Mailpit in Testcontainers
cd frontend && pnpm lint && pnpm typecheck && pnpm build
```

**Run `pnpm build` only when `pnpm dev` is stopped.** Both write `frontend/.next`, and a production
build performed underneath a running dev server leaves it serving 404s for every chunk — the page
renders unstyled and recovers only after you stop the server, delete `.next` and start it again.

Integration tests run against **real PostgreSQL 16**, never H2: the schema depends on `btree_gist`
exclusion constraints, partial unique indexes, `citext` and `tstzrange`, and H2 supports none of them.
Live-model tests are tagged `@Tag("llm")` and never gate the pipeline.
