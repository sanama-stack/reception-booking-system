# Session handoff — 2026-09-07 — Phase 03 (Business Setup), frontend half

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done;
> §5 is the part that will save you the most time; §6 is the part that will stop you assuming
> coverage that is not there. **Phase 03 is now complete** — this session finished the half the
> previous one deliberately left.

---

## 1. Where the project stands

**Phase 03 is complete.** The five settings screens and the dashboard onboarding checklist are
built, and every one of them was exercised in a browser against the real backend, signed in as the
project owner. Every checklist box in `docs/phases/phase-03-business-setup.md` is now ticked.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — the tested branch |
| Working branch | **`dev`** — **13 commits ahead of `main`**, and **still not pushed** |
| Backend tests | 234, untouched — this session changed no backend code |
| Frontend | Type-checks, lints, formats; `next build` passes with **all eleven routes static** |
| CI | **Still has not seen any of phase 03.** Unchanged and still the highest-value next action |

### The commits

```text
<this session>  Phase 03 — the settings screens and the onboarding checklist
```

**Nothing is pushed.** This was true at the end of the previous session and is still true. `dev` now
carries the whole of phase 03, backend and frontend, and CI has seen none of it.

---

## 2. Running it

Unchanged. `make up`, then the backend from the IDE and `pnpm dev` from a terminal. Open
**http://localhost:9080**, never 9082.

**Do not run `pnpm build` in the working tree while `pnpm dev` is running** — it leaves the dev
server serving 404s for every chunk and does not recover (phase 02 handoff §6.3). The copy-to-tmp
recipe in the session-aware-pages handoff §6.1 is what this session used, and it still works. Check
the route table afterwards: **every route must show `○`**. A route that has silently gone dynamic is
a `useSearchParams` that has escaped its Suspense boundary, and it is a much weaker signal than a
build failure.

---

## 3. What exists now

**New UI primitives** (`components/ui/`) — `Select`, `Textarea`, `ConfirmDialog`, `ResourceGate`.

`ConfirmDialog` is built on the native `<dialog>` and `showModal()`, which supplies the focus trap,
the inert background, Escape and top-layer stacking. Escape and a backdrop click both route to
`onCancel`, so dismissing never counts as confirming — and the `onClose` handler is what keeps
React's `open` in step with a dialog the browser closed on its own.

`ResourceGate` holds the loading and error states, which are the same on every screen. **Empty is
deliberately not in it**: an empty closure list and an empty FAQ list say different things and offer
different next steps, so each screen owns its own.

**New lib code**
- `lib/api/use-resource.ts` — `useResource<T>(path)`. It takes a **path, not a fetcher**: a fetcher
  is a new identity every render, and the effect depending on it would re-fetch forever unless every
  caller remembered `useCallback`. `loading` is true only for the first load, so a reload keeps
  content on screen instead of flashing a spinner.
- `lib/business/` — `types.ts` (the wire shapes), `api.ts` (`businessApi`), `days.ts`,
  `registries.ts` (IANA zones and ISO-4217 from `Intl.supportedValuesOf`), `patch.ts`
  (`changedFields`).
- `lib/time/formatIsoDate` — see §5.3.

**Screens**
- `app/(dashboard)/settings/` — `layout.tsx` with `SettingsNav`, a `page.tsx` that redirects to
  `/settings/profile`, and `profile/`, `hours/`, `closures/`, `booking/`, `faqs/`.
- `app/(dashboard)/dashboard/onboarding-checklist.tsx`, and a rewritten dashboard home.
- `DashboardShell` — Settings is now `available: true`, and the sidebar highlights by **first path
  segment** rather than exact path, or four of the five settings pages would leave nothing selected.

---

## 4. The decisions that will shape phase 04 onward

### `changedFields`, not "send the whole form"

Every settings form diffs against the profile it loaded and sends only what moved. `null` on the
profile and `''` in the form are treated as the same state, so an untouched optional field never
appears in the patch — while an owner who *empties* a filled field does produce `''`, and blank
clears.

Two things depend on this rather than merely benefiting from it: an unchanged slug would otherwise
be re-checked for uniqueness on every save, and an unchanged timezone would ask for confirmation
every time.

**What this means for you:** phase 04's service and employee forms want the same helper. It lives in
`lib/business/patch.ts` and is not business-specific in anything but its parameter type.

### A save that touches the session must reload it

Four fields — name, slug, timezone, currency — are in both `/business` and the session hydrated from
`/auth/me`. `ProfileForm` calls `useSession().reload()` when any of them is in the patch. The
phase-03 backend handoff flagged this for the slug; it is true of all four, and the sidebar showing
a stale business name is the visible symptom.

