package dev.reception.notifications;

import dev.reception.tenancy.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The tenant-facing view of the outbox: what the enqueuer writes and what a screen may read.
 *
 * <p>Every method names the tenant, and {@code TenantRepositoryShapeTest} enforces it. The poller's
 * claim is deliberately not here — it is the one query against this table that must cross tenants,
 * and it lives in {@link NotificationClaimRepository} so that this interface can keep the rule
 * without an exception carved into it. A repository with one method that breaks its own convention
 * is a repository whose convention no longer stops anything.
 */
@TenantScoped
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** One Appointment's outbox, oldest first. */
    List<Notification> findByBusinessIdAndAppointmentIdOrderByCreatedAt(UUID businessId, UUID appointmentId);

    /**
     * The rows a cancellation or a reschedule has to supersede.
     *
     * <p>Takes a status collection rather than {@code PENDING} alone, because the caller's question
     * is "what is still live for this appointment" and the answer that matters differs by caller:
     * cancelling supersedes what has not gone out, while deciding whether a reminder may be
     * re-enqueued has to count the ones that already have.
     */
    List<Notification> findByBusinessIdAndAppointmentIdAndStatusIn(
            UUID businessId, UUID appointmentId, Collection<NotificationStatus> statuses);

    /** The same question narrowed to one type — the reminder a reschedule is about to move. */
    List<Notification> findByBusinessIdAndAppointmentIdAndTypeAndStatusIn(
            UUID businessId, UUID appointmentId, NotificationType type, Collection<NotificationStatus> statuses);
}
