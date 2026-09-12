# The E2E's deterministic model is a fake provider behind `base-url`, not a bean

**Status:** accepted
**Decided:** 2026-09-12

[08-testing-strategy.md](../08-testing-strategy.md) §8 specifies one Playwright flow, and step 4 of it
books through the Receptionist. That step needs a model that says the same thing every run, in CI,
with no account and no spend.

[phase-11](../phases/phase-11-hardening-and-deployment.md) describes this as running *"against the
scripted model"*. **That sentence names a mechanism that cannot be reached.** `ScriptedChatModel`
lives in `backend/src/test` — the JVM test classpath — and Playwright drives a booted application.
`OpenAiChatModel` is the only `ChatModel` on the main classpath, and there is no profile-conditional
model bean anywhere in `dev.reception.ai`. The phase document was describing an option rather than
recording a ruling, and its wording is corrected alongside this decision.

**The deterministic model is served over HTTP at `app.ai.base-url`**, as a fake provider speaking the
chat-completions shape, brought up only in the E2E topology. The application is not modified and does
not know it is under test.

## Considered options

- **A fake provider at `base-url`.** Chosen. The application runs exactly as it ships; the only
  difference is which host it resolves. This is the affordance
  [ADR-0009](./0009-a-hand-written-rest-client-instead-of-an-sdk.md) closes on — *"`base-url` is
  configuration: a proxy, a gateway, a local model server can be pointed at without code"* — used for
  the case that motivated writing it down.
- **A deterministic `ChatModel` bean on the main classpath**, `@Primary` behind a property defaulting
  off. Rejected on two counts. It would leave `OpenAiChatModel` exercised by **nothing** end to end:
  every other test in the suite already resolves the `@Primary` double, deliberately, so the one test
  that boots the whole application is the only place the adapter can be crossed — and the adapter is
  where strict-mode encoding, `parse()` and `parseArguments()` live. And it would ship a model double
  inside the production artifact behind a single property, where this project's own standard for a
  fixture that must never run in anger is three guards, one of them a runtime check
  (`SeedRunner`). Rejected on fidelity first; the packaging risk is the second reason, not the first.
- **Recorded cassettes replayed byte for byte.** Rejected as not viable rather than as inferior.
  Nothing can be captured — the account is out of credits — and the seeded ids a booking conversation
  must name are generated per run, so an exact replay could not match a request anyway. It degrades
  into the chosen option with heavier fixtures.

## The fake provider must read the request, and this is the part that is easy to get wrong

A queue of canned replies **cannot work**, and the phase document's "scripted" framing invites exactly
that shape. Booking by chat requires `create_appointment` to be called with a real `service_id` and a
real slot, and both are generated when `make seed` runs — different on every rebuild. A reply fixed
in advance would name an id that does not exist and the tool would refuse it.

The ids are reachable: `OpenAiChatModel.encode()` sends the whole message list on every call,
including the `role: tool` messages carrying earlier tool results. So the fake provider answers from
what it was sent — it reads the `get_services` result to find the service, then the
`find_available_slots` result to find the slot. It is a small deterministic policy over the
conversation so far, not a tape.

This is the mirror of `backend/tools/receptionist-probe/probe.py`, which drives a real model against
stubbed tool results and already threads a `service_id` the same way. The pattern is established in
this repository; only the side being faked is new.

## Consequences

- **`OpenAiChatModel` is crossed end to end for the first time.** Request encoding, `strict: true`,
  the tool-call shape, `parse()` and `parseArguments()` all run against something that is not the
  class that produced them.
- **`OPENAI_BASE_URL` has to reach the backend container.** It is in `.env.example` and read by
  `application.yml`, but neither compose file passes it, so today the container cannot be pointed
  anywhere. Wiring it is part of this decision rather than a detail under it.
- **The fake provider runs in the E2E topology only.** It is test infrastructure and is not built
  into, referenced by, or shipped with the application image.
- **A fake that drifts from the real wire shape would pass while the real one fails.** The shape it
  must produce is small and is pinned by `OpenAiChatModel.parse()`; what protects against drift is
  that the adapter validates rather than assumes, which ADR-0009 already required of it.
- **This does not make the Receptionist's *behaviour* tested.** The flow proves the plumbing carries a
  conversation to a booked Appointment. What a real model does with a real utterance is the level-3
  corpus and the probe harness, and neither is replaced here.
