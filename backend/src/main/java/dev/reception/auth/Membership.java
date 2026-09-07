package dev.reception.auth;

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
 * The link between a {@link User} and a Business, carrying that user's role.
 *
 * <p>This is the record every authenticated request's tenancy derives from: the membership names
 * the business, so {@code business_id} never has to arrive from the client
 * (docs/02-product-architecture.md §4).
 *
 * <p>The foreign keys are held as raw ids rather than {@code @ManyToOne} associations. Nothing in
 * this phase navigates them, and a lazy association is a query waiting to happen in a place that
 * did not ask for one.
 */
@Entity
@Table(name = "memberships")
public class Membership extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    /**
     * Mapped as a string, matching the {@code CHECK} constraint. Ordinal mapping would make the
     * database unreadable and would silently reinterpret every row if the enum were reordered.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Membership() {
        // JPA.
    }

    public Membership(UUID id, UUID userId, UUID businessId, Role role, Instant now) {
        super(id);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.role = Objects.requireNonNull(role, "role");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID userId() {
        return userId;
    }

    public UUID businessId() {
        return businessId;
    }

    public Role role() {
        return role;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
