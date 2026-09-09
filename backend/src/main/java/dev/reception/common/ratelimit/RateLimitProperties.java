package dev.reception.common.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

/**
 * The rate-limit policy, and the switch that turns it off.
 *
 * <p>Disabled in the test profile by default: a suite that authenticates a hundred times would
 * otherwise start failing on the hundred-and-first for reasons that have nothing to do with what it
 * is testing. The limits themselves are still exercised, by tests that turn them back on
 * deliberately.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Every limit in docs/06-security.md §5. Chat joins the list in phase 09.
     *
     * <p><strong>Order is significant.</strong> {@code RateLimitFilter} takes the first policy that
     * matches, so a narrower pattern must precede the wider one it sits inside. Availability before
     * the general business read, and the lookup before the general appointment write — reversed,
     * the tight limit is unreachable and the endpoint it was protecting is protected by the loose
     * one instead, silently and with nothing failing.
     */
    public List<RateLimitPolicy> policies() {
        return List.of(
                // Credential stuffing. Ten attempts, then a quarter of an hour.
                new RateLimitPolicy("login", HttpMethod.POST, "/auth/login", 10, Duration.ofMinutes(15)),
                // Account spam. Registration is cheap for us and valuable to an abuser.
                new RateLimitPolicy("register", HttpMethod.POST, "/auth/register", 5, Duration.ofHours(1)),

                // Brute-forcing a Confirmation Code. The tightest limit in the system, and first in
                // this list so nothing wider can shadow it.
                new RateLimitPolicy(
                        "public-lookup", HttpMethod.POST, "/public/appointments/lookup", 5, Duration.ofHours(1)),
                // Spam bookings. A write, and the one that creates rows a business has to deal with.
                new RateLimitPolicy(
                        "public-booking",
                        HttpMethod.POST,
                        "/public/businesses/*/appointments",
                        10,
                        Duration.ofHours(1)),
                /*
                 * Customer cancel and reschedule. Not in the §5 table, which predates the Manage
                 * Link page, but the Definition of Done requires every public endpoint to be
                 * limited and an unlimited write is an unlimited write.
                 *
                 * Looser than booking because it cannot create anything: each request needs a proof
                 * that already names one existing appointment, and the worst an attacker with one
                 * can do is cancel it — which the Cancellation Window and idempotence already bound.
                 * A customer talking themselves through two or three reschedules should not meet a
                 * limit written for abuse.
                 */
                new RateLimitPolicy(
                        "public-appointment-change",
                        HttpMethod.POST,
                        "/public/appointments/*/**",
                        20,
                        Duration.ofHours(1)),

                // Computation, but read-only. Before the general business read below, which its
                // path also matches.
                new RateLimitPolicy(
                        "public-availability",
                        HttpMethod.GET,
                        "/public/businesses/*/availability",
                        60,
                        Duration.ofMinutes(1)),
                // The same computation for a rescheduling Customer, so the same budget.
                new RateLimitPolicy(
                        "public-manage-availability",
                        HttpMethod.GET,
                        "/public/appointments/manage/availability",
                        60,
                        Duration.ofMinutes(1)),
                // Cheap reads: the profile, the services, the employees.
                new RateLimitPolicy(
                        "public-business", HttpMethod.GET, "/public/businesses/**", 120, Duration.ofMinutes(1)),
                // Resolving a Manage Link. A cheap read too, and one a customer may refresh.
                new RateLimitPolicy(
                        "public-manage", HttpMethod.GET, "/public/appointments/manage", 120, Duration.ofMinutes(1)));
    }
}
