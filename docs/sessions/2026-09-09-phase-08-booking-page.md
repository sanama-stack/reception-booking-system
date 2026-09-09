# Session handoff — 2026-09-09 — Phase 08, Public Booking, the `/book/{slug}` page

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done and
> what is emphatically not; §5 will save you the most time.
>
> **§7.1 is settled** — this work is committed and the reconciliation it warned about was a
> non-event. Read it anyway if you are about to touch `CustomerFieldNames`.

---

## 1. Where the project stands

**Phase 08's frontend half is itself half done.** `/book/{slug}` exists, is server-rendered, and was
driven end to end in a real browser — a stranger books, gets a Confirmation Code, and the email
arrives with a Manage Link whose token resolves. **`/manage/{token}` does not exist**, so the phase's
Definition of Done stays open. See §8.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Working branch | `dev`, at **`9785269`** |
| **Committed** | **Yes**, in two commits — the phone fix, then the page. Written before they were made, so §7.1 records why the reconciliation it warns about turned out to be a non-event |
| CI | **Has not seen this yet** at the time of writing — not pushed in this session |
| Backend tests | **726**, up from 720 — six added, all green, full `./gradlew build` clean |
| Frontend gates | All four clean, `pnpm build` included. It was run before committing; `/book/[slug]` builds as a dynamic route |
| Migrations | **None.** Phase 08 still adds no table and no column |

Two pieces of work landed, and the second came out of verifying the first:

1. **The booking page** — eleven new frontend files, and six existing ones changed.
2. **An audience-aware phone refusal** in the backend, because driving the page found it telling
   Customers to change a Settings screen they have no account for. §4.3.

---

## 2. Running it

Unchanged, with three things worth knowing before you drive this surface.

**The backend does not hot-reload.** There is no `spring-boot-devtools`, so a pull that touches Java
needs the application restarted before the change is live. This session lost time to a backend that
had been started *before* the phase-08 commit: every `/public/*` path answered `401 UNAUTHENTICATED`,
which reads exactly like a `SecurityConfig` gap and is not one. **If a public endpoint is
unauthenticated-refused, check the process start time against the commit before you read any code**
— `ps -o pid,lstart -p $(lsof -nP -iTCP:9081 -sTCP:LISTEN -t)`.

**Rate limits are on and `RATE_LIMIT_ENABLED` is in neither `.env` nor `.env.example`.** It is read
by `application.yml` and defaults to `true`, which makes it invisible unless you grep for it — the
README calls `.env.example` "the authoritative list" and this variable is missing from it. The
binding limit while building against the booking page is **10 bookings per hour per IP**, and a
validation failure spends one, because `RateLimitFilter` runs before the body is validated. Add
`RATE_LIMIT_ENABLED=false` to `.env` and restart.

