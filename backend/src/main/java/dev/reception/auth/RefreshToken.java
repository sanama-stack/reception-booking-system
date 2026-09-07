package dev.reception.auth;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One issued refresh token, stored only as a SHA-256 hash of the value the client holds.
 *
 * <p>Every refresh issues a new token in the same {@code familyId} and revokes the old one.
 * Presenting an already-revoked token means the value was captured, so the whole family is
 * revoked — the standard response to a stolen refresh token (docs/06-security.md §2).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** SHA-256 of the raw token, 64 lowercase hex characters. The raw value is never stored. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    /** Rotation lineage. Every token descended from one login shares this. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip")
    private String ip;

    protected RefreshToken() {
        // JPA.
    }

    public RefreshToken(
            UUID id,
            UUID userId,
            String tokenHash,
            UUID familyId,
            Instant expiresAt,
            Instant now,
            String userAgent,
            String ip) {
        super(id);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.userAgent = userAgent;
        this.ip = ip;
    }

    public UUID userId() {
        return userId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public UUID familyId() {
        return familyId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String userAgent() {
        return userAgent;
    }

    public String ip() {
        return ip;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Usable exactly once: live, unrevoked and unexpired. */
    public boolean isUsableAt(Instant now) {
        return !isRevoked() && !isExpiredAt(now);
    }

    /** Idempotent — re-revoking keeps the original instant, so an audit trail is not overwritten. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
