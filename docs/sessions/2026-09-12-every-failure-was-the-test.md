# Session handoff — 2026-09-12 — every failure was the test

> **Purpose.** The Playwright flow exists and is **green in CI**. §2 is what it covers and where it
> lives; §3 is the eleven red rounds it took and what each one was, because **not one of them was a
> defect in the application** — the flow found no bugs and that is the finding; §4 is the three
> traps; §5 is **G19, G21 and G22 closed**, G21 being the one worth stating carefully.
>
> **Built no product code.** `backend/src` and `frontend/src` end **byte-for-byte** as they started —
> `git diff 78ecb5d..HEAD -- backend/src frontend/src` is empty. Fifteen commits, all of them the
> `e2e/` package, the compose overlay, CI and documentation.
>
> **No browser can run on this machine, and that is not a Playwright problem.** §3.1. The shell sits
> behind a network sandbox; every browser install extracts to 624K and a system Chrome is SIGKILLed
> on launch. **CI is the only place this flow can be run from here**, which is why the history is
> fifteen commits instead of one.
>
> **Nothing is outstanding.** `dev` and `origin/dev` are both `b00222a`, the tree is clean, and all
> four CI jobs are green. `main` is fifteen behind, and whether that merges now is §8's first item.

[ADR-0011]: ../adr/0011-the-e2e-fake-provider-lives-behind-the-base-url.md
[previous]: ./2026-09-12-the-model-that-had-to-read-the-transcript.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`78ecb5d`**, unchanged this session |
| `origin/dev` = `dev` | **`b00222a`** — **fifteen ahead of `main`**, nothing unpushed, tree clean |
| CI | **all four jobs green** on `b00222a`. Run `34697065351`: Backend, Frontend, Compose smoke, **End-to-end** |
| The flow | **2 passed (33.9s)** — the flow itself 30.4s, the 360 px run 1.2s |
| Backend | **not re-run.** No Java changed. The 860 is inherited from [the previous handoff][previous], not counted here |
| Frontend | not rebuilt locally. Its CI job is green |
| Migrations | **none.** New ADR: none — [ADR-0011] was the previous session's |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit check was made |
| Phase 11 | **24 boxes ticked, 47 open.** Six ticked here, and nothing near them |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 2. What the flow is

`e2e/`, its own pnpm package — Playwright, TypeScript, a typecheck script, and nothing else.

**One test, not eight.** Every step depends on the state the one before it left. `test.step` is what
makes the output readable, and it is what turned each red round into a sentence naming the step.

`08-testing-strategy.md` §8, end to end, in a browser: register → employee and working schedule →
service and assignment → **a stranger books through the Classic Flow** → **a stranger books by
talking to the Receptionist** → the confirmation email in Mailpit → the Manage Link followed →
cancelled → the owner signs back in → both bookings listed, one cancelled and one badged `AI` on the
calendar → marked completed → analytics revenue. Plus `mobile.spec.ts` at 360 px.

### 2.1 It runs against a system that is already up, in its own compose project

`make up-e2e` layers `docker-compose.e2e.yml` over the shipping topology, in compose project
`reception-e2e`, on ports **9180–9185**, with its own database volume. `make e2e` runs the flow and
refuses with a readable message if nothing answers on 9180.

**The isolation is not tidiness.** An E2E sharing a database with development work registers
businesses into it, and `make down-e2e` — which is `down -v` — would take that work with it.

Playwright's `webServer` is deliberately unused: the topology is six containers and a database, and a
flow that built its own stack would be proving a stack nothing else uses.

### 2.2 Three things the spec did not say, settled by reading the code

- **Opening hours need no configuring.** `BusinessDefaults` gives a new Business Mon–Fri
  09:00–17:00, and the week editor's own default interval is *also* 09:00–17:00 — so a working
  schedule is three clicks and no typing. A schedule is still required: `hasEmployeeSchedule` gates
  `publicPageReady`.
- **The `AI` badge is on the calendar, not on the appointments list**, though §8 says "listed". The
  flow asserts it where it exists.
- **The service form routes to `/services/{id}`**, on its success path *and* on its "created, but who
  provides it could not be saved" path — which is a second request that can fail on its own. The flow
  asserts the employee's name on that page, because a service nobody can perform surfaces much later
  as an empty availability grid.

---

## 3. Eleven red rounds, and not one was the application

This is the section to read. **The flow found no defects.** Every failure was the test: a locator
that named the wrong thing, a wait that asserted the wrong condition, or a consequence of an earlier
choice of mine. That is worth recording rather than glossing, because eleven red CI runs *look* like
a system being debugged and this was a test being taught what the screens actually say.

