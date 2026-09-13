# Session handoff — 2026-09-13 — the walk that ran out of things it could see

> **A synthesis, not a replacement.** Five handoffs were written in one sitting and each stands on its
> own; this is the argument they turn out to be. Read this first and the five for the evidence.
>
> **Eleven commits. Zero production files.** Every finding was in an instrument — a test, a written
> list, a checklist rule, or a handoff — and not one of them was in the system those instruments
> watch. That is either very good news about the code or a fact about what this kind of walk can see,
> and §6 argues it is mostly the first.
>
> **The sitting has an arc, and it runs outward.** It began with controls blind to *code*: five
> derivations that read one handler mapping out of eight, so a one-line `permitAll` change could
> create an anonymous unlimited endpoint that three separate coverage tests would call fine. It moved
> to controls blind to their own *subject*: an orphan check that asked whether a path was mounted and
> never whether the policy matched it, and a list of "every documentation path" that was one path
> short. Then to a control blind to *infrastructure*: twelve red behavioural tests that were a billing
> problem. And it ended outside the code entirely — a Definition of Done whose rule **denied the state
> it needed to record**, and seven handoffs that carried an item forward without once re-reading it.
>
> **By the end the walk was auditing its own bookkeeping, and finding things there too.** That is
> where it stops: not because the audit is complete, but because everything still open needs the
> network, a scanner, or money.
>
> **Twenty-three plants, all red. Two of them found defects in tests written earlier the same
> sitting** — §5 — which is the strongest evidence available that the rest were not shaped to pass.

