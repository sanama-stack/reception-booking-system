# Session handoff — 2026-09-09 — Phase 06 frontend, and a message that never reached anyone

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done;
> §4 is where a decision had to be taken; §5 will save you the most time; §6 will stop you assuming
> coverage that is not there.
>
> **§4.4 is the one to read if you are building any screen that submits a form.** A server field
> error was being discarded by the screen that asked for it, and no amount of reading the code was
> going to surface it — it took running the thing.

---

## 1. Where the project stands

**Phase 06 is complete.** Every box in `docs/phases/phase-06-appointments.md` is ticked, including
the eight Frontend ones and the Definition-of-Done line they satisfy. An owner can book, move,
cancel and close out an appointment from the dashboard, and see who has booked with them.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — protected, and behind `dev` |
| Working branch | **`dev`** — committed, **not pushed**, CI has not seen it |
| Backend tests | **625**, untouched — this session changed no backend code |
| Frontend | Type-checks, lints, formats; `next build` passes with **22 routes** |
| Migrations | None. This session changed no schema |

### What has not happened

**`dev` is still not pushed, and the pull request is still not open.** Now nine commits: all of
phase 05, the transparent-refresh fix, and both halves of phase 06. CI has seen none of it. This has
been the highest-value action in three consecutive handoffs and it still is.

```bash
git push origin dev
gh pr create --base main --head dev --fill
gh pr checks --watch
gh pr merge          # a merge commit, not a squash
```

### Two items from earlier handoffs are closed

**The owner's shifted `business_hours` rows are fixed.** `GD auto repair` now has **seven** rows, all
`09:00–17:00`. Four handoffs carried this as open; it had already been done by the owner, and this
session was repeating the document rather than checking the database. **Check before carrying an
item forward.**

**The availability preview no longer has the only copy of `explain()`.** See §4.2.

---

## 2. Running it

Unchanged. `make up`, the backend from the IDE, `pnpm dev`, **http://localhost:9080**.

Gradle still needs `JAVA_HOME=$(/usr/libexec/java_home -v 21)` on this machine — see the phase-06
backend handoff §2. Untested this session, which touched no Java.

---

## 3. What exists now

**`frontend/src/lib/appointments/`** and **`frontend/src/lib/customers/`** — the sixth and seventh
lib modules, both following `lib/scheduling`'s split: **reads are path builders, writes are calls.**
A read whose question changes while the screen is open has to be expressible as a string, because
that string is what a keyed component re-runs on.

**Six screens** under `app/(dashboard)/appointments/` and `app/(dashboard)/customers/`: the filtered
list, the detail with its audit trail, the booking flow, and the two customer screens.

**Three shared components** — `status-badge.tsx`, `slot-picker.tsx`, `empty-reason.tsx`. The last two
are shared with phase 05's availability preview and with each other; see §4.2.

**`dashboard-shell.tsx`** marks Appointments and Customers available. Analytics is the last one left
greyed out.

**No new UI primitive and no new dependency.** Everything is `Card`, `Table`, `Select`, `Input`,
`Textarea`, `Button`, `ConfirmDialog`, `EmptyState`, `ErrorState`, `ResourceGate` and `useToast`.

---

## 4. The decisions that will shape phases 08 and 09

### 4.1 The `409` the checklist names is one of nine codes

`SLOT_UNAVAILABLE` is the one the phase document singles out, and it is not the only refusal meaning
*the list on screen was computed against a world that has moved*. A service deactivated in another
tab, an employee unassigned, or enough time passing for the chosen start to fall inside the minimum
lead time all leave times that can no longer be booked.

`STALE_SLOT_CODES` in `appointments/new/page.tsx` names all nine, and they share one treatment: drop
the selection, bump the key, re-ask.

**Two codes are deliberately excluded, and both exclusions are load-bearing.** `VERSION_CONFLICT`
means reload *this appointment*, not availability — the recoveries are not interchangeable.
`VALIDATION_FAILED` says nothing about availability, and throwing away a chosen time because a name
was too long would be its own defect.

### 4.2 `explain()` moved, because a second screen needed it with a different subject

Phase 05 mapped `EmptyReason` to copy inside the availability preview, aimed at one named person.
The booking flow asks the same question on behalf of a customer and may name nobody.

