# Session handoff — 2026-09-08 — Phase 05 frontend, and an auth defect it surfaced

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done;
> §4 is where a decision had to be taken; §5 will save you the most time; §6 will stop you assuming
> coverage that is not there.
>
> **§7 is the one to read before anything else touches authentication** — a defect three phases old,
> found by running this screen for fifteen minutes. It is being fixed on a branch of its own, which
> §7.1 describes and this session has neither reviewed nor run.

---

## 1. Where the project stands

**Phase 05 is complete.** The availability preview is built and every state in it was exercised in a
browser against the real backend. Both Frontend checklist boxes in
`docs/phases/phase-05-availability-engine.md` are now ticked.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — protected, and **one commit behind `dev`** |
| Working branch | **`dev`** — committed, **not pushed**, CI has not seen it |
| Backend tests | **538**, untouched — this session changed no backend code |
| Frontend | Type-checks, lints, formats; `next build` passes with **17 routes** |
| Migrations | None. This session changed no schema |

### The commit

```text
1374e24  Phase 05 — the availability preview
```

Four files: two new (`lib/scheduling/`, `availability-section.tsx`), the employee detail page, and
the phase document.

### What has not happened

**`dev` is not pushed and the pull request is not open.** `dev` is a genuine ancestor of `main` as
of the previous session, so §5.1 of the phase-05-backend handoff no longer applies — the next pull
request has a real merge base and there is nothing to prove before opening it:

```bash
git push origin dev
gh pr create --base main --head dev --fill
gh pr checks --watch
gh pr merge          # a merge commit, not a squash — see the previous handoff §5.1
```

**There is now a second branch, and it is a sibling rather than a descendant.**
`claude/angry-ardinghelli-001b84` carries the §7 fix and was cut from `main` (`c2ebfec`) rather than
from `dev`, so the two share `a038093` as their merge base and neither contains the other.

Nothing overlaps: that branch touches `auth/`, `ErrorCode`, `lib/api/client.ts` and
`lib/auth/session-context.tsx`; this one touches the availability preview, `lib/scheduling/` and its
documents. Because the merge base is genuine, §5.1 of the previous handoff — the add/add conflict
storm a squashed `main` produced — applies to neither.

**Checked rather than assumed**, and the check costs nothing and touches no working tree:

```bash
git merge-tree --write-tree dev claude/angry-ardinghelli-001b84
```

It exited `0` with a tree hash and no conflict list, so the two merge cleanly and **merge order does
not matter**. Run it again before merging if either branch has moved since; a diffstat that looks
disjoint is a prediction, and this is the answer.

Both still have to be merged. Two branches off one commit is the state four handoffs complained
about arriving by a different road.

---

## 2. Running it

Unchanged. `make up`, then the backend from the IDE and `pnpm dev` from a terminal. Open
**http://localhost:9080**, never 9082.

**Two open items from the last two handoffs are now settled, one of them by measurement:**

**The running backend has the timezone fix.** It had been flagged as "must be restarted" in two
handoffs. Proven rather than assumed: registering a fresh business wrote `09:00:00` into
`business_hours` on a machine at UTC+4. Before the fix that write produced `05:00:00`. **This is a
better check than reading a row back through the API**, which round-trips the shift and looks
correct either way.

**The owner's five `business_hours` rows are still shifted**, and the owner chose to correct them by
re-entering the week rather than by SQL. That needs their own session, which this one did not have —
see §8.

---

## 3. What exists now

**`frontend/src/lib/scheduling/`** — the fifth lib module, mirroring the backend package:

| | |
|---|---|
| `types.ts` | `Availability`, `AvailabilityDay`, `AvailableSlot`, `SlotEmployee`, `EmptyReason`, `AvailabilityQuery` |
| `api.ts` | `availabilityPath(query)` — a **path builder**, not a call. See §4.2 |

**`app/(dashboard)/employees/[id]/availability-section.tsx`** — the section and, in the same file,
the `Slots` child that owns the request, plus `explain()` which turns an `EmptyReason` into a
sentence and a link.

