# Session handoff — 2026-09-09 — Phase 08, the `/manage/{token}` page

> **Purpose.** Enough context to continue without re-reading this session. §1 says where things
> stand; §5 will save you the most time; §7 carries the one finding that is somebody else's decision.

---

## 1. Where the project stands

**Phase 08 is complete.** `/manage/{token}` was the last unbuilt page, and its Definition of Done
closed with it. A Customer can now open the link in their confirmation email, read their
appointment, move it, cancel it, and be refused with the business's own policy when the window has
shut.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Working branch | `dev` |
| Previous session's work | **Committed** — `d6b797d` (the phone fix) and `9785269` (the booking page), plus `1378d99` for the docs |
| Backend | **Untouched this session.** 726 tests, last built green at `d6b797d` |
| Frontend gates | `lint`, `typecheck`, `format:check`, `build` all clean |
| Migrations | **None.** Phase 08 ends having added no table and no column |

Everything the previous handoff left open in its §7.1 is settled: there was one worktree, the tree
held one coherent version, and `pnpm build` — which had never run against the booking page — passed.

---

## 2. Running it

Unchanged from the previous handoff, plus one trap this session paid for.

**Do not run `pnpm build` while `next dev` is up.** They share `.next`, and the build left the
running dev server serving `500`s for every route. It looks exactly like a broken commit and is not
one: kill the dev server and `pnpm dev` again. Both were needed here — the gate has to pass and the
page has to be driven — so run the build first, then start the dev server, or accept the restart.

**The backend still does not hot-reload**, and `RATE_LIMIT_ENABLED` is still absent from
`.env.example` (§8). Neither bit this session: the manage limits are generous (120/min to resolve a
link, 20/hour for the writes), and the backend needed no restart because nothing in it changed.

---

## 3. What exists now

**`frontend/src/app/manage/[token]/`** — five files.

| File | |
|---|---|
| `page.tsx` | Server component. Resolves the token, the metadata, and the two designed refusals |
| `manage-flow.tsx` | Holds the appointment, routes both writes, and owns the refusal branches |
| `appointment-summary.tsx` | What was booked, when, with whom, the code, and the status note |
| `reschedule-card.tsx` | The fortnight grid, on the token-authorised endpoint |
| `cancel-dialog.tsx` | The confirmation, with an optional reason |
| `link-expired.tsx` | The designed page for `MANAGE_TOKEN_INVALID` |

**`lib/public` gained the manage half of the contract** — `ManagedAppointment`, `ManagingBusiness`,
`ManageAuthority`, the two request bodies, `managePath` and `manageAvailabilityPath`. It reuses
`AppointmentStatus` from `lib/appointments`, on the same test `Money` and `WallClockTime` pass: it is
genuinely the same closed domain enum, not a DTO that happens to overlap.

**`components/fortnight-picker.tsx` is `book/[slug]/availability-step.tsx`, promoted.** Both public
surfaces ask the engine the same question and must render the same answer, and a second copy would
be a second set of hit targets, a second `manyPeople` rule and a second `emptyReason` switch to keep
in step. It absorbed the Earlier/Later pager, which had been sitting in `classic-flow.tsx`. Two
things were deliberately **not** moved into it: `windowStart` and `chosenDate` stay with the caller,
because the caller remounts the picker to force a re-ask and a `409` refresh must not also throw the
Customer back to today.

---

## 4. The decisions

### 4.1 Server-resolved, then handed to a client component

The booking page server-renders its header because that is the part worth showing before JavaScript
arrives. Here the reason is sharper: **the whole page is the appointment.** Fetching it in the
browser would put a spinner where the summary goes and, for an expired link, a spinner that turns
into a refusal — which reads as a failure rather than as an answer.

Every write returns the appointment it produced, so the client component adopts the body rather than
re-reading. The summary is therefore always rendered from the server's own answer.

### 4.2 The URL is the credential, and three things follow from it

`referrer: 'no-referrer'` in the metadata is the load-bearing one — without it any navigation away
hands the token to wherever it went. The page also renders **no outbound links at all** (verified),
and the title is static and says nothing about the business, because a title naming it puts it in
browser history and in a tab read over a shoulder for a URL that is already a bearer token.