### A day is closed by having no intervals — in the editor too

There is no closed flag in `HoursEditor`'s state, exactly as there is none on the wire. "Mark
closed" empties the day's list; "Open this day" adds one back. The rendering follows from the same
fact, so the UI cannot show a day as open that the payload would send as closed.

### The client validates one rule, and it is the one the server cannot report

Overlap, ordering and day ranges are the server's, stated once in `BusinessHoursService.validateWeek`
and reported per row. The editor checks only for an **empty time**, because `""` is not a
`LocalTime`: the body would fail to parse and the owner would get a message about JSON instead of
about a field.

**What this means for you:** resist adding the overlap rule client-side for a faster message. It is
a second copy of a rule that will drift, and the server's messages are already written for a person.

### `aiAdditionalInfo` shipped; `aiEnabled` and `aiDailyCostCapCents` did not

The free-text notes are Receptionist *knowledge*, which the phase goal names, so they sit on the
FAQs screen. The other two decide whether the Receptionist runs and what it may cost per day —
operational, and they belong with the Receptionist screens in phase 09. **They are patchable today
and have no UI.** That is a deliberate gap, not an oversight.

---

## 5. Traps already paid for

### 5.1 The server names an hours failure by its position in the submitted array

`hours[2].opensAt` is not a position in the editor. A business closed on Monday and Tuesday submits
Wednesday as index 0, so the index cannot be read as a day. `HoursEditor` keeps the
`day:interval → payload index` map it built at submit time and looks messages up through it.

Any edit clears both the map and the messages, deliberately: adding an interval shifts every index
after it, and a message left behind would then be sitting on the wrong row — which is worse than no
message, because it is confidently wrong.

Verified rather than assumed: two overlapping intervals on Tuesday produced a `422` whose message
landed on *Tuesday interval 2*, with `aria-invalid` on that input and nothing on Monday's.

### 5.2 A closure's `endsAt` is a day later than the owner's last closed day

It is the start of the following day — half-open, which is what the engine needs. Formatting it
would tell the owner they are closed a day longer than they said. `ClosuresScreen` renders
`startDate`/`endDate`, which is the reason the response carries both representations at all.

Confirmed against the database: 24–26 December in `Asia/Tbilisi` stored as
`2026-12-23T20:00:00Z` → `2026-12-26T20:00:00Z`, and rendered as "24 December 2026 – 26 December
2026".

### 5.3 `new Date('2026-12-24')` parses as UTC midnight

Which renders as the 23rd for any business west of Greenwich — the exact off-by-one-day `lib/time`
exists to prevent. `formatIsoDate` formats from the string's own parts and touches no `Date` at all.

It is also **the only helper in `lib/time` that takes no timezone**, and that is deliberate: an
`IsoDate` has already been resolved into the business's zone by whoever produced it, so asking for a
zone would invite a second conversion. The doc comment says so, because it otherwise looks like a
hole in the module's central rule.

### 5.4 Reordering FAQs by swapping `sortOrder` does not work

The values are guaranteed *ordered*, not contiguous or distinct — a create with an explicit position,
or a delete, leaves gaps and ties, and swapping two equal values does nothing at all. `move()`
renumbers the visible order and patches only the rows that actually change, which is correct from
any starting state and repairs the numbering as a side effect.

### 5.5 The Browser pane's screenshots went stale mid-session

Two consecutive screenshots showed a confirmation dialog still spinning after the request had
already returned `200` and the DOM had moved on. The state was read correctly by querying the DOM
directly. **When a screenshot disagrees with the network log, believe the network log** — and verify
through `read_page`, `get_page_text` or a DOM query rather than the picture. Roughly ten minutes went
into diagnosing a bug that did not exist.

### 5.6 Deleting a watched file can wedge the dev server, and it does not recover on its own

Removing an unused component while `pnpm dev` was running left the server answering **500 on every
app route**, including ones the change could not touch. The tell is in the response body: it is
Next's *pages-router* error shell — `chunks/fallback/pages/_error.js` — which means the app router
resolved nothing at all. `.next/app-build-manifest.json` had been reduced to `{"pages": {}}`.

Touching a page to force a recompile does **not** fix it. The remedy is the same one phase 02's
handoff §6.3 gives for the other way of corrupting `.next`:

```bash
# stop the dev server first
cd frontend && rm -rf .next && pnpm dev
```

**Diagnose it before assuming your change is at fault.** A clean `next build` from a copied tree
(§2) compiles the same source in seconds; if that passes and the dev server still 500s, the source
is fine and only the server's `.next` is stale. This session ran that build to separate the two, and
it is the cheapest way to tell "I broke the code" from "I broke the cache".

