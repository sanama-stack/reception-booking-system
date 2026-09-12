package dev.reception.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>Every endpoint an unauthenticated caller can reach is rate limited.</strong>
 *
 * <p>The Definition of Done requires this, and until now it was asserted against a list of ten
 * paths somebody typed into {@link RateLimitPolicyOrderTest}. That list was correct on the day it
 * was written and wrong by phase 09: neither chat endpoint was ever added to it, so the two
 * endpoints in this application that <em>spend money per call</em> were covered by policies no test
 * required to exist. A hand-written surface cannot fail for the one case that matters — the
 * endpoint nobody remembered — because forgetting it in the controller and forgetting it in the
 * list are the same act of forgetting.
 *
 * <p>So neither side is written down here. The endpoints come from {@link
 * RequestMappingHandlerMapping}, the object Spring routes real requests with. Whether an endpoint
 * is public comes from the {@link AuthorizationManager} inside the running security filter chain —
 * the same object that decides it in production, asked the same question {@link
 * AuthorizationFilter} asks. Add a controller method under a {@code permitAll} pattern and it is in
 * this test the moment it is mapped; widen {@code permitAll} in {@code SecurityConfig} and every
 * endpoint that becomes reachable arrives here too.
 *
 * <p>This is deliberately <em>coverage</em> and not enforcement. That a policy exists and matches
 * is what this asserts; that the limit is really ten an hour is {@link RateLimitTest}, and which
 * policy wins where two overlap is {@link RateLimitPolicyOrderTest}. The gap this closes is the one
 * neither of those can see: an endpoint no policy mentions at all.
 */
class RateLimitCoverageTest extends IntegrationTest {

    /**
     * Public endpoints that are deliberately not rate limited, and why.
     *
     * <p>An entry here is a claim, not a silencer: it says an unauthenticated caller can hammer
     * this endpoint and that this is acceptable. Empty, and that is on purpose — every public
     * endpoint in this application carries a limit. The map exists so that the day one should not,
     * the reason is written next to the exemption rather than argued in a commit message.
     */
    private static final Map<String, String> UNLIMITED_ON_PURPOSE = new LinkedHashMap<>();

    /**
     * Policies whose target is served by a resource handler rather than a handler method, and a
     * concrete path each must still resolve to.
     *
     * <p>These cannot be derived from {@link RequestMappingHandlerMapping} because they are not in
     * it, so the orphan check probes the running application for them instead. The path is the
     * assertion: rename or remove the assets and the probe 404s, which is exactly when the policy
     * has stopped guarding anything.
     */
    private static final Map<String, String> SERVED_BY_A_RESOURCE_HANDLER =
            Map.of("api-docs-ui", "/swagger-ui/index.html");

    /**
     * Two beans implement this type — ours and springdoc's. The qualifier picks the one that routes
     * real requests; omitting it fails with {@code NoUniqueBeanDefinition}, which reads like a
     * missing bean and is the opposite. Same trap as {@code EndpointCoverageTest}.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private FilterChainProxy securityFilterChain;

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private RateLimitProperties rateLimits;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    /**
     * Whether the running application serves {@code path} at all.
     *
     * <p>Anything but a 404 counts: the question is whether something is mounted there, not whether
     * it answers happily. Rate limiting is disabled for this class as it is for most of the suite,
     * so a 429 cannot be mistaken for a resource that exists.
     */
    private boolean resolves(String path) {
        return rest.getForEntity("http://localhost:" + port + "/api" + path, String.class)
                        .getStatusCode()
                        .value()
                != 404;
    }

    @Test
    @DisplayName("every endpoint an anonymous caller can reach is covered by a rate-limit policy")
    void nothing_public_is_unlimited() {
        Set<String> unlimited = new TreeSet<>();
        for (Endpoint endpoint : publicEndpoints()) {
            if (!UNLIMITED_ON_PURPOSE.containsKey(endpoint.signature()) && policyFor(endpoint) == null) {
                unlimited.add(endpoint.signature());
            }
        }

        assertThat(unlimited)
                .as(
                        """
                        These endpoints are reachable without authentication and no rate-limit \
                        policy matches them. Add one to RateLimitProperties.policies() — minding the \
                        ordering rule, a narrower pattern goes above the wider one it sits inside — \
                        or add the endpoint to UNLIMITED_ON_PURPOSE with the reason it may be \
                        hammered. Do not reach for the exemption first: an unlimited public endpoint \
                        is a free amplifier pointed at this system.""")
                .isEmpty();
    }

