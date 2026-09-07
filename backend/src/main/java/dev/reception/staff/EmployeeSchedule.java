package dev.reception.staff;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One interval of one day on which an Employee is willing to work.
 *
 * <p>Wall-clock times against a day of week, never instants, so 09:00 stays 09:00 across a
 * daylight-saving change (ADR-0003). Several rows may share a day and are unioned — a split shift is
 * one Employee with a gap, not two employees.
 *
 * <p><strong>A Working Schedule may be wider than Business Hours and is stored as given.</strong>
 * Phase 05 intersects the two. "When is this person willing to work" and "when are we open" are
 * genuinely different facts, and storing only their intersection would mean re-editing every
 * employee whenever the opening hours moved.
 */
@Entity
@Table(name = "employee_schedules")
public class EmployeeSchedule extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    /** Stored as the ISO number so nothing has to translate; see the schema note in V4. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "starts_at", nullable = false)
    private LocalTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalTime endsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmployeeSchedule() {
        // JPA.
    }

    public EmployeeSchedule(
            UUID id,
            UUID businessId,
            UUID employeeId,
            DayOfWeek dayOfWeek,
            LocalTime startsAt,
            LocalTime endsAt,
            Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        this.dayOfWeek = (short) Objects.requireNonNull(dayOfWeek, "dayOfWeek").getValue();
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID employeeId() {
        return employeeId;
    }

    public DayOfWeek dayOfWeek() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime startsAt() {
        return startsAt;
    }

    public LocalTime endsAt() {
        return endsAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