Two copies of a switch over a closed enum are two `default` branches for a fifth reason to vanish
into — the exact failure the phase-05 handoff predicted for this function. It is now
`components/empty-reason.tsx`: one set of branches, the copy varying inside each on whether an
Employee was named. `employeeName: null` is not an unknown name, it is a different question, and it
changes which advice is true.

**Both phrasings were rendered in a browser** — see §6.

### 4.3 The reschedule screen says the wrinkle out loud

`GET /availability` cannot exclude the appointment being moved, so the pre-check counts it against
itself and its own current time is missing from its own reschedule list. The move still works: an
exclusion constraint never compares a row with itself.

Rather than let that read as a defect, the panel says it — *"The appointment's own current time is
not in this list — it is still holding that slot."* **Phase 08 owns the fix**, because a Customer
rescheduling through a Manage Link needs the same exclusion, and the query parameter should be
decided once for both.

### 4.4 A field error was arriving under a name the request does not have

**This is the defect worth the whole verification session.**

Bean Validation reports the request's own field, `customerPhone`. But a number that is present and
simply cannot be *read* as a phone number is refused deeper down by `PhoneField` inside
`CustomerService`, which names the field **`phone`** — the domain's name for it, not this request's.

The booking form rendered `fieldErrors.customerPhone` only. So the specific, actionable message —
*enter it in international form, or set your country in Settings* — was dropped on the floor,
replaced by the generic *"One or more fields are invalid."*, with the input never marked
`aria-invalid`. **The one sentence telling the owner how to fix it never reached them.**

The screen now accepts both names on that input. **The server half is left open on purpose:**
`CustomerService.requiredPhone` hard-codes `"phone"` while its caller's field is `customerPhone`, so
the response describes a field the request does not contain. Phase 08's public booking will have its
own field naming and will meet this again — decide it there, for both, rather than teaching every
client the mapping.

---

## 5. Traps already paid for

### 5.1 The Browser pane is useless while hidden, and it is worse than §5.1 of the last handoff said

The phase-05 handoff described blank screenshots and failing clicks. This session found the stronger
form: **with the pane hidden the viewport is `0×0`, React never paints, effects never run, and the
app sits on "Checking your session" forever** while `navigate` times out at 300 s. It is not slow, it
is stopped.

The moment the pane is actually shown, everything works — viewport `685×884`, screenshots render,
clicks land. **If `navigate` times out, stop debugging the app.**

### 5.2 The screenshot coordinate frame is not CSS pixels

A `getBoundingClientRect()` from injected JavaScript is in CSS pixels; `computer` clicks land in the
screenshot's frame, which was **1.168×** larger here. A click computed the first way misses silently
— the checkbox simply does not tick, which reads as §5.2 of the phase-05 handoff (React ignoring a
programmatic change) and is not.

Two ways past it, both used here: multiply, or skip coordinates entirely and click by `ref`.

### 5.3 A programmatic `.click()` *does* work on a React checkbox

The phase-05 handoff says `form_input` cannot tick one, and that is right — it sets `value` and
dispatches `input`/`change`, and React listens for `click`. But `element.click()` dispatches a real
click that bubbles to React's delegated listener, so it works for checkboxes **and** buttons.

For text inputs the mirror applies: assign through the native `value` setter
(`Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el, v)`) and dispatch
`input`, or React's own state never moves.

### 5.4 Registering a tenant and then querying too early looks like a missing row

A `select count(*)` ran 26 seconds before a registration completed, and the tenant appeared to not
exist. Compare `created_at` against `now()` before concluding anything about what is in the database.

### 5.5 Everything from the earlier handoffs still applies

Particularly `pnpm build` against a tree running `pnpm dev` — this session used the copy-to-tmp
recipe again, and it worked unchanged.

---

## 6. What is not covered by a test

- **There is still no frontend test runner**, by design (`08-testing-strategy.md` §11). Everything
  below was verified by hand, in a browser, against the real backend.
- **`VERSION_CONFLICT` is the one branch never exercised.** It needs two genuinely concurrent writes
  to one appointment, which a person clicking cannot produce on demand — a sequential external
  change simply loads the new version and succeeds. `ConcurrentRescheduleTest` covers the server;
  the screen's response to it is code-reviewed and unrun. **It is the first thing to exercise if a
  frontend runner ever arrives.**
- **`explainEmptyReason` is the cheapest thing here to test the day a runner exists**, and the most
  likely to fall out of step, for exactly the reason phase 05 said.
