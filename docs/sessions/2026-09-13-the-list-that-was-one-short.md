# Session handoff — 2026-09-13 — the list that was one short

> **Purpose.** **G38**, opened by [the previous handoff][prev] as the small remainder of G37:
> *`ApiDocumentationExposureTest` still drives a written list of four documentation paths.*
>
> **The list was one short.** It named `/openapi`, `/openapi/swagger-config`, `/swagger-ui/index.html`
> and `/docs` as *"the documentation paths an anonymous caller can actually reach"*, and omitted
> **`/swagger-ui/swagger-initializer.js`** — served, anonymous, and on the page every reader of the
> documentation loads. It is covered, but only because `/swagger-ui/**` happens to catch it. **The
> list did not know it existed**, which is the ordinary fate of a list that describes something it
> does not read.
>
> **Derived from the two halves that make it up.** Framework-mapped `GET` endpoints outside
> `dev.reception` — the set `MappedSurfaceTest` pins from the other side — plus the resource paths,
> read through a `ResourceSurface` helper now shared with `RateLimitCoverageTest`. **That extraction
> is the point of the session, not a tidy-up**: a generator copied between two tests is two
> generators, and G38 was opened precisely because two things were left to agree by hand.
>
> **Reachability is the running application's answer.** A `401` or `403` is the security chain
> refusing; anything else got past it, `/docs`'s `302` included. Asking for `200` would have dropped
> `/docs`; asking `SecurityConfig` again would have been a third copy of a derivation this repository
> already has two of.
>
> **A broken run proved the control before a plant did.** §4.3.
>
> **Committed, not pushed.** One commit plus this file, leaving `dev` **37 ahead of `origin/main`**
> and 35 ahead of `origin/dev`. 1054 backend tests, 0 failed. Phase 11 still at **62 of 72**.

[prev]: ./2026-09-13-the-branch-that-proved-nothing.md
[previous]: ./2026-09-13-the-branch-that-proved-nothing.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **37 ahead of `origin/main`**, 35 ahead of `origin/dev`. The work is **`9d29297`** |
| CI | **has still seen none of it.** Twelve sessions |
| Backend | **1054 tests, 0 failed, 120 classes** — was 1053 / 120 |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called** — **G32 is still unanswered** |
| Gates | `make check-docs` green. **No new gate** |
| Phase 11 | **62 ticked, 10 open**, unchanged. **G38 closed** |
| E2E stack | **down.** Not attempted |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

Production code changed in **no file**, for the sixth session running.

---

## 2. The path the list did not know about

| | Written list | Derived |
|---|---|---|
| `/docs` | ✓ | ✓ (`302`, and a redirect is reachable) |
| `/openapi` | ✓ | ✓ |
| `/openapi/swagger-config` | ✓ | ✓ |
| `/swagger-ui/index.html` | ✓ | ✓ |
| **`/swagger-ui/swagger-initializer.js`** | **absent** | ✓ |
| `/openapi.yaml` | absent, correctly | excluded — `401` |

The omission is not a hole: `api-docs-ui` guards `/swagger-ui/**` and catches it. It is a **list that
had stopped describing its subject**, which is the same defect as [two handoffs ago][prev] in a milder
form — and the reason it stayed invisible is that nothing compared the list to anything.

### 2.1 Why this list survived two sessions of removing lists

It was the test that *caught* the last session's defect, so it read as the reliable one. That is worth
naming as a bias: **a control that has just found something looks trustworthy, and finding something
is evidence about the bug it found, not about the control's own completeness.**

---

## 3. `ResourceSurface`, and why it is a class

The wildcard generator introduced last session lived as a private method in `RateLimitCoverageTest`.
This session needed the same paths. Copying it would have created exactly what G38 describes — two
things that must agree, left to agree by hand — so it moved to `dev.reception.support.ResourceSurface`
and both classes read it.

The repository has **five copies of `patternsOf`** and they have been harmless. The distinction, and
it is the reason this one was extracted rather than copied:

> `patternsOf` reads a value out of a framework object; a divergent copy fails to compile or returns
> the same thing. **`sampleUnder` constructs a value**, and two constructions can differ while both
> compile — so the day they disagree, one test is asserting something about paths the other does not
> believe exist.

---

## 4. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | The `api-docs-ui` policy deleted | **Red**, naming **both** swagger paths — §4.1 |
| 2 | The derivation returns an empty set | **Red** on the control; coverage passes vacuously — §4.2 |
| 3 | The reachability filter dropped, so everything counts | **Red** on both, `/openapi.yaml` included |
| 4 | The resource half of the derivation removed | **Red** on the control, naming `/swagger-ui/index.html` |
| 5 | The framework filter inverted — the `dev.reception` slip, again | **Red** on both |

