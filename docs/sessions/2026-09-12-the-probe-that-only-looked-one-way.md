# Session handoff — 2026-09-12 — the probe that only looked one way

> **Purpose.** The isolation suite is built, green, **and merged to `main`**; **G23 is closed**.
>
> Read in this order. **§11 first** — the End-to-end job went red on a commit that had already
> passed, and the cause was a step in the flow that asserted nothing. Then §3, the five plants the
> suite was tested with, **one of which failed to go red and exposed a weakness in my own probes**.
> Then §2 for what the suite is and why reflection alone could not have produced it, §4 for three
> traps (a fourth, T55, is in §11.2), §5 for the ten phase-11 boxes, and §12 for how it landed.
>
> **Built no product code.** `backend/src/main` and `frontend/src` end **byte-for-byte** as they
> started — `git diff 0063265..a816174 -- backend/src/main frontend/src` is empty. Eight commits:
> five the isolation suite, one the E2E fix, two documentation.
>
> **The suite found no defects in the application.** Every planted leak was caught; nothing unplanted
> was. That is the second session running in which the new test found nothing, and §6 is what that
> does and does not establish. **The push that followed found a real one** — in the E2E flow, not the
> application: §11.
>
> **Nothing is outstanding.** `origin/main` and `origin/dev` are level. The tree is clean, all four
> jobs are green, and the merge is `a71d17a`.
>
> > **How the session actually went**, because the order matters more than the outcome: the five
> > suite commits were **held unpushed** while the principal decided whether to widen PR [#30] or
> > open a second one. The decision was *push and widen* — and the push is what turned the E2E job
> > red. Had they gone onto a separate branch, the same defect would have been found at the same
> > moment; had nobody pushed at all, it would have been merged unnoticed.

[previous]: ./2026-09-12-every-failure-was-the-test.md
[#30]: https://github.com/sanama-stack/reception-booking-system/pull/30

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`a71d17a`** — *Merge pull request #30*. Contains everything below |
| `origin/dev` | **`a816174`**, **level with `main`**, nothing unpushed, tree clean |
| PR [#30] | **merged** 2026-09-12, 24 commits, a merge commit rather than a squash — as #28 and #29 |
| CI | **all four jobs green** on `a816174`, the head that was merged |
| Branch protection | **`Backend`, `Frontend`, `Compose smoke test`, `End-to-end`**, `strict: true`. G23 closed (§12) |
| Backend | **928 tests, 0 failures**, run twice in full. Was 915 mid-session, 860 inherited |
| Frontend | not touched, not rebuilt |
| Migrations | **none.** New ADR: none |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit check was made |
| Phase 11 | **34 boxes ticked, 37 open.** Ten ticked here |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 2. What was built

Five files, all under `backend/src/test/java/dev/reception/tenancy/`.

**`EndpointCatalogue`** — one line per endpoint, saying what tenant isolation means for it. Seven
classifications: `OWNER_RESOURCE_ID`, `OWNER_COLLECTION`, `OWNER_SINGLETON`, `PUBLIC_SLUG`,
`PUBLIC_MANAGE_TOKEN`, and `NO_TENANT` with a written reason.

**`EndpointCoverageTest`** — the gate. Reflection over `RequestMappingHandlerMapping` produces the
list; the catalogue produces the judgement; this fails if they disagree **in either direction**.

**`TenantIsolationSweepTest`** — 27 dynamic tests, one per id-taking endpoint, each handed Datos
Auto's real id and required to answer `404`.

**`SmuggledBusinessIdTest`** — 24 readable endpoints, each asked twice, plainly and with
`?businessId=<the other tenant>` appended, requiring **byte-identical** bodies. Plus the body half,
read back in SQL.

**`PublicSurfaceSweepTest`** — 12 probes, one per public endpoint, written for each endpoint's own
shape, with a registry check holding the set against the catalogue.

### 2.1 Why reflection alone could not have done this

The phase document asks for a suite "discovered by reflection … so a new endpoint cannot be silently
omitted". Reflection can list the endpoints. It cannot say which of them own tenant data, and a
suite that guessed would either skip the one that leaks or drown in false alarms over `/auth/login`.

So discovery and judgement are separated, and the **gate is on the disagreement**. Adding a
controller method breaks the build until somebody writes down what isolation means for it. Deleting
a line from the catalogue is not a way to make the suite pass; it is a way to make the gate fail with
the endpoint's own name in the message. **Classify or fail.**

### 2.2 Every probe has a control

Each probe first sends the same request with the **caller's own** id, and requires `2xx` or `409`.

This is not ceremony. A probe without it asserts `404` against a system that answers `404` for a
mistyped path, a wrong HTTP method, or a body the validator rejects — none of which have anything to
do with tenancy. The control is what makes the `404` that follows mean *the row exists and you cannot
see it*. It was demonstrated by planting an invalid body: the control fired with its own message
rather than the probe passing on a `422`.

### 2.3 Three response shapes that were wrong in the first draft

Found by the tests failing, and now pinned by them: the two public listings return **bare arrays**
rather than a wrapped object; `/public/appointments/manage` returns the appointment **flat**, not
under `$.appointment`; and a failed lookup is **`401`, by design** — a proof that does not hold,
rather than a row being hidden. `PublicIsolationTest` pins the same status from the other direction.

---

## 3. Five plants, and the one that did not go red

A suite that has only ever been green has not been tested. Each plant was made in
`backend/src/main`, run, and reverted; `git diff` over `src/main` is empty.

| # | Planted | Result |
|---|---|---|
| 1 | `GET /services/{id}/planted-leak`, an unclassified endpoint | Gate red, message names it |
| 2 | `ServiceCatalogService.read` → `findById` | PATCH and DELETE red — **but not the GET**. §3.1 |
| 3 | …and `AssignmentService.requireService` too | All six service probes red; GET returned `200 OK` with Datos Auto's service |
| 4 | A `@RequestParam businessId` honoured by `GET /services` | Red: "Oil change" where the control had "Haircut" |
| 5 | Every slug resolving to whichever Business sorts first | **Only the two chat probes went red.** §3.2 |

### 3.1 A single leak did not surface where it should have

Plant 2 made `ServiceCatalogService.read` unscoped, and `GET /services/{id}` **still answered 404**.
The reason is that `ServiceController.read` also calls `AssignmentService.employeesFor`, which
performs its own `requireService(businessId, serviceId)` — a second, independent tenant check on the
same request. That is defence in depth working exactly as one would want, and it is worth knowing:
**a single planted leak does not prove a probe is live**, because the endpoint may be guarded twice.
Planting in both places handed the row over.

### 3.2 The plant that exposed my own probe

Plant 5 resolved every slug to the first Business. Six of the seven `PUBLIC_SLUG` probes **passed**.

Salon Aria registers first, so Aria's page still showed Aria's rows. Only Datos Auto's page was
wrong — and every listing probe I had written inspected **Aria's page only**. The probes proved that
Aria was not the victim, which is not the same as proving the endpoint is sound.

Every listing probe now asks **both** pages. With the plant still installed, six went red.

This is the finding of the session. The suite was green, the plants were being chosen to confirm it,
and the one plant that did not go red was the one worth having run.

---

## 4. Three traps

**T52 — a one-directional isolation probe proves the wrong thing.** Checking that tenant A's page
shows only A's rows leaves the symmetric failure invisible, and which tenant ends up the victim is
decided by row ordering, id generation or insert order — none of which the test controls. Probe both
directions, or the test's result depends on which business registered first.

**T53 — defence in depth defeats a single plant.** An endpoint guarded twice stays correct when one
guard is removed, so a plant that fails to go red means *either* the probe is dead *or* the system is
stronger than the plant. Read the code before concluding the first.

**T54 — a probe with no control is an assertion about nothing.** `404` is the answer to a mistyped
path, an unsupported method and a rejected body. Send the same request with your own id first, and
require it to resolve, before the borrowed id means anything.

---

## 5. Ten phase-11 boxes

Ticked on this session's work: reflection-based endpoint discovery, the per-endpoint `404` probe,
`business_id`-in-request rejection, public cross-tenant leakage, the reflection-driven suite itself,
and *every tenant-scoped endpoint is probed*.

Ticked on work that **already existed** and was read before being ticked, not taken on the
filename's word: cross-tenant native-insert rejection (`CrossTenantAssignmentTest`, which has its own
same-tenant control) and the AI schema tenant-parameter assertion (`ToolSchemaTest`, at any depth, in
both spellings). Each is ticked with a pointer to where it lives.

---

## 6. Two sessions, no defects

The E2E flow found nothing. The isolation suite found nothing. Both were built against an
application that turned out to be right.

That is a real result and not a disappointing one — but it should be read for what it is. What these
two sessions establish is that **the guards that exist work**, demonstrated by removing them and
watching the tests notice. What they do not establish is that the guards are everywhere they should
be; that is the catalogue's job, and the catalogue is a judgement written by hand, held against
routing by a gate. The gate cannot tell whether an exemption is honest. There are six of them, each
with a reason, and they are the part of this suite that a reviewer should read.

---

## 7. Every open item

### 7.1 Issues

**[#17]** and **[#15]** — open, untouched. No model called, no credit check made.

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**.

**G23 — closed** (§12). `End-to-end` joined `Backend`, `Frontend` and `Compose smoke test` on
`main`'s required checks, `strict: true`, and the rest of the protection was read back afterwards and
is unchanged.

**New: G24 — the body half of the smuggling sweep covers two endpoints, not all of them.** `POST
/services` and `POST /employees` are checked in SQL for where the row landed. Every other write is
covered by `TenantRepositoryShapeTest`'s compile-time rule — no `..web..` DTO declares a
`businessId` — which is a strong argument and not the same as an observation.

### 7.3 Traps

Carried T1–T51. New: **T52**, **T53**, **T54** (§4), and **T55** (§11.2).

### 7.4 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; **nothing deletes an `ai_message`**; rate-limit buckets are in memory;
**no frontend test runner**; no `docs/deployment.md`.

---

## 8. Next steps, in order

Nothing from this session is half-finished, so this list is the phase's, not a continuation.

1. **The frontend test runner.** The largest untouched block in phase 11 — Vitest and Testing
   Library in CI, the timezone counterfactual, empty/loading/error states per screen. Overlaps
   nothing built here.
2. `docs/deployment.md`, and the `.env.example` audit beside it.
3. **G24**, if the compile-time argument is judged insufficient.
4. **`ai_message` retention**, which is the oldest carried item on the list and the only open one
   that touches data the system keeps about real people.
5. **The principal's**: credits for [#17]'s remaining arm; [#15]'s title.

---

## 9. Commands

```bash
# The whole suite. Six minutes, Testcontainers, needs a Docker daemon.
cd backend && ./gradlew test

# The isolation suite alone — about a minute.
cd backend && ./gradlew test --tests 'dev.reception.tenancy.*'

# One file while iterating. Gradle caches an unchanged test: add cleanTest to force it.
cd backend && ./gradlew cleanTest test --tests 'dev.reception.tenancy.PublicSurfaceSweepTest'
```

**The Gradle toolchain is Java 21 and the machine's default `java` is 25**, which fails with a bare
`25.0.4.1` as the entire error message. Export `JAVA_HOME` first:

```bash
export JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home
```

Two beans implement `RequestMappingHandlerMapping` — the application's and springdoc's. Autowiring
it needs `@Qualifier("requestMappingHandlerMapping")`, or it fails with `NoUniqueBeanDefinition`,
which reads like a missing bean and is the opposite.

---

## 10. Confidence

**High — that the suite passes.** 928 tests, 0 failures, the full suite run twice from a clean
invocation, counted out of the JUnit XML rather than read off a colour.

**High — that it asserts something.** Five plants, four of which turned it red, each reverted and
the revert confirmed by an empty `git diff` over `src/main`. The fifth is §3.2 and changed the test.

> **This sentence appears in [the previous handoff][previous] about the E2E flow, where it was
> wrong** — and the two claims are not the same claim, so do not read the correction there as
> applying here. That one inferred "it asserts something" from the test having failed eleven times
> during development, which is evidence about the steps that failed and none at all about a step that
> never did. This one is a direct measurement: four leaks were planted **into code that was already
> green**, and the suite went red on each. Failing during development is not evidence. Failing on
> demand is.

**Medium — that the E2E flow asserts something, step by step.** One of its steps was a no-op for as
long as the flow has existed (§11), and `retries: 0` means no run has ever repeated itself to check.
The fix is verified — the job is green on `a816174` — but only that one step has been audited this
way. **Nobody has read the other steps for the same defect**, and the method for finding it (plant a
failure, confirm the step notices) has never been applied to the flow at all.

**High — that the endpoint list is complete.** It comes from the same object Spring routes requests
with, and a planted endpoint broke the gate by name.

**Medium — that the classifications are right.** Six endpoints are exempt, each with a written
reason, and no machine checks a reason. They are listed together at the foot of `EndpointCatalogue`
and are the first thing to re-read if a leak is ever found in something this suite passed.

**None — [#17]'s verdict.** Unchanged. No model was called.

---

## 11. Added after the push — the E2E defect the isolation work turned up

Pushing turned **End-to-end** red at `0063265` — **a commit that had already passed**. That is the
shape of a flake, and it was not one.

**The log was not enough to tell.** It said `Expected: 40, Received: 0` on analytics revenue, which
reads as an analytics defect. The answer came out of the run's own Playwright artifact: the page
snapshot showed the range holding exactly **one** appointment, the tile reading **`0.00 USD`**, and
the appointment still **`CONFIRMED`**. The mark-completed click had never landed.

It was not caught at the step that should have caught it, because that step asserted:

```ts
await expect(page.getByText(/completed/i).first()).toBeVisible({ timeout: 30_000 });
```

`/completed/i` matches the **"Mark completed" button**, which is on the page before the click and
after a click that did nothing. The step passed on a no-op and the failure surfaced two assertions
later, pointing at the wrong subsystem.

**This is T49, recurring two sessions running, in code written by the session that wrote T49 down.**
`.first()` resolves ambiguity but not wrongness. The trap was recorded and then not applied to the
rest of the file.

The fix (`3d2b567`) asserts two things the trigger cannot satisfy: the badge reads exactly
`Completed`, and the action block — rendered only while the status is `CONFIRMED`
(`appointment-actions.tsx`) — is gone. `revenue()` now returns `null` rather than `0` when the tile
carries no figure, because returning `0` made "not rendered yet" and "no revenue" the same value and
is why diagnosing this needed an artifact download rather than a log.

### 11.1 What this costs the previous handoff

[The previous handoff][previous] §10 claimed **high** confidence that the flow "asserts something",
reasoning that a test asserting nothing would have gone green on the first attempt. One step did
assert nothing and did go green. Both of its §10 claims now carry dated corrections.

**Count the flow's green runs as fewer than they appear.** At least one included a step that did
nothing, and `retries: 0` means no run has ever been repeated to check itself.

### 11.2 The trap

**T55 — a red CI job on a commit that already passed is not evidence of a flake.** It is evidence of
*nondeterminism*, and a vacuous assertion is a common source: the step that should fail passes
regardless, so whether the run goes red depends on whether a later step happens to notice. Reach for
the artifact before the word "flaky" — the screenshot named the cause in one reading, and the log
pointed at the wrong subsystem.

---

## 12. How it landed

**G23 closed first, deliberately.** The required-checks change was made **before** the merge, not
after, so that `main` gained the gate while there was still something to gate. `End-to-end` now sits
beside `Backend`, `Frontend` and `Compose smoke test` with `strict: true`, which also requires a PR
to be up to date with `main` before it can merge.

It was made through `PATCH …/branches/main/protection/required_status_checks` — the sub-resource
endpoint — rather than the full `PUT …/protection`. The full call requires every field in the body
and silently resets whatever is omitted; the sub-resource one cannot. The whole protection was read
back afterwards and compared: review count, `dismiss_stale_reviews`, `enforce_admins`, force-pushes
and deletions are all where they were.

**Then the merge**, `gh pr merge 30 --merge` → **`a71d17a`**, a merge commit rather than a squash,
which is how #28 and #29 went in. **No `--admin`**, per the trap the previous-but-one session paid
for: a `gh pr merge` refusal there named a cause that was not the cause, and `--admin` would have
bypassed a protection that was not blocking anything.

### 12.1 One thing this merge did not do

`End-to-end` was already green on `a816174` when the requirement was added minutes earlier, so this
merge **satisfied** the new gate rather than exercising it. **The first merge the E2E job can
actually block is the next one.** Worth knowing before anyone cites today as evidence that the gate
works.
