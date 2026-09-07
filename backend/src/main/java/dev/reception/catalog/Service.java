package dev.reception.catalog;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Something a Business offers that a Customer can book, carrying a duration and a price.
 *
 * <p>The class is named for the domain term, which costs one fully-qualified
 * {@code @org.springframework.stereotype.Service} in this package and is worth it: CONTEXT.md makes
 * the vocabulary binding, and renaming the central noun of the catalog to dodge a Spring annotation
 * would put a framework detail into the language the whole team speaks.
 *
 * <p><strong>Deactivated, never deleted, once booked.</strong> Appointments reference a Service
 * forever and record what was sold; removing the row would rewrite history into a dangling id
 * (docs/03-data-model.md §1).
 */
@Entity
@Table(name = "services")
public class Service extends BaseEntity {

    /** The finest slot stride an owner may choose, and therefore the grid a duration must land on. */
    public static final int DURATION_GRANULARITY_MINUTES = 5;

    public static final int MIN_DURATION_MINUTES = 5;

    /** Twenty-four hours. A service longer than a day is a project, not an appointment. */
    public static final int MAX_DURATION_MINUTES = 1440;

    public static final int MAX_BUFFER_MINUTES = 240;

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /**
     * Padding that blocks the Employee's time without appearing in what the Customer booked. Buffers
     * are not required to fit inside Business Hours (CONTEXT.md), which is why phase 05 applies them
     * to the Employee's occupancy rather than to the offered slot.
     */
    @Column(name = "buffer_before_minutes", nullable = false)
    private int bufferBeforeMinutes;

    @Column(name = "buffer_after_minutes", nullable = false)
    private int bufferAfterMinutes;

    @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAmount;

    /**
     * Denormalised from the Business at creation and never patched.
     *
     * <p>Re-stamping it when a Business changes currency would silently reprice every service —
     * 60.00 USD is not 60.00 GEL — so the code the price was written in stays with the price. See
     * {@link ServiceCatalogService#create}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Service() {
        // JPA.
    }

    public Service(
            UUID id,
            UUID businessId,
            String name,
            String description,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            BigDecimal priceAmount,
            String currency,
            Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.durationMinutes = durationMinutes;
        this.bufferBeforeMinutes = bufferBeforeMinutes;
        this.bufferAfterMinutes = bufferAfterMinutes;
        this.priceAmount = Objects.requireNonNull(priceAmount, "priceAmount");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.active = true;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Absent leaves a field alone; a blank description clears it. The same {@code PATCH} semantics
     * the business package states once in {@code Business.apply} — absent leaves, blank clears, a
     * value sets — so an owner who empties the description box does not have to find a second
     * gesture for it.
     *
     * <p>{@code name} is required, so blank is not a clear here; the caller rejects it before
     * arriving.
     */
    void apply(
            String newName,
            String newDescription,
            Integer newDurationMinutes,
            Integer newBufferBeforeMinutes,
            Integer newBufferAfterMinutes,
            BigDecimal newPriceAmount,
            Instant now) {
        if (newName != null) {
            this.name = newName;
        }
        if (newDescription != null) {
            this.description = newDescription.isBlank() ? null : newDescription;
        }
        if (newDurationMinutes != null) {
            this.durationMinutes = newDurationMinutes;
        }
        if (newBufferBeforeMinutes != null) {
            this.bufferBeforeMinutes = newBufferBeforeMinutes;
        }
        if (newBufferAfterMinutes != null) {
            this.bufferAfterMinutes = newBufferAfterMinutes;
        }
        if (newPriceAmount != null) {
            this.priceAmount = newPriceAmount;
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

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public int durationMinutes() {
        return durationMinutes;
    }

    public int bufferBeforeMinutes() {
        return bufferBeforeMinutes;
    }

    public int bufferAfterMinutes() {
        return bufferAfterMinutes;
    }

    public BigDecimal priceAmount() {
        return priceAmount;
    }

    public String currency() {
        return currency;
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
