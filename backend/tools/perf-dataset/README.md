# The performance dataset

31 600 Appointments across ten tenants, in a database built from this repository's own migrations.
It exists so the three NFR checks of [`docs/01-prd.md`](../../../docs/01-prd.md) §5 can be run from a
clean clone instead of against a scratch database that lived on one machine.

```bash
backend/tools/perf-dataset/generate.sh          # build it   (~30 s)
cd backend && ./gradlew test -PincludeTags=perf --rerun
backend/tools/perf-dataset/generate.sh --drop   # remove it
```

## Migrated, not cloned — and this is the whole point

The scratch database three sessions measured against was a `pg_dump --schema-only` clone taken
before `V8` and `V9`. It was therefore **missing the three CHECK constraints the bounded queries
depend on**:

```
appointments_max_length · appointments_buffer_before_max · appointments_buffer_after_max
```

The calendar and availability queries are fast because they bound `starts_at` by a ceiling those
constraints enforce. Measure them against a fixture that cannot enforce the ceiling and one
over-long appointment gives you a fast, **wrong** answer — from the instrument that was supposed to
be doing the checking. Its `flyway_schema_history` also existed and was *empty*, which is worse than
absent: Flyway would have treated a fully populated database as unmigrated and run `V1` at it.

So `generate.sh` creates the database, runs `flywayMigrate` against it, and then **asserts those
three constraints are present** before loading a single row. That check was run against the old
clone before it was deleted, and it named all three.

## The shape, and why each part is there

| | |
|---|---|
| **10 000 appointments for tenant 0, 21 600 across nine others** | The second number is the one that is easy to leave out and the one that decides whether the measurement means anything. With a single Business in the table, `business_id = X` matches every row, a sequential scan genuinely is cheapest, and the green tick says *the table was small* — not *the index works*. At 31.6% of the table the target is the harder case for index selection |
| Exactly 10 000 | 500 days × 5 employees × 4 slots, by construction. No trimming, no "about ten thousand" |
| Slots at 09:00 / 11:00 / 13:00 / 15:00, 60 min, 10-minute buffers | One employee's blocked ranges never touch, so `appointments_no_overlap` is satisfied *by construction* rather than by retrying inserts. The constraint still judges all 31 600 on the way in |
| 440 days behind, 60 ahead | Revenue and the rates filter on status. An all-future dataset is 100% `CONFIRMED`, and the revenue query would have looked fast because it had nothing to sum |
| 6 100 `COMPLETED`, 1 965 `CONFIRMED`, 1 035 `CANCELLED`, 900 `NO_SHOW` | The above, realised. Deterministic in the row's own index, so two runs produce the same database |
| 260 closures and 1 300 time-off rows per tenant | Five years at one a week, far past what a real business enters. Both tables share the unbounded-overlap shape the calendar query had |
| 250 customers per tenant | Forty visits each for the target, which is the shape `appointments_customer_idx` is for |

Everything is placed relative to midnight UTC on the day it is generated, so the dataset is the same
shape whenever it is built. **Regenerate it if it is more than a few days old** — the NFR ranges are
anchored to *today*, and a stale dataset quietly moves its history out from under them.

## Taking a number off it

`NfrBenchmarkTest` is the instrument, and it goes through the **application services** rather than
through `psql`. That is not convenience; it removes the two things that made the previous readings
hard to trust:

- **The statement is Hibernate's**, because Hibernate wrote it. The SQL in a `@Query` annotation is
  not what reaches PostgreSQL, and the previous recipe involved capturing it from a log by hand.
- **The parameters are bound**, because JDBC bound them. PostgreSQL constant-folds a literal in an
  `EXPLAIN` and cannot constant-fold a parameter, so a plan taken with literals can be one the
  application never gets (T30). The driver also switches to a server-side prepared statement after
  five executions, which is why the test discards five runs before measuring twenty — the measured
  runs are on the **generic plan** the `PREPARE`/`EXECUTE` recipe existed to reach.

It measures the operation, entity hydration included, which is what the NFR is about. It does **not**
measure HTTP, JSON serialisation or a network hop.

### A cold reading

Every performance number in this project used to be `shared hit` (G16). To take one that is not:

```bash
docker compose restart postgres && sleep 4
docker compose exec -T postgres psql -U reception -d reception_perf <<'SQL'
PREPARE status_counts (uuid, timestamptz, timestamptz) AS
  select a.status, count(*) from appointments a
   where a.business_id = $1 and a.starts_at >= $2 and a.starts_at < $3
   group by a.status;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
  EXECUTE status_counts('11111111-0000-4000-8000-000000000000',
                        date_trunc('day', now()) - interval '366 days',
                        date_trunc('day', now()));
SQL
```

The first execution after the restart reports `shared read`; run it again and the same buffers are
`shared hit`. **Be exact about what that is**: a restart empties PostgreSQL's buffer pool, not the
operating system's page cache, so this is a cold *database*, not a cold *disk*. The recorded
readings are in the session handoff of 2026-09-11.

## The ids

Deterministic, so a row can be recognised on sight. The leading group says what it is.

| | |
|---|---|
| `11111111-…-00000000000<b>` | business `b`; **tenant 0 is the target of every measurement** |
| `22222222-…` | employee | 
| `33333333-…` | service |
| `44444444-…` | customer |
| `55555555-…` | appointment |

There are **no users and no memberships**, deliberately: nothing here can be signed into, and no
password hash is committed. The benchmark adopts the tenant directly, the way
`TenantContextFilter` would.
