# Session handoff — 2026-09-11 — the fix that was not an index

> **Purpose.** Enough context to take phase 11, or to act on [#23], without re-reading anything.
> §1 says where things stand; §2 is the merge that finally moved `main`; §3 is the dataset and why
> its shape is the whole measurement; §4 is what the numbers said; §5 is the fix and the constraint
> under it; §6 is what this session got wrong and corrected.
>
> **Read §3.1 first if you read nothing else.** A single-tenant dataset cannot verify a
> tenant-scoped index, and a green result from one means nothing at all.
>
> **Phase 10 is complete** — every box, G12 included. The build is **831 tests, 0 failures**, and
> `dev` carries one unpushed commit that has never been near CI.

[#15]: https://github.com/sanama-stack/reception-booking-system/issues/15
[#17]: https://github.com/sanama-stack/reception-booking-system/issues/17
[#23]: https://github.com/sanama-stack/reception-booking-system/issues/23
[#22]: https://github.com/sanama-stack/reception-booking-system/pull/22
[frontend]: ./2026-09-11-phase-10-frontend.md
[backend]: ./2026-09-11-phase-10-backend.md

---

## 1. Where the project stands

| | |
|---|---|
| `origin/main` | **`9b63ede`** — [#22][#22] merged. Two phases of work landed; `main` current for the first time since phase 09 |
| `dev` | **`45cb680`**, **not pushed**. One commit ahead of `main` and one behind it (the merge commit), so **T21 will fire on the next pull request** |
| Backend | **831 tests, 0 failures**, counted from the XML. Was 827; the four are §5.3 |
| `./gradlew build` | green locally — the whole of what CI's Backend job runs |
| Frontend | **untouched this session.** No gate was run, because nothing changed |
| Migrations | **`V8__appointment_max_length.sql`** — the first since `V7`, and the first in phase 10 at all |
| New ADR | None. §5.2 is a candidate if anyone wants the buffer decision recorded |
| Issues | **[#23] filed.** [#15] and [#17] open, neither touched |
| Phase 10 | **complete** — zero unticked boxes |

### 1.1 The commits

| | |
|---|---|
| `2596b7a` | merge `main` into `dev` — T21's fourth occurrence, §2 |
| `9b63ede` | **on `main`**: the merge of [#22][#22], 53 files, +5170 −86 |
| `45cb680` | **the only new work**: the calendar bound, `V8`, and four tests |

---

## 2. The merge, and T21 for the fourth time

[#22][#22] was open and read `BEHIND`: `main` had taken [#21]'s merge commit `a80ffad`, which was
not an ancestor of `dev`. Exactly what T21 predicted, and the second time it has been predicted
rather than discovered.

The merge brought **no content** — `git diff HEAD^1 HEAD` was empty — so nothing under review
changed when CI re-ran. All three jobs went green on the merge commit and it was merged with
`--merge`, matching every pull request on this repo since phase 05. **`--admin` was offered a
fourth time and declined a fourth time.**

`dev` is now one behind `main` again, which is the same shape that will make T21 fire on the next
one. It is not a problem; it is a step, and the recipe is in T21.

---

## 3. The dataset, which is most of the work

`docs/phases/phase-10-dashboard-and-analytics.md` asks for the indexes to be verified against
10 000 appointments. The database used was a **clone of the production schema** —
`pg_dump --schema-only` out of the dev database into a scratch database `reception_perf` on the same
PostgreSQL 16 — so every index measured is byte-for-byte the shipped one rather than something a
test fixture built. **The dev database was read and never written.**

### 3.1 A single-tenant dataset would have proved nothing

This is the part worth carrying forward.

Load 10 000 appointments for one business into an empty table and `business_id = X` matches **every
row**. A sequential scan is then genuinely the cheapest plan, PostgreSQL correctly chooses it, every
query is fast because the whole table is four hundred pages, and the result is a green tick that
says *the table was small* — not *the index works*.

So the target business holds exactly 10 000 and **nine other tenants hold 21 600 between them**,
31 600 rows in total, the target at 31.6%. That is the harder case for index selection, not the
easier one, and it is the only shape in which the question means anything.

### 3.2 The rest of the shape, and why each part is there

| | |
|---|---|
| **Exactly 10 000** | 500 days x 5 employees x 4 slots, by construction. No trimming, no "about ten thousand" |
| Slots at 09:00 / 11:00 / 13:00 / 15:00 with 10-minute buffers | the blocked ranges of one employee never touch, so the gist exclusion constraint is satisfied **by construction** rather than by retrying inserts |
| 440 days past, 60 future | revenue and the rates filter on status, and an all-future dataset is 100% `CONFIRMED` — the revenue query would have had nothing to sum and would have looked fast for the wrong reason |
| 6 170 `COMPLETED`, 1 943 `CONFIRMED`, 1 007 `CANCELLED`, 880 `NO_SHOW` | the above, realised |
| 260 closures, 1 300 time-off rows | five years at one a week, far past what a real business enters. Those tables share the unbounded-overlap shape and would otherwise have been assumed rather than measured |

### 3.3 How the numbers were taken

Every number below is the **second run after `VACUUM (ANALYZE)`**. Both halves of that matter, and
one of them cost a wrong number first — see T29.

All buffers are `shared hit`. **This measures plans and row counts, not disk** (G16).

---

## 4. What the numbers said

### 4.1 The analytics queries — clean, and nothing missing

| Query | Plan | Time |
|---|---|---|
| Status counts, one month | Index **Only** Scan, `appointments_business_status_idx`, 0 heap fetches | 0.250 ms |
| Revenue, one month | Bitmap Index Scan, same index | 0.084 ms |
| Period count, one month | Index **Only** Scan, `appointments_business_starts_idx` | 0.103 ms |
| Top services, one month | Bitmap + HashAggregate | 0.180 ms |
| Status counts, **366-day maximum** | Index Only Scan, 7 300 rows | 1.200 ms |
| Revenue, **366-day maximum** | Bitmap Index Scan, 5 106 rows | 1.437 ms |
| Appointments list, every filter absent | Index Scan + `LIMIT 20` | 0.019 ms |

**No index is missing and none was added.** The four on `appointments` are all led by `business_id`
and they serve every analytics query as written.

### 4.2 A risk cleared rather than assumed

`findByBusinessIdAndFilters` uses `cast(:from as Instant) is null or ...`. An OR-with-null can
defeat an index, and **Hibernate binds parameters rather than inlining them**, so `EXPLAIN` with
literals would not have caught it — PostgreSQL constant-folds a literal `NULL` and the real
application never sends one.

Tested through `PREPARE`/`EXECUTE` past the sixth execution, where PostgreSQL may switch to a
generic plan built without knowing the values: **it still uses the index**, both with every filter
absent and with a range and a status. That is T30, and it is why the row above can be quoted.

### 4.3 The calendar overlap query — the finding

`starts_at < :to AND ends_at > :from`. `starts_at` has **no lower end**, so the index range begins
at the tenant's first ever appointment, and `ends_at`, which no index covers, can only be applied as
a filter afterwards. The work is proportional to *everything the business has ever booked* rather
than to the week on screen.

Same query, same single day, three sizes:

| Tenant history | Rows scanned to return **20** | Plan | Time |
|---|---|---|---|
| 2 400 | 2 400 | Bitmap Index Scan | 0.443 ms |
| **10 000** | **8 820** | Bitmap Index Scan | 0.635 ms |
| 30 000 | **51 600** | **Seq Scan of the whole table** | 5.847 ms |

A week behaves identically: 8 860 at 10 000; 51 600 and 9.520 ms at 30 000.

**Two separate things happen, and they should not be conflated.** At 10 000 it is *fast but
wasteful* — 440 rows of work per row returned, invisible only because 20 MB is in RAM. Above
10 000 the estimate crosses the planner's threshold, it stops using the index at all, and it starts
reading every other tenant's rows too. The answer stays correct throughout; the work simply stops
being bounded by anything the user did.

### 4.4 So does the box pass?

**Yes, and it is worth being exact about why.** The phase asks whether the indexes hold at 10 000.
They do: the slowest query in the product is 1.4 ms. Nothing here was slow, and the tick is honest.

What the dataset also showed is that one query's *work* is already 440:1 at that size and that its
plan collapses shortly after. That is a finding the box did not ask for and did not forbid.

---

## 5. The fix, and the constraint under it

### 5.1 It is not an index, which the phase document did not anticipate

`docs/phases/phase-10-dashboard-and-analytics.md` says, in as many words, that *if a query is slow
at 10 000 appointments, the fix is an index in this phase*. It was not. **No index can help here**:
the predicate is a range on one column paired with a range on another, and an index leading with
either one still cannot use the second.

The fix is a **lower bound on `starts_at`**, which turns an open-ended range into a closed one.

**It is exact, not generous.** `Service.MAX_DURATION_MINUTES` is **1440 — precisely one day** — so
an appointment starting a full day before `:from` ends exactly *at* `:from`, and `ends_at > :from`
is strict, so it does not overlap. Nothing that overlaps can start earlier. The bound is derived
from the constant in a `default` method on the repository rather than passed in by callers, so
there is no site at which it can be forgotten.

Measured at 30 000, where the unbounded query sequential-scans:

| | Before | After | |
|---|---|---|---|
| One day | 5.847 ms, 51 600 rows | **0.128 ms, 40 rows** | **46x** |
| One week | 9.520 ms, 51 600 rows | **0.114 ms, 160 rows** | **83x** |

### 5.2 `V8` exists because the bound would otherwise be able to outlive its reason

The bound is correct **only while no appointment can last longer than a day**, and until now nothing
held anyone to that. `appointments_time_order` checks that the times are *ordered*, not that they
are close together.

Raise `MAX_DURATION_MINUTES` in Java without widening the query and the calendar **silently stops
drawing long appointments**. A wrong answer, arriving quietly, on a screen whose entire job is to
show everything — and the kind of wrong answer nobody reports, because an absence looks like an
empty afternoon.

`V8__appointment_max_length.sql` makes it a failed write instead:
`CHECK (ends_at - starts_at <= INTERVAL '1 day')`, with a `COMMENT ON CONSTRAINT` saying why, so the
next person to raise the ceiling meets the explanation at the point they are blocked.

Checked against the dev database before committing: 39 appointments, longest 2h30m, **none in
violation**, so the migration will apply on the next backend start rather than failing it.

### 5.3 The four tests, and why they go around the application

All four write through `JdbcTemplate`. That is not laziness about fixtures — it is the only way to
reach the case. **`AvailabilityEngine` cannot produce an appointment that crosses midnight**: it
requires the whole booking to fit inside one of a date's opening intervals, and an interval does not
span days. A test that could only build its fixture through the booking endpoint could not construct
an appointment that starts before a day-aligned range and ends inside it, and **would pass by never
trying**.

| Test | What it pins |
|---|---|
| the longest possible appointment reaching into the day is still drawn | an appointment from Sunday 00:30 running the full 1440 minutes to Monday 00:30 — thirty minutes of overlap, and the furthest back the query has any business looking |
| an appointment that ends before the day begins is not drawn | that the first is not passing by accident of range arithmetic |
| the database refuses an appointment longer than a day | `V8`, through `JdbcTemplate`, so what is proven is that *PostgreSQL* refuses it and not that a validator happened to run |
| an appointment of exactly a day is allowed | the constraint is a ceiling, not a fencepost error |

**The first was checked against its counterfactual.** Narrowed to an hour, it fails — and **every
test in `CalendarViewTest` stays green throughout**, which is to say the suite that existed before
this session could not have caught a wrong bound. That is the only reason the test is worth having.

---

## 6. What this session got wrong

**"Bounded per employee rather than per business."** Said of the availability query, and it is half
wrong. The plan `BitmapAnd`s the gist exclusion index (1 168 entries, per-employee) with
`appointments_business_starts_idx`, **which has no range restriction and reads all 10 000 of the
business's entries**. The heap fetch grows with the employees' history; the index scan grows with
the whole business's. It degrades on both axes. Corrected in [#23] before it was filed.

**A number was quoted from a table full of dead tuples.** After deleting 20 000 rows to restore the
dataset to 10 000, the appointments listing measured **2.166 ms** — against 0.019 ms on the same
data vacuumed. Nothing was wrong with the query; the index scan was stepping over the corpses of my
own cleanup. Caught because the number had no business changing. T29.

---

## 7. [#23], filed and not fixed

The availability overlap query — `blocked_from < :to AND blocked_to > :from`, which runs on **every
page of the public booking flow** and on every read the Receptionist makes — has the identical
shape and no index covering either column.

Measured at 10 000, for one day: **6 rows returned**, 10 000 + 1 168 index entries read, 264 heap
blocks, 1 162 discarded, 1.44 ms. The candidate fix was measured too rather than merely proposed —
bounding `starts_at` by `MAX_DURATION_MINUTES + MAX_BUFFER_MINUTES` back and `MAX_BUFFER_MINUTES`
forward gives a plain index scan reading **13** entries, 0.031 ms, **46x**.

**It is not a copy of the calendar fix, and the difference is the reason it was filed rather than
landed.** This bound depends on the *buffer* ceiling as well as the duration one, and `V8` enforces
only the duration. Its sibling constraint would go on `blocked_to - blocked_from`. And the failure
mode if the ceilings ever drift apart is worse: the calendar merely fails to *draw* something,
whereas the availability engine failing to see committed time **is how a double booking gets
written**. That is a decision, not a fix, and §5's pattern should not be applied to it on autopilot.

---

## 8. What is NOT done

| | |
|---|---|
| **`dev` is unpushed** | one commit, `45cb680`, and **CI has never seen it** — nor has it seen `V8` run against a database it did not create |
| **The pull request** | not opened. T21 applies: merge `main` in first |
| **[#23]** | filed, measured, unfixed |
| **The frontend** | untouched, and no frontend gate was run this session |
| **Cold cache** | every measurement is `shared hit`. Nothing here was measured against disk (G16) |
| **The scratch database** | `reception_perf` is still on the local PostgreSQL, about 50 MB. `DROP DATABASE reception_perf;` |
| The generator and `EXPLAIN` scripts | **not committed** — they live in a scratch directory that will not survive. [#23] carries the shape that matters; the SQL itself would have to be rewritten |

---

## 9. Every open item

### 9.1 Issues

**[#23]** — new, `bug` + `ready-for-agent`, §7. **[#17]** — open, deliberately parked by the
principal after three rejected candidates, two harmful. **[#15]** — open, unchanged. Neither of the
last two was touched.

### 9.2 Gaps

**G12 is closed** — §4, and the phase box is ticked with what the tick rests on.

Carried: **G1** (no inline tool activity), **G3** (phase-09 DoD stays unticked while [#17] is open),
**G8** ("Any available" never rendered), **G10** (the weekday arm still has one phrasing),
**G11** (`requested_date` never recorded), **G13** (keyboard navigation, no evidence either way),
**G14** (`serialVersionUID` warning, predates this branch).

New:

**G15 — the calendar fix's numbers were measured on hand-written SQL**, equivalent to the new JPQL
by inspection, **not on Hibernate's generated statement**. The translation is mechanical for a query
this simple and the tests prove the *behaviour*, but the 46x and 83x were not driven through the
application. Closing this is one SQL log away.

**G16 — nothing was measured cold.** Every buffer in every plan is `shared hit` on a 20 MB table.
The row counts and the plans are real; the milliseconds are a warm-cache best case, and the 264
heap blocks in §7 would be 264 random reads on a cold one.

### 9.3 Traps

Carried T1–T27. New:

**T28 — a single-tenant dataset cannot verify a tenant-scoped index.** With one business in the
table, `business_id` matches every row, a sequential scan really is cheapest, and a green result
says the table was small. Every index in this schema is led by `business_id`; none of them can be
tested without other tenants present. §3.1.

**T29 — measure after `VACUUM`, or you are measuring your own `DELETE`.** A large delete leaves the
index full of dead entries and the next scan steps over all of them. 2.166 ms against 0.019 ms, on
identical data. §6.

**T30 — `EXPLAIN` with literals is not the plan the application runs.** Hibernate binds parameters;
PostgreSQL may build a generic plan from the sixth execution without knowing the values, and
constant-folding that rescues a literal `NULL` is not available to it. `PREPARE`/`EXECUTE` six times
is the honest test. §4.2.

### 9.4 Security

**S1 — the `OPENAI_API_KEY` is unchanged.** No model was called this session.

The scratch database contains no real data: every row was generated, and the only thing copied out
of the dev database was the schema.

### 9.5 Carried

Unchanged: the advisory lock's cost is unmeasured; no "find my booking" page; `sessionStorage` holds
a customer's name and number; nothing deletes an `ai_message`; rate-limit buckets are in memory;
`make seed` is a placeholder; **no frontend test runner**.

---

## 10. Next steps, in order

### P0

1. **Push `dev` and open the pull request.** One commit, and it carries a **migration** — `V8` has
   only ever run against databases this machine built. CI is where it runs against one it did not.
   T21 first: merge `main` into `dev`, confirm the diff to `main` is only `45cb680`, let CI run.
2. **Decide [backend] §3.5**, the revenue currency. Unchanged from the last two handoffs, still
   owned by the principal, and `/analytics` is still resting on it.

### P1

3. **[#23]** — measured, specified, and carrying one real decision (§7). The nearest thing to
   shovel-ready in the tracker.
4. **Return `service_id` from `lookup_appointment`** — still unfiled, still one field, still a
   wasted round trip on every reschedule conversation.
5. **A frontend test runner**, which would turn [frontend] §4's four inventory rows into assertions.

### P2

6. **Phase 11** · [#17] · [#15] · `ai_message` retention · G15 · G16.

---

## 11. Commands

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

# The four new tests on their own, ~30 seconds.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests "dev.reception.calendar.*"
```

**Do not run `./gradlew` twice at once against this tree**, and **do not run `pnpm build` while the
dev server is up** — it clobbers the running server's `.next` and every route starts serving 500.

---

## 12. Confidence

**High — the analytics verdict.** Seven queries, each read as a plan rather than as a stopwatch, on
a dataset whose shape was chosen so that a wrong index *could* have shown up. §4.2 in particular had
a way to fail and did not.

**High — the calendar finding.** Measured at three sizes on the same query, and the degradation is
visible as a change of *plan*, not as a slower number: at 30 000 PostgreSQL stops using the index.
That is not something a timing artefact produces.

**High — the fix is safe.** The bound is derived from the constant rather than chosen, the constant
is now enforced by the database, and the test that would catch a wrong bound was **shown to fail
when the bound was wrong** while the pre-existing suite stayed green.

**Medium — the fix is as fast as claimed in production.** G15: the speedups were measured on
hand-written SQL, not on Hibernate's statement. I believe the translation; I did not watch it.

**Low — anything about disk.** G16. Every number is warm-cache. The *ratios* are about rows and
plans and survive; the milliseconds are a best case and should not be quoted as latency.

**None — [#23]'s decision.** §7 names a choice about where the buffer ceiling gets enforced, and
nobody has made it.
