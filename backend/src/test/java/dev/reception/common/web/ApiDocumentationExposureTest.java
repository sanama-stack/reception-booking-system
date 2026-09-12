package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.common.ratelimit.RateLimitProperties;
import dev.reception.publicapi.PublicTestClient;
import dev.reception.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * <strong>The accepted risk in docs/06-security.md §15, pinned so it cannot go stale in silence.</strong>
 *
 * <p>§15's last entry records that {@code /docs}, {@code /openapi} and {@code /swagger-ui} are
 * permitted to everyone, {@code prod} included, and calls that <em>"the first thing to change on an
 * internet-reachable host"</em>. That is a claim about a live configuration, and an accepted-risk
 * table is the one place in this repository where a claim going out of date is the <em>whole</em>
 * failure: an entry describing a risk that was since closed teaches a reader the system is worse
 * than it is, and one understating a risk teaches the opposite. Nothing asserted it.
 *
 * <p><strong>The phase-11 review of §15 found the entry understated.</strong> It recorded the
 * disclosure and not the amplification. These paths are mapped by springdoc, outside {@code
 * dev.reception}, and every derived control in this repository filters on that package — deliberately,
 * so that a springdoc release renaming its paths cannot break the build. {@code
 * RateLimitCoverageTest} inherits the filter, so its guarantee that "every endpoint an anonymous
 * caller can reach is rate limited" is a guarantee about <em>this application's own</em> handlers.
 * The three springdoc paths are anonymous, unlimited, and invisible to every sweep in the tree.
 *
 * <p><strong>This test asserts the risk as recorded, not as desired.</strong> If a future change
 * closes it — a profile guard on the {@code permitAll}, or a policy covering the paths — these
 * assertions fail on purpose, and the fix is to correct §15 rather than to weaken the test. That is
 * the direction of travel this file exists to notice.
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class ApiDocumentationExposureTest extends IntegrationTest {

    /** Comfortably past the tightest policy in the system, so "unlimited" is not "not yet limited". */
    private static final int ENOUGH_TO_TRIP_ANY_POLICY = 200;

    private static final List<String> DOCUMENTATION_PATHS =
            List.of("/openapi", "/swagger-ui/index.html", "/openapi/swagger-config");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RateLimitProperties rateLimits;

    @Test
    @DisplayName("the API documentation answers a caller who has never signed in")
    void the_endpoint_surface_is_published_to_anyone_who_asks() {
        ResponseEntity<String> spec = new PublicTestClient(rest, port).get("/openapi");

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
     * The half §15 did not record.
     *
     * <p>The Definition of Done requires every public endpoint to be rate limited, and {@code
     * RateLimitCoverageTest} enforces it across the surface it can see. These three paths are outside
     * it, so an anonymous caller pulls a document of tens of kilobytes as fast as it can be asked
     * for — the amplifier argument §5 makes about {@code /health}, at a much larger response size.
     */
    @Test
    @DisplayName("and it is unlimited: no policy matches, and nothing refuses two hundred requests")
    void the_documentation_endpoints_carry_no_rate_limit() {
        for (String path : DOCUMENTATION_PATHS) {
            assertThat(rateLimits.policies().stream().anyMatch(policy -> policy.matches("GET", path)))
                    .as("%s is matched by a rate-limit policy. If that was deliberate, §15's entry no "
                            + "longer describes the system and must say so.", path)
                    .isFalse();
        }

        PublicTestClient stranger = new PublicTestClient(rest, port);
        long refused = 0;
        long bytes = 0;
        for (int i = 0; i < ENOUGH_TO_TRIP_ANY_POLICY; i++) {
            ResponseEntity<String> response = stranger.get("/openapi");
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                refused++;
            } else if (response.getBody() != null) {
                bytes += response.getBody().length();
            }
        }

        assertThat(refused)
                .as("two hundred anonymous requests for the full specification, none refused")
                .isZero();
        assertThat(bytes)
                .as("and they are not cheap: this is the egress an unauthenticated caller can draw "
                        + "from one endpoint in one loop, with nothing in the way")
                .isGreaterThan(1_000_000L);
    }

    /**
     * The positive control, and the reason the test above is worth anything.
     *
     * <p>T89: "two hundred requests, none refused" is also true of an application whose rate limiting
     * is switched off — which is the default for the rest of this suite, and is one
     * {@code @TestPropertySource} line away from being true here. The same client, in the same run,
     * must meet a limit on a path that carries one.
     */
    @Test
    @DisplayName("rate limiting is switched on in this class, or the assertion above proves nothing")
    void the_limiter_is_running() {
        PublicTestClient stranger = new PublicTestClient(rest, port);

        HttpStatus last = null;
        for (int i = 0; i < 7; i++) {
            last = HttpStatus.valueOf(stranger
                    .post("/public/appointments/lookup", Map.of("confirmationCode", "ZZZZZZZZ", "phone", "+995555123456"))
                    .getStatusCode()
                    .value());
        }

        assertThat(last)
                .as("the tightest policy in the system is five an hour; if this is not refused, the "
                        + "limiter is off and 'the documentation is unlimited' is a statement about "
                        + "nothing")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