### 4.1 Plant 1 is also the proof of §2

The old test asserted inside its loop, so it reported the first uncovered path and stopped. Collected
instead, plant 1's message names `/swagger-ui/index.html` **and** `/swagger-ui/swagger-initializer.js`
— which is how the derived surface is shown to contain the path the written list omitted, rather than
asserted to.

Asserting in a loop was tolerable over four paths a reader knows by heart. It is misleading over a set
nobody has seen.

### 4.2 The coverage assertion passes over an empty surface, and that is correct

Plant 2 left *"every publicly reachable documentation path is covered by a policy"* green, because
every member of an empty set satisfies anything. That is T89 and it is why the control exists; the
control is what fails. Recording it because a reader skimming the plant table will notice one PASSED
in a column of REDs and should know it is the expected shape rather than a gap.

### 4.3 The run that broke before the plants did

One plant run came back with **every test in the class failing** and `/openapi.yaml` reported as
reachable. The plant did not cause it — the application was answering `404` to everything, for
reasons that did not survive a re-run.

**The control fired correctly in that state**, and for the right reason: a `404` is not a refusal, so
a dead application reports its entire surface as public, `/openapi.yaml` included, and
`the_documentation_surface_is_really_derived` refuses exactly that. The predicate was left alone
rather than taught to exclude `404`: including it is the conservative direction — it asks for a policy
on a path that is not there — and a broken application *should* produce a cascade rather than a
narrower green.

---

## 5. Every open item

### 5.1 Committed, not pushed

**Thirty-seven commits, twelve sessions, no CI.**

### 5.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**.

**G38 is closed.** §2, §3.

**No new gap.** The derivation-scope thread that ran from G33 through G38 is finished: every written
list feeding a public-surface control is now derived or reconciled, and §15's two remaining written
things — the accepted-risk rows themselves — are prose about decisions rather than claims about code.

**G32 is unchanged and is the oldest unanswered question in the project.** Seven consecutive handoffs.
**If the answer is "not now", that is a decision and should be recorded as one.**

### 5.3 Carried

Unchanged from [the previous handoff][prev] §5.3.

### 5.4 The E2E stack

Unchanged and not attempted.

---

## 6. Next steps, in order

1. **Push, and open a pull request.** Thirty-seven commits, twelve sessions. Deferred eight times.
2. **Try the E2E stack on an unmetered connection.** It unblocks eight of the ten remaining phase-11
   items, and `make check-access-log` has still never executed anywhere.
3. **Answer G32**, in either direction.
4. **The full-history secret scan.** Needs a scanner installed.
5. **The end-of-phase gates**, which want the stack from item 2.
6. **The principal's**: **G32**, G29, G30, G31; credits for [#17]; [#15]'s title.

**There is nothing left on this thread.** Every remaining item needs the network, a scanner, or a
decision — which was true after G35 as well, and G36, G37 and G38 each came out of closing the one
before it. That chain has now run out.

---

## 7. Traps

- **T132 — a control that has just caught something reads as trustworthy, and it is not evidence
  about itself.** `ApiDocumentationExposureTest` found the last session's defect and its own list was
  stale at the time. §2.1.
- **T133 — a helper that *reads* a value survives being copied; a helper that *constructs* one does
  not.** Five copies of `patternsOf` have cost nothing. Two copies of a path generator would be two
  answers to "what does this pattern mean". §3.
- **T134 — asserting inside a loop reports the first failure and hides the size of the problem.**
  Fine when the collection is four things a reader knows; misleading the moment it is derived. §4.1.
- Carried and re-confirmed: **T129**, **T130**, **T131** (from [the previous handoff][prev]),
  **T124**–**T128**, **T89**, **T104**/**T118**, **T105**, **T113**, **T116**, **T120**, **T122**,
  **T69**, **T70**.

---

## 8. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's two classes, which now share ResourceSurface.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.web.ApiDocumentationExposureTest' --tests 'dev.reception.common.ratelimit.RateLimitCoverageTest'
```

---

## 9. Confidence

**High on the finding.** §2 is a table of two sets, one read from the source file and one printed by a
failing assertion. `/swagger-ui/swagger-initializer.js` was confirmed served over HTTP before anything
was written.

**High on the derivation.** Five plants, every one red, including each half removed in turn so that
neither source can quietly stop contributing.

**Medium on the reachability predicate, and §4.3 is the reason it is worth watching.** *"Not a 401 or
403"* is the right question and it answers `true` for a `404`. That is deliberate and conservative,
and it means a badly broken application produces a wide, noisy failure here rather than a narrow one.
Anyone reading a cascade from this class should check that the application is serving at all before
believing the paths it names.

**Unchanged and unhappy on the deployed picture.** Twelve sessions, no CI.
