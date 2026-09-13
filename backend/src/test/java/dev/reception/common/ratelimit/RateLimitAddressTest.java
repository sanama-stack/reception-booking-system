package dev.reception.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * <strong>The limits are per address, and the address is the visitor's rather than the proxy's.</strong>
 *
 * <p>docs/06-security.md §1 names <em>rate limiting by IP</em> as the primary control for the
 * availability row, and {@link RateLimitProperties} repeats the claim in prose four times — most
 * plainly for {@code refresh} (<em>"keyed on the client address rather than the proxy's, which is
 * what server.forward-headers-strategy buys"</em>) and for {@code health} (<em>"it has a bucket of
 * its own and cannot be starved by traffic arriving through Caddy"</em>). <strong>Nothing asserted
 * any of it.</strong> {@link RateLimitCoverageTest} proves every public endpoint is matched by
 * <em>a</em> policy; {@link RateLimitTest} proves a limit bites. Both drive one client, so both are
 * equally true of a filter that keys every bucket on a constant.
 *
 * <p>That is not a cosmetic gap, because the failure it admits is the inverse of the control. A
 * limiter that pools every visitor into one bucket does not merely fail to stop an attacker — it
 * hands one the ability to exhaust the budget for everybody, which is the availability outage §1
 * lists the control to prevent. {@code application.yml} says so in as many words: <em>"a limit that
 * locks out real users while stopping nobody"</em>.
 *
 * <p><strong>Three independent facts hold the control up and none of them was checked.</strong>
 *
 * <ol>
 *   <li>{@code server.forward-headers-strategy: framework} is set, and in the base profile, so it
 *       applies to {@code prod}. Delete the line and every visitor arrives as Caddy.
 *   <li>Spring Boot registers {@link ForwardedHeaderFilter} at {@code HIGHEST_PRECEDENCE}, ahead of
 *       {@link RateLimitFilter} at {@code HIGHEST_PRECEDENCE + 10}. <strong>The whole control rests
 *       on a margin of ten.</strong> Tighten {@code RateLimitFilter}'s {@code @Order} to run "as
 *       early as possible" — the obvious instinct for a filter that must precede authentication —
 *       and the two tie, the order becomes arbitrary, and the limiter may read the peer address
 *       again.
 *   <li>{@link RateLimitFilter} keys on {@code getRemoteAddr()}, which the wrapper installed by that
 *       filter overrides.
 * </ol>
 *
 * <p>Any one of the three can be undone without a single existing test going red.
 *
 * <p><strong>Why the assertions here are not vacuous.</strong> Every request in this suite arrives
 * from {@code 127.0.0.1}, so two callers can only be told apart if {@code X-Forwarded-For} is both
 * transmitted and honoured. A pass therefore requires the header to have survived the client (T99 —
 * a restricted header is dropped silently), the filter to have run, and the ordering to have held.
 * There is no arrangement in which these tests pass and the control is absent — and the converse
 * vacuity, a limiter switched off entirely, is caught by requiring each address to be refused in
 * its own right.
 *
 * <p>Addresses are from {@code 203.0.113.0/24} (TEST-NET-3), which is reserved for documentation and
 * can never be a real client.
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class RateLimitAddressTest extends IntegrationTest {

    /** The tightest policy in the system — five an hour — so the loops here are short. */
    private static final int LOOKUP_BUDGET = 5;

    private static final String ALICE = "203.0.113.7";
    private static final String MALLORY = "203.0.113.99";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        // Buckets outlive a test but not the process (RateLimitTest says the same).
        rateLimitFilter.reset();
        // The JDK factory for PublicTestClient's reason: Apache HttpClient 5 retries 429 and honours
        // Retry-After, which would turn every assertion below into a sleep.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /**
     * The finding, stated as a test: one abusive caller cannot spend anybody else's budget.
     *
     * <p>Mallory exhausts the tightest limit in the system and is refused. Alice — who has made no
     * request at all — must still be served. Against a single shared bucket Alice is refused on her
     * first attempt, which is precisely the outage the control exists to prevent.
     *
     * <p>The tail of the test is its positive control. "Alice was served" is also true of an
     * application with no rate limiting, so Alice is then made to exhaust her own budget and be
     * refused in turn. Both halves must hold: separate budgets, and a real limit on each.
     */
    @Test
    @DisplayName("one address exhausting its budget does not spend another's")
    void an_abusive_caller_cannot_lock_out_everybody_else() {
        List<HttpStatus> mallory = new ArrayList<>();
        for (int i = 0; i < LOOKUP_BUDGET + 1; i++) {
            mallory.add(lookupFrom(MALLORY));
        }

        assertThat(mallory.subList(0, LOOKUP_BUDGET))
                .as("Mallory's budget is five, and each of the five reached the handler")
                .containsOnly(HttpStatus.UNAUTHORIZED);
        assertThat(mallory.getLast())
                .as("the sixth is refused, so the limit is switched on for this test")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(lookupFrom(ALICE))
                .as(
                        """
                        Alice has made no request and must be served. A TOO_MANY_REQUESTS here means \
                        the buckets are not keyed by address: either X-Forwarded-For is no longer \
                        honoured (server.forward-headers-strategy), or ForwardedHeaderFilter no \
                        longer runs ahead of RateLimitFilter, or clientAddress() stopped reading the \
                        remote address. Every visitor is then sharing one budget and any stranger can \
                        close this endpoint for all of them.""")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        for (int i = 1; i < LOOKUP_BUDGET; i++) {
            lookupFrom(ALICE);
        }
        assertThat(lookupFrom(ALICE))
                .as("Alice is limited in her own right — without this, the assertion above is also "
                        + "true of an application that rate limits nothing")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The other direction, and the one that separates "keyed by address" from "keyed by anything
     * that happens to vary".
     *
     * <p>A filter keying on a fresh value per request — a connection, a session, a random id — gives
     * every request its own bucket and passes the test above for the wrong reason. Here the two
     * halves of the budget are spent under the same forwarded address, and the limit must still
     * arrive on the sixth request overall.
     */
    @Test
    @DisplayName("the same forwarded address shares one budget across requests")
    void one_address_is_one_bucket() {
        for (int i = 0; i < LOOKUP_BUDGET; i++) {
            assertThat(lookupFrom(MALLORY)).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(lookupFrom(MALLORY))
                .as("a sixth request from the same address must meet the limit the first five built")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The margin of ten, asserted.
     *
     * <p>The behavioural tests above prove the ordering holds today. This one names <em>why</em>, so
     * that the edit which breaks it fails here with an explanation rather than there with a puzzle:
     * a tie in filter order is resolved arbitrarily, and the losing arrangement is the silent one.
     */
    @Test
    @DisplayName("ForwardedHeaderFilter is registered ahead of RateLimitFilter")
    void the_address_is_rewritten_before_it_is_counted() {
        int forwarded = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> registration.getFilter() instanceof ForwardedHeaderFilter)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        """
                        No ForwardedHeaderFilter is registered. server.forward-headers-strategy is \
                        no longer 'framework', so X-Forwarded-For is ignored, every visitor behind \
                        Caddy arrives as Caddy, and the per-address limits have collapsed into one \
                        global bucket."""))
                .getOrder();

        Integer rateLimit = OrderUtils.getOrder(RateLimitFilter.class);
        assertThat(rateLimit)
                .as("RateLimitFilter's @Order is what keeps it behind the rewrite; unannotated, it "
                        + "orders LOWEST_PRECEDENCE and lands behind authentication too")
                .isNotNull();

        assertThat(forwarded)
                .as(
                        """
                        ForwardedHeaderFilter must run strictly before RateLimitFilter, or the \
                        limiter counts the proxy's address instead of the visitor's. The gap is ten, \
                        and the tempting edit — ordering RateLimitFilter at HIGHEST_PRECEDENCE so it \
                        certainly precedes authentication — closes it to nothing. Filters tied on \
                        order are sequenced arbitrarily, so that edit does not fail: it makes the \
                        control a coin toss.""")
                .isLessThan(rateLimit);
    }

    /**
     * One confirmation-code lookup, presented as arriving from {@code clientAddress}.
     *
     * <p>The code is deliberately wrong: this class is about who is counted, not about what the
     * endpoint answers, and an unauthenticated refusal is the cheapest real answer it gives. The
     * limiter runs ahead of routing, so the body never needs to be good.
     */
    private HttpStatus lookupFrom(String clientAddress) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        headers.set("X-Forwarded-For", clientAddress);

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/api/public/appointments/lookup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("confirmationCode", "ZZZZZZZZ", "phone", "+995555123456"), headers),
                String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
