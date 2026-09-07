package dev.reception.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import dev.reception.business.BusinessHoursRepository;
import dev.reception.business.BusinessRepository;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The atomicity claim, tested the only way it can honestly be tested: by breaking the last step and
 * counting rows.
 *
 * <p>Asserting that a happy-path registration writes four tables proves nothing about atomicity —
 * it would pass just as well with four independent transactions. This forces a failure after the
 * business exists and asserts that nothing survives it, which is the property an owner depends on:
 * a half-created account is one they can neither sign in to nor register again.
 *
 * <p>Its own class because the spy replaces a bean, and a replaced bean means a separate
 * application context.
 */
class RegistrationAtomicityTest extends IntegrationTest {

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

    @MockitoSpyBean
    private BusinessHoursRepository hours;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient client;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, port);
        databaseCleaner.clean();
    }

    @Test
    void a_failure_writing_the_default_week_leaves_no_user_business_or_membership() {
        doThrow(new IllegalStateException("forced failure at the last step"))
                .when(hours)
                .saveAll(any());

        ResponseEntity<String> response =
                client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(users.count()).isZero();
        assertThat(businesses.count()).isZero();
        assertThat(memberships.count()).isZero();
    }

    /** And the address is genuinely free afterwards, which is the point of leaving nothing behind. */
    @Test
    void the_email_can_be_registered_again_after_a_failed_attempt() {
        doThrow(new IllegalStateException("forced failure at the last step"))
                .when(hours)
                .saveAll(any());
        client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");
        client.forgetCookies();

        org.mockito.Mockito.reset(hours);
        ResponseEntity<String> retry =
                client.register("nino@aria.test", "a-long-enough-password", "Salon Aria");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(users.count()).isEqualTo(1);
    }
}
