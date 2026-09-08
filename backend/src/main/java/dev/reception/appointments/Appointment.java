package dev.reception.appointments;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A confirmed reservation of one Employee's time for one Service on behalf of one Customer.
 *
 * <p><strong>Two pairs of times, and they are not the same pair.</strong> {@code startsAt} and
 * {@code endsAt} are what the Customer agreed to and what every screen shows. {@code blockedFrom}
 * and {@code blockedTo} are that span widened by the Service's Buffers, and they are what actually
 * makes the Employee unavailable — the columns the exclusion constraint compares
 * (docs/03-data-model.md §2). Both are stored because the Service's buffers can change afterwards,
 * and an Appointment must go on blocking the time it blocked when it was booked.
 *
 * <p><strong>The price is a snapshot.</strong> Copied from the Service at booking and never
 * recomputed. Without it, raising a price would rewrite last month's revenue.
 *
 * <p>Mutation is through the three named transitions below rather than through setters, so every
 * change an Appointment can undergo is visible in one place and each one leaves the row in a state
 * the database's own CHECK constraints accept.
 */
@Entity
@Table(name = "appointments")
public class Appointment extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "blocked_from", nullable = false)
    private Instant blockedFrom;

    @Column(name = "blocked_to", nullable = false)
    private Instant blockedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentStatus status;

    @Column(name = "price_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal priceAmount;

    /** {@code char(3)} in the schema; see {@code Service.currency} for why the type code is named. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    /**
     * Issued once and kept for life. A reschedule does <em>not</em> reissue it: the Customer is
     * holding a confirmation email with this code in it, and changing the code because the time
     * moved would lock them out of the appointment they were just told about.
     */
    @Column(name = "confirmation_code", nullable = false, length = 12, updatable = false)
    private String confirmationCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AppointmentSource source;

    @Column(name = "customer_note", length = 1000)
    private String customerNote;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private CancelledBy cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    /**
     * Optimistic locking for the case the exclusion constraint cannot see: two owners rescheduling
     * the <em>same</em> appointment in two tabs. Nothing overlaps, both writes are legal, and
     * without a version the second silently wins while the first person believes they moved it.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Appointment() {
        // JPA.
    }

    public Appointment(
            UUID id,
            UUID businessId,
            UUID employeeId,
            UUID serviceId,
            UUID customerId,
            Instant startsAt,
            Instant endsAt,
            Instant blockedFrom,
            Instant blockedTo,
            BigDecimal priceAmount,
            String currency,
            String confirmationCode,
            AppointmentSource source,
            String customerNote,
            Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
        this.blockedFrom = Objects.requireNonNull(blockedFrom, "blockedFrom");
        this.blockedTo = Objects.requireNonNull(blockedTo, "blockedTo");
        this.priceAmount = Objects.requireNonNull(priceAmount, "priceAmount");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.confirmationCode = Objects.requireNonNull(confirmationCode, "confirmationCode");
        this.source = Objects.requireNonNull(source, "source");
        this.customerNote = customerNote;
        this.status = AppointmentStatus.CONFIRMED;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Moves this Appointment in place, keeping its id and Confirmation Code.
     *
     * <p>An update rather than a cancel-and-create. Cancelling first releases the original time, and
     * a concurrent booking can take it in the gap before the new row is written — which leaves the
     * Customer with no appointment at all, having asked only to move one
     * (docs/09-phase-plan.md, phase 06).
     *
     * <p>The Employee may change with the time: the person free at the new hour need not be the one
     * who was free at the old one.
     */
    void moveTo(UUID newEmployeeId, Instant newStartsAt, Instant newEndsAt, Instant newBlockedFrom, Instant newBlockedTo, Instant now) {
        this.employeeId = Objects.requireNonNull(newEmployeeId, "employeeId");
        this.startsAt = Objects.requireNonNull(newStartsAt, "startsAt");
        this.endsAt = Objects.requireNonNull(newEndsAt, "endsAt");
        this.blockedFrom = Objects.requireNonNull(newBlockedFrom, "blockedFrom");
        this.blockedTo = Objects.requireNonNull(newBlockedTo, "blockedTo");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Releases the time. {@code cancelledAt} is set in the same call as the status, because the
     * database rejects one without the other ({@code appointments_cancel_fields}) — which is what
     * makes an incomplete cancellation unrepresentable rather than merely unlikely.
     */
    void cancel(CancelledBy by, String reason, Instant now) {
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledBy = Objects.requireNonNull(by, "cancelledBy");
        this.cancellationReason = reason;
        this.cancelledAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** {@code COMPLETED} or {@code NO_SHOW}. Legality is {@link AppointmentStatus#canMoveTo}'s. */
    void moveToStatus(AppointmentStatus next, Instant now) {
        this.status = Objects.requireNonNull(next, "status");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID employeeId() {
        return employeeId;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public UUID customerId() {
        return customerId;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public Instant blockedFrom() {
        return blockedFrom;
    }

    public Instant blockedTo() {
        return blockedTo;
    }

    public AppointmentStatus status() {
        return status;
    }

    public BigDecimal priceAmount() {
        return priceAmount;
    }

    public String currency() {
        return currency;
    }

    public String confirmationCode() {
        return confirmationCode;
    }

    public AppointmentSource source() {
        return source;
    }

    public String customerNote() {
        return customerNote;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public CancelledBy cancelledBy() {
        return cancelledBy;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
