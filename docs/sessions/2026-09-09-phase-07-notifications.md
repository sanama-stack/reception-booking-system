# Session handoff — 2026-09-09 — Phase 07, Notifications

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done;
> §4 is where decisions had to be taken; §5 will save you the most time.
>
> **§4.1 is the one to read.** A documented index would have made a required feature impossible,
> and the departure is recorded in three places so that the next person meets it before they meet
> the bug.

---

## 1. Where the project stands

**Phase 07 is complete.** Every box in `docs/phases/phase-07-notifications.md` is ticked, including
the Definition of Done. Booking, cancelling and rescheduling produce real emails, delivered by a
database outbox, each carrying the Confirmation Code and a Manage Link.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — protected, and **`c0e770d`** |
| Working branch | **`dev`** — identical to `main`, nothing unpushed |
| Backend tests | **674**, up from 625 — 49 new, all green |
| Frontend | Untouched. This phase has no frontend work, by design |
| Migrations | **`V6__notifications.sql`** — one table, three indexes |

**Merged.** Phase 07 is on `main` as
[pull request #4](https://github.com/sanama-stack/reception-booking-system/pull/4) — all six checks
green, a merge commit rather than a squash, `dev` fast-forwarded afterwards.
`git rev-list --left-right --count dev...origin/main` reports `0 0`.

**Phase 06's nine-commit backlog is also gone.** It went out at the start of this session as
[pull request #3](https://github.com/sanama-stack/reception-booking-system/pull/3), the item three
consecutive handoffs had carried as the highest-value next action.

**This is the first handoff in five sessions that carries no unpushed work**, and the state to
preserve: the value of every §7 item below is that nothing is competing with it.

---

## 2. Running it

Unchanged, plus one thing worth knowing: **mail is visible at http://localhost:9083** (Mailpit),
and it arrives up to sixty seconds after the action that caused it, not immediately. That is the
outbox, not a bug. `README.md` §"Watching the mail" says it in the place someone will look.

`NOTIFICATIONS_POLLER_ENABLED=false` in `.env` stops the sending without stopping the queueing —
rows still commit with their appointments, and `select type, status, scheduled_for from
notifications` shows the backlog.

Gradle still needs `JAVA_HOME=$(/usr/libexec/java_home -v 21)` on this machine. Unchanged.

---

## 3. What exists now

**`dev/reception/notifications/`** — sixteen classes where phase 06 left a `package-info.java`
promising them. The four worth opening first are named in that file.

**Nine template files** under `src/main/resources/mail/`: one shared HTML layout and four
type-specific fragments, each with a plain-text twin. **No template engine and no new dependency** —
the whole requirement is `{{key}}` substitution plus one conditional block, and the second thing a
real engine would add here is the ability to put logic in a template, which is what makes email
templates rot.

**Table layout and inline styles in the HTML**, which is not how anything else in this project is
written. Email clients are not browsers; that markup is deliberately twenty years old.

---

## 4. The decisions that will shape phases 08 and beyond

### 4.1 The partial unique index has a type predicate the data model did not specify

`docs/03-data-model.md` wrote the index over **every** type. That also forbids the second
`CANCELLATION` or `RESCHEDULE` row — and a customer who moves an appointment twice is owed two
emails, which the phase document requires two sections above it. **The two statements cannot both
hold.**

The prose in both documents says what the index is *for*: duplicate **confirmations and reminders**.
Those are owed once per appointment for its whole life. A cancellation and a reschedule are
*events*, and an event that happens twice is two facts rather than one fact repeated.

Nor could the application have worked around a wider index: superseding the previous `RESCHEDULE`
row would mean setting a `SENT` row to `CANCELLED`, which is a lie and a violation of
`notifications_sent_fields`.

**Recorded in three places on purpose** — `V6__notifications.sql` carries the full argument,
`docs/03-data-model.md` is corrected, and `NotificationEnqueueTest` has a test named
*rescheduling twice sends two reschedule emails*.

**The index caught a bad test of mine while I was writing this phase**, which is the best evidence
it works: a loop enqueuing five rows cycled back to `BOOKING_CONFIRMATION` and the database refused
it. The test was wrong; the constraint was not.

### 4.2 `scheduled_for` means "next due", not "first owed"

The poller's claim query is exactly the one the phase document specifies — `status = 'PENDING' AND
scheduled_for <= now()`. That leaves the backoff only one place to live: a failed attempt pushes
`scheduled_for` forward by `RetryBackoff.delayAfter(attempts)`.

The alternative was a `next_attempt_at` column the query would have to agree with, and two columns
that can disagree about when a row is due is one more than the problem needs. The cost is that a row
no longer records when it was first owed — recoverable from `created_at`, the type and the
appointment's `starts_at`.

### 4.3 Two repositories over one table

`NotificationRepository` is `@TenantScoped` like every other repository here.
`NotificationClaimRepository` holds the poller's claim, which is the one query in this application
that must cross tenants.

They are separate files rather than one repository with an exception in it, because
`TenantRepositoryShapeTest` enforces the rule by **name prefix**: a cross-tenant method on a
`@TenantScoped` repository either fails that test or teaches the next reader that the rule has
exceptions, and the next exception will be an accident.

### 4.4 A reminder that has already been sent is not replaced

Rescheduling supersedes the pending `REMINDER_24H` and enqueues a new one. If the old reminder had
already gone out — only possible inside twenty-four hours — it is left alone: a `SENT` row cannot be
superseded without lying, and the index would refuse the replacement anyway. The customer is not
left uninformed, because the reschedule email carries the new time.

**This branch is code-reviewed and reasoned about, not exercised by a test.** See §6.

### 4.5 Delivery is at-least-once, and the code says so

One transaction per batch, `FOR UPDATE SKIP LOCKED` holding the claim for its duration. If the
process dies between the transport accepting a message and the transaction committing, the row is
still `PENDING` and the next poll sends it again. Exactly-once would need the mail server to enlist
in the transaction, which no mail server does — duplicating a confirmation is the better failure to
choose over losing one.

---

## 5. Traps already paid for

### 5.1 `@Scheduled` calling `@Transactional` on `this` silently does nothing

This is why `NotificationPoller` and `NotificationDispatcher` are two beans. A scheduled method
calling a transactional one on itself bypasses the proxy and runs with **no transaction at all** —
which means `FOR UPDATE SKIP LOCKED` releases its locks statement by statement and two instances
cheerfully send the same rows. Nothing about it looks wrong, and no single-instance test would
catch it.

### 5.2 An update must be flushed before the insert that replaces it

The old reminder is set to `CANCELLED` and the new one inserted in the same transaction, both
`(appointment_id, REMINDER_24H)`. Hibernate orders inserts **before** updates within a flush, so
without an explicit `saveAllAndFlush` the collision is guaranteed rather than merely likely.

### 5.3 The `test` profile must switch the poller off

A background thread claiming rows every sixty seconds races every test that asserts on what is still
`PENDING`, and it does so intermittently. The switch is on the **poller**, not the dispatcher, so the
tests can still drive a batch on demand — a `@ConditionalOnProperty` that removed the dispatcher bean
would have taken that away with it.

### 5.4 Mailpit is static and shared by the whole suite

`NotificationDeliveryTest` empties the mailbox in `@BeforeEach` rather than assuming it is empty.
It was already in `IntegrationTest` with a `mailpitApiUrl()` helper left there by phase 01, which
saved a container's worth of work.

### 5.5 `./gradlew test` in a background shell produced no output twice

Both attempts finished with exit code 0 and an empty log; the same command in the foreground took
4m07s and printed everything. Not diagnosed. **Run the full suite in the foreground.**

---

## 6. What is not covered by a test

- **The already-sent reminder branch of §4.4 is unrun.** Reaching it needs a reminder to be
  delivered and then the appointment moved — inside a twenty-four-hour window that the fixture's
  opening hours make awkward to place deterministically. The code is reviewed and the reasoning is
  in `NotificationEnqueuer`'s javadoc. **It is the first thing to exercise if this area is touched.**
- **`FAILED` rows are never retried by anything.** That is intended — they are evidence, not work —
  but there is no operator path to re-enqueue one, and nothing tests that there isn't.
- **The HTML is asserted for content, never for rendering.** No test opens a message in a client, so
  "does this look right in Outlook" is unanswered. Mailpit's UI is the way to check by hand.
- **`SmtpEmailSender`'s address redaction is unit-tested by nothing.** It matters — the string
  reaches `last_error` and the log — and the regex is deliberately loose. Worth a test.
- **No test asserts the poller's `@Scheduled` timing.** The dispatcher is driven directly everywhere,
  which is what makes the suite deterministic; the wiring that calls it every sixty seconds is
  reviewed and unrun.

### What *was* verified

Everything else, by 49 tests: 27 unit (backoff schedule and cap, token round-trip, tampering,
expiry, foreign secret, nine malformed shapes, template rendering in the business timezone, HTML
escaping, the conditional block, no unresolved placeholder in any of the nine templates) and 22
integration against real Postgres and real Mailpit.

**Four of those are worth more than the rest.**

**A booking's two rows commit with the booking, and a refused booking leaves none.** The second half
is the one that matters: a `409 SLOT_UNAVAILABLE` leaves the outbox with exactly the first booking's
two rows and nothing belonging to the refusal.

**Two pollers drained a twelve-row queue and no address was sent twice**, repeated three times, with
a deliberately slow sender so the transactions actually overlap. Against a plain `SELECT` every
customer in that batch gets two emails.

**A real message went through a real socket into a real mailbox**, and the Manage Link parsed out of
its plain-text body verified back to the appointment it was issued for — not merely present, but
opening that appointment and no other.

**The superseded reminder was proven superseded by status, not by timing.** After cancelling, the
reminder's `scheduled_for` was dragged an hour into the past and the poller still sent only the
cancellation.

---

## 7. Open items

Everything in §7 of the phase-06 frontend handoff still stands unless listed below.

- **Nothing is unpushed and nothing is unmerged.** `dev`, `origin/dev` and `origin/main` are all
  `c0e770d`. Start phase 08 by branching from there, not by clearing a backlog.
- **The `phone` / `customerPhone` field name mismatch is still open on the server side.** Phase 08's
  to decide, for both clients. Untouched by this phase.
- **`GET /availability` still cannot exclude an appointment being rescheduled.** Phase 08's to
  decide. Unchanged.
- **`Actor.system()` still has no caller.** The poller sends mail; it does not transition
  appointments, so `SYSTEM` has still never been written to `actor_type`. The history list's
  *"automatically"* copy has still never rendered. **This did not close in phase 07 as the last
  handoff predicted** — a no-show sweep will be its first caller, and nothing has built one.
- **The Manage Link is issued and verified; nothing consumes it.** `ManageTokenService.verify`
  returns the appointment id or empty, with every failure indistinguishable. The page is phase 08's.
- **The verification tenant is unchanged and still the one to use.** `Phase 06 Scratch` /
  `phase-06-scratch`, owner `scratch@example.com`, UTC/USD. Its data is verification wreckage, not a
  clean slate, and an agent cannot sign in to it — the owner has to, inside whatever browser the
  session can drive. **Nothing in this phase was verified against it**, because phase 07 ships no
  screen; the browser check it deserves is opening Mailpit after a dashboard booking.
- **Gradle 8.14 cannot run on this machine's default JDK.** Unchanged.
- **`aiEnabled` and `aiDailyCostCapCents` still have no UI.** Phase 09 owes them.
- **CI's deprecation warnings grew a new one.** Alongside the standing Node 20 notices,
  `actions/setup-java@v4` is now itself deprecated and "will no longer receive updates". Still
  warnings, still green — but this is the one that stops being a warning eventually, and phase 11
  owns the workflow file. Bumping `setup-java`, `checkout`, `setup-node`, `upload-artifact` and
  `pnpm/action-setup` is one small commit whenever somebody is in there.

---

## 8. Next: phase 08, public booking

`docs/phases/phase-08-public-booking.md`. Four things this session leaves you:

1. **The Manage Link works and is waiting for a page.** `ManageTokenService.verify` is the whole
   server side. Issue is already wired into every email. The token authorises exactly one
   appointment and grants nothing else — do not let the page widen that.
2. **Two field-naming questions are yours, and they are the same question.** `CustomerService`
   reports `phone` while the booking request's field is `customerPhone` (§4.4 of the phase-06
   frontend handoff), and `GET /availability` cannot exclude the appointment being rescheduled
   (§4.3 there). Both were deferred to phase 08 precisely because the public flow meets them again
   and the answer should be decided once for both clients.
3. **Every booking path already emails.** `BookingService.book` takes an `AppointmentSource` and an
   `Actor`, and the enqueuer hangs off it — so the public Classic Flow gets confirmations for free
   the moment it calls that method. Nothing in `notifications` needs to know phase 08 exists.
4. **`CLASSIC` as a source has still never rendered on any screen.** The detail page has copy for
   it. Phase 08 is what makes it appear.
