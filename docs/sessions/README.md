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
| [2026-09-07](./2026-09-07-phase-03-frontend.md) | Phase 03 — the settings screens and the onboarding checklist. **Phase 03 complete.** Still not pushed. |
| [2026-09-07](./2026-09-07-phase-04-backend.md) | Phase 04 — Services and Employees, **backend half only**. 416 tests. `dev` pushed; CI green. |
| [2026-09-08](./2026-09-08-phase-04-frontend.md) | Phase 04 — the services and employees screens. **Phase 04 complete.** `main` merged and protected, and a two-phase-old timezone defect fixed. |
| [2026-09-08](./2026-09-08-phase-05-availability-engine.md) | Phase 05 — the availability engine, **backend half only**. 538 tests. Merged to `main` with a merge commit, which ends the squash conflicts. |
| [2026-09-08](./2026-09-08-phase-05-frontend.md) | Phase 05 — the availability preview. **Phase 05 complete.** Found a phase-02 defect: the transparent refresh never fires in a browser. §7.2 carries the fix, merged into `dev` on 2026-09-09 and still unpushed. |
| [2026-09-09](./2026-09-09-phase-06-backend.md) | Phase 06 — Appointments, **backend half only**. 625 tests, the exclusion constraint proven under twenty threads, and the last stub deleted. Still not pushed. |
| [2026-09-09](./2026-09-09-phase-06-frontend.md) | Phase 06 — the six Appointments and Customers screens. **Phase 06 complete.** Verified in a browser, where a `409` was raced for real and a field error found to be arriving under a name the request does not have. Merged to `main` as part of pull request #3. |
| [2026-09-09](./2026-09-09-phase-07-notifications.md) | Phase 07 — Notifications. **Phase 07 complete and merged.** 674 tests. The outbox, the poller and the Manage Link; a documented index corrected because it would have made a required email impossible. First session in five to end with nothing unpushed. |
| [2026-09-09](./2026-09-09-phase-08-backend.md) | Phase 08 — Public Booking, **backend half only**. 720 tests, no migration at all. Two questions three handoffs had carried were put to the principal and answered: the public booking body, and how a rescheduling customer sees their own slot. |
| [2026-09-09](./2026-09-09-phase-08-booking-page.md) | Phase 08 — the `/book/{slug}` page, **one of the frontend half's two pages**. Verified in a browser: a stranger books, races a real `409`, and the email's Manage Link resolves. Found the phone refusal telling Customers to change a Settings screen they cannot reach, and fixed it. 726 tests. Committed as `d6b797d` and `9785269` by the next session. |
| [2026-09-09](./2026-09-09-phase-08-manage-page.md) | Phase 08 — the `/manage/{token}` page. **Phase 08 complete.** A real reschedule and a real cancel driven in a browser, a `409` raced on the reschedule path, and the token exclusion proven against both grids. Found that the booking page promises a confirmation email it may not send. |
| [2026-09-09](./2026-09-09-confirmation-promise.md) | The booking page's email promise, and **ADR-0007**. Built no page: fixed the defect the previous handoff left as a decision, found a third case it had called safe, and found that the name rule's stated justification is false (issue #5). 728 tests. Committed as `44f42e4` by the next session. |
| [2026-09-09](./2026-09-09-manage-page-promise.md) | The manage page's email promise, and **ADR-0008**. Built no page: took the sibling defect the previous handoff handed to a separate session that never touched this tree, confirmed its trigger link by link, and found the lookup endpoint will widen it from a race to the ordinary case. 731 tests. Cleared the two-session commit backlog. **`dev` is six commits ahead of `origin` and CI has seen none of them — the pull request is the next action.** |
| [2026-09-09](./2026-09-09-copy-states-and-the-stranded-reminder.md) | The four copy states, rendered at last, and the reminder they stranded. **Phase 08 merged** as pull request #6, ten commits, CI green at last. Built no page: found that a reschedule with no address on file leaves its old reminder `PENDING`, aimed at a time the appointment has left, and fixed it with a regression test. 732 tests. Issues #7 and #8 opened. |
| [2026-09-09](./2026-09-09-phase-09-backend.md) | Phase 09 — the AI Receptionist, **backend half only**. 793 tests, `V7`, and **ADR-0009**: a hand-written RestClient, no SDK. Wiring the tools onto shipped code found three defects in it — the Receptionist could have cancelled around the Cancellation Window, cancel and reschedule asked that question two different ways, and `lookup_appointment` would have crossed tenants. Level 3 is written and has never run. |
| [2026-09-10](./2026-09-10-the-slot-race-deadlock.md) | Issues #5, #7, #8 and #9 cleared before the phase 09 frontend half. Built no screen. The widened concurrency assertion fired on the next build and named a **production defect**: a Postgres deadlock on the exclusion constraint answered nineteen of twenty slot-race losers `500` instead of `409`. Fixed with an advisory lock — retry alone was measured and is not enough. Also aligned `appointmentCreated` onto the Classic Flow's own record before building on it. 798 tests, `dev` pushed and green throughout. |

**Reading order for a new session:** the newest handoff first, then
[../09-phase-plan.md](../09-phase-plan.md), then the current phase document.

A handoff whose title says "half" means the phase is deliberately unfinished — its own §1 says what
is missing and why. Do not read an unticked checklist box in the phase document as an oversight
without checking there first.