### 4.3 There is no "with whom" picker, and there cannot be one

`ManagedAppointment.employee` is `{ fullName }` — **a name with no id**. So the page cannot pin the
current Employee even if it wanted to, and these paths carry no slug, so there is no public Employee
list to populate a picker from either. Every reschedule grid is therefore an "anyone eligible" grid.

**This was left as the minimisation dictates rather than fixed by adding the id**, and that is the
decision to argue with if you disagree. `PublicResponses`' own rule is that any id not needed to
complete the operation is absent, and the move completes without it. It also turns out to be the
better screen: the Customer sees every time that exists rather than only their own person's, and the
picker names the performer on each Slot whenever they vary, so nobody is moved to a different person
without being shown who. The confirm line names them unconditionally, because this page cannot tell
whether it is the same person.

### 4.4 Two refusals, not one

`canCancel` and `canReschedule` are both `live && windowOpen` on the server, so they always agree —
but the page still treats them separately, and it distinguishes **why** they are false:

- `status !== CONFIRMED` → *"Nothing left to change"*. The appointment is closed; the summary above
  has already said what became of it, and there is no way forward to offer.
- `status === CONFIRMED` and the window shut → *"Too late to change this online"*. A live
  appointment the Customer simply cannot alter themselves any more. **This** is the one that needs
  a phone number, and giving it one is the entire reason `ManagingBusiness` carries contact details.

**The refusal cannot say how long the window was.** `ManagingBusiness` has no
`cancellationWindowHours`, unlike `PublicBusiness`. That matters less than it sounds — the number is
only useful before it passes — but it is why the copy says the time has passed rather than naming a
number it would have had to guess.

### 4.5 A refusal the page thought impossible re-resolves rather than arguing

`CANCELLATION_WINDOW_CLOSED`, `VERSION_CONFLICT` and `INVALID_STATUS_TRANSITION` all mean this
screen's copy of the appointment is stale. Rather than flip a local flag and keep rendering from a
picture already known to be wrong — which would then be wrong about the next thing too — the page
goes and reads the appointment again. A failure of *that* read is swallowed on purpose: the refusal
that caused it is already on screen and is the message that matters.

A stale-slot code is different and is **not** in that set: it means the *grid* is stale, not the
appointment, so the grid is re-asked and the card stays open.

---

## 5. Traps already paid for

### 5.1 A hidden Browser pane also stops React hydrating

New, and worse than the four consequences the previous handoff listed. With the pane hidden the page
serves a clean `200`, the DOM is complete and correct, and **`Object.keys(button).some(k =>
k.startsWith('__react'))` is `false`** — so every `.click()` silently does nothing. It reads exactly
like a broken event handler.

**`setTimeout` is throttled too**, which retires the previous handoff's advice to use it instead of
`requestAnimationFrame`: a background tab throttles timers after the first few, and an in-page
`await new Promise(r => setTimeout(r, 3000))` hung until the tool's 45-second timeout. Waiting
*between* tool calls works, because that is real wall-clock time outside the page.

**The check to run first**, before reading any code, is that hydration flag. Reading the DOM is
still reliable while hidden — everything in §6 up to the first write was gathered that way.

### 5.2 The dashboard's reschedule note is false on this page

`reschedule-section.tsx` tells the owner *"The appointment's own current time is not in this list."*
That is true there: the dashboard reads the **unexcluded** endpoint, the appointment blocks itself,
and the gap looks like a defect unless explained.

Copying the sentence here was the obvious mistake and it was made — and then caught by looking at the
real grid, where `11:00` was plainly present. This page reads `…/manage/availability`, which excludes
the appointment being moved, so its own hour **is** offered back. The note is gone; a comment where
it stood says why the two screens differ.

Worth generalising: a sentence explaining an absence is a sentence that has to be re-checked whenever
the thing stops being absent.

### 5.3 A phone number that already exists silently replaces the name and email you typed

`CustomerService.findOrCreate` matches on `(businessId, phone)` and returns the existing Customer
**untouched** — the supplied name and email are discarded. That is deliberate and documented for the
name (booking for a partner must not rename the Customer). The email follows the same code path.

