# Session handoff — 2026-09-13 — the branch that proved nothing

> **Purpose.** **G37**, opened by [the previous handoff][prev] as the small remainder of G35: *the
> resource-handler policy list is still written.* It was written, and that was the least of it.
>
> **The branch was vacuous.** `RateLimitCoverageTest`'s orphan check kept a map from a policy **name**
> to a concrete path and probed the running application for that path. The probe asked whether
> something was *mounted* there. **It never asked whether the policy matched it.** So the one thing
> the map asserted — this policy guards those assets — was the one thing nothing checked.
>
> **Measured rather than argued.** Repoint `api-docs-ui` at `/nonsense/**` and the swagger-ui assets
> — roughly 1.8 MB a page load — are unlimited, while **every test in `RateLimitCoverageTest` stays
> green**, the orphan check included. The consequence *is* caught, by `ApiDocumentationExposureTest`
> driving real requests; it is not caught by the control that claims it, and that control is the one
> a reader would check.
>
> **The pairing is now computed.** Patterns from the resource mapping, paths from the patterns, and
> the question asked of a path is the one asked of an endpoint — *does this policy match it*. No
> policy name appears in the derivation. A second assertion adds the direction the written list never
> had: **every path served by a resource handler is matched by some policy**, which deleting the
> policy outright now fails and previously could not — with no policy of that name, the map key was
> never consulted and nothing was reported.
>
> **A plant found the weak half of my own control.** Written as *"anything but a 404"*, the guard on
> the generated paths **passed against a generator producing paths nothing serves** — the security
> chain answers a literal asterisk with `401`, and a refusal is not a resource. §4.2.
>
> **Committed, not pushed.** One commit plus this file, leaving `dev` **35 ahead of `origin/main`**
> and 33 ahead of `origin/dev`. 1053 backend tests, 0 failed. Phase 11 still at **62 of 72**.

[prev]: ./2026-09-13-the-one-mapping-out-of-eight.md
[previous]: ./2026-09-13-the-one-mapping-out-of-eight.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **35 ahead of `origin/main`**, 33 ahead of `origin/dev`. The work is **`c4554ed`** |
| CI | **has still seen none of it.** Eleven sessions |
| Backend | **1053 tests, 0 failed, 120 classes** — was 1051 / 120 |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called** — **G32 is still unanswered** |
| Gates | `make check-docs` green. **No new gate** |
| Phase 11 | **62 ticked, 10 open**, unchanged. **G37 closed** |
| E2E stack | **down.** Not attempted; [the handoff before last][prev] §6.4 is unchanged |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

Production code changed in **no file**, for the fifth session running. One test class and one paragraph
of §15 that had gone false.

---

## 2. What the branch actually did

```java
if (!guardsSomething && SERVED_BY_A_RESOURCE_HANDLER.containsKey(policy.name())) {
    guardsSomething = resolves(SERVED_BY_A_RESOURCE_HANDLER.get(policy.name()));
}
```

`resolves` is an HTTP probe for "not a 404". Read it against what the map claims:

| The map's claim | What was checked |
|---|---|
| `api-docs-ui` guards the swagger-ui assets | — |
| Something is mounted at `/swagger-ui/index.html` | **this** |

The policy is the subject of the sentence and appears in the check only as a **key**. Rename the
assets and the probe 404s, which is the failure the comment describes and the one it catches. Repoint
the *policy* and nothing happens at all.

### 2.1 The measurement

`api-docs-ui`'s pattern changed from `/swagger-ui/**` to `/nonsense/**`, everything else untouched:

| Test | Before the fix | After |
|---|---|---|
| `RateLimitCoverageTest` — *no policy guards a path this application no longer maps* | **PASSED** | **FAILED** |
| `RateLimitCoverageTest` — *every path served by a resource handler is covered* | did not exist | **FAILED** |
| `ApiDocumentationExposureTest` — *every publicly reachable documentation path is covered by a policy* | **FAILED** | FAILED |
| `MappedSurfaceTest` — the pattern set | PASSED, correctly — the patterns did not change | PASSED |

**The system was protected and the control was not doing it.** That distinction is the whole of this
session: `ApiDocumentationExposureTest` drives real requests against its own written list of four
documentation paths, and it is the reason this was a latent defect in a test rather than an open hole.

---

## 3. What replaced it

- **Patterns** come from `resourceHandlerMapping` — already pinned from the other side by
  `MappedSurfaceTest`, so a new resource root fails there and arrives here.
- **Paths** come from the patterns, taking every wildcard at its narrowest: a `*` inside a segment
  matches zero characters and is dropped, so `/swagger-ui*` becomes `/swagger-ui`. A whole segment of
  `**` names no file and becomes `index.html`.
- **The question** is `policy.matches("GET", path)` — the same one asked of every mapped endpoint.

`index.html` is the single assumption and it is checked rather than trusted: §4.2's control requires
every generated path to be served.

### 3.1 The asymmetry this surfaced, which is not a hole

The policy's pattern is `/swagger-ui/**`. The resource handler's is `/swagger-ui*/**` — springdoc
registers the `*` for versioned paths. **The resource mapping's routable surface is therefore wider
than either the policy or `permitAll`.** Probed: `/swagger-uiZZZ/index.html` and
`/swagger-ui-4.15.5/index.html` both answer `401`.

So the security chain closes it, which is the same shape as `/error` and `/actuator` in [the previous
handoff][prev] — three surfaces now whose safety rests on `anyRequest().authenticated()` rather than
on the control that nominally covers them. Recorded, not changed: widening the policy to `/swagger-ui*/**`
would rate-limit paths nothing can reach, and widening `permitAll` to match would publish them.

