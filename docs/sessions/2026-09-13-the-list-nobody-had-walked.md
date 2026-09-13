# Session handoff — 2026-09-13 — the list nobody had walked

> **The Definition of Done went from 0 of 30 to 26 of 30 in one sitting, and almost none of it was
> new work.** The list was audited on 2026-09-11 — which corrected its wording and recorded that
> nothing had ever been ticked — and then left. Nobody had gone through it in eleven phases.
> **Twenty-four rows already had evidence and were waiting only for somebody to look.**
>
> **No row was ticked by reading.** Each names the test that carries it, and two were *measured*
> during the walk rather than cited: `.env.example` by comparing every `${VAR}` placeholder in
> `application*.yml` and the three compose files against its keys — **35 used, 35 documented, none
> missing** — and the full-history secret scan by running it: **239 non-merge commits, control
> assertion green, no finding.**
>
> **Then the seven commits were pushed and CI went green on all six jobs**, including **`Pipeline
> parity` on its first ever run on GitHub**. That ticked the CI row.
>
> **And the clean-checkout row was closed by doing it** — a fresh clone of `origin/dev`, its own
> volumes, its own images, torn down afterwards. It surfaced **two gaps, both real**: `make seed`
> shelled out to the host's Gradle with no JDK guard and failed with the bare string **`25.0.4.1`**,
> and `docker-compose.yml` hardcodes `name: reception`, so **a clean clone silently joins a stack
> already running on the machine.**
>
> **G46 is fixed in the same sitting**, and not where it was found: `check-java` guards **`test`,
> `migrate` and `seed`** — all three shell out to the host's Gradle and all three failed identically.
> Proven in four states, including the two targets now stopping *at the guard* rather than at Gradle.
>
> **The four rows still open are all the same blocker.** Three Receptionist behaviour rows and the
> README demo script, all credit-bound, all carried. **Every box that can be closed without a funded
> key is now closed.** Phase 11 is **67 of 72**.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[prev]: ./2026-09-13-the-instrument-that-could-not-see-the-resolver.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/dev` | **`82fc678`** — the seven commits are pushed. Nothing is unpushed |
| CI | **Green on all six jobs.** Backend, Frontend, Documentation consistency, Compose smoke test, End-to-end, **Pipeline parity** |
| MVP Definition of Done | **26 of 30.** Was 0 of 30 this morning, and has been 0 since `aa84b19` |
| Phase 11 | **67 of 72** — 62 at the start of the day |
| Backend | 1063 tests, 0 failed, **and now green on a runner rather than a laptop** |
| Issues | [#15] and [#17] open, both commented this sitting. Still credit-blocked |
| Credits | **Still none.** The only thing standing between this list and 30 of 30 |

---

## 2. The walk — 24 ticked by looking, not by working

The 2026-09-11 audit's own words were that *"the list had survived ten phases unread."* It stayed
unread for one more. What the walk actually found is that **the list was carrying far less risk than
its zero suggested**.

The evidence was frequently *stronger* than the row asked for:

| Row | What actually carries it |
|---|---|
| two concurrent bookings → one appointment, one `409` | `ConcurrentBookingTest`: **twenty** threads, one 201, nineteen 409, exactly one row |
| availability excludes … | `SlotBookabilityTest` closes it **both ways** — every slot offered is bookable, *and* every start not offered is refused with a reason |
| public endpoints are rate limited | `RateLimitCoverageTest` proves **every** anonymous-reachable endpoint has a policy, the surface is derived rather than empty, and every exemption carries a reason |
| tenant-scoped endpoints `404` | `TenantIsolationSweepTest`'s first assertion is that every catalogued collection **has a control registered**, so the sweep cannot pass by covering nothing |

**Where a row is only partly proven it says so in place**, rather than ticking on the half that
works. The reschedule row's **ownership half is ticked in its own text** while the date half stays
with [#17]. The conversational-booking row records that the E2E's Receptionist is the **fake provider
behind the base URL**, so what is ticked is that the path books — not that the model chooses
correctly, which is the next three rows and they are not ticked.

---

## 3. The clean-checkout walk, and it was not a formality

A fresh `git clone` of `origin/dev` at `82fc678` into a scratch directory — **not** this working copy,
which is the whole point of the row.

| Step | Result |
|---|---|
| `make up` | `.env` created from `.env.example`; Postgres, Mailpit, Caddy up on **fresh** volumes |
| `make up-all` | backend and frontend built **from the clone's own context**; five containers healthy |
| `make seed` | **failed** — see §4 |
| `make seed`, with `JAVA_HOME` at 21 | the two demo tenants, 7 services, 5 employees, 39 appointments |
| `/api/health` | `{"status":"UP","components":{"database":"UP","mail":"UP"}}` |
| `/book/salon-aria` | `200`, and the public API served the business, its services and its prices |

**What proves the clone was actually clean**: its database held **exactly the two demo tenants**. The
machine's own volume has five — including `Phase 06 Scratch`, kept deliberately. Had the walk been
contaminated, the extra three would have been there.

**And it could not reuse an image built here**: `docker-compose.apps.yml` declares no `image:`, so
compose names images `<project>-<service>`. The clone built its own.

Torn down with `-v` afterwards; the pre-existing volumes were untouched and verified present, and the
machine's stack was brought back up with all five tenants intact.

---

## 4. G46 — `make seed` had no JDK guard, and failed with a version number. **Fixed**

`make up` and `make up-all` are containerised and carry their own JDK. **`make seed` is not**: it
shells out to the host's Gradle.

```
* What went wrong:
25.0.4.1
```

That is the entire message. It names neither Java, nor the version required, nor `JAVA_HOME`. The
README does list **JDK 21** under *Prerequisites*, so a stranger with exactly 21 is fine — but 21 is
no longer what a machine defaults to, and the failure a newer JDK produces points nowhere. It cost
this session a cycle and it is written down in [prev] and in three earlier handoffs as a thing
everybody here already knows, which is the tell.

**It is also an inconsistency**: under `make up-all` every part of the system runs in a container
**except seeding it**.

### 4.1 The guard, and where it went

**Not on `seed`.** Three targets shell out to the host's Gradle — **`test`, `migrate` and `seed`** —
and all three failed the same way; fixing the one that happened to be noticed would have left the
other two to be noticed later. `check-java` is a prerequisite of all three.

It reads the JDK that would *launch* Gradle — `$JAVA_HOME/bin/java` when `JAVA_HOME` is set, `java`
otherwise, which is the wrapper's own rule — and refuses outside **21–24**:

```
Java 25.0.4.1 will not build this project.
  found     25.0.4.1, at java
  needed    JDK 21 (Gradle 8.14 runs on 21 to 24; the build targets 21)
  why       Gradle will not start on 25 and reports only the version string