**Ports and everything else are unchanged.** Gradle still needs
`JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

---

## 3. What exists now

**`frontend/src/app/book/[slug]/`** — seven files.

| File | |
|---|---|
| `page.tsx` | Server component. Reads profile and catalogue, header, the two-column layout |
| `classic-flow.tsx` | The flow and all of its state. The big one |
| `availability-step.tsx` | The fortnight day strip and the slot grid |
| `confirmation.tsx` | The Confirmation Code screen |
| `business-panel.tsx` | Reserved Receptionist slot, opening hours, cancellation policy |
| `not-found.tsx` | The designed `404` |
| `not-accepting-bookings.tsx` | A real business with nothing bookable |

**`frontend/src/lib/public/`** — hand-written mirrors of `PublicResponses` and `PublicRequests`, plus
the path builders. **Deliberately not the dashboard's types**: `business.BusinessProfile` carries an
id, a slug, the cost cap and the booking horizon, none of which a stranger is ever sent, and the
first screen to read one of them would compile. `Money`, `DayOfWeek` and `WallClockTime` *are*
reused, because those are the same shape on both surfaces.

Every slug is `encodeURIComponent`d into its path segment, in one place. `SlugService` only produces
`[a-z0-9-]`, but nothing stops a visitor typing something else into the address bar, and an unencoded
`..` in a segment is a request this application did not mean to make.

**Three shared modules changed rather than copied:**

- **`components/empty-reason.tsx`** gained an `audience`. One switch over the closed enum, as its own
  javadoc argued for; the customer branches carry no `/settings` links, because an anonymous visitor
  cannot open them, and say "in this period" where the owner's say "that day" — the owner asks about
  one date and a Customer is shown a fortnight.
- **`lib/scheduling/stale.ts`** is new: `STALE_SLOT_CODES` moved out of the dashboard's booking page
  and became `isStaleSlot`. Both surfaces book through the same `BookingService` and can be refused
  for any of those nine reasons, so a second copy is a second list for phase 09's Tools to forget.
- **`lib/time`** gained `isoDateWeekday` and `addIsoDays`. Both do calendar arithmetic at UTC
  midnight, which is safe *because* it is calendar arithmetic and not instant arithmetic — a date has
  no offset to be perturbed by. The javadoc says so, since the module exists to prevent the opposite
  mistake.

**Backend: two new files and six changed.** `common/phone/PhoneAudience.java` and
`PhoneFieldTest.java` are new; `PhoneField`, `CustomerFieldNames`, `CustomerService` and
`EmployeeService` thread the audience, and two existing endpoint tests gained a message assertion.
§4.3.

---

## 4. The decisions

### 4.1 A fortnight per availability request, not a date at a time

The phase-05 preview left a comment saying "phase 08's public page is where the range earns its
keep", and it was right. The grid asks for **14 days** in one request and renders a strip of day
chips carrying each day's slot count; the slots below are the chosen day's.

A Customer poking one date at a time cannot tell a closed Monday from a full one without trying it,
and a business booked solid for a week reads as a broken page. Fourteen is not arbitrary: it is the
largest window that still fits as tappable chips at 360 px without scrolling sideways — four columns
there, so fourteen is three and a half rows. Asking for the endpoint's full 31 days would be fetching
an answer the page cannot show.

`emptyReason` describes the whole result rather than each day in it, which suits this exactly: a
fortnight with any availability carries `null` and the empty days simply have no slots, and a
fortnight with none carries the one reason that explains all of it.

**The active day is derived, not stored.** The Customer's choice when the current window still holds
it *and* it still has times, otherwise the soonest day that does. That fallback is what makes paging
and changing the service work with no effect to resynchronise them — a chosen date from the previous
fortnight simply is not found. It was watched working: when a day was booked out from under the page,
the strip showed `Wed 9 —` and the grid moved itself to `Thu 10`.

### 4.2 Per-slot employee naming is derived from the Slots, never from the selection

Whether each time is labelled with a name is decided by `new Set(slots.map(s => s.employee.id)).size > 1`.

The obvious rule — label them when the Customer named nobody — is wrong in a way that only shows up
with real data. A Service only one person provides **hides its picker**, because there is nothing to
choose, so no `employeeId` is ever sent and "the Customer named nobody" stays true; labelling from
that stamped *the same name under all fifteen buttons*. Deriving it from the Slots handles all three
cases with one condition: one eligible person (no names), several with "Anyone available" (names), and
a named person (one id, so no names).

The `employeeName` prop survives, but only to word an empty result — "Dana is not working" and
"nobody is working" are different facts.

### 4.3 The phone audience is bundled into `CustomerFieldNames`, not passed beside it

`PhoneField.unreadableMessage` had two branches, and the country-less one said *"…or set your country
in Settings so local numbers can be understood."* That is an owner's remedy. The public page renders
server-provided field messages verbatim — deliberately (`docs/02-product-architecture.md` §7) — so a
Customer booking with a country-less Business was told to change a screen they have no account for.
The architecture is not at fault; a shared service assuming its client was.

This is the same defect class `CustomerFieldNames` solved for field *names* in the backend half, one
step along. So it is fixed the same way: `PhoneAudience` arrives as an argument, never inferred.

**The audience lives on `CustomerFieldNames` rather than travelling as its own parameter**, and that
is the part to argue with if you disagree. The audience is 1:1 with the entry point — every caller has
exactly one — so two parameters could be passed inconsistently, and `PUBLIC_BOOKING` with `OWNER` is
*precisely* the defect being fixed, failing silently again. Bundling makes the combination
unrepresentable.

The cost is a record named for field names that carries something else. The name was kept because two
shipped javadocs and three phase-08 handoffs refer to it, and a stale name in a record is cheaper
than stale names in the documents that explain it — the javadoc now says so out loud. **If you would
rather it were renamed, `RequestVocabulary` fits the javadoc's own word and it is eight files.**

`PhoneAudience` had to exist regardless of that choice: `EmployeeService` calls `PhoneField.normalise`
and has no `CustomerFieldNames` to hang an audience on.

Copy, for the record: the country-set message is **unchanged** and shared, because "that is not a
number we can reach" is equally true and actionable for both, and two strings would be two to keep in
step. The owner's country-less message is **unchanged**. Only the Customer's is new, and it names the
country code rather than leaving "international form" to be guessed at, because it is now the whole of
the advice.

### 4.4 The `401`/`404` split, which is load-bearing

Only a `404` renders "there is no booking page at this address". Anything else — the backend down, a
rate limit, a `500` — renders a fault page that says the *link* is fine and the problem is at our end.
Saying "no such business" for a transient fault tells a visitor to go back to whoever sent them a
link that works.

### 4.5 `robots: noindex` on the booking page

A small call, made rather than asked. Real booking pages want indexing; the landing page is still a
placeholder and these are test businesses. One line in `generateMetadata` to reverse, and phase 11
owns whatever the marketing surface wants.

---

## 5. Traps already paid for

### 5.1 A hidden Browser pane is `0×0`, and that breaks more than screenshots

Already known for `navigate` timing out. Three more consequences, all of which cost time here:

- **Pointer input cannot be dispatched at all** — `left_click` fails with "the press at (0, 0) could
  not be attributed to a frame". Driving the page meant `element.click()` and native-setter `input`
  events instead, which exercises React's handlers but proves nothing about hit targets. Those were
  measured numerically instead (§6).
- **`requestAnimationFrame` never fires**, so any `await new Promise(r => requestAnimationFrame(r))`
  hangs until the tool's 45-second timeout. Use `setTimeout`.
- **`scrollTo({behavior: 'smooth'})` does nothing** for the same reason, while
  `behavior: 'auto'` works. `window.innerWidth` and `innerHeight` both read `0`.

`resize_window` *does* work while hidden — it overrides the layout viewport independently of the pane
— which is what made the 360 px measurements possible.

### 5.2 `curl` cannot verify Next's streamed content, and produced a convincing false alarm

`curl` on `/book/no-such-business` returned `404` with a body whose only visible text was
"Reception". It looked as though the designed `not-found.tsx` had not rendered. It had: the content
was in the RSC flight payload with an `<div hidden id="S:0">` container, waiting for the inline
scripts that relocate it, and "Reception" was the `<title>` — which Next streams into the *body* in
dev, so stripping `<head>` by regex misses it.

**A page whose content is behind a Suspense boundary can only be verified in something that runs
scripts.** `curl` was fine for `/book/gd-studio-010`, whose content flushes inline; the `notFound()`
path suspends differently. Same tool, same command shape, opposite reliability — so do not conclude
"the component did not render" from a curl body without checking whether the string is present
anywhere in the response.

### 5.3 React batches, so N synchronous clicks all read the same state

Paging the availability window five times to reach the far end of the Booking Horizon has to be five
*separate* ticks. Five `click()` calls in one tick all read the same `windowStart` from their closure
and all set the same value, so the window moves 14 days rather than 70. Obvious in hindsight; it
looked like a broken pagination button.

### 5.4 Gating the details card on the selection made the page lie

A stale-slot `409` drops the chosen time deliberately. The details card was gated on `selection`, so
the name, phone and email **unmounted at the exact moment the banner said "everything you typed has
been kept"**. The values genuinely were kept — in component state — and came back when a new time was
picked. But a claim the reader cannot check reads as a lie, and the phase document calls the `409`
experience the page's own job. The card now stays once anything has been typed; the submit button
stays gated on the selection, because a time really is required.

Worth generalising: *technically* honest is not honest. The assertion was true and the screen
contradicted it.

### 5.5 One `emptyReason` covers both ends of the Booking Horizon

`OUTSIDE_HORIZON` arrives for a window inside the minimum lead time *and* for one beyond the maximum
advance, and nothing in the response distinguishes them. The first copy said "try dates a little
further out", which is backwards for half the cases — caught by paging past the 60-day limit and
reading it. It now names both directions.

### 5.6 `ErrorCode` was left imported after the stale-slot set moved out

Trivial, but `pnpm lint` is the only thing that catches it and the frontend's gate order means a lint
failure means `pnpm build` never ran (the phase-08 backend handoff §5.6). Mentioned because the
sequence recurs: move a constant out of a screen, and check what its type import was for.

---

## 6. What was verified, and what has no test at all

### Verified in a browser, against a live stack

`phase-06-scratch`, whose data made an unusually good fixture: mixed availability, closed weekends,
and days already partly booked.

- **The server render** — title from `generateMetadata`, `1 hr` from 60 minutes, `Wednesday · today`
  in the hours panel, weekends `Closed` (a day absent from the wire, reconstructed), `Times shown in
  UTC`, the 24-hour policy, and the Receptionist panel because this tenant has `aiEnabled`.
- **The day strip against the API, chip by chip** — `Wed 9 15`, `Thu 10 1`, `Sat 12 —`, `Mon 14 13`,
  and so on for all fourteen. Weekday labels correct, `aria-label` pluralising ("1 time").
- **A booking end to end**, code `87KEQWZB`. In the database: `source = CLASSIC` and
  `actor_type = CUSTOMER`, **both reaching their columns for the first time** as the backend handoff
  predicted; phone normalised to E.164; note stored; the outbox row `SENT`.
- **The real email in Mailpit**, subject and code matching the screen, carrying a Manage Link whose
  token resolves against `GET /public/appointments/manage`. It answers `canCancel: false`, because a
  24-hour window has already closed on a 14:30 appointment today — which is the case `/manage` must
  render as policy text rather than a raw error.
- **The `409`, raced twice for real**, by booking the slot from `curl` while the page held it. Banner,
  grid recomputed, selection dropped, entered details kept and visible. The recomputation was checked
  against the engine's own arithmetic: a 60-minute booking at 14:30 removed 13:45–15:15 and spared
  15:30, which starts as it ends.
- **A field error under the right input** — `aria-invalid` on Phone, message via `aria-describedby`,
  and **no** banner, because every message was accounted for. This is the `CustomerFieldNames`
  decision working in a browser.
- **The designed `404`** and the **"not taking online bookings"** page (`gd-studio-010`, which has no
  services and no contact details, so the no-contact fallback rendered too).
- **360 px** — `scrollWidth - clientWidth` is **0**, nothing overflows the viewport, the two-column
  grid collapses to `328px`, and **every control in `main` measures ≥ 44 px**. At 1280 px:
  `640px 304px` with a sticky aside and a seven-column strip.

### What has no automated test — and cannot, as things stand

**There is no frontend test harness in this repository.** `frontend/package.json` has no `test`
script and no test dependency; the whole frontend gate is `lint`, `typecheck`, `format:check`,
`build`. So the phase document's four "Frontend" testing boxes are satisfied by browser verification
and by nothing that runs in CI. **A regression in this page will not be caught by the pipeline.**
That is a gap to name deliberately rather than discover: standing one up is phase 11's kind of work,
and it is not in phase 08's scope.

Specific gaps beyond that:

- **"Any available" with more than one eligible Employee is unverified.** No tenant has two people
  assigned to one Service, so the branch that labels each Slot with a name — and the "Anyone in
  particular?" picker itself — has never been rendered with real data. It is the one part of the flow
  nothing has exercised. Creating that fixture is the first thing to do next time the dashboard is
  open.
- **`scrollTo({behavior: 'smooth'})` on the confirmation** is inspection-only, for §5.1.
- **Real pointer and touch input.** Hit targets were measured, not tapped.

---

## 7. Open items

### 7.1 Committed, and the second `PhoneField` session was a non-event — settled

Written as a warning, kept as a record. The phone-message defect was first raised as a background
task and that task was **started** before it was fixed here, so another session might have held its
own edits to `PhoneField.java`, `CustomerFieldNames.java`, `CustomerService.java` and
`EmployeeService.java` — and a merge taking half of each would have produced a `CustomerFieldNames`
whose constants disagree about the audience, with nothing failing loudly.

It did not. `git worktree list` showed one checkout, and the working tree held exactly this session's
coherent version. The two commits are `d6b797d` (the phone fix) and `9785269` (the page), made after
a full `./gradlew build` (726 green) and all four frontend gates including `pnpm build`, which had
never run against this code and passed.

**Kept because the hazard is real and recurring:** a background task spun off mid-session can be
started against the same files, and `git worktree list` is the thirty-second check that settles it
before a merge, not after.

### 7.2 The running backend needs a restart before the phone fix is live

No devtools (§2). The live application still returns the owner-facing message on the public path.
Nothing depends on this except seeing it by hand — the endpoint assertion in `PublicBookingTest` goes
through the real controller stack, which is what a browser click exercises.

### 7.3 Carried, and still carried

- **`Actor.system()` still has no caller.** Three handoffs have now predicted this would close. This
  one did not close it either and does not predict.
- **`aiEnabled` has no Settings UI.** The booking page now *reads* it — the Receptionist panel is
  absent for a business that has not switched it on — so phase 09 owes the control as well as the
  chat.
- **CI's deprecation warnings** are unchanged. Phase 11 owns the workflow file.
- **`RATE_LIMIT_ENABLED` is absent from `.env.example`** (§2). One line, deliberately not taken
  mid-task.

### 7.4 The verification tenant has grown

`Phase 06 Scratch` now holds **three `CLASSIC` appointments** beside its three `DASHBOARD` ones, and
9 September 2026 is fully booked as a result. Left in place on purpose — it is verification wreckage,
not a clean slate. Useful side effect: the dashboard's appointment detail screen now has real
`CLASSIC` and `ActorType.CUSTOMER` rows to render, and its copy for both was written in phase 06 and
**has still never been looked at**.

### 7.5 `classic-flow.tsx` is 420 lines of component

Not flagged as a defect — it is one form with four sections and the house comment density, and the
dashboard's `BookingFlow` is built the same way. Flagged so that whoever adds the chat column beside
it in phase 09 decides deliberately whether the sections want extracting first.

---

## 8. Next: `/manage/{token}`

The last unbuilt page in phase 08. Five things this session leaves you:

1. **There is a live Manage Link in Mailpit right now**, for `87KEQWZB`. Read it out of
   `http://localhost:9083` rather than making a new booking, and remember the rate limit.
