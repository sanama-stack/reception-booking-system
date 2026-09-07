# PostgreSQL exclusion constraints prevent double booking

**Status:** accepted

Two customers can request the same slot in the same millisecond, and an application-level "check then
insert" is a race however carefully it is written. We make overlap **structurally impossible** in the
database with a `btree_gist` exclusion constraint over `(employee_id, tstzrange(blocked_from, blocked_to))`
restricted to `status = 'CONFIRMED'`, so correctness does not depend on application discipline, transaction
isolation level, or every future code path remembering to check.

## Considered options

- **Pessimistic locking** (`SELECT … FOR UPDATE` on the employee row): serialises all bookings for a
  employee, including non-overlapping ones, and depends on every write path taking the lock.
- **`SERIALIZABLE` isolation**: correct, but imposes retry handling on every transaction in the application
  for a problem confined to one table.
- **Optimistic version column**: does not model the constraint — two appointments at overlapping times are
  two different rows, so there is no shared version to conflict on.
- **Application check inside a transaction**: the default, and wrong. It is a check-then-act race.

## Consequences

- `btree_gist` is required, so PostgreSQL is not swappable. Accepted; nothing in the roadmap suggests moving.
- Tests must run against real PostgreSQL. H2 cannot express this constraint, so testing against H2 would
  test a different system than we ship. This is why [08-testing-strategy.md](../08-testing-strategy.md)
  mandates Testcontainers.
- Buffers are handled by storing `blocked_from`/`blocked_to` separately from the customer-facing
  `starts_at`/`ends_at`. The constraint uses the blocked range; the customer sees the other.
- The range bound is half-open `'[)'`, so a 15:00–16:00 and a 16:00–17:00 appointment do not collide.
  With `'[]'` the system would silently forbid back-to-back bookings, which reads as a mysterious bug.
- The partial `WHERE status = 'CONFIRMED'` is what allows a cancelled appointment's time to be rebooked.
- The application still pre-checks availability — for a good error message, not for correctness. The
  constraint violation maps to `409 SLOT_UNAVAILABLE`, keyed on the constraint name so unrelated integrity
  errors are not masked.
