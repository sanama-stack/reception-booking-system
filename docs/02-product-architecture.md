# 02 — Product Architecture

## 1. Runtime topology

Everything runs behind **one origin**. That single decision removes CORS from the security surface and lets
authentication use httpOnly cookies with no token ever reaching JavaScript.

```text
                       ┌──────────────────────────────────┐
   browser  ─────────► │  Caddy   (:8080, single origin)  │
                       └───────┬──────────────────┬───────┘
                               │ /api/*           │ /*
                               ▼                  ▼
                    ┌────────────────────┐  ┌──────────────────┐
                    │  Spring Boot API   │  │  Next.js (SSR)   │
                    │       :8081        │  │      :3000       │
                    └─────┬───────┬──────┘  └──────────────────┘
                          │       │
              ┌───────────┘       └───────────┐
              ▼                               ▼
      ┌────────────────┐              ┌────────────────┐
      │  PostgreSQL 16 │              │  OpenAI API    │
      │     :5432      │              │   (outbound)   │
      └────────────────┘              └────────────────┘
              │
              ▼
      ┌────────────────┐
      │ Mailpit :8025  │   (local mail capture + web UI)
      └────────────────┘
```

**Every port in that diagram is container-internal.** They are what each process listens on inside the
compose network. The browser arrow reaches Caddy at `localhost:9080`, not `:8080`, and Mailpit's UI at
`localhost:9083`, not `:8025`.

**In this topology only two of them are published at all, and only one beyond loopback.** Caddy's
`9080` is on every interface because it is the origin; Mailpit's UI is on `127.0.0.1:9083` because a
developer reads it and the E2E flow asserts against it. **The database is not published here** — the
backend is a container and resolves `postgres:5432` on the compose network, so nothing on the host
needs it ([06-security.md](./06-security.md) §12). The wider `9080`–`9085` block belongs to the
`make up` topology, where the two applications run on the host and must reach Postgres and Mailpit
through it; the README carries the full table and `make check-bindings` asserts both shapes.

Five containers, no more. Nothing is present to make the diagram look impressive: Caddy exists because of
the cookie decision, Mailpit because notifications must be demonstrable.

This is the **deployment** topology — `make up-all`, and what CI smoke-tests. In day-to-day
development the two applications run from the IDE instead and Caddy proxies to the host
(`make up`), so a code change is a reload rather than an image rebuild. The origin the browser
talks to is identical either way, which is the property the design actually depends on.

## 2. Backend layering

Dependencies point inward. Nothing in an inner ring knows about an outer one.

```text
┌───────────────────────────────────────────────────────────────┐
│ web            Controllers, DTOs, request validation,         │
│                exception → problem+json mapping               │
├───────────────────────────────────────────────────────────────┤
│ application    Use-case services, transaction boundaries,     │
│                authorization checks, orchestration            │
├───────────────────────────────────────────────────────────────┤
│ domain         Entities, value objects, the availability      │
│                engine, state machines, business rules         │
├───────────────────────────────────────────────────────────────┤
│ persistence    JPA entities, repositories, native queries     │
├───────────────────────────────────────────────────────────────┤
│ integration    Ports + adapters: ChatModel, EmailSender       │
└───────────────────────────────────────────────────────────────┘
```

**Rules that are enforced, not merely suggested:**

- A controller may not contain an `if` about business rules. It validates shape, delegates, and maps results.
- `@Transactional` lives on application services. Never on controllers, never on repositories.
- The **availability engine is pure**: it takes plain domain inputs and an injected `Clock` and returns
  slots. It performs no I/O. This is what makes it exhaustively unit-testable.
- The **AI layer never touches persistence.** Every tool call goes through the same application services the
  REST controllers call. See [ADR-0004](./adr/0004-llm-confined-to-tools.md).
- Integrations are used only through ports defined in `application`.

## 3. Module map

