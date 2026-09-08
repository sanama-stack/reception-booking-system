# Session handoff — 2026-09-08 — Phase 04 (Services and Employees), frontend half

> **Purpose.** Enough context to continue without re-reading this session. §1 says what is done;
> §5 is the part that will save you the most time; §6 is the part that will stop you assuming
> coverage that is not there; **§7 opens a defect that is older than this session and matters to
> phase 05.** Phase 04 is now complete.

---

## 1. Where the project stands

**Phase 04 is complete.** The six new screens are built and every one of them was exercised in a
browser against the real backend. Every checklist box in
`docs/phases/phase-04-services-and-employees.md` is now ticked, including the two Definition-of-Done
boxes the backend sitting left open.

| | |
|---|---|
| Repository | https://github.com/sanama-stack/reception-booking-system — **public** |
| Default branch | `main` — **protected as of this session**, see below |
| Working branch | **`dev`** — pushed |
| Backend tests | 416, untouched — this session changed no backend code |
| Frontend | Type-checks, lints, formats; `next build` passes with 17 routes |
| CI | Green on `dev` |

### Two open items that had been open since phase 01 are now closed

**Branch protection is enabled on `main`.** Offered four times across four handoffs and never
accepted; accepted this session. The rules:

| | |
|---|---|
| Required checks | `Backend`, `Frontend`, `Compose smoke test` |
| Strict | yes — the branch must be up to date with `main` before merging |
| Approving reviews | **0** — this is a solo repository, and requiring one would mean no pull request could ever merge |
| Force pushes, deletion | both blocked |
| Administrators | **exempt** — the owner's choice, taken so a rule cannot lock them out of their own repository |

The last row is the one to know about: `enforce_admins` is `false`, so the protection is a
guardrail rather than a guarantee. It stops an accident, not a decision.

**The pull request was opened and merged.** `main` had been behind since phase 02 and the merge had
been offered in four handoffs. The commit carrying this document was the last one on `dev` before
it went.

---

## 2. Running it

Unchanged. `make up`, then the backend from the IDE and `pnpm dev` from a terminal. Open
**http://localhost:9080**, never 9082.

**A phase that adds a migration needs the backend restarted before its screens can be tested**, and
that is not obvious from anything the frontend does. This session opened `/services` against a JVM
started before the phase 04 commit: every request 401'd or 404'd in ways that looked like frontend
bugs. The check that settles it in one command:

```bash
docker compose exec -T postgres psql -U reception -d reception \
  -c "select version, description, success from flyway_schema_history order by installed_rank;"
```

If the newest migration is not listed, the running backend is not the one you are working on. **Do
this before debugging a screen that cannot reach its endpoints.**

---

## 3. What exists now

**Six screens**, all under `app/(dashboard)/`:

| | |
|---|---|
| `services/page.tsx` + `services-screen.tsx` | the list: length, price *with its own currency*, how many people provide it, an active toggle |
| `services/new/`, `services/[id]/` | both render `services/service-form.tsx`; the detail adds the toggle and `delete-service.tsx` |
| `employees/page.tsx` + `employees-screen.tsx` | the list: job title, how many services, an active toggle |
| `employees/new/` | renders `employees/employee-form.tsx` |
| `employees/[id]/page.tsx` | the person, and three sections beneath them |

**Three shared controls**, in `components/`:

| | |
|---|---|
| `week-editor.tsx` | the seven-day editor. Opening hours **and** Working Schedules |
| `assignment-picker.tsx` | the multi-select. Both directions of `employee_services` |
| `active-toggle.tsx` | activate, and deactivate with its confirmation and its impact report |

**Two lib modules mirroring the backend packages** — `lib/catalog/` (`serviceApi`, the Service wire
shapes, `formatDuration`) and `lib/staff/` (`employeeApi`, the Employee, schedule and time-off
shapes).

**`changedFields` moved** out of `lib/business/patch.ts` into `lib/forms/changed-fields.ts` and is
now generic. The phase-03 handoff flagged it as "not business-specific in anything but its parameter
type"; four forms now use it. The two settings forms changed by one import line each.

