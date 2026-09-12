package dev.reception.auth;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.ids.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>The token is an opaque 256-bit random value; only its SHA-256 hash is stored, so a database
 * dump does not yield usable sessions. Every refresh <em>rotates</em>: the presented token is
 * revoked and a new one in the same family is issued.
 *
 * <p>The consequence worth understanding is replay detection. A token can be used exactly once, so
 * a second presentation means two parties hold it — the legitimate client and whoever captured it.
 * We cannot tell which one is asking, so the entire family is revoked and both must sign in again.
 * That is the standard, and deliberately blunt, response to a stolen refresh token
 * (docs/06-security.md §2).
 */
@Service
public class RefreshTokenService {

    /** docs/01-prd.md FR-1. */
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_USER_AGENT_LENGTH = 400;

    private final RefreshTokenRepository tokens;
    private final RefreshTokenFamilyRevoker familyRevoker;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository tokens, RefreshTokenFamilyRevoker familyRevoker, IdGenerator ids, Clock clock) {
        this.tokens = tokens;
        this.familyRevoker = familyRevoker;
        this.ids = ids;
        this.clock = clock;
    }

    /** Starts a new family. Called on login and on registration — that is, once per sign-in. */
    @Transactional
    public String issueNewFamily(UUID userId, RequestFingerprint fingerprint) {
        return issue(userId, ids.newId(), fingerprint);
    }

    /**
     * Validates the presented token, revokes it, and issues its successor.
     *
     * @throws ApiException {@code TOKEN_REUSED} when the token was already used — the family is
     *     revoked before this throws; {@code UNAUTHENTICATED} when it is unknown or expired
     */
    @Transactional
    public Rotation rotate(String presentedToken, RequestFingerprint fingerprint) {
        Instant now = clock.instant();
        RefreshToken existing = tokens.findByTokenHash(hash(presentedToken))
                // An unknown hash is either a forgery or a token from a database that has since
                // been reset. Neither is a family we can revoke, so there is nothing to do but
                // refuse.
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNAUTHENTICATED, "That session is no longer valid. Please sign in again."));

        if (existing.isRevoked()) {
            // Replay. The token is single-use, so a second presentation means the value leaked.
            // The revocation commits in its own transaction, because the exception below would
            // otherwise roll it back and leave the stolen token live.
            familyRevoker.revokeFamily(existing.familyId(), existing.userId(), fingerprint.ip());
            throw new ApiException(
                    ErrorCode.TOKEN_REUSED,
                    "This session was ended for security reasons. Please sign in again.");
        }

        if (existing.isExpiredAt(now)) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED, "That session has expired. Please sign in again.");
        }

        existing.revoke(now);
        tokens.save(existing);
        String successor = issue(existing.userId(), existing.familyId(), fingerprint);
        return new Rotation(existing.userId(), successor);
    }

    /** Revokes one token if it is known. Logout is idempotent, so an unknown token is not an error. */
    @Transactional
    public Optional<UUID> revoke(String presentedToken) {
        Optional<RefreshToken> existing = tokens.findByTokenHash(hash(presentedToken));
        existing.ifPresent(token -> {
            token.revoke(clock.instant());
            tokens.save(token);
        });
        // The user whose session this was, so the revocation can be audited (docs/06-security.md
        // §14). Empty for an unknown or already-forgotten token, which is a successful logout and
        // not an event about anybody.
        return existing.map(RefreshToken::userId);
    }

    private String issue(UUID userId, UUID familyId, RequestFingerprint fingerprint) {
        Instant now = clock.instant();
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        tokens.save(new RefreshToken(
                ids.newId(),
                userId,
                hash(value),
                familyId,
                now.plus(REFRESH_TOKEN_TTL),
                now,
                fingerprint.truncatedUserAgent(),
                fingerprint.ip()));
        return value;
    }

    /**
     * SHA-256, not BCrypt. The input is 256 bits of our own randomness rather than a human-chosen
     * password, so there is nothing to brute-force and a deliberately slow hash would only make
     * every refresh slower. It is also what lets the lookup be an indexed equality match.
     */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** Where a session was created from, for the user's session list later. Both values are optional. */
    public record RequestFingerprint(String userAgent, String ip) {

        public String truncatedUserAgent() {
            if (userAgent == null) {
                return null;
            }
            // Attacker-controlled and unbounded; the column is not.
            return userAgent.length() <= MAX_USER_AGENT_LENGTH
                    ? userAgent
                    : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        }
    }

    /** The owner of the rotated session, and the successor token to hand back. */
    public record Rotation(UUID userId, String refreshToken) {}
}
