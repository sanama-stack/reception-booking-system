# Session handoff — 2026-09-13 — the one mapping out of eight

> **Purpose.** **G33**, **G35** and **G36** — the three derivation-scope gaps the last handoff called
> *"doable at a keyboard, none urgent."* Two of them turned out to be one gap described from two
> angles, and it is wider than either description.
>
> **G35 said the derived controls are scoped to `dev.reception`, and that the cost is three springdoc
> paths and `/error`.** That is true and it is not the boundary. Every derived control in this
> repository reads `RequestMappingHandlerMapping` — and **this application builds eight handler
> mappings.** A resource handler holds the swagger-ui assets. The actuator holds `GET /actuator`,
> mapped **even though `management.endpoints.web.exposure.include` is set to the empty string**,
> because the links document is registered independently of what it has to link to. A
> `RouterFunction` bean would hold a third set. None of them is filtered out by anything: no control
> looks in the object that holds them.
>
> **The demonstration is the part worth reading.** Add `/error` to `permitAll` — one line — and you
> have an anonymous, unlimited, unclassified endpoint. `RateLimitCoverageTest`,
> `EndpointCoverageTest` and `PublicSurfaceSweepTest` **all stay green.** That was measured, not
> argued, and it is G36 stated as a consequence rather than as a property.
>
> **G33 was the smaller and cleaner of the three.** Five orderings this application depends on lived
> in `@Order` arithmetic that nothing reconciled. Each is now pinned twice — by position *and by
> order value* — because filters tied on order are sequenced arbitrarily, and **a position-only
> assertion passes on the lucky run.** Shown red against exactly that: a tie that ran in the correct
> order anyway.
>
> **A plant on my own test found a dead branch in it.** `surfaceOf()` refuses to treat an unreadable
> mapping as an empty one — and, called only for the mappings expected to be empty, that refusal was
> unreachable. §5.3.
>
> **Two tests, no new `make check-*` target** — §4's question, answered the way the last seven
> sessions have answered it.
>
> **Committed, not pushed.** Two commits plus this file, leaving `dev` **33 ahead of `origin/main`**
> and 31 ahead of `origin/dev`. 1051 backend tests, 0 failed. Phase 11 still at **62 of 72**.

[prev]: ./2026-09-13-the-twenty-that-belonged-somewhere-else.md
[previous]: ./2026-09-13-the-twenty-that-belonged-somewhere-else.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`6d01bf8`**, unmoved. Still no pull request |
| `dev` | **33 ahead of `origin/main`**, 31 ahead of `origin/dev`. The work is **`b1c7aa5`** and **`3f14770`** |
| CI | **has still seen none of it.** Ten sessions |
| Backend | **1051 tests, 0 failed, 120 classes** — was 1036 / 118 |
| Frontend | **82 tests**, untouched and not run |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. **No model was called** — **G32 is still unanswered** |
| Gates | `make check-docs` green. **No new gate**, deliberately — §4 |
| Phase 11 | **62 ticked, 10 open**, unchanged. **G33, G35 and G36 closed** |
| E2E stack | **down, and possibly recoverable.** Not attempted — [the previous handoff][prev] §6 is unchanged |

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

Production code changed in **no file**, for the fourth session running. Both commits are tests plus
the two paragraphs of §15 they make necessary.

---

## 2. The eight handler mappings

Derived from the running context, not read from configuration:

| Handler mapping | Holds | Who was looking |
|---|---|---|
| `requestMappingHandlerMapping` | 71 handler methods | **all five derived controls, and only this** |
| `resourceHandlerMapping` | 2 swagger-ui patterns | one written list in `RateLimitCoverageTest` |
| `webEndpointServletHandlerMapping` | **`GET /actuator`** | **nothing** |
| `controllerEndpointHandlerMapping` | nothing | — |
| `routerFunctionMapping` | nothing | — |
| `beanNameHandlerMapping` | nothing | — |
| `welcomePageHandlerMapping` | nothing | — |
| `welcomePageNotAcceptableHandlerMapping` | nothing | — |

Five of the seven hold nothing today, and **they are now asserted to hold nothing rather than
described as empty.** That is the difference between a list and a control: *"there is no
`RouterFunction` bean"* is a true sentence about today that a reader cannot check and nothing
maintains, and the assertion fails on the commit that adds one. Both were planted; §5.

### 2.1 The actuator, which is switched off and mounted anyway

`application.yml` sets `management.endpoints.web.exposure.include: ""` and disables the health
endpoint outright, under the comment *"Actuator is not exposed through the public origin."* Both
true. `GET /actuator` is mapped regardless — the links mapping is registered whether or not it has
anything to link to.

It is refused by the security chain, so this is not a hole. It is recorded because **"actuator is
switched off" and "nothing is mounted" read as the same sentence and are not one**, and because the
mapping sits in the one place nothing in this repository looks.

### 2.2 What G35's own wording missed, and what it did not

