package dev.reception.ai.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link TranscriptPurge} on a timer. Nothing else.
 *
 * <p><strong>{@code fixedDelay}, not {@code fixedRate}</strong> — the notification poller's reason,
 * and it bites harder here. The first run against a database that has never been purged does the
 * most work of any run in its life, and {@code fixedRate} would queue the next one up behind it.
 *
 * <p><strong>The timer is configuration; the window is not.</strong> How often this wakes up changes
 * nothing about what is kept — a purge that runs hourly and one that runs daily both enforce the
 * same ninety days, to within the interval. The window itself is
 * {@link ConversationLimits#TRANSCRIPT_RETENTION_DAYS}, a constant, because a promise about other
 * people's data should be widened in a diff rather than in an environment variable.
 *
 * <p>Hourly by default rather than daily. Nothing here is expensive enough to justify a fixed hour,
 * and an interval short against the window means a restart can never skip a day's worth: a host
 * that reboots each night at midnight would, on a daily timer, purge nothing for as long as the
 * reboot kept landing before the run.
 *
 * <p>The kill switch is here rather than on {@link TranscriptPurge}, and the difference matters:
 * tests need to drive a batch on demand, and a {@code @ConditionalOnProperty} that removed the
 * purge bean would take that away along with the timer. The {@code test} profile switches this off
 * so a suite is never racing a background thread that is deleting the rows it was about to assert
 * on.
 *
 * <p>Exceptions are caught and logged rather than allowed to escape, for the poller's reason: an
 * uncaught exception from a {@code @Scheduled} method kills nothing visibly. Catching it is what
 * makes the failure legible — and a retention job that has silently stopped is a promise being
 * silently broken.
 */
@Component
public class TranscriptPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(TranscriptPurgeJob.class);

    private final TranscriptPurge purge;
    private final boolean enabled;

    public TranscriptPurgeJob(TranscriptPurge purge, @Value("${app.ai.retention.enabled:true}") boolean enabled) {
        this.purge = purge;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${app.ai.retention.interval-ms:3600000}",
            initialDelayString = "${app.ai.retention.initial-delay-ms:60000}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            purge.purgeBatch();
        } catch (RuntimeException failure) {
            // Nothing is lost and nothing is half-done: the batch is one transaction, so a failure
            // rolls it back whole and the next tick claims the same conversations again.
            log.error("Retention purge failed; transcripts past the window remain", failure);
        }
    }
}
