# Session handoff — 2026-09-13 — the sweep that rejected the fix

> **Purpose.** One decision, executed: **G34**, the documentation exposure [the previous
> session][prev] found and left open. The principal's call was to add the policy. It took two changes
> and only the first was the one anybody expected.
>
> **The policies were the easy half.** Three of them — `/openapi/**` at thirty a minute,
> `/swagger-ui/**` and `/docs/**` at sixty — sized against a real page load rather than guessed.
>
> **The second half is the finding.** `RateLimitCoverageTest` derives the anonymous surface from
> Spring's routing table and fails the build for any public endpoint no policy mentions. It filtered
> handlers to `dev.reception`, inherited from `EndpointCoverageTest` where that reason is *sound*.
> Rate limiting is a different question from tenancy, and **the filter did not merely hide the gap —
> it rejected the fix.** A policy written for `/openapi` matched nothing in the derived surface and
> was reported as a dead policy. **The derivation that concealed the problem also refused the
> remedy**, which is the sharpest form this failure has taken in the whole walk.
>
> **And one plant corrected me.** I added a policy for `/openapi.yaml`, reasoning it was a sibling
> the pattern would miss. **The coverage test stayed green without it — because it was right and I
> was wrong.** `permitAll` lists `/openapi/**`, which matches children and the bare path but *not* a
> sibling, so the YAML rendering of the specification answers `401` while the JSON one answers `200`.
> Nobody decided that. The pattern decided it. The policy came out and the asymmetry went in as an
> assertion instead.
>
> **Committed, not pushed.** One commit plus this file, leaving `dev` **29 ahead of `origin/main`**
> and 27 ahead of `origin/dev`. `main` untouched. 1033 backend tests, 0 failed. Phase 11 still at
> **62 of 72**.

[prev]: ./2026-09-13-the-table-nothing-could-test.md
[previous]: ./2026-09-13-the-table-nothing-could-test.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **29 ahead of `origin/main`**, 27 ahead of `origin/dev`. The work is **`a3ddf29`**; everything after it is this handoff |
| CI | **has still seen none of it.** Eight sessions now |
| Backend | **1033 tests, 0 failed, 117 classes** — was 1030. No new class; three new tests and one rewritten class |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called** — **G32 is still unanswered** |
| Gates | `make check-docs` green, 323 links. No new `make check-*` target |
| Phase 11 | **62 ticked, 10 open**, unchanged. The security walk stays ticked; **G34 is now closed** |
| E2E stack | **still down.** Not attempted |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

| | |
|---|---|
| `a3ddf29` | Limit the documentation surface, and widen the sweep that hid it |
| this file | The handoff |

---

## 2. The policies, and how they were sized

Measured rather than guessed, because a limit that breaks the documentation page is not a fix:

| Asset | Size |
|---|---|
| `swagger-ui-bundle.js` | **1.47 MB** |
| `swagger-ui-standalone-preset.js` | 229 KB |
| `swagger-ui.css` | 155 KB |
| `/openapi` (the specification) | 45 KB |

One page load is roughly **1.8 MB** across about seven asset requests, two spec requests and a
redirect.

| Policy | Budget | Why |
|---|---|---|
| `/openapi/**` | 30 / min | Two per page load, and the expensive document. Fifteen page loads a minute |
| `/swagger-ui/**` | 60 / min | Seven per page load. Roughly eight page loads a minute |
| `/docs/**` | 60 / min | The redirect, one per page load |

The budgets are deliberately generous against a human and tight against a loop. §5's table carries
them now.

---

## 3. The derivation that rejected its own fix

`RateLimitCoverageTest` is one of the better tests in this repository. It derives the anonymous
surface from `RequestMappingHandlerMapping` and the running security filter chain, and fails the
build for a public endpoint no policy mentions. Its headline sentence is *"every endpoint an
anonymous caller can reach is rate limited."*

It filtered handlers to `dev.reception`, with the reason `EndpointCoverageTest` still gives:

> *Framework endpoints are excluded by where they are declared, not by name … a new springdoc version
> that renames its paths changes nothing here.*

**That reasoning is correct for tenancy and wrong here, and the difference is the question being
asked.** Tenancy is about our own data, and a framework endpoint holds none. Rate limiting is about
what an anonymous caller can *spend*, and springdoc's paths spend exactly what ours do.

So the sentence quietly meant *every endpoint of ours*, while the largest unauthenticated response
in the system sat outside it.