**`ButtonLink`** in `components/ui/button.tsx`, sharing one `appearance()` with `Button`. "New
service" opens a page, so it is a real `<a>` — middle-clickable, and announced as a link.

**`--color-warning`** in `globals.css`, the design system's third tone.

---

## 4. The decisions that will shape phase 05 onward

### The seven-day editor is one component, not two

The phase-04 backend handoff described the schedule editor as "`HoursEditor` with three renamed
fields". It is now literally that: `components/week-editor.tsx` owns the closed-is-empty
representation, the submit-time index mapping and the stale-message flush, and each screen supplies
its words, its key shape (`hours[2].opensAt` against `schedule[2].startsAt`) and its write.

The alternative was a copy, which would have copied the index-mapping trap with it — and a trap
that exists in two files is one that will eventually be fixed in one.

**What this means for you:** phase 05 intersects these two lists. They are already the same shape in
the UI as they are on the wire, and a change to how a week is edited lands in both places at once.
The copy object is long on purpose; if you add a field to it, both screens must answer for it.

### The employee page stacks, where Settings tabs

Four concerns on one page rather than four sub-routes. Settings is five screens an owner visits one
at a time, months apart; this is four steps worked through in one sitting the first time someone is
added, three of which decide whether that person can be booked at all. Sub-navigation would hide
exactly the steps the onboarding checklist is pushing the owner towards.

Each section holds its own resource and saves independently, so one failing does not take the rest
of the page with it.

### The long-duration warning measures the duration alone

Not duration plus buffers. Buffers occupy the calendar too, but **whether padding may spill past
closing time is phase 05's decision and has not been taken** — warning on the total would assert an
answer this screen does not have.

It compares against the longest single *stretch* the business is open, never a day's total: two
intervals with a lunch break between them cannot hold an appointment spanning both, so summing them
would tell an owner something fits when it does not.

**What this means for you:** when phase 05 decides how buffers meet the edges of the day, this
warning is the thing to revisit, and `longestOpenStretch` in `services/service-form.tsx` is where
it lives.

### A create cannot carry its assignments

`POST /services` and then `PUT /services/{id}/employees`, because the second call needs an id the
first produces. If the second fails the service still exists — so the owner is told exactly that,
by name, and sent to the detail screen. Leaving them on a create form for something that has
already been created would be the worse of the two wrong answers.

### The list screens ask once and count, rather than naming

`GET /services` carries `employeeIds` and `GET /employees` carries `serviceIds`, so both lists show
"Nobody assigned" — the most common reason a fully configured service cannot be booked — with one
request and no join on this side. They show a *count*, not names: names in a table cell would need
the other list loaded to render a column nobody sorts by.

---

## 5. Traps already paid for

### 5.1 A stale backend looks exactly like a broken frontend

See §2. Ten minutes, and the symptom was `/services` 404ing under a screen that was correct.

### 5.2 `get_page_text` does not always surface a field's error message

Two server-reported validation messages were rendering correctly on their fields and did not appear
in the extracted page text at all. The DOM query found them immediately:

```js
[...document.querySelectorAll('p.text-danger, [role=alert]')].map(e => e.textContent)
```

Same lesson as §5.5 of the phase-03 frontend handoff, one tool along: **when a reading tool
disagrees with what the DOM says, believe the DOM.** Roughly ten minutes went into a bug that did
not exist.

### 5.3 `setTimeout` in the page never fires while the Browser pane is hidden

`await new Promise(r => setTimeout(r, 900))` inside an injected script times out at 45 seconds
rather than resolving — background tabs throttle timers. Two calls were lost to this. Write
synchronous scripts and let separate tool calls be the wait.

### 5.4 `read_page` returns an empty tree immediately after a navigation

Reported as `Viewport: 0x0`, which reads like a blank page and is not one. Batching a screenshot
before it in the same call fixes it every time.

### 5.5 A page with two dialogs needs `querySelector` to say which

