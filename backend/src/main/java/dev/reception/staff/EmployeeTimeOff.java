package dev.reception.staff;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A span during which one Employee is unavailable regardless of their Working Schedule.
 *
 * <p>Stored as instants, converted from business-local dates at write time, exactly as a Business
 * Closure is — so phase 05 subtracts the two with one piece of range algebra rather than two
 * (ADR-0003). The conversion lives in {@link TimeOffService} and nowhere else.
 *
 * <p>The stored range is half-open: {@code endsAt} is the start of the day after the owner's last
 * day off. Two adjacent absences then meet exactly rather than overlapping or leaving an hour
 * between them.
 */
@Entity
@Table(name = "employee_time_off")
public class EmployeeTimeOff extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmployeeTimeOff() {
        // JPA.
    }

    public EmployeeTimeOff(
            UUID id, UUID businessId, UUID employeeId, Instant startsAt, Instant endsAt, String reason, Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
        this.reason = reason;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID employeeId() {
        return employeeId;
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