**The employee detail page** now reads `useSession()` for the business timezone — needed to default
the date input to *today in the business's zone* before any response has arrived to name it — and
gates a second `ResourceGate` on the `/services` resource it already loads.

**No new UI primitives, and no new dependency.** The section is `Card` + `CardHeader` + `Select` +
`Input` + `Button` + `ResourceGate` + `EmptyState` + `ErrorState`, all of which existed.

---

## 4. The decisions that will shape phase 06 and 08

### 4.1 One date at a time

The endpoint takes up to 31 days. The preview asks for one, because **`emptyReason` describes the
whole result rather than each day in it** — over a range it only appears when every day is empty,
and it stops meaning anything precise. A single date is the shape in which a reason means exactly
one thing, which is the point of a screen built to prove the engine.

The component still renders `days` as a list rather than reaching for `[0]`, so phase 08 widening it
is not a reshape.

**The consequence to accept:** nothing on this screen can produce the `422` for a range over 31
days. That case lives in `AvailabilityEndpointTest` and nowhere on this side.

### 4.2 The read is a path, and the result is keyed on it

`useResource` takes a path rather than a fetcher — established in phase 03 for a reason that has not
changed. This is the first read in the app whose **question changes while the screen is open**, and
a path is what makes that expressible: a different question is a different string, so the hook
re-runs because the question moved and for no other reason.

But `useResource` also **keeps the data it last loaded when a later load fails**, which is correct
for a screen reloading one resource and wrong here. The previous answer is about another service or
another day; leaving it under a changed picker states something false, and it hides the loading and
error states behind a stale success. So the slot list is a child keyed on the request path — a new
question remounts it and it starts from nothing.

**What this means for you:** if phase 08 or 09 adds another read whose question changes in place,
this is the shape to copy, and the reason to copy it is the failure mode rather than the tidiness.
Changing `useResource` to clear `data` on error instead would fix this case and break every screen
that reloads after a save.

### 4.3 The picker resolves its selection rather than storing it

Four editors above this section can change `employee.serviceIds` while the screen is open. A stored
service id would leave the picker naming a service the employee no longer provides, and the server
would refuse a choice the owner never made with `EMPLOYEE_CANNOT_PERFORM_SERVICE`. The selection is
resolved on every render — `assigned.find(...) ?? assigned[0]` — so that state is not expressible.

The same expression is the empty check: `!selected` is exactly "nothing is assigned", which is why
there is one branch rather than two.

### 4.4 Inactive services are marked, not hidden

`Full day audit · 6 hr · not offered`. A service the owner deactivated a moment ago disappearing
from the picker is the confusing answer; the server's own `SERVICE_INACTIVE` sentence — *"Full day
audit is not currently offered, so it has no availability."* — is the clear one. The same instinct
as `AssignmentPicker` marking an inactive employee rather than dropping them.

### 4.5 `Recalculate` is the one control that is not a question

Saving a working schedule changes the *answer* without changing the *question*, so no path moves and
the preview would go on showing something computed before the save. Rather than couple this section
to the other four resources — which would make each of them responsible for a section none of them
knows about — the button bumps a counter that forms part of the key.

### 4.6 The reason is rendered twice

A sentence for the owner, pointed at the setting that would change it (`OUTSIDE_HORIZON` → Booking
settings, `CLOSED` → Opening hours), and the raw enum underneath in small monospace. The phase
document asks for a preview that is developer-facing *and* useful to an owner; those are two
readers, and the enum is what the first one needs. `ErrorState` already sets this precedent by
printing the error code under the message.

**`NO_ELIGIBLE_EMPLOYEE` has copy but is unreachable from this screen.** That is a property of the
service, not an oversight: the preview always names an employee, and both ways to earn that reason
are refused earlier by `AvailabilityService` with their own messages. Verified by deactivating the
employee and getting `EMPLOYEE_INACTIVE` instead. **If phase 08 asks without an employee, that
reason becomes reachable and the copy is already there.**