### 5.7 Everything from the earlier handoffs still applies

Particularly the two build traps in §2, and `noUncheckedIndexedAccess` being on, which is why
`HoursEditor` models the week as an array it maps over rather than one it indexes into.

---

## 6. What is not covered by a test

Stated so it is not mistaken for coverage that exists.

- **There is still no frontend test runner**, by design
  (`08-testing-strategy.md` §11). Everything below was verified by hand, in a browser, against the
  real backend — which is better than nothing and is not a regression test.
- **Nothing in this session is automated.** The five screens, the checklist, `changedFields`,
  `formatIsoDate` and the hours index-mapping all have zero automated coverage. **The index-mapping
  in §5.1 and `formatIsoDate` are the two that most deserve it** — both are pure, so they need no
  framework beyond a runner existing, and both are the kind of logic that breaks silently.
- **`safeNextPath` still has no test file.** Carried over from the session-aware-pages handoff §7 and
  unchanged.
- **The `SLUG_TAKEN` path was not exercised.** The code puts the message on the slug field rather
  than in the banner, but proving it needs a second business to collide with, and this session was
  working in the owner's own account. Worth one line in the phase-11 E2E.
- **The 51-FAQ ceiling was not exercised** from the UI for the same reason — it needs fifty
  questions. The server's message is rendered on the `question` field if it arrives.
- **`availableTimezones()` returning empty was not exercised.** The fallback is a plain text field
  and the server still validates, but no browser in this session lacked `Intl.supportedValuesOf`.

### What *was* verified in a browser

Signed in as the owner, against the real backend, with the database checked before and after:
the checklist rendering 1-of-5 with the four phase-04 steps shown honestly; the timezone
confirmation dialog and its full save path, including the session reload; the hours editor rendering
Saturday and Sunday as Closed; an overlap landing on the correct row; a closure round-tripping
through the inclusive/half-open translation; a booking-settings range violation landing on its field
with nothing else saved; FAQ create, inline edit, reorder and delete; and `aiAdditionalInfo` being
cleared by an emptied textarea.

**All test data was removed and every changed value restored.** The database was confirmed byte-for-
byte back to its starting state — UTC, USD, 15/60, five hours rows, no closures, no FAQs, no notes.

---

## 7. Open items

Everything in §8 of the phase-03 backend handoff still stands. Added or changed:

- **`dev` is 13 commits ahead of `main` and unpushed.** Unchanged from the last two handoffs and
  still the highest-value next action. CI has seen neither half of phase 03.
- **`aiEnabled` and `aiDailyCostCapCents` have no UI.** Deliberate — see §4. Phase 09 owes them.
- **The `EmptyCatalogReadiness` / `EmptyAppointmentImpact` deletions are still the first thing phases
  04 and 06 should do.** The dashboard checklist now *displays* those three flags, so phase 04
  turning them true is visible immediately with no frontend change — which is the payoff the backend
  session bought by publishing the final shape up front.
- **`CLAUDE.md` and `docs/agents/` are still untracked**, for the third session running. Still the
  owner's call.
- **Branch protection is still not enabled.** Offered three times now.
- **The dev server was left wedged** — see §5.6. `rm -rf frontend/.next` and restart it. The
  committed source is healthy; a clean production build of it passes with all eleven routes
  static. The process belongs to the developer, so this session did not restart it.
- **The owner's timezone is `UTC`.** It was changed to `Asia/Tbilisi` during verification and changed
  back, because restoring what was there is not the same as guessing what was meant. If the business
  is in Tbilisi, that is a real setting worth making deliberately — the dialog now exists to make it.

---

## 8. Next: Phase 04 — Services and Employees

Read `docs/phases/phase-04-services-and-employees.md` in full. What this session leaves you:

1. **Delete `EmptyCatalogReadiness` before writing the real one.** Not "change its return value" —
   delete. Two candidate beans is the failure you want; a stub left as a fallback fails silently by
   going on answering "no" after the catalog exists.
2. **The dashboard checklist needs no change.** Three of its five steps read `false` from a port that
   phase 04 implements. Flip the port and the checklist follows. What phase 04 *does* owe it is the
   two `href: null` entries in `STEPS` — point them at `/services` and `/employees` once those exist,
   and drop the `arrives` copy.
3. **`DashboardShell` marks Services and Employees `available: false`.** Flip them, the same way
   Settings was flipped this session.
4. **The form patterns are established.** `useResource` + `ResourceGate` + `changedFields` + a keyed
   remount on save is what all five screens do; phase 04's forms should look the same rather than
   inventing a sixth shape.
