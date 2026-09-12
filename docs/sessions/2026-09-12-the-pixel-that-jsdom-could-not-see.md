# Session handoff — 2026-09-12 — the pixel that jsdom could not see

> **Purpose.** The frontend test runner exists and is **green in CI**. §2 is what it is; §3 takes
> the three targets phase 11 named and says what happened to each, because **one of them could not
> be built where it was specified** and the reason is a measurement rather than an opinion; §4 is
> four traps; §6 is what is still open, including the four empty states this deliberately did not
> reach.
>
> **Built no product code.** `backend/src` and the non-test half of `frontend/src` end
> **byte-for-byte** as they started — `git diff 3b415ec..HEAD -- backend/src` is empty, and so is
> the same diff over `frontend/src` with `*.test.ts*` and `src/test/` excluded. Every file this
> session touched is a test, a configuration file or a document.
>
> **The headline is a thing that cannot be done.** Phase 11 asked for the 360 px widths to be
> asserted in the frontend runner. **jsdom has no layout engine**: a `div` explicitly 1200 px wide
> reports `scrollWidth`, `offsetWidth` *and* `clientWidth` of **0**. The natural assertion there
> reads `0 <= 0` and passes for every page forever. That row went to Playwright instead, by the
> principal's decision, and is green — §3.3.
>
> **Nothing is outstanding.** The tree is clean and all four CI jobs are green on **`52b5121`**, the
> last commit carrying code — this handoff is the one after it, and is documentation only. `main`
> is ten behind, and whether that merges now is §7's first item.

[previous]: ./2026-09-12-the-probe-that-only-looked-one-way.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`a71d17a`**, unchanged this session |
| `origin/dev` = `dev` | **ten ahead of `main`** — `52b5121` plus this handoff, nothing unpushed, tree clean |
| CI | **all four jobs green** on `52b5121`. Three runs this session, all green: `34701980741`, **`34702330928`** (the one the claims below are read from), `34702655780` |
| Frontend | **18 files, 68 tests**, plus lint, typecheck, format and the build |
| The 360 px sweep | **21 routes, every one `360/360`**, printed per route in the job log |
| Backend | **not re-run.** No Java changed. The 928 is inherited from [the previous handoff][previous] |
| Migrations | **none.** New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit check was made |
| Phase 11 | **39 boxes ticked, 32 open.** Five ticked here |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 2. What was built

Vitest 5 and Testing Library in `frontend/`, wired into CI's Frontend job beside the gates that
were already there. There has never been a runner here; its absence has been carried as an open
item since phase 02, where it was recorded as *deliberate*, and phase 11 widened that row rather
than ignoring it. This is the other half of that decision.

**No `@vitejs/plugin-react`.** Its contributions here would be Fast Refresh, which a test run has
no use for, and the React Compiler's Babel pass, which this application does not use — against a
peer range demanding a Vite major of its own. Vite's own transform handles `.tsx`.

Four pieces:

- **`vitest.config.mts`** — jsdom, `TZ=Asia/Tbilisi`, and the `oxc` JSX option (**T56**)
- **`src/test/setup.ts`** — the `next/navigation` stub, the `<dialog>` shim (**T57**), and the
  teardown that unstubs `fetch` between files
- **`src/test/harness.tsx`** — `serve()` and `renderScreen()`
- **`src/test/screens/`** — the catalogue and the gate over it

### 2.1 `fetch` is stubbed, not `@/lib/api/client`

The client is the thing that turns a response into the `ApiError` a screen renders: its status
handling, its `detail`/`title` fallback, its `NETWORK_ERROR` case, its `/api` prefix. A mocked
`api.get` would replace all of that with whatever the test decided an error looks like. Stubbing the
transport keeps the real client on the path, so an error state appears in a test for the same reason
it would appear in a browser.

It cost one round to get right and the failure was instructive: catalogued bodies keyed `/customers`
matched nothing, because the client asks for `/api/customers`. The prefix is Caddy's split and not
something any screen knows about, so the harness takes it off rather than having every case repeat
it.

### 2.2 The harness mirrors the real layout rather than approximating it

`renderScreen` wraps in `SessionProvider` then `ToastProvider`, which is `app/layout.tsx`'s own
nesting. Two screens rendered without the toast provider threw *"useToast must be used inside a
ToastProvider"* on a path the test was not looking at — which reads as a broken screen rather than
as a missing wrapper.

