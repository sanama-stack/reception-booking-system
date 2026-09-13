package dev.reception.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.common.logging.RequestIdFilter;
import dev.reception.common.ratelimit.RateLimitFilter;
import dev.reception.common.web.JsonOnlyWriteFilter;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.SlugTenantContextFilter;
import dev.reception.tenancy.TenantContextFilter;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * <strong>Every filter ordering this application depends on is reconciled against the chain it
 * actually builds.</strong>
 *
 * <p>{@code RateLimitAddressTest} pinned exactly one of these relationships — {@link
 * ForwardedHeaderFilter} ahead of {@link RateLimitFilter} — after finding that the whole per-address
 * control rested on an unasserted margin of ten, and that the tempting edit closed it to nothing.
 * <strong>The same class of defect was open everywhere else.</strong> {@link JsonOnlyWriteFilter}
 * must run before authentication or its {@code 415} becomes a {@code 401}; {@link RateLimitFilter}
 * must run before both or the login limit stops bounding callers who cannot authenticate; {@link
 * RequestIdFilter} must run before the security chain or a refused request's problem body loses the
 * {@code requestId} {@code ProblemDetails} reads from the MDC. Each of those sentences appears in a
 * class comment. None of them was checked, and all of them live in {@code @Order} arithmetic that
 * nothing reconciled.
 *
 * <p><strong>The chain is derived, not described.</strong> {@link ServletContextInitializerBeans} is
 * the object {@code ServletWebServerApplicationContext} iterates to register filters, so its
 * iteration order <em>is</em> the order Tomcat maps them in. Reading it means an ordering assertion
 * cannot drift from the ordering that runs: change an {@code @Order}, add a filter, let Boot's
 * auto-configuration move one, and the chain read here moves with it. The security chain is the
 * ordered {@link java.util.List} inside {@link FilterChainProxy} for the same reason.
 *
 * <p><strong>Both facts are asserted for each pair, and the second is the one that matters.</strong>
 * Position says the filters run in the right sequence today. The order <em>value</em> says the
 * position is not a coin toss: filters tied on order are sequenced arbitrarily, so an edit that ties
 * two of these does not fail — it makes the arrangement lucky. A test asserting position alone would
 * pass on the lucky run and this suite would never see the other one.
 *
 * <p><strong>Rate limiting is switched on here on purpose.</strong> {@link RateLimitFilter} is a
 * {@code @ConditionalOnProperty} bean and most of the suite runs with it off, where it is not in the
 * chain at all — and a pin against a filter that is absent is not a weak assertion but an
 * unanswerable question. {@link #positionOf} therefore fails loudly on an absent filter rather than
 * skipping it, which is also what keeps the empty-violation assertions below from being satisfied by
 * a derivation that has stopped seeing anything (T89).
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class FilterOrderTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FilterChainProxy securityFilterChain;

    /**
     * One ordering this application depends on, and the consequence of losing it.
     *
     * @param before the filter that must run first
     * @param after the filter that must run second
     * @param why what breaks when they swap — written to be read in a failure message, by somebody
     *     who has just made the edit and does not yet know what it cost
     */
    private record Pin(Named before, Named after, String why) {}

    /**
     * A filter, named the way it can be found.
     *
     * <p>Matching is by class wherever there is a class to match. The security chain is the
     * exception: Boot registers it as a {@code DelegatingFilterProxyRegistrationBean}, whose filter
     * is an anonymous subclass, so the registration name is the only stable handle — and it is taken
     * from Spring Security's own constant rather than typed as a string.
     */
    private record Named(String label, Predicate<Registered> matches) {

        static Named type(Class<? extends Filter> type) {
            return new Named(type.getSimpleName(), registered -> type.isAssignableFrom(registered.type()));
        }

        static Named registration(String name) {
            return new Named(name, registered -> name.equals(registered.name()));
        }
    }

    /** One entry of the servlet filter chain, as Boot registered it. */
    private record Registered(String name, Class<?> type, int order) {}

    /** The security chain's own filters carry no registration order; position is all there is. */
    private static final Named SECURITY_CHAIN =
            Named.registration(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME);

    /**
     * The orderings held by {@code @Order} arithmetic, each with the sentence it comes from.
     *
     * <p>The first pin is also asserted behaviourally by {@code RateLimitAddressTest}, which drives
     * two forwarded addresses through a real limit. It is repeated here because this is the list a
     * reader consults before editing an {@code @Order}, and a list that omits the one relationship
     * everybody already knows about teaches the wrong lesson about how complete it is.
     */
    private static final List<Pin> SERVLET_CHAIN_PINS = List.of(
            new Pin(
                    Named.type(ForwardedHeaderFilter.class),
                    Named.type(RateLimitFilter.class),
                    """
                    The limiter keys its buckets on getRemoteAddr(), which the wrapper installed by \
                    ForwardedHeaderFilter overrides. Counted first, every visitor behind Caddy \
                    arrives as Caddy and shares one bucket, which is the availability outage the \
                    control exists to prevent."""),
            new Pin(
                    Named.type(RequestIdFilter.class),
                    SECURITY_CHAIN,
                    """
                    ProblemDetails reads the requestId out of the MDC that RequestIdFilter puts it \
                    in. Run the security chain first and every response it writes itself — every \
                    401, every 403 — loses the one field that ties it to a log line."""),
            new Pin(
                    Named.type(RateLimitFilter.class),
                    Named.type(JsonOnlyWriteFilter.class),
                    """
                    JsonOnlyWriteFilter's class comment states this ordering as a fact about the \
                    system. Reversed, a caller refused for content type spends no budget, and a \
                    flood of form posts is answered 415 forever at no cost to the sender."""),
            new Pin(
                    Named.type(RateLimitFilter.class),
                    SECURITY_CHAIN,
                    """
                    RateLimitFilter's class comment: "Runs before authentication on purpose: the \
                    point of the login limit is to bound attempts by callers who cannot \
                    authenticate." Behind the security chain it bounds nobody, because the \
                    credential-stuffing request never reaches it."""),
            new Pin(
                    Named.type(JsonOnlyWriteFilter.class),
                    SECURITY_CHAIN,
                    """
                    FormPostRejectionTest asserts 415 with no credentials at all, for every \
                    state-changing endpoint including the authenticated ones. That test is only \
                    asking its question while this holds: behind authentication, a protected \
                    endpoint answers 401 and the form-post shape is never refused on its merits."""));

    /** The orderings {@code SecurityConfig} builds by hand, each with the comment it is built under. */
    private static final List<Pin> SECURITY_CHAIN_PINS = List.of(
            new Pin(
                    Named.type(BearerTokenAuthenticationFilter.class),
                    Named.type(TenantContextFilter.class),
                    """
                    SecurityConfig: "After authentication, so there is a principal to derive the \
                    tenant from." Ahead of it there is no Authentication in the context, the tenant \
                    resolves to nothing, and every repository scoped by it reads an empty result \
                    rather than refusing."""),
            new Pin(
                    Named.type(TenantContextFilter.class),
                    Named.type(SlugTenantContextFilter.class),
                    """
                    SecurityConfig: "After that one, so it runs inside it: TenantContextFilter's \
                    finally is what clears the holder, and it must wrap every resolution rather \
                    than only its own." Outside it, a slug-resolved tenant survives the request and \
                    is inherited by whatever the container hands the thread next."""));

    @Test
    @DisplayName("every ordering the servlet filter chain depends on holds, and holds strictly")
    void the_servlet_chain_is_ordered_as_its_comments_claim() {
        List<Registered> chain = servletChain();
        List<String> violations = new ArrayList<>();

        for (Pin pin : SERVLET_CHAIN_PINS) {
            int before = positionOf(chain, pin.before());
            int after = positionOf(chain, pin.after());
            if (before >= after) {
                violations.add("%s runs after %s. %s".formatted(pin.before().label(), pin.after().label(), pin.why()));
            } else if (chain.get(before).order() >= chain.get(after).order()) {
                violations.add(
                        """
                        %s and %s are tied at order %d. They happen to be sequenced correctly in \
                        this run and there is nothing holding them that way — filters tied on order \
                        are sequenced arbitrarily. %s"""
                                .formatted(
                                        pin.before().label(),
                                        pin.after().label(),
                                        chain.get(before).order(),
                                        pin.why()));
            }
        }

        assertThat(violations)
                .as(
                        """
                        The filter chain no longer runs in the order the filters' own comments say \
                        it does. The chain as registered, in order, is:

                        %s"""
                                .formatted(describe(chain)))
                .isEmpty();
    }

    @Test
    @DisplayName("the filters SecurityConfig inserts by hand sit where its comments put them")
    void the_security_chain_is_ordered_as_its_comments_claim() {
        List<Registered> chain = securityChain();
        List<String> violations = new ArrayList<>();

        for (Pin pin : SECURITY_CHAIN_PINS) {
            if (positionOf(chain, pin.before()) >= positionOf(chain, pin.after())) {
                violations.add("%s runs after %s. %s".formatted(pin.before().label(), pin.after().label(), pin.why()));
            }
        }

        assertThat(violations)
                .as(
                        """
                        addFilterAfter() places a filter relative to a named one, so a change to \
                        either reference reshuffles this chain silently. The chain as built, in \
                        order, is:

                        %s"""
                                .formatted(describe(chain)))
                .isEmpty();
    }

    /**
     * The positive control, for the reason {@code RateLimitCoverageTest} has one.
     *
     * <p>Both assertions above are that a list of violations is empty, and both are fed by a
     * framework API that could stop answering. {@link #positionOf} already refuses to be blind — an
     * absent filter throws rather than scoring zero violations — so this test asserts the other
     * half: that the chains read here are the whole application's and not some fragment of it that
     * happens to contain the five filters being asked about.
     */
    @Test
    @DisplayName("both chains are the running application's, whole")
    void the_derivation_still_sees_both_chains() {
        List<Registered> servlet = servletChain();
        assertThat(servlet.stream().map(Registered::name))
                .as("the servlet chain must contain the filters Boot registers as well as ours; a "
                        + "derivation returning only dev.reception beans would satisfy most pins above")
                .contains("forwardedHeaderFilter", "requestIdFilter", "rateLimitFilter", "jsonOnlyWriteFilter")
                .contains(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME);

        assertThat(servlet)
                .as("a servlet chain this short is a chain that was not read")
                .hasSizeGreaterThanOrEqualTo(6);

        List<Registered> security = securityChain();
        assertThat(security.stream().map(Registered::name))
                .as("the security chain must end in the filter that decides authorization")
                .contains(AuthorizationFilter.class.getName());

        assertThat(security)
                .as("Spring Security builds a dozen filters into even a minimal chain")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("every pinned ordering says what losing it would cost")
    void no_pin_is_silent() {
        List<Pin> all = new ArrayList<>(SERVLET_CHAIN_PINS);
        all.addAll(SECURITY_CHAIN_PINS);

        for (Pin pin : all) {
            assertThat(pin.why())
                    .as("%s before %s is pinned and says nothing about why", pin.before().label(), pin.after().label())
                    .isNotBlank();
        }
    }

    /**
     * The servlet filter chain, in the order Tomcat maps it.
     *
     * <p>{@link ServletContextInitializerBeans} is not a convenience wrapper over the bean factory:
     * it is the same object {@code ServletWebServerApplicationContext.selfInitialize} iterates, and
     * it is where a plain {@code Filter} {@code @Component} acquires the registration Boot never
     * asked the author to write. Constructing it here asks Boot the question rather than
     * re-deriving the answer from {@code @Order} annotations, which is what makes this a reading of
     * the chain rather than a second implementation of it.
     */
    private List<Registered> servletChain() {
        List<Registered> chain = new ArrayList<>();
        for (ServletContextInitializer initializer : new ServletContextInitializerBeans(context)) {
            if (initializer instanceof AbstractFilterRegistrationBean<?> registration) {
                chain.add(new Registered(
                        registration.getFilterName(),
                        registration.getFilter().getClass(),
                        registration.getOrder()));
            }
        }
        return chain;
    }

    /**
     * The security chain's filters, in the order {@link FilterChainProxy} runs them.
     *
     * <p>These carry no order value — the list itself is the ordering — so {@link Registered#order}
     * is the position, and the tie branch above can never fire for them. That is correct rather than
     * a shortcut: {@code addFilterAfter} cannot produce a tie, only a wrong neighbour.
     */
    private List<Registered> securityChain() {
        List<Registered> chain = new ArrayList<>();
        securityFilterChain.getFilterChains().stream()
                .flatMap(each -> each.getFilters().stream())
                .forEach(filter -> chain.add(new Registered(
                        filter.getClass().getName(), filter.getClass(), chain.size())));
        return chain;
    }

    /**
     * Where {@code named} sits in {@code chain}, or a failure naming what vanished.
     *
     * <p>An absent filter must not score zero violations. A pin whose subject is gone is not a pin
     * that holds; it is a question this test can no longer ask, and the two must fail differently
     * from each other but neither may pass.
     */
    private int positionOf(List<Registered> chain, Named named) {
        for (int i = 0; i < chain.size(); i++) {
            if (named.matches().test(chain.get(i))) {
                return i;
            }
        }
        throw new AssertionError(
                """
                %s is not in this chain at all, so the ordering pinned on it cannot be checked. \
                Either it was removed, or it is conditional on a property this test does not set \
                — RateLimitFilter is @ConditionalOnProperty and absent from most of the suite. The \
                chain read was:

                %s"""
                        .formatted(named.label(), describe(chain)));
    }

    private static String describe(List<Registered> chain) {
        return chain.stream()
                .map(registered -> "  %d  %s".formatted(registered.order(), registered.name()))
                .collect(Collectors.joining("\n"));
    }

}