| The five | Closes |
|---|---|
| [the one mapping out of eight](./2026-09-13-the-one-mapping-out-of-eight.md) | G33, G35, G36 |
| [the branch that proved nothing](./2026-09-13-the-branch-that-proved-nothing.md) | G37 |
| [the list that was one short](./2026-09-13-the-list-that-was-one-short.md) | G38 |
| [G32 was never a decision](./2026-09-13-g32-was-never-a-decision.md) | G32 — answered |
| [the third state](./2026-09-13-the-third-state.md) | G32 — ruled and recorded |

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **42 ahead of `origin/main`**, 40 ahead of `origin/dev`. This sitting is **`dfc73db..68e97b0`** |
| CI | **has seen none of it.** Fourteen sessions |
| Backend | **1054 tests, 0 failed, 120 classes** — was 1036 / 118 at the start of the sitting |
| Frontend | **82 tests**, untouched and not run throughout |
| Production code | **unchanged. Zero files under `backend/src/main` or `frontend/src`** |
| New classes | `FilterOrderTest`, `MappedSurfaceTest`, `ResourceSurface` |
| Migrations | **`V10`**, unchanged. No new ADR — deliberately, [see the fifth handoff][third] §4 |
| Level 3 | **Recorded unavailable.** Ruling of 2026-09-13 |
| Issues | [#17] and [#15] open, untouched. No model was called — none could be |
| Gates | `make check-docs` green. **No new `make check-*` target**, for the fourth sitting |
| Phase 11 | **62 ticked, 10 open** — unchanged from start to finish, and §6 says why that is right |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[third]: ./2026-09-13-the-third-state.md

---

## 2. The derivation-scope thread, G33 → G38

Each of these came out of closing the one before it. That is worth noticing as a shape: **a control
you have just fixed is the best available place to look for the next defect**, because you have only
then understood what it was actually asserting.

| Gap | The claim that was not true | What it cost |
|---|---|---|
| **G33** | Five filter orderings were held by `@Order` arithmetic that nothing reconciled | A tie orders arbitrarily. `RateLimitFilter` at `HIGHEST_PRECEDENCE` **ran correctly anyway** in the plant — a position-only test passes on the lucky run |
| **G35** | "The derived controls are scoped to `dev.reception`, costing three springdoc paths and `/error`" | True, and not the boundary: all five derivations read **one handler mapping out of eight**. The actuator mounts `GET /actuator` **even with exposure set to the empty string** |
| **G36** | "A methodless mapping is invisible … harmless today, because `/error` is a forward target" | Harmless rests on a `permitAll` list one line can change. §2.1 |
| **G37** | The orphan check's resource branch proved a policy still guarded the assets | It probed that *something was mounted at a path*. It never asked whether the policy **matched** it |
| **G38** | A list of "the documentation paths an anonymous caller can actually reach" | One short. `/swagger-ui/swagger-initializer.js` is served, anonymous, and was never on it |

### 2.1 The measurement that carries the whole thread

Add `/error` to `SecurityConfig`'s `permitAll`. One line. You now have an anonymous, unlimited,
unclassified endpoint.

| Test | Its headline claim | Result |
|---|---|---|
| `RateLimitCoverageTest` | *every endpoint an anonymous caller can reach is covered by a policy* | **PASSED** |
| `EndpointCoverageTest` | *every endpoint the application maps has been classified* | **PASSED** |
| `PublicSurfaceSweepTest` | *every public endpoint has a probe written for it* | **PASSED** |

Three derived controls, three true-sounding sentences, and nothing anywhere that could say a word
about it. **A control cannot report its own blind spot, because the blind spot is exactly where it
reports nothing** — so the union of what a set of controls steps over has to be written from outside
all of them, which is what `MappedSurfaceTest` now is.

The same shape held for G37: repoint `api-docs-ui` at `/nonsense/**` and the swagger-ui assets go
unlimited while every test in `RateLimitCoverageTest` stays green. **The system was protected — by
`ApiDocumentationExposureTest`, driving real requests — and the control that claims to do it was
not.** That distinction is most of what this thread found.

---

## 3. G32, and the state the scope document denied

G32 was a different animal: not a control that could not see, but an item **nobody had examined**.

Carried as *the principal's call* through seven handoffs. One attempt, costing nothing, established
there was no call to make:

> `429 Too Many Requests` — *"You have no credits remaining."* `insufficient_quota`,
> `credit_balance_exhausted`

The dates make it worse. The corpus last ran green on **2026-09-10**; the credits ran out on
**2026-09-11**, *during* the experiment that shipped the first of two system prompt changes; the
second landed **2026-09-13**. **The corpus could never have been run against either.** G32 was born
blocked.

And the blocker was in the repository the whole time — `07-mvp-scope.md` under [#17]'s *Status* from
2026-09-11, and phase 11's §8 note. Seven handoffs listed G32 and *"credits for [#17]"* in the same
numbered list without reading one against the other.

**Two consequences, both recorded.**

- **The instrument reported a billing failure as twelve behavioural regressions.** All twelve tests
  failed at the same line; the run said *"12 tests completed, 12 failed"* with the word quota nowhere
  in it — run, as this corpus is, immediately after a prompt change. It now aborts once, quotes the
  provider verbatim, and says a skip is **not** a pass.
- **`07-mvp-scope.md` said "There is no third state."** There is: a box can be *unverifiable*. The
  document that insisted on two had nowhere to put this one. `Gates that cannot be run` is the new
  section; the level-3 entry is its first.

---

## 4. The seventeen traps, collected

T124–T140, grouped by what they are actually about rather than by which handoff found them.

**A control that cannot see reports the same thing as a healthy system**

- **T124** — a control cannot report its own blind spot. §2.1.
- **T125** — *"serves nothing"* and *"could not be asked"* are the same value and opposite facts. Any
  derivation summarising something it may not understand must fail on the unfamiliar case.
- **T130** — *"not a 404"* is not *"is served"*, and the gap between them is every refusal the
  application can make.
- **T137** — an infrastructure failure inside a behavioural suite reads as a behavioural finding, and
  most strongly at the moment the suite is run: just after the change it exists to evaluate.

**A claim that is checked in the wrong place, or not where it is made**

- **T127** — an ordering assertion on position alone passes on the lucky run; where order is a number,
  assert the number.
- **T128** — a conditional bean makes a question about it *unanswerable*, not merely unasserted, and a
  green build cannot tell you which.
- **T129** — an exemption keyed by name checks the key, not the claim. When an exemption is a pair,
  assert the pair.
- **T131** — the cheaper mistake is often the invisible one. Repointing a policy was caught elsewhere;
  *deleting* it was caught by nothing.
- **T136** — a guard written for the case somebody hit does not cover the class it belongs to.

**Instruments and the people who trust them**

- **T126** — a defensive branch reached only from a narrow caller is unreachable, and reviews as a
  control. Mine was.
- **T132** — a control that has just caught something reads as trustworthy, and finding something is
  evidence about the bug, not about the control.
- **T133** — a helper that *reads* a value survives being copied; one that *constructs* a value does
  not. Five copies of `patternsOf` cost nothing; two path generators would be two answers.
- **T134** — asserting inside a loop reports the first failure and hides the size of the problem.

**Bookkeeping, which is also an instrument**

- **T135** — an item carried as a decision may be a blocker nobody tried. Try the thing before
  escalating the decision about the thing.
- **T138** — a rule that denies a state has nowhere to record it. A checklist's exhaustiveness claim
  is itself a claim, and it can be wrong.
- **T139** — two facts in one list are not connected by being in one list.
- **T140** — carrying an item forward is not re-reading it, and the difference is invisible from
  inside. **A carried item should periodically be re-derived from the repository rather than from the
  previous handoff.**

T139 and T140 are the ones to act on, because they are about this document's own genre.

---

## 5. Every plant, and the two that mattered most

**Twenty-three plants across the sitting, every one red.** The full tables are in the five handoffs.
Two are worth repeating here because they were aimed at tests written hours earlier:

- **`surfaceOf()`'s refusal to guess was unreachable.** It fails rather than treating an unreadable
  `HandlerMapping` as an empty one — and its caller only asked about mappings *expected* to be empty,
  which is the one set that can never contain an unfamiliar shape. Dead branch, dressed as a control,
  written by me and caught by a plant an hour later. **T126.**
- **A positive control accepted a `401` as evidence that an asset exists.** Written as *"not a 404"*,
  it passed against a generator emitting `/swagger-ui*/index.html` — the literal asterisk — because
  the security chain refuses that with `401`. **T130.**

A third, smaller: a run broke mid-plant with the application answering `404` to everything, and the
`MappedSurfaceTest` control fired for exactly the right reason before any plant did.

---

## 6. What this sitting did not do

**It moved no phase-11 box**, and that is the correct outcome rather than a shortfall: the ten open
boxes are a clean clone, the E2E stack, the demo script, the concurrency run, the secret scan and the
sign-off — none of which a test-derivation audit can touch.

**It changed no production code**, across eleven commits. Read one way that is reassurance about the
system. Read honestly it is also a statement about scope: **an audit of instruments finds defects in
instruments.** The three findings in §2 are real and each would have let a real regression through
silently, but none of them was a live defect in the running system, and nobody should read this
sitting as evidence that the application is clean.

**It called no model** — none could be called — so nothing here says anything about Receptionist
behaviour since the prompt changed. That is G32's entry in `Gates that cannot be run`, not a gap this
sitting closed.

---

## 7. Next steps, in order

1. **Push, and open a pull request.** Forty-two commits, fourteen sessions, no CI. Deferred ten times
   and the cost of every other item on this list is lower afterwards.
2. **Try the E2E stack on an unmetered connection.** It unblocks eight of the ten remaining phase-11
   items, and `make check-access-log` has still never executed anywhere.
3. **The full-history secret scan.** Needs a scanner installed.
4. **The end-of-phase gates**, which want the stack from item 2.
5. **If credits are ever added**: run the level-3 corpus, close **G39**, and move the level-3 entry
   out of *Gates that cannot be run*. [#17]'s open arm is the same run — **T139, and it applies to
   this list too.**
6. **The principal's**: G29, G30, G31; [#15]'s title.

**Everything open needs the network, a scanner, or money.** No item on this list can be advanced at
this keyboard, which has not been true since the walk began.

---

## 8. Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**.

**Closed this sitting: G32, G33, G35, G36, G37, G38.**

**G39 is the only one opened and still open.** *The level-3 corpus has never been shown to go red for
a behavioural reason since the abort was added.* It cannot be demonstrated without credits, and it is
named in the scope document as part of what running the gate again must confirm.

---

## 9. Confidence

**High on the three findings in §2**, all of which are test runs on either side of a change rather
than arguments about what code would do.

**High on the new tests**, at twenty-three plants with two of them landing on the tests themselves.

**Certain on G32's answer.** The provider's words are quoted from the run.

**Medium, and stated in each handoff, on three things**: `sampleUnder()` handles the two wildcard
shapes the registered patterns contain and would mis-generate an unfamiliar one (guarded by a `200`
probe, not proven); `MappedSurfaceTest` asserts the excluded surfaces are *what they were* and says
nothing about whether accepting them was ever right; and **nothing here shows the level-3 corpus can
still go red for a behavioural reason**, because no live model answered.

**Unchanged and unhappy on the deployed picture.** Fourteen sessions, no CI, and this sitting added
eleven commits to a branch that has never been built by anything but this machine.
