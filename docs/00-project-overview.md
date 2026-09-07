# 00 — Project Overview

> **Product name (placeholder):** **Reception**
> Rename in one pass before launch; no document depends on the name.

## What this is

A multi-tenant B2B SaaS where appointment-based businesses configure their services, staff and hours,
and their customers book appointments by talking to an AI receptionist on a public booking page.

The business gets a scheduling and customer-management dashboard. The customer gets a conversation.
Behind the conversation is a deterministic booking engine that the AI cannot bypass.

## The one-sentence thesis

**The AI is an interface over real backend capabilities, not the system itself.**

Everything in this repository is arranged to make that statement literally true and visibly demonstrable:
the Receptionist can only act through a fixed set of validated tools; those tools call the same endpoints
the non-AI booking flow calls; and the database makes double-booking structurally impossible regardless of
what any layer above it believes.

## Who uses it

| Actor | Authenticated? | Surface |
|---|---|---|
| Business Owner | Yes (email + password) | Dashboard |
| Customer | No — never has an account | Public booking page |
| Receptionist (AI) | N/A — server-side, scoped to one Business | Tools only |

Target verticals: barbers, salons, spas, dental and medical clinics, personal trainers, gyms, auto repair,
consultants, tutors. **No vertical is special-cased anywhere.** The seed data deliberately ships a barber
shop *and* an auto repair shop so this is provable rather than asserted.

## Document map

| Document | Answers |
|---|---|
| [00-project-overview.md](./00-project-overview.md) | What is this, who is it for |
| [01-prd.md](./01-prd.md) | Requirements, flows, edge cases, acceptance criteria, user stories |
| [02-product-architecture.md](./02-product-architecture.md) | Layers, modules, runtime topology, stack |
| [03-data-model.md](./03-data-model.md) | Entities, columns, constraints, indexes, tenancy |
| [04-api-overview.md](./04-api-overview.md) | Endpoint surface, shapes, error contract |
| [05-ai-architecture.md](./05-ai-architecture.md) | Tools, orchestration loop, safety, cost |
| [06-security.md](./06-security.md) | Authn, authz, tenant isolation, abuse, injection |
| [07-mvp-scope.md](./07-mvp-scope.md) | In, out, and the MVP Definition of Done |
| [08-testing-strategy.md](./08-testing-strategy.md) | What is tested, at which level, and how the AI is tested |
| [09-phase-plan.md](./09-phase-plan.md) | 11 phases, dependency graph, sequencing rationale |
| [phases/](./phases/) | One implementation checklist per phase |
| [adr/](./adr/) | Six decisions that were genuine trade-offs |
| [future/future-features.md](./future/future-features.md) | V1.1 / V1.2 / V2 roadmap |
| [../CONTEXT.md](../CONTEXT.md) | Domain glossary — the vocabulary all code should use |

## Reading order for a new developer

1. This file
2. [07-mvp-scope.md](./07-mvp-scope.md) — so you know what you are *not* building
3. [../CONTEXT.md](../CONTEXT.md) — so you name things correctly
4. [02-product-architecture.md](./02-product-architecture.md) and [03-data-model.md](./03-data-model.md)
5. [09-phase-plan.md](./09-phase-plan.md), then the current phase document

## Non-goals

This is a portfolio project built to demonstrate production-grade engineering. It optimises for
**depth in the hard parts** — multi-tenancy, concurrent scheduling, timezone correctness, constrained AI —
and deliberately *not* for breadth of features. Anything that would consume the project's time on
undifferentiated UI work is out of scope; see [07-mvp-scope.md](./07-mvp-scope.md).
