# Session handoff — 2026-09-11 — the seed, and the headers that exist at last

> **Purpose.** Phase 11 started. Three of its items are done and one of its documents is no longer
> false. §2 is the two-tenant seed and the decisions inside it; §3 is the demo script, **walked
> rather than written**, and the defect walking it found; §4 is **G20 closed** — the CSP and HSTS
> that `06-security.md` §13 has described since the design phase now exist, and the header test
> that would have certified their absence is written the other way round; §5 is the performance
> dataset, which closes **G18, G15 and G16** and corrects a number this project has been quoting.
>
> **Read §4.2 before touching `.env`.** `CSP_SCRIPT_EXTRA` needs double quotes around its single
> quotes. Written the obvious way it reaches Caddy as a bare token, and the policy then names a
> *host* called `unsafe-eval` — a valid-looking header that permits nothing (**T43**).
>
> **Nothing is outstanding but a decision.** `dev` and `origin/dev` are both `2e18c48`, the working
> tree is clean, and CI is green — but `main` has not moved, and whether these seven commits go into
> it now or wait for more of phase 11 is §8's opening item rather than something this session took.
>
> **Resolved 2026-09-12, by the principal: merge.** Opened as [#28] and merged as **`34c454e`**,
> three checks green. `dev` was fast-forwarded, so both branches hold an identical tree. §8's item 0
> is closed and the numbering below is otherwise unchanged — **the next step is item 1, G19.** The
> merge included one commit this document does not describe, `3e2b913`, which is the commit that
> finished this handoff.
>
> **The principal answered three questions at the top of the session** and this handoff is what
> followed from them: implement CSP and HSTS rather than correct the document; start phase 11 with
> the seed; park [#17]'s tool count as *"eight, plus a ninth under test"* rather than wait on an
> experiment nobody can fund.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#20]: https://github.com/sanama-stack/reception-booking-system/pull/20
[#27]: https://github.com/sanama-stack/reception-booking-system/pull/27
[#28]: https://github.com/sanama-stack/reception-booking-system/pull/28
[previous]: ./2026-09-11-the-headers-that-existed-only-in-prose.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`fb9717a`**, unchanged this session. **`34c454e` as of 2026-09-12**, when the decision below was taken |
| `origin/dev` = `dev` | **`2e18c48`** — **seven ahead of `main`**, nothing unpushed, working tree clean. **`34c454e` as of 2026-09-12**, identical to `main` |
| CI | **green on `dev` twice**, most recently on `2e18c48`: Backend, Frontend and Compose smoke all pass. §6.1 has the one failure and why it was not a change of mine |
| Backend | **860 tests, 0 failures, 0 errors, 0 skipped** — `--rerun`, counted after the `BUILD` line and not before it (T41). 6m 20s. Up from 844; the sixteen are the seed's own, eight of them without a database. `NfrBenchmarkTest`'s five are **not** in that number: they are tagged `perf` and excluded, and the full run was checked for the absence of their result file rather than trusted to skip them |
| Frontend | untouched. No `pnpm build` ran locally; the dev server stayed up throughout (G9 unchanged). It ran green in CI |
| Migrations | **none.** The seed writes through the application and the perf fixture runs the existing nine; neither adds a column or a table |
| New ADR | none |
| Issues | [#17] and [#15] open, untouched. No model was called: the account is **still out of credits**, re-checked this session (`429 credit_balance_exhausted`) |
| Perf fixture | `backend/tools/perf-dataset/` — committed, migrates rather than clones. The ad-hoc `reception_perf` is **dropped**, and the drop-and-rebuild cycle run end to end afterwards |
| Phase 11 | **started. 18 boxes ticked, 53 open.** Seed, security headers and the three performance checks done |
| ~~**Open decision**~~ | ~~whether to open the pull request into `main` now or let more of phase 11 accumulate on `dev`. Not taken — see §8~~. **Taken 2026-09-12: merge.** [#28], `34c454e` |

---

## 2. The two-tenant seed

`make seed` writes **Salon Aria** (`Asia/Tbilisi`, GEL — 4 services, 3 employees, 6 customers, 23
appointments) and **Dato's Auto** (`Europe/Berlin`, EUR — 3 services, 2 mechanics, 5 customers, 16
appointments). Both verified in a browser: the dashboard, the calendar's day and week, analytics,
the customer list and both public booking pages.

### 2.1 Configuration goes through the application; appointments do not

Hours, catalog, staff, schedules, assignments, closures, FAQs and customers are written by the same
services the dashboard calls, so every validation rule an owner meets applies to the seed. A fixture
that wrote around them could describe a business the application would refuse to accept, silently.

**Appointments are the exception, because the engine cannot book the past** — and half the data is
history, which is the half that makes Analytics show anything. They are constructed and saved
directly, but saved `CONFIRMED`, so the `EXCLUDE` constraint judges every one, the composite foreign
keys still refuse a row that mixes tenants, and the `V8`/`V9` ceilings still apply. Buffer arithmetic
is `ServiceSpec.occupancyFor`'s own rather than a copy. Everything after the insert is the real path
again: `AppointmentStatusService` closes one out, `CancellationService` cancels one.

### 2.2 Placement is anchored to the week, never to a date

A fixture that said "three days ago" would land on a Sunday one run in seven and on a day the
Employee does not work in three — and the failure would look like a scheduling defect rather than a
fixture that cannot count. `Weeks` resolves *(offset in weeks, weekday, time)* against the Monday of
the current week **in the Business's own zone**, so a Wednesday booking is a Wednesday booking
whenever the seed runs, and the two tenants do not disagree about which day it is.

`COMPLETED` and `NO_SHOW` are applied only once an Appointment has actually ended. Which of this
week's rows that covers depends on the day you seed, deliberately. A `BOOKED` row in the past stays
`CONFIRMED` on purpose — it is the owner's unfinished business, and it is what the demo script marks
completed by hand.

### 2.3 `BlueprintCheck` — and it is proven able to fail

Every defect this catches is one the application would happily store. An Appointment outside opening
hours is **legal**, and has to be, because hours change after bookings exist — so nothing downstream
would object and the demo would simply show a salon open on a day it is shut.

It is a pure function of the blueprint: no database, no Spring, no clock, so it runs in CI in
milliseconds. `BlueprintCheckTest` runs it against the real data and then plants **six** defects in
the real Salon Aria blueprint one at a time — past closing time, outside the employee's own shift, a
service they do not perform, two bookings in one diary on one day, a booking during time off, and a
customer cancellation too late to be allowed — and asserts the check names each. A validator never
seen to fail is indistinguishable from one that returns silently.

`DemoSeedTest` is the other half: it drives the seeder against real Postgres exactly as the runner
does, then reads everything back **in SQL**, in each Business's own timezone, on the suite's
`Pacific/Kiritimati` JVM. That is the part `BlueprintCheck` cannot do — on its own it can only
confirm the plan agrees with itself. Eight cases, including *seeding twice leaves one copy*.

### 2.4 Three guards, and the third is not decoration

`@Profile("local")`, so outside that profile the bean does not exist. `@ConditionalOnProperty`, so a
local application started normally does not reseed itself on every restart — `make seed` sets that
property and nothing else does. And a check of the environment inside `run()` before anything is
deleted, because the first two are annotations, and an annotation is exactly the kind of thing a
refactor moves or copies without noticing it was load-bearing.

### 2.5 The reset, and why the seed is repeatable

Refusing to run when a Salon Aria already exists makes the command useless from the second time on,
which is every time after the first. `SeedReset` removes **the two demo tenants by name** — by slug,
and by the memberships of the two demo accounts, because a run that failed between registration and
the profile patch leaves a business under the slug registration derived. It deletes nothing else: the
database it runs against is a developer's, and `Phase 06 Scratch` and two other local businesses sat
through every run of this session untouched.

`memberships`, `refresh_tokens` and the users are deleted by hand; everything else goes by
`ON DELETE CASCADE` from the Business row. A future migration that adds a table referencing a
Business **without** cascading will fail this loudly on the foreign key rather than leave an orphan.

### 2.6 The seed cannot log its own credentials

It prints to `System.out`, and that is not a style preference. The log appenders mask an email
address and redact anything beside the word "password", in every profile, deliberately
(`06-security.md` §10) — so a seed that announced its demo login through the logger would emit
`[REDACTED_EMAIL]` and be working exactly as designed while being useless.

It also **empties the outbox** for the seeded tenants afterwards. The seed sends no mail of its own,
but cancelling through the real service enqueues a real cancellation email, and a demo that opens
with a Mailpit holding messages nobody sent teaches the wrong thing. After the seed, the first
message in Mailpit is one the demo caused.

---

## 3. The demo script was walked, and it was wrong — **T44**

The README now carries the ten-step demo script the phase asks for. It was **executed**, not
composed: signed in, read the calendar and analytics, booked a haircut on the public page as a
stranger, waited for the poller, pulled the Manage Link out of Mailpit's API, and followed it.

**The first walk dead-ended at step 7.** Booking the nearest slot the day strip offers — tomorrow —
puts the appointment inside Salon Aria's 24-hour Cancellation Window, and the manage page correctly
answers *"Too late to change this online"*. The step that exists to prove a customer can move their
own booking without an account cannot run. A second booking two days out moved and cancelled
normally, Confirmation Code unchanged.

The script now says **pick a day at least two ahead**, and names the refusal as a thing to go and
look at, since the Cancellation Window binding the customer while leaving the business free is a real
property worth seeing.

**T44 — a demo script has to be walked, not written.** The path it recommends by default is the one a
stranger takes, and the default here was the one that fails. Nothing but walking it would have said
so: every individual feature involved works.

---

## 4. CSP and HSTS — **G20 closed**

The [previous handoff][previous] §3.1 found that `06-security.md` §13 documented a
`Strict-Transport-Security` and a Content-Security-Policy that **no file set**, and warned that the
phase-11 header test, written from the Caddyfile, would go green and certify the gap (**T42**). The
principal chose to implement rather than to correct the document, so the order was *decide, then
assert*.

### 4.1 What ships

`default-src 'self'` with `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'` and
`object-src 'none'`; `img-src` and `font-src` additionally `data:`; `style-src` and `script-src`
additionally `'unsafe-inline'`.

**`'unsafe-inline'` on `script-src` is a concession and §13 now names it** rather than implying a
policy stricter than the one served: the App Router serves its hydration payload as inline
`<script>` elements, and a per-request nonce has to be issued where the HTML is rendered — Next's
middleware — not by a reverse proxy. `'unsafe-eval'` is absent, which is the half that matters.

`Strict-Transport-Security` is `max-age=0` locally, and that is not a stub: a browser honours HSTS
only on a response that arrived over HTTPS, so on a plain-HTTP origin the header is inert whatever it
says, and `max-age=0` is the value meaning *remember no policy for this host*. A TLS deployment sets
`HSTS`.

### 4.2 One seam, measured rather than assumed — and **T43**

`next dev` compiles modules through `eval`. Under the strict policy the dev server throws
`EvalError: Evaluating a string as JavaScript violates the following Content Security Policy
directive` on every page — **observed in a browser console, not predicted**. So `CSP_SCRIPT_EXTRA` is
appended to `script-src`: `'unsafe-eval'` in `.env` for the development topology,
`""` in `docker-compose.apps.yml` for the five-container shape CI smoke-tests and deploys. The
Caddyfile's own default is **empty**, so a topology that forgets the variable gets the strict policy
rather than the lenient one.

**T43 — a `.env` value that must itself contain quotes loses one layer to Compose.** Written
`CSP_SCRIPT_EXTRA='unsafe-eval'`, the header came out as
`script-src 'self' 'unsafe-inline' unsafe-eval` — which is not a keyword but a **host source named
`unsafe-eval`**. It is a syntactically valid policy that permits nothing, it looks right in a
`curl -I`, and the only tell is a pair of missing quotes. `.env.example` now says so beside the
value.

### 4.3 `make check-headers`, and it was made to fail

The header test is `make check-headers`, run against the **running origin** rather than read off the
Caddyfile — which is the whole point of T42. CI calls it from the Compose smoke job, the only
topology whose policy is the one that ships.

It was proven in both directions before being trusted: against this machine's development origin it
**failed**, naming `unsafe-eval`; with `CSP_SCRIPT_EXTRA=""` and Caddy restarted it passed on both
`/` and `/api/health`. A check that has only ever been seen to pass is the thing this project keeps
filing traps about.

### 4.4 What is not proven — **G21**

The strict policy has been shown to be *served*, and shown not to break `next dev` once relaxed. It
has **not** been exercised by a browser against a production build (`next start`, the `make up-all`
topology). A CSP violation is client-side, so the smoke test's `curl /` cannot see one and neither
can `check-headers`. The remaining directives are the same ones the dev app runs under today, so the
risk is low — but it is a real gap and the phase-11 Playwright flow is where it closes.

---

## 5. The performance dataset — **G18, G15 and G16 closed**

`backend/tools/perf-dataset/generate.sh` builds a database of 31 600 Appointments from nothing:
create, **migrate**, load, `VACUUM (ANALYZE)`. The ad-hoc `reception_perf` three sessions measured
against has been dropped.

### 5.1 Migrated, not cloned — and the check was made to fail first

The old scratch database was a `pg_dump --schema-only` clone taken before `V8` and `V9`, so it was
missing `appointments_max_length`, `appointments_buffer_before_max` and
`appointments_buffer_after_max` — **the three CHECK constraints the bounded queries depend on**. Its
`flyway_schema_history` also existed and was empty, which is worse than absent.

The script therefore asserts those three constraints are present after migrating and before loading
a row. **That assertion was run against the old clone before it was deleted, and it named all
three**; against the migrated database it passes. A fixture that cannot enforce the ceiling a fast
query assumes will return a fast, wrong answer — from the instrument that was supposed to be
checking.

### 5.2 The three NFR checks pass

`NfrBenchmarkTest`, tagged `perf`, twenty runs after five discarded:

| Check | Threshold | p50 | **p95** |
|---|---|---|---|
| Availability, 7 days, **five** employees (the NFR says three) | < 300 ms | 87.64 ms | **103.20 ms** |
| Appointment list, first page, 10 000 rows | < 200 ms | 16.67 ms | **20.93 ms** |
| Analytics summary, 90 days | < 500 ms | 25.32 ms | **32.40 ms** |
| Calendar, one week (no NFR) | — | 33.89 ms | **42.64 ms** |

**It goes through the services rather than through `psql`, and that is the point.** The statement is
Hibernate's because Hibernate wrote it; the parameters are bound because JDBC bound them; and the
driver switches to a server-side prepared statement after five executions, so the twenty measured
runs are on the **generic plan** that the `PREPARE`/`EXECUTE`-six-times recipe existed to reach
(T30). Three sessions solved those two problems by hand. It also measures the operation, hydration
included, which is what the NFR is about.

**Nothing was missing and nothing was added.** The interesting part is the distance from phase 10's
query times for the same operations — 0.019 ms to 1.4 ms. **The query was never the cost.**

**Those are one run's figures, and a re-run will not reproduce them.** Across four rebuilds the
analytics p95 moved between 24.9 ms and 45.2 ms and availability between 103 ms and 111 ms — a
factor of nearly two on the first. Every one passes its threshold with an order of magnitude to
spare, which is why the test gates on the threshold and prints the number rather than the other way
round. **A differing re-run is not a regression**; a failing threshold is.

### 5.3 G15, and the number it corrects — 46x is 1.2x

Phase 10 recorded **46x** for the calendar's lower bound, from hand-written SQL on literals. G15
asked for it on Hibernate's own statement. It is **1.2x** — 7.55 ms against 8.95 ms at p95.

Both are right, and the gap between them is the finding: 46x was a ratio of *queries*, 1.2x is a
ratio of *operations*, and hydrating a week of Appointments costs the same on both sides. Measured
separately on this dataset the query alone is **0.26 ms against 3.64 ms, 30 buffers against 841** —
14x and 28x.

**Neither ratio is why the bound exists**, and quoting 1.2x on its own would be the worse mistake of
the two. The unbounded query reads 8 800 rows to return 140, and 8 800 is every Appointment this
tenant has ever had: it is a slope, not a factor. At 30 000 rows phase 10 watched the same plan
abandon the index and scan the whole table. **No measurement at one table size can show a cliff** —
which is why the committed test asserts only that removing the bound is worse, and records the ratio
instead of gating on it (**T45**).

### 5.4 G16 — the first cold reading in this project

Every performance number here has been `shared hit`. The 366-day status count, first execution after
a PostgreSQL restart: **`shared read=31`, 1.931 ms**, against **`shared hit=31`, 1.026 ms** warm.

**Be exact about what that is.** A restart empties PostgreSQL's buffer pool and not the operating
system's page cache, so it is a cold *database*, not a cold *disk*. The number is quoted with that
attached, and the recipe is in the tool's README. It is also not a large finding, and says so: 31
pages is nothing, and a query that touches 31 pages cannot be slow however cold they are.

### 5.5 What the fixture deliberately does not have

No users and no memberships. Nothing in it can be signed into and no password hash is committed; the
benchmark adopts the tenant directly, the way `TenantContextFilter` would. Everything is placed
relative to midnight UTC on the day it is generated, so **a dataset more than a few days old should
be rebuilt** — the NFR ranges are anchored to today and a stale one quietly moves its history out
from under them.

## 6. Four documentation drifts closed, and the clearance

| | |
|---|---|
| **The tool count** | `07-mvp-scope.md`, `08-testing-strategy.md` and `ADR-0004` said *eight*; `05-ai-architecture.md` said *eight plus a ninth under test*. All four now say the latter, by the principal's decision, rather than waiting on [#17]'s unfunded verdict |
| **The Caddyfile's own port comment** | said *"served from `http://localhost:8080`"*, the container-internal port, in precisely the file a reader opens to find out what the origin is ([previous][previous] §3.3) |
| **`APP_PUBLIC_URL`'s default** | `docker-compose.apps.yml` defaulted it to `http://localhost:8080`. That is the origin a **Manage Link** is written with — a default that sends a customer to a port no browser can reach. Now `9080` |
| **The unauthenticated API docs** | `/docs`, `/openapi` and `/swagger-ui` are permitted to everyone in every profile, `prod` included. Correct against the MVP's local-compose contract and **not** in §15's accepted-risks table, which is the state that table exists to make impossible ([previous][previous] §3.4). Now recorded there, named as the first thing to change on an internet-reachable host |

### 6.1 The clearance, and the CI failure that was not a change

Seven commits, pushed in two batches, **CI green on both**:

| | |
|---|---|
| `fb9717a..a64fa22` | the seed, the headers, the tool count, the first handoff commit |
| `a64fa22..2e18c48` | the perf dataset and its record |

**The first push went red, and it was Docker Hub.** The Compose smoke job failed at *Bring the
system up* with `failed to fetch oauth token … read: connection reset by peer` while pulling
`node:22-alpine` — before `make check-headers` ran at all. Re-run unchanged, it passed. Worth
recording because a red Compose job on the commit that changes the Compose files invites exactly the
wrong conclusion, and the log names the cause in one line.

**That green run is also the only evidence the shipped CSP works in the topology that ships.**
`make check-headers` passed there, against containers built from `docker-compose.apps.yml`, which is
where `CSP_SCRIPT_EXTRA` is emptied. It asserts the header, not the page — see G21.

`dev` and `origin/dev` are identical at `2e18c48` and the working tree is clean. **`main` has not
moved**: the pull request is the open decision in §8.

**Superseded 2026-09-12.** `main` has moved: [#28] merged as `34c454e`, and CI is green on the merge
commit — so the shipped CSP now has a fourth Compose smoke behind it, and the first on `main`. **G21
is unchanged by that**, because every one of those runs is `curl`: none of them loads the page in a
browser, which is the whole content of the gap.

---

## 7. Every open item

### 7.1 Issues

**[#17]** — open, unchanged. Still carried as an accepted, measured defect at 10.6%, rising to 55.2%
on relative phrasing. Its deciding arm is still fifteen trials of fifty short and **still unfunded**:
the credit check was re-run this session and returned `429 credit_balance_exhausted`.

**[#15]** — open, untouched. Its title still names a diagnosis a later session disproved.

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G19**.

**Closed this session: G15, G16, G18** (§5) and **G20** (§4).

New: **G21 — the strict CSP has not been exercised by a browser against a production build.** §4.4.

### 7.3 Traps

Carried T1–T42. New:

**T43 — a `.env` value that must itself contain quotes loses one layer to Compose**, and the
resulting policy names a host where a keyword was meant. §4.2.

**T44 — a demo script has to be walked, not written.** §3. Its default path was the one that fails,
and every feature it touches works.

**T45 — a ratio measured at one table size cannot see a cliff.** §5.3. The calendar bound is 46x, or
14x, or 1.2x, depending entirely on what is being divided by what; none of those is the reason it is
there, which is that the unbounded query's cost grows with the tenant's whole history and eventually
stops using the index at all. Record such a ratio; do not gate on it.

### 7.4 Security

**S1 — no model was called. Zero API requests, zero tokens, zero cost.** The one credit check is a
request that costs nothing when it fails, and it failed.

**The demo credentials are committed on purpose.** `owner@salonaria.example` /
`owner@datosauto.example`, password `reception-demo`, in `DemoTenants` and in the README. They are a
`local`-profile-guarded fixture for accounts that exist only in a developer's own database, and the
seed refuses to run anywhere else.

### 7.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory;
no frontend test runner; no `docs/deployment.md`; no Playwright.

---

## 8. Next steps, in order

**0. The pull request, which is a judgement rather than a task.** `dev` is seven ahead of `main` with
CI green, and every commit in it has been exercised rather than merely compiled — which is the
standard the README sets. The argument for merging now is that two of the seven make `main` a better
clone for a stranger, which is what that standard is *for*: `make seed` and a demo script that was
walked. The argument for waiting is that phase 11 has 53 boxes open and this project has merged one
pull request per phase since 08. **Not taken here.** Whoever takes it should note that the previous
session already merged mid-phase-11 work ([#27]), so the per-phase rule is not as firm as the history
first suggests.

> **Taken 2026-09-12 — merge.** Opened as [#28] with eight commits (the seven above plus `3e2b913`,
> this handoff's own last commit) and merged as **`34c454e`**, a merge commit, which is what every
> pull request since [#20] has been and what phase 05 recorded as the thing that ends the squash
> conflicts on a long-lived `dev`. `dev` was then fast-forwarded: both branches are `34c454e` and
> `git diff origin/main origin/dev` is empty. That last step is the one earlier sessions skipped —
> which is why this history contains two *"Merge main into dev to satisfy strict branch protection"*
> commits that carry no work of their own.
>
> **The first `gh pr merge` was refused** — *"the base branch policy prohibits the merge"* — while
> `mergeStateStatus` read `CLEAN`, protection required **zero** approving reviews, and all three
> required checks had passed. The retry succeeded with nothing changed, so it was GitHub computing
> mergeability asynchronously rather than a policy. Recorded because that message names a cause that
> was not the cause, and the obvious response to it is `--admin`, which would have bypassed a
> protection that was not blocking anything.
>
> **CI on the merge commit is green** — run `34689259786`, Backend, Frontend and Compose smoke test
> all `success`, waited for rather than assumed from the pull request's own head. So `main` has now
> been smoke-tested with the shipped CSP in the topology that ships, which is the fourth such run
> and the first on `main`.

1. **The E2E's model strategy (G19)** — a stub at `OPENAI_BASE_URL` keeps `OpenAiChatModel` on the
   path. Decide it before writing the flow. The seed is now in place, which was its prerequisite.
2. **The isolation suite**, built as classify-or-fail. The seed gives it two real tenants to probe
   across, which was the other prerequisite.
3. **The frontend test runner**, independent of all of the above.
4. `docs/deployment.md`, which now has three things waiting for it: HSTS, `CSP_SCRIPT_EXTRA`, and the
   unauthenticated API documentation.
5. **The principal's**: add credits and finish the [#17] arm; [#15]'s title.

---

## 9. Commands

```bash
# Where things actually stand.
git status -sb && git log --oneline -3 && git rev-list --left-right --count origin/main...origin/dev

# The demo dataset. Repeatable; replaces only the two demo tenants.
make seed

# The security headers, against the running origin. Expects the up-all topology: under `make up`
# it fails on 'unsafe-eval', and that failure is the check working (§4.3).
make check-headers

# The performance dataset, then the three NFR checks against it. Rebuild it if it is stale — every
# range in it is anchored to the day it was generated.
backend/tools/perf-dataset/generate.sh
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test -PincludeTags=perf --rerun
# `--drop` removes it and touches nothing else. The cold-reading recipe (G16) is in the tool's README.
backend/tools/perf-dataset/generate.sh --drop

# The seed's own tests, without the rest of the suite.
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*BlueprintCheckTest' --tests '*DemoSeedTest' --rerun

# Is the account still out of credits? One call, costs nothing when it is.
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $(grep '^OPENAI_API_KEY=' .env | cut -d= -f2-)" \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'
```

**The whole backend suite is `--rerun` plus waiting for the `BUILD` line** (T41), and
**`pnpm build` still must not run while the dev server is up**.

---

## 10. Confidence

**High — the seed.** Written, run four times, and read back out of the database twice: once by hand
in `psql` and once by `DemoSeedTest` in SQL. Both tenants were then opened in a browser — dashboard,
calendar day and week, analytics, customers, both public booking pages — and the 890.00 GEL on the
analytics card was checked against the eight completed appointments by arithmetic.

**High — the demo script.** Every step from booking to the Manage Link was performed. The step that
does not work as first written is §3, and it is the reason to trust the rest.

**High — §4.2's measurement.** The `EvalError` was read off a browser console with the strict policy
live, and the console was re-read in a **fresh tab** after the relaxation, because a console
accumulates and a stale error is indistinguishable from a current one.

**Medium — that the strict policy is complete.** `default-src 'self'` is safe because the frontend
loads nothing external — no `next/font`, no `<link>` to a CDN, no absolute URL in `src/` that is not
a comment. That was established by grep, not by watching a production build run under it (**G21**).

**High — §5's dataset.** Built four times from scratch, and the ceiling assertion that guards it was
run against the old clone, where it named all three missing constraints, before that clone was
deleted.

**Medium — the NFR numbers.** They are real and repeatable on this machine and they pass with room
to spare, but they are one laptop, warm, with no HTTP layer and no concurrency. What they establish
is that no query is pathological at 31 600 rows; they are not a statement about a server under load.

**None — [#17]'s verdict.** Unchanged. No model was called.