`/auth/me` is answered from the fixture in **every** mode, including `pending`. A dashboard page
renders `null` until it has a session, so a stub that left the session pending would produce an
empty document, and a test looking for a spinner would be asserting the absence of a page rather
than the presence of a loading state.

---

## 3. The three targets, one at a time

Phase 11 named three. They did not fare the same, and that is the section worth reading.

### 3.1 Business-timezone rendering — done, and proven the way it was specified

The suite runs at **`Asia/Tbilisi`** — +04:00, no DST — and every fixture belongs to a business at
**UTC**. Run the same files at UTC and most of them would pass against a module that did nothing at
all.

The calendar is where a zone mistake is worst, because there the zone decides **pixels** and not
only text: `minutesOfDay` is a block's top edge. Both halves are asserted. The block is labelled
`09:00 – 09:45`, *and* it is drawn **60 px** down — where 09:00 is, on a grid whose window starts at
08:00 and whose scale is one pixel a minute — rather than at the **300 px** the browser's own
reading of the same instant would give.

**Each file asserts the counterfactual is in force before anything rests on it.** If `TZ` ever
stopped reaching the worker, the difference the whole file is about would be zero, every assertion
below would still pass, and the suite would be a tautology announcing itself as a timezone test.

**CI is what proves that guard is not decoration.** GitHub's runners are UTC. Had `env: { TZ }`
failed to reach the worker there, the Frontend job would have gone **red** rather than passing
against a business and a browser that happened to agree.

**Shown able to fail.** Replacing `DayView`'s `calendar.range.timezone` with
`Intl.DateTimeFormat().resolvedOptions().timeZone` turned both calendar assertions red. Reverted,
and the revert confirmed by an empty `git diff` over `src/app`.

**One assertion of mine was over-broad, and the test caught it.** Asserting that `13:00` appears
nowhere failed against a correct render: `13:00` is on that screen legitimately, as an hour label in
the gutter, because the window runs to 18:00. That is **T49 from the other side** — a locator that
names the wrong thing is as wrong when it matches as when it misses. It is scoped to the blocks now.

**And ESLint caught the other one.** The first draft read the ambient zone through
`Intl.DateTimeFormat()`, which `no-restricted-properties` forbids application-wide under ADR-0003.
Rather than punch a `eslint-disable` through a rule this project enforces on purpose, the assertion
went through `Date.prototype.getTimezoneOffset()` — which needs no exception and is the better
assertion anyway, because a zone named correctly but not in force passes a name check and fails an
offset one.

### 3.2 Empty, loading and error per screen — two of three, and the third is counted

**Loading and error: every screen.** They come from `ResourceGate` everywhere but one, so they are
the same by construction rather than by everyone remembering, and are asserted once at the gate —
including the two behaviours that are deliberate choices rather than accidents: a reload keeps the
current content on screen instead of replacing it with a spinner, and so does a *failed* reload.

`DetailDrawer` is the exception. It hand-rolls both, because each has to sit inside the dialog under
a heading and above a Close button — a gate wrapped around the drawer would put a bare spinner in a
panel with no title and no way out of it. That makes it the one screen whose states could drift from
every other screen's without anything noticing, so they are asserted directly.

**Empty: fifteen of nineteen.** It cannot be shared — an empty closure list and an empty FAQ list
say different things and offer different next steps — so each screen's own copy is asserted against
that screen. The pairs got particular attention, because they are where the copy does real work: an
owner who has narrowed to one employee and a fortnight, and is shown *"No appointments yet"*, has
been told their business is empty when it is their filter that is.

`explainEmptyReason` is asserted once rather than three times over, since the availability preview,
the booking flow and the public page all render its copy.

**The four that are not asserted are counted, not hidden.** `UNASSERTED_EMPTY_STATES` pins the
number exactly, so a fifth turns the gate red rather than joining a number nobody reads. They need
fixtures nothing here has yet — a full `AppointmentWithHistory`, an `AppointmentDetail`, and the
availability read.

### 3.3 The 360 px widths — the row that could not live where it was specified

**The measurement first.** In jsdom, a `div` explicitly 1200 px wide reports:

```
scrollWidth: 0   offsetWidth: 0   clientWidth: 0   getComputedStyle().width: "1200px"
```

