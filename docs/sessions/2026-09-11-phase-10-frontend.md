# Session handoff — 2026-09-11 — Phase 10, the calendar, the numbers and the sweep

> **Purpose.** Enough context to take phase 11 — or to finish the one box phase 10 leaves open —
> without re-reading anything. §1 says where things stand; §2 is the three screens and what each
> one deliberately refuses to do; §3 is the two defects the sweep found, one of which had been live
> since phase 07 and was invisible on any day but a Friday; §4 is the sweep itself and exactly what
> each tick rests on; §5 is what is still not done.
>
> **Read §3.1 first if you read nothing else.** The suite was red on the tree the previous session
> left, and it would have been red in CI for the same reason — not because of phase 10.
>
> **Phase 10 is complete apart from the index verification (G12).** Everything the previous
> session left uncommitted is committed, and `dev` carries seven commits CI has not seen.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[backend]: ./2026-09-11-phase-10-backend.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`a80ffad`** — pull request #21 |
| `dev` | **seven commits ahead and unpushed**; CI has seen none of them |
| Backend | **827 tests, 0 failures**, counted from the XML |
| Frontend gates | `pnpm lint`, `pnpm typecheck`, `pnpm format:check` all green. **`pnpm build` still unrun locally (G9)** — deliberately: the user's `next dev` is running on 9082, and a build clobbers its `.next`. CI runs `pnpm build`, so the pull request is what closes this |
| Migrations | **None**, this session or the last |
| New ADR | None |
| Issues | **#15 and #17 open**, neither touched |

### 1.1 The commits

| | |
|---|---|
| `7dcd4f5` | the previous session's probe instrumentation and `stats.py`, which were untracked |
| `a9a1165` | phase 10's **backend half**, which the previous session left entirely uncommitted |
| `b9a0ddb` | three sessions' handoffs, likewise untracked |
| `15662c3` | **the Friday defect** — §3.1 |
| `2af2652` | `/calendar` |
| `555b38e` | `/analytics` |
| `12b082b` | the dashboard home |
| `9873432` | **the invisible label that widened the page** — §3.2 |
| `9250a11` | the phase checklist |

---

## 2. The three screens

### 2.1 `/calendar`

Day view is a column per person; week view is seven days across, Monday first. Both are drawn from
**one read of `GET /calendar`** plus two configuration reads — `/business/hours` for the grid's hours
and `/employees` for the columns — which do not change when the date does.

That is not a breach of the phase's "one request per view". The rule exists because *the view's
contents* must be one moment: appointments, closures and time off fetched separately would render a
booking made between the first call and the last as a block with no employee. Configuration read
once has no such seam, and re-reading the roster on every arrow press would be three requests to
move one day.

**Four decisions worth knowing before changing anything here.**

| | |
|---|---|
| **Only appointments widen the grid's window** | A closure and a time off are whole days by construction — both are entered as *dates* — so letting them widen it would stretch every closed day to twenty-four hours of hatching and squeeze the hours anything happens in into a sliver. They are clipped instead, which costs nothing: a region carries its meaning in its label, not its height. An appointment outside opening hours **does** widen it, because a booking off-screen is the one thing a calendar may not do |
| **Clicking empty space carries no minute** | It carries the day, and in day view the person. Which times exist depends on the service's duration, its buffers, the employee's working hours and what is already booked — all of which the availability engine answers and none of which a grid knows. `10:07` would either invent a slot the engine refuses or silently round to one nobody clicked. The minute was computed and plumbed through in a first draft; it was deleted, because a parameter nothing can consume is worse than no parameter |
| **Time off is packed *beside* appointments in week view, behind them in day view** | A week column holds the whole team, so three people away on Thursday would be three hatched regions drawn exactly on top of each other, and the view would report one absence where there are three. A day column belongs to one person, so there is nothing to collide with |
| **Blocks under 45 minutes are one line** | A block is as tall as the appointment is long — the whole point — so a short one has nowhere for three lines and was rendering the second sliced in half by its own bottom edge. Half a name reads as a rendering fault rather than as a short appointment |

**The geometry lives in two files and nowhere else.** `geometry.ts` decides where something sits in
a day (pure, no React, no fetching); `time-grid.tsx` is the only file that turns a minute into a
pixel. That is what makes "a 150-minute colour looks three times a 50-minute one" a property of two
functions rather than something four components have to agree about — and it is why the day and week
views could not drift apart on it.

### 2.2 `/analytics`

Four presets and a custom range over the one summary endpoint. Three of the server's deliberate
choices are carried into the UI rather than flattened by it:

- a **null rate renders `—`** and "No appointments to measure", never `0%`;
- **revenue says what it counts and in which currency**, because a business that switched currency
  has older revenue this figure does not report ([backend] §3.5, still open);
- the **period counts are not bounded by the range**, so they live under a *Right now* heading well
  away from the dates — "Today: 8" must not read as part of a report about 2020.

### 2.3 The dashboard home

Quick stats, today's agenda, and the setup checklist standing down once it is finished.

