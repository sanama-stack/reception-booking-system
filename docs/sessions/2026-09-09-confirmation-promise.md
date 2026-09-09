# Session handoff — 2026-09-09 — the confirmation screen's email promise, and ADR-0007

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand and carries the one thing that must happen before anything else; §2 is the change itself;
> §5 is what a fresh session must not redo.

---

## 1. Where the project stands

**Phase 08 was already complete when this session opened.** This session built no page and closed no
checklist. It fixed a defect the previous handoff had found in already-committed phase-08 code and
left as somebody else's call — §7.1 of
[2026-09-09-phase-08-manage-page.md](./2026-09-09-phase-08-manage-page.md) — by putting the choice
to the principal and implementing the ruling.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Working branch | `dev` |
| Backend | **728 tests, built green** (726 before, plus this session's two) |
| Frontend gates | `lint`, `typecheck`, `format:check`, `build` — **all four run, all clean** |
| Migrations | **None.** No table and no column was added |
| New ADR | **ADR-0007**, `docs/adr/0007-booking-response-says-whether-a-confirmation-was-sent.md` |

### Two things about the tree, and the first one matters most

**Nothing this session did is committed.** Ten modified files and two new ones are sitting in the
working tree (§7). They were left uncommitted deliberately — nobody asked for a commit — but that
means the gates in the table above were run against a tree that only exists on this machine.
**Committing is the first action for the next session** (§6, P0).

**`dev` is four commits ahead of `origin/dev`, and CI has not seen any of them.** The previous
handoff's §1 said "dev is pushed and green"; that was true of `68ce872` and has not been true since.
Unpushed:

```
d2cd169 Phase 08 — the /manage/{token} page, and the phase closes
1378d99 Record the booking page, and settle the handoff's one hazard
9785269 Phase 08 — the /book/{slug} page
d6b797d Tell a Customer something a Customer can act on
```

So the pull request the previous handoff's §8 asked for is still owed, and it now has this session's
work to carry as well.

### A second session is working on the sibling defect right now

The same false promise exists on `/manage/{token}` (§4.1). It was **not** fixed here; it was written
up and handed to a separate local session, which the principal started and which was still running
when this handoff was written. **Do not start work on `manage-flow.tsx:221` without checking what
that session did.** At the time of writing, `git worktree list` shows only the main checkout, so
whatever it produces is not yet visible in this tree.

---

## 2. What was decided, and what was built

### The defect

`frontend/src/app/book/[slug]/confirmation.tsx` promised *"A confirmation is on its way to
{whatever the Customer typed}"*. `CustomerService.findOrCreate` matches an existing Customer on
`(businessId, phone)` and returns it **untouched**, so the typed email is discarded on a match, and
`NotificationEnqueuer` gates on the *Customer's* address. Three ways the screen was wrong:

1. Stored address differs from the typed one → the mail goes to the stored one, the screen names the
   typed one.
2. Stored record has no address → **nothing is sent at all**, and the screen promises a message and
   names an address. This one is a dead end, not just a lie: no mail means no Manage Link, and §7.3
   of the previous handoff records that no lookup page exists, so that Customer has no self-service
   route.
3. **The one the previous handoff thought was safe.** Its §7.1 said the "no email given" branch
   "is correct today and should stay correct." It was not correct. `email` is `@Email` without
   `@NotBlank`, so blank is legal — and a blank submission from a returning number that *does* have
   a stored address sends a confirmation while the screen says *"there is nothing to send — the code
   above is your only copy of it."* A Customer told to write the code down while mail is on its way.

Case 3 is why the copy-only option could not have been enough: that branch has no typed address to
soften. Only a server-sourced fact reaches it.

### The ruling: option B, in its narrow form

The previous handoff offered three ways out and called the middle one right. On inspection the
middle one has two shapes, and they are not equally safe:

| | Verdict |
|---|---|
| **A** — soften the copy | Rejected: fixes case 1 only, cannot reach case 3 |
| **B-bit** — a `confirmationSent` boolean | **Chosen** |
| **B-enum** — three states, so the typed address can still be named | Rejected: an oracle for confirming a guessed address against a phone number |
| **B-echo** — return the resolved recipient | Rejected: contradicts `PublicResponses`' own doctrine |
| **C** — write the typed email onto the matched Customer | Rejected on the merits, **not** as already-forbidden — see below |

B-echo was ruled out against a sentence twelve records down in the file it would have changed:
`ManagedAppointment`'s javadoc already refuses to echo a stored name, phone or email because doing
so "would turn a Confirmation Code into a way to read them." Booking is proved by a phone number
alone, which is weaker than a Manage Token, so the same objection applies harder.

The reasoning is recorded in full in **ADR-0007**. Read it before reopening any of this.

### The decision-trail audit, which changed the shape of the question

The previous handoff presented C as the option that "silently overwrites a Customer's address …
which is the defect `findOrCreate` exists to avoid for the name," implying a standing ruling.
**There is no such ruling for the email.** Every recorded decision is about the *name*:

- `docs/01-prd.md:421` — "the name is **not** overwritten"
- `docs/phases/phase-06-appointments.md:59` — same, for the name
- `docs/phases/phase-06-appointments.md:125` — the checklist box, for the name

`CustomerService`'s class javadoc does mention email, but it says the email is not the *identity
key* — a different claim from whether a supplied email updates the record. So C was a genuinely
open question that had never been put to anyone. It was declined on its merits (a public form must
not rewrite the contact details of whoever holds a number), and ADR-0007 says so explicitly, so the
next session meets the reasoning rather than a phantom precedent.

---

## 3. The change, file by file

**Backend.**

| File | |
|---|---|
| `customers/Customer.java` | New `hasEmail()`. **One predicate, two callers** — this is the load-bearing part |
| `notifications/NotificationEnqueuer.java` | Its private `hasEmail(Customer)` deleted; all three call sites now ask `customer.hasEmail()` |
| `publicapi/PublicResponses.java` | `BookedAppointment` gains `confirmationSent`; `of(…)` gains the parameter |
| `publicapi/PublicBookingController.java` | Reads the **resolved** Customer and asks `hasEmail()`. Not the submitted email |

The point of moving the predicate onto `Customer` is that the outbox and the screen now cannot
disagree: there is one definition of "reachable by email", and both the thing that writes the row
and the thing that promises it ask the same question of the same object.

**Frontend.**

| File | |
|---|---|
| `lib/public/types.ts` | `confirmationSent: boolean` on `BookedAppointment`, documented as the server's answer |
| `app/book/[slug]/confirmation.tsx` | Branches on `appointment.confirmationSent` **first**, then on the typed email |

Three states now, in this order:

1. `confirmationSent` → *"on its way to the email address on file for this number"*. **Never names
   an address.** This also silently fixes case 3, because it is keyed on the server's answer rather
   than on whether the form had an email in it.
2. `!confirmationSent && email` → the surprising one. Says plainly that no email is being sent,
   that the number is on file without an address, that booking does not change stored details, and
   to ask the business to add the address. That last clause is the only route, because ADR-0007
   deliberately does not touch stored data.
3. `!confirmationSent && !email` → unchanged from before.

The `email` prop is **still passed and still needed** — but only to tell states 2 and 3 apart. Its
javadoc now says so, because reading it as the recipient is exactly the mistake that was fixed.

**Guards.** `PublicFieldAllowListTest`: `confirmationSent` added to `ALLOWED`, and
`recipientEmail` / `customerEmail` / `sentTo` added to `NEVER` — so B-echo fails the build rather
than being re-argued in eighteen months. Worth knowing: **`ALLOWED` is a flat set of key names, not
per-record**, and `"email"` is already in it for `BusinessProfile`. A resolved recipient added to
`BookedAppointment` under the name `email` would have passed that gate silently. What actually
catches it is `customer_details_are_not_echoed`, which asserts on the *value*.

**Tests.** Two new integration tests in `PublicBookingTest`, plus a `confirmationSent` assertion
added to each of the two existing email tests:

- `a_returning_customer_is_mailed_at_the_address_on_file` — books twice on the same phone with two
  different addresses, and asserts against the `notifications` table that the **stored** one is the
  recipient, that `confirmationSent` is true, and that the response body does not contain the stored
  address.
- `a_returning_customer_without_a_stored_address_is_not_mailed` — first booking with no email,
  second with one; asserts the outbox is empty and `confirmationSent` is false.

Every helper in that class already books the same `BookingScenario.CUSTOMER_PHONE`, so "book twice
and you are the same Customer" needed no new fixture. `recipientEmails()` was added beside
`pendingNotificationTypes()`, reading `recipient_email` in SQL for the same reason the older helper
reads the table directly.

**Docs.** ADR-0007 (new), and `docs/04-api-overview.md` §6 now shows `confirmationSent` in the
`201` body with a paragraph on why the recipient is not there.

---

## 4. Findings that are somebody else's call

### 4.1 `/manage/{token}` makes the same promise, and it can be false

`frontend/src/app/manage/[token]/manage-flow.tsx:221` says *"A confirmation email should reach you
within a minute or two"* unconditionally after a cancel or a reschedule. Both
`NotificationEnqueuer.appointmentCancelled` and `appointmentRescheduled` return early on
`!customer.hasEmail()`.

**Confirmed reachable, and narrow.** The page is only reached from an email, so the Customer had an
address when the token was issued — but `Customer.applyCorrection` treats a blank string as a clear,
reachable from the dashboard's customer `PATCH`, and a Manage Token is valid until the appointment
ends. So there is a real window: the owner clears the address, the Customer opens an old link and
cancels, and the screen promises a message that no row exists for.

Not fixed here. Fixing it is another published-contract change (a field on the manage responses),
which is a fresh principal-owned call rather than scope this session could take. **Handed to a
separate session, which was running when this was written — see §1.** The cheaper option is on that
session's table too: since the page is only ever reached from an email, softening the sentence on
the client alone may be proportionate for a window this narrow.

### 4.2 The name rule's stated justification is false — issue #5

Four places claim *"the Appointment records the name it was given"*:
`CustomerService.findOrCreate`, `Customer.applyCorrection`, `docs/01-prd.md:421`, and
`docs/phases/phase-06-appointments.md:59`.

**`appointments` has no name column.** See the `CREATE TABLE appointments` block in
`V5__customers_and_appointments.sql`. `BookingService.book` passes `request.customerName()` only to
`findOrCreate`, which discards it on a match. So when somebody books for a partner on their own
number, the partner's name is recorded **nowhere** — it is dropped.

The rule itself is right; the justification is half fiction, and the fictional half is the part that
makes the rule sound costless. Filed as
[#5](https://github.com/sanama-stack/reception-booking-system/issues/5) with both resolutions laid
out: correct the four sentences, or add `appointments.customer_name` and make the claim true. The
second is a migration on the large table and a principal's call. Phase 06's checklist box passes on
the true half alone, so no test catches the gap.

### 4.3 Carried, and still carried

Untouched by this session, all from the previous handoff's §7:

- **The shared `Input` is 40 px** on a mobile-first surface, below the 44 px guideline. Design-system
  decision.
- **There is no "find my booking" page.** `POST /public/appointments/lookup` is built, tested and
  rate-limited at 5/hour, and no page uses it. Phase 10 or 11.
- **`Actor.system()` still has no caller.** Five handoffs now.
- **`aiEnabled` has no Settings UI.** Phase 09 owes the control as well as the chat.
- **`RATE_LIMIT_ENABLED` is absent from `.env.example`.** One line, still not taken mid-task.
- **CI's deprecation warnings.** Phase 11 owns the workflow file.

---

## 5. What a fresh session must not redo

- **Do not re-argue ADR-0007.** In particular: do not add the resolved recipient to
  `BookedAppointment` (rejected, and `PublicFieldAllowListTest.NEVER` now fails the build for it),
  and do not propose the three-state variant that names the typed address (rejected as an oracle).
  Reopen only on new facts, through the same ritual, per `fablish-decision-ritual`.
- **Do not make `findOrCreate` write the supplied email.** Considered as option C, put to the
  principal, declined. It is recorded in ADR-0007 as declined-on-merits rather than forbidden, so
  the reasoning is there to read — but it was decided, not skipped.
- **Do not re-derive the decision trail.** §2 records the audit: the no-overwrite rule is documented
  for the *name* in three places and nowhere for the email. That search has been done.
- **Do not "fix" the `email` prop out of `Confirmation`.** It looks vestigial now that the promise
  reads `confirmationSent`. It is not: it separates the two `!confirmationSent` branches.
- **Do not soften the state-1 copy back to naming the address.** Not naming it is the ruling, not an
  oversight.
- **Do not run `pnpm build` while `next dev` is up.** They share `.next` and the dev server starts
  serving `500`s for every route — it looks exactly like a broken commit. Paid for by the previous
  session, and it shaped this one: the dev server was killed to run the build.

---

## 6. Next steps, in order

### P0 — before anything else

1. **Commit this session's work.** Twelve files (§7), nothing staged. The gates in §1 were run against
   this tree and nowhere else.
2. **Check on the `/manage/{token}` session** (§1, §4.1) before touching `manage-flow.tsx`.

### P1

3. **The pull request the previous handoff's §8 asked for.** Four unpushed commits plus this
   session's work; CI has seen none of it. Its description should carry the frontend testing gap
   (both public pages are browser-verified, not pipeline-enforced) and now ADR-0007 as well.
4. **Verify the three copy states in a browser.** Not done this session — the change is covered by
   backend tests and there is no frontend test harness, so the *rendering* of states 2 and 3 has
   never been looked at. State 2 needs a Customer on file without an address; the previous handoff's
   §7.5 says `Phase 06 Scratch` has `+995555010203` belonging to `Ada Lovelace Tester`, who **has**
   an address, so state 2 needs a new fixture. **Read the pane traps in §5.1 of the previous handoff
   first** — a hidden Browser pane serves a clean `200` with a complete DOM and no React hydration,
   so clicks silently do nothing.

### P2

5. **Decide issue #5** (§4.2) — correct the sentences, or add the column.
6. **Phase 09.** The previous handoff's §8 lists the three things it inherits; none of them changed
   here.

---

## 7. Files changed in this session

All uncommitted. Ten modified, two new — the last two are this handoff itself.

```
backend/src/main/java/dev/reception/customers/Customer.java              hasEmail() added
backend/src/main/java/dev/reception/notifications/NotificationEnqueuer.java  private copy deleted
backend/src/main/java/dev/reception/publicapi/PublicResponses.java       confirmationSent
backend/src/main/java/dev/reception/publicapi/PublicBookingController.java   sources the bit
backend/src/test/java/dev/reception/publicapi/PublicBookingTest.java     +2 tests, +2 assertions
backend/src/test/java/dev/reception/publicapi/PublicFieldAllowListTest.java  ALLOWED and NEVER
docs/04-api-overview.md                                                  the 201 body
frontend/src/lib/public/types.ts                                         confirmationSent
frontend/src/app/book/[slug]/confirmation.tsx                            three states
docs/sessions/README.md                                                  index row
docs/adr/0007-booking-response-says-whether-a-confirmation-was-sent.md   NEW
docs/sessions/2026-09-09-confirmation-promise.md                         NEW — this file
```

The gates in §1 were run **before** the last three were written. They are Markdown, and
`format:check` runs `prettier --check .` from `frontend/`, so nothing under `docs/` is in its
scope — but that is why the count here is larger than the tree the build saw.

---

## 8. Commands, and what they last returned

```bash
# Backend. From backend/. ~5 minutes.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```
`BUILD SUCCESSFUL` — 728 tests, 0 failures, 0 errors, 0 skipped.

```bash
# Frontend. From frontend/, in this order. A format failure means build never runs.
pnpm lint && pnpm typecheck && pnpm format:check && pnpm build
```
All four clean. `/book/[slug]` builds at 3.78 kB, 118 kB first load.

**The dev server is down.** It was killed (PID 86275, port 9082) to run the build, on the
principal's instruction to leave it down. `pnpm dev --port 9082` from `frontend/` brings it back.

Unchanged from previous handoffs: **the backend does not hot-reload**, and the outbox has no HTTP
surface — read the `notifications` table in SQL. And per §5.4 of the previous handoff, **check that
table before concluding anything from Mailpit**: cancelling an appointment voids every pending
notification, so a missing message is not necessarily a missing enqueue.

---

## 9. Confidence

**High — verified against the repository or a command's output.** Every code claim in §2 and §3 was
read at the file; the 728/0/0 count came from the JUnit XML in `build/test-results/test/`; all four
frontend gates were run in this session; the two new tests were confirmed present and passing in
`TEST-dev.reception.publicapi.PublicBookingTest.xml`; the unpushed-commit list came from
`git log origin/dev..dev`; §4.2's missing column was read in `V5__customers_and_appointments.sql`.

**High, and worth naming separately because it corrects the previous handoff.** Case 3 in §2 — the
"no email given" branch being already wrong — follows from `@Email` without `@NotBlank` in
`PublicRequests` plus the `hasEmail` gate. It was derived by reading both, not reproduced against a
running system.

**Medium — reasoned from code, not observed.** §4.1's reachability window. Each step is read at the
file (`applyCorrection` clears on blank; the manage token lives until the appointment ends; both
enqueuer methods return early), but no one has driven that sequence end to end. The session working
on it has been told to confirm the trigger before changing any contract.

**Low — not verified.** How states 2 and 3 of the new copy actually render. No frontend test
harness, no browser pass this session. That is P1 item 4 and it is the largest untested surface this
session leaves.
