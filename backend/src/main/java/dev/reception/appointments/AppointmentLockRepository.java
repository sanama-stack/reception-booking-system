package dev.reception.appointments;

import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The lock that makes two people booking the same Employee queue instead of colliding.
 *
 * <p><strong>Why this exists at all, given {@code appointments_no_overlap}.</strong> The exclusion
 * constraint is still what makes double booking impossible (ADR-0002) and nothing here replaces it.
 * What the constraint does not do is <em>decide the race politely</em>. Each transaction inserts its
 * tuple and then checks the constraint, which means waiting on the other transaction's uncommitted
 * conflicting tuple — and two of those waiting on each other is a deadlock, not a conflict:
 *
 * <pre>
 * ERROR: deadlock detected
 *   Detail: Process 64 waits for ShareLock on transaction 774; blocked by process 63.
 *           Process 63 waits for ShareLock on transaction 776; blocked by process 64.
 *   Where: while checking exclusion constraint on tuple (0,1) in relation "appointments"
 * </pre>
 *
 * <p>Postgres resolves it by aborting a victim, which arrives as a {@code CannotAcquireLockException}
 * — not a constraint violation — and so was answered {@code 500 INTERNAL_ERROR} instead of
 * {@code 409 SLOT_UNAVAILABLE} (issue #7). Retrying the victim helps but cannot finish the job:
 * detection costs a full {@code deadlock_timeout} (one second by default) per cycle, and the retried
 * transactions re-enter the cycle they were aborted out of. Measured at twenty racers, retry alone
 * still lost roughly a third of runs, and every loser got a 500.
 *
 * <p><strong>Taken before the availability re-check, not just before the insert.</strong> That makes
 * check-and-write atomic per Employee, so a loser's re-check sees the winner's committed row and
 * refuses with the sentence it was written for — {@code SLOT_UNAVAILABLE}, naming the employee —
 * rather than being refused by a constraint whose message is for nobody. The constraint remains the
 * backstop for anything that reaches it another way.
 *
 * <p><strong>Keyed on the Employee, not on the Employee and the time.</strong> Bookings conflict
 * through the Buffers around them, so two requests for different start times can still overlap; a
 * key that included the time would let exactly those two through, which are the ones worth
 * serialising. One Employee cannot be in two places at once, so there is nothing to gain from
 * booking them in parallel, and a booking transaction is milliseconds long.
 */
public interface AppointmentLockRepository extends Repository<Appointment, UUID> {

    /**
     * Blocks until no other transaction holds this Employee's booking lock.
     *
     * <p>{@code pg_advisory_xact_lock} releases at commit or rollback with no unlock call, which is
     * the property being bought: a booking that throws cannot leave the Employee locked. It follows
     * that <strong>this must be called inside a transaction</strong> — called outside one, the
     * statement commits on its own and the lock is released before the caller does anything with it.
     *
     * <p>The projection is a formality: the function returns {@code void}, which JDBC has no useful
     * mapping for, so the call is wrapped in a subquery that yields a row.
     */
    @Query(
            value =
                    """
                    select 1 from (select pg_advisory_xact_lock(:key)) as locked
                    """,
            nativeQuery = true)
    int lock(@Param("key") long key);

    /**
     * The 64-bit key an Employee's UUID reduces to.
     *
     * <p>A collision would put two Employees on one lock — slightly more serialisation than
     * necessary, and nothing worse. It cannot admit a double booking, because the lock is not what
     * enforces the rule.
     */
    static long keyFor(UUID employeeId) {
        return employeeId.getMostSignificantBits() ^ employeeId.getLeastSignificantBits();
    }
}