It cost time here: a fixture booked as `manage.fixture@example.com` produced a confirmation to
`ada@example.com`, which looked like the wrong recipient until the Customer row explained it. It also
has a real consequence for the booking page — §7.1.

### 5.4 A missing email is not always a missing email

The reschedule produced no message in Mailpit, which looked like an enqueue that never happened. It
was not: the row existed, `RESCHEDULE`, correctly rendered — and was `CANCELLED`, because the
appointment was cancelled fifty seconds later and cancelling voids every pending notification,
reminders included. Correct behaviour, and worth knowing before you go looking at
`NotificationEnqueuer`. **Check the `notifications` table before concluding anything from Mailpit.**

---

## 6. What was verified

### In a real browser, against a live stack, with real pointer input

Against `Phase 06 Scratch`, whose existing 14:30 appointment made an unusually complete fixture: it
resolves `canCancel: false` with **no policy text and no contact details at all**, which is the
hardest refusal the page has to render.

- **The refusal** — *"Too late to change this online"*, the `CONFIRMED`-but-shut branch rather than
  the settled one, with the no-contact fallback and no action buttons.
- **The metadata** — `referrer: no-referrer`, `robots: noindex, nofollow`, and **zero outbound links
  on the page**, so there is nothing the token could ride out on.
- **The Link Expired page**, against a genuinely forged signature. Reached by accident — a token
  typed from memory — which makes it a real check that the HMAC is verified rather than a mocked one.
- **The exclusion, proven against both endpoints.** For 16 September the public grid returns 22 slots
  and the manage grid 29. The seven it withholds are exactly `10:15`–`11:45`: every start a
  60-minute service could not take against an 11:00–12:00 booking. `11:00` itself is on screen in the
  page's own grid.
- **A reschedule end to end** — 16 September 11:00 → 17 September 15:00. In the database: `RESCHEDULED`
  with `actor_type = CUSTOMER` and the previous times in the payload, **the first customer-sourced
  reschedule the audit trail has held**. The `RESCHEDULE` mail rendered with was/now times, the
  unchanged code, and a fresh Manage Link carrying the new expiry.
- **A cancel end to end**, with a reason. `status = CANCELLED`, `cancelled_at` and
  `cancellation_reason` set, `CANCELLED` event with `actor_type = CUSTOMER`, and the cancellation
  email in Mailpit one second later. The page moved to the *settled* refusal — both branches of §4.4
  now seen.
- **The `409`, raced for real**, by booking the held slot from `curl` while the page had it selected.
  The banner carried the server's own words, the appointment did **not** move, the selection dropped,
  the Move button disabled, `09:00` vanished from the grid, and Monday 21 went from 29 times to 25 —
  exactly the four starts a 60-minute booking at 09:00 removes from a 09:00 opening.
- **360 px** — no horizontal overflow, the day strip four columns, the slot grid three, and **all 47
  controls ≥ 44 px** with the reschedule grid open.

### A correction to the previous handoff

It recorded that on the booking page at 360 px "every control in `main` measures ≥ 44 px". That was
true of the state measured and not of the completed flow: the details card and the Confirm button do
not exist until a slot is chosen, and once chosen there were four controls at 40 px. **The Confirm
button now carries `min-h-11`**, as the manage page's actions do — the same pattern the slot buttons
and the pager already used.

**The three text inputs are still 40 px, on both public pages and everywhere else.** That is the
shared `Input` height, and raising it is a design-system change affecting every screen — a decision
rather than a fix, so it was left. §7.2.

### What has no automated test

**Still no frontend test harness.** `frontend/package.json` has no `test` script and no test
dependency; the gate is `lint`, `typecheck`, `format:check`, `build`. Everything in this section was
established by hand and **none of it is enforced by CI**. A regression in either public page will not
be caught by the pipeline. Standing a harness up is phase-11 work.

Beyond that: **"Any available" with more than one eligible Employee is still unexercised** — the one
box left unticked in the phase document, and now doubly relevant, because the reschedule grid is
*always* an "anyone" grid and its `manyPeople` labelling has therefore never been seen with real
data. Creating that fixture is the first thing to do next time the dashboard is open.

---

## 7. Findings that are somebody else's call

### 7.1 The booking page promises an email it may not send

Not introduced here — found while verifying, in already-committed phase-08 code.

