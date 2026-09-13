# Session handoff — 2026-09-13 — the table nothing could test

> **Purpose.** The last section of the [06-security.md](../06-security.md) walk, continuing from
> [the previous session][prev]: **§15**, the explicitly-accepted-risks table. **The walk is now
> complete — fifteen of fifteen — and the phase-11 row is ticked.**
>
> **§15 is the one section with nothing to resolve.** The other fourteen name controls, and the
> question each time was whether the control resolves to a test that runs. An accepted risk resolves
> to nothing *by definition*: the entry **is** the decision not to build the control. So it was
> checked for the opposite defect — an entry that no longer describes the system — and that is a
> read, not an investigation.
>
> **Three of the seven entries were wrong, each in a different way.** A **justification that was
> false** (2FA "needs no migration" — it needs one). An entry that **recorded half its risk** (the
> API documentation is not only unauthenticated, it is **unlimited**). And a **risk that was
> missing** (the Manage Link is a bearer capability in a URL, discussed at length in §6 and never
> carried here as a decision).
>
> **The finding worth reading is §3.** `/openapi` hands any anonymous caller the complete
> specification — tens of kilobytes — and **no rate-limit policy matches it**: two hundred
> consecutive requests, none refused, measured rather than argued. It went unnoticed because every
> derived control in this repository filters to `dev.reception`, *deliberately*, and these paths are
> springdoc's. They are anonymous, unlimited, and **invisible to every sweep in the tree.**
>
> **And the plant that passed is the lesson for the third time this walk.** With the limiter switched
> off, *"the documentation is unlimited"* **passed** — vacuously, because two hundred requests are
> also unrefused when nothing is limiting anything. Only the positive control caught it.
>
> **Committed, not pushed.** One commit plus this file, leaving `dev` **27 ahead of `origin/main`**
> and 25 ahead of `origin/dev`. `main` untouched. 1030 backend tests, 0 failed. Phase 11 at
> **62 of 72**.

[prev]: ./2026-09-13-the-control-held-by-a-coin-toss.md
[previous]: ./2026-09-13-the-control-held-by-a-coin-toss.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **27 ahead of `origin/main`**, 25 ahead of `origin/dev`. The work is **`21bb3de`**; everything after it is this handoff |
| CI | **has still seen none of it.** Seven sessions now |
| Backend | **1030 tests, 0 failed, 117 classes** — was 1027 / 116. One new class |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called**; no credit was spent — **G32 is still unanswered** |
| Gates | `make check-docs` green, 321 links. No new `make check-*` target |
| Phase 11 | **62 ticked, 10 open.** The security-walk row is **ticked** — fifteen of fifteen sections |
| E2E stack | **still down.** Not attempted; nothing here needed it |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

| | |
|---|---|
| `21bb3de` | Correct the three §15 entries that no longer described the system |
| this file | The handoff |

Production code changed in **no file**, for the second session running.

---

## 2. What §15 can and cannot be asked

Every other section of this document names a control and points at a mechanism. The walk's question
for fourteen sections was the same one: *does the named control resolve to something that runs?* Six
times the answer was no, which is what the last three handoffs are about.

§15 cannot be asked that question. Its rows record **absences** — no lockout, no backups, no 2FA —
and an absence has no mechanism to point at. There is nothing to resolve, nothing to sweep and
nothing to derive.

What it can be asked is whether each row is **still true**, in both directions:

- a risk **since closed** that still reads as open, which teaches a reader the system is worse than
  it is;
- a risk that is **real and missing**, which is the state the table's own last entry was written to
  describe: *"recorded here in phase 11 because it was in neither this table nor the deployment
  notes, which is the state this table exists to make impossible."*

That sentence is a standing invitation to check the rest of the table, and this session took it.

**Four of the seven were accurate**, including the two that make claims about other documents — the
rate-limit externalisation path is genuinely written down (three places), and the backup position is
stated in `deployment.md` rather than assumed. Those were checked rather than assumed, because
"documented as the first scale-out task" is exactly the shape of sentence this walk has found
pointing at nothing six times.

---

## 3. The API documentation entry recorded half its risk

The entry was added in phase 11 and is right about what it says: `/docs`, `/openapi` and
`/swagger-ui` are permitted to everyone, `prod` included, and that publishes the endpoint surface to
anyone who asks.

**It says nothing about cost.** Measured, with the limiter on:

| Path | Status | Refused of 200 | Policy matches |
|---|---|---|---|
| `/api/openapi` | `200`, ~45 KB | **0** | no |
| `/api/swagger-ui/index.html` | `200` | **0** | no |
| `/api/openapi/swagger-config` | `200` | **0** | no |
| `/api/docs` | `302` | **0** | no |

Two hundred requests for the full specification, not one refused. That is the amplifier argument §5
makes about `/health` — *"the only endpoint where an unauthenticated caller causes an outbound
connection"* — at a far larger response size, and it was never made here.

### 3.1 Why nothing caught it, and why the cause is a good decision

