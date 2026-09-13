package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>Everything this application serves is inside one of the derived controls, or named here
 * with the reason it is outside — and the reason is asserted rather than asserted-by-comment.</strong>
 *
 * <p>Five tests now derive their subject from {@link RequestMappingHandlerMapping} rather than from a
 * list somebody typed: tenancy coverage, rate-limit coverage, the form-post refusal, the CORS sweep
 * and the authentication-event sweep. Each narrows what it looks at, and every narrowing is correct
 * in its own place. <strong>What nothing recorded is the union of what they all step over</strong> —
 * and a control's blind spot is not visible from inside it, because the blind spot is exactly where
 * it reports nothing.
 *
 * <p>There are three ways out of that union, and they are different in kind.
 *
 * <ol>
 *   <li><strong>A mapping with no HTTP method condition is invisible to all five.</strong> Each of
 *       them iterates {@code info.getMethodsCondition().getMethods()} to pair a pattern with a verb;
 *       an empty condition yields an empty loop, so the endpoint contributes nothing and is not
 *       reported as anything. It is not filtered out — it never arrives. Spring's {@code /error} is
 *       mapped this way.
 *   <li><strong>Every derivation is scoped to {@code dev.reception}.</strong> That is the right
 *       decision — a springdoc release renaming its paths should not break a test about our tenancy
 *       — and its cost is the set of framework endpoints below. Rate-limit coverage was widened to
 *       include them in phase 11, after the package filter was found to be hiding {@code /openapi}
 *       serving the full specification to anybody, unlimited.
 *   <li><strong>All five read one {@link HandlerMapping} out of the eight this application
 *       builds.</strong> This is the widest of the three and the one that had not been written down
 *       at all. A path served by a resource handler is in no handler mapping; {@code GET /actuator}
 *       is in a handler mapping of the actuator's own; a {@code RouterFunction} bean would be in a
 *       third. None of them is filtered out by any control, because no control ever looks in the
 *       object that holds them.
 * </ol>
 *
 * <p><strong>Naming an exclusion is not the same as making it safe, and this class tries for the
 * second.</strong> A written list of what sits outside the controls is itself the shape this
 * repository has spent several sessions removing. So every list here is pinned by equality against a
 * derivation — a new framework endpoint, a second methodless mapping, a new handler mapping, a new
 * resource root all stop the build; the five mappings that serve nothing today are asserted to serve
 * nothing rather than described as empty; and the exclusions that could actually hurt are pinned by
 * their consequence instead. {@code /error} and {@code GET /actuator} are invisible to rate-limit
 * coverage, so what is asserted of them is that they are <em>not reachable anonymously</em>. The day
 * one is added to {@code permitAll}, this fails and says why, rather than the public surface quietly
 * gaining an unlimited endpoint that no coverage test can see.
 *
 * <p><strong>{@link #surfaceOf} refuses to be blind.</strong> A {@link HandlerMapping} whose type it
 * cannot read is an assertion error, not an empty set — because "serves nothing" and "could not be
 * asked" are the same value and opposite facts, and this whole class exists because the second kept
 * being mistaken for the first.
 */
class MappedSurfaceTest extends IntegrationTest {

    /**
     * Mappings that declare no HTTP method, and why each may be invisible.
     *
     * <p>An entry is a claim that the five derivations may step over this path. It is not a
     * silencer: {@link #nothing_invisible_is_reachable_anonymously()} holds every entry to the
     * property that makes the exclusion harmless.
     */
    private static final Map<String, String> MAPPED_WITHOUT_A_METHOD = new LinkedHashMap<>(Map.of(
            "/error",
            """
            Spring Boot's error dispatch target. It declares no method because it answers whichever \
            one the failed request used, and it is reached by a container forward rather than by a \
            client — a direct request for it is refused by the security chain, which is the property \
            asserted below."""));

    /**
     * Endpoints in {@link RequestMappingHandlerMapping} mapped by classes outside {@code
     * dev.reception}, and what each is.
     *
     * <p>Today this is springdoc four times, plus the error target. The list is here so that a sixth
     * entry — a springdoc release adding a path, anything a dependency bump mounts — has to be
     * looked at and classified before it is admitted, rather than arriving inside the one package
     * every derived control declines to look at.
     */
    private static final Map<String, String> OUTSIDE_OUR_PACKAGE = new LinkedHashMap<>(Map.of(
            "GET /docs",
            "springdoc's Swagger UI entry point; redirects to the swagger-ui assets. Public.",
            "GET /openapi",
            "springdoc's JSON rendering of the specification. Public, and rate limited since phase 11.",
            "GET /openapi/swagger-config",
            "springdoc's configuration document for the UI. Public.",
            "GET /openapi.yaml",
            """
            springdoc's second rendering of the same specification. NOT public, deliberately and \
            load-bearingly so — ApiDocumentationExposureTest asserts it, because a second public \
            rendering would double the documentation surface for nothing.""",
            "ANY /error",
            "Spring Boot's error dispatch target. Also the sole entry of MAPPED_WITHOUT_A_METHOD."));

    /**
     * Every {@link HandlerMapping} this application builds, and what covers what it serves.
     *
     * <p>This is the general form of the gap. The five derived controls read {@code
     * requestMappingHandlerMapping}; there are seven others, and until this list existed nothing
     * recorded that they were there, let alone what they held.
     */
    private static final Map<String, String> HANDLER_MAPPINGS = new LinkedHashMap<>(Map.of(
            "requestMappingHandlerMapping",
            "The annotated controllers. What all five derived controls read, and the only one they read.",
            "resourceHandlerMapping",
            "Static resources. Serves the swagger-ui assets; pinned by SERVED_BY_A_RESOURCE_HANDLER below.",
            "webEndpointServletHandlerMapping",
            "The actuator's web endpoints. Serves GET /actuator; pinned by SERVED_BY_THE_ACTUATOR below.",
            "controllerEndpointHandlerMapping",
            "The actuator's @ControllerEndpoint beans. There are none, and that is asserted.",
            "routerFunctionMapping",
            """
            Functional routes. There is no RouterFunction bean, and that is asserted — one would \
            map endpoints that no derivation in this suite can see at all.""",
            "beanNameHandlerMapping",
            "URL-to-bean-name routing, a pre-annotation mechanism this application does not use. Asserted empty.",
            "welcomePageHandlerMapping",
            "Boot's index.html handler for '/'. There is no static index page, and that is asserted.",
            "welcomePageNotAcceptableHandlerMapping",
            "The same, for requests that do not accept HTML. Asserted empty."));

    /** The mappings that hold nothing today, asserted to hold nothing rather than described so. */
    private static final Set<String> SERVES_NOTHING = new LinkedHashSet<>(Set.of(
            "controllerEndpointHandlerMapping",
            "routerFunctionMapping",
            "beanNameHandlerMapping",
            "welcomePageHandlerMapping",
            "welcomePageNotAcceptableHandlerMapping"));

    /**
     * Patterns served by a resource handler rather than a handler method, and what sits behind each.
     *
     * <p>{@code RateLimitCoverageTest} keeps its own entry for the one policy that guards these and
     * probes the running application to prove the path still resolves. That answers "is this policy
     * still guarding something". <strong>This answers the other half — whether the resource surface
     * is still only this.</strong> A {@code /**} handler added for static assets would be served by
     * the application, reachable, and outside all five derivations with nothing anywhere reporting
     * that it exists.
     */
    private static final Map<String, String> SERVED_BY_A_RESOURCE_HANDLER = new LinkedHashMap<>(Map.of(
            "/swagger-ui*/**",
            "The swagger-ui webjar: roughly 1.8 MB a page load, which is why it has a budget of its own.",
            "/swagger-ui*/*swagger-initializer.js",
            "springdoc's rewritten initializer, mapped ahead of the webjar's own copy of it."));

    /**
     * What the actuator mounts, and the reason it is not a hole.
     *
     * <p>{@code spring-boot-starter-actuator} is a dependency for the health indicators {@code
     * HealthController} composes; the web endpoints come with it whether or not they are wanted.
     * {@code application.yml} sets {@code management.endpoints.web.exposure.include} to the empty
     * string and disables the health endpoint outright, so <strong>no actuator endpoint is
     * exposed</strong> — and the discovery document at {@code /actuator} is mapped anyway, because
     * the links mapping is registered independently of what it has to link to. That is the whole of
     * this surface, and it is why "actuator is switched off" and "nothing is mounted" are different
     * sentences.
     */
    private static final Map<String, String> SERVED_BY_THE_ACTUATOR = new LinkedHashMap<>(Map.of(
            "GET /actuator",
            """
            The actuator's links document. It lists the exposed endpoints, of which there are none, \
            and it is mapped regardless. Not in permitAll, so anyRequest().authenticated() refuses \
            it — which is what makes it harmless to be invisible to rate-limit coverage. /health is \
            ours, a plain controller, and unrelated to this mapping."""));

    /**
     * Two beans implement this type — ours and springdoc's. The qualifier picks the one that routes
     * real requests; omitting it fails with {@code NoUniqueBeanDefinition}, which reads like a
     * missing bean and is the opposite. Same trap as {@code EndpointCoverageTest}.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FilterChainProxy securityFilterChain;

    @Autowired
    private WebApplicationContext webContext;

    @Test
    @DisplayName("no endpoint of ours is invisible for want of a declared HTTP method")
    void nothing_of_ours_is_mapped_without_a_method() {
        Set<String> ours = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("dev.reception")
                    && info.getMethodsCondition().getMethods().isEmpty()) {
                ours.addAll(patternsOf(info));
            }
        });

        assertThat(ours)
                .as(
                        """
                        These are ours and they declare no HTTP method, which means every derived \
                        control in this repository steps over them without reporting anything: not \
                        the tenancy sweep, not rate-limit coverage, not the form-post refusal, not \
                        the CORS sweep, not the authentication-event sweep. They are not excluded — \
                        they never arrive, because each of those tests pairs a pattern with a verb \
                        and there is no verb to pair. Give the mapping a method.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the mappings with no HTTP method are the ones named here")
    void the_methodless_mappings_are_the_ones_named_here() {
        Set<String> methodless = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (info.getMethodsCondition().getMethods().isEmpty()) {
                methodless.addAll(patternsOf(info));
            }
        });

        assertThat(methodless)
                .as(
                        """
                        A mapping that declares no HTTP method is invisible to every endpoint \
                        derivation in this suite. If this set has grown, the new entry is currently \
                        outside all of them — decide what it is, then either give it a method or add \
                        it to MAPPED_WITHOUT_A_METHOD with the reason it may stay invisible. Do not \
                        reach for the second: the first is almost always right.""")
                .containsExactlyInAnyOrderElementsOf(MAPPED_WITHOUT_A_METHOD.keySet());
    }

    /**
     * The assertion that makes the two invisible surfaces safe rather than merely recorded.
     *
     * <p>{@code RateLimitCoverageTest}'s headline is that every endpoint an anonymous caller can
     * reach carries a limit. Neither a methodless mapping nor an actuator endpoint can appear in
     * that test's surface at all, so a public one would be an unlimited public endpoint reported by
     * nothing — a free amplifier pointed at this system, arriving through the one door the coverage
     * test cannot see.
     *
     * <p>The swagger-ui assets are the deliberate exception and are not asserted here: they are
     * public on purpose, and {@code ApiDocumentationExposureTest} proves their budget bites.
     */
    @Test
    @DisplayName("nothing invisible to the derivations is reachable without authentication")
    void nothing_invisible_is_reachable_anonymously() {
        Set<String> invisible = new LinkedHashSet<>(MAPPED_WITHOUT_A_METHOD.keySet());
        SERVED_BY_THE_ACTUATOR.keySet().forEach(signature -> invisible.add(signature.split(" ", 2)[1]));

        Set<String> publicAndInvisible = new TreeSet<>();
        for (String path : invisible) {
            for (String method : Set.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                if (anonymousCanReach(method, path)) {
                    publicAndInvisible.add(method + " " + path);
                }
            }
        }

        assertThat(publicAndInvisible)
                .as(
                        """
                        These paths are reachable without authentication AND invisible to every \
                        endpoint derivation in this suite, rate-limit coverage included. Nothing \
                        will report them as uncovered, because nothing can see them. Either give the \
                        mapping an HTTP method in a controller the derivations read — which puts it \
                        back inside the coverage test, where a missing policy fails the build — or \
                        take it out of permitAll.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the endpoints outside dev.reception are the ones named here")
    void the_framework_surface_is_the_one_named_here() {
        Set<String> framework = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("dev.reception")) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                Set<String> methods = new TreeSet<>();
                info.getMethodsCondition().getMethods().forEach(method -> methods.add(method.asHttpMethod().name()));
                if (methods.isEmpty()) {
                    framework.add("ANY " + pattern);
                } else {
                    methods.forEach(method -> framework.add(method + " " + pattern));
                }
            }
        });

        assertThat(framework)
                .as(
                        """
                        These endpoints are mapped by classes outside dev.reception, which is how \
                        EndpointCoverageTest and PublicSurfaceSweepTest exclude them — by where they \
                        are declared, not by name. That exclusion is deliberate and this set is what \
                        it currently costs. A new entry is an endpoint this application serves that \
                        no tenancy or public-surface test looks at: classify it before admitting it. \
                        A vanished entry is a dependency that renamed its paths, which matters for \
                        the opposite reason — the rate-limit policy written for the old one is now \
                        an orphan.""")
                .containsExactlyInAnyOrderElementsOf(OUTSIDE_OUR_PACKAGE.keySet());
    }

    @Test
    @DisplayName("the handler mappings this application builds are the ones named here")
    void the_handler_mappings_are_the_ones_named_here() {
        assertThat(context.getBeansOfType(HandlerMapping.class).keySet())
                .as(
                        """
                        Every derived control in this suite reads requestMappingHandlerMapping and \
                        no other. A new HandlerMapping is a new way for this application to serve a \
                        request that none of them can see — not filtered out, simply never looked \
                        at. Name it here, then either assert it serves nothing or pin what it \
                        serves.""")
                .containsExactlyInAnyOrderElementsOf(HANDLER_MAPPINGS.keySet());
    }

    /**
     * The five that hold nothing, asserted to hold nothing.
     *
     * <p>This is the difference between a list and a control. "There is no {@code RouterFunction}"
     * is a true sentence about today that a reader has no way to check and that nothing maintains;
     * the assertion below fails on the commit that adds one. {@link #surfaceOf} treats a mapping it
     * cannot read as an error rather than as empty, so this cannot pass by failing to look.
     */
    @Test
    @DisplayName("the handler mappings said to serve nothing serve nothing")
    void the_other_handler_mappings_serve_nothing() {
        Map<String, Set<String>> unexpected = new LinkedHashMap<>();
        context.getBeansOfType(HandlerMapping.class).forEach((name, mapping) -> {
            // Read every mapping, not only the ones expected to be empty. Asking just those would
            // leave surfaceOf()'s refusal to guess unreached for any mapping that is new — which is
            // the only kind that can be of an unfamiliar shape, and so the only kind it is for.
            Set<String> served = surfaceOf(mapping);
            if (SERVES_NOTHING.contains(name) && !served.isEmpty()) {
                unexpected.put(name, served);
            }
        });

        assertThat(unexpected)
                .as(
                        """
                        These handler mappings were recorded as serving nothing and now serve \
                        something. Whatever they serve is outside every derived control in this \
                        suite: it is not classified for tenancy, not swept for public exposure, and \
                        not covered by any rate-limit policy, and no other test will say so.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the paths served by a resource handler are the ones named here")
    void the_resource_surface_is_the_one_named_here() {
        assertThat(surfaceOf(context.getBean("resourceHandlerMapping", HandlerMapping.class)))
                .as(
                        """
                        A resource handler serves paths that are in no handler mapping the \
                        derivations read, so every endpoint control in this suite is blind to them \
                        by construction. A new entry is a surface the application serves that \
                        nothing classifies, rate limits, or sweeps for tenant leakage. The likeliest \
                        way for one to appear is a static resource location added to configuration, \
                        which reads like a build concern and is not one.""")
                .containsExactlyInAnyOrderElementsOf(SERVED_BY_A_RESOURCE_HANDLER.keySet());
    }

    @Test
    @DisplayName("the actuator mounts what it is said to mount, and no more")
    void the_actuator_surface_is_the_one_named_here() {
        Set<String> mounted = new TreeSet<>();
        context.getBean(WebMvcEndpointHandlerMapping.class).getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : patternsOf(info)) {
                info.getMethodsCondition()
                        .getMethods()
                        .forEach(method -> mounted.add(method.asHttpMethod().name() + " " + pattern));
            }
        });

        assertThat(mounted)
                .as(
                        """
                        The actuator's endpoints live in a handler mapping of their own, which no \
                        derived control in this suite reads. application.yml exposes none of them \
                        and this is the links document, mapped anyway; widening \
                        management.endpoints.web.exposure.include mounts more here in one line, \
                        with nothing else in this repository reporting the new surface. Whatever is \
                        added must be checked against the anonymity assertion above before it is \
                        admitted.""")
                .containsExactlyInAnyOrderElementsOf(SERVED_BY_THE_ACTUATOR.keySet());
    }

    @Test
    @DisplayName("every handler mapping is either asserted empty or has its surface pinned")
    void no_handler_mapping_is_merely_described() {
        Set<String> pinned = new LinkedHashSet<>(SERVES_NOTHING);
        pinned.add("requestMappingHandlerMapping");
        pinned.add("resourceHandlerMapping");
        pinned.add("webEndpointServletHandlerMapping");

        assertThat(HANDLER_MAPPINGS.keySet())
                .as(
                        """
                        A handler mapping named in HANDLER_MAPPINGS but neither in SERVES_NOTHING \
                        nor pinned by a test above is documented and unchecked, which is the state \
                        this class exists to end.""")
                .containsExactlyInAnyOrderElementsOf(pinned);
    }

    @Test
    @DisplayName("every exclusion carries a reason")
    void no_exclusion_is_silent() {
        Map<String, String> all = new LinkedHashMap<>(MAPPED_WITHOUT_A_METHOD);
        all.putAll(OUTSIDE_OUR_PACKAGE);
        all.putAll(SERVED_BY_A_RESOURCE_HANDLER);
        all.putAll(SERVED_BY_THE_ACTUATOR);
        all.putAll(HANDLER_MAPPINGS);

        all.forEach((entry, why) -> assertThat(why)
                .as("%s is outside the derived controls and says nothing about why", entry)
                .isNotBlank());
    }

    /**
     * The positive control, for the reason {@code RateLimitCoverageTest} has one.
     *
     * <p>Three of the assertions above are that a set is empty, and all of them are fed by framework
     * objects read at a particular moment. A handler mapping read before initialisation, a security
     * API that starts denying everything, a bean name that changes — any of those empties the sets
     * and every emptiness assertion passes for the worst possible reason. These are the assertions
     * that fail in that world. T89, whose general form is that an assertion a set is empty is
     * satisfied by an instrument that has stopped seeing anything.
     */
    @Test
    @DisplayName("the derivation still sees the application")
    void the_derivation_still_sees_the_application() {
        assertThat(mappings.getHandlerMethods())
                .as("this application maps considerably more than a handful of endpoints; a mapping "
                        + "this small was read before the context finished building")
                .hasSizeGreaterThan(40);

        assertThat(anonymousCanReach("POST", "/auth/login"))
                .as("the authorization derivation must still report a known public endpoint as "
                        + "public, or the anonymity assertion above is vacuous")
                .isTrue();

        assertThat(anonymousCanReach("GET", "/auth/me"))
                .as("and must still report a known protected one as protected, or it is reporting "
                        + "everything as reachable and would not have caught a public /error either")
                .isFalse();
    }

    /**
     * What a {@link HandlerMapping} serves, or a failure saying it could not be asked.
     *
     * <p>The three branches are the three shapes Spring's mappings come in. The fourth case — a type
     * this method does not know — is the one that matters: returning an empty set for it would make
     * {@link #the_other_handler_mappings_serve_nothing()} pass over precisely the new mapping it
     * exists to catch, which is the failure this class was written about.
     *
     * <p>That branch is reachable only because its caller reads <em>every</em> mapping rather than
     * only the ones it expects to be empty. Written the narrow way it was unreachable, and a
     * counterfactual — a {@code HandlerMapping} bean of an unfamiliar shape — passed this method
     * without ever entering it.
     */
    private static Set<String> surfaceOf(HandlerMapping mapping) {
        if (mapping instanceof AbstractUrlHandlerMapping url) {
            Set<String> served = new TreeSet<>(url.getHandlerMap().keySet());
            if (url.getRootHandler() != null) {
                served.add("/");
            }
            return served;
        }
        if (mapping instanceof AbstractHandlerMethodMapping<?> methods) {
            Set<String> served = new TreeSet<>();
            methods.getHandlerMethods().keySet().forEach(info -> served.add(String.valueOf(info)));
            return served;
        }
        if (mapping instanceof RouterFunctionMapping router) {
            return router.getRouterFunction() == null ? Set.of() : Set.of(router.getRouterFunction().toString());
        }
        throw new AssertionError(
                """
                %s is a HandlerMapping of a shape this test cannot read, so what it serves is \
                unknown. It must not be treated as empty: that is how a surface outside every \
                control in this suite becomes invisible to the one test written to find it. Teach \
                surfaceOf() to read it."""
                        .formatted(mapping.getClass().getName()));
    }

    /**
     * Whether an anonymous caller is allowed through to {@code path}.
     *
     * <p>The same question {@link AuthorizationFilter} asks in production, put to the same object.
     * The servlet context on the request is load-bearing for the reason {@code RateLimitCoverageTest}
     * records at length: every {@code permitAll} rule decides between its MVC and its Ant candidate
     * by reading the servlet registrations of the context the request carries, and a request built
     * without one matches nothing at all — including the rule naming the exact path being asked
     * about. That failure is silent and reports everything as protected, which is why the control
     * above asserts a known-public path in the other direction.
     */
    private boolean anonymousCanReach(String method, String path) {
        AuthorizationManager<HttpServletRequest> authorization = securityFilterChain.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(AuthorizationFilter.class::isInstance)
                .map(AuthorizationFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No AuthorizationFilter in the security filter chain, so this test can no longer "
                                + "ask what an anonymous caller may reach."))
                .getAuthorizationManager();

        Supplier<Authentication> anonymous = () -> new AnonymousAuthenticationToken(
                "mapped-surface", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        ServletContext servletContext = webContext.getServletContext();
        String contextPath = servletContext.getContextPath();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, method, contextPath + path);
        request.setContextPath(contextPath);
        request.setServletPath(path);

        AuthorizationResult result = authorization.authorize(anonymous, request);
        // An abstention is a request that reaches the controller, the same reading
        // RateLimitCoverageTest takes; anything else quietly shrinks the surface measured here.
        return result == null || result.isGranted();
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }
}