---

## 5. Traps already paid for

### 5.1 A hidden Browser pane composites nothing, and the failures do not say so

This session lost the most time here, and every symptom pointed somewhere else.

| Symptom | What is actually happening |
|---|---|
| Screenshots return a blank page | The pane is hidden, so the page is not drawing. **Only the top of the document composites**; anything scrolled to comes back blank even when `getBoundingClientRect` says it is in view |
| `computer` click fails: *"ref is entirely outside the viewport (center (-53, -104))"* | Same cause. The ref is fine |
| `computer` scroll and hover time out after 30s | Same cause, and the error message says so — believe it rather than retrying |
| An injected script times out at 45s | `requestAnimationFrame` **never fires** in a hidden pane. The phase-04 handoff §5.3 said this about `setTimeout`; it is true of any frame or timer wait. Write synchronous scripts |

`tabs_select` fronts the pane, but it was hidden again by the next call. **Batch `tabs_select` with
the action that needs it**, in that order, in one `browser_batch`.

To photograph a section far down a long page: front the tab, set `display:none` on its previous
siblings, scroll to 0 and screenshot, all in one batch — then reload to restore. The DOM change is
transient and does not touch what the component renders.

Throughout, `get_page_text`, `find` and DOM queries worked perfectly while screenshots were blank.
**That is the third handoff in a row to say believe the DOM**, and this is the strongest version of
it: the picture was not stale, it was empty.

### 5.2 `form_input` does not tick a React checkbox

It sets the value and dispatches, and React's controlled `<select>`, `<input type=date>` and text
inputs all pick it up. A checkbox does not: React listens for the click, so the box changes in the
DOM and `onChange` never runs — the state does not move and the save button stays disabled, which
reads as a broken form. Use a real `left_click` on the checkbox.

Confirming which one you have is one line, and worth it before assuming the form is at fault:

```js
const cb = document.querySelector('input[type=checkbox]');
const btn = [...document.querySelectorAll('button')].find(b => b.textContent.includes('Save'));
({ checked: cb.checked, saveDisabled: btn.disabled })
```

### 5.3 The dev server double-fires every request, and one of each pair is an abort

`read_network_requests` shows every availability call twice — one `net::ERR_ABORTED` and one `200`.
That is React 19 StrictMode double-invoking effects in development, with `useResource`'s
`AbortController` cancelling the first. It is correct behaviour and it is development-only.
**Do not read the aborts as failures**, and do not count requests to judge whether a change caused a
re-fetch.

### 5.4 Everything from the earlier handoffs still applies

Particularly `pnpm build` against a tree running `pnpm dev` (this session used the copy-to-tmp recipe
from the session-aware-pages handoff §6.1) and `noUncheckedIndexedAccess`, which is why `selected`
is `ServiceDetail | undefined` and why that turned out to be the natural place for the empty branch.

The phase-04 rule about the route table held: two routes are `ƒ` and they are the same two as
before. **No route that was `○` became `ƒ`.**

---

## 6. What is not covered by a test

- **There is still no frontend test runner**, by design (`08-testing-strategy.md` §11). Everything
  below was verified by hand.
- **Nothing in this session is automated.** `explain()` is pure and maps a closed enum onto copy,
  which makes it the cheapest thing here to test the day a runner exists — and the thing most likely
  to fall out of step, because a fifth `EmptyReason` would land in its `default` branch silently.
  The `Slots` keying in §4.2 is the most valuable, and the hardest without a DOM.
- **The 31-day `422` cannot be produced from this screen.** See §4.1.
- **`NO_ELIGIBLE_EMPLOYEE` was never rendered.** See §4.6.
- **No slot has ever been withheld by an appointment**, because none exist. Every `FULLY_BOOKED`
  seen here came from time off. Unchanged from the backend handoff §6 and still phase 06's to close.