On macOS:
    export JAVA_HOME=$(/usr/libexec/java_home -v 21)
Containers are unaffected: 'make up-all' carries its own JDK.
```

**The ceiling is the wrapper's, and it is derived rather than preferred.** `gradle-wrapper.properties`
pins 8.14, which does not run on 25 — that is the refusal, and it is a *launch* failure, not a
toolchain one. `build.gradle.kts` asks for language level 21 and Gradle's toolchain support would
find a 21 to compile with; it never gets that far. The two are different questions and the comment
above the constants says so, because moving the wrapper has to move this number.

**Proven in four states**, not one:

| | |
|---|---|
| JDK 25 | fails, with the message above |
| JDK 21 | passes |
| `JAVA_HOME` pointing at nothing | fails, and says *which* path it tried and that `JAVA_HOME` is what set it |
| `make seed` / `make test` on 25 | stop **at the guard** — no container started, Gradle never reached |

`make check-pipeline` is still green, which had to be checked because `test` gained a prerequisite
and that file derives what `test` runs. The README now says which targets need a host JDK and which
do not — the containerised ones never did.

---

## 5. G47 — a clean clone silently joins a stack already running

`docker-compose.yml:12` is `name: reception`, hardcoded. So the compose project does not depend on
the directory, and two checkouts of this repository **cannot run side by side**: the second does not
start a second system, it **recreates the first one against its own config, on the first one's
volumes.**

This was caught before running anything, because the machine had the stack up from the working copy
with five tenants in it. Had it not been caught, the walk would have run against an already-migrated,
already-populated database and **reported a green that meant nothing** — the exact class of false
result this repository keeps cataloguing.

The walk was therefore run with `COMPOSE_PROJECT_NAME=reception-clean`, which the Makefile itself
already does for the E2E topology. **That deviation is the finding**: the documented command could
not be used verbatim, on this machine, safely.

It is recorded here rather than filed, by the principal's call.

---

## 6. Traps

**T165 — a checklist nobody walks is not a low-risk checklist, it is an unknown one.** Thirty
unticked boxes read as thirty pieces of outstanding work and were, in fact, twenty-four pieces of
completed work and a filing job. The cost of *not* walking it was not the work; it was eleven phases
of not knowing which the six were.

**T166 — a status message that is only a version number is a dead end.** `25.0.4.1` is true,
accurate, and useless. A failure that names the value and not the expectation makes the reader guess
which of the two is wrong.

**T167 — a fixed compose project name makes a second checkout dangerous rather than merely
inconvenient.** It does not collide and fail; it succeeds, against the other checkout's data. A
verification run is exactly when somebody has two checkouts.

---

## 7. Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**, **G39**, **G40**, **G44**, **G45**.

**G43 is closed by circumstance, not by decision** — the push happened and `Pipeline parity` ran
green. Whether it *blocks a merge* is still branch protection, still outside the repository, still
the principal's.

**G46 is closed**, in the sitting that opened it. `check-java` reads the JDK that would *launch*
Gradle — `$JAVA_HOME/bin/java` when set, `java` otherwise — and refuses outside 21–24, naming the
version found, the version needed, the reason, and the `JAVA_HOME` line that fixes it. It guards
**`test`, `migrate` and `seed`**, not only `seed`: all three shell out to the host's Gradle and all
three failed the same way. Proven in four states — JDK 25 (fails), JDK 21 (passes), a `JAVA_HOME`
pointing at nothing (fails, and says which), and the targets themselves stopping at the guard rather
than at Gradle. `make check-pipeline` is still green, which matters because `test` gained a
prerequisite.

**G47 is new and open.** `docker-compose.yml`'s hardcoded `name: reception`. §5.

---

## 8. Confidence

**Certain on the 26.** Every ticked row names its evidence, and the two that were measured rather
than cited are reproducible in one command each.

**Certain on the clean-checkout walk.** It ran, the endpoints answered, and the two-tenant database is
what rules out contamination.

**Certain CI is green** on `82fc678`, all six jobs, read off the run rather than inferred.

**Explicitly unproven: anything about Receptionist behaviour.** No model was called this sitting
either. The four open rows are open for that reason and no other.

**Explicitly narrow: the clean-checkout walk needed two deviations**, G46 and G47. The row is ticked
because the system came up and worked; the deviations are recorded because the *documented commands*
did not both work as written.

---

## 9. What is the principal's

1. **Credits.** Seventh handoff. It is now the **only** thing between this project and 30 of 30 —
   every other box is closed. Roughly 40 minutes of runs: [#15]'s re-baseline, [#17]'s cut-short arm,
   and the level-3 corpus.
2. ~~**G46**~~ — done. The guard is in, and the README says which targets need a host JDK and which
   do not.
3. **G47** — whether `name: reception` should move, given it makes a second checkout act on the
   first's data.
4. **G43** — branch protection, unchanged.
