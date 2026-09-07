package dev.reception.auth;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An authenticated principal who signs in to the dashboard.
 *
 * <p>Belongs to a Business through a {@link Membership} and deliberately carries no
 * {@code business_id} of its own, so a user belonging to two businesses later needs no schema
 * change (ADR-0006).
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    /**
     * Stored in a {@code citext} column, so uniqueness and lookup are case-insensitive at the
     * storage layer rather than depending on every call site remembering to lowercase.
     *
     * <p>The JDBC type is declared explicitly because the driver reports {@code citext} as
     * {@code Types#OTHER}. Hibernate maps a {@code String} to {@code VARCHAR} by default, and
     * {@code ddl-auto: validate} refuses to start on the mismatch — correctly, since the whole
     * point of validation is that the entity and the migration cannot silently disagree.
     */
    @JdbcTypeCode(SqlTypes.OTHER)
    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    /** BCrypt, cost 12. The only stored form of a password (docs/06-security.md §2). */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA.
    }

    public User(UUID id, String email, String passwordHash, String fullName, Instant now) {
        super(id);
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String fullName() {
        return fullName;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