- **The preview has only ever asked for one employee.** The multi-employee path — several eligible
  people, the tie-break attaching one per slot — is covered in the unit suite and by nothing in a
  browser.
- **`safeNextPath` still has no test file.** Carried forward unchanged since the session-aware-pages
  handoff.

### What *was* verified in a browser

In a throwaway second tenant, deleted row by row afterwards: the unassigned empty state; `CLOSED`
for an employee with no working schedule; thirteen slots on the current day where the minimum lead
time cut the first four — arithmetic checked against the clock — and seventeen on a clear day; the
response's own `days[].date` echoed back; `OUTSIDE_HORIZON` for a past date, for a past **Sunday**,
and for a date beyond `max_advance_days`; `CLOSED` for a future Sunday and for a six-hour service
against a five-hour overlap with nothing booked; `FULLY_BOOKED` from a day of time off;
`EMPLOYEE_INACTIVE` and `SERVICE_INACTIVE` in the error state; the loading spinner; and the
cleared-date state.

**Two of those are worth more than the rest.**

**The §4.3 precedence is now visible in a browser.** The same Sunday one week in the past reports
`OUTSIDE_HORIZON` and one week in the future reports `CLOSED` — the rule that a date which has
already happened is not a question about opening hours. And a six-hour service against a five-hour
overlap with nothing booked reports `CLOSED`, which is the case the engine's first implementation
got wrong.

**The business zone is proven rather than assumed.** The tenant was switched to `America/New_York`
(UTC−4) while the browser ran at UTC+4 — **eight hours apart** — and the slots read 10:00–15:00,
which is the business's clock. A test in a zone that matches the browser's could not have told the
difference, which is the same lesson as the suite running at UTC+14.

**The owner's own business was confirmed unchanged afterwards**: one business, one user, one
membership, five hours rows, one service, one employee, one assignment, no schedules, no time off,
no closures, no FAQs.

---

## 7. A defect this session found, and handed off

**The transparent refresh never fires in a browser. It has been that way since phase 02.**

Fifteen minutes into the session a `GET /availability` returned `401` with code `UNAUTHENTICATED`.
Requests seconds earlier were `200`, and a manual `POST /auth/refresh` immediately afterwards
returned `200` — **the session was fully recoverable and the client never tried.**

Two halves of the design contradict each other, and each is individually reasonable:

| | |
|---|---|
| `AuthController.respond` | sets the access cookie's max-age to the token's remaining lifetime, so *"the browser drops it exactly when the server would stop accepting it"* |
| `ProblemAuthenticationEntryPoint` | answers `TOKEN_EXPIRED` only for an `InvalidBearerTokenException` whose description contains "expired" — that is, only when a token was **presented** and rejected |

Because the first makes the browser delete the cookie at expiry, the server never receives an
expired token. It receives none, which is `UNAUTHENTICATED`. And `lib/api/client.ts` retries only on
`TOKEN_EXPIRED`, so neither the refresh nor `onSessionExpired` — which lives inside the same
`retryable` branch — ever runs.

The user-visible result is an error state every fifteen minutes that "Try again" cannot clear,
because nothing calls `/auth/refresh`. That is precisely what
`ProblemAuthenticationEntryPoint`'s own doc comment says it exists to prevent.

**Why 538 tests are green:** integration tests drive HTTP through `AuthTestClient`, which does not
honour cookie max-age the way a browser does, so no test can watch the cookie disappear. The phase
02 handoff §8 already listed *"access token expires mid-session, client refreshes and retries
without the user noticing"* as not automated. **This is that gap, and it was not theoretical.**

**Not fixed here**, because it is authentication rather than availability and the fix is a real
decision — lengthening the cookie past the token has security reasoning behind it that the current
choice was made for. It was handed to a session of its own, with the three candidate approaches and
the constraint that any client-side fix must keep the single-flight refresh promise, since five
concurrent refreshes would rotate and revoke the family.

**The regression test that reproduces it** omits the access cookie while keeping the refresh cookie.
That shape is what no existing test does.

