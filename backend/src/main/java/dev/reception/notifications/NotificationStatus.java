package dev.reception.notifications;

/**
 * Where a queued message is in its life.
 *
 * <p><strong>{@code PENDING} and {@code SENT} together mean "live"</strong> — that is the set the
 * partial unique index uses, and it is why the two terminal-ish states are separated from the two
 * that are not. A message that is owed and a message that was delivered are both reasons not to
 * enqueue another of the same type; a cancelled or failed one is not.
 */
public enum NotificationStatus {

    /** Owed. The poller will claim it once {@code scheduledFor} has passed. */
    PENDING,

    /** Delivered to the SMTP transport, with {@code sentAt} recorded. */
    SENT,

    /**
     * Retries exhausted. {@code lastError} says why. A failed row is deliberately left in the table
     * rather than deleted: it is the only trace that a message was owed and never arrived.
     */
    FAILED,

    /**
     * Superseded before it was due — the reminder for a time the appointment no longer occupies,
     * or the pending mail of an appointment that was called off. Never sent, and out of the live
     * index so its replacement can be enqueued.
     */
    CANCELLED
}
