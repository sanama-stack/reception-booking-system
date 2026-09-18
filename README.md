# Reception — AI Appointment Booking SaaS

A multi-tenant B2B SaaS where appointment-based businesses configure their services, staff and hours, and
their customers book by talking to an AI receptionist on a public booking page.

**The thesis:** the AI is an interface over real backend capabilities, not the system itself. The
Receptionist acts only through validated tools; those tools call the same endpoints the non-AI booking flow
calls; and the database makes double-booking structurally impossible regardless of what any layer above it
believes.

> **Build status: phase 10 of 11 complete.** An owner can configure a business and run its
> schedule from the dashboard — a real day and week **Calendar**, booking, moving, cancelling and
> closing out appointments, seeing who has booked, and reading counts, revenue and top services
> under **Analytics** — with double booking made impossible by the database rather than by a
> check, and every one of those changes sends the customer a real email. A stranger can book
> without an account at `/book/{slug}`, and manage that appointment from the link the email
> carries. **They can also just ask.** The AI receptionist answers on the booking page and books
> through the same endpoints the form uses: every slot and price it quotes comes from a tool, and
> its confirmation card is rendered from the booking the server made, never from what it said.
> The owner reads every conversation, and every tool call inside it, under **Conversations**.
> **Phase 11 is most of the way through**: the isolation suite, the E2E flow, the seed, the
> security walk, the performance checks, transcript retention and the frontend runner are all in,
> and the ten-step demo below was followed end to end on 2026-09-18. Three boxes are open — the
> concurrency test asserted *repeatedly* rather than once, a final reading of the security list
> against its own checklist, and `docs/deployment.md`, which is written but has never been run on a
> host. See [docs/09-phase-plan.md](docs/09-phase-plan.md) for the build order.
>
> **One measured defect ships with it, and it is much smaller than it was.** Asked to reschedule,
> the receptionist used to search from the appointment's *current* date rather than the one the
> customer named, and write the move to the wrong day. One prompt rule, pre-registered and measured
> on 2026-09-17 over two fifty-trial arms, took the correct landing from **22% to 98%** and the
> mechanism itself — searching the day the customer actually named — from **18% to 100%**. It is
> tracked as [#17](https://github.com/sanama-stack/reception-booking-system/issues/17) and carried
> with its rate and its evidence in [docs/07-mvp-scope.md](docs/07-mvp-scope.md) § *Accepted,
> measured, open defects*.
>
> **It is not closed, and three limits are worth stating plainly.** 98% is not 100% — one trial in
> fifty still failed to move the appointment at all — and fifty trials only bound the residual to
> *somewhere below* **10.6%**. That is the upper end of a confidence interval, and it is
> deliberately spelled out because it is the same number this paragraph used to quote as the *rate*:
> the two are different quantities measured on different scenarios, and reading one as the other is
> the mistake the rate is written down to prevent. So this is "not detectable at this sample", never
> "gone". The arm covered **one phrasing at one distance**: an ISO date twelve days out. The
> relative phrasing that was the worst case before the fix — "the Monday after next", **55.2%**
> wrong on 2026-09-11 — has had **zero trials since**, so nothing here says the rule helps it. And
> a separate mode survives on [#40](https://github.com/sanama-stack/reception-booking-system/issues/40):
> the receptionist has been recorded declining a legitimate move on the right day, with the
> requested slot in hand.
> Ownership is proven, the slot is real and the engine returned it; the day is the part that was
> wrong, which is the part nothing downstream can check. This paragraph used to say the
> receptionist "cannot invent a slot, a price or a policy" — the first two hold, the third does
> not — and then carried the pre-fix rates for a day after they were superseded. Both are
> corrected here rather than quietly dropped.
>
> The receptionist needs an `OPENAI_API_KEY` in `.env`. Without one it degrades to the booking
> form and says so — which is the ordinary state of a fresh clone, and deliberately not a startup
> failure.

## Quick start

Prerequisites: **Docker** with Compose v2, **JDK 21**, **Node 22+**, **pnpm 11**, and **make**.

`make up` and `make up-all` need only Docker — the applications build inside containers that carry
their own JDK. The JDK on **your** machine matters for `make test`, `make migrate` and `make seed`,
which run Gradle on the host. `make check-java` asserts it, and those three targets depend on it, so
a wrong JDK is named rather than reported as a bare version string:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
```

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
| Mail — every message the system sends | http://localhost:9083 |

**Always use `localhost:9080`.** Caddy puts both applications on that one origin — `/api/*` to the
backend, everything else to the frontend. Hitting the backend or the frontend on its own port puts
them on *different* origins, which is exactly what the single-origin design exists to avoid: it is
what lets authentication use httpOnly cookies with no CORS configuration anywhere. As of phase 02
this is no longer theoretical: sign in through any other port and the cookies will not come back.

`make up` copies `.env.example` to `.env` on first run.

**Ports live in one contiguous block, `9080`–`9085`**, deliberately away from the usual
`3000`/`8080`/`5432` range so this project can run alongside another without colliding:

| Port | | Reachable from |
|---|---|---|
| `9080` | Caddy — the origin you browse | anywhere — it is the origin |
| `9081` | backend | your machine (run by your IDE) |
| `9082` | frontend | your machine (run by your IDE) |
| `9083` | Mailpit web UI | **this host only** |
| `9084` | Mailpit SMTP | **this host only**, and only under `make up` |
| `9085` | Postgres | **this host only**, and only under `make up` |

**Only Caddy is published beyond loopback, and the database only to the IDE topology.** Everything
else is bound to `127.0.0.1`, because an unqualified Docker port mapping is reachable from the
network even when `ufw` says otherwise — Docker writes its rules below the firewall's
(docs/06-security.md §12). Under `make up-all` the applications are containers that reach Postgres
and Mailpit by service name, so `9084` and `9085` are not published at all; use `make psql` for a
database shell. `make check-bindings` asserts all of this and both `up` targets depend on it.

### A second checkout of this repository

`docker-compose.yml` pins the compose project to `reception`, so it does **not** depend on which
directory you are in — that is what keeps `reception_postgres-data` stable across a `git pull`. The
cost is that two checkouts would otherwise share one system: the second does not collide and fail, it
*succeeds*, recreating the first's containers against its own config and on its volumes.

`make check-project` refuses that, naming both directories, and `up`, `up-all`, `migrate` and `seed`
depend on it. To run a second checkout properly, give it its own project and its own volumes:

```bash
COMPOSE_PROJECT_NAME=reception-scratch make up
```

It is the same mechanism `make up-e2e` has always used to keep the E2E stack off your data.

Check the whole block at once with `lsof -nP -iTCP:9080-9085 -sTCP:LISTEN`. Change any of them in
`.env`; Caddy derives its upstreams from `SERVER_PORT` and `FRONTEND_PORT`. The one exception is
the frontend's own port, which Next.js will only take from the command line — it is pinned in
`frontend/package.json`, and `make up` fails if the two ever disagree.

```bash
make seed      # load the two-tenant demo dataset (see below)
make down      # stop the infrastructure (keeps the database volume)
make logs      # tail container logs
make test      # both test suites, plus lint, typecheck and format checks
make psql      # psql shell on the running database
make help      # every target
```

## Demo data

```bash
make seed
```

Two businesses, in two timezones and two currencies, with about forty appointments between them
spread across the last fortnight and the next.

| | Salon Aria | Dato's Auto |
|---|---|---|
| Booking page | [/book/salon-aria](http://localhost:9080/book/salon-aria) | [/book/datos-auto](http://localhost:9080/book/datos-auto) |
| Sign in | `owner@salonaria.example` | `owner@datosauto.example` |
| Timezone | `Asia/Tbilisi` | `Europe/Berlin` |
| Currency | GEL | EUR |
| | 4 services, 3 employees | 3 services, 2 mechanics |

Both accounts use the password **`reception-demo`**. Sign in at
[localhost:9080/login](http://localhost:9080/login).

**Two tenants rather than one, deliberately.** Tenant isolation and timezone correctness are then
things you can *watch* rather than things this README claims: sign into one and the other's
customers, appointments and revenue are not merely filtered out of the page, they are unreachable.
Each business's day is drawn in its own wall clock, and Berlin observes daylight saving while
Tbilisi does not — so the two are not even a constant number of hours apart.

The differences are arranged to show something:

- Salon Aria's barber is not assigned to Colour, so a service with no available employee is on
  screen from the first minute
- Its colourist is away all of next week — time off, which is one employee; Dato's Auto closes for a
  public holiday, which is the whole business. The calendar draws them differently
- Dato's Auto's full service is four hours with a thirty-minute buffer: the case where the time an
  appointment blocks is visibly not the time the customer agreed to
- One customer has no email address on file, which is what makes the manage page say so rather than
  promise a message nothing will send ([ADR-0008](docs/adr/0008-the-manage-page-says-whether-an-address-is-on-file.md))

`make seed` **replaces** the two demo businesses each time it runs and touches nothing else — your
own business, if you made one, is left alone. It runs only under the `local` profile, and refuses
to run at all if the profile says anything else. It also leaves Mailpit empty, so the first message
you see there is one your own clicking caused.

### The demo, end to end

1. **`make up`**, start both applications, **`make seed`**.
2. Sign in as `owner@salonaria.example`. The home screen counts today, this week and this month, and
   says which timezone it is counting in.
3. **Calendar.** **Day** puts the three employees side by side, a column each. Switch to **Week** for
   the seven days, appointments drawn in proportion to how long they take, and an `AI` badge on the
   ones the receptionist booked. Press **Next** — next week is hatched across Monday to Friday for
   Mariam Beridze, who is on leave.
4. **Analytics.** Revenue is completed appointments only, priced in GEL. Change the range and every
   number moves with it.
5. Open [**/book/salon-aria**](http://localhost:9080/book/salon-aria) in a private window — you are a
   stranger now, with no account. Book a haircut: pick the service, an employee or *any available*,
   a day, a slot, and give a name, a phone number and an email address. **Pick a day at least two
   ahead**, for the reason in step 7.
6. **[Mailpit](http://localhost:9083)** — the confirmation arrives within a minute, carrying a
   Confirmation Code and a Manage Link. It is not sent by the request that caused it; it was written
   into an outbox row in the same transaction as the appointment
   ([ADR-0005](docs/adr/0005-database-outbox-instead-of-queue.md)).
7. **Follow the Manage Link.** Move the appointment to another slot, or cancel it, with no account
   and no password. Another email follows, and the Confirmation Code does not change — you are
   holding an email with that code in it. Book *tomorrow* instead and this page will politely refuse
   both: Salon Aria asks for 24 hours' notice, and the Cancellation Window binds the customer rather
   than the business, which is why the owner can still move it from the dashboard.
8. Back in the dashboard: the change is already there. **Appointments** → mark a past one
   **completed**, and watch the revenue on **Analytics** move.
9. **Ask instead of clicking.** With an `OPENAI_API_KEY` in `.env`, the booking page's receptionist
   books the same way — through the same endpoints the form calls. Every slot and price it quotes
   came from a tool, and the confirmation card is rendered from the booking the server made rather
   than from anything the model said. Read the whole conversation, and every tool call inside it,
   under **Conversations**. Without a key the panel says so and the form is still there.
10. Sign out, sign in as `owner@datosauto.example`, and look again: a different catalog, a different
    currency, a different clock, and no trace of the salon.

### Watching the mail

Nothing is emailed to anyone in development. **Mailpit** at
**[http://localhost:9083](http://localhost:9083)** accepts every message the application sends and
shows it in a browser instead — book, move or cancel an appointment in the dashboard and the
confirmation, reschedule or cancellation lands there within a minute, HTML and plain text side by
side.

**Within a minute, not instantly.** Mail is not sent by the request that causes it. Booking writes
the message into a `notifications` outbox row in the same transaction as the appointment — so an
appointment and its confirmation commit together or not at all — and a poller sends what is due
every sixty seconds ([ADR-0005](docs/adr/0005-database-outbox-instead-of-queue.md)). Set
`NOTIFICATIONS_POLLER_ENABLED=false` in `.env` to stop it: rows still queue, nothing is sent, and
the outbox is visible in `select type, status, scheduled_for from notifications`.

Each message carries the appointment's Confirmation Code and a **Manage Link** — a signed token
authorising exactly one appointment, good until twenty-four hours after it ends. Since phase 08 it
opens a real page: `/manage/{token}`, where the customer reschedules or cancels without an account.

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
Docker Compose · OpenAI tool calling over a hand-written client ([ADR-0009](docs/adr/0009-a-hand-written-rest-client-instead-of-an-sdk.md))

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

Every AI turn logs what it cost and none of what it said: the model, the latency, both token counts,
the estimated cost the daily cap is charged, and each tool's name and outcome — as JSON fields you
can sum, rather than prose you would have to parse. Tool arguments are never logged, because that is
where a customer's name and number arrive.

In `local` alone, any query slower than 100 ms is reported with the statement that caused it. That
is how a missing index shows up while the data is still small enough to hide one.

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