```text
backend/src/main/java/…/reception/
├── common/           ids, Money, TimeRange, problem+json, error codes, Clock config
├── tenancy/          TenantContext, resolution filter, guards        ← isolation lives here, once
├── auth/             users, memberships, tokens, password hashing
├── business/         profile, hours, closures, FAQs, settings
├── catalog/          services, employee↔service assignments
├── staff/            employees, working schedules, time off
├── scheduling/
│   ├── domain/       AvailabilityEngine, TimeRange algebra, SlotGenerator   ← pure
│   └── application/  AvailabilityService, BookingService, RescheduleService
├── appointments/     appointment aggregate, state machine, audit events
├── customers/        customer lookup and identity by phone
├── notifications/    outbox entity, scheduler, templates, EmailSender port
├── ai/
│   ├── port/         ChatModel, ChatMessage, ToolSpec
│   ├── openai/       the single adapter; SDK types stop here
│   ├── tools/        ToolRegistry + one class per tool
│   └── application/  ConversationService, orchestration loop, ToolContext
├── analytics/        summary queries
└── publicapi/        public controllers (booking page, chat, manage link)
```

`publicapi` is a separate module on purpose: it makes "what is reachable without authentication" a
directory you can read, rather than a property you have to infer from annotations.

## 4. Tenant isolation architecture

This is the single most important structural decision in the codebase.

**`business_id` never comes from the client.** Not from a path, not from a body, not from a header, and —
critically — not from an AI tool argument.

```text
authenticated request  →  JWT  →  Membership  →  TenantContext.businessId
public request         →  slug in path  →  Business lookup  →  TenantContext.businessId
AI tool call           →  conversation record  →  TenantContext.businessId
```

Three consequences:

1. Every repository method for a tenant-owned entity takes `businessId` as its first argument. There is no
   `findById(id)` on tenant-owned entities — only `findByBusinessIdAndId(businessId, id)`.
2. A lookup that fails because the row belongs to another tenant is indistinguishable from one that fails
   because the row does not exist: both raise `NotFound` → `404`. Returning `403` would confirm existence.
3. The database backs this up with **composite foreign keys** on `(business_id, id)`, so a bug in the
   application cannot link an employee to another tenant's service. Detail in
   [03-data-model.md](./03-data-model.md).

## 5. The booking write path

The most concurrency-sensitive path in the system, start to finish:

```text
POST /api/public/businesses/{slug}/appointments
  │
  ├─ resolve tenant from slug
  ├─ validate request shape
  │
  └─ BookingService.book()  ── @Transactional ────────────────────────┐
       ├─ load service, employee, assignment          (tenant-scoped) │
       ├─ AvailabilityEngine.isSlotBookable(...)      (pure)          │
       ├─ find-or-create Customer by (business, phone)                │
       ├─ compute blocked range from buffers                          │
       ├─ snapshot price + currency                                   │
       ├─ generate Confirmation Code                                  │
       ├─ INSERT appointment  ──► EXCLUDE constraint is the arbiter   │
       ├─ INSERT appointment_event                                    │
       └─ INSERT notification rows (confirmation + reminder)          │
     ──────────────────────────────────────────────── commit ─────────┘
                    │
   constraint violation ──► 409 SLOT_UNAVAILABLE
```

The pre-check exists for a good error message. **The constraint exists for correctness.** The pre-check can
be raced; the constraint cannot. See [ADR-0002](./adr/0002-exclusion-constraint-for-booking-conflicts.md).

## 6. AI orchestration position

```text
Customer message
      │
      ▼
ConversationService  ── builds system prompt from configured business data only
      │
      ▼
ChatModel port ──► OpenAI adapter ──► model
      │                                 │
      │◄────────── tool call ───────────┘
      ▼
ToolRegistry.execute(name, args, ToolContext)
      │                                   ▲
      ▼                                   │ businessId — from the session, never the model
Application services (the same ones REST controllers call)
      │
      ▼
Domain rules ──► Database
      │
      ▼
Tool result ──► model ──► assistant message ──► customer
```

