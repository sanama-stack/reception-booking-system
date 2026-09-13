package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.common.ratelimit.RateLimitPolicy;
import dev.reception.common.ratelimit.RateLimitProperties;
import dev.reception.support.IntegrationTest;
import dev.reception.support.ResourceSurface;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>The accepted risk in docs/06-security.md §15, and the half of it that was closed.</strong>
 *
 * <p>§15 records that {@code /docs}, {@code /openapi} and {@code /swagger-ui} are permitted to
 * everyone, {@code prod} included, and calls that <em>"the first thing to change on an
 * internet-reachable host"</em>. **That disclosure is still accepted and is still true**, and the
 * first test here holds it to the table: an accepted-risk row whose subject is a live configuration
 * is the row most likely to drift, and nothing else in the suite would notice it being closed.
 *
 * <p><strong>The amplification was not accepted — it was simply never recorded.</strong> The
 * phase-11 review of §15 found these paths anonymous <em>and unlimited</em>: the specification is
 * ~45 KB, {@code swagger-ui-bundle.js} is 1.4 MB, one page load is roughly 1.8 MB, and two hundred
 * consecutive requests were served without one refusal. They were invisible to {@code
 * RateLimitCoverageTest} because its derivation filtered handlers to {@code dev.reception} — which
 * also meant a policy written for them was reported as an orphan, so the derivation that hid the
 * gap rejected the fix as well. Both are changed: the derivation now sees every mapped endpoint, and
 * four policies cover the documentation surface.
 *
 * <p><strong>The disclosure and the amplification are separate decisions and this class keeps them
 * apart.</strong> If the first test fails, somebody closed the disclosure and §15's row must be
 * rewritten. If the second fails, somebody removed a limit that was added deliberately.
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class ApiDocumentationExposureTest extends IntegrationTest {

    /** {@code /openapi/**} is thirty a minute — the tightest of the documentation budgets. */
    private static final int SPEC_BUDGET = 30;

    /**
     * The three subjects §15 names, as a floor under the derivation below.
     *
     * <p>Not the surface — the surface is derived. This is the positive control: §15's row is about
     * {@code /docs}, {@code /openapi} and {@code /swagger-ui}, and a derivation that stopped seeing
     * any of them would empty the assertions built on it and pass.
     */
    private static final List<String> WHAT_SECTION_15_NAMES = List.of("/docs", "/openapi", "/swagger-ui/index.html");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RateLimitProperties rateLimits;

    @Autowired
    private ApplicationContext context;

    /**
     * Two beans implement this type — ours and springdoc's. The qualifier picks the one that routes
     * real requests; omitting it fails with {@code NoUniqueBeanDefinition}, which reads like a
     * missing bean and is the opposite.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /**
     * Each test speaks from its own address, so the budgets do not leak between them.
     *
     * <p>Buckets outlive a test but not the process, and the documentation budgets are small enough
     * that one method could otherwise start from a budget another had spent. {@code RateLimitTest}
     * solves this by calling the filter's package-private {@code reset()}; this class is in another
     * package and does not need it, because the limits are keyed per address — the property {@code
     * RateLimitAddressTest} was written to assert. Using it here is the cheapest possible
     * demonstration that it holds.
     */
    private String address = "203.0.113.1";

    private ResponseEntity<String> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON, MediaType.ALL));
        headers.set("X-Forwarded-For", address);
        return rest.exchange(
                "http://localhost:" + port + "/api" + path, method, new HttpEntity<>(body, headers), String.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void useAFreshAddress(org.junit.jupiter.api.TestInfo test) {
        // Derived from the test name so it is stable across runs and distinct between methods.
        address = "203.0.113." + (1 + Math.floorMod(test.getDisplayName().hashCode(), 200));
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    @DisplayName("the API documentation still answers a caller who has never signed in")
    void the_endpoint_surface_is_published_to_anyone_who_asks() {
        ResponseEntity<String> spec = get("/openapi");

        assertThat(spec.getStatusCode())
                .as("§15 records this as permitted to everyone; a 401 here means the risk was closed "
                        + "and the table now describes a system that no longer exists")
                .isEqualTo(HttpStatus.OK);
        assertThat(spec.getBody())
                .as("the risk §15 names is that this publishes the entire endpoint surface, so the "
                        + "assertion has to be that the surface is really in there — a 200 carrying an "
                        + "error page would pass a status check and prove nothing")
                .contains("/public/businesses/{slug}/availability", "/auth/login", "/appointments");
    }

    /**
     * The scope of §15's disclosure, which is narrower than the row sounds — <strong>by accident.</strong>
     *
     * <p>springdoc renders the specification twice, as {@code /openapi} and as {@code /openapi.yaml}.
     * {@code SecurityConfig} permits {@code /openapi/**}, and a trailing {@code /**} matches children
     * and the bare path, <em>not</em> a sibling — so the JSON rendering answers anybody and the YAML
     * one answers {@code 401}. Nobody decided that the same document should be public in one format
     * and authenticated in the other; the pattern decided it.
     *
     * <p>It is pinned because the tidy-up is obvious and one character wide. Widening the matcher to
     * catch both siblings reads like consistency and is a widening of the disclosure §15 accepted.
     */
    @Test
    @DisplayName("the YAML rendering of the specification is not public, and that is load-bearing")
    void the_disclosure_stops_at_the_json_rendering() {
        assertThat(get("/openapi").getStatusCode())
                .as("the JSON rendering is the accepted disclosure")
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/openapi.yaml").getStatusCode())
                .as("the YAML rendering is the same document and is NOT part of it. A 200 here means "
                        + "a permitAll pattern was widened and §15's row now understates what is "
                        + "published — the change is a character long and reads like tidying up")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("every publicly reachable documentation path is covered by a policy")
    void the_documentation_surface_is_matched_by_a_policy() {
        Set<String> unlimited = new TreeSet<>();
        for (String path : publicDocumentationPaths()) {
            RateLimitPolicy policy = rateLimits.policies().stream()
                    .filter(candidate -> candidate.matches("GET", path))
                    .findFirst()
                    .orElse(null);
            if (policy == null) {
                unlimited.add(path);
            }
        }

        // Collected rather than asserted in the loop: an assertion inside it reports the first path
        // and stops, which hides how much of the surface is uncovered — and the surface is derived
        // now, so the answer is no longer four things a reader already knows.
        assertThat(unlimited)
                .as(
                        """
                        These are reachable without a token and no policy matches them. An unlimited \
                        documentation path is 1.8 MB of egress a stranger can draw in a loop.""")
                .isEmpty();
    }

    /**
     * The positive control, and the reason the test above is worth anything now that its subject is
     * derived.
     *
     * <p>The surface used to be four paths somebody typed. <strong>It was one short</strong> —
     * {@code /swagger-ui/swagger-initializer.js} is served, anonymous, and was never on it — which is
     * the ordinary fate of a list that describes something it does not read. Derived, the assertion
     * above covers whatever the application actually exposes; undetectably blind, it covers nothing
     * and passes.
     *
     * <p>Both halves are needed. A derivation that returned an empty set passes a
     * {@code containsAll}; one that reported every path as reachable passes it too, and would have
     * swallowed the {@code /openapi.yaml} asymmetry that {@link
     * #the_disclosure_stops_at_the_json_rendering()} exists to protect.
     */
    @Test
    @DisplayName("the documentation surface is derived, and still sees what §15 is about")
    void the_documentation_surface_is_really_derived() {
        Set<String> derived = publicDocumentationPaths();

        assertThat(derived)
                .as("§15's row is about these three; a derivation that has lost one of them is not "
                        + "describing the risk the row records")
                .containsAll(WHAT_SECTION_15_NAMES);

        assertThat(derived)
                .as("the YAML rendering answers 401 and must not be counted as public. A derivation "
                        + "that includes it is reporting reachability it has not checked, and would "
                        + "report anything as reachable")
                .doesNotContain("/openapi.yaml");
    }

    /**
     * Every path an anonymous caller reaches that this application does not serve from its own
     * controllers — which is, today, exactly the documentation surface.
     *
     * <p>Two sources, because the surface has two halves that are invisible to each other. The
     * framework-mapped endpoints come from {@link RequestMappingHandlerMapping} filtered to handlers
     * outside {@code dev.reception} — {@code MappedSurfaceTest} pins that same set from the other
     * side, so a springdoc release adding a path fails there and arrives here. The assets come from
     * {@link ResourceSurface}, shared with {@code RateLimitCoverageTest} precisely so the two cannot
     * disagree about what a resource pattern means.
     *
     * <p><strong>Reachability is the running application's answer, not a re-reading of
     * SecurityConfig.</strong> A {@code 401} or {@code 403} is the security chain refusing; anything
     * else got past it, {@code /docs}'s {@code 302} included — which is why the question is asked
     * this way round rather than by checking for {@code 200}. A {@code 404} counts as reachable too,
     * which is the conservative direction — it asks for a policy on a path that is not there — and
     * is caught by the control rather than tuned away: an application answering {@code 404} to
     * everything reports its whole surface as public here, {@code /openapi.yaml} included, and that
     * is precisely what {@link #the_documentation_surface_is_really_derived()} refuses. Two other classes put the same question
     * to the {@code AuthorizationManager} directly; this one is an HTTP class throughout and asking
     * over the wire is both simpler here and a stricter test, since it also requires the path to
     * survive routing.
     */
    private Set<String> publicDocumentationPaths() {
        Set<String> candidates = new TreeSet<>(ResourceSurface.samplePaths(context));
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("dev.reception")
                    || info.getPathPatternsCondition() == null
                    || info.getMethodsCondition().getMethods().stream()
                            .noneMatch(method -> method.asHttpMethod() == HttpMethod.GET)) {
                return;
            }
            candidates.addAll(info.getPathPatternsCondition().getPatternValues());
        });

        Set<String> reachable = new TreeSet<>();
        candidates.forEach(path -> {
            int status = get(path).getStatusCode().value();
            if (status != 401 && status != 403) {
                reachable.add(path);
            }
        });
        return reachable;
    }

    /**
     * The limit as behaviour rather than as configuration.
     *
     * <p>A policy that matches is not a policy that bites — the gap {@code RateLimitCoverageTest}
     * names in its own javadoc and deliberately leaves to others. The budget is spent against the
     * real endpoint over real HTTP.
     */
    @Test
    @DisplayName("and it bites: the specification stops being served past its budget")
    void the_specification_is_limited() {
        HttpStatus last = null;
        long served = 0;
        for (int i = 0; i < SPEC_BUDGET + 1; i++) {
            ResponseEntity<String> response = get("/openapi");
            last = HttpStatus.valueOf(response.getStatusCode().value());
            if (last == HttpStatus.OK) {
                served++;
            }
        }

        assertThat(served)
                .as("the budget is thirty a minute and a reader of the documentation must still get "
                        + "it — a limit that refuses the first request is not the fix that was wanted")
                .isEqualTo(SPEC_BUDGET);
        assertThat(last)
                .as("the thirty-first is refused; before phase 11 two hundred were served in a row")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The positive control, and the reason the test above is worth anything.
     *
     * <p>T89, and it caught a real vacuity here before the limits existed: <em>"two hundred requests,
     * none refused"</em> passed against an application whose limiter was switched off, because the
     * finding and the vacuum produce identical output. The assertion above is the other direction and
     * cannot pass with the limiter off — but the budgets are per policy, so a documentation budget
     * that silently stopped applying would still leave this class green if nothing else were checked.
     */
    @Test
    @DisplayName("the swagger-ui assets have a budget of their own, not the specification's")
    void the_assets_are_limited_separately() {
        for (int i = 0; i < SPEC_BUDGET + 1; i++) {
            get("/openapi");
        }

        assertThat(get("/openapi").getStatusCode())
                .as("the specification's budget is spent")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(get("/swagger-ui/index.html").getStatusCode())
                .as("the assets are a different policy and must be unaffected — one bucket for the "
                        + "whole documentation surface would let a spec reader lock themselves out of "
                        + "the page that reads it")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("rate limiting is switched on in this class, or none of the above proves anything")
    void the_limiter_is_running() {
        HttpStatus last = null;
        for (int i = 0; i < 7; i++) {
            last = HttpStatus.valueOf(
                    post("/public/appointments/lookup", Map.of("confirmationCode", "ZZZZZZZZ", "phone", "+995555123456"))
                            .getStatusCode()
                            .value());
        }

        assertThat(last)
                .as("the tightest policy in the system is five an hour; if this is not refused, the "
                        + "limiter is off and every assertion in this class is about nothing")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