`getComputedStyle` only echoes the inline string back. There is no layout. So the natural assertion
— `scrollWidth <= clientWidth` — reads `0 <= 0` and **passes for every page, forever**. That is a
vacuous assertion of exactly the kind this project has twice paid for: T49, and the mark-completed
step that was a no-op for as long as the flow existed.

**Put to the principal with the measurement rather than decided quietly.** Three options: extend the
Playwright `mobile` project, add Vitest browser mode to the Frontend job, or keep the single-page
E2E run and strike the row. **Answered: extend the `mobile` project** — the only one that measures
real layout where a browser already runs.

It grew from one route to **twenty-one**: three signed out, seventeen behind the sign-in, and the
public booking page. Green in CI, every route reading `360/360`.

Three conditions hold before anything is measured, each ruling out a way to pass having measured
nothing — the URL is still the route asked for, something rendered, and nothing is still loading.
The first matters most: a guard bouncing the visitor to `/login` would otherwise produce seventeen
passes against the sign-in form, which is the shape of **T52**.

`/settings` redirects to `/settings/profile` — it is a section, not a screen — and that is written
down as an expected landing rather than worked around, so an unintended bounce is still caught.

**The sweep plants a 900 px element on every execution and requires the measurement to notice it.**
Every other assertion in it is an *absence*, and an absence is also what a broken measurement
reports.

### 3.4 And then it passed in 3.9 seconds

Its first CI run went green in **3.9 seconds** — for a registration and twenty-one page loads.

That is fast enough to look like a loop that ran nothing, and **the log could not settle it**: the
`list` reporter prints the test, not its steps, so a sweep that visited everything and a sweep that
visited nothing leave an identical green tick.

The time was honest. A warm production build over localhost really is that fast, and the slug in the
log — `e2e-salon-mtyjoqzbrx6w` — is a business that was really registered. But **no green tick could
have established that**, which is the point. The sweep now prints every route's
`scrollWidth/clientWidth` and **asserts the number of routes measured against the number declared**,
which an empty loop cannot satisfy.

The plant proves the measurement can fail; this proves it was taken. Neither is worth much without
the other. That is **T59**.

---

## 4. Four traps

**T56 — Vite 8 transforms with Oxc, so an `esbuild:` option is ignored, and the error names the
wrong cause.** `esbuild: { jsx: 'automatic' }` in a Vitest config produces a warning only if an
`oxc` block is *also* present, and is silently ignored when it is the only one. The failure that
follows says *"If you use tsconfig.json, make sure to not set jsx to preserve"* — pointing at
`tsconfig.json`, which says `preserve` **correctly**, because Next does that transform in the real
build. The fix is `oxc: { jsx: 'automatic' }`; the diagnosis is not in the message.

**T57 — jsdom implements `<dialog>` but none of its modal behaviour.** `showModal()` is not a
function, so any component built on a native dialog throws on mount — which reads as a broken
component. Both the drawer and the confirm dialog are built that way deliberately, because the focus
trap, the inert background and the Escape key are things a browser supplies and this application
declined to re-implement. The shim in `setup.ts` opens and closes and claims nothing more: what a
browser does with a modal is the E2E flow's to prove.

**T58 — jsdom has no layout engine and no stylesheet resolution, and both are easy to forget.**
Every dimension is `0` (§3.3), so no geometry can be asserted there. Less obviously: no Tailwind CSS
is loaded in a unit test, so a class that hides an element does nothing, and `toBeVisible()` means
*"in the document, not hidden by an inline style or the `hidden` attribute"* — **not** *"a person
could see this"*. Do not read a `toBeVisible` in this suite as a claim about what renders.

**T59 — an absence-asserting suite needs two proofs, and a plant is only one of them.** A plant
proves the measurement *can* fail. It says nothing about whether the measurement was *taken* — a
loop that never ran executes no plant and reports the same green. Make such a suite print what it
measured and assert how much it measured. Reasoning about whether a duration is plausible is not
evidence; §3.4 is the whole argument.

---

## 5. What phase 11 still wants

**Five boxes ticked here**, all against run `34702330928`, read out of the job logs rather than off
the jobs' colours: the timezone counterfactual, the runner wired into CI, the 360 px assertions,
*frontend unit tests green in CI*, and the Definition of Done's *frontend unit tests run in CI's
Frontend job*.

**One row stays open and says why**: empty/loading/error per screen — loading and error cover every
screen, empty covers fifteen of nineteen.

