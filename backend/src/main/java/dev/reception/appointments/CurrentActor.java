package dev.reception.appointments;

import dev.reception.auth.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The signed-in dashboard user, as an {@link Actor}.
 *
 * <p>The one place that reads the security context for this purpose, and it is deliberately outside
 * the application services — see {@link Actor} for why they take the actor as an argument instead.
 * The public and AI entry points construct their own and never reach this class.
 */
@Component
public class CurrentActor {

    /**
     * @throws IllegalStateException when there is no authenticated user, which is a programming
     *     error rather than an authorization failure: every caller of this is behind the filter
     *     chain that requires one
     */
    public Actor resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return Actor.user(AuthenticatedUser.from(authentication).userId());
    }
}