| # | What failed | What it really was |
|---|---|---|
| 1 | `getByRole('heading', {name: businessName})` on `/dashboard` | The `h1` greets the owner — "Welcome, {first name}". The business name is a `<p>` and a `<dd>`. It **is** a heading on `/book/{slug}`, which is why the line looked right |
| 2 | `waitForURL('**/services')` | The form goes to `/services/{id}`, the detail page |
| 3 | `getByRole('button', {name: /^(Mon\|Tue…)/})` | The day strip sets `aria-label="<date>, N times"`, which **overrides** the visible "Tue 9" |
| 4 | `getByText('Booked')` | Matched "What would you like **booked**?" and "fully **booked**" — **T49** |
| 5 | `getByText('Your confirmation code').locator('..')` | Matched the caption *and* an ancestor containing it: two parents |
| 6 | Nine more `getByText` calls | Fixed as a class rather than one per round |
| 7 | 180s test timeout | The outbox poller's interval is 60s and hardcoded; the flow waits for real mail |
| 8 | 300s timeout on `fill('Email')` | **Not slowness.** `GuestGuard` keeps a signed-in visitor off `/login`; the owner had never signed out |
| 9 | `waitForURL(/\/(login\|)$/)` | `AuthGuard` can win the race and land on `/login?next=…` |
| 10 | `getByText(/cancelled/i)` | Matched `<option value="CANCELLED">` inside a closed `<select>` — nineteen retries against a dropdown |
| 11 | The AI badge; then analytics revenue | **T51** — both consequences of booking the fortnight's last day |

### 3.1 The local environment cannot run a browser at all

Three browser installs reported 129.7 MiB downloaded and extracted **624K**. The cause is a network
sandbox whose allowlist carries `playwright.azureedge.net` but **not `cdn.playwright.dev`**, which is
where Playwright 1.56 fetches browsers — and `playwright install --dry-run` then reports the result
as installed. A system Chrome is present and is `SIGKILL`ed on launch.

Two of those attempts were made worse by my own error: the install was launched as `… & pnpm test`
*nested inside* a background task, so reaping the task killed the install mid-extraction.

**So CI is the only place this flow runs from here**, and each round trip is about ten minutes.

### 3.2 What was verified locally instead, and what it was worth

With no browser, the flow's *scenario* was walked through the API against the running stack — the
half that was invented rather than copied from the already-walked demo script. A fresh tenant
registered, took an employee, a schedule, a service and an assignment, and was booked through both
doors. Read back in SQL:

```
Z9D8F5Q6 | CLASSIC | CONFIRMED | 09:00 | Clara Classic
ZFD0QGEP | AI      | CONFIRMED | 09:30 | E2E Customer
```

The Receptionist landed on 09:30 because the Classic Flow had taken 09:00 — a slot conflict avoided
rather than asserted. **That walk was worth doing**: it meant every CI round was debugging selectors
against a scenario already known to be sound, rather than two unknowns at once.

It also found three of my own script's bugs before CI did — `services` is a separate endpoint,
availability takes `from`/`to` rather than `dateFrom`/`dateTo`, and the employee is nested under
`employee.id`.

---

## 4. Three traps

**T49 — an ambiguous Playwright locator does not fail slowly, it throws immediately.** A strict-mode
violation is not retried, so the `toBeVisible` timeout that exists to let a page settle is silently
cancelled by the very thing it was meant to tolerate. Rounds 4, 5 and 10 were all this. The fix is
not `.first()` sprinkled where it bites — `.first()` resolves ambiguity but not *wrongness*, and in
round 10 it happily picked a `<select>` option nineteen times. Assert a **role and an accessible
name** where identity matters, and scope to a row or a card where it does not.

**T50 — a `waitForURL` timeout can mean "navigated somewhere else", not "did not navigate".** Round
11's click worked perfectly: the customer's name is a link, to `/customers/{id}`. The page loaded,
the URL never matched, and the error said `Timeout exceeded` — which reads as a dead click. Ask the
row for the href you want (`a[href^="/appointments/"]`) rather than clicking whatever text matched.

**T51 — a choice made to satisfy one constraint will be read as a mistake by every later step that
did not know about it.** The flow books the day strip's **last** bookable day, deliberately, so the
booking clears the 24-hour Cancellation Window the cancel step must pass (T44). That single decision
then broke two later steps for reasons that looked nothing like each other: the calendar opens on
*today*, so the `AI` badge was real and off-screen; and **every** analytics preset ends at today —
"Last 30 days" is `today-29..today` — so a future booking could never move revenue however it was
marked. Both were fixed by carrying the booking's own date forward, from the API rather than by
parsing a rendered one.

### 4.1 And one assertion that was too weak to be worth having

