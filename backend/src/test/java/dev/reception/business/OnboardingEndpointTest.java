package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code GET /business/onboarding} against real state.
 *
 * <p>{@link OnboardingDerivationTest} covers the combinations; this covers the one thing a unit test
 * cannot, which is that the flags follow what the database actually holds rather than what a stored
 * flag once said.
 */
class OnboardingEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient owner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");
    }

    @Test
    @DisplayName("a freshly registered business has its hours and nothing else")
    void a_new_business_has_only_hours() {
        String body = owner.get("/business/onboarding").getBody();

        assertThat((boolean) JsonPath.read(body, "$.hoursConfigured")).isTrue();
        assertThat((boolean) JsonPath.read(body, "$.hasActiveService")).isFalse();
        assertThat((boolean) JsonPath.read(body, "$.hasActiveEmployee")).isFalse();
        assertThat((boolean) JsonPath.read(body, "$.hasEmployeeSchedule")).isFalse();
        assertThat((boolean) JsonPath.read(body, "$.hasBookableService")).isFalse();
        assertThat((boolean) JsonPath.read(body, "$.publicPageReady")).isFalse();
        assertThat((String) JsonPath.read(body, "$.bookingUrl")).isEqualTo("/book/salon-aria");
    }

    @Test
    @DisplayName("clearing the week turns hoursConfigured off — the checklist is derived, not stored")
    void the_checklist_follows_the_data() {
        owner.put("/business/hours", Map.of("hours", List.of()));

        assertThat((boolean) JsonPath.read(owner.get("/business/onboarding").getBody(), "$.hoursConfigured"))
                .isFalse();

        owner.put("/business/hours", Map.of("hours", List.of(Map.of("dayOfWeek", 1, "opensAt", "09:00", "closesAt", "17:00"))));

        assertThat((boolean) JsonPath.read(owner.get("/business/onboarding").getBody(), "$.hoursConfigured"))
                .isTrue();
    }
}
