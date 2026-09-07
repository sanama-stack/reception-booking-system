# 08 — Testing Strategy

## 1. Principle

**Tests are part of every phase's Definition of Done.** There is no phase called "write the tests"; a phase
that says so produces no tests. The final phase hardens and deploys — it does not retrofit coverage.

Effort is spent where correctness is hard to see by inspection: availability, concurrency, timezones,
tenant isolation, and AI constraint. Effort is *not* spent asserting that a CRUD controller returns the row
it just saved.

## 2. The pyramid, as it applies here

```text
        ┌───────────────────────────────┐
        │  E2E (Playwright)             │  1 flow, 1 concurrency scenario
        ├───────────────────────────────┤
        │  AI orchestration (scripted)  │  ~25 transcripts, no network
        ├───────────────────────────────┤
        │  Integration (Testcontainers) │  every endpoint, every isolation probe
        ├───────────────────────────────┤
        │  Unit (pure)                  │  availability engine, state machine, time algebra
        └───────────────────────────────┘
```

## 3. Testcontainers, not H2

The schema depends on `btree_gist` exclusion constraints, partial unique indexes, `citext` and `tstzrange`.
H2 supports none of them. Testing against H2 would test a **different system than the one shipped**, and
specifically would not test the constraint the entire booking design rests on.

Every integration test runs against real PostgreSQL 16 in Testcontainers, with a shared container per suite
and per-test transactional rollback.

## 4. Unit tests — the availability engine

The engine is a pure function of (business config, schedules, busy ranges, service, requested range,
`Clock`). Time is injected, never read from the ambient system — this is what makes exhaustive testing
possible at all.

**Required cases:**

| Group | Cases |
|---|---|
| Basic fit | Service fits exactly; service one minute too long; service fits at the last possible start |
| Intersection | Working schedule wider than business hours; narrower; disjoint (→ empty); partial overlap |
| Existing appointments | Before, after, exactly abutting (both must be offered), fully containing, partially overlapping |
| Buffers | Buffer blocks the neighbouring slot; **buffer extending past closing does not remove the final slot**; buffers on both sides |
| Grid | Slot interval 15 vs 30 vs 60; a service duration that is not a multiple of the interval |
| Horizon | Slot before `now`; exactly at `now + lead time` (allowed); one minute before it (rejected); at `max_advance_days`; one day beyond |
| Closures / time off | Full-day, partial-day, spanning midnight, spanning multiple days |
| Multi-employee | Two eligible employees with different schedules; one on time off; tie-break determinism |
| Empty reasons | `CLOSED`, `NO_ELIGIBLE_EMPLOYEE`, `FULLY_BOOKED`, `OUTSIDE_HORIZON` |
| **DST** | Spring-forward day (non-existent local times skipped); fall-back day (repeated hour resolved once); a booking spanning the transition; a business in a zone with no DST |
| Degenerate | No hours configured; no schedule configured; inactive service; inactive employee |

Fixed clocks and a fixed IANA zone throughout. No test may depend on the day it runs.

## 5. Unit tests — other pure logic

- **Appointment state machine:** every legal transition; every illegal transition rejected with
  `INVALID_STATUS_TRANSITION`; terminal states are terminal.
- **Time range algebra:** union, intersection, subtraction, half-open `[start, end)` semantics, empty and
  adjacent ranges.
- **Confirmation Code generation:** alphabet excludes `I`/`L`/`O`/`U`; collisions retried.
- **Phone normalisation:** local formats per business country; unparseable input rejected.
- **Slug derivation:** unicode names, punctuation-only names, collision suffixing.
- **Cancellation window:** boundary exactly at the limit; customer refused, business allowed.

## 6. Integration tests

Real HTTP through MockMvc / `@SpringBootTest`, real Postgres.

**Per resource:** create, read, update, deactivate, validation failures, and the specific error code for
each rule.

**Booking path specifically:**
- Successful booking writes appointment + audit event + two notification rows in one transaction
- Each rejection reason returns its own code, not a generic `422`
- A failed booking writes nothing at all (transaction rollback verified by row count)
- Rescheduling into a taken slot returns `409` and leaves the original appointment intact
- Cancellation is idempotent and does not send a second email

**Authentication:** registration atomicity, duplicate email, login failure parity, access expiry, refresh
rotation, refresh replay revoking the family, logout idempotence.

**Notifications:** enqueue-on-booking, poller sends and marks `SENT`, failure increments `attempts`,
`FAILED` after 5, cancellation cancels pending rows, reschedule re-schedules the reminder, and the partial
unique index prevents duplicates.

### The tenant-isolation suite

A dedicated, parameterised suite — the most important integration tests in the project.

For **every** tenant-scoped endpoint: authenticate as Business A, pass a Business B resource id, assert
`404` (never `403`, never `200`). Plus:

- No endpoint accepts `business_id` in a body or query and honours it
- A Business A token cannot list, read, modify or delete any Business B row
- Public endpoints for slug A never return Business B data
- Composite foreign keys reject a cross-tenant insert at the database level