The gap was written as *"three springdoc paths and `/error`."* The three is right about the **public**
springdoc paths, which is what §15 says and what it was drawn from. The full framework surface inside
`RequestMappingHandlerMapping` is **five signatures** — `/openapi.yaml` is mapped and deliberately
*not* public — and the surface outside that one mapping was not in the count at all. Shorthand in a
gap register rather than an error in the document.

---

## 3. `/error`, and why the exclusion is a consequence rather than a property

G36 was recorded as *"a mapping with no HTTP method condition is invisible to these derivations …
harmless today, because `/error` is a forward target and should not carry a limit."*

The mechanism is exact. Every one of the five derivations does this:

```java
for (String pattern : patternsOf(info)) {
    info.getMethodsCondition().getMethods().forEach(method -> endpoints.add(...));
}
```

An empty methods condition is an empty loop. The endpoint is **not filtered out — it never
arrives**, and so is never reported as excluded either.

"Harmless today" was the right verdict and the wrong kind of claim: it rests on a `permitAll` list
that one line can change. So what is asserted is not that `/error` is a forward target but that
**nothing invisible to the derivations is reachable anonymously** — the property that makes the
invisibility survivable, checked against the same `AuthorizationManager` the running application
decides with.

### 3.1 The measurement

Planted `/error` into `SecurityConfig`'s `permitAll` list and ran the three controls that exist to
catch exactly this:

| Test | Its headline claim | Result over a public, unlimited, unclassified `/error` |
|---|---|---|
| `RateLimitCoverageTest` | *every endpoint an anonymous caller can reach is covered by a policy* | **PASSED** |
| `EndpointCoverageTest` | *every endpoint the application maps has been classified* | **PASSED** |
| `PublicSurfaceSweepTest` | *every public endpoint has a probe written for it* | **PASSED** |

Three derived controls, three true-sounding sentences, and a new public endpoint none of them can
say anything about. `MappedSurfaceTest` is the only thing that fails.

---

## 4. No new gate, and the question that is now seven sessions old

The standing shape, unchanged and still without a counter-example:

> **Derive it in a test where you can; probe the running system where you cannot; read configuration
> only when neither is possible.**

Both classes here are the first category, and emphatically so: the filter chains and the handler
mappings are *objects*, and the truth can be asked for rather than parsed. Teaching
`docs/tools/consistency` to read `@Order` annotations out of Java source would replace a derivation
with a regular expression whose failure mode is silence.

The count stands at **six `make check-*` targets and no new one for four sessions.** Nine data
points. Still the principal's to rule on.

---

## 5. Every plant

| # | Plant | Result |
|---|---|---|
| 1 | `RateLimitFilter` moved to `HIGHEST_PRECEDENCE`, tying it with `ForwardedHeaderFilter` | **Red** — §5.1 |
| 2 | `JsonOnlyWriteFilter` moved behind the security chain | **Red**, naming `FormPostRejectionTest`'s premise |
| 3 | The two `addFilterAfter` calls in `SecurityConfig` swapped | **Red** on the security chain |
| 4 | Rate limiting switched off, so the pinned filter is **absent** | **Red** — §5.2 |
| 5 | A methodless `@RequestMapping` added to one of our controllers | **Red** on two tests |
| 6 | `/error` added to `permitAll` | **Red** — and §3.1 |
| 7 | Actuator exposure widened to `health,info` | **Red** on two tests; it also adds a **ninth** handler mapping |
| 8 | `spring.web.resources.add-mappings` flipped to `true` | **Red** on the resource surface |
| 9 | A `RouterFunction` bean added | **Red** — the mapping that was empty no longer is |
| 10 | A `HandlerMapping` bean of an unfamiliar shape | **See §5.3** |

### 5.1 The tie that ran in the right order anyway

Plant 1 is the defect [two handoffs ago][prev] named as a class: an edit that orders
`RateLimitFilter` at `HIGHEST_PRECEDENCE` — the obvious instinct for a filter that must precede
authentication — closes the margin of ten to nothing.

The chain it produced was **still correct**:

```
  -2147483648  forwardedHeaderFilter
  -2147483648  requestIdFilter
  -2147483648  rateLimitFilter        <- tied, and sequenced correctly this run
```

A test asserting position would have passed, and gone on passing until the arrangement came up the
other way. That is why every pair is asserted twice, and the order-value assertion is the one doing
the work.

### 5.2 An absent filter must not score zero violations

Plant 4 removed `RateLimitFilter` from the chain entirely — it is `@ConditionalOnProperty` and most
of the suite runs with it off. `positionOf` throws rather than returning nothing, so the pin fails
loudly with the chain printed. Without that, four of the five pins would have been silently
unchecked in any profile where the bean is absent, which is **most of this suite**.

Worth stating generally: **a conditional bean makes an ordering question unanswerable rather than
merely unasserted**, and the two look identical from a green build.

### 5.3 The plant that found a dead branch in my own test

`surfaceOf()` refuses to treat a `HandlerMapping` it cannot read as an empty one, because *"serves
nothing"* and *"could not be asked"* are the same value and opposite facts. Plant 10 added a
`HandlerMapping` of an unfamiliar shape — and **the refusal never fired**. The caller only asked
about the mappings it expected to be empty, and a *new* mapping is the only kind that can be of an
unfamiliar shape, so the branch was unreachable by construction.

