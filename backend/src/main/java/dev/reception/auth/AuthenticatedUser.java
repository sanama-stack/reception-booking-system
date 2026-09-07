package dev.reception.auth;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is making the current request, and on behalf of which Business.
 *
 * <p>Every field is server-derived from a signed token. Nothing here can be influenced by a request
 * body or a query parameter, which is the property the whole isolation design rests on.
 */
public record AuthenticatedUser(UUID userId, UUID businessId, Role role) {

    /** Extracts the principal from a Spring Security authentication, or empty when unauthenticated. */
    public static AuthenticatedUser from(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token && token.getToken() != null) {
            Jwt jwt = token.getToken();
            return JwtService.principalOf(jwt);
        }
        throw new IllegalStateException("Request is not authenticated with a JWT");
    }
}
