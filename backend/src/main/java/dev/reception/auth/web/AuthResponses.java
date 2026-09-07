package dev.reception.auth.web;

import dev.reception.auth.AuthService;
import java.util.UUID;

/**
 * Response bodies for {@code /auth/*}, written by hand rather than mapped from entities.
 *
 * <p>No password hash, no token, no internal setting can appear in one of these by accident,
 * because there is no path from an entity into them that does not pass through a named field.
 */
public final class AuthResponses {

    private AuthResponses() {}

    public record UserSummary(UUID id, String email, String fullName) {}

    public record BusinessSummary(UUID id, String name, String slug, String timezone, String currency) {}

    /** What registration, login, refresh and {@code /auth/me} all return. */
    public record SessionResponse(UserSummary user, BusinessSummary business, String role) {

        public static SessionResponse of(AuthService.Session session) {
            return new SessionResponse(
                    new UserSummary(
                            session.user().getId(), session.user().email(), session.user().fullName()),
                    new BusinessSummary(
                            session.business().getId(),
                            session.business().name(),
                            session.business().slug(),
                            session.business().timezone().getId(),
                            session.business().currency()),
                    session.role().name());
        }
    }
}
