package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The retry that turns a deadlocked booking into an answer.
 *
 * <p><strong>Why this is a unit test and {@code ConcurrentBookingTest} is not enough.</strong> The
 * defect it guards against — twenty racers, some aborted by {@code deadlock detected} and answered
 * {@code 500} instead of {@code 409} — took a full build under load to reproduce once, and a test
 * that reproduces once is a test that verifies nothing on the run where it passes. Postgres decides
 * when to declare a deadlock and which transaction to abort, and neither is a thing a test can ask
 * for. So the *policy* is tested here, where both branches are reachable on purpose, and
 * {@code ConcurrentBookingTest} keeps its job of proving the constraint still admits exactly one row.
 *
 * <p>What matters is the pair: a deadlock is retried, and an exclusion-constraint violation is not.
 * Retrying the second would turn one honest {@code 409 SLOT_UNAVAILABLE} into three slow ones, and
 * failing to retry the first is issue #7.
 */
class DeadlockRetryTest {

    @Test
    @DisplayName("work that succeeds is run once and its value returned")
    void a_booking_that_wins_is_attempted_once() {
        AtomicInteger attempts = new AtomicInteger();

        String result = DeadlockRetry.attempt(() -> {
            attempts.incrementAndGet();
            return "booked";
        });

        assertThat(result).isEqualTo("booked");
        assertThat(attempts).hasValue(1);
    }

    /** The victim of a deadlock committed nothing, so repeating its whole transaction is safe. */
    @Test
    @DisplayName("a deadlocked attempt is repeated, and the second attempt's answer is the answer")
    void a_deadlock_victim_tries_again() {
        AtomicInteger attempts = new AtomicInteger();

        String result = DeadlockRetry.attempt(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("deadlock detected");
            }
            return "booked";
        });

        assertThat(result).isEqualTo("booked");
        assertThat(attempts).hasValue(2);
    }

    /**
     * The ceiling exists so a booking answers rather than waits. A caller deadlocked three times is
     * contending with something other than an ordinary race, and the exception it finally throws is
     * a {@code 500} — which is correct, because at that point something really has gone wrong.
     */
    @Test
    @DisplayName("a booking deadlocked every time gives up after MAX_ATTEMPTS and rethrows")
    void a_permanent_deadlock_stops() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.attempt(() -> {
                    attempts.incrementAndGet();
                    throw new CannotAcquireLockException("deadlock detected");
                }))
                .isInstanceOf(CannotAcquireLockException.class);

        assertThat(attempts).hasValue(DeadlockRetry.MAX_ATTEMPTS);
    }

    /**
     * <strong>The half that is easy to get wrong.</strong> {@code appointments_no_overlap} refusing
     * a row is not a failure to retry — it is the race being settled, and the answer the loser is
     * owed. Retrying it would attempt a booking that cannot ever succeed, three times, before
     * returning the {@code 409} it could have returned immediately.
     */
    @Test
    @DisplayName("an exclusion-constraint violation is not retried: it is the answer")
    void a_lost_race_is_not_retried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.attempt(() -> {
                    attempts.incrementAndGet();
                    throw new DataIntegrityViolationException(
                            "conflicting key value violates exclusion constraint \"appointments_no_overlap\"");
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attempts).hasValue(1);
    }
}