**"Today" and "next up" are one read a week wide, not two.** They are the same list cut at now, and
asking twice would let the second answer come from a moment the first does not know about. The week
of lookahead is what lets it say *nothing else this week* rather than *nothing else today* — a quiet
afternoon and a quiet business are different news. Next up is chosen by an appointment's **end**, so
the one happening right now is the answer rather than one that has already scrolled past.

Each panel has its own gate: a summary query that times out should cost the owner that panel, not
the screen.

---

## 3. Two defects, both found by the sweep rather than by the feature

### 3.1 The suite was red on Fridays, and had been since phase 07

The first full run on the previous session's tree failed:
`NotificationEnqueueTest > an appointment less than 24 hours away gets a confirmation and no reminder`.

Its fixture searched the calendar forward an hour at a time for a bookable start inside twenty-four
hours. **That cannot reach Monday from a Friday afternoon.** The fixture's business opens 09:00–17:00
Monday to Friday, so from 15:00 on a Friday there is no bookable start in the next twenty-four hours
at all, and the fixture threw. Red every Friday after 15:00 and all weekend — and today was a Friday.

The previous fix to this same test, recorded as a one-in-sixteen flake, **fixed one axis short**: it
survived any hour of the day, and then met the week. The lesson is not "test the fixture" but
*a fix for a time-dependent flake has to name which clock it is fixing* — hour-of-day and
day-of-week are two clocks, and the first fix only knew about one.

The fix moves the hours to the appointment rather than the appointment to the hours: pick a time two
to three hours out and open the business and the employee across it, stepping to the next morning
when the appointment would otherwise cross midnight, which an opening interval cannot express.

**827 tests, 0 failures, counted from the XML.**

### 3.2 An invisible label was widening the page

The 360px sweep measured `scrollWidth` against `clientWidth` on eighteen routes. `/services` came
back **466 against 360** — the whole page scrolled sideways on a phone.

The cause was the `sr-only` "Actions" heading over the column of buttons. `sr-only` is
`position: absolute`, and an absolutely positioned element is clipped by an ancestor's `overflow`
only if that ancestor is **positioned**. The table's scroll container was not, so a one-pixel span
nobody can see took its position from the table's full 554px width and escaped the container
entirely. One word — `relative` on the shared `Table` — fixes every table at once.

**T26: a screenshot of the top of a page cannot show a horizontal scrollbar at the bottom of it.**
This was measured, not looked at, and that is the only reason it was found. Every screenshot of
`/services` at 360px looked correct.

---

## 4. The sweep, and exactly what each tick rests on

The phase's polish pass is seven standards across twenty screens. What was actually done:

| Standard | How it was verified | Strength |
|---|---|---|
| **Usable at 360 px** | `scrollWidth === clientWidth` on **eighteen routes**, driven in a real browser | **Measured.** Found §3.2 |
| **Business timezone everywhere** | every call site read: 69 of them, in 30 files. **Not one passes anything but a business zone** — an envelope's `timezone`, a `timezone` prop, or `session.business.timezone`. The nine `toBusinessDate(new Date(), …)` calls were checked individually | **Measured**, and the rule is *enforced*: ESLint forbids `Intl.DateTimeFormat` and the `toLocale*` family outside `lib/time`, so a violation is a build failure rather than a review miss |
| **Times in the business zone, browser elsewhere** | the verification tenant is **UTC** and the browser is **`Asia/Tbilisi` (+04:00)**. A 09:00Z appointment draws at **09:00**, not 13:00 | **Measured against its counterfactual** — a browser-converted grid answers differently |
| Empty state on every list | inventory across every route directory | **Inventory.** Not each list driven to empty |
| Loading and error on every async region | `ResourceGate` is the single implementation, and every screen's read goes through it | **Inventory**, but the population is one component |
| Confirmation on destructive actions | inventory: every destructive call site has a `ConfirmDialog` | **Inventory** |
| Keyboard-navigable, labelled forms | **not swept**, see §5 |

**The distinction is the point.** Three of these were measured and four were counted. A future
session that needs certainty about the counted four has a smaller job than it looks — the
implementations are shared — but it does not have it from this session.

---

## 5. What is NOT done

| | |
|---|---|
| **Indexes at 10 000 appointments (G12)** | the one unticked box in the phase. The analytics and calendar queries are aggregate SQL over `business_id` + `starts_at`; whether the existing indexes serve them **has not been measured**, and the phase document says a miss is fixed with an index in this phase |
| **`pnpm build` locally (G9)** | not run, deliberately: `next dev` is live on 9082 and a build clobbers its `.next`. CI runs it |
| **The keyboard sweep** | the one polish standard with no evidence either way. The calendar's empty-space affordance is a real `<button>` with an `aria-label` and the blocks are buttons, so the new screens are navigable by construction — but no screen was driven by keyboard this session |
| **The conversations transcript's empty state** | it has none, and it may not need one — a conversation always has messages. Unchecked |
| **`/analytics` on a business that changed currency** | pinned by a server test, never seen on a screen. The screen says what it counts, which is the honest half; whether the remainder should be reported is [backend] §3.5, **still a decision for the principal** |