    /**
     * The positive control, and the reason the assertion above is worth anything.
     *
     * <p>T89: every assertion in this class is that a set is empty, and the derivation feeding those
     * sets is three framework APIs deep. Break any of them — a security API that starts reporting
     * every request as denied, a handler mapping read too early — and the public set empties, the
     * emptiness assertion passes, and this test reports that nothing is unlimited because it can no
     * longer see anything at all. These are the assertions that fail in that world.
     */
    @Test
    @DisplayName("the public surface is derived, not empty, and excludes what needs a token")
    void the_derivation_still_sees_the_application() {
        Set<String> publicSignatures = new TreeSet<>();
        publicEndpoints().forEach(endpoint -> publicSignatures.add(endpoint.signature()));

        assertThat(publicSignatures)
                .as("the anonymous surface, derived from the security filter chain")
                .contains(
                        "POST /auth/login",
                        "POST /auth/register",
                        "POST /public/appointments/lookup",
                        "POST /public/businesses/{slug}/chat",
                        "GET /public/businesses/{slug}/availability");

        assertThat(publicSignatures)
                .as(
                        """
                        These need a token, and a derivation that calls them public is a derivation \
                        that has stopped reading the security configuration.""")
                .doesNotContain("GET /auth/me");
    }

    /**
     * The other direction, and the one a coverage test alone cannot catch.
     *
     * <p>A policy whose path no longer exists does not fail anything: the endpoint it was written
     * for was renamed, the rename got its own policy or fell under a wider one, and the orphan sits
     * in the list looking like protection. The ordering rule makes this worse than untidy — a dead
     * pattern above a live one is exactly the shape that shadows a tight limit with a loose one.
     */
    @Test
    @DisplayName("no policy guards a path this application no longer maps")
    void no_policy_is_an_orphan() {
        Set<String> orphans = new TreeSet<>();
        for (RateLimitPolicy policy : rateLimits.policies()) {
            boolean guardsSomething = mappedEndpoints().stream()
                    .anyMatch(endpoint -> policy.matches(endpoint.method(), endpoint.samplePath()));
            if (!guardsSomething && SERVED_BY_A_RESOURCE_HANDLER.containsKey(policy.name())) {
                // Not in the handler mapping and not dead either: a static resource is served by
                // ResourceHttpRequestHandler, which declares no handler methods. There is nothing to
                // derive from, so the running application is asked instead — the fallback this
                // project reaches for when a derivation is impossible rather than merely harder.
                guardsSomething = resolves(SERVED_BY_A_RESOURCE_HANDLER.get(policy.name()));
            }
            if (!guardsSomething) {
                orphans.add(policy.name() + " (" + policy.method() + " " + policy.pathPattern() + ")");
            }
        }

        assertThat(orphans)
                .as(
                        """
                        These policies match no endpoint this application maps. Either the path was \
                        renamed and the policy was left behind — in which case the new path is \
                        guarded by whatever wider pattern happens to catch it — or the endpoint is \
                        gone and so should the policy be.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no exemption names an endpoint that is no longer public")
    void no_exemption_is_stale() {
        Set<String> publicSignatures = new TreeSet<>();
        publicEndpoints().forEach(endpoint -> publicSignatures.add(endpoint.signature()));

        Set<String> vanished = new TreeSet<>(UNLIMITED_ON_PURPOSE.keySet());
        vanished.removeAll(publicSignatures);

        assertThat(vanished)
                .as(
                        """
                        These exemptions name endpoints that are no longer reachable anonymously. A \
                        stale exemption is worse than a missing one: it will silently excuse a \
                        future endpoint that happens to be mapped at the same path.""")
                .isEmpty();
    }

    @Test
    @DisplayName("every exemption carries a reason")
    void no_exemption_is_silent() {
        UNLIMITED_ON_PURPOSE.forEach((endpoint, why) -> assertThat(why)
                .as("%s is exempt from rate limiting and says nothing about why", endpoint)
                .isNotBlank());
    }

    /** The endpoints an {@link AnonymousAuthenticationToken} is allowed to reach. */
    private Set<Endpoint> publicEndpoints() {
        AuthorizationManager<HttpServletRequest> authorization = authorizationManager();
        Supplier<Authentication> anonymous = () -> new AnonymousAuthenticationToken(
                "rate-limit-coverage", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        Set<Endpoint> reachable = new TreeSet<>();
        for (Endpoint endpoint : mappedEndpoints()) {
            AuthorizationResult result = authorization.authorize(anonymous, requestFor(endpoint));
            // AuthorizationFilter throws only on a decision that is present and not granted, so an
            // abstention is a request that reaches the controller. Reading it any other way would
            // quietly shrink the surface this test is here to measure.
            if (result == null || result.isGranted()) {
                reachable.add(endpoint);
            }
        }
        return reachable;
    }

    /**
     * A request shaped the way a real one arrives at the security filter chain.
     *
     * <p><strong>The servlet context is the load-bearing argument.</strong> Every {@code permitAll}
     * rule is a {@code DeferredRequestMatcher}, which decides between its MVC and its Ant candidate
     * by reading the servlet registrations of the context the request carries. A {@link
     * MockHttpServletRequest} built without one carries {@code MockServletContext}, which has no
     * registrations, and <em>every pattern then reports no match</em> — including the rule naming
     * the exact path being asked about. Nothing throws. The request simply falls through to {@code
     * anyRequest().authenticated()}, an anonymous caller is denied, and the derived public surface
     * comes back empty: this test passing because it can no longer see the application. That is why
     * {@link #the_derivation_still_sees_the_application()} exists, and it is what caught this.
     *
     * <p>The context path is set for the same reason it is real: the container strips {@code /api}
     * before the matchers look, which is why {@code RateLimitFilter} subtracts it and why {@code
     * SecurityConfig} writes {@code /public/**} rather than {@code /api/public/**}. The policies are
     * asked about the stripped path, the security chain about the whole one.
     */
    private MockHttpServletRequest requestFor(Endpoint endpoint) {
        ServletContext servletContext = webContext.getServletContext();
        String contextPath = servletContext.getContextPath();

        MockHttpServletRequest request =
                new MockHttpServletRequest(servletContext, endpoint.method(), contextPath + endpoint.samplePath());
        request.setContextPath(contextPath);
        request.setServletPath(endpoint.samplePath());
        return request;
    }

    private AuthorizationManager<HttpServletRequest> authorizationManager() {
        return securityFilterChain.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(AuthorizationFilter.class::isInstance)
                .map(AuthorizationFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No AuthorizationFilter in the security filter chain. Either authorization moved to "
                                + "another filter or the chain is not the application's — either way this test is "
                                + "no longer asking the question it claims to ask."))
                .getAuthorizationManager();
    }

    private RateLimitPolicy policyFor(Endpoint endpoint) {
        return rateLimits.policies().stream()
                .filter(policy -> policy.matches(endpoint.method(), endpoint.samplePath()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Every endpoint mapped in this application, <strong>including the ones it does not declare.</strong>
     *
     * <p>This filtered handlers to {@code dev.reception} until phase 11, for the reason {@code
     * EndpointCoverageTest} still does: a springdoc release renaming its paths should not break a
     * test about <em>our</em> tenancy. <strong>Inherited here, that reason did not hold and the cost
     * was real.</strong> Tenancy is a question about our own data and framework endpoints hold none;
     * rate limiting is a question about what an anonymous caller can spend, and springdoc's paths
     * spend exactly as much as ours. Filtering them out made this test's headline sentence — "every
     * endpoint an anonymous caller can reach is rate limited" — quietly mean "every endpoint of
     * ours", while {@code /openapi} served the full specification to anybody, unlimited, and
     * {@code /swagger-ui} served 1.8 MB of assets beside it (docs/06-security.md §15).
     *
     * <p>The filter also made the gap <em>unfixable in place</em>: a policy written for a springdoc
     * path matched nothing here and was reported as an orphan, so the derivation that hid the
     * problem also rejected the fix.
     *
     * <p>What the old comment was right about is that a springdoc rename must not pass silently. It
     * does not: the renamed path arrives here as an uncovered public endpoint and the policy left
     * behind arrives as an orphan. Both fail, which is the outcome that was wanted — a build that
     * stops rather than a limit that disappears.
     */
    private Set<Endpoint> mappedEndpoints() {
        Set<Endpoint> endpoints = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : patternsOf(info)) {
                info.getMethodsCondition()
                        .getMethods()
                        .forEach(method -> endpoints.add(new Endpoint(method.asHttpMethod().name(), pattern)));
            }
        });
        return endpoints;
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }

    /**
     * One mapped endpoint: the pattern as Spring declares it, and a concrete path to ask questions
     * with.
     *
     * @param method the HTTP method, as {@link HandlerMethod}'s mapping declares it
     * @param pattern the routing pattern, template variables and all
     */
    private record Endpoint(String method, String pattern) implements Comparable<Endpoint> {

        /**
         * The pattern with every template variable filled in.
         *
         * <p>Neither {@code AntPathMatcher} nor the security matchers can be asked about a pattern;
         * both answer about a path. The value substituted is deliberately meaningless — nothing
         * downstream of this test resolves it, and a value that looked like a real slug would
         * suggest it had been looked up.
         */
        String samplePath() {
            return pattern.replaceAll("\\{[^/]*}", "sample");
        }

        String signature() {
            return method + " " + pattern;
        }

        @Override
        public int compareTo(Endpoint other) {
            return signature().compareTo(other.signature());
        }
    }
}