`RateLimitCoverageTest` is a strong test. It derives the anonymous surface from the security filter
chain and fails the build for a public endpoint no policy mentions. It filters handlers to
`dev.reception`, and `EndpointCoverageTest` does the same, with a comment explaining exactly why:

> *Framework endpoints are excluded by where they are declared, not by name … filtering on the
> package means a new springdoc version that renames its paths changes nothing here, and an endpoint
> of ours can never be excluded by resembling one of theirs.*

**That reasoning is correct and I did not touch it.** The consequence is simply that the guarantee is
narrower than its sentence sounds: *"every endpoint an anonymous caller can reach is rate limited"*
is a guarantee about **this application's own handlers**. Three paths are mapped outside them, and
they are anonymous, unlimited, and invisible to every derived control in the tree.

Caddy's `handle /api/*` proxies all of it, so this is the deployed exposure and not a local artefact.

### 3.2 What was built, and what was deliberately not

`ApiDocumentationExposureTest` pins the entry's two live claims — that the paths answer an anonymous
caller with the real specification, and that nothing limits them. It asserts **the risk as recorded,
not as desired**: if a future change closes it, these fail on purpose and the fix is to correct §15.

**Closing the exposure is left open, and it is the principal's.** Two shapes:

| Option | Cost |
|---|---|
| A policy over the three paths | One entry in `RateLimitProperties`, minding the ordering rule. Closes the amplification, leaves the disclosure |
| An `UNLIMITED_ON_PURPOSE` exemption | Records the decision where the coverage test can see it. The map's own comment warns against reaching for this first |
| Extending the derivation past `dev.reception` | **Undoes a documented decision** (§3.1) and buys a build that a springdoc release can break |

The third is why this is not a change I made. The first is cheap and I would take it.

---

## 4. The two other corrections

### 4.1 A justification that was false

*"No 2FA — out of MVP scope; the account model supports adding it without migration."*

`users` has **six columns**: id, email, password hash, full name, created at, updated at. There is no
credentials table and no extensible column. Nothing there can hold a shared secret or an enrolment
flag, so adding 2FA is a migration — `V11` and a deploy.

The risk itself was correctly accepted. **The justification is what a reader prices the reversal
with**, and it was off by a schema change. This is a quieter defect than a missing control and it is
the characteristic §15 failure: nobody re-reads a row they already agree with.

### 4.2 A risk that was missing

A Manage Link is HMAC-SHA256 over `appointmentId|expiry`, valid until 24 hours after the appointment
ends, and it travels **in a URL** — a path segment on the page and a query parameter on the two API
calls that page makes. Anyone holding the link can cancel or reschedule that appointment: a forwarded
email, a shared screen, a shared browser's history.

§6 discusses the token at length and closed the access-log leak two sessions ago. **It closed the
leak, not the property that made the leak matter**, and the residual decision was never carried into
§15. Accepted deliberately — the alternative is asking a Customer for their code and phone number
every time they open a link we emailed them, which is the friction the link exists to remove — so it
belongs in the table, and now is.

The candidate [the previous handoff][prev] §8 raised — that the CSRF layer §13 once described and did
not have might now read as a stale accepted risk — **dissolved on inspection.** §15 never listed
CSRF. Worth recording so the next reader does not re-check it.

---

## 5. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | A policy added over `/openapi/**` and `/swagger-ui/**` | **Red, correctly**, on the unlimited assertion |
| 2 | `/openapi/**` removed from `permitAll` | **Red, correctly**, on both the disclosure and unlimited assertions |
| 3 | The limiter switched off | **The unlimited assertion PASSED.** §6 |

---

## 6. The plant that passed, again

Switching `app.rate-limit.enabled` to `false` — one line in the test's own
`@TestPropertySource` — left *"two hundred requests, none refused"* **green**. Of course it did: two
hundred requests are also unrefused when nothing is limiting anything. The finding and the vacuum
produce identical output.

Only `the_limiter_is_running` caught it.

That is the third time in this walk that a positive control was the only thing between a green test
and a meaningless one, and the pattern across the three is worth naming plainly: **an assertion that
something did *not* happen is satisfied by a world where nothing happens at all.** "No CORS header",
"no tenant's rows leaked", "no request refused" — each is true of a system that is working, and each
is equally true of a probe that has stopped working. The three are not separate lessons. They are
T89, arriving in a new costume each time.

---

## 7. Every open item

### 7.1 Committed, not pushed

**Twenty-seven commits, seven sessions, no CI.** Compose smoke and End-to-end remain the two jobs a
local suite cannot stand in for, and `check-access-log` has still never executed in either.

Unchanged in kind, one commit larger, and now the largest item on the list by some distance —
because with the walk finished, **almost everything remaining needs either CI or the E2E stack.**

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G28**, **G29**, **G30**, **G31**, **G33**.

**G32 is unchanged and still the one needing an answer soonest.** The system prompt changed two
sessions ago and the level-3 corpus has not been run against it.

**G34 is new.** *The API documentation is anonymous and unlimited, and the decision to leave it that
way has not been made.* §3.2 has the options. Recorded rather than closed, on purpose.