### 3.1 The part that is new

The filter did not only conceal the gap. **It rejected the remedy.** `no_policy_is_an_orphan` asks
whether each policy guards something in the derived surface — the same filtered surface — so a policy
for `/openapi` matched nothing and was reported as a dead policy pointed at a renamed path.

Adding the protection made the build fail *because* the protection was unprotectable. Restoring the
filter as a plant reproduces it exactly, which is the finding restated as a test.

### 3.2 What changed, and what the old comment was right about

The derivation now sees every mapped endpoint. The concern behind the old filter is not lost, it is
inverted into something better: a springdoc rename now surfaces **twice** — the renamed path arrives
as an uncovered public endpoint, and the policy left behind arrives as an orphan. A build that stops
is what was wanted; what the filter delivered was a limit that disappeared.

And a `permitAll` widened to expose a framework path now arrives as an uncovered public endpoint
rather than as nothing at all. §5 above did not have that property yesterday.

---

## 4. The plant that corrected me

I added `api-docs-spec-yaml`, a policy for `/openapi.yaml`, on the reasoning that `/openapi/**`
matches children and not siblings and springdoc maps both renderings — so the same document would
otherwise stay unlimited in a second format.

**Removing it as a plant left the coverage test green.** That is the test disagreeing with me, and it
was right.

`SecurityConfig`'s `permitAll` list has the *same shape as the policy pattern*: it names
`/openapi/**`. So `/openapi.yaml` is not public at all — it answers an anonymous caller **`401`**,
measured, while `/openapi` answers `200` with 45 KB.

Nobody decided that the same document should be published in one serialisation and authenticated in
the other. **A path matcher decided it**, and the two patterns agree only by having been written the
same careless way. The policy was removed — a limit on an authenticated endpoint is noise in a table
whose job is to be readable — and the asymmetry was pinned as an assertion instead, because the
tidy-up that makes the patterns consistent is **one character wide**, reads like housekeeping, and
publishes a document that is currently behind authentication.

**The lesson is about the plant, not the pattern.** I would have shipped an unnecessary policy and a
comment confidently explaining a near-miss that never existed. What caught it was removing the thing
I had just added and being surprised that nothing went red. *A plant that fails to go red is not a
gap in the test; sometimes it is a correction.* That is **T104** from three sessions ago — "a plant
that stays green may mean the system is right" — arriving from the other direction: the system was
right and my change was the thing that was wrong.

---

## 5. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | `/openapi/**` policy removed | **Red four ways** — the coverage test, and three assertions in the exposure test |
| 2 | `/openapi.yaml` policy removed | **Green — and correctly.** §4 |
| 3 | The `dev.reception` filter restored | **Red on the orphan check.** The three new policies become dead policies again. §3.1 |
| 4 | `/swagger-ui/**` policy removed | **Red on one assertion only**, and **not** on the coverage test. §6 |
| 5 | `permitAll` widened to `/openapi**` | **Red twice** — the asymmetry pin, and the coverage test, which now sees a newly-public framework path with no policy |

Plant 5 is the one that shows the widening earned its place: that second failure was impossible
yesterday.

---

## 6. The exclusion that could not be closed

`/swagger-ui/**` is served by `ResourceHttpRequestHandler`. It declares **no handler methods**, so it
can never appear in a derivation built on `RequestMappingHandlerMapping` — and it carries the 1.4 MB
bundle, which is the largest single response in the system.

Plant 4 proves the consequence: removing its policy is caught by a **written list** and by nothing
derived. That is the shape this repository has spent four sessions removing, and here it cannot be
removed.

What was done instead of trusting the list: the orphan check **probes the running application** for
these entries — a concrete path that must resolve to something other than a `404`. Rename or drop the
assets and the probe fails. It is derivation where possible, a probe where not, a written list only
where neither works — the shape [the handoff two sessions back][prev] proposed, with all three
appearing in one method for the first time.

**This is the weakest link in the control and the first place to look if the assets are ever
unlimited again.** G35.

---

## 7. Every open item

### 7.1 Committed, not pushed

**Twenty-nine commits, eight sessions, no CI.** Unchanged in kind. Everything below is cheaper after
it.

### 7.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G28**, **G29**, **G30**, **G31**, **G33**, **G35**.

**G32 is unchanged and is now the oldest unanswered question.** The system prompt changed three
sessions ago; the level-3 corpus has not been run against it.

**G34 is closed.** §2, §3.