- **The keying in every paged and filtered read is the most valuable and the hardest without a DOM.**
- **Nothing asserts the `phone` / `customerPhone` mapping of §4.4.** It was found by hand and fixed
  by hand, and a rename on either side would break it silently.
- **No screen has met `AI` or `CLASSIC` as a source**, because nothing produces them yet. `SOURCES`
  in the detail page has copy for both and neither has ever rendered.
- **No screen has met a second Employee**, so the "anyone" tie-break shows one name per slot and has
  never had to distinguish two.

### What *was* verified in a browser

In a throwaway tenant — `Phase 06 Scratch`, UTC, USD, one 60-minute service at 50.00, one employee
working 09:00–17:00 seven days, against opening hours of Mon–Fri:

The unfiltered and the filtered empty states, which say different things; booking from the flow;
the detail screen with its price snapshot, source and audit trail; reschedule keeping the
Confirmation Code; cancel with a reason; completed; no-show; the customer list, a partial-phone
search, the profile, and the correction form with its read-only phone; pagination across two pages
with `Previous`/`Next` disabling correctly at each end; `OUTSIDE_HORIZON` with an Employee named and
`CLOSED` with nobody named, covering both halves of §4.2; and the phase-05 availability preview,
which this session refactored and which still works.

**Four of those are worth more than the rest.**

**The `409` was produced for real, not simulated.** With a slot selected on an open booking screen,
the same slot was taken by another request; the submit then returned `409 SLOT_UNAVAILABLE`, the
screen rendered the server's own sentence — *"Dana Scratch is not free at that time."* — recalculated
21 slots down to 17, dropped 15:00 from the list, and cleared the selection. **The failed booking
wrote nothing:** no customer row, no appointment row.

**A booking removes seven starts, not one.** 28 times before, 21 after a single 60-minute booking at
14:00 on a 15-minute grid — exactly 13:15 through 14:45 gone, with 13:00 and 15:00 both surviving.
That is the `'[)'` bound visible from outside, and it is the phase-05 handoff §9 item closed: slots
disappearing from the preview with no change to that screen at all.

**Only `CONFIRMED` holds time, proven from the outside.** After one appointment was completed and
another marked no-show, both of their slots came back on offer; the cancelled one's did too. The
availability read went back to 27 times with three appointments on the books.

**The audit trail renders UTC payload instants at the business's offset.** The reschedule line reads
*"From Wed, 9 Sept 2026, 14:00 to Wed, 9 Sept 2026, 11:00"* — the payload stores
`Instant.toString()`, so those are `Z` times shifted by the envelope's zone on the way out. A zone
that matched the browser's could not have told the difference.

---

## 7. Open items

Everything in §7 of the phase-06 backend handoff still stands unless listed below. Changed or added:

- **`dev` is unpushed and the pull request is not open.** Nine commits. Unchanged, and now the
  largest it has ever been.
- **The stale `business_hours` item is closed.** See §1.
- **The `phone` / `customerPhone` field name mismatch is open on the server side.** See §4.4. Phase
  08's to decide, for both clients.
- **`GET /availability` still cannot exclude an appointment being rescheduled.** See §4.3. Phase 08's
  to decide, unchanged from the backend handoff.
- **The throwaway tenant was deleted** at the end of this session — see §8. The owner's own business
  was not touched at any point.
- **Gradle 8.14 cannot run on this machine's default JDK.** Unchanged.
- **`aiEnabled` and `aiDailyCostCapCents` still have no UI.** Phase 09 owes them.

---

## 8. Next: push, then phase 07

**Push `dev` and open the pull request before starting anything.** Nine commits and two full phases
have never been compiled by CI. A phase 07 that begins on top of an unverified base cannot tell its
own failure from an inherited one.

Then `docs/phases/phase-07-notifications.md`. Three things this session leaves you:

1. **Cancelling twice must not enqueue a second email.** `CancellationService` answers a repeat
   before consulting the state machine, keeps the first reason and writes no second event — the
   backend handoff §4.6. The dashboard reaches this through the cancel dialog, which is now a real
   path a person can double-click.
2. **`Actor.system()` still has no caller.** Phase 07's poller is its first, and `SYSTEM` has never
   been written to `actor_type`. The history list has copy for it — *"automatically"* — that has
   never rendered.
3. **Every booking now carries an email when one was given**, and the detail screen shows it. The
   confirmation email has a real address to go to and a real Confirmation Code to carry, both
   visible on `/appointments/[id]` for checking against what lands in Mailpit.
