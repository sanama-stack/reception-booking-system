package dev.reception.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues and reads the short-lived access token.
 *
 * <p>We are both the issuer and the resource server (ADR-0001). Spring Security is nonetheless
 * configured as a resource server from the first commit, so adopting an external identity provider
 * later replaces the issuer and changes no controller.
 *
 * <p>The token carries the tenant. {@code business_id} is derived from the user's Membership at
 * issue time and signed into the token, so a request never has to name its own tenant and the
 * server never has to re-read the membership to find out. The cost is bounded by the 15-minute
 * TTL: a membership revoked mid-window takes effect at the next refresh, which is the standard
 * trade-off a short access token exists to make acceptable.
 */
@Service
public class JwtService {

    /** docs/06-security.md §2. Short, because it is the window in which a revoked membership survives. */
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    public static final String ISSUER = "reception";
    public static final String CLAIM_BUSINESS_ID = "bid";
    public static final String CLAIM_ROLE = "role";

    private final JwtEncoder encoder;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    /** Mints an access token for one membership. */
    public IssuedAccessToken issue(UUID userId, UUID businessId, Role role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ACCESS_TOKEN_TTL);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(userId.toString())
                .claim(CLAIM_BUSINESS_ID, businessId.toString())
                .claim(CLAIM_ROLE, role.name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, expiresAt);
    }

    /**
     * Reads the three claims this application depends on out of a validated token.
     *
     * <p>Signature and expiry were already checked by the decoder; anything malformed at this point
     * is a token we signed ourselves and got wrong, so it fails loudly rather than defaulting.
     */
    public static AuthenticatedUser principalOf(Jwt jwt) {
        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(CLAIM_BUSINESS_ID)),
                Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE)));
    }

    /** The token value and the instant it stops being accepted, which is also the cookie's max age. */
    public record IssuedAccessToken(String value, Instant expiresAt) {}
}