Still open and untouched by this session: rate limits for every public endpoint, the log-redaction
test, the `prod` default-secret refusal, the full-history secret scan, the error-response leakage
review, `ai_message` retention, the revenue remainder from ADR-0010, all four observability items,
`.env.example`'s audit, and `docs/deployment.md`.

---

## 6. Every open item

### 6.1 Issues

**[#17]** and **[#15]** — open, untouched. **No model was called this session**, and no credit check
was made.

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**.

**New: G25 — the five id-taking routes are outside the 360 px sweep.** `/appointments/{id}`,
`/customers/{id}`, `/services/{id}`, `/employees/{id}` and `/conversations/{id}` are not swept,
because each needs a row the sweep's freshly-registered tenant would have to create first. They are
also the routes most likely to be too wide, because they are the ones that render tables and
history. The twenty-one that are swept are the breadth; these five are the depth, and they are not
covered by anything.

### 6.3 Traps

Carried T1–T55. New: **T56**, **T57**, **T58**, **T59** (§4).

### 6.4 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory;
no `docs/deployment.md`. **No longer carried: no frontend test runner.**

---

## 7. Next steps, in order

Nothing from this session is half-finished except the four empty states, which are counted rather
than forgotten.

1. **The pull request.** Ten commits, four green jobs, `main` ten behind. A judgement, not a task
   — and note this would be the **first merge the `End-to-end` gate can actually block**: the last
   one satisfied the gate rather than exercising it, as [the previous handoff][previous] §12.1 says.
2. **The four unasserted empty states**, and **G25** beside them — both are fixture work, and the
   fixtures overlap.
3. `docs/deployment.md`, and the `.env.example` audit beside it.
4. **`ai_message` retention** — still the oldest carried item, and still the only open one that
   touches data the system keeps about real people.
5. **The principal's**: credits for [#17]'s remaining arm; [#15]'s title.

---

## 8. Commands

```bash
# The frontend suite. About four seconds, no Docker, no network.
cd frontend && pnpm test

# One file while iterating, or watch mode.
cd frontend && pnpm exec vitest run src/lib/time
cd frontend && pnpm test:watch

# The gate alone — the catalogue against what is actually in src/.
cd frontend && pnpm exec vitest run src/test/screens

# The 360 px sweep. CI only (see below).
cd e2e && pnpm exec playwright test --project=mobile
```

**A browser still cannot be launched on this machine.** Re-confirmed 2026-09-12: Chrome starts
(`pid=18950`) and is `SIGKILL`ed, with `kill EPERM` on the way out. `E2E_BROWSER_CHANNEL=chrome`
does not help. **CI is the only place the sweep runs**, and its evidence is the per-route table in
the *The flow* step's log.

The Vitest suite has no such problem and runs anywhere — which is most of the argument for it
existing.

---

## 9. Confidence

**High — that the frontend suite passes.** 18 files, 68 tests, read out of the Frontend job's log on
two separate runs rather than inferred from the job's colour.

**High — that the timezone tests assert something.** A browser-zone fallback was planted into
`DayView` — code that was already green — and both assertions went red. Failing during development
is not evidence; failing on demand is. The same distinction [the previous handoff][previous] §10
drew, and for the same reason.

**High — that the 360 px sweep ran and measured.** Twenty-one routes, each printed with its own
`scrollWidth/clientWidth`, the count asserted against the declared list, and a 900 px plant that
runs every execution. This claim was **Medium for about twenty minutes** and §3.4 says what changed
it.

**Medium — that the empty-state coverage is what it says.** Fifteen of nineteen, and the gate
enforces the pointers by requiring every literal title in a source file to appear in the test file
that claims it. What no machine checks is whether an assertion on that title is a *good* one.

**Medium — that the state tests would catch a visual regression.** They would not, and T58 is why:
no CSS is loaded, so `toBeVisible` is a claim about the document and not about the screen. This
suite asserts behaviour and copy. The sweep asserts geometry. **Nothing here asserts appearance**,
and nothing in this project ever has.

**Low — that the four unasserted empty states are the only gaps of their kind.** The gate lists
components that render `<EmptyState>`. A screen that shows nothing at all when it has nothing —
never having had an empty state to begin with — is invisible to it, and would be to a reader too.

**None — [#17]'s verdict.** Unchanged. No model was called.