`document.querySelector('dialog')` on the service detail page finds the deactivation dialog, not
the delete one. `[...document.querySelectorAll('dialog')].find(d => d.open)` is the reliable form.
Worth knowing for any future screen with more than one confirmation.

### 5.6 Everything from the earlier handoffs still applies

Particularly `pnpm build` against a tree running `pnpm dev` (phase 02 §6.3), the copy-to-tmp recipe
that works around it (session-aware-pages §6.1), and `noUncheckedIndexedAccess`.

**One phase-03 rule needs restating rather than repeating.** That handoff said every route in the
build output must show `○`, as the tell for a `useSearchParams` that has escaped its Suspense
boundary. Two routes are now `ƒ`, and correctly so: `/services/[id]` and `/employees/[id]` are
dynamic segments with no `generateStaticParams`. **The rule is that no route which was `○` becomes
`ƒ`**, not that none may be.

---

## 6. What is not covered by a test

- **There is still no frontend test runner**, by design (`08-testing-strategy.md` §11). Everything
  in this session was verified by hand.
- **Nothing in this session is automated.** The three shared components are the most valuable
  candidates if that ever changes: `week-editor`'s index mapping now serves two screens, so a
  regression in it breaks both.
- **`409 SERVICE_IN_USE` has never been rendered.** It cannot be until appointments exist. The
  delete screen shows the server's message verbatim — which is the part that matters — but nothing
  has produced one.
- **`affectedFutureAppointments` has only ever been `0`.** Phase 06 is the first time the
  deactivation dialog's promise can be tested against a non-zero number.
- **`safeNextPath` still has no test file.** Carried forward unchanged since the session-aware-pages
  handoff.
- **The assignment `404`** — submitting an id that resolves to nothing — was not exercised from the
  UI. It cannot be reached through the picker, which offers only ids it was given.

### What *was* verified in a browser

Against the real backend, in a throwaway second tenant, with the database checked before and after:
both empty states; a create refused with `422` putting "Use a multiple of 5 minutes" and "Use at
most two decimal places" on their own fields and nothing in the banner; the long-duration warning
naming 10 hr against an 8 hr Monday; `25` typed, stored and re-read as `25.00 USD`; the phone
`555 12 34 56` refused with the message that names Settings rather than the field, and
`+995 555 12 34 56` normalised to `+995555123456` and shown back; assignments saving from the
employee side; a schedule overlap landing on *Tuesday shift 2* with `aria-invalid` on that input and
nothing on Monday's; the same overlap on the hours screen landing on *Tuesday interval 2*, which is
the regression check the shared editor needed; time off round-tripping 24–26 December as inclusive
dates over a half-open `2026-12-27T00:00Z`; deactivation confirming, reporting and reversing; a
delete returning `204`; and the checklist reaching **"Everything is configured. Your booking page is
ready to take appointments."** and falling back to "3 of 5 done" when the only service was
deactivated.

**The verification tenant was deleted row by row afterwards**, and the owner's own business was
confirmed byte-for-byte unchanged: one business, one user, five business-hours rows, no services, no
employees.

---

## 7. A defect this session found, and fixed

**`time` columns were stored four hours off on this machine, and correctly in CI.**

`business_hours.opens_at` held `05:00:00` for a business that opens at 09:00, and
`employee_schedules.starts_at` did the same. The API read both back as `09:00`, so nothing in the
application noticed.

The cause was `spring.jpa.properties.hibernate.jdbc.time_zone: UTC` in `application.yml`. It makes
Hibernate bind every temporal value through a `Calendar` in the named zone — which is what a
`timestamp without time zone` column needs, and **this schema has none**. Every instant is
`timestamptz`, where Postgres carries the zone itself; the only other temporal columns are the four
`time` ones. A `LocalTime` has no zone to convert, so the Calendar shifted it by the JVM's offset
instead. This machine runs `Asia/Tbilisi`, UTC+4. The read applied the same shift in reverse, which
is why it round-tripped.

### What was measured, not assumed

A probe run against a fresh Testcontainers database, with and without the setting:

