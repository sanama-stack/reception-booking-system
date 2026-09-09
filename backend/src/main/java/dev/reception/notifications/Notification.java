package dev.reception.notifications;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One outbox row: a message that is owed, was delivered, or was superseded.
 *
 * <p><strong>The body is stored, not rendered on the way out.</strong> {@code subject},
 * {@code bodyHtml} and {@code bodyText} are filled in when the row is created, inside the same
 * transaction as the state change that justified it. A later template edit therefore cannot alter a
 * message already queued, and — the reason that matters more — this row is the system's only record
 * of what the Customer actually received. Re-rendering at send time would leave it able to answer
 * "what would we say now" and never "what did we say".
 *
 * <p><strong>{@code recipientEmail} is copied, not joined.</strong> The address a message went to is
 * part of what happened. Reading it back through the Customer would report wherever a later
 * correction points, which is a different fact wearing the same name.
 *
 * <p>No {@code updatedAt} and no {@code @Version}. The only writer of a claimed row is the poller,
 * which holds a row lock taken by {@code FOR UPDATE SKIP LOCKED} for the length of its transaction —
 * so the contention an optimistic version exists to detect cannot arise here. Enqueue-side races are
 * settled by the partial unique index instead.
 */
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private NotificationChannel channel;

    @Column(name = "recipient_email", nullable = false, length = 254, updatable = false)
    private String recipientEmail;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(name = "body_html", nullable = false, updatable = false)
    private String bodyHtml;

    @Column(name = "body_text", nullable = false, updatable = false)
    private String bodyText;

    /**
     * When this row is next due to be attempted — not a frozen record of when it was first owed.
     * A booking confirmation starts at {@code now}, a reminder at {@code startsAt − 24h}, and a
     * failed attempt pushes it forward by {@link RetryBackoff}. That is what lets the poller's claim
     * query be nothing but {@code status = 'PENDING' AND scheduled_for <= now()}: the backoff is
     * expressed in the data the query already reads rather than in a second column it would have to
     * agree with.
     */
    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // JPA.
    }

    public Notification(
            UUID id,
            UUID businessId,
            UUID appointmentId,
            NotificationType type,
            String recipientEmail,
            RenderedEmail rendered,
            Instant scheduledFor,
            Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId");
        this.type = Objects.requireNonNull(type, "type");
        this.channel = NotificationChannel.EMAIL;
        this.recipientEmail = Objects.requireNonNull(recipientEmail, "recipientEmail");
        Objects.requireNonNull(rendered, "rendered");
        this.subject = rendered.subject();
        this.bodyHtml = rendered.html();
        this.bodyText = rendered.text();
        this.scheduledFor = Objects.requireNonNull(scheduledFor, "scheduledFor");
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Delivered. {@code sentAt} is set in the same call as the status because the database rejects
     * one without the other ({@code notifications_sent_fields}) — the same shape as an Appointment's
     * cancellation fields, and for the same reason: a half-written outcome is unrepresentable rather
     * than merely unlikely.
     */
    void markSent(Instant now) {
        this.status = NotificationStatus.SENT;
        this.sentAt = Objects.requireNonNull(now, "now");
        this.attempts = this.attempts + 1;
        this.lastError = null;
    }

    /**
     * A send failed.
     *
     * <p>Whether this is the last word is the caller's decision, not this method's: the retry cap is
     * policy and lives in {@code RetryBackoff}. What this guarantees is that {@code attempts} always
     * counts the tries actually made, so the backoff and the cap are both computed from something
     * the row itself knows.
     *
     * @param stillRetryable false once the attempt cap is reached, which moves the row to
     *     {@code FAILED} and stops the poller ever claiming it again
     * @param nextAttemptAt when the row becomes eligible again; ignored when it has failed for good
     */
    void markAttemptFailed(String error, boolean stillRetryable, Instant nextAttemptAt) {
        this.attempts = this.attempts + 1;
        this.lastError = truncate(error);
        this.status = stillRetryable ? NotificationStatus.PENDING : NotificationStatus.FAILED;
        if (stillRetryable) {
            this.scheduledFor = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        }
    }

    /** Superseded before it was due. Never sent, and out of the live partial unique index. */
    void cancel() {
        this.status = NotificationStatus.CANCELLED;
    }

    /** {@code last_error} is text, but a driver stack trace in a column nobody reads is not free. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID appointmentId() {
        return appointmentId;
    }

    public NotificationType type() {
        return type;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public String recipientEmail() {
        return recipientEmail;
    }

    public String subject() {
        return subject;
    }

    public String bodyHtml() {
        return bodyHtml;
    }

    public String bodyText() {
        return bodyText;
    }

    public Instant scheduledFor() {
        return scheduledFor;
    }

    public NotificationStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
