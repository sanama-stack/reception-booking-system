package dev.reception.staff;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A bookable resource owned by a Business — someone who performs Services and has a Working
 * Schedule.
 *
 * <p><strong>An Employee is not a User</strong> (ADR-0006). {@code userId} is the seam for staff
 * login and nothing in the MVP writes it; it exists now so the table does not have to be altered
 * later under load.
 *
 * <p>Contact details are for the owner. No public endpoint selects them, and none may
 * (docs/06-security.md).
 */
@Entity
@Table(name = "employees")
public class Employee extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(length = 254)
    private String email;

    /** E.164, normalised on write using the Business's country. See {@link PhoneNumbers}. */
    @Column(length = 20)
    private String phone;

    @Column(name = "job_title", length = 120)
    private String jobTitle;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Employee() {
        // JPA.
    }

    public Employee(
            UUID id, UUID businessId, String fullName, String email, String phone, String jobTitle, Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
        this.email = email;
        this.phone = phone;
        this.jobTitle = jobTitle;
        this.active = true;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** Absent leaves, blank clears, a value sets — the {@code PATCH} rule stated once per entity. */
    void apply(String newFullName, String newEmail, String newPhone, String newJobTitle, Instant now) {
        if (newFullName != null) {
            this.fullName = newFullName;
        }
        if (newEmail != null) {
            this.email = newEmail.isBlank() ? null : newEmail;
        }
        if (newPhone != null) {
            this.phone = newPhone.isBlank() ? null : newPhone;
        }
        if (newJobTitle != null) {
            this.jobTitle = newJobTitle.isBlank() ? null : newJobTitle;
        }
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    void setActive(boolean newActive, Instant now) {
        this.active = newActive;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID userId() {
        return userId;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public String jobTitle() {
        return jobTitle;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
