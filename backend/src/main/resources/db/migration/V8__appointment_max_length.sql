-- Phase 10 — the ceiling the calendar query is fast because of.
--
-- No new table, no new column and no new index. One CHECK, and it exists to make an assumption
-- enforceable that was previously only true by habit.
--
-- THE QUERY THIS PROTECTS.
--
-- A calendar asks for the Appointments that OVERLAP a range: `starts_at < :to AND ends_at > :from`.
-- Written that way and no other, `starts_at` has no lower bound, so the index range begins at the
-- tenant's first ever Appointment and `ends_at`, which no index covers, can only be applied as a
-- filter afterwards. The work is then proportional to everything the business has ever booked
-- rather than to the week on screen: measured against 10 000 Appointments it read 8 820 rows to
-- return 20, and at 30 000 PostgreSQL gave up on the index and sequentially scanned the whole
-- table -- every other tenant's rows included.
--
-- AppointmentRepository now bounds it: `starts_at >= :from - <the maximum an Appointment can last>`.
-- That is exact rather than generous. An Appointment starting the full maximum before `:from` ends
-- exactly AT `:from`, and `ends_at > :from` is strict, so it cannot overlap. Nothing that overlaps
-- can start earlier.
--
-- WHY THE CONSTRAINT AND NOT JUST THE COMMENT.
--
-- The bound's correctness rests entirely on that maximum -- Service.MAX_DURATION_MINUTES, 1440,
-- exactly one day -- and until now nothing in the database held anyone to it.
-- `appointments_time_order` checks that the times are ordered, not that they are close together.
-- If the ceiling were ever raised in Java and this query not widened with it, the calendar would
-- silently stop drawing long Appointments: a WRONG ANSWER, arriving quietly, on a screen whose
-- whole job is to show everything. A query that is fast because of an invariant should not be able
-- to outlive it.
--
-- The failure mode is now the loud one instead. Raise MAX_DURATION_MINUTES past a day and the
-- write fails here, in a migration-shaped place, rather than in a view that just leaves things out.

ALTER TABLE appointments ADD CONSTRAINT appointments_max_length
    CHECK (ends_at - starts_at <= INTERVAL '1 day');

COMMENT ON CONSTRAINT appointments_max_length ON appointments IS
    'Service.MAX_DURATION_MINUTES is 1440. The calendar overlap query bounds starts_at by this '
    'ceiling to stay indexable; raising one without the other silently drops long appointments '
    'from the view.';
