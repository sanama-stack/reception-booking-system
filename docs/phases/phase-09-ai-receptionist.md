# Phase 09 — AI Receptionist

> **Phase complete.** The frontend half shipped on 2026-09-10 and **level 3 ran for the first time
> in the project's history** — 9 tests, 0 failures, 0 skipped, against a live model. A customer has
> booked entirely by conversation in a browser, and the row is correct in the database.
>
> Two boxes below stay unticked on purpose and neither is an oversight. **Inline tool activity** was
> not built because there is nothing on the wire to build it from; **cancel and reschedule** have no
> live-model happy-path test because the corpus only covers them adversarially. Both are explained
> where they sit.
>
> Two defects in the *backend* half were found by driving the finished screens, and neither is fixed
> here: the system prompt states the date without its weekday, and tool errors drop their field
> detail. See that day's handoff.

## Goal

A customer books, reschedules, cancels and asks questions in natural language — through a model that
**cannot** invent a slot, a price or a policy, cannot reach another tenant, and cannot touch an appointment
whose ownership was not proven.

## Scope

**In:** `ChatModel` port + OpenAI adapter, `ToolRegistry` and eight tools, the orchestration loop, system
prompt construction, `ai_conversations` / `ai_messages`, session tokens, ceilings, cost caps, the chat panel,
the owner's transcript screen.

**Out:** voice, multi-language, RAG, streaming responses (V1.1).

## Dependencies

Phase 08 — every tool calls an application service the Classic Flow already proved correct.

## Technical work

Full design in [05-ai-architecture.md](../05-ai-architecture.md). The implementation-critical points:

### No intent classifier

The brief proposed an intent taxonomy. We do not build one: a classification step adds a failure mode
without adding a capability, and tool calling handles mixed intents in one turn. The intent names survive
as **test categories**, not runtime code.

### `business_id` is not a tool parameter

Not in any of the eight schemas. The model cannot name a tenant, so cross-tenant access is not merely
blocked — it is inexpressible. A test asserts this against the published schemas, so a future tool cannot
quietly introduce one.

### Authority is server-held

```java
record ToolContext(UUID businessId, UUID conversationId,
                   Set<UUID> authorizedAppointmentIds, Clock clock) {}
```

`authorizedAppointmentIds` grows only via a successful `create_appointment` or `lookup_appointment` in this
conversation, or a Manage Link at session creation. `cancel_appointment` and `reschedule_appointment` reject
anything outside it **before** reaching the application service. A hallucinated appointment id achieves
nothing.

### Strict schemas

All eight tools use OpenAI strict mode (`additionalProperties: false`, every property present, nullable
where optional). This removes malformed-argument handling from the runtime instead of validating
defensively afterwards.

### The loop is bounded

5 tool calls per turn, 40 messages per conversation, a 20-message sliding window, and a per-business daily
cost cap checked **before** each model call. Every model response and tool result is persisted before the
next iteration.

### Confirmation is not prose

The chat response carries `appointmentCreated` populated from the tool result. The UI renders the
confirmation card from **that object**, never by parsing the model's text. If the model claims a booking
that did not happen, no card appears — the failure is visible rather than convincing. This is the single
most effective hallucination control in the system, and it is a UI decision as much as a backend one.

### Every failure lands on the Classic Flow

Provider outage, ceiling reached, cost cap hit, repeated tool errors — all degrade to a link to the Classic
Flow with an honest message. This works only because phase 08 shipped a genuinely complete alternative.

## Database work

`V7__ai.sql`:
- `ai_conversations` — `session_token_hash`, `status`, `message_count`, token counts,
  `estimated_cost_cents`, `authorized_appointment_ids uuid[]`, indexes on
  `(business_id, started_at DESC)` and `(session_token_hash)`
- `ai_messages` — role, content, tool name, call id, arguments, result, index `(conversation_id, created_at)`

## Backend work

- `ChatModel` port; `ChatMessage`, `ToolSpec`, `ChatResponse`, `ToolCall`
- `OpenAiChatModel` — **the only class permitted to import the SDK**; enforced by an ArchUnit rule
- `ToolRegistry` with `register`, `specs`, `execute`
- Eight tool implementations, each calling existing application services
- `SystemPromptBuilder` — assembles from configuration only, with a token cap
- `ConversationService` — session creation, the loop, persistence, ceilings
- `CostTracker` — token accounting and the daily cap check
- `PublicChatController` — session and turn endpoints
- `ConversationQueryController` — the owner's read-only transcripts
- Rate limits: 20/hour/conversation, 60/hour/IP
- `ScriptedChatModel` test double

