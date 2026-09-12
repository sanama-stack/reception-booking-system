# The LLM is confined to a tool registry and is never authoritative

**Status:** accepted

An AI receptionist that can book appointments is only trustworthy if the model cannot be the source of truth
for anything that matters. The model is responsible for language — understanding intent, choosing a tool,
asking for missing details, phrasing the reply — and for nothing else. Availability, prices, policies,
tenancy and whether a booking succeeded are all decided by backend services the model reaches only through
eight validated, strictly-schema'd tools — nine
since `resolve_date` landed under test, and the count is the only thing that number changes: whatever it
is, it is the complete list of what the model can do.

## Considered options

- **Intent classification then routing** (as the brief proposed): adds a failure mode without adding a
  capability. A misclassified intent routes the whole turn wrongly, and mixed intents in one message
  ("what are your hours, and can I book Thursday?") have no correct classification. The intent names survive
  as test-suite categories instead.
- **Giving the model database or query access**: makes tenant isolation a prompt-engineering problem.
- **Letting the model compute availability from a dump of the schedule**: makes correctness probabilistic.

## Consequences

- **No tool takes a `business_id` parameter.** Tenancy comes from the conversation record, so cross-tenant
  access is not blocked — it is inexpressible. A test asserts this against the published schemas so a future
  tool cannot quietly introduce one.
- Write tools require the appointment id to be in a server-held `authorizedAppointmentIds` set, populated
  only by a successful `create_appointment` or `lookup_appointment` in the same conversation, or by a valid
  Manage Link. A hallucinated id achieves nothing.
- The confirmation UI renders from the API's `appointmentCreated` object, never from the model's prose. A
  model that claims a booking that did not happen produces a visible absence instead of a convincing lie.
- Prompt-injection defence is structural rather than textual: there is no bulk tool, no filter parameter,
  and nothing resembling a query, so the worst outcome of a successful injection is a rude answer.
- The Receptionist adds no capability the Classic Flow lacks, which is why every AI failure mode can
  degrade to it — and why the Classic Flow was built first.
