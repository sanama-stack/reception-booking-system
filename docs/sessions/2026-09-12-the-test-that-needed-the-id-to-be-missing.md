# Session handoff — 2026-09-12 — the test that needed the id to be missing

> **Purpose.** Two items closed and a pull request merged. §2 is the merge; §3 is the four empty
> states, and **the defect it found in the suite itself** — a test that passed *because* an id was
> missing, and would have gone on passing had the page stopped reading its route; §4 is G25, and
> the distinction the session turned on: **a verified fixture is not a verified assertion**.
>
> **Built no product code.** `backend/src` and the non-test half of `frontend/src` end
> **byte-for-byte** as they started — `git diff d420568..HEAD -- backend/src` is empty, and so is
> the same diff over `frontend/src` with `*.test.ts*` and `src/test/` excluded. Every file this
> session touched is a test, a fixture, a configuration file or a document.
>
> **`main` moved for the first time in three sessions.** Pull request
> [#31](https://github.com/sanama-stack/reception-booking-system/pull/31), ten commits, merged as
> `d420568` — and **this is the first merge the `End-to-end` gate actually exercised**, which
> [the previous handoff][previous] §7.1 flagged as untested.
>
> **Nothing is outstanding.** The tree is clean, `dev` is three ahead of `main`, and the claims
> below are read out of run `34704699908`'s job logs rather than off the jobs' colours.

[previous]: ./2026-09-12-the-pixel-that-jsdom-could-not-see.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`d420568`** — PR #31 merged this session, ten commits. Was `a71d17a` |
| `origin/dev` = `dev` | **three ahead of `main`**, nothing unpushed, tree clean |
| CI | **green on both runs carrying code**: `34703554370` (the PR run) and **`34704699908`** (the one every claim below is read from). `34705042652` and this handoff's own run are documentation only and were **still in flight** when this was written |
| Frontend | **22 files, 76 tests** — was 18 and 68 |
| The 360 px sweep | **26 routes, every one `360/360`** — was 21. 5.4s, was 3.9s |
| Backend | **not re-run by anything here.** No Java changed; the job is green in both runs |
| Migrations | **none.** New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit check was made |
| Phase 11 | **41 boxes ticked, 31 open.** Two ticked here, one of them added and ticked in the same session |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 2. The merge

Ten commits, four green jobs, `main` ten behind — [the previous handoff][previous] left this as a
judgement rather than a task, and the judgement was to merge.

**The `End-to-end` gate ran on the pull request and passed** (`34703554370`, 3m43s). That is the
part worth recording: the previous merge *satisfied* the gate without exercising it, so until this
one nothing had established that a red E2E could actually block a merge. It can.

`mergeStateStatus` read `BEHIND` throughout, which is `main`'s own merge commit not being in `dev`
and is the normal shape of this repo's merge-commit workflow. `delete_branch_on_merge` is `false`
and `dev` is long-lived, so the merge was made **without** `--delete-branch`.

One thing to know for next time: the app's cached PR status reported `7 passing, 1 pending` while
`gh pr checks` reported all eight green. The cache was stale, not the checks.

---

## 3. The four empty states

`UNASSERTED_EMPTY_STATES` is **zero**. The four were carried because each needed a fixture the suite
did not have; the fixtures are written and shared in `src/test/fixtures.ts`, since all four wanted
the same service and the same person.

They are asserted as **two different kinds of thing**, because that is what they are:

- **Dead ends** — `"This service no longer exists"` on the move panel, `"No services to book"` on
  dashboard booking. Availability is computed from a service's length, so with no service there is
  nothing to offer, and the copy has to carry the next step instead.
- **Open questions** — `"Pick a date"` on three screens, `"Nothing to preview yet"` on the
  availability preview. One field away from working.

Each case also asserts **what is not offered**: no date field under *"this service no longer
exists"*, no booking form under *"no services to book"*, no Recalculate button under *"nothing to
preview yet"*. A screen rendering the message and the controls at once is red.

The `"Pick a date"` states are **reached rather than rendered** — the field is cleared with a real
change event, because every one of these screens opens on a date already filled in. That is also the
only way an owner reaches them.

### 3.1 The finding: a test that needed the id to be missing

The suite's `next/navigation` stub answered `useParams()` with **`{}`** for every screen. No test had
rendered a `[id]` page before, so nothing had noticed.

The appointment detail page reads its own id and asks for `appointmentPath(id)`. With `{}` that is
**`/appointments/undefined`** — and the harness matches catalogued bodies by **prefix**, so
`/appointments` answered it. Every assertion would have passed. The test would have passed *because
the id was absent*, and gone on passing had the page stopped reading its route altogether.

`useParams` now answers from `src/test/navigation.ts`, cleared after every test like the document is.
The first case asserts the **request path** — not just that the screen rendered — so the mechanism is
load-bearing rather than decorative.

**Shown able to fail.** The old `() => ({})` stub was planted back, and the assertion reported the
failure in its own words:

```
AssertionError: expected [ '/api/appointments/undefined', …(3) ]
  to include '/api/appointments/appointment-1'
```

This is **T49's family again**, from a third side. T49 was a locator that matched the wrong thing;
the mark-completed step was an assertion satisfied by the control that triggered it; this is a
*fixture server* loose enough to answer a request the code should never have made. All three are the
same failure: the test passed for a reason that had nothing to do with the behaviour.

### 3.2 The gate was shown able to fail too

`coverage.test.ts` requires every literal `EmptyState` title in a source file to appear in the test
file that claims it. A title was reworded in the test and not in the screen, and the gate named the
file it came from:

```
“Pick a date” is rendered by app/(dashboard)/employees/[id]/availability-section.tsx
and asserted by none of .../availability-section.test.tsx
```

**`UNASSERTED_EMPTY_STATES` stays at zero rather than being deleted** with the last row it counted.
The next screen to ship an unasserted empty state should meet the same gate, not a constant somebody
removed because it had briefly stopped mattering.

---

## 4. G25 — the five id-taking routes

**26 routes, every one `360/360`** in run `34704699908`, read off the per-route table in the *The
flow* step's log. `/appointments/{id}`, `/customers/{id}`, `/services/{id}`, `/employees/{id}` and
`/conversations/{id}` are in the sweep.

They were outside it because each needs a row the freshly-registered tenant does not have — and they
are the ones most likely to be too wide, because they are the ones that render history tables, a
week of schedule, and pretty-printed JSON.

**The conversation is the one that mattered.** Its transcript draws each tool call's arguments and
results as `JSON.stringify(value, null, 2)` inside a `<pre>` — the widest content this application
draws anywhere — and it holds, inside its own `overflow-auto` container. An empty conversation would
have given the sweep nothing to measure, which is why the fixture has to be *talked* into existing
rather than written.

**The count assertion earned its keep.** The sweep went from 3.9s to **5.4s**. A version that
silently skipped the five new routes would have stayed at 3.9s and still gone green — which is
exactly **T59**, one session after it was written down.

### 4.1 Through the API, not through five forms

`seedDetailRows` creates the rows with `page.request`, which carries the session cookies registration
left behind. Driving five forms would spend most of the run re-proving what `flow.spec.ts` already
proves, and would fail for *form* reasons inside a spec whose question is *layout*.

Everything it assumes is checked against `BusinessDefaults` rather than guessed: the business
timezone is `UTC` (so a UTC `from` date is the right one), opening hours are 09:00–17:00 **Mon–Fri**
(so the seven-day employee schedule intersects to five, and a fortnight always contains some),
`MAX_ADVANCE_DAYS` is 60 (so a 14-day window is inside the horizon), and `AI_ENABLED` defaults true
(so the chat needs no toggle). The live run confirmed it: the first two days came back with **no
slots** — 12 and 13 September are a Saturday and a Sunday — and the first slot was Monday the 14th.

### 4.2 What the live stack established, and what it did not

**This is the reusable part of the session.** A browser cannot run here, so before pushing, every
request in the helper was run against a live E2E stack with `curl`: registration, employee, schedule,
service, assignment, availability, booking, chat session, chat turn, and **all five detail endpoints
answering `200`**. The transcript was confirmed to hold **eight messages including `TOOL` rows
carrying `toolArguments` and `toolResult`**.

That established the **requests**. It established **nothing about the measurement**, because no
browser ran — and the sweep's whole content is a measurement. So the G25 row was written into the
phase document as **open**, with the twenty-one-route numbers left standing as the last a run had
actually produced, and was ticked only once CI printed twenty-six.

**A verified fixture is not a verified assertion.** The temptation to tick on the strength of ten
green `curl`s was real and would have been wrong.

---

## 5. Three traps

**T60 — a route-parameter stub that answers `{}` makes a `[id]` page request `/undefined`, and a
prefix-matching fixture server answers it.** The test then passes *because* the id is missing. Any
test double loose enough to answer a request the code should never have made will hide the code not
making it. Assert the request, not only the render. §3.1 is the whole argument.

**T61 — verifying a fixture is not verifying the assertion built on it.** Ten `curl`s proving five
endpoints answer `200` says nothing about whether a browser measured those pages, and a sweep's
entire content is the measurement. When the check cannot run locally, write the row down as open and
let the run tick it. §4.2.

**T62 — Docker builds fail here on downloads, and the first error names the wrong cause.** BuildKit's
`load metadata` times out (`DeadlineExceeded`) against Docker Hub while a plain `docker pull` of the
same tag succeeds — pull the base images first, then build. The Gradle *distribution* download
inside the build container (`gradle-8.14-bin.zip`, 10s read timeout) does not have that workaround
and fails outright, so **`make up-e2e` cannot build the backend image on this machine.**

---

## 6. What phase 11 still wants

**Two boxes ticked**, both against run `34704699908` and both read out of the logs: *empty / loading
/ error state tests per screen*, and *G25 — the five id-taking routes inside the sweep*, which was
added to the checklist and ticked in the same session because it did not exist as a row before.

Still open and untouched here: rate limits for every public endpoint, the log-redaction test, the
`prod` default-secret refusal, the full-history secret scan, the error-response leakage review,
`ai_message` retention, the revenue remainder from ADR-0010, all four observability items,
`.env.example`'s audit, and `docs/deployment.md`.

---

## 7. Every open item

### 7.1 Issues

**[#17]** and **[#15]** — open, untouched. **No model was called this session.** The chat turn in
§4.2 went to the fake provider (ADR-0011), which is not a model and costs nothing; no credit check
was made.

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**.

**Closed: G25** — the five id-taking routes, §4.

**No new gaps.** One thing is worth watching rather than filing: the sweep now depends on the AI
stack, because the conversation fixture has to be talked into existing. A fake provider that stopped
answering would take the `mobile` project red for a reason that has nothing to do with layout. It is
the same topology `flow.spec.ts` already depends on, which is why this is a note and not a gap.

### 7.3 Traps

Carried T1–T59. New: **T60**, **T61**, **T62** (§5).

### 7.4 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory;
no `docs/deployment.md`. **No longer carried: the four unasserted empty states.**

### 7.5 One environment note

An E2E stack was **already running** on ports 9180–9185 when this session started — left by an
earlier one, five hours up — and is what made §4.2 possible at all, since the images could not be
rebuilt here (T62). Its backend image is faithful to `HEAD` because no `backend/src` has changed
since it was built. **It is still running**, and now holds one extra test tenant from §4.2. It was
left alone rather than torn down, because `make down-e2e` is `down -v` and the stack predates this
session.

---

## 8. Next steps, in order

Nothing from this session is half-finished.

1. **The pull request.** Three commits, `main` three behind. Smaller than the last one, and the
   same judgement.
2. **`docs/deployment.md`, and the `.env.example` audit beside it.** Neither needs a browser or
   Docker, so both are fully doable on this machine — which is not true of most of what is left.
3. **`ai_message` retention** — still the oldest carried item, and still the only open one that
   touches data the system keeps about real people.
4. **The security block**: rate limits per public endpoint, log redaction, the `prod` default-secret
   refusal, the full-history secret scan, error-response leakage.
5. **The principal's**: credits for [#17]'s remaining arm; [#15]'s title.

---

## 9. Commands

```bash
# The frontend suite. About five seconds, no Docker, no network.
cd frontend && pnpm test

# One file while iterating, or watch mode.
cd frontend && pnpm exec vitest run "src/app/(dashboard)/appointments"
cd frontend && pnpm test:watch

# The gate alone — the catalogue against what is actually in src/.
cd frontend && pnpm exec vitest run src/test/screens

# The 360 px sweep. CI only (see below).
cd e2e && pnpm exec playwright test --project=mobile
```

**A browser still cannot be launched on this machine.** Re-confirmed across three sessions: Chrome
starts and is `SIGKILL`ed. **CI is the only place the sweep runs**, and its evidence is the
twenty-six-row table in the *The flow* step's log.

**Nor can the E2E images be rebuilt here** — T62. If a stack is already up, `curl` against
`http://localhost:9180/api` is the available substitute, and §4.2 says exactly how far it goes.

---

## 10. Confidence

**High — that the frontend suite passes and covers what it says.** 22 files, 76 tests, read out of
the Frontend job's log. All nineteen screens have empty, loading and error asserted, and
`coverage.test.ts` fails in both directions when a component reads a resource and is not classified.

**High — that the 360 px sweep measured twenty-six routes.** Each printed with its own
`scrollWidth/clientWidth`, the count asserted against the declared list, a 900 px plant on every
execution, and a runtime that moved 3.9s → 5.4s when the five were added.

**High — that the empty-state assertions are not vacuous.** Each asserts an absence beside its
presence, and the gate was shown red on demand. Failing during development is not evidence; failing
on demand is.

**High — that `useParams` was the defect §3.1 says it was.** The old stub was planted back and the
assertion named `/api/appointments/undefined` in its own failure message.

**Medium — that the state tests would catch a visual regression.** They would not, and **T58** is
why: no CSS is loaded, so `toBeVisible` is a claim about the document and not about the screen. This
suite asserts behaviour and copy; the sweep asserts geometry. **Nothing here asserts appearance**,
and nothing in this project ever has.

**Medium — that the five detail routes will stay swept.** They depend on `seedDetailRows`, and the
conversation leg depends on the fake provider answering. §7.2 is the note.

**Low — that twenty-six is the whole route surface.** The sweep lists routes by hand. Nothing
enumerates `src/app` and fails when a route exists that nobody swept — which is the
`EndpointCoverageTest` trick the backend uses and the frontend does not. A route added tomorrow is
invisible to this sweep and would be to a reader too.

**None — [#17]'s verdict.** Unchanged. No model was called.