**A new tenant-scoped endpoint added without a probe in this suite fails code review.**

### The concurrency test

```java
@Test void only_one_of_N_concurrent_bookings_of_the_same_slot_succeeds() {
    // 20 threads, one slot, a CountDownLatch to release them together.
    // Assert: exactly 1 × 201, exactly 19 × 409 SLOT_UNAVAILABLE,
    //         exactly 1 appointment row, exactly 1 confirmation notification.
}
```

Repeated for concurrent reschedules onto the same target slot, and for two concurrent modifications of one
appointment (optimistic `version` → `VERSION_CONFLICT`).

## 7. AI tests

Three levels, only the third of which touches a network.

### Level 1 — tools, without any model

Each of the eight tools is called directly with a `ToolContext`. No LLM involved.

- Correct arguments produce correct results
- A `service_id` from another business → not found
- `cancel_appointment` with an id **not** in `authorizedAppointmentIds` → `NOT_AUTHORIZED`
- `lookup_appointment` with a correct code but wrong phone → not found
- `lookup_appointment` with a phone but no code → schema rejection
- `find_available_slots` returns exactly what the engine returns, with employees resolved
- `create_appointment` on a taken slot → `SLOT_UNAVAILABLE`
- **No tool accepts a `business_id` argument** — asserted against the published schemas

### Level 2 — the orchestration loop, with a scripted model

A `ScriptedChatModel` implementing the `ChatModel` port replays fixed transcripts. Deterministic, free,
offline.

- Model requests a tool → executed → result fed back → final answer returned
- Model requests 6 tools in one turn → capped at 5, graceful hand-off
- Tool returns `SLOT_UNAVAILABLE` → the error reaches the model as a structured result
- Model returns text with no tool call → returned verbatim
- Conversation at the message ceiling → `CLOSED`, hand-off message
- Daily cost cap exceeded → no model call is made at all
- Every message and tool call is persisted with correct `business_id`
- Provider throws → `503 AI_UNAVAILABLE`, conversation left in a resumable state

### Level 3 — live model behaviour, tagged and excluded from CI

A corpus of ~25 real prompts run against the real API, organised by the intent categories from §17 of the
brief (which live here, as test taxonomy, rather than as runtime code):

| Category | Assertion |
|---|---|
| Book | Correct tool sequence; appointment created; time matches an offered slot |
| Check availability | `find_available_slots` called with correctly parsed relative dates |
| Cancel | `lookup_appointment` demanded before `cancel_appointment` |
| Reschedule | Availability re-checked before rescheduling |
| Business info | Answer traceable to configured data |
| Service info | Price and duration match the database exactly |
| **Unknown** | Says it does not know; offers the phone number; invents nothing |
| **Adversarial** | Injection attempts produce no unauthorised tool call |

Assertions target **tool call sequences and resulting database state**, not the model's wording. Tagged
`@Tag("llm")`, excluded from CI, run manually before a release. Flaky-by-nature tests must never gate a
pipeline.

## 8. End-to-end (Playwright)

One complete flow, because one honest E2E is worth ten brittle ones:

1. Register an owner → configure hours, service, employee, schedule
2. Open `/book/{slug}` in a fresh context
3. Book through the **Classic Flow**; assert the confirmation card and code
4. Book through the **Receptionist** (against the scripted model in CI); assert the confirmation card
5. Assert both emails in Mailpit's API, including a working Manage Link
6. Follow the Manage Link and cancel
7. Log back in as the owner; assert both appointments are listed, one badged `AI`, one now cancelled
8. Mark one `COMPLETED`; assert analytics revenue changes accordingly

Plus a mobile-viewport (360 px) run of the public page.

## 9. What is deliberately not tested

- Framework behaviour (that Spring maps JSON, that Flyway runs)
- Getters, setters, DTO mapping
- Visual pixel diffs
- Third-party libraries
- Live LLM output wording

## 10. CI

GitHub Actions on every push and pull request:

```text
1. backend:  ./gradlew build            (unit + integration, Testcontainers)
2. frontend: pnpm lint && pnpm typecheck && pnpm build
3. e2e:      docker compose up -d && pnpm playwright test   (scripted model)
4. report:   JaCoCo coverage summary
```

`@Tag("llm")` tests are excluded. The build fails on any test failure; coverage is reported but is not a
gate — a coverage threshold rewards testing getters, which is exactly the behaviour this strategy avoids.

## 11. Coverage expectations

| Area | Expectation |
|---|---|
| Availability engine | Effectively total, including both DST transitions |
| Booking write path | Every rejection reason, plus the concurrency test |
| Appointment state machine | Every transition, legal and illegal |
| Tenant isolation | Every tenant-scoped endpoint probed |
| AI tools | Every tool, including every authorization failure |
| Controllers | Happy path plus each distinct error code |
| Frontend | Type-checked and linted; E2E covers the critical path |