---

## 6. The verification tenant

`Phase 06 Scratch` — UTC, USD, owner `scratch@example.com`, one employee `Dana Scratch`. **Its data
is not pristine and this session added to it**, deliberately, because several of the phase's claims
have no meaning without data that exercises them:

| Added | Why |
|---|---|
| services **Quick trim** (30 min, 20.00) and **Colour and cut** (150 min, 180.00), both assigned to Dana | the phase's duration requirement is unverifiable with one 60-minute service. 30 / 60 / 150 in one column is what makes "five times as tall" a thing you can see |
| appointments on **2026-09-17** at 09:00 (30 min) and 10:00 (150 min) | the same |
| **time off** for Dana on 2026-09-15, **closure** on 2026-09-16 | the regions had never been rendered |

The AI badge needed nothing: the tenant already carries AI-booked appointments from phase 09, and one
of them renders the badge in the week view.

**An agent still cannot sign in to this tenant** — entering a password is not something an agent
does — so the session that uses it next needs the owner signed in first, in the browser the session
drives. It was already signed in this time.

---

## 7. Every open item

### 7.1 Issues

**[#17]** — open, unfixed, **deliberately parked** by the principal after three rejected candidates,
two of them harmful. **[#15]** — open, unchanged. Neither was touched.

### 7.2 Gaps

Carried: **G1** (no inline tool activity), **G3** (phase-09 DoD box stays unticked while #17 is
open), **G8** ("Any available" never rendered), **G10** (the weekday arm still has one phrasing),
**G11** (`requested_date` never recorded), **G12** (indexes unverified at scale).

**G9 is narrowed, not closed**: `pnpm build` has still never run on this machine, but lint,
typecheck and format have, and CI runs the build.

New: **G13** — the keyboard-navigation standard is the one polish item with no evidence either way
(§5).

### 7.3 Traps

Carried T1–T25. New:

**T26 — a screenshot cannot show you a scrollbar.** §3.2 was found by measuring `scrollWidth`
against `clientWidth`, and every screenshot of the broken page looked right.

**T27 — a fix for a time-dependent flake has to name which clock it is fixing.** §3.1's first fix
survived every hour of the day and was defeated by the day of the week.

### 7.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged.** No model was called this session at all.

The calendar endpoint remains the first read that deliberately narrows what it returns for privacy
rather than for size, and the client now depends on that: `lib/calendar/types.ts` says a calendar
appointment carries the customer's name and nothing else, and the drawer fetches the full row only
when one is actually opened. If a later change reuses the detail record there, the server test that
asserts the fixture's phone appears nowhere in a week is the thing that will fail, and it should be
believed rather than relaxed.

### 7.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; nothing deletes an `ai_message`; rate-limit buckets are in memory;
`make seed` is a placeholder; **no frontend test runner** — which is why §4's three measured rows
were driven in a browser rather than asserted, and why `geometry.ts` was written as pure functions
that a runner could take tomorrow.

---

## 8. Next steps, in order

### P0

1. **Push and open the pull request.** Seven commits, two phases' worth of work, and CI has seen
   none of it — including the backend half, which was written by a session that never committed it.
2. **Decide [backend] §3.5**, the revenue currency. One question; the answer changes a response
   shape.

### P1

3. **Verify the indexes at 10 000 appointments** (G12) — the last box in phase 10.
4. **Return `service_id` from `lookup_appointment`** — still unfiled, still one field, still a
   wasted round trip on every reschedule conversation.
5. **A frontend test runner**, which would turn §4's four inventory rows into assertions and give
   `geometry.ts` the unit tests it was shaped for.

### P2

6. **[#17]** · **[#15]** · `ai_message` retention · phase 11.

---

## 9. Commands

```bash
# The whole backend suite, ~5.5 minutes, from backend/.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# Count it from the XML rather than the console, which does not distinguish a stale result file.
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(t, f, e)"

# The frontend gates, from frontend/. NOT `pnpm build` while `next dev` is running.
pnpm lint && pnpm typecheck && pnpm format:check
```

**Do not run `./gradlew` twice at once against this tree**, and **do not run `pnpm build` while the
dev server is up** — it clobbers the running server's `.next` and every route starts serving 500.

---

## 10. Confidence

**High — the three measured rows in §4.** Each was driven in a real browser against a live tenant,
and the timezone one had a way to fail: the browser and the business are four hours apart, so a
grid that converted would have been visibly wrong rather than plausibly right.

**High — §3.1.** The failure was reproduced, the mechanism read off the fixture's own opening hours,
and the fix re-run against the full suite.

**Medium — the four inventory rows in §4.** The implementations are shared, so the population is
small; but "every list has an empty state" was counted, not exercised.

**None — performance.** G12 is open and nothing here has been run against more than a few dozen
rows. The queries are shaped to be indexable; that is not the same as knowing they are indexed.

**None — the keyboard standard.** G13. Nothing was driven by keyboard.
