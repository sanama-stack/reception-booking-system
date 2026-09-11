-- The ceilings the availability query is fast because of -- the sibling of V8, for the other half.
--
-- No new table, no new column and no new index. Two CHECKs, and they exist for the same reason V8
-- does: to make an assumption enforceable that was previously only true by habit.
--
-- THE QUERY THIS PROTECTS.
--
-- `AppointmentRepository.findByBusinessIdAndEmployeesOverlapping` is what the availability engine
-- reads. It runs on every page of the public booking flow and on every availability read the
-- Receptionist makes, and it asks which committed time overlaps a range:
-- `blocked_from < :to AND blocked_to > :from`.
--
-- Neither blocked column is in any index. Written that way and no other, both comparisons can only
-- be applied as a filter after the rows have been fetched, and what PostgreSQL does instead is
-- BitmapAnd the gist exclusion constraint with `appointments_business_starts_idx` -- which has no
-- range restriction at all. Measured against 10 000 Appointments, one day's question read all
-- 10 000 of the business's index entries plus 1 168 for the employees, fetched 264 heap blocks and
-- discarded 1 162 rows, to return six. Bounded, the same question is a plain index scan of 13
-- entries: 1.439 ms to 0.031 ms.
--
-- WHY TWO CONSTRAINTS AND NOT ONE.
--
-- The query now bounds `starts_at` on both sides, and THE TWO BOUNDS REST ON DIFFERENT QUANTITIES:
--
--   blocked_to   = starts_at + duration + buffer_after   <=  starts_at + 1440 + 240 minutes
--   blocked_from = starts_at - buffer_before             >=  starts_at - 240 minutes
--
-- so `blocked_to > :from` gives `starts_at > :from - 28 hours`, and `blocked_from < :to` gives
-- `starts_at < :to + 4 hours`. Both are exact rather than generous: an Appointment sitting exactly
-- on either bound has a blocked range that touches the range's edge without crossing it, and both
-- overlap comparisons are strict.
--
-- A single CHECK on the total blocked span -- `blocked_to - blocked_from <= INTERVAL '32 hours'` --
-- would look equivalent and would not be. It is satisfied by a long leading Buffer paired with a
-- short duration, which breaks the second bound while passing the check. The ceiling each bound
-- actually depends on is the one that has to be held, so each is held separately.
--
-- `appointments_time_order` already pins `blocked_from <= starts_at AND ends_at <= blocked_to`, so
-- both Buffers are non-negative by construction and only the upper ends are missing.
--
-- THE FAILURE MODE, WHICH IS WORSE THAN V8'S.
--
-- Raise Service.MAX_BUFFER_MINUTES in Java without widening this query and the availability engine
-- quietly stops seeing some committed time. V8's version of this mistake makes a calendar fail to
-- DRAW something; this one makes the engine offer a slot that is already taken, and that is how a
-- double booking gets written. Loud is the only acceptable failure here.

ALTER TABLE appointments ADD CONSTRAINT appointments_buffer_before_max
    CHECK (starts_at - blocked_from <= INTERVAL '4 hours');

ALTER TABLE appointments ADD CONSTRAINT appointments_buffer_after_max
    CHECK (blocked_to - ends_at <= INTERVAL '4 hours');

COMMENT ON CONSTRAINT appointments_buffer_before_max ON appointments IS
    'Service.MAX_BUFFER_MINUTES is 240. The availability overlap query bounds starts_at by this '
    'ceiling to stay indexable; raising one without the other silently hides committed time from '
    'the engine, which is how a double booking gets written.';

COMMENT ON CONSTRAINT appointments_buffer_after_max ON appointments IS
    'Service.MAX_BUFFER_MINUTES is 240. With appointments_max_length this bounds how far after '
    'starts_at a blocked range can reach, which is the other half of what the availability overlap '
    'query relies on to stay indexable.';
