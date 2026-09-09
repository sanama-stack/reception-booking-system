package dev.reception.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link NotificationDispatcher} on a timer. Nothing else.
 *
 * <p><strong>{@code fixedDelay}, not {@code fixedRate}.</strong> The delay is measured from the end
 * of the previous run, so a batch that takes ninety seconds against a slow relay is not immediately
 * followed by another — {@code fixedRate} would queue runs up behind it and turn a slow mail server
 * into a growing backlog of overlapping pollers.
 *
 * <p>The kill switch is here rather than on the dispatcher, and the difference matters: tests need
 * to drive a batch on demand, and a {@code @ConditionalOnProperty} that removed the dispatcher bean
 * would take that away along with the timer. The {@code test} profile switches this off so a suite
 * is never racing a background thread that sends the rows it was about to assert on.
 *
 * <p>Exceptions are caught and logged rather than allowed to escape. An uncaught one from a
 * {@code @Scheduled} method kills nothing visibly — it simply means the next run happens and the
 * failure is swallowed by the scheduler — so catching it here is what makes the failure legible.
 */
@Component
public class NotificationPoller {

    private static final Logger log = LoggerFactory.getLogger(NotificationPoller.class);

    private final NotificationDispatcher dispatcher;
    private final boolean enabled;

    public NotificationPoller(
            NotificationDispatcher dispatcher, @Value("${app.notifications.poller-enabled:true}") boolean enabled) {
        this.dispatcher = dispatcher;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${app.notifications.poll-interval-ms:60000}",
            initialDelayString = "${app.notifications.poll-initial-delay-ms:10000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int sent = dispatcher.dispatchDueBatch();
            if (sent > 0) {
                log.info("Sent {} notification(s)", sent);
            }
        } catch (RuntimeException failure) {
            // Most often the database being unreachable, which claimDue surfaces before any mail is
            // attempted. Nothing is lost: every row is still PENDING and the next run claims it.
            log.error("Notification poll failed; rows remain pending", failure);
        }
    }
}
