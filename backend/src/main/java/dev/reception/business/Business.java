package dev.reception.business;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * The tenant. Every other record in the system belongs to exactly one Business.
 *
 * <p>Minimal in phase 02 — registration creates it, and phase 03 adds the profile, booking-settings
 * and AI columns. The {@code timezone} is already here because it is load-bearing from phase 03
 * onward: every recurring rule is wall-clock time interpreted in this zone (ADR-0003).
 */
@Entity
@Table(name = "businesses")
public class Business extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    /** Lowercase, URL-safe and unique: this is the public booking page's address. */
    @Column(nullable = false, length = 140)
    private String slug;

    /** A validated IANA zone id, e.g. {@code Asia/Tbilisi}. */
    @Column(nullable = false, length = 64)
    private String timezone;

    /**
     * ISO-4217, stored as {@code char(3)} rather than {@code varchar}. The JDBC type is declared
     * explicitly because Hibernate would otherwise expect {@code varchar} and schema validation
     * would refuse to start — which is exactly what {@code ddl-auto: validate} is for.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Business() {
        // JPA.
    }

    public Business(UUID id, String name, String slug, ZoneId timezone, String currency, Instant now) {
        super(id);
        this.name = Objects.requireNonNull(name, "name");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.timezone = Objects.requireNonNull(timezone, "timezone").getId();
        this.currency = Objects.requireNonNull(currency, "currency");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug;
    }

    /** Parsed rather than raw: a caller that wants the zone should not have to remember to validate it. */
    public ZoneId timezone() {
        return ZoneId.of(timezone);
    }

    public String currency() {
        return currency;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
