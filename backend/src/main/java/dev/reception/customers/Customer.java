package dev.reception.customers;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A person who books with one Business, identified by their phone number.
 *
 * <p><strong>No password, no account, no global identity</strong> (CONTEXT.md). The same human at
 * two businesses is two rows — a deliberate cost, paid so there is no cross-tenant customer record
 * to leak and no join a later feature could widen by accident.
 *
 * <p>{@code phone} is E.164 and is the identity key together with {@code businessId}. It is not
 * mutable through booking: see {@link #applyCorrection}.
 */
@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    /** E.164, normalised on write. Half of the identity key, and therefore never updated here. */
    @Column(nullable = false, length = 20, updatable = false)
    private String phone;

    @Column(length = 254)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {
        // JPA.
    }

    public Customer(UUID id, UUID businessId, String fullName, String phone, String email, Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
        this.phone = Objects.requireNonNull(phone, "phone");
        this.email = email;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * An explicit correction by the Business — the only way a Customer's details change.
     *
     * <p>Booking does <em>not</em> call this. A returning phone number with a different name is
     * usually someone booking for a family member, and overwriting the stored name would lose the
     * record of who the customer actually is (docs/03-data-model.md §2). The Appointment records the
     * name it was given; the Customer keeps theirs.
     *
     * <p>Absent leaves, blank clears, a value sets — the {@code PATCH} rule stated once per entity.
     */
    void applyCorrection(String newFullName, String newEmail, Instant now) {
        if (newFullName != null) {
            this.fullName = newFullName;
        }
        if (newEmail != null) {
            this.email = newEmail.isBlank() ? null : newEmail;
        }
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID businessId() {
        return businessId;
    }

    public String fullName() {
        return fullName;
    }

    public String phone() {
        return phone;
    }

    public String email() {
        return email;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
