package dev.reception.appointments;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;

/**
 * How many times a booking aborted by a database deadlock is attempted again, and how long it waits.
 *
 * <p>Policy, not configuration — the argument {@code RetryBackoff} and {@code RateLimitProperties}
 * both make. These numbers decide what a customer racing for a slot experiences, so they belong in
 * code that is reviewed rather than in a YAML file that is not.
 *
 * <p><strong>Why a booking deadlocks at all.</strong> Concurrent inserts of overlapping ranges
 * against {@code appointments_no_overlap} block on one another's uncommitted rows, and with enough
 * contenders those waits form a cycle. Postgres breaks the cycle by aborting victims with
 * {@code deadlock detected} rather than with an exclusion-constraint violation — so the loser of a
 * race arrives at {@code GlobalExceptionHandler} as a {@code CannotAcquireLockException}, which is
 * not a {@code DataIntegrityViolationException} and never reaches the mapping that would have made
 * it a {@code 409} (issue #7).
 *
 * <p><strong>Why retrying is the right answer rather than mapping the deadlock to
 * {@code SLOT_UNAVAILABLE} directly.</strong> A deadlock says two transactions waited on each other;
 * it does not say the slot is taken, and answering "that time has gone" on that evidence is the
 * inference {@code GlobalExceptionHandler} argues against for exactly the same reason it maps by
 * constraint name. A victim is safe to retry because it committed nothing. On the retry the winner's
 * row is committed, so the exclusion constraint refuses this one on its own terms and the caller
 * gets the {@code 409 SLOT_UNAVAILABLE} it should have had — and if the slot was in fact free, the
 * booking simply succeeds.
 *
 * <p><strong>The pause is jittered</strong>, and that is the point of it rather than a detail:
 * victims that all wake at the same instant re-form the same cycle. Randomising the wait is what
 * makes the second attempt a different race from the first.
 */
public final class DeadlockRetry {

    /**
     * Total attempts, the first one included.
     *
     * <p>Five, and the number was measured rather than chosen. Three with a 20 ms step still let
     * roughly a third of heavily contended runs through: retried victims re-entered the same cycle
     * before it had drained, so the retries themselves became contention. Five attempts spread over
     * the schedule below absorbed it.
     *
     * <p>There has to be a ceiling. A booking that has been the victim five times is contending with
     * something other than an ordinary race, and at that point failing is better than waiting: the
     * exception propagates, {@code GlobalExceptionHandler} logs it, and the caller gets a 500 that
     * is telling the truth.
     */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * Base wait before attempt <em>n</em>, indexed by attempts already made; the jitter is added on
     * top.
     *
     * <p><strong>It widens</strong>, because a fixed step reproduces the pile-up it is meant to
     * break: everybody aborted at the same moment comes back at the same moment. Growing the gap
     * gives the cycle time to drain between waves, and the whole schedule still fits inside a third
     * of a second, which is well under what a booking request already costs.
     */
    private static final Duration[] SCHEDULE = {
        Duration.ofMillis(20), // before the 2nd attempt
        Duration.ofMillis(60), // before the 3rd
        Duration.ofMillis(140), // before the 4th
        Duration.ofMillis(300), // before the 5th
    };

    private DeadlockRetry() {}

    /**
     * Runs {@code work}, repeating it if a deadlock aborted it, and returns whatever it returns.
     *
     * <p>{@code work} must be a <strong>whole transaction</strong>. A deadlock rolls back everything
     * the victim did, so anything left outside the unit passed here would not be repeated and the
     * retry would build on half a booking.
     *
     * <p>Only {@code CannotAcquireLockException} is caught. Every other failure — a
     * {@code DataIntegrityViolationException} from the exclusion constraint above all — is the
     * database answering the question correctly and propagates on the first attempt.
     */
    static <T> T attempt(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (CannotAcquireLockException victim) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw victim;
                }
                pauseBefore(attempt + 1);
            }
        }
    }

    /**
     * Waits before the given attempt, which is 1-based and never 1 — the first attempt does not
     * wait.
     *
     * <p>Restores the interrupt flag rather than swallowing it: this runs on a request thread, and a
     * container shutting down is entitled to stop being waited on.
     */
    static void pauseBefore(int attempt) {
        long base = SCHEDULE[Math.min(attempt - 2, SCHEDULE.length - 1)].toMillis();
        long jitter = ThreadLocalRandom.current().nextLong(base);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
