package dev.reception.appointments;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing that happened to an Appointment. Append-only.
 *
 * <p>The Appointment row holds where it <em>is</em>; this holds how it got there. "When was this
 * moved, and from what time?" is a question a business gets asked by a customer and cannot answer
 * from a mutable record — which is why there is no updater on this class and no {@code updated_at}
 * on its table.
 *
 * <p>The payload is {@code jsonb} because each event type carries a different shape: a reschedule
 * has two pairs of times, a cancellation has a reason, a status change has neither. Columns for all
 * of them would be a table that is mostly null.
 */
@Entity
@Table(name = "appointment_events")
public class AppointmentEvent extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30, updatable = false)
    private AppointmentEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private ActorType actorType;

    /** Null for a Customer and for the Receptionist, neither of which has a user row. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppointmentEvent() {
        // JPA.
    }

    public AppointmentEvent(
            UUID id,
            UUID businessId,
            UUID appointmentId,
            AppointmentEventType eventType,
            ActorType actorType,
            UUID actorId,
            Map<String, Object> payload,
            Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorId = actorId;
        // Copied into a LinkedHashMap rather than kept by reference: the caller builds this from
        // locals it is about to change, and key order is what makes the stored JSON readable in
        // psql when someone is reconstructing what happened.
        this.payload = new LinkedHashMap<>(Objects.requireNonNull(payload, "payload"));
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID appointmentId() {
        return appointmentId;
    }

    public AppointmentEventType eventType() {
        return eventType;
    }

    public ActorType actorType() {
        return actorType;
    }

    public UUID actorId() {
        return actorId;
    }

    public Map<String, Object> payload() {
        return Map.copyOf(payload);
    }

    public Instant createdAt() {
        return createdAt;
    }
}
