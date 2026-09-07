package dev.reception.common.ratelimit;

import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.ProblemJsonWriter;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the per-endpoint, per-address limits in docs/06-security.md §5.
 *
 * <p>Buckets are held in memory. That is correct for the MVP's single instance and is the first
 * thing to externalise when running more than one — a documented accepted risk rather than an
 * oversight (docs/06-security.md §15). Bucket4j's API is unchanged by that move; only the backing
 * store is.
 *
 * <p>Runs before authentication on purpose: the point of the login limit is to bound attempts by
 * callers who cannot authenticate.
 */
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final List<RateLimitPolicy> policies;
    private final ProblemJsonWriter writer;

    /**
     * Keyed by policy name and client address. Unbounded in principle; bounded in practice by the
     * address space a single instance actually sees, and every entry is a few dozen bytes. A real
     * eviction policy arrives with the externalised store.
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, ProblemJsonWriter writer) {
        this.policies = properties.policies();
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitPolicy policy = policyFor(request);
        if (policy == null) {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.computeIfAbsent(
                policy.name() + "|" + clientAddress(request),
                key -> Bucket.builder()
                        .addLimit(policy.bucketConfiguration().getBandwidths()[0])
                        .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds =
                Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        writer.write(
                response,
                ErrorCode.RATE_LIMITED,
                "Too many requests. Try again in " + retryAfterSeconds + " seconds.",
                request.getRequestURI());
    }

    private RateLimitPolicy policyFor(HttpServletRequest request) {
        // The servlet path excludes the /api context path, which is what the policy patterns are
        // written against — the same paths the security matchers use.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return policies.stream()
                .filter(policy -> policy.matches(request.getMethod(), path))
                .findFirst()
                .orElse(null);
    }

    /**
     * The client's address.
     *
     * <p>This reads the peer address rather than a header on purpose: {@code X-Forwarded-For} is
     * trivially spoofed, and a filter that honoured it directly would let one attacker present as
     * an unlimited number of clients — turning the limit off for exactly the caller it exists to
     * stop. Whether the peer address is the browser's or the proxy's is settled once, in
     * configuration, by {@code server.forward-headers-strategy}, where the decision to trust Caddy
     * is visible and revocable. Deciding it here would bury it.
     */
    private String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