`Confirmation` says *"A confirmation is on its way to **{what the Customer typed}**"*. When the phone
number matches an existing Customer, `findOrCreate` returns that Customer untouched (§5.3) and the
mail goes to **their stored address** instead. Worse: `NotificationEnqueuer` gates on the *Customer's*
email, so a returning Customer whose stored record has **no** email gets **no message at all**, while
the screen promises one and names an address.

Rare in a fresh tenant, ordinary in a real business where people book twice. Three ways out, and the
choice is a contract decision rather than a copy fix:

| | |
|---|---|
| **Soften the copy** | Say a confirmation is on its way "to the address on file for this number" without naming one. One file, no contract change, and vaguer for the common case where the two agree |
| **Tell the client where it went** | Add the resolved recipient (or a `confirmationSent` boolean) to `BookedAppointment`. Honest and precise; it publishes one more fact about a returning Customer to whoever holds their phone number |
| **Update the stored email on booking** | Makes the promise true by changing the data. It also silently overwrites a Customer's address from a public form, which is the defect `findOrCreate` exists to avoid for the name |

The middle one looks right and the third looks wrong, but it is a published-response decision, so it
is left here rather than taken.

### 7.2 The shared `Input` is 40 px on a mobile-first surface

Below the 44 px guideline both public pages are otherwise built to. Raising `Input` touches every
screen in the dashboard, so it is a design-system decision. Small; named so it is a choice.

### 7.3 There is no way in without the email

The API accepts a second proof — Confirmation Code **and** phone number, via
`POST /public/appointments/lookup` — and **no page uses it.** The endpoint is built, tested and
rate-limited at 5/hour; the "find my booking" screen simply does not exist. So a Customer whose link
has expired or who deleted the email has no self-service route at all, which is why `link-expired.tsx`
sends them back to the original email rather than offering a way forward.

Deliberately not built: it is not in phase 08's Frontend checklist, and adding a page would have been
scope growth. But it is the one thing that would make the public surface feel finished, and it is
small — the lookup returns the same `ManagedAppointment` this page already renders, so it is a form
in front of machinery that exists. Phase 10 or 11 is the natural home.

### 7.4 Carried, and still carried

- **`Actor.system()` still has no caller.** Four handoffs now. This one does not predict either.
- **`aiEnabled` has no Settings UI.** Phase 09 owes the control as well as the chat.
- **`RATE_LIMIT_ENABLED` is absent from `.env.example`.** One line, still not taken mid-task.
- **CI's deprecation warnings.** Phase 11 owns the workflow file.

### 7.5 The verification tenant has grown again

`Phase 06 Scratch` gained three appointments this session: one cancelled (`AZVS6AB3`, moved then
cancelled, so it carries a three-event audit trail — `CREATED`, `RESCHEDULED`, `CANCELLED`, all
`CUSTOMER`), one live on 18 September (`923MTH64`), and one booked purely to win a race
(`QKXFR3WJ`, 21 September 09:00). Left in place: it is verification wreckage on purpose, and the
cancelled one is the only appointment in the system with a customer-sourced reschedule on it.

---

## 8. Next: phase 09, and a pull request first

Phase 08 is done, so the branch is ready for **the first pull request since #4**. Two things belong
in its description: the frontend testing gap of §6 — every ticked box on both public pages is
browser-verified rather than pipeline-enforced — and the finding in §7.1, which a reviewer should see
even though it is not this branch's defect.

Then [phase 09](../phases/phase-09-ai-receptionist.md). Three things this session leaves it:

1. **The chat column is reserved on `/book/{slug}` and deliberately absent from `/manage/{token}`.**
   The reservation on the booking page is a Receptionist that helps a stranger *choose*; a Customer
   who already has an appointment has one short question. If phase 09 wants the space here, it is a
   layout change rather than a rethink.
2. **`isStaleSlot` is the one list of refusals that mean "re-ask the engine"**, and both public
   surfaces now go through it. A Tool that introduces a tenth code must add it there and nowhere else.
3. **`classic-flow.tsx` is smaller than the previous handoff left it** — the pager and the grid moved
   out to `FortnightPicker` — so the question of whether to extract its sections before adding a chat
   column beside it is less pressing than §7.5 of that handoff suggested.
