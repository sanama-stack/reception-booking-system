package dev.reception.notifications;

/**
 * How a message reaches the Customer.
 *
 * <p>One member in the MVP, and it is still an enum rather than an implied constant. The column
 * exists because SMS is the obvious second channel and because {@code EmailSender} is a port: the
 * day a second adapter arrives, the row already says which one rendered it. A boolean or an absent
 * column would have to be migrated on a table that is by then large.
 */
public enum NotificationChannel {
    EMAIL
}
