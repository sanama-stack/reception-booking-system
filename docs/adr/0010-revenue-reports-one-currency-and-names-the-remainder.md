# Revenue reports one currency and names the remainder

**Status:** accepted
**Date:** 2026-09-11

`GET /analytics/summary` answers with a single `revenue` object in the Business's **current**
currency. An Appointment keeps the currency it was priced in — `AnalyticsRepository`'s sum is
filtered `and a.currency = :currency` and `AnalyticsService` passes `business.currency()` — so a
Business that changes currency in Settings has older completed revenue the summary no longer
reports, and the contract has nowhere to say so.

The figure is never *wrong*: the amount stated in GEL really is the GEL revenue. It is a partial
truth presented in the shape of a whole one, and the screen has no way to know it is looking at
part.

**We keep the single figure and add a subordinate `excluded` list beside it** — one entry per other
currency found among the same COMPLETED Appointments in the same range, each with its own sum,
empty when there are none. The primary number keeps its privileged position and its `basis`; the
remainder is a footnote the screen can render as one, and the client is never asked to decide which
of several totals is "the" revenue.

## Why not sum across currencies

Adding lari to euros produces a number that is not money. This was never a live option; it is
recorded because it is the thing a future reader will assume the obvious fix was.

## Why not report per-currency as co-equal totals

Turning `revenue` into a list of `{currency, amount}` is the more correct model and was the real
alternative. It was declined on two grounds.

It changes a shipped contract's shape rather than extending it, which costs the response builder,
the `/analytics` screen's layout, and every test that reads `revenue.amount`. And it makes the
ordinary case pay for the rare one: a Business that has never changed currency — which is every
Business today and, `make seed`'s two tenants included, every Business we intend to demonstrate —
would get a one-element list the screen must unwrap before it can show anything. The remainder is
an exception, and the contract should read like one.

The cost of this choice is that a Business whose *entire* history straddles a change reads a
headline figure covering only the newer part, with the older part in a list below it rather than
given equal weight. That is a real cost and it is accepted: the alternative gives equal weight to
every currency at the price of making the common case harder to read.

## Why the remainder carries amounts rather than just a count

A bare "3 appointments excluded" tells the owner something is missing without telling them whether
it matters. Each entry in `excluded` is a sum within one currency, so no cross-currency arithmetic
happens anywhere — the thing the first section refuses is refused here too.

## Consequences

- **This is not yet built.** The ADR records the decision; the field lands in phase 11 alongside the
  `/analytics` screen change. A reader looking for `excluded` in `AnalyticsResponses` today will not
  find it.
- The existing test — *"after a currency change, revenue reports the new currency only"* — becomes
  wrong as written once `excluded` exists, and is the natural place to assert the new behaviour. It
  was always a record of what the endpoint did rather than an argument that it was right
  ([phase-10 backend handoff](../sessions/2026-09-11-phase-10-backend.md) §3.5).
- `excluded` is an empty list rather than `null` when there is no remainder, matching `topServices`
  and unlike `Rates`, which is deliberately `null` when there were no appointments at all. The
  distinction holds: `Rates` has no meaningful zero, a remainder does.
- Nothing about an Appointment changes. Prices stay snapshotted in the currency they were taken in,
  which is what makes last month's revenue stable when somebody edits the catalog — the property
  `AnalyticsRepository` already documents and the reason the currencies diverge in the first place.
- **`basis` covers the primary figure only.** It says `COMPLETED_ONLY`, and each `excluded` entry is
  filtered the same way, so the constant is true of every number in the object. If a future basis
  ever varies per entry, this stops being true silently.
