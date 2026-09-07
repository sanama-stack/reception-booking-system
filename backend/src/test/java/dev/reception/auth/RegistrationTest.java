package dev.reception.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.business.BusinessHoursRepository;
import dev.reception.business.BusinessRepository;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Registration is the only genuinely interesting write in this phase: four tables in one
 * transaction, and a failure at any step must leave nothing behind.
 */
class RegistrationTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private BusinessHoursRepository hours;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient client;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, port);
        // These tests count rows, so they start from a known empty state. Transactional
        // rollback does not apply: the request runs on the server's own thread and commits.
        databaseCleaner.clean();
    }

    @Test
    void creates_user_business_membership_and_the_default_week_in_one_transaction() {
        ResponseEntity<String> response =
                client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(users.count()).isEqualTo(1);
        assertThat(businesses.count()).isEqualTo(1);
        assertThat(memberships.count()).isEqualTo(1);

        var business = businesses.findAll().getFirst();
        assertThat(business.slug()).isEqualTo("salon-aria");
        // Monday to Friday. A day with no row is closed, so the weekend is absent rather than
        // present and zero-length.
        assertThat(hours.findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(business.getId()))
                .hasSize(5)
                .allSatisfy(row -> {
                    assertThat(row.opensAt()).hasToString("09:00");
                    assertThat(row.closesAt()).hasToString("17:00");
                });

        var membership = memberships.findAll().getFirst();
        assertThat(membership.role()).isEqualTo(Role.OWNER);
        assertThat(membership.businessId()).isEqualTo(business.getId());
    }

    @Test
    void the_response_carries_both_cookies_and_neither_is_reachable_from_javascript() {
        ResponseEntity<String> response =
                client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");

        List<String> setCookies = response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of());

        assertThat(setCookies).hasSize(2);
        assertThat(setCookies).allSatisfy(cookie -> assertThat(cookie)
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax")
                .containsIgnoringCase("Path=/"));
        assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie).startsWith("access_token="));
        assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie).startsWith("refresh_token="));
    }

    /** No token, no hash, and nothing about the password appears in what the client is told. */
    @Test
    void the_response_body_carries_no_credential() {
        ResponseEntity<String> response =
                client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");

        assertThat(response.getBody())
                .doesNotContain("a-long-enough-password")
                .doesNotContain("passwordHash")
                .doesNotContain("$2a$")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token");
    }

    @Test
    void the_password_is_stored_only_as_a_bcrypt_hash() {
        client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");

        var user = users.findByEmail("nino@aria.test").orElseThrow();
        assertThat(user.passwordHash()).startsWith("$2a$12$").isNotEqualTo("a-long-enough-password");
    }

    @Test
    void a_duplicate_email_is_refused_and_creates_nothing() {
        client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");
        client.forgetCookies();

        ResponseEntity<String> second =
                client.register("nino@aria.test", "another-long-password", "Different Business");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"code\":\"EMAIL_TAKEN\"");
        assertThat(users.count()).isEqualTo(1);
        assertThat(businesses.count()).isEqualTo(1);
    }

    /** The email column is citext, so uniqueness does not depend on the caller's capitalisation. */
    @Test
    void a_duplicate_email_in_a_different_case_is_still_a_duplicate() {
        client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");
        client.forgetCookies();

        ResponseEntity<String> second =
                client.register("NINO@ARIA.TEST", "another-long-password", "Different Business");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(users.count()).isEqualTo(1);
    }

    /**
     * The pre-check cannot decide this — both requests pass it. The unique index does, and the
     * loser must get a clean {@code 409} rather than an unhandled integrity error.
     */
    @Test
    void concurrent_registration_on_one_email_produces_exactly_one_account() throws Exception {
        int attempts = 8;
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<Integer>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                calls.add(() -> new AuthTestClient(rest, port)
                        .register("race@aria.test", "a-long-enough-password", "Race Salon")
                        .getStatusCode()
                        .value());
            }
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(calls)) {
                statuses.add(future.get());
            }

            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(attempts - 1);
        }
        assertThat(users.count()).isEqualTo(1);
        assertThat(memberships.count()).isEqualTo(1);
        assertThat(businesses.count()).isEqualTo(1);
    }

    /** Two businesses with the same name get distinct public URLs. */
    @Test
    void a_colliding_business_name_gets_a_suffixed_slug() {
        client.register("one@aria.test", "a-long-enough-password", "Salon Aria");
        client.forgetCookies();
        client.register("two@aria.test", "a-long-enough-password", "Salon Aria");

        assertThat(businesses.findAll()).extracting(b -> b.slug()).containsExactlyInAnyOrder("salon-aria", "salon-aria-2");
    }

    @Test
    void a_short_password_is_rejected_with_a_field_level_message() {
        ResponseEntity<String> response = client.post(
                "/auth/register",
                Map.of("email", "nino@aria.test", "password", "short", "fullName", "N", "businessName", "B"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_FAILED\"").contains("\"field\":\"password\"");
        assertThat(users.count()).isZero();
    }

    /**
     * Screens render the server's message verbatim (docs/02-product-architecture.md §7), so the
     * server has to write for a person. Bean Validation's defaults — "size must be between 10 and
     * 200" — are written for a developer, and shipping one straight to a customer is the failure
     * this guards against. Asserting the shape rather than the exact wording keeps the copy free to
     * change.
     */
    @Test
    void validation_messages_are_written_for_a_person_not_a_developer() {
        ResponseEntity<String> response = client.post(
                "/auth/register",
                Map.of("email", "not-an-email", "password", "short", "fullName", "", "businessName", ""));

        assertThat(response.getBody())
                .doesNotContain("must be between")
                .doesNotContain("must not be blank")
                .doesNotContain("must be a well-formed");
        assertThat(response.getBody()).contains("Use at least 10 characters.");
    }

    @Test
    void a_malformed_email_is_rejected() {
        ResponseEntity<String> response = client.post(
                "/auth/register",
                Map.of(
                        "email", "not-an-email",
                        "password", "a-long-enough-password",
                        "fullName", "N",
                        "businessName", "B"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"field\":\"email\"");
        assertThat(users.count()).isZero();
    }
}
