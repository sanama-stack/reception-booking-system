package dev.reception.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends one batch of due notifications. The <em>what</em>; {@link NotificationPoller} is the
 * <em>when</em>.
 *
 * <p>Split from the poller for two reasons, and the first is not stylistic. A {@code @Scheduled}
 * method that called a {@code @Transactional} one on {@code this} would bypass the proxy and run
 * with no transaction at all — the {@code FOR UPDATE SKIP LOCKED} locks would be released statement
 * by statement, and two instances would happily send the same rows. Across two beans the call goes
 * through the proxy and the transaction is real. The second reason is that tests can drive a batch
 * directly without waiting on a scheduler.
 *
 * <p><strong>One transaction for the whole batch, and a failure inside it is not an exception.</strong>
 * A row that will not send is recorded as a failed attempt and the loop continues; nothing is
 * rethrown, because a rollback here would undo the {@code SENT} marks of every row that did go out
 * and they would all be sent a second time. That is the concrete meaning of "a failing row never
 * blocks the others".
 *
 * <p><strong>Delivery is at-least-once, and honestly so.</strong> If the process dies between the
 * transport accepting a message and this transaction committing, the row is still {@code PENDING}
 * and the next poll sends it again. Exactly-once would need the mail server to participate in the
 * transaction, which no mail server does. Duplicating a confirmation email is the right failure to
 * choose over losing one.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationClaimRepository claims;
    private final EmailSender email;
    private final Clock clock;
    private final int batchSize;

    public NotificationDispatcher(
            NotificationClaimRepository claims,
            EmailSender email,
            Clock clock,
            @Value("${app.notifications.batch-size:50}") int batchSize) {
        this.claims = claims;
        this.email = email;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Claims up to {@code batchSize} due rows, sends each, and records the outcome.
     *
     * @return how many were sent successfully, which is what the poller logs and the tests assert on
     */
    @Transactional
    public int dispatchDueBatch() {
        Instant now = clock.instant();
        List<Notification> due = claims.claimDue(now, batchSize);
        if (due.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Notification notification : due) {
            if (deliver(notification, now)) {
                sent++;
            }
        }
        claims.saveAll(due);
        return sent;
    }

    private boolean deliver(Notification notification, Instant now) {
        try {
            email.send(notification.recipientEmail(), new RenderedEmail(
                    notification.subject(), notification.bodyHtml(), notification.bodyText()));
            notification.markSent(now);
            return true;
        } catch (EmailDeliveryException failure) {
            int attemptsAfter = notification.attempts() + 1;
            boolean retryable = RetryBackoff.stillRetryable(attemptsAfter);
            notification.markAttemptFailed(
                    failure.getMessage(), retryable, now.plus(RetryBackoff.delayAfter(attemptsAfter)));

            // The id and the type, never the recipient, the subject or a body. The bodies carry the
            // Confirmation Code and the Manage Link, and a log is exactly the wrong place for a
            // capability token (docs/06-security.md §10).
            if (retryable) {
                log.warn(
                        "Notification {} ({}) failed on attempt {}, retrying: {}",
                        notification.getId(),
                        notification.type(),
                        attemptsAfter,
                        failure.getMessage());
            } else {
                log.error(
                        "Notification {} ({}) failed permanently after {} attempts: {}",
                        notification.getId(),
                        notification.type(),
                        attemptsAfter,
                        failure.getMessage());
            }
            return false;
        }
    }
}