The last step asserted `toBeGreaterThan(before)`. That passes on any increase at all. This tenant has
exactly one completed Appointment at a price the test sets, so *"revenue changes accordingly"* has a
right answer: it now asserts **0 before and exactly `SERVICE_PRICE` after**. The price is one
constant, used to fill the form and to check the figure — it was a literal `'40'` in one place and a
constant in the other, which is two things that must agree and eventually would not.

---

## 5. G19, G21 and G22 — closed

**G19 — closed.** The deterministic model is built *and consumed*. It was deliberately left open by
[the previous handoff][previous] on the grounds that nothing used it; the flow is what uses it.

**G22 — closed.** The E2E topology has been **started**, not merely configuration-checked. Six
containers, healthy, and the flow ran against them.

**G21 — closed, and this is the one to state carefully.** The gap was that the strict Content-Security-Policy
had been shown *served* but never **exercised by a browser against a production build**. It now has
been: `CSP_SCRIPT_EXTRA` is `""` in `docker-compose.apps.yml`, the E2E overlay does not override it,
and a real Chromium drove registration, React hydration, form submission, the chat panel and the
calendar through it. A CSP violation is client-side, so no `curl` check could ever have established
this — and had the policy broken hydration, the flow could not have registered a business at all.

---

## 6. Every open item

### 6.1 Issues

**[#17]** and **[#15]** — open, untouched. **No model was called this session and no credit check was
made**, so this branch made no outbound request to the provider at all.

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**.

**Closed: G19, G21, G22** (§5).

**New: G23 — the End-to-end job is not a required status check.** `main`'s protection requires
`Backend`, `Frontend` and `Compose smoke test` only, so a red **End-to-end** does not block a merge.
Adding the context is a repository-settings change and was **not** made here. Until it is, the flow
is a test that can rot without anyone being stopped — the mirror of T42.

### 6.3 Traps

Carried T1–T48. New: **T49**, **T50**, **T51** (§4).

### 6.4 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory;
**no frontend test runner**; no `docs/deployment.md`.

---

## 7. What phase 11 still wants

Ticked here: the Playwright flow, the Mailpit assertions inside it, the mobile-viewport run, the
360 px public-page run, *full E2E flow green in CI*, and the Definition of Done's *E2E passes in CI*.
**Six, and nothing adjacent.**

Still open and untouched by this session: the **reflection-driven isolation suite**, rate limits for
every public endpoint, the log-redaction test, the `prod` default-secret refusal, the full-history
secret scan, **the frontend test runner**, `ai_message` retention, the revenue remainder from
ADR-0010, all four observability items, and `docs/deployment.md`.

---

## 8. Next steps, in order

1. **The pull request.** Fifteen commits, four green jobs, and `main` fifteen behind. A judgement,
   not a task — and see G23, because merging does not make the new job load-bearing.
2. **G23**: add `End-to-end` to `main`'s required status checks. One settings change, and without it
   the work above protects nothing.
3. **The isolation suite**, built as classify-or-fail. Independent of everything here.
4. **The frontend test runner**. Its targets are named in the phase document and none of them
   overlap the E2E.
5. `docs/deployment.md`.
6. **The principal's**: credits for [#17]'s remaining arm; [#15]'s title.

---

## 9. Commands

```bash
# The E2E stack: isolated project, own database, own ports. Minutes, and it builds both images.
make up-e2e

# The flow. Refuses with a readable message if nothing answers on 9180.
make e2e

# Everything it wrote, gone — including its database. It cannot touch `make up`'s.
make down-e2e

# The fake provider's own self-test. No containers, no network, about a second.
make check-fake-provider

# One spec, or one project, while iterating.
cd e2e && pnpm exec playwright test --project=mobile
cd e2e && pnpm exec playwright test -g 'books through the Classic Flow'
```

**A browser cannot be installed or launched on this machine (§3.1).** `E2E_BROWSER_CHANNEL=chrome`
exists for a machine with Google Chrome and no sandbox; here it is SIGKILLed too. **CI is the only
place this runs.**

---

## 10. Confidence

**High — that the flow passes.** Run `34697065351`, all four jobs, both specs, read from the log
rather than inferred from the job's colour.

**High — that it asserts something.** Eleven rounds of it failing on real differences between what
the screens do and what the test expected is, in its way, the evidence: a test that asserted nothing
would have gone green on the first attempt.

**High — G21.** `CSP_SCRIPT_EXTRA: ""` was read out of `docker-compose.apps.yml` and the overlay
checked for not overriding it, and the browser demonstrably ran the application through that policy.

**Medium — that the flow is stable.** It has passed **once**. `retries: 0` is deliberate — this flow
books real rows and a retry would run against a tenant the first attempt changed — but a flow with
one green run has no flake history, and the mail wait is the part most likely to produce one.

**None — [#17]'s verdict.** Unchanged. No model was called.
