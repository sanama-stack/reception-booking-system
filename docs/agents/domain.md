# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring
the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — the domain glossary. It is *binding*: class, table and
  endpoint names follow it (`docs/09-phase-plan.md` §5, rule 3).
- **`docs/adr/`** — read the ADRs that touch the area you are about to work in.

This repo also carries a full design suite under `docs/`. It is not a substitute for the two
above, but when the work touches one of these areas, the relevant document is the authority:

| Area | Document |
|---|---|
| Requirements and acceptance criteria | `docs/01-prd.md` |
| Layering, module map, tenant isolation | `docs/02-product-architecture.md` |
| Schema, constraints, indexes | `docs/03-data-model.md` |
| Endpoint surface and error codes | `docs/04-api-overview.md` |
| Tool registry and AI constraints | `docs/05-ai-architecture.md` |
| Security controls and accepted risks | `docs/06-security.md` |
| What is in and out of the MVP | `docs/07-mvp-scope.md` |
| What is tested, at which level | `docs/08-testing-strategy.md` |
| Build order and per-phase checklists | `docs/09-phase-plan.md`, `docs/phases/` |
| What happened while building | `docs/sessions/` |

This is a single-context repo. There is no `CONTEXT-MAP.md` and no per-context `CONTEXT.md`;
if one appears later, read it instead of the root glossary alone.

## File structure

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-self-issued-jwt-over-keycloak.md
│   ├── 0002-exclusion-constraint-for-booking-conflicts.md
│   ├── 0003-wall-clock-rules-utc-instants.md
│   ├── 0004-llm-confined-to-tools.md
│   ├── 0005-database-outbox-instead-of-queue.md
│   ├── 0006-employee-separated-from-user.md
│   ├── 0007-booking-response-says-whether-a-confirmation-was-sent.md
│   └── 0008-the-manage-page-says-whether-an-address-is-on-file.md
├── backend/
└── frontend/
```

## Use the glossary's vocabulary

When your output names a domain concept — an issue title, a refactor proposal, a hypothesis,
a test name, a class or a column — use the term as defined in `CONTEXT.md`. Each entry lists
the synonyms it explicitly avoids; do not drift to them. Writing `Booking` where the glossary
says `Appointment`, or `Staff` where it says `Employee`, is a defect here rather than a style
preference.

If the concept you need is not in the glossary yet, that is a signal: either you are inventing
language the project does not use (reconsider), or there is a real gap (note it for
`/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently
overriding:

> *Contradicts ADR-0002 (exclusion constraint for booking conflicts), but worth reopening
> because…*
