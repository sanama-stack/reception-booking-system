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
 * <p>Phase 03 completed the row: profile, booking policy and the knowledge the Receptionist is
 * given. The booking settings are the ones the availability engine reads from phase 05 onward, so
 * they carry defaults rather than nulls — an engine that has to interpret a missing lead time is
 * an engine with a rule written in two places.
 *
 * <p>The mutation surface is package-private on purpose. {@code business.web} can read every
 * getter and change nothing; the only way in is {@link #apply(BusinessPatch, Instant)}, which
 * lives beside the rules it applies.
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

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "address_line", length = 200)
    private String addressLine;

    @Column(length = 120)
    private String city;

    /** ISO-3166-1 alpha-2. Same {@code char} treatment as {@code currency}, for the same reason. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String country;

    @Column(length = 20)
    private String phone;

    @Column(length = 254)
    private String email;

    @Column(length = 300)
    private String website;

    /** The stride slots are generated on, from phase 05. */
    @Column(name = "slot_interval_minutes", nullable = false)
    private int slotIntervalMinutes;

    @Column(name = "min_lead_time_minutes", nullable = false)
    private int minLeadTimeMinutes;

    @Column(name = "max_advance_days", nullable = false)
    private int maxAdvanceDays;

    @Column(name = "cancellation_window_hours", nullable = false)
    private int cancellationWindowHours;

    @Column(name = "cancellation_policy", columnDefinition = "text")
    private String cancellationPolicy;

    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled;

    /** Bounded on purpose — it enters every system prompt (docs/05-ai-architecture.md). */
    @Column(name = "ai_additional_info", length = 2000)
    private String aiAdditionalInfo;

    @Column(name = "ai_daily_cost_cap_cents", nullable = false)
    private int aiDailyCostCapCents;

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
        this.slotIntervalMinutes = BusinessDefaults.SLOT_INTERVAL_MINUTES;
        this.minLeadTimeMinutes = BusinessDefaults.MIN_LEAD_TIME_MINUTES;
        this.maxAdvanceDays = BusinessDefaults.MAX_ADVANCE_DAYS;
        this.cancellationWindowHours = BusinessDefaults.CANCELLATION_WINDOW_HOURS;
        this.aiEnabled = BusinessDefaults.AI_ENABLED;
        this.aiDailyCostCapCents = BusinessDefaults.AI_DAILY_COST_CAP_CENTS;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Applies a partial update.
     *
     * <p>Three cases, and the difference between the first two is the whole reason this is a
     * {@code PATCH} rather than a {@code PUT}:
     *
     * <ul>
     *   <li><strong>Absent</strong> ({@code null} on the patch) — leave the field as it is. A
     *       caller updating one setting must not silently erase the sixteen it did not mention.
     *   <li><strong>Blank</strong> ({@code ""}) — clear an optional field. This is what a form
     *       sends when the owner empties the input, so clearing needs no separate gesture, and
     *       "set this to the empty string" is not a state distinct from "not set".
     *   <li><strong>A value</strong> — set it.
     * </ul>
     *
     * <p>The four required fields — name, slug, timezone, currency — accept only the first and
     * third: blank is rejected before this by {@code @Size(min = 1)}, because a business with no
     * name is not a state the column allows.
     *
     * <p>Values are assumed validated. Format, range and reachability checks belong to the
     * validators and {@code BusinessService}; what happens here is assignment.
     */
    void apply(BusinessPatch patch, Instant now) {
        if (patch.name() != null) {
            this.name = patch.name();
        }
        if (patch.slug() != null) {
            this.slug = patch.slug();
        }
        if (patch.timezone() != null) {
            this.timezone = patch.timezone();
        }
        if (patch.currency() != null) {
            this.currency = patch.currency();
        }

        this.description = replaceOptional(this.description, patch.description());
        this.addressLine = replaceOptional(this.addressLine, patch.addressLine());
        this.city = replaceOptional(this.city, patch.city());
        this.country = replaceOptional(this.country, patch.country());
        this.phone = replaceOptional(this.phone, patch.phone());
        this.email = replaceOptional(this.email, patch.email());
        this.website = replaceOptional(this.website, patch.website());
        this.cancellationPolicy = replaceOptional(this.cancellationPolicy, patch.cancellationPolicy());
        this.aiAdditionalInfo = replaceOptional(this.aiAdditionalInfo, patch.aiAdditionalInfo());

        if (patch.slotIntervalMinutes() != null) {
            this.slotIntervalMinutes = patch.slotIntervalMinutes();
        }
        if (patch.minLeadTimeMinutes() != null) {
            this.minLeadTimeMinutes = patch.minLeadTimeMinutes();
        }
        if (patch.maxAdvanceDays() != null) {
            this.maxAdvanceDays = patch.maxAdvanceDays();
        }
        if (patch.cancellationWindowHours() != null) {
            this.cancellationWindowHours = patch.cancellationWindowHours();
        }
        if (patch.aiEnabled() != null) {
            this.aiEnabled = patch.aiEnabled();
        }
        if (patch.aiDailyCostCapCents() != null) {
            this.aiDailyCostCapCents = patch.aiDailyCostCapCents();
        }

        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /** Absent leaves, blank clears, a value sets — the rule stated once. */
    private static String replaceOptional(String current, String replacement) {
        if (replacement == null) {
            return current;
        }
        return replacement.isBlank() ? null : replacement;
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

    public String description() {
        return description;
    }

    public String addressLine() {
        return addressLine;
    }

    public String city() {
        return city;
    }

    public String country() {
        return country;
    }

    public String phone() {
        return phone;
    }

    public String email() {
        return email;
    }

    public String website() {
        return website;
    }

    public int slotIntervalMinutes() {
        return slotIntervalMinutes;
    }

    public int minLeadTimeMinutes() {
        return minLeadTimeMinutes;
    }

    public int maxAdvanceDays() {
        return maxAdvanceDays;
    }

    public int cancellationWindowHours() {
        return cancellationWindowHours;
    }

    public String cancellationPolicy() {
        return cancellationPolicy;
    }

    public boolean aiEnabled() {
        return aiEnabled;
    }

    public String aiAdditionalInfo() {
        return aiAdditionalInfo;
    }

    public int aiDailyCostCapCents() {
        return aiDailyCostCapCents;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
