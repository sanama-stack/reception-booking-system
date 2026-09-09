package dev.reception.notifications;

/**
 * What a queued message is about.
 *
 * <p>The four are not interchangeable copy variants: each one is a different fact about the
 * appointment, and the partial unique index in {@code V6__notifications.sql} keys on this value, so
 * adding a member changes what "already enqueued" means. A fifth type is a schema decision as much
 * as a template one.
 */
public enum NotificationType {

    /** Sent immediately on booking. Carries the Confirmation Code and the Manage Link. */
    BOOKING_CONFIRMATION,

    /**
     * Scheduled for {@code startsAt − 24h}. Not enqueued at all when that moment is already past —
     * see {@code NotificationEnqueuer}, which is where the rule lives rather than in the poller.
     */
    REMINDER_24H,

    /** Sent when an Appointment is called off, by either party. */
    CANCELLATION,

    /** Sent when an Appointment moves. Carries the new time and the unchanged Confirmation Code. */
    RESCHEDULE
}