**G35 is narrowed but open.** Framework endpoints are now inside the rate-limit derivation. They are
still outside `EndpointCoverageTest` and `PublicSurfaceSweepTest`, correctly in both cases, and
resource-handler paths remain outside everything. §6.

**G36 is new.** *A mapping with no HTTP method condition is invisible to these derivations.*
`patternsOf` reads the method condition, and Spring's `/error` declares none — so it never enters the
surface at all. Harmless today, because `/error` is a forward target and should not carry a limit,
and noted because it is the same class of silent exclusion this session spent its time on.

### 7.3 Carried

Unchanged from [the previous handoff][prev] §7.3.

### 7.4 The E2E stack is still down

Unchanged and not attempted.

---

## 8. Next steps, in order

1. **Push, and open a pull request.** Twenty-nine commits, eight sessions, no CI. Deferred four
   times. **Everything else on this list is blocked, optional, or cheaper afterwards.**
2. **Run the level-3 corpus**, or decide not to and say so. G32 — now the oldest open question.
3. **The full-history secret scan.** Needs a scanner installed. **Not on a tethered connection.**
4. **The end-of-phase gates** — clean clone, `make up && make seed`, the demo script, the concurrency
   test, the Definition of Done sweep. **All want the E2E stack and a real connection**, and they are
   now most of phase 11's remainder.
5. **The principal's**: **G32**, G30, G31, G33, G35, G36, G28, G29; credits for [#17]'s remaining
   arm; [#15]'s title.

There is no longer a "finish the walk" item. This is the first handoff in five sessions whose next
step is not something that can be done at this keyboard.

---

## 9. Traps

- **T116 — a derivation's exclusions must be re-argued when the control is copied, not re-used.**
  `dev.reception` is right for tenancy and wrong for rate limiting, and the filter travelled between
  them with its comment intact. The comment was true; it had stopped being about this test. §3.
- **T117 — a sweep that hides a gap can also reject its fix.** The orphan check and the coverage
  check read the same derived surface, so the endpoint that could not be seen could not be protected
  either: adding the policy failed the build. If a control refuses a correct change, suspect its
  scope before rewriting the change. §3.1.
- **T118 — a plant that stays green may be correcting you.** `/openapi.yaml` needed no policy because
  it is not public, and the coverage test knew. **T104** from the other direction: there, the system
  was right and the probe was weak; here, the system was right and *my change* was wrong. §4.
- **T119 — a path matcher's sibling/child distinction decides security here.** `/openapi/**` matches
  `/openapi` and `/openapi/x` and **not** `/openapi.yaml`. The same pattern appears in `SecurityConfig`
  and in the policy table, which is why one rendering of the specification is public and the other is
  not. Making them "consistent" is a widening. §4.
- **T120 — a resource handler declares no handler methods.** Nothing derived from
  `RequestMappingHandlerMapping` can see `/swagger-ui/**`, which carries the largest response in the
  system. Probe it. §6.
- Carried: **T89**, **T104** (§4), **T105**, **T109**, **T113**, **T115**, **T69**, **T70**.

---

## 10. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's two classes. --rerun-tasks, or a restored plant reports a cached pass (T70).
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.web.ApiDocumentationExposureTest' --tests 'dev.reception.common.ratelimit.RateLimitCoverageTest'
```

```bash
# G32: the level-3 corpus. NEEDS A KEY AND SPENDS CREDIT — the principal's call.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline -PincludeTags=llm
```

---

## 11. Confidence

**High** on the policies and their sizing. The asset sizes were measured against the running
application, not estimated, and the budgets are stated against a real page load.

**High** on the derivation change. It is shown red by restoring the old filter, and plant 5
demonstrates a failure the test could not previously produce.

**High** on the `/openapi.yaml` correction, because it is a measured `401` rather than an argument
about pattern semantics — and I am stating it plainly because I had it wrong an hour earlier and
committed nothing in between.

**Medium on `/swagger-ui/**`, deliberately.** Its protection rests on a written entry, verified by a
probe. Better than a bare list, weaker than everything else here, and named in §6 and G35 so it is
not mistaken for the same grade of control as the rest.

**Medium on the deployed picture, and unchanged from the last two sessions.** Everything here is
asserted against the backend. Caddy's `handle /api/*` is a file read, not a probe, and the compose
smoke job that would test the real edge **has not run in eight sessions.** That is §8 item 1 in a
different costume, for the third handoff running.
