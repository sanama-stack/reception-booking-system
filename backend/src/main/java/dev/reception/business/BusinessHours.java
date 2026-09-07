package dev.reception.business;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One interval a Business is open, on one day of the week.
 *
 * <p>Stored as wall-clock {@link LocalTime}, never as an instant: a business that opens at 09:00
 * opens at 09:00 on both sides of a daylight-saving transition (ADR-0003). The availability engine
 * converts these to instants in the business's zone, never the reverse.
 *
 * <p><strong>A day with no row is closed.</strong> Absence is meaningful here, not missing data.
 */
@Entity
@Table(name = "business_hours")
public class BusinessHours extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    /** ISO-8601: 1 = Monday .. 7 = Sunday, matching {@link DayOfWeek#getValue()}. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    protected BusinessHours() {
        // JPA.
    }

    public BusinessHours(UUID id, UUID businessId, DayOfWeek dayOfWeek, LocalTime opensAt, LocalTime closesAt) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.dayOfWeek = (short) Objects.requireNonNull(dayOfWeek, "dayOfWeek").getValue();
        this.opensAt = Objects.requireNonNull(opensAt, "opensAt");
        this.closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) {
            // Mirrors the CHECK constraint, so the failure names the rule rather than the index.
            throw new IllegalArgumentException("Business hours must open before they close");
        }
    }

    public UUID businessId() {
        return businessId;
    }

    public DayOfWeek dayOfWeek() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime opensAt() {
        return opensAt;
    }

    public LocalTime closesAt() {
        return closesAt;
    }
}
