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
     * The phase 02 policies. Public booking, availability, lookup and chat join this list in
     * phases 08 and 09 (docs/06-security.md §5).
     */
    public List<RateLimitPolicy> policies() {
        return List.of(
                // Credential stuffing. Ten attempts, then a quarter of an hour.
                new RateLimitPolicy("login", HttpMethod.POST, "/auth/login", 10, Duration.ofMinutes(15)),
                // Account spam. Registration is cheap for us and valuable to an abuser.
                new RateLimitPolicy("register", HttpMethod.POST, "/auth/register", 5, Duration.ofHours(1)));
    }
}