**G35 is new.** *Every derived control in this repository is scoped to `dev.reception`.* That is the
right decision and it has a consequence nobody had written down: the framework's own mapped
endpoints are outside tenancy classification, rate-limit coverage and the public-surface sweep
alike. Today that set is three springdoc paths and `/error`. It is not asserted to stay that size.

### 7.3 Carried

Unchanged from [the previous handoff][prev] §6.3 — **except** the Manage Link token, which is no
longer an unrecorded risk. It is still in a URL; it is now in the table.

### 7.4 The E2E stack is still down

Unchanged and not attempted.

---

## 8. Next steps, in order

The walk is finished, so this list is shorter and more blocked than it has been.

1. **Push, and open a pull request.** Twenty-seven commits, seven sessions, no CI. Deferred three
   times. Everything below this line is cheaper once it has happened.
2. **Run the level-3 corpus**, or decide not to and say so. G32.
3. **Decide the documentation exposure.** G34, §3.2 — a one-line policy, an exemption, or leave it.
4. **The full-history secret scan.** Needs a scanner installed. **Not on a tethered connection.**
5. **The end-of-phase gates** — clean clone, `make up && make seed`, the demo script, the concurrency
   test, the Definition of Done sweep. **All want the E2E stack and a real connection**, and they are
   now most of what is left in phase 11.
6. **The principal's**: **G32**, **G34**, G30, G31, G33, G35, G28, G29; credits for [#17]'s
   remaining arm; [#15]'s title.

---

## 9. What the walk found, over four sessions

Fifteen sections, and it is worth stating the base rate once now that it is final:

| | |
|---|---|
| Sections walked | **15** |
| Sections containing a claim nothing implemented or nothing could check | **9** |
| Controls that turned out to be **absent** | §6 (Manage tokens in the access log), §13's CSRF half, §14 (authentication logging), §8's fencing (three fields of four) |
| Controls that were **true and asserted by nothing** | §13's CORS half, §3/§4's fifteen endpoints, §1's "by IP", §7's three strings, §2's `Secure` |
| Sections where the **instrument** was the defect | four in one session, plus three positive-control saves |
| Production code changed across the whole walk | **five files**, all in one session |

The shape is consistent and worth carrying forward: **the walk found far more untested truths than
untrue claims.** A document that describes a system accurately and is checked by nothing is the
normal state, not the exceptional one — and it fails silently, at the moment somebody changes the
code and not at the moment somebody writes the sentence.

---

## 10. Traps

- **T113 — an accepted-risk table cannot go red, so it goes stale instead.** Every other section is
  caught by the test that exercises it. A row recording an absence has no such test, and the only
  thing keeping it true is somebody re-reading it. The rows that drift first are the ones whose
  subject is a live configuration. §2.
- **T114 — check the justification, not just the risk.** Three of the seven entries named the right
  risk; one of them priced it wrongly, and a wrong price is what makes a decision look cheaper to
  reverse than it is. §4.1.
- **T115 — a derivation scoped to your own package is narrower than its sentence sounds.** "Every
  endpoint an anonymous caller can reach is rate limited" means *every endpoint of ours*. The
  framework's are anonymous too. §3.1, G35.
- Carried and re-confirmed: **T89** — for the third time in this walk, and §6 above argues it is one
  trap and not three. **T109** (assert the ordering, not just the outcome), **T105**, **T69**,
  **T70**.

---

## 11. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's new class. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.web.ApiDocumentationExposureTest'
```

```bash
# G32: the level-3 corpus against the system prompt changed two sessions ago. NEEDS A KEY AND SPENDS
# CREDIT — the principal's call. Without a key the corpus skips itself rather than failing.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline -PincludeTags=llm
```

---

## 12. Confidence

**High** on all three corrections. The 2FA one is a schema read and cannot be argued with; the
documentation one was measured against a running application rather than reasoned from
configuration; the Manage Link one is a decision that was already made and simply never written in
the place decisions are written.

**High** on the four entries left alone, and they were genuinely checked — each of the two that
points at another document was followed to it.

**Medium on the documentation test's coverage, in a way worth naming.** It proves the backend serves
these paths anonymously and unlimited. It does **not** prove what reaches them through Caddy: the
`handle /api/*` block is a file read, not a probe, and a future route that carved out `/api/openapi`
at the edge would leave this test green and the risk reduced — the stale-entry failure, from the
kind direction. `make check-headers` probes the running origin and could assert this in one line; it
belongs with the next compose-smoke change, and **CI has seen none of the last seven sessions.**

**High** that the walk is complete. Fifteen of fifteen, and §9 states what it found.

**Low on phase 11 being close to done, and this is the honest note to end on.** The row that ticked
today was the last one that could be finished at a keyboard with no network and no containers. Of the
ten still open, **eight want the E2E stack, a clean clone, or CI** — none of which has run in seven
sessions. The remaining work is not smaller than it looks; it is differently blocked, and §8 item 1
is the thing standing in front of all of it.
