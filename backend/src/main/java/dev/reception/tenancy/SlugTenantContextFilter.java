package dev.reception.tenancy;

import dev.reception.business.BusinessRepository;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the tenant for a public request, from the slug in its path.
 *
 * <p>The other half of what {@link TenantContextFilter} does for an authenticated one — same holder,
 * same guarantee, different source. Everything downstream is indifferent to which door a request
 * came through, which is what lets the public Classic Flow call the same application services the
 * dashboard calls rather than a parallel set of them.
 *
 * <p><strong>An unknown slug is a {@code 404} here, before any other work happens</strong>
 * (docs/phases/phase-08-public-booking.md). Refusing in the filter rather than in each controller
 * means there is no public endpoint that could be written without the check, and no window in which
 * a handler runs with no tenant resolved and reaches for one.
 *
 * <p>The slug is a public identifier and the only thing a stranger has, so reading it from the path
 * is not the smuggling {@code TenantContextFilter}'s javadoc warns about: what makes that dangerous
 * is a caller <em>choosing</em> a tenant they have no claim to, and here every caller is anonymous
 * and every business's page is public by design. What must never be readable from the path is a
 * {@code business_id}, and {@code TenantRepositoryShapeTest} fails the build if one ever is.
 *
 * <p>Deliberately not a {@code @Component}, for the reason {@link TenantContextFilter} is not: a
 * {@code Filter} bean is auto-registered against every request, and this one belongs inside the
 * security chain. {@code SecurityConfig} constructs it.
 */
public class SlugTenantContextFilter extends OncePerRequestFilter {

    /** Requests under here carry their tenant in the segment that follows. */
    private static final String PREFIX = "/public/businesses/";

    private final BusinessRepository businesses;
    private final ProblemJsonWriter problems;

    public SlugTenantContextFilter(BusinessRepository businesses, ProblemJsonWriter problems) {
        this.businesses = businesses;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String slug = slugIn(request);
        if (slug == null) {
            // Not a slug-addressed request. /public/appointments/* resolves its tenant from a proven
            // capability instead — see TenantAdoption.
            chain.doFilter(request, response);
            return;
        }

        Optional<UUID> businessId = businesses.findBySlug(slug).map(business -> business.getId());
        if (businessId.isEmpty()) {
            problems.write(
                    response,
                    ErrorCode.NOT_FOUND,
                    "No booking page at that address.",
                    request.getRequestURI());
            return;
        }

        // Set here rather than through TenantAdoption: this is a filter in the tenancy package, so
        // the package-private holder is reachable, and keeping TenantAdoption's caller list at one
        // is worth more than the symmetry.
        TenantContextHolder.set(businessId.get());
        org.slf4j.MDC.put(TenantContextFilter.MDC_KEY, businessId.get().toString());
        // No finally: TenantContextFilter wraps this one and clears in its own, for both doors at
        // once. Clearing here as well would work, and would also be the second place a future
        // reader has to check when a tenant leaks.
        chain.doFilter(request, response);
    }

    /**
     * The slug segment, or {@code null} when this request is not addressed to a business page.
     *
     * <p>Read from the servlet path — the URI minus the {@code /api} context path — which is what
     * the security matchers and the rate-limit policies are written against too.
     */
    private static String slugIn(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(PREFIX)) {
            return null;
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String slug = slash < 0 ? rest : rest.substring(0, slash);
        return slug.isBlank() ? null : slug;
    }
}
