package dev.reception.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The limits from docs/06-security.md §5, switched on deliberately.
 *
 * <p>They are off for the rest of the suite, because a hundred logins in other tests would
 * otherwise fail the hundred-and-first for a reason unrelated to what it asserts. Turning them on
 * here is what keeps "the limits are configured" from being an untested claim.
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class RateLimitTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        // Buckets outlive a test but not the process, so each test starts from a full budget
        // rather than from whatever the previous one spent.
        rateLimitFilter.reset();
    }

    /** Ten attempts per fifteen minutes, per address — the credential-stuffing budget. */
    @Test
    void login_is_limited_and_the_refusal_says_when_to_come_back() {
        AuthTestClient client = new AuthTestClient(rest, port);
        client.register("nino@aria.test", PASSWORD, "Salon Aria");

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            responses.add(client.login("nino@aria.test", "wrong-password-attempt"));
        }

        ResponseEntity<String> limited = responses.getLast();
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(limited.getHeaders().getContentType()).hasToString("application/problem+json");

        // The limit bounds attempts, not just failures: the first ten were let through.
        assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.UNAUTHORIZED))
                .hasSize(10);
    }

    /**
     * The limit counts attempts regardless of outcome, so a correct password does not buy an
     * attacker more guesses.
     */
    @Test
    void a_successful_login_still_consumes_from_the_budget() {
        AuthTestClient client = new AuthTestClient(rest, port);
        client.register("nino@aria.test", PASSWORD, "Salon Aria");

        for (int i = 0; i < 10; i++) {
            client.login("nino@aria.test", PASSWORD);
        }

        assertThat(client.login("nino@aria.test", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Registration is cheap for us and valuable to an abuser: five an hour. */
    @Test
    void registration_is_limited() {
        AuthTestClient client = new AuthTestClient(rest, port);

        List<HttpStatus> statuses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            client.forgetCookies();
            statuses.add(HttpStatus.valueOf(
                    client.register("owner" + i + "@aria.test", PASSWORD, "Salon " + i)
                            .getStatusCode()
                            .value()));
        }

        assertThat(statuses).filteredOn(HttpStatus.CREATED::equals).hasSize(5);
        assertThat(statuses.getLast()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Only the endpoints named in the policy are limited; nothing else is silently throttled. */
    @Test
    void an_unlisted_endpoint_is_not_limited() {
        AuthTestClient client = new AuthTestClient(rest, port);
        client.register("nino@aria.test", PASSWORD, "Salon Aria");

        for (int i = 0; i < 30; i++) {
            assertThat(client.get("/auth/me").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
