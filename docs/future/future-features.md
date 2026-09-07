# Future Roadmap

Everything here is **out of the MVP**. The purpose of this document is to keep it out: a feature with a home
in a named future release stops arguing for a place in the current one.

Grouped by what each stage is *for*, not by size.

---

## V1.1 — Close the gaps the MVP deliberately left

Small items that were cut for scope, not for principle. Each is a few days.

| Feature | Why it was cut | What it needs |
|---|---|---|
| **Email verification at signup** | Put a wall in front of the demo | Verification token, a state on `users`, a gate on the public page going live |
| **`PENDING` status + approval queue** | Implies notifications, expiry rules and a queue UI nobody specified | `requires_approval` on `businesses`, a fifth status, owner notification, an expiry job |
| **Staff (`STAFF`) login** | The role exists; the flow adds authorization surface without proving anything new | Invite flow, scoped dashboard showing only own appointments, `employee.user_id` finally used |
| **Password reset** | No user-visible data depended on it in a local demo | Reset token, email template, rate limiting |
| **Customer anonymisation** | Deletion is refused today when appointments exist | Scrub name/phone/email, retain the appointment for analytics |
| **AI conversation purge job** | 90-day retention is documented but not enforced | Scheduled delete |
| **Streaming chat responses** | Latency is legible enough with a typing indicator | SSE endpoint, incremental rendering |
| **Recurring closures** | Every public holiday is entered by hand | A recurrence rule on `business_closures` |
| **Waitlist** | Not needed to prove the engine | Notify when a cancellation frees a matching slot |
| **iCal feed per employee** | One-way, and much cheaper than real sync | Signed feed URL |

---

## V1.2 — Integrations and channels

The point of this stage: everything here plugs into a port that already exists, which is the payoff of the
MVP's abstractions.

| Feature | Where it plugs in |
|---|---|
| **SMS notifications** (Twilio) | New `channel` on `notifications`, new adapter behind the sender port. The outbox does not change |
| **WhatsApp Business** | Same port; plus inbound message handling routed into the existing conversation service |
| **Google Calendar (one-way push)** | New integration adapter subscribing to appointment events |
| **Google / Outlook two-way sync** | Genuinely large: conflict resolution, webhook handling, an external-id column, and a policy for who wins |
| **Keycloak or social login** | Replaces the token issuer only; controllers are already resource-server shaped ([ADR-0001](../adr/0001-self-issued-jwt-over-keycloak.md)) |
| **Stripe deposits** | Payment intent at booking, a `payment` table, refund policy on cancellation |
| **Stripe subscriptions** (the actual SaaS business model) | Plan tiers, a per-business subscription, feature gating, a billing portal |
| **Overnight business hours** | Lifts the `closes_at > opens_at` limitation; the engine must handle day-spanning intervals ([ADR-0003](../adr/0003-wall-clock-rules-utc-instants.md)) |
| **Multi-language Receptionist** | Language per business and per conversation; multiplies the AI test corpus |

---

## V2 — Capabilities that change the product's shape

Each of these adds a new axis to the data model or the engine, which is exactly why none belongs earlier.

**Multiple locations.** A second tenancy axis below `business_id`: hours, employees and services all become
location-scoped, and every query and index in the schema is affected. This is the largest single change on
the roadmap.

**Full customer accounts.** Customers log in, see history across visits, save preferences. Requires deciding
whether identity is global (one login, many businesses) or per-tenant — a decision the MVP avoided
deliberately by using Confirmation Codes and Manage Links instead.

**Voice receptionist.** Telephony, speech-to-text, text-to-speech, and turn-taking latency budgets. The tool
layer is reusable unchanged, which is the strongest argument that the MVP's AI architecture was right.

**Resource booking beyond people.** Rooms, chairs, bays, equipment. Generalises `Employee` into a bookable
resource with a type — the exclusion constraint already generalises, which is a quiet benefit of
[ADR-0002](../adr/0002-exclusion-constraint-for-booking-conflicts.md).

**Group bookings and classes.** One appointment, many customers, a capacity limit. Breaks the one-customer
assumption throughout the model.

**Recurring appointments.** A series entity, exception handling, and a policy for what "cancel" means for
one occurrence versus all of them.

**Advanced analytics.** Cohorts, retention, utilisation per employee, revenue forecasting, no-show
prediction. Likely a separate read model.

**AI business insights.** "Your Thursday afternoons are 40% empty; consider a promotion." Requires the
analytics above first, and careful framing so a suggestion is never presented as a fact.

**Marketing automation.** Win-back campaigns, birthday offers, review requests. Requires consent tracking
and unsubscribe handling — a compliance surface, not just a feature.

**Native mobile apps.** Only worth doing once the web product has users who ask for it.

---

## Deliberately not planned

Recording these prevents them from being re-proposed:

- **Separate database per tenant.** Shared-schema with `business_id` plus composite foreign keys is
  sufficient and vastly simpler to operate. Revisit only if a customer contractually requires isolation.
- **Microservices.** There is one bounded context. Splitting it would add network calls between modules that
  share a transaction today — in particular booking and notification, which must commit together.
- **Event sourcing for appointments.** `appointment_events` already gives an audit trail. Full event
  sourcing would add projection complexity for no product requirement.
- **A vector database / RAG.** FAQ volume never justifies it, and a bounded, structured context is a
  *stronger* hallucination control than retrieval, not a weaker one.
- **GraphQL.** The clients are one dashboard and one booking page, both with known queries.
