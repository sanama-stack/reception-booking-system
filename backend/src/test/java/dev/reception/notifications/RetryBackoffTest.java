package dev.reception.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The retry schedule, which is policy and therefore worth pinning.
 *
 * <p>These numbers decide how long a customer waits for a confirmation email while a relay is down,
 * and how long a permanently undeliverable row occupies a batch slot before it stops trying. Both
 * are product behaviour; a change to either should have to change a test that says so.
 */
class RetryBackoffTest {

    @Test
    void the_schedule_lengthens_with_each_failure() {
        assertThat(RetryBackoff.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryBackoff.delayAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(RetryBackoff.delayAfter(3)).isEqualTo(Duration.ofMinutes(15));
        assertThat(RetryBackoff.delayAfter(4)).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void the_whole_schedule_fits_inside_an_hour_and_a_half() {
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < RetryBackoff.MAX_ATTEMPTS; attempt++) {
            total = total.plus(RetryBackoff.delayAfter(attempt));
        }
        // Not an arbitrary assertion: it is the promise the cap makes. A message that has not gone
        // out in eighty-one minutes is one an operator should be looking at, not one the system is
        // still quietly retrying.
        assertThat(total).isEqualTo(Duration.ofMinutes(81));
    }

    @Test
    void a_fifth_attempt_is_the_last() {
        assertThat(RetryBackoff.stillRetryable(1)).isTrue();
        assertThat(RetryBackoff.stillRetryable(4)).isTrue();
        assertThat(RetryBackoff.stillRetryable(RetryBackoff.MAX_ATTEMPTS)).isFalse();
        assertThat(RetryBackoff.stillRetryable(RetryBackoff.MAX_ATTEMPTS + 1)).isFalse();
    }

    /**
     * Clamped rather than throwing. An attempt count outside the schedule means the cap was lowered
     * while rows were in flight, and throwing would strand exactly the messages that had the most
     * trouble getting out.
     */
    @Test
    void an_attempt_count_outside_the_schedule_is_clamped_at_both_ends() {
        assertThat(RetryBackoff.delayAfter(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryBackoff.delayAfter(-3)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryBackoff.delayAfter(99)).isEqualTo(Duration.ofMinutes(60));
    }
}