The caller now reads every mapping. Replanted, it fires. **A defensive branch reached only from a
narrow caller is decoration**, and it looks exactly like a control in review.

---

## 6. Every open item

### 6.1 Committed, not pushed

**Thirty-three commits, ten sessions, no CI.**

### 6.2 Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**.

**G33, G35 and G36 are closed.** §2, §3.

**G37 is new and is small.** *The resource-handler policy list is still written.*
`RateLimitCoverageTest` names `api-docs-ui → /swagger-ui/index.html` by hand and probes that the path
resolves. The *set* of resource patterns is now derived and pinned, so a new resource root fails the
build — but which policy guards which asset is still a sentence somebody typed. Narrower than G35
was, and left open deliberately: there is one entry.

**G32 is unchanged and is the oldest unanswered question in the project.** The system prompt changed
five sessions ago; the level-3 corpus has not been run against it. Listed as the principal's call in
five consecutive handoffs. **If the answer is "not now", that is a fine answer and should be recorded
as a decision rather than carried as a gap.**

### 6.3 Carried

Unchanged from [the previous handoff][prev] §7.3.

### 6.4 The E2E stack

Unchanged from [the previous handoff][prev] §6, which is still the current picture: Docker up,
infrastructure containers healthy, application containers absent, blocker is the metered link rather
than feasibility. Not attempted.

---

## 7. Next steps, in order

1. **Push, and open a pull request.** Thirty-three commits, ten sessions. Deferred six times.
2. **Try the E2E stack on an unmetered connection.** It unblocks eight of the ten remaining phase-11
   items, and `make check-access-log` has still never executed anywhere.
3. **Answer G32**, in either direction.
4. **The full-history secret scan.** Needs a scanner installed.
5. **The end-of-phase gates**, which want the stack from item 2.
6. **The principal's**: **G32**, G29, G30, G31, G37; credits for [#17]; [#15]'s title.

There is nothing left on the derivation-scope list. Every remaining item needs the network, a
scanner, or a decision.

---

## 8. Traps

- **T124 — a control cannot report its own blind spot**, because the blind spot is exactly where it
  reports nothing. Three tests stayed green over a public unlimited endpoint they each claim to
  cover. The union of what a set of controls steps over has to be written from outside all of them.
  §3.1.
- **T125 — "serves nothing" and "could not be asked" are the same value and opposite facts.** Any
  derivation that summarises a thing it might not understand must fail on the unfamiliar case rather
  than return the empty one. §5.3.
- **T126 — a defensive branch reached only from a narrow caller is unreachable, and reviews as a
  control.** Mine was. Plant the case the branch exists for, and check the branch actually ran. §5.3.
- **T127 — an ordering assertion on position alone passes on the lucky run.** Where order is a
  number, assert the number too; a tie is sequenced arbitrarily and will be correct roughly half the
  time. §5.1.
- **T128 — a conditional bean makes a question about it unanswerable, not merely unasserted**, and a
  green build cannot tell you which. §5.2.
- Carried and re-confirmed: **T89** (fifth time — the empty-set family, and T124 is its
  control-shaped cousin), **T104**/**T118**, **T105**, **T109**, **T113**, **T116**, **T120**,
  **T122**, **T69**, **T70**.

---

## 9. Commands

```bash
# The backend suite. JAVA_HOME is not optional here — see T69.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline
```

```bash
# This session's two classes.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.config.FilterOrderTest' --tests 'dev.reception.common.web.MappedSurfaceTest'
```

```bash
# §3.1, reproducible in one line: add "/error" to SecurityConfig's permitAll list, then watch the
# three controls that exist to catch it stay green.
cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home ./gradlew test --offline --rerun-tasks --tests 'dev.reception.common.ratelimit.RateLimitCoverageTest' --tests 'dev.reception.tenancy.EndpointCoverageTest' --tests 'dev.reception.tenancy.PublicSurfaceSweepTest'
```

---

## 10. Confidence

**High on the finding.** The eight handler mappings and their contents were read off the running
context before anything was written, and the `/error` demonstration in §3.1 is three test runs rather
than an argument.

**High on both tests.** Ten plants, every one red, and each one names which assertion it was aimed
at. Plant 10 found a real defect in the instrument, which is the strongest evidence available that
the plants were not shaped to pass.

**Medium on the reach of `MappedSurfaceTest`, and it is worth naming.** It asserts that the framework
surface, the resource surface and the actuator surface are *what they were*, by equality. Equality
against a recorded set catches growth and renaming; it says nothing about whether the recorded set
was ever the right one to accept. The reasons attached to each entry are prose, and prose is what
this walk keeps finding to be wrong.

**Unchanged and unhappy on the deployed picture.** Ten sessions, no CI, and §6.4 is unchanged from
the last handoff — movement in what is *possible*, still none in what has been *done*.
