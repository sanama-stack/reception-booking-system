package dev.reception.notifications;

import java.time.Duration;

/**
 * How long a failed notification waits, and when it stops waiting.
 *
 * <p>Policy, not configuration — the same argument {@code RateLimitProperties} makes. These numbers
 * are a judgement about what an SMTP outage looks like (usually seconds, sometimes an hour) and
 * changing them changes product behaviour, so they belong in code that is reviewed rather than in a
 * YAML file that is not.
 *
 * <p>The schedule is indexed by attempts <em>already made</em>, so a row that has failed once waits
 * a minute and a row that has failed four times waits an hour. Five failures is the end: a message
 * that could not be delivered across roughly eighty minutes of trying is not going to be delivered
 * by trying a sixth time, and a row retried forever is a row that quietly consumes a poller batch
 * slot for the rest of the table's life.
 */
public final class RetryBackoff {

    /** Attempt count at which a row becomes {@code FAILED} instead of waiting again. */
    public static final int MAX_ATTEMPTS = 5;

    private static final Duration[] SCHEDULE = {
        Duration.ofMinutes(1), // after the 1st failure
        Duration.ofMinutes(5), // after the 2nd
        Duration.ofMinutes(15), // after the 3rd
        Duration.ofMinutes(60), // after the 4th
    };

    private RetryBackoff() {}

    /** Whether a row that has now made {@code attempts} tries gets another one. */
    public static boolean stillRetryable(int attempts) {
        return attempts < MAX_ATTEMPTS;
    }

    /**
     * How long to wait before the attempt after {@code attempts} failures.
     *
     * <p>Clamped at both ends rather than throwing. An {@code attempts} outside the schedule means
     * the cap changed while rows were in flight, and refusing to schedule those would strand exactly
     * the messages that had the most trouble getting out.
     */
    public static Duration delayAfter(int attempts) {
        if (attempts < 1) {
            return SCHEDULE[0];
        }
        int index = Math.min(attempts, SCHEDULE.length) - 1;
        return SCHEDULE[index];
    }
}
