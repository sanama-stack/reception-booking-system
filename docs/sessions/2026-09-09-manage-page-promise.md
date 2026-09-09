# Session handoff — 2026-09-09 — the manage page's email promise, and ADR-0008

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand and carries the two things that must happen before anything else; §2 is the decision and
> why the recommendation was not the obvious one; §5 is what a fresh session must not redo.

---

## 1. Where the project stands

**Phase 08 is complete and was complete before this session opened.** This session built no page and
closed no checklist. It fixed the sibling of the defect ADR-0007 fixed — the one the previous
handoff's §4.1 wrote up, handed to a separate session, and could not confirm had been picked up.

**It had not been.** `git worktree list` showed only the main checkout, `manage-flow.tsx` was
untouched in the working tree, and no commit since `d2cd169` had gone near it. Whatever that session
was asked to do, nothing of it reached this repository. The defect was still open when this session
started, and this session took it.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Working branch | `dev` |
| Backend | **731 tests, built green** (728 before, plus this session's three) |
| Frontend gates | `lint`, `typecheck`, `format:check`, `build` — **all four run, all clean** |
| Migrations | **None.** No table and no column was added |
| New ADR | **ADR-0008**, `docs/adr/0008-the-manage-page-says-whether-an-address-is-on-file.md` |

### The tree, and the backlog this session finally cleared

**Both sessions are committed.** The previous handoff's P0 — "commit this session's work" — had not
been done either, so this session ended by committing both, as two commits rather than one, because
they are two decisions with two ADRs and two handoffs:

```
Stop the manage page promising an email nobody enqueued   ← this session, HEAD
44f42e4 Tell a Customer whether a confirmation is actually coming  ← ADR-0007's session
```

(This session's own commit is named by its subject rather than its hash, because it is the commit
this file is *in* — a hash written here would be the hash of the tree before this file was added.)

Five files carried **both** sessions' edits — `PublicResponses.java`,
`PublicFieldAllowListTest.java`, `types.ts`, `docs/04-api-overview.md` and `docs/sessions/README.md`
— so the split was made by reverting this session's edits from them, committing, and restoring. Two
checks were run rather than trusting that: the two commits' combined diff is byte-identical to the
pre-split working tree, and `44f42e4` compiles with `compileJava` **`FROM-CACHE`**, meaning its
sources hash-match a compilation that had already happened — the previous session's own green build.
Neither commit is a reconstruction anybody has to take on faith.

**`dev` is now six commits ahead of `origin/dev`, and CI has seen none of them.** `origin/dev` is
still `68ce872`. The phase-08 pull request two handoffs have now asked for is still owed, and it has
grown a second ADR since it was first requested. That is P0 (§6).

**`44f42e4` was never run through a full build in the exact shape it was committed in.** It is the
state the previous session tested at 728 tests, and the cache hit says the Java sources match — but
the run that proved 731 was against both sessions' work together. If a bisect ever lands on it, that
is the caveat.

---

## 2. The decision, and why the recommendation was not the obvious one

### The defect, and that it is genuinely reachable

`manage-flow.tsx:221` promised *"A confirmation email should reach you within a minute or two"*
unconditionally after a cancel or a reschedule. Both `NotificationEnqueuer.appointmentCancelled` and
`appointmentRescheduled` return early without writing a row when the Customer has no address.

The previous handoff rated the window "medium — reasoned from code, not observed", and asked for the
trigger to be confirmed before any contract moved. It was, link by link, at the file:

| Link | Where |
|---|---|
| The token outlives the address | `ManageTokenService.issue` — expiry is `endsAt + 24h`, so it covers the whole span from booking to appointment |
| Blank is a legal request body | `PatchCustomer.email` is `@Email` with **no** `@NotBlank`, and Jakarta's `@Email` accepts the empty string |
| Blank clears the column | `CustomerService.patch` → `trim("")` → `""` → `applyCorrection` → `isBlank()` → `null` |
| The dashboard actually sends it | `customer-form.tsx:43` submits `email: values.email.trim()`; emptying the field sends `""`. `type="email"` without `required` does not block it. **Two clicks, not a curl** |

### What was found that the previous handoff did not have, and it moved the recommendation

**The window is not narrow for long, because of something already built.**
`POST /public/appointments/lookup` returns the same `ManagedAppointment`, and `PublicRequests.Authority`
accepts a Confirmation Code and phone number for cancel and reschedule — `the_lookup_proof_also_cancels`
already proves it. No page uses it yet (previous handoff §4.3; phase 10 or 11 owes one). **When that
page ships the email premise dies completely:** a Customer who booked with no address gets their code
on screen — ADR-0007's own state 3 — types it into the lookup page, cancels, and is promised a
message on their first attempt. Not a race. The ordinary case. A client-side softening would have had
to be undone at that point, which is what ruled out the cheap option the previous handoff floated.

**`ManagedAppointment` is returned by four endpoints, not two.** `GET /manage`, `POST /lookup`,
cancel and reschedule. That is what decided the field's *name*, and it is the part of the ruling most
likely to be second-guessed by someone who has only read ADR-0007.

### The ruling: option C

Four options were put to the principal with a recommendation. C was chosen.

| | Verdict |
|---|---|
| **A** — soften the client sentence only | Rejected: dies when the lookup page ships |
| **B** — reuse `confirmationSent` on `ManagedAppointment` | Rejected: inaccurate on the two read endpoints, **and passes the field allow-list silently** |
| **C** — `emailOnFile` on `ManagedAppointment` | **Chosen** |
| **D** — a write-only response carrying `confirmationSent` | Rejected on cost, not on merit — see ADR-0008 |

The disclosure question the previous handoff flagged resolved *in favour* of the field. ADR-0007
accepted this same bit behind a **phone number alone**. This surface is reached by a signed
single-appointment token, or by a code **and** the phone that booked. Both are strictly stronger
proofs, so a bit acceptable there cannot be less acceptable here. The address itself is still never
returned and that was not reopened.

Full reasoning in **ADR-0008**. Read it before touching any of this.

---

## 3. The change, file by file

**Backend.**

| File | |
|---|---|
| `publicapi/PublicResponses.java` | `ManagedAppointment` gains `emailOnFile`; `of(…)` gains the parameter |
| `publicapi/PublicAppointmentController.java` | Injects `CustomerService`; `render(…)` sources the bit from `hasEmail()` |
| `publicapi/PublicFieldAllowListTest.java` | `emailOnFile` added to `ALLOWED`, with the reason |
| `publicapi/PublicAppointmentAuthorityTest.java` | +3 tests, +1 assertion, +1 helper |

`render(…)` is the single place all four endpoints assemble their response, so one line covers the
whole surface. `Customer.hasEmail()` now has three callers and is still the only definition of
"reachable by email" — the property ADR-0007 bought, extended rather than duplicated.

**Frontend.**

| File | |
|---|---|
| `lib/public/types.ts` | `emailOnFile: boolean` on `ManagedAppointment`, documented as read-as-a-promise **only after a write** |
| `app/manage/[token]/manage-flow.tsx` | `OutcomeBanner` branches; the outcome clause is unchanged |

Two states, and the second says plainly that no email is being sent, that the number is on file
without an address, and to ask the business — the same route ADR-0007's state 2 offers, because
neither ADR changes stored data.

**It deliberately makes no claim that the Manage Link still works.** A reschedule to a later date can
outlive the token, which was minted against the *old* end time, and with no address on file no
reschedule email carries a fresh one. That sentence was drafted, checked, and cut.

**Tests.** Three new in `PublicAppointmentAuthorityTest`, plus an `emailOnFile` assertion added to
the existing `a_customer_can_cancel_outside_the_window` beside the row it is about:

- `a_cancel_reports_no_address_on_file` — books, clears the address **through the real dashboard
  `PATCH`**, cancels, asserts the outbox is empty and the bit is false.
- `a_reschedule_reports_no_address_on_file` — the same on the other write path.
- `the_manage_page_reports_the_bit_and_not_the_address` — true on a plain `GET`, where nothing was
  sent, and the body does not contain the stored address.

The clearing goes through `PATCH /customers/{id}` rather than a `jdbc.update` on purpose: the
reachability of that state *is* the finding. A direct write would have proved the response field
works while proving nothing about whether anyone can get into it.

Fixture times matter here — the employee schedule is 09:00–17:00 and the Haircut is 60 minutes, so
16:00 is the last bookable start. The first draft of these tests used 16:30 and 17:30 and would have
failed on availability rather than on anything they were about.

---

## 4. Findings that are somebody else's call

### 4.1 `PublicFieldAllowListTest.ALLOWED` is flat, and that is a live hazard

It is a `Set<String>` of key names with **no notion of which record produced them** — the comments
group it by record, the assertion does not. Two consequences already realised:

- ADR-0007 recorded that a resolved recipient added to `BookedAppointment` under the name `email`
  would pass, because `"email"` is legitimately there for `BusinessProfile`. What catches that is a
  separate value assertion, not this gate.
- Option B above would have added a field to the **public contract of four endpoints** and the gate
  would have stayed green, because ADR-0007 had already put `confirmationSent` in `ALLOWED`.

That is a guard that reads as stronger than it is. Making it per-record is a test refactor nobody has
scoped and it was not taken mid-decision. Worth an issue.

### 4.2 `docs/agents/domain.md` had a stale ADR list

Its file-structure block stopped at 0006; ADR-0007 was added without it. Corrected here to list both
0007 and 0008 — a one-line fix taken because this session was adding the file that made it wrong
again. Flagged so the next reader knows it is maintained by hand and will drift again.

### 4.3 Carried, and still carried

Untouched here, all from the previous two handoffs:

- **Issue [#5](https://github.com/sanama-stack/reception-booking-system/issues/5)** — the name rule's
  stated justification is false. Still the only open issue. Deliberately **not** pre-empted: adding
  `customerName` to `PublicFieldAllowListTest.NEVER` was drafted here and reverted, because it would
  have quietly closed half of #5's second resolution.
- **The three copy states on `/book/{slug}` have never been rendered.** Previous handoff's P1 item 4,
  still open, and this session adds a fourth unrendered state on `/manage/{token}`.
- **There is no "find my booking" page** — and §2 above is now a reason to want one sooner.
- **The shared `Input` is 40 px**, below the 44 px guideline. Design-system decision.
- **`Actor.system()` still has no caller.** Six handoffs.
- **`aiEnabled` has no Settings UI.** Phase 09 owes the control as well as the chat.
- **`RATE_LIMIT_ENABLED` is absent from `.env.example`.** One line, still not taken.
- **CI's deprecation warnings.** Phase 11 owns the workflow file.

---

## 5. What a fresh session must not redo

- **Do not re-argue ADR-0008,** and in particular do not "fix" `emailOnFile` to `confirmationSent`
  for consistency with ADR-0007. The name is the ruling: two of the four endpoints returning this
  shape send nothing, and the allow-list would not have caught the rename either way.
- **Do not re-derive the trigger.** §2 records it link by link, each read at the file. The blank-clears
  path, the token lifetime and the dashboard form were all confirmed, not assumed.
- **Do not add the resolved recipient to any manage response.** Rejected by ADR-0007 for the booking
  body and by ADR-0008 here; `NEVER` fails the build for `recipientEmail`, `customerEmail`, `sentTo`.
- **Do not add a sentence promising the Manage Link still works** after a cancel or reschedule. It was
  considered and cut — see §3.
- **Do not add `customerName` to `NEVER`.** Drafted and reverted on purpose: issue #5 is open and one
  of its two resolutions would make that name legitimate.
- **Do not run `pnpm build` while this repo's `next dev` is up.** They share `.next` and every route
  starts serving `500`s. *(A `next dev` was running during this session and was left alone — it belongs
  to a different project, `orderManagementPlatform`, and shares nothing with this one.)*

---

## 6. Next steps, in order

### P0 — before anything else

1. **The pull request.** Six unpushed commits; CI has seen none of them, and the oldest has been
   waiting three handoffs. Its description owes the frontend testing gap (three pages
   browser-verified, none pipeline-enforced), ADR-0007 **and** ADR-0008.

### P1

2. **Verify the copy states in a browser.** Now four unrendered states across two pages: `/book/{slug}`
   states 2 and 3, and `/manage/{token}`'s new "no email" branch on both the cancel and the reschedule
   path. All of them need a Customer on file **without** an address, which `Phase 06 Scratch` does not
   have — `+995555010203` (Ada Lovelace Tester) has one. The manage states also need a live Manage
   Link, so the fixture is: book with an address, take the link from the outbox, clear the address from
   the dashboard, then open the link. **Read the pane traps first** — a hidden Browser pane serves a
   clean `200` with a complete DOM and no React hydration, so clicks silently do nothing.

### P2

3. **Decide issue #5** — correct the four sentences, or add `appointments.customer_name`.
4. **An issue for §4.1**, the flat allow-list.
5. **Phase 09.** Nothing here changed what it inherits.

---

## 7. Files changed

This session's, all in the commit this file is part of:

```
backend/src/main/java/dev/reception/publicapi/PublicResponses.java            emailOnFile
backend/src/main/java/dev/reception/publicapi/PublicAppointmentController.java  sources the bit
backend/src/test/java/dev/reception/publicapi/PublicAppointmentAuthorityTest.java  +3 tests
backend/src/test/java/dev/reception/publicapi/PublicFieldAllowListTest.java   ALLOWED
frontend/src/lib/public/types.ts                                             emailOnFile
frontend/src/app/manage/[token]/manage-flow.tsx                              two states
docs/04-api-overview.md                                                      the manage shape
docs/agents/domain.md                                                        stale ADR list
docs/adr/0008-the-manage-page-says-whether-an-address-is-on-file.md          NEW
docs/sessions/2026-09-09-manage-page-promise.md                              NEW — this file
docs/sessions/README.md                                                      index row
```

The **previous** session's, committed as `44f42e4`: `Customer.java`, `NotificationEnqueuer.java`,
`PublicBookingController.java`, `PublicBookingTest.java`, `confirmation.tsx`, plus its own ADR and
handoff — and its half of the five shared files. Those five appear in **both** commits, which is why
the split needed the checks §1 describes rather than a `git add` per file.

---

## 8. Commands, and what they last returned

```bash
# Backend. From backend/. ~5 minutes.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```
`BUILD SUCCESSFUL` in 4m 38s — 731 tests, 0 failures, 0 errors, 0 skipped.

```bash
# Frontend. From frontend/, in this order. A format failure means build never runs.
pnpm lint && pnpm typecheck && pnpm format:check && pnpm build
```
All four clean. `/manage/[token]` builds at 3.64 kB, 118 kB first load. `format:check` was re-run
after the Markdown was written — it runs `prettier --check .` from `frontend/`, so nothing under
`docs/` is in its scope, but the re-run costs seconds and the previous handoff's §7 records the trap.

**No dev server for this project is running.** `pnpm dev --port 9082` from `frontend/` brings the
frontend back. A Spring Boot `ReceptionApplication` was running from an IDE throughout this session
and did not interfere with the test run.

Unchanged from previous handoffs: **the backend does not hot-reload**, the outbox has no HTTP surface
— read the `notifications` table in SQL — and **check that table before concluding anything from
Mailpit**, because cancelling voids every pending notification.

---

## 9. Confidence

**High — verified against the repository or a command's output.** Every link in §2's trigger table
was read at the named file. The four-endpoint claim comes from reading `PublicAppointmentController`,
where `render(…)` is called by `lookup`, `manage`, `cancel` and `reschedule`. The flat-`ALLOWED`
hazard in §4.1 was read in the test itself. That nothing had been done about `manage-flow.tsx` came
from `git worktree list`, `git status` and `git log`.

**High, and worth naming because it changed the recommendation.** §2's lookup-page argument follows
from `PublicRequests.Authority` accepting code+phone on the write endpoints plus the existing
`the_lookup_proof_also_cancels` test — read, not run.

**Low — not verified.** How the new "no email" branch actually renders, on either write path. Same
gap as ADR-0007's states 2 and 3: no frontend test harness, no browser pass. That is P1 item 3 and it
is the largest untested surface this session leaves.
