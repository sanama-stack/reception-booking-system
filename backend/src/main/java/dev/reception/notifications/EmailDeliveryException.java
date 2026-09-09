package dev.reception.notifications;

/**
 * A send failed. Carries the transport's own message, which the poller records in
 * {@code last_error}.
 *
 * <p>Unchecked, because every caller of {@link EmailSender} is the poller and the poller's handling
 * is the same for every failure: count the attempt, record the reason, back off. A checked exception
 * would buy a compiler reminder to do what there is only one way to do.
 */
public class EmailDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
