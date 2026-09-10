# Session handoff — 2026-09-09 — Phase 09, the AI Receptionist, backend half

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §1.1 is the small drift the session cleared before starting the phase; §2 is the three
> defects found in *shipped* code while wiring the tools up; §3 is the one decision put to the
> principal; §6 is what a fresh session must not redo.
>
> Three commits, in order: `7169a51` clears the drift, `7d99200` is the phase, and the third is this
> document. This file does not name its own hash — it cannot, and an earlier draft that tried was
> wrong within a minute of being written.

---

## 1. Where the project stands

**Phase 09's backend half is complete and green.** Every box under *Database*, *Backend* and levels
1 and 2 of *Testing* in the phase document is ticked. The chat panel, the confirmation card, the
degradation banner and the `/conversations` screens are **not started** — the same split phases 03,
04, 05, 06 and 08 all took.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | `c56d9df` — all of phase 08 |
| `dev` | **Four commits ahead of `origin/dev`, five ahead of `origin/main`, and CI has seen none of them.** The oldest, `8b177f4`, was already stranded when this session began |
| Backend | **793 tests, built green** (732 before, so 61 new) |
| Frontend gates | Not re-run: **no frontend file changed this session** |
| Migrations | **`V7__ai.sql`** — `ai_conversations`, `ai_messages` |
| New ADR | **[ADR-0009](../adr/0009-a-hand-written-rest-client-instead-of-an-sdk.md)** |
| Issues opened | [#9](https://github.com/sanama-stack/reception-booking-system/issues/9) — `make migrate` has never worked |
| Labels | The five of `docs/agents/triage-labels.md` now **exist**; #5, #7 and #8 wear them |

### 1.1 What the session cleared first

Three pieces of documentation drift, all of the same kind — the repository describing a state it had
left two phases earlier — plus the label vocabulary, committed as `7169a51` before any phase work
started.

| | |
|---|---|
| README's build status | Said **phase 07 of 11** and called the public booking page "still to come". It merged as [#6](https://github.com/sanama-stack/reception-booking-system/pull/6) |
| README's Manage Link paragraph | Said the page the link opens "arrives in phase 08"; it had, and `/manage/{token}` is a page to visit rather than a token to read |
| `.env.example` | Had no `RATE_LIMIT_ENABLED`, though the README calls that file the authoritative list of every variable and the switch has existed since phase 08 |
| The triage labels | `docs/agents/triage-labels.md` defines five, and **none of them existed in the tracker** — only GitHub's defaults, which is why #7 and #8 had both been filed as plain `bug` |

The labels were created and applied from the previous handoff's own classification: #5 and #8 are
`ready-for-human` because that handoff called both decisions rather than patches, and #7 is
`ready-for-agent` because its first step is specified — widen the assertion so the next failure names
its cause.

The README's stack line was updated later, with the phase, to name ADR-0009.

**None of this is worth redoing or re-checking.** It is recorded because a fresh session reading only
§2 onward would not know the label vocabulary is now real, and would file the next issue as `bug`
like the last four.

### What the phase actually ships

Eight tools, a bounded orchestration loop, a system prompt built only from configuration, session
tokens stored as hashes, a per-business daily cost cap checked before every model call, both
controllers, chat rate limits, and a level-3 corpus that is written and has never run.

**The Receptionist works end to end against a scripted model and has never spoken to a real one.**
There is no `OPENAI_API_KEY` in this project's environment. Everything in §5 is verified against
`ScriptedChatModel`; nothing is verified against OpenAI.

---

## 2. Three defects in shipped code, found by wiring phase 09 onto it

None of these were phase-09 code. All three were latent in code that had been green for phases,
because `Actor.ai()` had no caller until now.

### 2.1 The Receptionist would have been a way around the Cancellation Window

`Actor.asCancellingParty()` read `type == CUSTOMER ? CUSTOMER : BUSINESS`. So `Actor.ai()` — which
had no caller and therefore no test — sorted to `BUSINESS`, and **the Business is never bound by the
Cancellation Window** (CONTEXT.md). A customer refused at `/manage/{token}` could have opened the
chat panel, asked, and had the same cancellation go through.

Fixed by making `asCancellingParty()` a `switch` that maps `CUSTOMER, AI → CUSTOMER`. The
Receptionist is reached only from the public booking page, by the person whose appointment it is,
and can do nothing they could not do themselves through a Manage Link — so it is the customer's
side. That the AI did it is not lost: the actor is recorded on the audit event and
`AppointmentSource.AI` records the door.

### 2.2 Cancelling and rescheduling asked the same question two different ways

`CancellationService` went through `asCancellingParty()`; `RescheduleService` compared
`actor.type() == ActorType.CUSTOMER` directly. Identical for the two actors that existed, and they
diverge the moment a third appears — which is exactly what `Actor.ai()` is. Fixing 2.1 alone would
have fixed cancelling and left rescheduling open.

Both now call `Actor.boundByCancellationWindow()`, derived from `asCancellingParty()` rather than
listed again, for the reason `CancellationWindow` gives about its own arithmetic: the two must not be
able to disagree, and the only way to guarantee that is for one to be the other.

### 2.3 `lookup_appointment` would have crossed tenants

`PublicAppointmentAuthority.byLookup` searches **every** tenant for a Confirmation Code and then
adopts whichever business it found. That is correct there — a customer following a link from an email
has not said which business they mean. Reusing it from the tool would have meant a customer could
hand business A's Receptionist a code belonging to business B, have the conversation adopt B, and then
cancel B's appointment. A cross-tenant path, opened by the one tool whose entire job is proving
ownership.

The matching is now `ConfirmationCodeLookup`, shared by both callers; the **scoping** is not.
`matching(...)` spans tenants for the public endpoint; `withinBusiness(...)` confines to one for the
Receptionist. `PublicAppointmentAuthority` was rewired onto it, so the phone-normalisation rule has
one definition rather than two.

`ToolExecutionTest.a_code_from_another_business_is_not_found` books in a second real business and
presents its real code. It fails against the naive implementation.

---

## 3. The decision the principal made

**A hand-written `RestClient` call, not an SDK** — ADR-0009. Options put were the official
`com.openai:openai-java`, Spring AI, and no SDK at all. The reasoning that decided it: the
`ChatModel` port already provides the seam an SDK's abstraction would provide, so an SDK's types
would stop at one file's boundary anyway; and Spring AI ships its own tool-calling abstractions that
overlap the ones `05-ai-architecture.md` specifies, which would have meant bending the documented
design around a framework.

Scope was also put and answered: **backend half this session**, per the precedent of every phase
since 03.

---

## 4. Departures from the design documents, all deliberate

| Document | Says | Shipped | Why |
|---|---|---|---|
| `04-api-overview.md` §6 | turn body carries `conversationId` **and** `sessionToken` | `sessionToken` alone | The token is uniquely indexed and names exactly one conversation. A second identifier beside it can only agree redundantly or disagree, and there is no useful behaviour for disagreeing — the argument `PublicAppointmentController` makes about the id in its path, except a body can simply omit it where a URL cannot |
| `05-ai-architecture.md` §4 | loop is `for (i < 5)` over iterations | budget of 5 tool **calls** | The document's own level-2 test says "six tool calls in one turn → capped at five". Counting iterations, a model that asks for six tools in a single response gets all six — one iteration, six executions. Counting calls makes the stated limit true however the model groups them |
| `03-data-model.md` | index on `session_token_hash` | **unique** index | A session token names one conversation; that is what the client assumes when it resumes. A duplicate would make the lookup return an arbitrary row. The document does not forbid it |
| `05-ai-architecture.md` §6 | UI renders the card from `appointmentCreated`, the tool result | projected into `camelCase` before it leaves the API | What makes the field a hallucination control is its **provenance** — it exists only because `create_appointment` succeeded — and renaming keys does not touch that. Passing the tool result through verbatim would have put `snake_case` into a package whose one rule is a single hand-written vocabulary (`PublicFieldAllowListTest`) |

`04-api-overview.md` has been updated to match on the first and fourth; the phase document carries the
second and third.

---

## 5. What is verified, and how

- **Level 1 — 22 tests, `ToolExecutionTest`.** Each tool through `ToolRegistry` with a hand-built
  `ToolContext`, against a business built over HTTP. Includes both cross-tenant probes, the
  authorisation refusals, and a fabricated booking time being refused by the availability re-check.
- **Level 2 — 20 tests, `ConversationLoopTest`.** The loop against `ScriptedChatModel`. Both
  ceilings, the cost cap making **no** model call at all, a provider failure leaving the conversation
  `ACTIVE` and genuinely resumable, the system prompt sent but never persisted, and authority earned
  in one turn surviving into the next.
- **HTTP — 11 tests, `PublicChatTest`.** Includes the two isolation probes rule 5 requires: a session
  token presented under another business's slug, and a Manage Link for another business's appointment
  seeding nothing.
- **Schemas — 4 tests, `ToolSchemaTest`.** Eight tools by name and count; no `business_id` at any
  depth in any schema; strict mode's three requirements on every one.
- **Architecture — 3 tests, `AiProviderIsolationTest`.** Nothing outside `ai.openai` depends on the
  adapter, nothing else in the AI layer speaks HTTP, nothing else reads the API key.

Two existing tests were tightened rather than added to:

- `LayeringTest.the_ai_layer_never_touches_persistence_directly` **lost its `allowEmptyShould(true)`**,
  which was there with a note saying "empty until phase 09".
- `PublicFieldAllowListTest` gained `every_mapped_public_endpoint_is_swept`. Its old pinned count of
  eleven claimed in a comment that "adding an endpoint without adding it here fails loudly" — it does
  not, and it did not: phase 09 added two endpoints and the count noticed neither. The new test reads
  the mapped patterns out of Spring's `RequestMappingHandlerMapping` and compares them to a pinned
  list, so a new controller now fails, naming itself.

`TenantRepositoryShapeTest` also caught a genuine convention breach in new code — a repository method
that filtered by tenant without saying so in its name. Renamed to `sumByBusinessIdSince`, and
`sumByBusinessId` added to the allowed prefixes rather than the method being excused.

---

## 6. What a fresh session must not redo

- **Do not re-argue `Actor.ai()` → `CancelledBy.CUSTOMER`.** §2.1. The Receptionist is the customer's
  side, and the alternative is a documented way around the Cancellation Window.
- **Do not point `lookup_appointment` at `PublicAppointmentAuthority.byLookup`** to remove the
  apparent duplication. §2.3 is the whole reason `ConfirmationCodeLookup` splits matching from
  scoping.
- **Do not "simplify" the loop's tool budget back to counting iterations.** §4, row 2.
- **Do not add `conversationId` to the turn request** to match the API document's example. §4, row 1;
  the document has been corrected.
- **Do not make `ScriptedChatModel` non-`@Primary`.** It is `@Primary` so that no test can reach the
  network by forgetting something. `LiveReceptionistTest` opts out with
  `@TestPropertySource(properties = "app.ai.scripted=false")`, which is greppable.
- **Do not tick level 3.** The corpus is written and has never executed — there is no API key in this
  project. Boxes stay empty until something runs.
- **Do not trust a running backend to be current.** Still true, still launched from IntelliJ, still
  never hot-reloads. Both servers were found running at the start of this session, started by
  somebody else; they were left alone.
- **Do not run `pnpm build` while `next dev` is running.** Unchanged.

---

## 7. Next steps, in order

### P0

1. **A pull request.** `dev` → `main`, and CI has seen none of it — including `ebb89d4` and `8b177f4`,
   which were already unpushed when this session started. This is now a large PR containing a
   migration.

### P1

2. **Issue #9 — `make migrate` has never worked.** Found while trying to verify V7 outside the test
   harness. The Gradle Flyway plugin resolves its own classpath and never receives
   `flyway-database-postgresql`, so the task cannot handle a `jdbc:postgresql:` URL at all; the
   Makefile target also drops `DB_PORT`. Nothing depends on it — Flyway runs at application startup
   and the suite migrates through Testcontainers — which is why it has been broken since phase 01
   without anybody noticing. Well specified in the issue.
3. **The frontend half of phase 09.** The chat panel in `/book/[slug]`'s right column, the
   confirmation card from `appointmentCreated`, `sessionStorage` persistence, the degradation banner,
   the persistent "book the classic way" affordance, and `/conversations` in the dashboard. The API it
   consumes is documented in `04-api-overview.md` §6 and is what actually shipped.
4. **The `aiEnabled` Settings toggle**, which phase 09 owes and this half did not build. The column,
   the patch path and the public `aiEnabled` field all already exist — this is a switch on a Settings
   screen and nothing else.
5. **Run level 3 once**, with a real key: `OPENAI_API_KEY=sk-... ./gradlew test -PincludeTags=llm`.
   Until then nothing in this phase has met a real model, and the system prompt in particular has
   never been evaluated by the only thing that reads it.

### P2

6. **Issues [#7](https://github.com/sanama-stack/reception-booking-system/issues/7) and
   [#8](https://github.com/sanama-stack/reception-booking-system/issues/8)**, both now labelled.
   Untouched here.
7. **Issue [#5](https://github.com/sanama-stack/reception-booking-system/issues/5)**, unchanged from
   four handoffs.
8. **Retention.** `05-ai-architecture.md` §11 documents 90 days for transcripts and says the purge job
   is V1.1. Nothing deletes an `ai_message` today, and nothing is meant to yet.

---

## 8. Carried, and still carried

- **`PublicFieldAllowListTest.ALLOWED` is flat.** Four handoffs. Still not filed — but the *other*
  half of that finding, the count that could not notice a new endpoint, was fixed here (§5).
- **The shared `Input` is 40 px**, below the 44 px guideline.
- **`Actor.system()` still has no caller.** Eight handoffs. `Actor.ai()` is no longer on this list.
- **There is no "find my booking" page.** Phase 10 or 11.

---

## 9. Files, and what changed

`7169a51` — the drift of §1.1:

```
README.md                                                                   build status, Manage Link
.env.example / .env                                                         RATE_LIMIT_ENABLED
```

Plus five labels created in the tracker and applied to #5, #7 and #8, which no commit records.

`7d99200` — the phase:

```
backend/src/main/resources/db/migration/V7__ai.sql                          NEW — two tables
backend/src/main/java/dev/reception/ai/port/            8 files             NEW — the ChatModel port
backend/src/main/java/dev/reception/ai/tools/          15 files             NEW — eight tools + registry
backend/src/main/java/dev/reception/ai/openai/          1 file              NEW — the adapter
backend/src/main/java/dev/reception/ai/application/    11 files             NEW — loop, prompt, cost, store
backend/src/main/java/dev/reception/publicapi/          3 files             NEW — chat controller
backend/src/main/java/dev/reception/appointments/Actor.java                 §2.1, §2.2
backend/src/main/java/dev/reception/appointments/RescheduleService.java     §2.2
backend/src/main/java/dev/reception/appointments/ConfirmationCodeLookup.java NEW — §2.3
backend/src/main/java/dev/reception/publicapi/PublicAppointmentAuthority.java rewired onto it
backend/src/main/java/dev/reception/common/error/ErrorCode.java             +2 codes
backend/src/main/java/dev/reception/common/ratelimit/RateLimitProperties.java +2 policies
backend/src/main/resources/application.yml                                  app.ai
backend/build.gradle.kts                                                    -PincludeTags
backend/src/test/…                                     6 files              NEW/changed — §5
docs/adr/0009-a-hand-written-rest-client-instead-of-an-sdk.md               NEW
docs/04-api-overview.md                                                     §4 rows 1 and 4
docs/phases/phase-09-ai-receptionist.md                                     backend boxes
.env.example / .env                                                         OPENAI_* additions
```

---

## 10. Commands, and what they last returned

```bash
# Backend. From backend/. ~5 minutes.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```
`BUILD SUCCESSFUL` — **793 tests, 0 failures, 0 errors, 0 skipped**.

```bash
# The level-3 corpus. Skips every test without a key rather than failing.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test -PincludeTags=llm --tests '*LiveReceptionistTest*'
```
`BUILD SUCCESSFUL` — 9 SKIPPED. That the context loads with `app.ai.scripted=false` is what this
currently proves, which is worth something and is not the same as the corpus passing.

---

## 11. Confidence

**High — verified against a command's output.** Everything in §5. 793 tests green on a full build,
and each of the three defects in §2 has a test that fails without its fix.

**High.** §2.3's cross-tenant hole. The probe books in a second real business and presents its real
Confirmation Code; it is not a reasoned claim about what the code would have done.

**None — not verified at all.** Anything involving a real model. The system prompt has never been
read by an LLM, no live tool call has ever been made, and the whole of level 3 is unrun. The scripted
double proves the loop is correct; it proves nothing about whether the Receptionist is any good.

**High — verified against the real development database.** `V7` applies cleanly to the dev database as
it actually is — schema version 6, three businesses, 32 appointments — creating both tables with every
constraint, index and foreign key intact. Applied inside `BEGIN … ROLLBACK`, so the database is
untouched and still reports version 6 with no `ai_` tables; the fixture in §12 is exactly as it was.

**Not verified.** That the *application* starts against a database with V7 applied. The migration's
SQL is proven; Hibernate's mapping of `uuid[]` and `jsonb` to `AiConversation` and `AiMessage` is
proven only against Testcontainers, which is the same schema by the same file — so this is a small
gap, but it is a gap.


---

## 12. The verification tenant

Untouched. `Phase 06 Scratch` and the two other businesses are as the previous handoff left them —
32 appointments, schema version 6. The only thing this session did to the development database was
apply `V7` inside a transaction and roll it back, which is why §11 can claim the migration works and
§1 can still say no fixture changed.

**The backend and frontend were already running** on 9081 and 9082 when this session started, begun
by somebody else. Both were left running; neither was restarted, so the process on 9081 predates
every class in this handoff and knows nothing about `/public/businesses/{slug}/chat`. Restart it
before trying anything by hand — and assert a field you know is new before trusting what comes back,
per the standing warning.