The AI is a *client* of the application layer, positioned exactly where the browser is. It has no extra
privileges, no direct SQL, and no ability to name its own tenant.

## 7. Frontend architecture

Next.js App Router, TypeScript, Tailwind.

```text
frontend/src/
├── app/
│   ├── (marketing)/            landing, pricing placeholder
│   ├── (auth)/                 login, register
│   ├── (dashboard)/            all authenticated screens
│   │   ├── appointments/  calendar/  services/  employees/
│   │   ├── customers/  settings/  analytics/  conversations/
│   ├── book/[slug]/            public booking page (Classic Flow + chat)
│   └── manage/[token]/         Manage Link landing
├── components/                 design-system primitives + feature components
├── lib/api/                    typed fetch client, error normalisation
└── lib/time/                   business-timezone formatting helpers
```

**Conventions:**

- Public pages are **server-rendered** for first paint and shareability; the dashboard is client-rendered
  behind an auth guard.
- Cookies are sent automatically because everything shares one origin. There is no token handling code in
  the frontend at all — this is the payoff of the Caddy decision.
- One typed API client. Every error response is normalised into `{ code, message, fieldErrors }` so screens
  render server-provided messages instead of inventing their own.
- **All datetimes render in the business timezone**, formatted through `lib/time`. Using the browser's
  local formatter anywhere is a defect.
- Empty, loading and error states are part of a screen's definition of done, not polish.

## 8. Technology choices and why

| Area | Choice | Reason |
|---|---|---|
| Language | Java 21 | Records, pattern matching, virtual threads available if needed |
| Framework | Spring Boot 3.x | Target stack for the portfolio's audience |
| Build | Gradle (Kotlin DSL) | Faster incremental builds; readable typed config |
| Database | PostgreSQL 16 | `btree_gist` exclusion constraints — the single feature the design depends on |
| Migrations | Flyway | Plain SQL, reviewable, no schema generation from entities |
| Auth | Spring Security resource server, self-issued JWT | [ADR-0001](./adr/0001-self-issued-jwt-over-keycloak.md) |
| LLM | OpenAI tool calling, strict schemas | Strict JSON schemas eliminate malformed-argument handling |
| LLM client | Official SDK, wrapped in a port | SDK does transport; the orchestration loop is ours |
| Frontend | Next.js App Router + TS + Tailwind | SSR for public pages, one framework for both surfaces |
| Proxy | Caddy | Single origin with a three-line config |
| Mail (dev) | Mailpit | Notifications visible in a browser during a demo |
| Rate limiting | Bucket4j (in-memory) | No Redis needed at one instance; documented externalisation path |
| Tests | JUnit 5, AssertJ, Testcontainers, Playwright | Real Postgres, because the schema uses features H2 lacks |

**Deliberately absent:** Redis, Kafka/RabbitMQ, Elasticsearch, a vector database, service mesh, Kubernetes,
CQRS, event sourcing. None is required by any MVP requirement, and each would be architecture for its own
sake.

## 9. Configuration

All configuration through environment variables, with `.env.example` as the authoritative list. Spring
profiles: `local` (compose defaults, relaxed cookie flags), `test` (Testcontainers), `prod` (secure
cookies, no seed data, external SMTP).

Nothing secret is committed. The seed script is guarded by profile so it can never run outside `local`.

## 10. Cross-cutting concerns

| Concern | Where it lives |
|---|---|
| Tenant resolution | One filter in `tenancy`, applied before controllers |
| Transactions | Application services only |
| Error mapping | One `@RestControllerAdvice` producing RFC 9457 `problem+json` |
| Validation | Bean Validation on DTOs for shape; domain rules in `domain` |
| Time | A single injected `Clock` bean — `Instant.now()` is banned outside it |
| Audit | `appointment_events` for appointment state; `created_at`/`updated_at` elsewhere |
| Logging | Structured JSON with request id and `business_id`; explicit redaction list |
| Rate limiting | Filter over public endpoints, keyed by IP and by conversation |
