# Session handoff — 2026-09-10 — The slot race deadlock, and four issues closed

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §2 is the four issues cleared; §3 is the defect this session existed to find, which nobody
> was looking for; §4 is a contract change made *before* building on it; §6 is what a fresh session
> must not redo.
>
> **This session built no screen.** It was asked to clear issues and gaps before the phase 09
> frontend half, and the last of those issues turned out to be a production defect on the booking
> write path that took most of the session to measure and fix. The frontend half is started — the
> wire types and the client — and nothing renders yet.

---

## 1. Where the project stands

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| `origin/main` | `c56d9df` — all of phase 08 |
| `dev` | `43b28bb`, **pushed, CI green**, twelve commits ahead of `main` |
| Backend | **798 tests, built green** (793 before) |
| Frontend gates | lint, typecheck, `format:check` green. **`pnpm build` not run** — a dev server is up |
| Migrations | **V7**, and the development database is now actually *at* V7 (§2.1) |
| Issues open | [#5](https://github.com/sanama-stack/reception-booking-system/issues/5), [#7](https://github.com/sanama-stack/reception-booking-system/issues/7), [#8](https://github.com/sanama-stack/reception-booking-system/issues/8), [#9](https://github.com/sanama-stack/reception-booking-system/issues/9) — all **fixed on `dev`**, held open by GitHub until the merge; [#10](https://github.com/sanama-stack/reception-booking-system/issues/10) and [#11](https://github.com/sanama-stack/reception-booking-system/issues/11) newly filed, untouched |
| Phase 09 | Backend complete. Frontend: **types and client only**, no component |

### The commits, in order

```
d5a2043  Give the Flyway plugin a database it recognises            #9
ff52f35  Let emphasis carry its own weight, not the parent's colour #8
6444fc7  Stop claiming an Appointment records the name it was given #5
1c0fcd1  Make the concurrency test name its own failure             #7, first half
00881a2  Give a booking one card shape, whichever door it came in through
f6cddf6  Answer the losers of a slot race instead of failing them   #7, the fix
43b28bb  Teach the frontend to speak to the Receptionist
```

### The one thing that is genuinely new to know

**The previous handoff's P0 is discharged.** `dev` was five commits ahead with CI having seen none
of them; it was pushed first thing, CI went green, and it has stayed green through every commit
since. There is no longer a backlog. The pull request into `main` has *not* been opened — see §7.

---

## 2. The four issues, cleared

### 2.1 #9 — `make migrate` had never worked

The Gradle Flyway plugin resolves its own classpath, so `flyway-database-postgresql` and the driver
declared for the application never reached it: the task could not handle a `jdbc:postgresql:` URL at
all. A `buildscript` block fixes it. The Makefile target separately never exported `.env`, so even
fixed it would have targeted 5432.

**Verified by running it.** The development database is now at **schema version 7** with
`ai_conversations` and `ai_messages` present, and the fixture is untouched — 32 appointments, the
same three businesses including `Phase 06 Scratch`. The previous handoff could only apply V7 inside
`BEGIN … ROLLBACK`; that gap is closed, and so is its companion — §11's "not verified: that the
application starts against a database with V7 applied". It does. `ddl-auto: validate` accepted every
mapping including the `uuid[]` and `jsonb` columns.

### 2.2 #8 — an emphasis span that emphasised nothing

`<span className="text-ink">` inside a `text-ink` banner. The same idiom works on the booking page
only because *that* paragraph is `text-ink-muted`. Both sites now carry `font-medium`, so emphasis
does not depend on what colour the parent happens to be. The principal chose this over patching the
one site.

### 2.3 #5 — a justification that was false

Four places said "the Appointment records the name it was given". `appointments` has no name column;
the given name is discarded on a match. The rule is right and unchanged — the *reason* given for it
was not, and the false half was the load-bearing one, because it made the rule sound costless.

All four corrected. **The column was not added** — the principal chose the documentation fix, and
recording the given name remains a migration plus a decision about what the dashboard shows.

### 2.4 #7 — see §3

---

## 3. The defect this session existed to find

### 3.1 The widened assertion earned its keep on the very next build

`1c0fcd1` only changed how `ConcurrentBookingTest` reports a failure: the whole status multiset
rather than a filtered count, because the original flake had printed `Expected size: 19 but was: 0
in: []` and thrown away the evidence. It was committed as a diagnostic, not a fix.

It fired on the next full build, and named the cause:

```
[responses that were neither the winner nor an expected loser:
 [500 INTERNAL_SERVER_ERROR {"code":"INTERNAL_ERROR", ...}; …]]
```

Not `429`. The rate limiter — the leading candidate in the issue — was innocent.

### 3.2 What was actually happening

```
ERROR: deadlock detected
  Detail: Process 64 waits for ShareLock on transaction 774; blocked by process 63.
          Process 63 waits for ShareLock on transaction 776; blocked by process 64.
  Where: while checking exclusion constraint on tuple (0,1) in relation "appointments"
```

Each transaction inserts its tuple and *then* checks `appointments_no_overlap`, which means waiting
on another transaction's uncommitted conflicting tuple. Two of those waiting on each other is a
**deadlock**, not a conflict. Postgres aborts a victim, which arrives as
`CannotAcquireLockException` — **not** a `DataIntegrityViolationException`, so it never reached the
mapping that turns a lost race into `409 SLOT_UNAVAILABLE`, fell through to a logged 500, and was
returned to a customer whose only mistake was being second.

**This is a product defect, not a test defect.** Exactly one row always survived, so ADR-0002 holds
and the constraint was never in doubt. What was wrong is the *answer* the losers got. Twenty threads
is heavier than production contention, which is why it took eight phases to surface — a matter of
degree, not of kind.

### 3.3 The retry was not enough, and the measurement is the point

The principal was offered retry / map-to-409 / advisory-lock and chose retry, on a recommendation
that turned out to be insufficient. **Recorded because the obvious remedy for a deadlock is to retry
it, and a future session will reach for the same thing:**

| | result |
|---|---|
| retry, 3 attempts, 20 ms step | 2 failures in 6 runs |
| retry, 5 attempts, widening 20→300 ms | 3 failures in 10 runs |
| advisory lock + retry | **10 runs, 10 passes, zero deadlocks logged** |

Two reasons retry cannot finish this job. Detection costs a full `deadlock_timeout` — one second by
default, and the failures arrived *exactly* a second apart, which is what put it beyond doubt. And
retried victims re-enter the cycle they were aborted out of, so the retries become contention
themselves. When it failed it failed completely: all nineteen losers, never a subset.

Failing runs logged 150+ `deadlock detected` lines. With the lock, none at all.

### 3.4 What shipped

**`AppointmentLockRepository`** takes `pg_advisory_xact_lock` on the Employee **before the
availability re-check**, not merely before the insert. That makes check-and-write atomic per
Employee, so a loser waits, then sees the winner's committed row, and is refused by the re-check with
the sentence it was written for — naming the employee — rather than by a constraint whose message is
for nobody. Keyed on the **Employee alone**: bookings conflict through their Buffers, so a key
including the start time would let through exactly the pairs worth serialising. Releases at commit or
rollback with no unlock call, so a booking that throws cannot strand it.

**`DeadlockRetry`** stays behind it as the safety net. Only `CannotAcquireLockException` is retried;
a constraint violation is the race being settled correctly and must propagate on the first attempt.

**`BookingService.book` is no longer `@Transactional`.** The retry has to sit outside the boundary —
a deadlock aborts the transaction it happened in, and a second attempt inside it would fail on
arrival — so the boundary is a `TransactionTemplate` the method can open twice.

---

## 4. A contract changed before anything was built on it

Reading `PublicChatResponses` to write the confirmation card found it declaring its **own**
`BookedAppointment`, under a javadoc saying "a client should not need two shapes to render one card".
A client did:

| | Classic Flow | Receptionist, as shipped |
|---|---|---|
| `service` | `{ name, durationMinutes }` | `"Haircut"` |
| `price` | `{ amount, currency }` | `"60.00"` + flat `currency` |
| `timezone` | `"Asia/Tbilisi"` | **absent** |

Same key names, different value types — worse than different names, because a reader assumes they
agree. The missing `timezone` is the sharpest part: an offset cannot be turned back into a zone.

`appointmentCreated` is now `PublicResponses.BookedAppointment` **itself** — not a second record that
matches, which is a thing that has to keep being true. `create_appointment` gained the two fields the
projection needed, `timezone` and `service_duration_minutes`, both worth the model seeing anyway.

**Nothing caught this and nothing could have.** `PublicFieldAllowListTest.ALLOWED` is a flat set of
key names, so `service`, `price`, `currency` and `timezone` were all already on it for other records
— the exact weakness filed as #10 earlier the same session, found in the wild before the issue was a
day old. `PublicChatTest.one_card_shape_serves_both_doors` now reduces both responses to keys and
value types and compares the skeletons, so a field added to one and not the other fails and names
itself.

---

## 5. What is verified, and how

- **798 tests, full build green.** 793 before; +4 `DeadlockRetryTest`, +1 the shape guard.
- **The deadlock fix — measured, not argued.** Ten consecutive runs of `ConcurrentBookingTest`
  (`--rerun-tasks` each time, so nothing was cached), ten passes, zero `deadlock detected` lines
  where failing runs had produced 150.
- **Both new tests were watched failing.** The shape guard was checked by re-introducing the flat
  record: it reports `service:String` against `service:{durationMinutes:Integer,name:String}` and
  lists the four missing fields. The widened concurrency assertion was checked the same way before
  it was committed, and then proved itself on real data (§3.1).
- **`make migrate` — run, and the database inspected afterwards**, not trusted from
  `BUILD SUCCESSFUL`.
- **The application starts against the real V7 database.** Verified on a second instance on a spare
  port so the principal's IDE process was not disturbed; the phase-09 chat endpoint returned a real
  session token against the real database.

**Not verified.** Anything involving a real model — unchanged from the previous handoff, and there is
still no `OPENAI_API_KEY`. Level 3 is still written and still unrun.

---

## 6. What a fresh session must not redo

- **Do not "simplify" the advisory lock away** because the exclusion constraint already prevents
  double booking. It does, and that is not what the lock is for — §3.2. Removing it restores a 500
  for nineteen customers out of twenty.
- **Do not key the lock on Employee *and* start time.** §3.4. Buffers are why.
- **Do not put `@Transactional` back on `BookingService.book`.** §3.4, last paragraph.
- **Do not retry `DataIntegrityViolationException`.** It is the race being settled correctly.
  `DeadlockRetryTest.a_lost_race_is_not_retried` pins this.
- **Do not give the Receptionist's confirmation card its own type.** §4. The whole point is that it
  is the same record.
- **Do not re-argue #5 into adding `appointments.customer_name`.** The principal chose the
  documentation fix; the column is a separate, larger decision.
- **Do not trust `BUILD SUCCESSFUL` for `make migrate`.** It reported success while doing nothing
  useful for eight phases.
- **Do not run `pnpm build` while `next dev` is running.** Unchanged, and still true.
- **The standing warning about a stale backend is discharged for now** — see §8.

---

## 7. Next steps, in order

### P0

1. **The frontend half of phase 09** — the actual next work, and the reason this session's ledger is
   clear. `43b28bb` left the wire types and the two calls in `lib/public`; **nothing renders**. What
   the phase document still owes:
   - the chat panel in `/book/[slug]`'s right column, full-width on mobile — the page's grid
     comment already reserves that column for it
   - the message list, typing indicator, and inline tool activity
   - the confirmation card **from `appointmentCreated`**, which after §4 is the Classic Flow's
     existing component rather than a new one
   - `sessionStorage` persistence, so a reload resumes and a new tab does not
   - the degradation banner on `AI_UNAVAILABLE` / `AI_LIMIT_REACHED`, and the permanent "book the
     classic way" affordance
   - `/conversations` and `/conversations/[id]` in the dashboard
2. **The `aiEnabled` Settings toggle**, which phase 09 owes and neither half has built. The column,
   the patch path and the public field all exist; this is a switch on a Settings screen.

### P1

3. **The pull request.** `dev` → `main`, twelve commits. Not opened here on purpose: phase 09 is
   half-built, and `main` is meant to be a commit a stranger could clone and run. Merging closes #5,
   #7, #8 and #9, which is why all four still show open.
4. **Run level 3 once**, with a real key. Unchanged from the previous handoff and still the largest
   unverified thing in the project.

### P2

5. **Issues [#10](https://github.com/sanama-stack/reception-booking-system/issues/10) and
   [#11](https://github.com/sanama-stack/reception-booking-system/issues/11)**, both filed here, both
   `ready-for-human`. #11 is worth deciding *before* the chat panel: it adds a message composer and a
   send button to the most touch-heavy screen in the product, and without a decision it becomes the
   fifth file to carry `min-h-11` and the third to carry the same comment explaining why.
6. **Retention.** Unchanged: nothing deletes an `ai_message`, and nothing is meant to yet.

---

## 8. Carried, and still carried

- **`Actor.system()` still has no caller** — but it is **off this list from now on**. Three javadocs
  name it as the poller's identity and `ActorType.SYSTEM` is real in the schema; it is a deliberate
  vocabulary, not debt. Nine handoffs is enough.
- **`PublicFieldAllowListTest.ALLOWED` is flat** — now **filed as #10**, and no longer merely
  carried. §4 is what it let through.
- **The shared `Input` is 40 px** — now **filed as #11**, with the larger shape: the guideline is an
  override four files remember, and `Input` has no override anywhere.
- **There is no "find my booking" page.** Phase 10 or 11.
- **`ConcurrentBookingTest` is still a 20-thread test** and still the heaviest thing in the suite.
  It is no longer the only thing standing between the §3 defect and a customer — `DeadlockRetryTest`
  covers the policy deterministically — but it is what proves the constraint still admits exactly one
  row, and it should stay.

---

## 9. Files, and what changed

`d5a2043` — #9:
```
backend/build.gradle.kts                          buildscript classpath for the Flyway plugin
Makefile                                          migrate now sources .env
```

`ff52f35` — #8:
```
frontend/src/app/book/[slug]/confirmation.tsx     font-medium on both emphasis spans
frontend/src/app/manage/[token]/manage-flow.tsx   font-medium, and the comment saying why
```

`6444fc7` — #5:
```
backend/…/customers/CustomerService.java          the javadoc that was false
backend/…/customers/Customer.java                 the same sentence, second site
docs/01-prd.md · docs/phases/phase-06-appointments.md
```

`1c0fcd1` — #7, the diagnostic:
```
backend/src/test/…/ConcurrentBookingTest.java     status multiset + unexpected() renderer
```

`00881a2` — the contract, §4:
```
backend/…/publicapi/PublicChatResponses.java      second record deleted, projection onto the first
backend/…/ai/tools/CreateAppointmentTool.java     + timezone, service_duration_minutes
backend/src/test/…/PublicChatTest.java            NEW one_card_shape_serves_both_doors
backend/src/test/…/PublicFieldAllowListTest.java  the comment that described the old projection
docs/04-api-overview.md                           §6 now shows what actually ships
```

`f6cddf6` — #7, the fix, §3:
```
backend/…/appointments/AppointmentLockRepository.java  NEW — pg_advisory_xact_lock
backend/…/appointments/DeadlockRetry.java              NEW — the policy and its loop
backend/…/appointments/BookingService.java             TransactionTemplate, lock before re-check
backend/src/test/…/DeadlockRetryTest.java              NEW — 4 tests, both halves of the rule
```

`43b28bb` — the frontend half's first commit:
```
frontend/src/lib/public/types.ts   StartedChatSession, ChatReply, ConversationStatus, …
frontend/src/lib/public/api.ts     publicChatPath, publicChatSessionPath, startChat, chat
frontend/src/lib/public/index.ts   the barrel
```

---

## 10. Commands, and what they last returned

```bash
# Backend. From backend/. ~5 minutes.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```
`BUILD SUCCESSFUL` — **798 tests, 0 failures, 0 errors, 0 skipped**.

```bash
# The deadlock, ten times over. From backend/. ~10 minutes.
for i in $(seq 1 10); do ./gradlew test --tests '*ConcurrentBookingTest*' --rerun-tasks -q; done
```
Ten passes, and `grep -c "deadlock detected"` on the results is **0** every time.

```bash
# Now actually works. From the repo root.
make migrate
```
Applies through V7. The database reports version 7 and the fixture is unchanged.

```bash
# Frontend gates. From frontend/. NOT pnpm build — a dev server is running.
pnpm lint && pnpm typecheck && pnpm format:check
```
All three clean.

---

## 11. Confidence

**High — measured, not argued.** §3's fix. Ten runs, ten passes, zero deadlocks, against a baseline
of 2-in-6 and 3-in-10 failures with the retry alone. The numbers in §3.3 are from runs done in this
session, each with `--rerun-tasks`.

**High — verified against a command's output.** 798 tests. `make migrate` against the real database,
inspected afterwards rather than inferred. Both new tests watched failing before being trusted.

**Moderate.** That twenty threads is a fair model of production contention. It is heavier than
reality, which is the safe direction, but the lock's cost under *ordinary* load has not been
measured — one Employee's bookings now serialise, and no benchmark says what that costs.

**None — not verified at all.** Anything involving a real model. Unchanged, and it is now the
largest unverified area in the project by a distance.

---

## 12. The verification tenant

**Untouched, and deliberately checked twice.** `Phase 06 Scratch` and the two other businesses are as
every previous handoff left them — 32 appointments. Two chat sessions were opened against the real
database while verifying the backend was current; both rows were deleted afterwards, and
`ai_conversations` is back to **0**. The only lasting change to the development database is that it
is now at **schema version 7** instead of 6, which is `make migrate` doing the thing it had never
been able to do.

**The backend on 9081 was restarted by the principal at 02:08, nine minutes after the last commit,
and is current.** Asserted rather than assumed: it answers `POST
/api/public/businesses/{slug}/chat/session` with a real session token, which the process this session
started against could not do. The frontend on 9082 was left alone throughout.