### 7.1 What the other session has done, and what this one can vouch for

`claude/angry-ardinghelli-001b84`, one commit — `bd97b4a`, *"Make the transparent refresh actually
fire, and both ways out of a 401"*. **This session did not write it, has not read it beyond its
commit message and diffstat, and has not run it.** Recorded here so the next reader knows the branch
exists and what it claims, not as a review.

What it claims: a new `SESSION_REFRESHABLE` code for "no access token, but a refresh cookie is still
in hand", which the client treats as it treats `TOKEN_EXPIRED`; both halves of that condition
load-bearing, so a forged token stays on the `UNAUTHENTICATED` path. It reports fixing a mirror bug
this session did not name — `onSessionExpired` sitting inside the retry branch, so a session that
was genuinely gone notified nobody and left the same stuck screen from the other side. It adds
`TransparentRefreshTest` and an `expireCookie` on `AuthTestClient`, which is the browser behaviour
whose absence let this survive two phases, and reports 545 tests.

It also amends `docs/sessions/2026-09-07-phase-02-authentication.md`. **A previous session's handoff
was edited rather than superseded**, which is a departure from how this folder has worked — worth
knowing before trusting a handoff's date as the date of its contents.

**What it means for this screen:** once merged, an expired session refreshes underneath the
availability preview instead of surfacing `UNAUTHENTICATED` in its error state. Nothing here needs
changing for that, and §6 of this document still stands — `lib/api/client.ts` acting on any of it
has no automated coverage either way, because there is still no frontend test runner.

---

## 8. Open items

Everything in §8 of the phase-05-backend handoff still stands unless listed below. Changed or added:

- **`dev` is one commit ahead of `main` and not pushed.** CI has not seen the preview. This is the
  highest-value next action, and it is now cheap — see §1.
- **The backend restart item is closed.** The running backend has the timezone fix, proven by a
  fresh registration writing `09:00:00`. See §2.
- **The five stale `business_hours` rows are still shifted.** The owner chose to correct them by
  re-entering the week rather than by SQL, which needs their own session. **Open
  `/settings/hours` and enter Monday to Friday as 09:00–17:00.** Until then the preview against the
  owner's business will answer honestly about hours of 05:00–13:00 and look four hours wrong.
- **The transparent refresh is broken in a browser.** See §7. **A fix exists on
  `claude/angry-ardinghelli-001b84` and is unmerged and unreviewed here**; `dev` still carries the
  defect. Until that branch lands it affects every screen, and it will affect anyone verifying phase
  06 in a session longer than fifteen minutes — **if a request fails with `UNAUTHENTICATED`
  mid-session, that is this, not your code.**
- **`EmptyAppointmentImpact` is still the last stub standing**, with five methods. Phase 06 deletes
  it. `blockedRangesFor` is the one whose wrong answer is silent.
- **`aiEnabled` and `aiDailyCostCapCents` still have no UI.** Phase 09 owes them.
- **Node 20 deprecation warnings in CI** — unchanged, warnings only.

---

## 9. Next: Phase 06 — Appointments

Read `docs/phases/phase-06-appointments.md` in full, and §4 of the phase-05-backend handoff before
it. That handoff's §9 lists the three things phase 06 must do; they are unchanged, and this session
adds two:

1. **The preview is the fastest way to see phase 06 working.** It already reads busy ranges through
   `AppointmentImpact.blockedRangesFor`, which returns `Map.of()` today. The first real appointment
   should make slots disappear from this screen with no frontend change at all — and if it does not,
   the stub was left in place, which is exactly the silent failure the backend handoff warned about.
2. **`FULLY_BOOKED` will finally mean what it says.** Every one seen so far came from time off.
   A booking that removes the last slot is the case that proves the engine and the write path agree,
   and `SlotBookabilityTest` is what guarantees they do today.

**Land §7's branch before verifying phase 06 by hand**, or budget for a session that stops working
every fifteen minutes. It is one merge, and it is the difference between a long verification session
and an interrupted one.
