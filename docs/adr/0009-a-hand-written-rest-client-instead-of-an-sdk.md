# The OpenAI adapter is a hand-written RestClient call, not an SDK

**Status:** accepted

[05-ai-architecture.md](../05-ai-architecture.md) §10 requires that exactly one class knows which provider
is behind the `ChatModel` port, and calls that class "the only class permitted to import the OpenAI SDK".
It does not say which SDK. Phase 09 imports none: `OpenAiChatModel` builds the chat-completions request
with Spring's own `RestClient` and reads the response with Jackson, in about 150 lines.

The decisive fact is that **the port already provides the seam an SDK's abstraction would provide.** The
reason `ChatModel` exists is testing — the orchestration loop runs against `ScriptedChatModel` with no
network, no cost and no flakiness — and that benefit is unchanged by what sits on the far side of it. What
an SDK would add here is typed request objects for a payload that is four fields deep, in exchange for a
dependency tree, a version to track, and a second vocabulary that stops at this one file's boundary anyway.

## Considered options

- **The official `com.openai:openai-java` SDK.** Typed, maintained by the provider, with first-class
  structured-output support. Rejected on weight rather than on quality: its types cannot cross the port, so
  the whole benefit is confined to the one class that would have been easy either way — and the strict-mode
  schemas are already built by `ToolSchemas` in exactly the shape the wire wants.
- **Spring AI (`spring-ai-openai`).** Spring-native and well integrated. Rejected because it ships its own
  `ChatModel`, tool-callback and function-calling abstractions that overlap the ones this document already
  specifies. We would either run two tool registries or bend the documented design around the framework's,
  and the design is the part that is load-bearing — `authorizedAppointmentIds`, the absent `business_id`,
  the confirmation rendered from a tool result — none of which a framework's tool-calling would enforce.
- **A hand-written `RestClient` call.** Chosen. No new dependency, one screen of code, and complete control
  over the request body, which matters because `strict: true` and `additionalProperties: false` are the
  mechanism that removes malformed-argument handling from the runtime.

## Consequences

- **The isolation rule is enforced differently, and no more weakly.** With no SDK to import, a leak is a
  second class that knows the wire format. `AiProviderIsolationTest` asserts that nothing outside
  `dev.reception.ai.openai` depends on the adapter, that no other class in the AI layer speaks HTTP at all,
  and that only the adapter reads the API key.
- **A provider change is still one class**, which was the second reason the port was drawn. It is now one
  class with no dependency to remove alongside it.
- **`base-url` is configuration.** A compatible endpoint — a proxy, a gateway, a local model server — can be
  pointed at without code. The adapter therefore validates the response shape rather than assuming it, which
  an SDK would have done on its behalf.
- **Streaming stays out of scope**, as V1.1 already had it. Server-sent events are the one place a
  hand-written client would cost meaningfully more than an SDK, and adding it is a second port method
  rather than a change to this decision.
- **Model pricing lives in configuration** (`prompt-cents-per-million-tokens`), because no endpoint reports
  it and the daily cap needs a number. This is why the column is called `estimated_cost_cents`.
