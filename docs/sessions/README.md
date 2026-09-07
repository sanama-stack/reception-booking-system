# Session handoffs

One document per working session, newest last. Each is written to be fed to a fresh session that
has no memory of the previous one.

A handoff records what the *specification does not*: which way an ambiguity was resolved, which
approaches were tried and abandoned and why, the traps already paid for, and the state the machine
was left in. The design documents say what to build; these say what happened while building it.

| Session | Covers |
|---|---|
| [2026-09-07](./2026-09-07-phase-01-foundation.md) | Phase 01 — Foundation. Complete, CI green, published to GitHub. |
| [2026-09-07](./2026-09-07-phase-02-authentication.md) | Phase 02 — Authentication and Tenancy. Complete, 101 tests, verified in a browser. |
| [2026-09-07](./2026-09-07-session-aware-public-pages.md) | The landing, login and register screens notice a session. Closes an open redirect phase 02 shipped. |
| [2026-09-07](./2026-09-07-phase-03-business-setup.md) | Phase 03 — Business Setup, **backend half only**. 234 tests. Not pushed; CI has not seen it. |

**Reading order for a new session:** the newest handoff first, then
[../09-phase-plan.md](../09-phase-plan.md), then the current phase document.

A handoff whose title says "half" means the phase is deliberately unfinished — its own §1 says what
is missing and why. Do not read an unticked checklist box in the phase document as an oversight
without checking there first.