2. **That appointment cannot exercise the happy path.** It starts at 14:30 today against a 24-hour
   window, so it resolves with `canCancel: false` and `canReschedule: false` — which makes it the
   perfect fixture for the **policy-aware refusal** the phase document asks for, and useless for
   testing a successful cancel. **Book a second appointment several days out** for that; the strip
   shows 15 September onwards wide open.
3. **Use `GET /public/appointments/manage/availability?token=` for the reschedule grid**, never
   `/public/businesses/{slug}/availability`. The backend handoff §4.2 explains why at length; the
   short version is that the ordinary endpoint cannot exclude the appointment being moved and will
   refuse to offer the Customer their own hour back.
4. **`canCancel` and `canReschedule` already combine the window with the status**, and
   `business.cancellationPolicy` and `business.phone` sit beside them — `ManagingBusiness` exists so
   that a refusal can say who to contact instead of being a dead end. The endpoint re-checks
   regardless: a field in a response is a hint, never a control.
5. **`lib/public/types.ts` stops short of the manage shapes** deliberately — `ManagedAppointment` and
   `ManagingBusiness` are not typed yet, because speculative types for a page nobody had written
   would have been guesses. `PublicResponses.java` is the thing to mirror, and the resolved JSON for
   `87KEQWZB` is in this session's transcript if you want a worked example.

After that, phase 08's Definition of Done closes and it is the first pull request since #4 — with the
frontend testing gap of §6 named in it, because a reviewer should know the four ticked boxes are
browser-verified rather than pipeline-enforced.
