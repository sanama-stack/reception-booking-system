package dev.reception.tenancy;

import dev.reception.auth.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the tenant for an authenticated request, once, before any controller runs.
 *
 * <p>The business comes from the signed token, which carried it from the Membership at issue time.
 * There is no code path here that reads a path variable, a body field or a header — that absence is
 * the isolation guarantee, not a check that could be forgotten
 * (docs/02-product-architecture.md §4).
 *
 * <p>Public requests resolve their tenant from the slug instead, in phase 08. Both populate the same
 * holder, so everything downstream is indifferent to which door the request came through.
 *
 * <p>Deliberately not a {@code @Component}: any {@code Filter} bean is auto-registered against every
 * request, and this one must run inside the security chain, after authentication. {@code SecurityConfig}
 * constructs it.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    /** Tenant-scoped log lines carry this, per docs/06-security.md §10. */
    public static final String MDC_KEY = "businessId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean resolved = false;

        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            AuthenticatedUser principal = AuthenticatedUser.from(token);
            TenantContextHolder.set(principal.businessId());
            MDC.put(MDC_KEY, principal.businessId().toString());
            resolved = true;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // Always, not only when resolved: a carrier thread that inherits another request's
            // tenant is the one bug this whole design exists to make impossible.
            if (resolved) {
                MDC.remove(MDC_KEY);
            }
            TenantContextHolder.clear();
        }
    }
}
