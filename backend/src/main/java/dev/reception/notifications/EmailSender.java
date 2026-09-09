package dev.reception.notifications;

/**
 * The port out to a mail transport.
 *
 * <p>An interface with one method because that is the entire surface the outbox needs, and because
 * the poller's tests need to make a send fail on demand — which is impossible against a real SMTP
 * server and trivial against this.
 *
 * <p><strong>The contract is "delivered to the transport", not "delivered to the human".</strong>
 * SMTP accepting a message says nothing about whether it was later bounced or filed as spam. That
 * is a real limit of the design and is the reason a {@code SENT} row means what it says and no more.
 */
public interface EmailSender {

    /**
     * Sends, or throws.
     *
     * <p>Throwing is the only way to report failure — there is no boolean return. A caller that
     * ignored a false would leave a row marked {@code SENT} that never went anywhere, and the
     * outbox's whole value is that its statuses are true.
     *
     * @throws EmailDeliveryException when the transport refused or could not be reached
     */
    void send(String recipient, RenderedEmail message);
}
