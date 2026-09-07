package dev.reception.auth;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a token family in a transaction of its own.
 *
 * <p>This exists because of a trap that is easy to write and hard to notice. Replay detection ends
 * with an exception — the caller must be refused — and an exception rolls back the transaction it
 * was thrown in. Revoking the family inside that same transaction therefore <em>undoes the
 * revocation</em>: the response says "this session was ended for security reasons" while the stolen
 * token remains live. The security action has to outlive the refusal that reports it, so it commits
 * separately.
 *
 * <p>A separate bean rather than a second method on {@code RefreshTokenService}, because Spring's
 * proxying means a method calling its own {@code @Transactional} sibling gets no new transaction at
 * all — the annotation would be silently ignored, which is the same failure wearing a disguise.
 */
@Component
public class RefreshTokenFamilyRevoker {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenFamilyRevoker.class);

    private final RefreshTokenRepository tokens;
    private final Clock clock;

    public RefreshTokenFamilyRevoker(RefreshTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId, UUID userId) {
        int revoked = tokens.revokeFamily(familyId, clock.instant());
        // The user id is logged; the token is not. A log line is not a place to put a credential
        // (docs/06-security.md §10).
        log.warn("Refresh token replay detected for user {}; revoked {} tokens in family {}", userId, revoked, familyId);
    }
}