## Frontend work

- Chat panel in the `/book/[slug]` right column; full-width on mobile
- Message list with user/assistant styling, a typing indicator, and inline tool activity ("checking
  availability…") so latency is legible rather than mysterious
- **Confirmation card rendered from `appointmentCreated`**, never from the reply text
- Session token in `sessionStorage`; conversation resumes on reload within the session
- Degradation banner linking to the Classic Flow on `AI_UNAVAILABLE` or `AI_LIMIT_REACHED`
- A visible "or book the classic way" affordance at all times — the AI is the default, not the only door
- `/conversations` and `/conversations/[id]` in the dashboard, showing messages and tool calls

## Testing

Three levels, per [08-testing-strategy.md](../08-testing-strategy.md) §7.

### Level 1 — tools, no model
- [x] Each tool returns correct data for a valid context
- [x] A `service_id` from another business → not found
- [x] `cancel_appointment` with an unauthorised id → `NOT_AUTHORIZED`
- [x] `reschedule_appointment` with an unauthorised id → `NOT_AUTHORIZED`
- [x] `lookup_appointment` correct code + wrong phone → not found
- [x] `create_appointment` on a taken slot → `SLOT_UNAVAILABLE`
- [x] `find_available_slots` matches the engine exactly, employees resolved
- [x] **No published schema contains a `business_id` property**

### Level 2 — orchestration, scripted model
- [x] Tool call → execution → result → final answer
- [x] Six tool calls in one turn → capped at five, graceful hand-off
- [x] `SLOT_UNAVAILABLE` reaches the model as a structured result
- [x] A text-only response is returned verbatim
- [x] Message ceiling → conversation `CLOSED`
- [x] Daily cost cap exceeded → no model call is made at all
- [x] Provider exception → `503 AI_UNAVAILABLE`, conversation resumable
- [x] Everything persisted with the correct `business_id`
- [x] `authorizedAppointmentIds` grows only through the two sanctioned paths

### Level 3 — live model, `@Tag("llm")`, excluded from CI

> **Run on 2026-09-10, and green: 9 tests, 0 failures, 0 errors, 0 skipped.** Counted from the
> result XML rather than read off `BUILD SUCCESSFUL` — and `skipped: 0` is the load-bearing number,
> because it is what proves the `assumeTrue` guard let them run instead of passing quietly. Run with
> `./gradlew test -PincludeTags=llm` and an `OPENAI_API_KEY` in the environment.
>
> **Re-run on 2026-09-10 after the authorised write cases were added: 12 tests, 0 failures, 0
> errors, 0 skipped.** The earlier count stands as the record of that run; this is a second one, not
> a correction of it.
- [x] Booking, availability, cancel, reschedule, business info, service info categories — **all six,
      as of 2026-09-10.** Cancel and reschedule were the gap: the corpus exercised them only
      adversarially (a bulk cancel, a guessed id), and *every one of those cases would still have
      passed with the write tools hard-wired to refuse.* `an_authorised_cancellation_is_carried_out`
      and `an_authorised_reschedule_is_carried_out` are their counterfactual — a live model looks the
      appointment up with a Confirmation Code and the number that booked, and then writes. The
      reschedule case asserts the **time** and deliberately not the **date**, because the date has
      its own defect: [#17](https://github.com/sanama-stack/reception-booking-system/issues/17)
- [x] Unknown-information prompts produce "I don't know" plus the phone number
- [x] Adversarial prompts (bulk cancel, tenant switch, discount, prompt disclosure) produce no unauthorised
      tool call

Assertions target tool sequences and database state, never the model's wording.

## Definition of Done

- [x] A customer books entirely by conversation, and the appointment is correct in the database —
      driven in a browser on 2026-09-10 and checked in the database: `CONFIRMED`, `source = AI`,
      phone normalised to E.164, confirmation email actually `SENT` and the 24h reminder `PENDING`
- [x] The Receptionist reschedules and cancels only after ownership is proven — **now proven both
      ways.** A guessed id is refused by a live model, and as of 2026-09-10 a live model also
      *completes* both writes once a Confirmation Code and the booking number have been presented.
      The refusal half was what this box asked for; the success half is what makes the refusal
      meaningful, because a tool that refuses everything satisfies the refusal half perfectly
- [x] It answers configured questions accurately and says "I don't know" otherwise
- [ ] It never states a slot, price or policy that did not come from a tool or the context —
      **not established, and the first real conversation is why.** Asked for Monday 14 September the
      model called the tool for the 12th, got a correct `CLOSED`, and told the customer *Monday* was
      closed while the form beside it offered nine times that day. Nothing was invented — every
      value came from a tool — but it was attributed to a date the customer named and the tool never
      saw, which is indistinguishable from invention to the person reading it. See the handoff.
      **A second, plainer instance found on 2026-09-10**
      ([#17](https://github.com/sanama-stack/reception-booking-system/issues/17)): on the reschedule
      path the model said *"the earliest I can reschedule your appointment for is tomorrow"* — a
      **policy that came from no tool and is false** — and then wrote the appointment to that day.
      The first instance was a misattributed date; this one is an invented rule, which is the box's
      own words. **Left unticked deliberately**, and not waiting on #15: ticking it means deciding
      that ~95% is the bar
- [x] The confirmation card renders from backend data, not from the reply text — measured against
      its counterfactual, not argued
- [x] No tool accepts a tenant identifier
- [x] Ceilings, rate limits and the cost cap are enforced server-side
- [x] Every failure mode degrades to the Classic Flow — all four codes exercised against the running
      server: `AI_UNAVAILABLE`, `AI_LIMIT_REACHED`, `NOT_FOUND`, and the disabled-business path
- [x] Conversations are persisted and viewable by the owner
- [x] Levels 1 and 2 pass in CI; level 3 passes locally

## Checklist

### Database
- [x] `V7__ai.sql`
- [x] `ai_conversations` with `authorized_appointment_ids`
- [x] `ai_messages`
- [x] Both indexes

### Backend — port and adapter
- [x] `ChatModel` port and message types
- [x] `OpenAiChatModel` adapter
- [x] ArchUnit rule: the SDK is imported nowhere else
- [x] `ScriptedChatModel` test double

### Backend — tools
- [x] `ToolRegistry`
- [x] `ToolContext`
- [x] `get_business_info`
- [x] `get_services`
- [x] `get_service_details`
- [x] `find_available_slots`
- [x] `create_appointment`
- [x] `lookup_appointment`
- [x] `cancel_appointment`
- [x] `reschedule_appointment`
- [x] Strict JSON schemas for all eight
- [x] Authorisation guard on the two write tools

### Backend — orchestration
- [x] `SystemPromptBuilder` with token cap
- [x] `ConversationService` loop with both ceilings
- [x] Session token issue and verification
- [x] Message and tool-call persistence
- [x] `CostTracker` and the daily cap
- [x] `PublicChatController`
- [x] `ConversationQueryController`
- [x] Chat rate limits
- [x] Error codes: `AI_UNAVAILABLE`, `AI_LIMIT_REACHED`

### Frontend
- [x] Chat panel with message list and typing indicator
- [ ] Inline tool-activity indicator — **not built, and not an oversight.** `ChatReply` carries a
      reply, a status, a count and an appointment; there is no tool activity on it, because a turn is
      one non-streaming `POST` and streaming is out of scope for this phase (see *Scope*). Naming a
      tool the panel never saw would be inventing a fact about the request, which is the same mistake
      as reading a booking out of prose. The indicator says "Thinking", then "Still working" after
      six seconds. **The tool calls themselves are visible, with their arguments and their results,
      on `/conversations/[id]`** — which is where an owner needs them
- [x] Confirmation card from `appointmentCreated` — and proven against its counterfactual: a reply
      claiming a booking with the field nulled renders a paragraph and no card
- [x] Session persistence in `sessionStorage` — the transcript beside the token, because no public
      endpoint returns one. Reload resumes; a new tab does not
- [x] Degradation banner with a Classic Flow link
- [x] Persistent "book the classic way" affordance
- [x] Mobile layout for the chat panel — full width at 375 px, flow first, no sideways scroll
- [x] `/conversations` list and detail in the dashboard

### Testing
- [x] All level 1 tests
- [x] All level 2 tests
- [x] Level 3 corpus, tagged and excluded from CI
- [x] Schema assertion that no tool takes `business_id`
