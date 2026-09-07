package dev.reception.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import java.time.Duration;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

/**
 * One rate-limit rule: a method, a path pattern, and how many requests an address may make.
 *
 * <p>The limits themselves are the table in docs/06-security.md §5. They are declared as data
 * rather than annotations so the whole policy is readable in one place — which is what you want
 * when the question is "is anything unprotected?".
 */
public record RateLimitPolicy(String name, HttpMethod method, String pathPattern, long capacity, Duration window) {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    public boolean matches(String requestMethod, String path) {
        return method.matches(requestMethod) && MATCHER.match(pathPattern, path);
    }

    /**
     * A greedy refill over the whole window rather than a smooth drip.
     *
     * <p>The difference is visible to a user: with an interval refill, ten failed logins are
     * followed by fifteen minutes of nothing and then ten more attempts. A smooth refill would
     * hand back one attempt every ninety seconds, which reads as the limit never quite clearing.
     */
    public BucketConfiguration bucketConfiguration() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(capacity, window).build())
                .build();
    }
}
