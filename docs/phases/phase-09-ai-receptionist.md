# Phase 09 — AI Receptionist

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
- [ ] Each tool returns correct data for a valid context
- [ ] A `service_id` from another business → not found
- [ ] `cancel_appointment` with an unauthorised id → `NOT_AUTHORIZED`
- [ ] `reschedule_appointment` with an unauthorised id → `NOT_AUTHORIZED`
- [ ] `lookup_appointment` correct code + wrong phone → not found
- [ ] `create_appointment` on a taken slot → `SLOT_UNAVAILABLE`
- [ ] `find_available_slots` matches the engine exactly, employees resolved
- [ ] **No published schema contains a `business_id` property**

### Level 2 — orchestration, scripted model
- [ ] Tool call → execution → result → final answer
- [ ] Six tool calls in one turn → capped at five, graceful hand-off
- [ ] `SLOT_UNAVAILABLE` reaches the model as a structured result
- [ ] A text-only response is returned verbatim
- [ ] Message ceiling → conversation `CLOSED`
- [ ] Daily cost cap exceeded → no model call is made at all
- [ ] Provider exception → `503 AI_UNAVAILABLE`, conversation resumable
- [ ] Everything persisted with the correct `business_id`
- [ ] `authorizedAppointmentIds` grows only through the two sanctioned paths

### Level 3 — live model, `@Tag("llm")`, excluded from CI
- [ ] Booking, availability, cancel, reschedule, business info, service info categories
- [ ] Unknown-information prompts produce "I don't know" plus the phone number
- [ ] Adversarial prompts (bulk cancel, tenant switch, discount, prompt disclosure) produce no unauthorised
      tool call

Assertions target tool sequences and database state, never the model's wording.

## Definition of Done

- [ ] A customer books entirely by conversation, and the appointment is correct in the database
- [ ] The Receptionist reschedules and cancels only after ownership is proven
- [ ] It answers configured questions accurately and says "I don't know" otherwise
- [ ] It never states a slot, price or policy that did not come from a tool or the context
- [ ] The confirmation card renders from backend data, not from the reply text
- [ ] No tool accepts a tenant identifier
- [ ] Ceilings, rate limits and the cost cap are enforced server-side
- [ ] Every failure mode degrades to the Classic Flow
- [ ] Conversations are persisted and viewable by the owner
- [ ] Levels 1 and 2 pass in CI; level 3 passes locally

## Checklist

### Database
- [ ] `V7__ai.sql`
- [ ] `ai_conversations` with `authorized_appointment_ids`
- [ ] `ai_messages`
- [ ] Both indexes

### Backend — port and adapter
- [ ] `ChatModel` port and message types
- [ ] `OpenAiChatModel` adapter
- [ ] ArchUnit rule: the SDK is imported nowhere else
- [ ] `ScriptedChatModel` test double

### Backend — tools
- [ ] `ToolRegistry`
- [ ] `ToolContext`
- [ ] `get_business_info`
- [ ] `get_services`
- [ ] `get_service_details`
- [ ] `find_available_slots`
- [ ] `create_appointment`
- [ ] `lookup_appointment`
- [ ] `cancel_appointment`
- [ ] `reschedule_appointment`
- [ ] Strict JSON schemas for all eight
- [ ] Authorisation guard on the two write tools

### Backend — orchestration
- [ ] `SystemPromptBuilder` with token cap
- [ ] `ConversationService` loop with both ceilings
- [ ] Session token issue and verification
- [ ] Message and tool-call persistence
- [ ] `CostTracker` and the daily cap
- [ ] `PublicChatController`
- [ ] `ConversationQueryController`
- [ ] Chat rate limits
- [ ] Error codes: `AI_UNAVAILABLE`, `AI_LIMIT_REACHED`

### Frontend
- [ ] Chat panel with message list and typing indicator
- [ ] Inline tool-activity indicator
- [ ] Confirmation card from `appointmentCreated`
- [ ] Session persistence in `sessionStorage`
- [ ] Degradation banner with a Classic Flow link
- [ ] Persistent "book the classic way" affordance
- [ ] Mobile layout for the chat panel
- [ ] `/conversations` list and detail in the dashboard

### Testing
- [ ] All level 1 tests
- [ ] All level 2 tests
- [ ] Level 3 corpus, tagged and excluded from CI
- [ ] Schema assertion that no tool takes `business_id`
