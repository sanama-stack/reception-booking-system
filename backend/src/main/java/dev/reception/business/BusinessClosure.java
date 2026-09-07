package dev.reception.business;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A period during which a Business is closed regardless of its Business Hours.
 *
 * <p>Stored as instants rather than as the local dates the owner typed. Business Hours are
 * wall-clock because they recur and must survive a daylight-saving change; a closure does not
 * recur, so it is an actual span of time and is stored as one (ADR-0003). The availability engine
 * then subtracts it with exactly the same range algebra it uses for appointments and time off,
 * rather than carrying a second representation it has to convert on every call.
 *
 * <p>The conversion happens once, at write time, in {@link ClosureService}.
 */
@Entity
@Table(name = "business_closures")
public class BusinessClosure extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Exclusive: a closure ends at the instant the business is open again. */
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessClosure() {
        // JPA.
    }

    public BusinessClosure(UUID id, UUID businessId, Instant startsAt, Instant endsAt, String reason, Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
        if (!startsAt.isBefore(endsAt)) {
            // Mirrors the CHECK constraint, so the failure names the rule rather than the index.
            throw new IllegalArgumentException("A closure must start before it ends");
        }
        this.reason = reason;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID businessId() {
        return businessId;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