| | `time` column | `timestamptz` column |
|---|---|---|
| As configured | `05:00:00` for an API value of `09:00` | `2026-09-08T07:58:54.332789Z` — exact |
| Setting removed | `09:00:00` | `2026-09-08T07:59:33.902738Z` — exact |

Removing it fixes the wall-clock columns and does not disturb the instants, because there is no
plain `timestamp` column for it to have been protecting. The full suite is green without it.

### The fix, in two parts

**The setting is gone**, and a comment stands where it was saying why adding it back is a defect.
That is the part a future "best practices" pass would otherwise undo.

**The test JVM now runs in `Pacific/Kiritimati`** — UTC+14, the largest offset there is, and on
tomorrow's date for ten hours of every UTC day. This is the part that matters more than the fix.
A suite that runs in UTC cannot see a timezone bug, because in UTC every conversion to and from UTC
is the identity: 416 green tests said nothing about this for two phases, and would have said
nothing about the next one either. Kiritimati has no daylight saving, so the suite is hostile
without becoming a different environment in March and October.

All 416 tests pass under both changes. One `HealthEndpointTest` flake was seen once at UTC+14 and
passed on re-run; it is SMTP container readiness, not zone.

### What was deliberately *not* done

**No Flyway migration corrects the existing rows.** There cannot be a correct one: the shift depends
on the offset of the machine that wrote each row, so a migration that repairs this database would
corrupt one written in UTC. The affected data is five rows in one developer's database — CI's is
ephemeral and there is no production — which is why this was worth fixing now rather than later.

**The five local rows were left as they are.** Until they are corrected, the owner's own business
reads 05:00–13:00 in the UI. One scoped `UPDATE` fixes it, or re-entering the week under
`/settings/hours` does.

## 8. Open items

Everything in §7 of the phase-04 backend handoff still stands unless listed below. Changed:

- **`main` is current and protected.** Both of the items that had been open since phase 01 are
  closed. See §1 for what the protection does and does not enforce.
- **`dev` and `main` have the same content and different histories.** The merge was a squash, which
  is what four handoffs prescribed, so `git log main..dev` still lists every commit that went into
  it. That is cosmetic; if it is annoying, `git checkout dev && git reset --hard origin/main` after
  the merge makes the two identical, and nothing depends on it either way.
- **The `time`-column shift is fixed, §7 — but the five rows written before the fix are not.**
  The owner's opening hours read 05:00–13:00 until they are corrected by hand. Nothing else in the
  database is affected: every other temporal column is `timestamptz`.
- **The backend must be restarted for the fix to take effect.** It was running with the old
  configuration when this session ended.
- **`EmptyAppointmentImpact` is still the last stub standing.** Phase 06 deletes it. Do not change
  its return values.
- **`aiEnabled` and `aiDailyCostCapCents` still have no UI.** Phase 09 owes them.
- **A currency change still leaves existing services on the old code.** Deliberate. The services
  list now renders the currency per row, which is what makes that visible rather than silent.
- **The dashboard checklist's `Step.arrives` is gone**, along with the `href: string | null`. Every
  step now points somewhere real.
- **Node 20 deprecation warnings in CI** — unchanged, warnings only.

---

## 9. Next: Phase 05 — Availability

Read `docs/phases/phase-05-availability.md` in full, and §4 of the phase-04 backend handoff before
it — the engine's inputs are all decided there. Three things this session leaves you:

1. **§7 is settled**, so the engine can read a `time` column in SQL and get the wall-clock the
   owner typed. Correct the five stale rows before trusting anything computed from them.
2. **`hasBookableService` is the flag to trust.** An active service, with an active assigned
   employee, who has a schedule. If the engine ever disagrees with it, one of the two is wrong —
   and the dashboard now shows that flag to the owner on every visit, so a disagreement is visible.
3. **Working Schedules are stored wider than opening hours on purpose**, and the intersection is
   the engine's job. `EmployeeScheduleEndpointTest` asserts both halves of that; do not
   "fix" the storage to match the hours.