---

## 4. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | `api-docs-ui` repointed at `/nonsense/**` — **the plant that was green before this session** | **Red** on two |
| 2 | The `api-docs-ui` policy deleted outright | **Red**, naming both asset paths |
| 3 | The generator stops dropping the in-segment `*` | **Red** on three — §4.2 |
| 4 | The derivation returns an empty set | **Red** on the control and the orphan check |
| 5 | `spring.web.resources.add-mappings` flipped to `true` | **Red**, naming `/index.html` and `/webjars/index.html` |

### 4.1 Plant 2 is the direction that did not exist

Deleting the policy could not have failed the old check: the map is keyed by policy name, so with no
`api-docs-ui` there is no key, no probe, and no orphan. The assets go unlimited and the test reports
nothing. It is the cheaper mistake of the two and it was the invisible one.

### 4.2 The control that accepted a refusal as an asset

Plant 3 broke the generator so it emitted `/swagger-ui*/index.html` — the literal asterisk, the exact
slip a wildcard-substituting generator makes. The two assertions went red, and **the control did
not**: it was written on `resolves`, which takes anything but a `404` as evidence something is
mounted, and `/swagger-ui*/index.html` is refused by the security chain with a `401`.

Not a 404, therefore an asset. The control now requires a `200`.

> **"Not a 404" and "is served" are different questions, and the gap between them is every refusal
> the application can make.** `resolves` is right where it was written — asking whether a path is
> mounted, in a class where the answer may legitimately be a refusal. Inherited one method over to
> answer *"is this generated path real"*, it accepts a `401` as a yes. **Second time this walk that a
> helper was correct where written and wrong where reused**, after the `dev.reception` filter in this
> same class.

---

## 5. Every open item

### 5.1 Committed, not pushed

**Thirty-five commits, eleven sessions, no CI.**

### 5.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**.

**G37 is closed.** §2, §3.

**G38 is new and is small.** *`ApiDocumentationExposureTest` still drives a written list of four
documentation paths.* It is the test that actually caught §2.1, so the list is load-bearing — and it
is a list. Every path on it is now also derived somewhere else (`MappedSurfaceTest` for the framework
mappings, `resourceSamplePaths()` for the assets), so the two could be reconciled rather than left to
agree by hand. Cheap, and nothing depends on it.

**G32 is unchanged and is the oldest unanswered question in the project.** Six consecutive handoffs.
**If the answer is "not now", that is a decision and should be recorded as one.**

### 5.3 Carried

Unchanged from [the previous handoff][prev] §6.3.

### 5.4 The E2E stack

Unchanged and not attempted.

---

## 6. Next steps, in order

1. **Push, and open a pull request.** Thirty-five commits, eleven sessions. Deferred seven times.
2. **Try the E2E stack on an unmetered connection.** It unblocks eight of the ten remaining phase-11
   items, and `make check-access-log` has still never executed anywhere.
3. **Answer G32**, in either direction.
4. **G38**, if the written list in `ApiDocumentationExposureTest` is worth reconciling. Small.
5. **The full-history secret scan.** Needs a scanner installed.
6. **The end-of-phase gates**, which want the stack from item 2.
7. **The principal's**: **G32**, G29, G30, G31, G38; credits for [#17]; [#15]'s title.

---

## 7. Traps

- **T129 — an exemption keyed by name checks the key, not the claim.** The map said *"this policy
  guards those assets"* and the code said *"something is mounted at this path"*. Both sentences
  mention the same path and only one mentions the policy. **When an exemption is a pair, assert the
  pair.** §2.
- **T130 — "not a 404" is not "is served", and the difference is every refusal the application can
  make.** A helper correct where it was written was wrong one method away, and made a positive
  control accept a `401` as evidence that an asset exists. §4.2.
- **T131 — the cheaper mistake is often the invisible one.** Repointing a policy was caught by
  another test; *deleting* it was caught by nothing, because a name-keyed exemption disappears along
  with the name. §4.1.
- Carried and re-confirmed: **T124**, **T125**, **T126**, **T127**, **T128** (all from [the previous
  handoff][prev]), **T89**, **T104**/**T118**, **T105**, **T113**, **T116**, **T120**, **T122**,
  **T69**, **T70**.

---

## 8. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's class.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.ratelimit.RateLimitCoverageTest'
```

```bash
# §2.1, reproducible: change api-docs-ui's pattern to "/nonsense/**" in RateLimitProperties, then run
# these two. The second fails on HEAD~1 as well; the first is what this session added.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.ratelimit.RateLimitCoverageTest' --tests 'dev.reception.common.web.ApiDocumentationExposureTest'
```

---

## 9. Confidence

**High on the finding.** §2.1 is four test runs on either side of the change, not an argument about
what the code would do.

**High on the replacement.** Five plants, every one red, and plant 3 found a real defect in the
control before the control was trusted.

**Medium on the generator, and it is the thing to watch.** `sampleUnder()` handles `*` and `**` and
nothing else, because that is what the two registered patterns contain. A pattern with a different
shape — `{id}`, a regex segment, a suffix match — would produce a path that is not what the pattern
means. **It would not pass silently**: §4.2's control requires each derived path to be served with a
`200`, and a mis-generated path is overwhelmingly likely to 404. That is a guard rather than a proof,
and it is the honest limit of this.

**Unchanged and unhappy on the deployed picture.** Eleven sessions, no CI.
