# Session handoff — 2026-09-13 — the guard that counted weeks

> **The backlog is merged.** Forty-seven commits, fifteen sessions, no CI — that sentence has opened
> every handoff since 2026-09-11 and it is now false. [#35] is squashed into `main` as **`55e5b02`**,
> all five CI jobs green, and `dev` is back in sync. The first thing this session did was the thing
> ten handoffs had deferred.
>
> **The second thing it did was find a defect in `main`, by accident, because CI went red on a change
> that could not have caused it.** `DemoSeedTest`'s eight cases all failed at one line. The commit
> before them had passed eleven hours earlier. Nothing in the diff touched backend code.
>
> **The demo seed fails every Sunday after 11:00 Tbilisi.** Salon Aria cancels an appointment placed
> on the Monday of week +1, behind a **24-hour** Cancellation Window, and placements anchor to Monday
> 00:00 of the week the seed runs in — so seeded on a *Sunday* that Monday is **11 hours** away, not
> days. `CancellationService` refuses it, and the seed dies with an `ApiException` from four frames
> deep. `git clone && make up && make seed` has been broken on Sunday afternoons for as long as the
> fixture has existed, which is the phase-11 box it sits directly underneath.
>
> **`BlueprintCheck` exists to catch exactly this, had a test asserting it worked, and was green the
> whole time.** Its rule was `weekOffset() < 1`, behind a comment claiming next week is *"certainly
> outside a window measured in hours, whatever day the seed runs"*. **A week number cannot answer a
> question asked in hours.** The rule now computes the lead a placement is guaranteed and compares it
> against the tenant's own `cancellationWindowHours` — still clock-free, because the worst case is
> derivable from the blueprint alone.
>
> **The evidence that the old test was hollow is Plant C**: neutering the new rule fails the new
> regression *and* the cancellation test that had been passing all along. That test was green against
> a rule that could not see the defect it was written to prevent.
>
> **Two gates ran for the first time anywhere**: `make check-access-log`, and CI's `End-to-end` and
> `Compose smoke test` jobs — both of which gate on `Backend` and had been skipped every prior cycle.

[#35]: https://github.com/sanama-stack/reception-booking-system/pull/35
[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`55e5b02`** — [#35] squashed, merged 13:11Z. Moved for the first time since 2026-09-12 |
| `origin/dev` | **`16b87e1`** — `main` merged back. **0 behind**, and `git diff origin/main..dev` is empty |
| CI | **Green, all five jobs.** Backend 5m41s · Frontend 1m19s · Docs 4s · **End-to-end 3m49s** · **Compose 2m40s** |
| Backend | **1056 tests, 0 failed, 120 classes** — was 1054; the two new ones are in §3 |
| Frontend | 82 tests, untouched |
| E2E | **2 passed locally** (flow 15.3s, 360 px sweep 26 routes) **and in CI** |
| Production code | **Changed, for the first time in nine sittings** — `BlueprintCheck`, `DemoTenants` |
| Migrations | **`V10`**, unchanged. No new ADR |
| Issues | [#17] and [#15] open, untouched. No model was called — none could be |
| Phase 11 | **62 ticked, 10 open** — unchanged, and §7 says which are now closer |

---

## 2. The push, and the gates that had never run

Pushing was item 1 on the last handoff's list and had been deferred ten times. It cost one secret
scan and one command. **Everything after it in this document is a consequence of having done it.**

Three things ran that had never run anywhere:

- **`make check-access-log`** — passed. Its own control fired first: a PID-stamped request has to
  appear in Caddy's log *with its uri and query intact* before the check's silence about a Manage
  Link token means anything. It did, and neither the path segment nor the query parameter kept a
  token.
- **CI's `End-to-end`** — passed, 3m49s. It `needs: [backend, frontend]`, so every previous red or
  partial cycle skipped it. This is the first CI evidence the E2E suite runs anywhere but this
  machine.
- **CI's `Compose smoke test`** — passed, 2m40s, same reason.

**A caution about the first one, which is §5's trap.** The dev Caddy had started *the same second*
the Caddyfile was written and 37 seconds before the commit that carried the log filters. A `grep`
inside the container reads the bind mount — the host file again, not the configuration in memory —
and the admin API on `:2019` answered nothing. **Neither could say which config was live**, so Caddy
was restarted before the check was trusted. The check defends against a stale log *line*; nothing in
it defended against a stale *Caddy*. That is now written above the target.

---

## 3. G40 — the seed that fails on Sundays

### What happened

CI went red on a push whose diff was a Makefile comment and two E2E TypeScript files. `DemoSeedTest`:
**1054 tests completed, 8 failed**, every one of the eight throwing from `DemoSeedTest.java:204` —
`DemoTenants.all().forEach(seeder::seed)`, the shared setup they all call. Eight failures, one cause.

**"It cannot be mine" is an argument, not evidence.** The decisive test was a worktree at the last
green commit `67e17d5`, run at the current hour: **all eight failed there too**, same exception, same
lines. The variable was the clock.

| Run | Local time (Tbilisi) | Gap to Monday 11:00 | Result |
|---|---|---|---|
| `67e17d5` | 05:17 | **29h43m** — outside the window | green |
| `b0d3343` | 16:40 | **18h20m** — inside it | **red** |

The crossover is **Sunday 11:00 local**. Before it CI is green; after it, red. Every green obtained
on a Sunday morning was partly luck.

### The mechanism

The seed does not write a `CANCELLED` row. It **cancels an appointment through the real
`CancellationService`**, deliberately — which is what makes the demo's cancelled appointment a real
one rather than a row arranged to look like one. `CancellationService` enforces the Cancellation
Window. Salon Aria's fixture was:

```java
appointment(1, MONDAY, at(11, 0), "Giorgi Tsiklauri", "Blow-dry", "Keti Lomidze", DASHBOARD, CANCELLED_BY_CUSTOMER)
```

`weekOffset 1` is *Monday 00:00 of the seed's own week, plus a week*. On a Sunday, that is tomorrow.

### The guard, and why it could not see it

`BlueprintCheck` is the class whose entire purpose is catching a fixture the application would
refuse. It had the rule, and the rule was a proxy:

```java
if (outcome == CANCELLED_BY_CUSTOMER && appointment.when().weekOffset() < 1) { … }
```

above a comment asserting that next week or later *"is the only placement that is certainly outside a
window measured in hours, whatever day the seed runs"*. **That sentence is false**, and the fixture it
waved through was this project's own.

The fix asks the question in the unit the window is written in:

```java
long guaranteed = minutesFromAnchor(appointment.when()) - MINUTES_IN_A_WEEK;
long window = tenant.profile().cancellationWindowHours() * 60L;
if (guaranteed <= window) { … }
```

**It stays a pure function of the blueprint**, which is the property that lets it run in CI with no
database, no Spring context and no clock. The worst case needs no clock: placements anchor to Monday
00:00 of the seed's week, so the latest the seed can run inside that week is one week after the
anchor. `<=` rather than `<`, because `isOpenFor` shuts the window **at** the boundary — *"A customer
who is told they have until 24 hours before has until then, not through it."*

The fixture moved Monday → **Wednesday**, the nearest day Giorgi works that is far enough: **59
guaranteed hours instead of 11**.

### Why this is the session's real finding

Not because a demo fixture was wrong. Because **the control written to prevent this class of mistake
was green throughout**, and so was its test. A rule that checks a proxy for its claim passes until
the proxy and the claim disagree — and the day they disagree is not a day anybody chose.

---

## 4. The E2E step that asserted less than its name

`flow.spec.ts` had a step called **`both confirmations arrive, and the Manage Link resolves`**. It
asserted **one**, and one is all there can be: the Receptionist's customer is the fake provider's
constant — a name and a phone and **no email** (`infra/fake-provider/server.js`) — so ADR-0007 sends
nothing for that booking.

The product is correct. **The name claimed something nothing checked**, which is a control that would
have stayed green if the second confirmation ever did break.

It now asserts the real behaviour: that the Receptionist's booking sends **nothing**. Asked by
Confirmation Code rather than by recipient, because the booking under test *has no recipient to ask
about* — which is what `mailContaining()` is for. The helper **throws** rather than returning `[]`
when Mailpit will not answer: an empty list is the value that makes an absence assertion pass, so
returning it on failure would turn every outage into a green.

**The control comes first**, because an empty result is also what an unreachable Mailpit, an empty
mailbox and a search matching nothing all return:

```ts
expect(await mailContaining(request, classicCode), 'the control: …').not.toHaveLength(0);
expect(await mailContaining(request, receptionistCode), 'ADR-0007 sends nothing').toHaveLength(0);
```

---

## 5. Every plant

**Five, all red.**

| # | Plant | Result |
|---|---|---|
| 1 | `customer_email` added to the fake provider's `create_appointment`, making the second confirmation real | Red at **flow.spec.ts:172**, the ADR-0007 line, with its own message — while the control above it passed. `server.js` reverted to `36a227f4` |
| 2 | Salon Aria's fixture put back on MONDAY | Named **before a row is written**: *"MONDAY of week +1 at 11:00 — not far enough ahead: 11 hours in the worst case"*. `DemoTenants` reverted to `dba81146` |
| 3 | The new rule neutered with `false &&` | **The new regression AND the pre-existing cancellation test both fail.** `BlueprintCheck` reverted to `8f769b61` |

Plant 2 is the shape of the improvement: the same defect used to surface as an opaque `ApiException`
four frames deep, mid-seed, on Sundays only. It is now a named blueprint problem, caught before the
first row, on any day of the week.

**Plant 3 is the one that matters.** It failed a test that had been passing for as long as the rule
existed. *The test was green and the rule was wrong* were compatible all along, and only a plant
could tell them apart.

---

## 6. What this session got wrong

Recorded because three of them cost real time and all three are the genre this project collects.

- **A working download read as a hung one.** Playwright's installer writes its zip to a *temp* path,
  so the cache directory stays near-empty during a healthy download. I watched the cache, called it
  stalled, and killed it. **T146.**
- **Verify-then-destroy.** The zip's integrity was checked and *then* the process holding it was
  killed — whose temp directory is cleaned up on exit, taking the verified 136 MB with it. Paid
  twice: the same thing happened to ffmpeg. **T147.**
- **A task notification's exit code read as the test's.** The plant run reported *"completed (exit
  code 0)"* and I took it at face value for a moment; that was the wrapper's `echo` succeeding. The
  log said `1 failed`, `EXIT=1`. **T145** — and it is the same shape as most of what this branch
  fixed.
- **A CI watcher that could settle early.** It exited on `pending == 0`, which was briefly true while
  `End-to-end` and `Compose smoke test` had not yet registered. Rewritten to require all five job
  names before declaring a result. **T148.**

---

## 7. Phase 11, and what is now closer

**No box moved**, deliberately — ticking is the principal's call. But three of the ten are materially
closer than the last handoff recorded:

- *"All security items are implemented or explicitly listed as accepted risks"* — `make
  check-access-log` has now executed and passed, the last security gate with no execution history.
- *"The E2E flow passes in CI"* is already ticked; it is now also **true of the E2E job itself**,
  which had never run.
- *"`git clone && make up && make seed` produces a fully working, populated system"* — **was false on
  Sunday afternoons** and is now not. That box could not have been honestly ticked before §3.

---

## 8. Next steps, in order

1. **The full-history secret scan.** Needs a scanner installed; `gitleaks`, `trufflehog` and
   `ggshield` are all absent. **The network works**, so this is now a `brew install` away — it was
   listed as blocked for four sittings while it was not.
2. **The remaining phase-11 boxes**: the clean clone, the demo script end to end, the concurrency
   run, the sign-off. The E2E topology is proven up, which is what they were waiting on.
3. **If credits are ever added**: the level-3 corpus, closing **G39** and [#17]'s open arm.
4. **The principal's**: G29, G30, G31; [#15]'s title.

---

## 9. Traps

**T141** — a control that checks a *proxy* for its claim is green until the proxy and the claim
disagree, and nothing chooses that day. A week number cannot answer a question asked in hours.

**T142** — a passing test and a correct rule are different claims. Neutering a rule is the only thing
that tells you which one you have; the test that had been green for weeks failed the moment the rule
under it was disabled.

**T143** — a check can defend against stale *data* and not against stale *configuration*. A
bind-mounted config file makes `cat` inside the container read the host file, not what the process
loaded, so the obvious verification proves nothing.

**T144** — a step's name is an assertion that nothing runs. *"Both confirmations arrive"* over a body
checking one would have stayed green if the second ever broke.

**T145** — a runner's exit code describes the wrapper, not the work inside it. A pipeline ending in
`echo` succeeds while the suite inside it fails.

**T146** — during a download, progress is in a temp path and the destination stays near-empty. Check
the *process* (0% CPU with a file that stopped growing), not the directory.

**T147** — killing a stuck process destroys what it already finished, because its temp directory goes
with it. **Copy before you kill.**

**T148** — a watcher that waits for "nothing pending" can settle before the jobs that have not yet
registered exist. Wait for the *names* you expect, not for the absence of pending ones.

**T149** — a red build on your change is not evidence your change caused it. Re-run the last green
commit **at the current time** before believing the diff; time is a variable a diff cannot show.

---

## 10. Gaps

Carried: **G1**, **G3**, **G8**, **G9**, **G10**, **G11**, **G13**, **G14**, **G24**, **G26**,
**G27**, **G29**, **G30**, **G31**, **G39**.

**Closed this sitting: the push and CI backlog** — fifteen sessions of unbuilt commits, now merged and
green.

**G40 is new and open.** *The new cancellation rule's boundary comparison has no plant behind it.* No
fixture sits at exactly `cancellationWindowHours`, so nothing would go red if `<=` were flipped to
`<`. It was matched to `isOpenFor`'s documented *"at exactly the boundary the window is shut"* **by
reading, not by test** — and the difference between those two is most of what this session was about.

---

## 11. Commands

```bash
make up-e2e                 # six containers: app, Caddy, Postgres, Mailpit, fake provider
make e2e                    # see the Makefile's note first — the installer hangs on macOS
make check-access-log       # restart Caddy first, or the green is about an unknown config
make check-fake-provider    # no containers needed

cd backend && JAVA_HOME=/Users/sanama/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home \
  ./gradlew test --tests '*BlueprintCheckTest' --tests '*DemoSeedTest'
```

The `JAVA_HOME` is not optional on this machine: the default `java` is 25, the build needs 21, and
Gradle reports the mismatch as a bare version number — `* What went wrong: 25.0.4.1` — in a build
that fails in five seconds and looks like a test result.

---

## 12. Confidence

**Certain on G40's cause.** Two runs of the same commit at two times of day, plus a worktree at the
last green commit reproducing the failure at the current hour. The arithmetic matches both runs to
the minute.

**High on the fix.** Three plants, one of which failed a pre-existing test, and the full backend suite
green afterwards **at the hour that was failing** — then green again in CI at 17:05 local, past the
crossover.

**High on the two instrument findings**, both demonstrated rather than argued.

**Medium on the E2E's reach.** It passed in CI for the first time, which is one run on one runner.
Nothing here says it is not flaky.

**Explicitly unproven: G40's boundary**, §10. And **nothing here says anything about Receptionist
behaviour** since the system prompt changed twice — no model was called, because none could be.
