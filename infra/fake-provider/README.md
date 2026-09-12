# The fake AI provider

A chat-completions endpoint that books an appointment the same way every time, so the Playwright
flow is deterministic in CI without an account, a budget, or a network.

It exists because of [ADR-0011](../../docs/adr/0011-the-e2e-fake-provider-lives-behind-the-base-url.md):
the E2E's model sits **behind `app.ai.base-url`**, not behind the `ChatModel` port. The application
under test is the one that ships, and `OpenAiChatModel` — request encoding, `strict: true`, `parse()`,
`parseArguments()` — stays on the path. Phase 11 originally said this would run "against the scripted
model"; `ScriptedChatModel` is on the test classpath and cannot be reached by a booted application.

## Running it

```bash
make up-e2e              # everything, with the backend pointed at this
make seed                # it books real seeded data, so there must be some
make check-fake-provider # the double's own self-test; no containers needed
```

## It is not a queue of canned replies, and it cannot be

A booking names a `service_id`, an `employee_id` and a `starts_at` that `make seed` generates fresh
on every run. A reply fixed in advance would name an id that does not exist and `create_appointment`
would refuse it.

So it answers **from the transcript it is sent**. `OpenAiChatModel.encode()` includes every earlier
`role: tool` message, so the ids are reachable: the `get_services` result carries the service, the
`find_available_slots` result carries the slot. This is the mirror of
[`receptionist-probe`](../../backend/tools/receptionist-probe/), which drives a real model against
stubbed tool results and threads a `service_id` the same way.

The policy is four states, chosen by **which tool results are already present** rather than by a turn
counter:

| Seen so far | Answer |
|---|---|
| nothing | call `get_services` |
| services | call `find_available_slots` for the service the customer named |
| slots | call `create_appointment` on the first slot, `starts_at` verbatim |
| a booking | text, quoting the Confirmation Code the tool returned |

An empty grid and a failed `create_appointment` are sentences naming the reason, not crashes — so a
failing E2E says why in the transcript.

## What the self-test protects

`node selftest.js` — 19 checks, no dependencies and no runner.

It asserts the transcript-reading property directly, because that is the one a plausible wrong
implementation gets wrong: fed a conversation where `get_services` has **already** returned, the
provider must move on rather than ask again, and it must still do so when there is extra chatter in
front of the tool calls.

**It has been shown to fail.** Four defects were planted in `server.js` one at a time and each was
named by the check meant to name it:

| Planted | Named by |
|---|---|
| a turn counter instead of reading the transcript | *extra conversation before the tools does not shift the state* |
| `starts_at` normalised through `Date` instead of copied | *starts_at is copied verbatim, offset included* |
| always the first service, ignoring what was asked for | *find_available_slots gets the id of the service the customer named* |
| the search starting today | *the search starts at least two days out (T44)* |

The turn-counter defect is the one worth dwelling on: it was caught **only** by the padded variant.
The unpadded case happened to align with a counter, so a suite with just that case would have passed
a provider that cannot survive a customer saying hello first.

## Two things that will bite

**A missing API key does not fail — it degrades.** `OpenAiChatModel.complete()` throws before it
opens a socket when `app.ai.api-key` is empty, and the Receptionist answers a provider failure by
falling back to the Classic Flow. An E2E pointed at this provider with no key set would book
successfully through the wrong door and look green. `docker-compose.e2e.yml` sets a dummy key for
exactly this reason.

**The booking is placed two days out, not tomorrow (T44).** The nearest bookable slot falls inside a
24-hour Cancellation Window, and the E2E goes on to follow the Manage Link and cancel. Measured
against Salon Aria: 42.9 hours. See the comment on `SEARCH_FROM_DAYS` for the case where two days is
not enough.

## Verified against the real application

A backend booted with `OPENAI_BASE_URL` pointed here booked a real Appointment for the seeded
`salon-aria` in one turn, and the row reads `source = AI` — which is the proof it did not quietly
degrade to the Classic Flow.
