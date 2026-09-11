# Session handoff — 2026-09-11 — the ceiling that was two ceilings

> **Purpose.** Enough context to take phase 11, or the revenue-currency decision, without
> re-reading anything. §1 is where things stand; §2 is the two pull requests that landed; §3 is
> [#23], which is fixed; §4 is the correction that changed the migration's shape; §5 is what was
> measured and how; §6 is what this session got wrong.
>
> **Read §4 first if you read nothing else.** The constraint [#23] specified would not have made
> the bound safe, and the difference is not cosmetic.
>
> **Phase 10 is merged, and so is [#23].** The build is **837 tests, 0 failures**. `dev` and
> `main` are **identical and both pushed** — nothing is outstanding, which has not been true at the
> end of a session since phase 07.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#23]: https://github.com/sanama-stack/reception-booking-system/issues/23
[#22]: https://github.com/sanama-stack/reception-booking-system/pull/22
[#24]: https://github.com/sanama-stack/reception-booking-system/pull/24
[#25]: https://github.com/sanama-stack/reception-booking-system/pull/25
[previous]: ./2026-09-11-the-fix-that-was-not-an-index.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`7df76d3`** — [#25][#25] merged, six checks green. Both this session's pull requests are on `main` |
| `dev` | **`ac8183c`**, pushed. Its tree is **byte-for-byte identical to `main`**, and `main` is an ancestor, so **T21 is already paid and the next pull request opens clean** |
| Backend | **837 tests, 0 failures**, counted from the XML. Was 831; the six are §3.3 |
| `./gradlew build` | green locally — the whole of what CI's Backend job runs |
| Frontend | **untouched this session.** No gate was run, because nothing changed |
| Migrations | **`V9__appointment_buffer_ceilings.sql`**, the sibling of `V8` |
| New ADR | None |
| Issues | **[#23] closed by [#25][#25]**, with the correction in §4 posted to it first. [#15] and [#17] open, neither touched |
| Phase 10 | **complete and merged** |

### 1.1 The commits

| | |
|---|---|
| `2e2a7b4` | merge `main` into `dev` — T21's fifth occurrence, and its second consecutive prediction |
| `3fbc1b6` | **on `main`**: the merge of [#24][#24] |
| `3fc2e63` | merge `main` into `dev` again, **before** starting work rather than at pull-request time |
| `6466b13` | **the only new product work**: the availability bound, `V9`, and six tests |
| `8b5e688` | this handoff, as first written |
| `7df76d3` | **on `main`**: the merge of [#25][#25] |
| `ac8183c` | merge `main` into `dev` a third time, again up front — T21's sixth occurrence |

---

## 2. Two pull requests, both merged

The [previous session][previous] ended with `dev` unpushed and a migration CI had never run. Merged
`main` in (no content — `git diff HEAD^1 HEAD` was empty, exactly as T21 predicts), pushed, opened
[#24][#24].

**Six checks, not the three the last handoff named.** There are three jobs — Backend, Frontend and
**Compose smoke test** — and two workflow runs fire per push, one on the `push` event and one on the
pull request. The Compose smoke test is the one that matters for a migration: it is where `V8` ran
against a container start rather than under the test harness.

All six green. Merged with `--merge`. **`--admin` was offered a fifth time and declined a fifth
time.**

### 2.1 [#25][#25], which landed [#23]

Opened after the work in §3, with the correction in §4 posted to [#23] first so the issue's own
record does not outlive the session that found it wrong. Six checks green again — `V9` applied
under the test harness **and** in a real container start, twice each, which is the whole reason to
care about the Compose smoke test.

Merged with `--merge`; [#23] closed automatically on the merge commit. **`--admin` was offered a
sixth time and declined a sixth time.**

### 2.2 Merging `main` back in immediately, twice

`main` takes the merge commit, so `dev` goes one behind the moment a pull request lands. This
session merged it straight back in both times — `3fc2e63` after [#24][#24], `ac8183c` after
[#25][#25] — instead of waiting for the next pull request to discover it. Both merges brought **no
content**, and after the second `dev` and `main` are byte-for-byte identical.

T21 costs nothing when it is paid up front and a round trip when it is not. Six occurrences now,
and the last three were predicted rather than discovered.

---

## 3. [#23], fixed

`findByBusinessIdAndEmployeesOverlapping` — the availability engine's read, running on every page of
the public booking flow and every read the Receptionist makes — asked
`blocked_from < :to AND blocked_to > :from` with neither blocked column in any index. PostgreSQL
`BitmapAnd`ed the gist exclusion constraint with `appointments_business_starts_idx`, which has no
range restriction, so **asking about one day cost the same as asking about any day**.

The fix bounds `starts_at` on both sides, from the ceilings the blocked range cannot exceed.

### 3.1 Both bounds are exact, and they rest on different quantities

```
blocked_to   = starts_at + duration + buffer_after   <=  starts_at + 1440 + 240 minutes
blocked_from = starts_at - buffer_before             >=  starts_at - 240 minutes
```

so `blocked_to > :from` gives `starts_at > :from - 28h`, and `blocked_from < :to` gives
`starts_at < :to + 4h`. Both comparisons are strict, and an appointment sitting exactly on either
bound has a blocked range that touches the range's edge without crossing it — so nothing is lost.

Derived inside a `default` method on the repository, as the calendar's bound is, so no caller can
forget them.

### 3.2 `V9`

Two `CHECK`s — `starts_at - blocked_from <= INTERVAL '4 hours'` and
`blocked_to - ends_at <= INTERVAL '4 hours'` — each with a `COMMENT ON CONSTRAINT` saying what
breaks. `appointments_time_order` already pinned `blocked_from <= starts_at AND ends_at <=
blocked_to`, so both buffers were already non-negative and only the upper ends were missing.

Checked against the dev database first: 39 appointments, **none in violation**. Note that
`max(starts_at - blocked_from)` and `max(blocked_to - ends_at)` are both `00:00:00` there — the dev
data uses **no buffers at all**, so it proves the migration applies and nothing more.

### 3.3 The six tests

Four behavioural, two on the constraints. All fixtures are written with `JdbcTemplate`, for the same
reason `CalendarOverlapBoundTest`'s are and one more: `AvailabilityEngine` cannot *produce* an
appointment whose blocked range reaches this far, because it requires the whole booking to fit
inside one of a date's opening intervals.

**They assert on the repository rather than on offered Slots, deliberately.** Each bound can only be
wrong at its limit, and an appointment at either limit blocks time in the first four hours of the
range or the last — outside every fixture business's opening hours, and so invisible in a grid of
bookable Slots. A test driven through `GET /availability` could not reach the case and **would pass
by never trying**.

### 3.4 Both bounds were shown to fail when wrong

| Bound narrowed to | What went red |
|---|---|
| the duration ceiling alone — the calendar's bound, copied across without thinking | *the furthest-reaching appointment still blocking the range is still seen* |
| `to`, dropping the leading-buffer term | *an appointment starting after the range whose buffer reaches back into it is seen* |

And with the first of those still in place, **265 tests across `scheduling`, `appointments` and
`publicapi` ran and only the new one noticed.** That is the whole argument for the tests existing.

---

## 4. The correction: one constraint would not have been enough

[#23]'s body specified the guard as *"a check on `blocked_to - blocked_from`"* — the total blocked
span, ≤ 32 hours. **That would have looked equivalent and would not have been.**

The two bounds rest on different quantities. A service with a ten-hour leading buffer and a short
duration satisfies a 32-hour total check and **still breaks the second bound**: the query would stop
seeing that appointment's committed time, which is the double-booking path the issue was written to
prevent. The total is not the sum of the things that have to be held; each ceiling the bound
actually depends on has to be held separately.

So `V9` is two constraints, one per buffer, and with `V8`'s `ends_at - starts_at <= INTERVAL '1 day'`
they compose to exactly the two bounds the query needs.

**This was put to the principal before anything was written**, with the correction stated, and the
two-constraint form was chosen.

---

## 5. What was measured, and on what

**On Hibernate's own generated statement**, captured from the SQL log, not on a hand-written
equivalent — which closes this change's version of **G15**, the gap the [previous handoff][previous]
left open on the calendar fix:

```sql
select a1_0.employee_id,a1_0.blocked_from,a1_0.blocked_to from appointments a1_0
 where a1_0.business_id=? and a1_0.employee_id in (?) and a1_0.status='CONFIRMED'
   and a1_0.starts_at>? and a1_0.starts_at<?
   and a1_0.blocked_from<? and a1_0.blocked_to>? and (? is null or a1_0.id<>?)
```

Run through `PREPARE`/`EXECUTE` past the sixth execution (T30), after `VACUUM (ANALYZE)` (T29),
against the **surviving `reception_perf` scratch database** — 10 000 appointments for the target
business and 21 600 across nine other tenants, the shape T28 requires. One day, three employees.
**Both forms return the same six rows.**

| | Unbounded | Bounded |
|---|---|---|
| Index entries read | 10 000 + 1 164 | **13** |
| Heap blocks | 264 | **4** |
| Rows discarded by filter | 1 158 | **7** |
| Buffers | 328 | **7** |
| Time | 0.851 ms | **0.097 ms** |

### 5.1 The issue's 46x is not what this measures

[#23] advertised **46x** (1.439 ms → 0.031 ms), measured on hand-written SQL with literals. On
Hibernate's real statement under a generic plan the time ratio is **8.8x**. The **47x survives as
the buffer ratio** (328 → 7), which is the cache-independent number and the one worth quoting.
Nothing is wrong with either measurement; they are of different things, and the commit message
quotes the ones taken here.

---

## 6. What this session got wrong

**Said "two checks" when CI has six.** Reported the pull request as having two pending checks from
the app's cache before the third job had been scheduled, and repeated it. Corrected in §2 once
`gh pr checks` was read directly. The lesson is small and general: a cache that is not yet populated
looks exactly like a complete answer.

**Nearly quoted the issue's 46x.** The number is real and was measured by a previous session, but
not of the thing this session built. §5.1.

---

## 7. What is NOT done

**Nothing of this session's work is outstanding.** [#25][#25] is merged, `V9` has applied in CI
under both the test harness and a real container start, and `dev` is level with `main`.

| | |
|---|---|
| **The revenue currency** | still the principal's, still unchanged across **five** handoffs. `/analytics` ships resting on it |
| **Phase 11** | entirely unticked, and now the largest open thing in the project |
| **The frontend** | untouched this session, no gate run |
| **Cold cache** | G16 stands. Every buffer in §5 is `shared hit` |
| **G15's calendar half** | narrowed but not closed — §8.2 |
| **The scratch database** | `reception_perf` **still exists and was used again**. It has now earned its keep twice; the argument for dropping it is weaker than it was |

---

## 8. Every open item

### 8.1 Issues

**[#23]** — **closed** by [#25][#25], with §4's correction posted to it first.

**[#17]** and **[#15]** — open, neither touched. Both are AI-behaviour issues and both are parked
for a reason worth carrying: [#17] after three rejected candidates, two of which made things
measurably worse, and [#15] with a **known-wrong diagnosis in its own title** — a previous session
established that the SATURDAY mode it names is not the dominant failure. Neither is a
pick-up-and-fix.

**The tracker is otherwise empty.** Nothing else is filed.

### 8.2 Gaps

Carried: **G1**, **G3**, **G8**, **G10**, **G11**, **G13**, **G14**, **G16**.

**G15 is narrowed, not closed.** This session measured Hibernate's real statement for the
*availability* query. The **calendar** fix's 46x and 83x are still hand-written-SQL numbers, and
closing that is the same recipe applied to `findByBusinessIdAndOverlappingNoEarlierThan`.

### 8.3 Traps

Carried T1–T30. New:

**T31 — a guard on a total does not guard the parts.** A single check on the whole blocked span
would have passed while one of the two bounds it was supposed to protect was broken. When a query
depends on several ceilings, hold each one; the sum is not a proxy for any of them. §4.

**T32 — Gradle skips a re-run you meant to force.** `./gradlew test --tests X` with unchanged inputs
is `UP-TO-DATE` and prints nothing, which reads exactly like a test that produced no output.
`--rerun-tasks` is the difference between an empty grep and a real one. Cost two confused rounds
while capturing the SQL log.

**T33 — the app's PR cache can be short by a job.** `mcp__ccd_pr__get_status` reported two checks
while three jobs existed and a second workflow run was still being scheduled. `gh pr checks` is the
honest read before saying what CI is doing.

**T34 — a CI watch does not survive a session restart.** The background monitor on [#25][#25] was
reported as *stopped, no completion record* when the session resumed, with the pull request already
fully green and nobody watching. A watch is session-local; on resuming, re-read `gh pr checks`
rather than waiting for a notification that can no longer arrive.

**T35 — a poll loop that clobbers its seen-set re-reports what it already said.** The first watch
emitted `Frontend: pass` twice: one poll returned an empty result, `prev` was overwritten with it,
and the next poll saw every check as new. Guard the assignment (`if [ -n "$cur" ]`) or a transient
API blip reads as a fresh event. Corrected in the second watch.

### 8.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged.** No model was called this session.

`reception_perf` contains no real data: every row is generated, and only the schema came from the
dev database.

### 8.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; nothing deletes an `ai_message`; rate-limit buckets are in memory;
`make seed` is a placeholder; **no frontend test runner**.

---

## 9. Next steps, in order

**There is no P0 that is mine.** The tree is clean, both branches are level, and the only thing
blocking is a decision.

### P0 — the principal's

1. **Decide the revenue currency** ([phase-10 backend handoff](./2026-09-11-phase-10-backend.md)
   §3.5). Five handoffs old. Revenue is reported in the Business's *current* currency alone, so
   anything priced before a currency change silently leaves the figure, and the contract has one
   `revenue` object with nowhere to report the remainder. Three ways out: widen the contract, report
   in the currencies the rows carry, or accept the gap and record it. A test pins today's behaviour
   either way — it is a record, not an argument that today is right.

### P1

2. **Phase 11.** The reflection-driven isolation suite, the Playwright E2E, a real two-tenant
   `make seed` (`make seed` is still a placeholder), observability, security completion, the three
   performance benchmarks, and the documentation. It hardens rather than adds, and every box is
   unticked.

### P2

3. **Return `service_id` from `lookup_appointment`** — still unfiled, still one field, still a
   wasted round trip on every reschedule conversation.
4. **A frontend test runner**, which would turn the phase-10 frontend handoff's inventory rows into
   assertions.
5. **G15's calendar half** — §5's recipe applied to `findByBusinessIdAndOverlappingNoEarlierThan`.
   Cheap now that it has been done once, and it is the last thing standing between the calendar fix
   and a measurement of what actually ships.
6. [#17] · [#15] · `ai_message` retention · G16.

---

## 10. Commands

```bash
# The whole backend suite, ~5.5 minutes, from backend/.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# Count it from the XML rather than the console, which does not distinguish a stale result file.
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(x).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(t, f, e)"

# Exactly what CI's Backend job runs.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build --no-daemon

# The six new tests on their own, ~25 seconds. Add --rerun-tasks to force a re-run (T32).
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests "dev.reception.scheduling.AvailabilityOverlapBoundTest"

# The scratch dataset is still there, and the measurement in §5 is repeatable against it.
docker compose exec -T postgres psql -U reception -d reception_perf
```

**Do not run `./gradlew` twice at once against this tree**, and **do not run `pnpm build` while the
dev server is up**.

---

## 11. Confidence

**High — the fix is correct, and it has now run somewhere this machine did not build.** Both bounds
are derived rather than chosen, both were shown to fail when narrowed, and the pre-existing suite was
shown not to catch either. The query returns the same six rows before and after. `V9` applied in CI
under the test harness and in a container start, twice each.

**High — the fix is as fast as claimed.** Unlike the calendar's, this was measured on Hibernate's
own statement under a generic plan. The numbers in §5 are of the thing that ships.

**High — `V9` is the right shape.** §4's counterexample is concrete, and the two-constraint form
follows from it rather than from taste.

**Medium — the ceilings are now fully held.** `V9` plus `V8` cover duration and both buffers, which
is everything the two bounds depend on *today*. If a future change made `blocked_to` depend on
something else, nothing here would notice.

**Low — anything about disk.** G16, unchanged. Every number is warm-cache.

**None — the revenue currency.** Still nobody's decision, **five** handoffs running.
