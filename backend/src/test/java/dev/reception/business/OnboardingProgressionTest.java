package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The onboarding checklist against the catalog phase 04 actually builds.
 *
 * <p>{@link OnboardingDerivationTest} covers all sixteen combinations by stubbing
 * {@link CatalogReadiness}; {@link OnboardingEndpointTest} covers the hours. What neither could
 * cover until now is the port's <em>real</em> implementation — whether
 * {@code DatabaseCatalogReadiness} asks the questions the derivation assumes it asks.
 *
 * <p>The flags are walked forward one configuration step at a time, because the interesting
 * failures are the ones where a flag turns true too early: a schedule belonging to a deactivated
 * employee, or a service with nobody assigned, would each let an owner reach "your page is ready"
 * with a page nobody can book on.
 */
class OnboardingProgressionTest extends IntegrationTest {

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

    private String checklist() {
        return owner.get("/business/onboarding").getBody();
    }

    private boolean flag(String name) {
        return JsonPath.read(checklist(), "$." + name);
    }

    private String createService(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", 45);
        body.put("price", "60.00");
        return JsonPath.read(owner.post("/services", body).getBody(), "$.id");
    }

    private String createEmployee(String fullName) {
        return JsonPath.read(owner.post("/employees", Map.of("fullName", fullName)).getBody(), "$.id");
    }

    private void giveSchedule(String employeeId) {
        owner.put(
                "/employees/" + employeeId + "/schedule",
                Map.of("schedule", List.of(Map.of("dayOfWeek", 1, "startsAt", "09:00", "endsAt", "17:00"))));
    }

    @Test
    @DisplayName("the checklist reaches 'your page is ready' as configuration completes, one step at a time")
    void walks_the_checklist_to_ready() {
        // Registration seeds Mon–Fri hours and nothing else.
        assertThat(flag("hoursConfigured")).isTrue();
        assertThat(flag("hasActiveService")).isFalse();
        assertThat(flag("publicPageReady")).isFalse();

        String service = createService("Haircut");
        assertThat(flag("hasActiveService")).isTrue();
        assertThat(flag("hasActiveEmployee")).isFalse();
        assertThat(flag("publicPageReady")).isFalse();

        String employee = createEmployee("Nino Beridze");
        assertThat(flag("hasActiveEmployee")).isTrue();
        assertThat(flag("hasEmployeeSchedule")).isFalse();

        giveSchedule(employee);
        assertThat(flag("hasEmployeeSchedule")).isTrue();
        // Still not bookable: nobody is assigned to the service, which is the most common reason a
        // fully configured business still cannot take a booking.
        assertThat(flag("hasBookableService")).isFalse();
        assertThat(flag("publicPageReady")).isFalse();

        owner.put("/services/" + service + "/employees", Map.of("employeeIds", List.of(employee)));
        assertThat(flag("hasBookableService")).isTrue();
        assertThat(flag("publicPageReady")).isTrue();
    }

    @Test
    @DisplayName("a schedule belonging to a deactivated employee is not readiness")
    void a_deactivated_employees_schedule_does_not_count() {
        String employee = createEmployee("Nino Beridze");
        giveSchedule(employee);
        assertThat(flag("hasEmployeeSchedule")).isTrue();

        owner.post("/employees/" + employee + "/deactivate", Map.of());

        assertThat(flag("hasActiveEmployee")).isFalse();
        assertThat(flag("hasEmployeeSchedule")).isFalse();
    }

    @Test
    @DisplayName("deactivating the only assigned employee takes the page out of the ready state")
    void deactivating_the_last_employee_unmakes_readiness() {
        String service = createService("Haircut");
        String employee = createEmployee("Nino Beridze");
        giveSchedule(employee);
        owner.put("/services/" + service + "/employees", Map.of("employeeIds", List.of(employee)));
        assertThat(flag("publicPageReady")).isTrue();

        owner.post("/employees/" + employee + "/deactivate", Map.of());

        // The exact case that makes a stored checklist wrong: nothing writes to the checklist here,
        // so a persisted one would still say ready. Deriving on every read cannot drift.
        assertThat(flag("publicPageReady")).isFalse();
        assertThat(flag("hasBookableService")).isFalse();
    }

    @Test
    @DisplayName("deactivating the only service takes the page out of the ready state")
    void deactivating_the_last_service_unmakes_readiness() {
        String service = createService("Haircut");
        String employee = createEmployee("Nino Beridze");
        giveSchedule(employee);
        owner.put("/services/" + service + "/employees", Map.of("employeeIds", List.of(employee)));

        owner.post("/services/" + service + "/deactivate", Map.of());

        assertThat(flag("hasActiveService")).isFalse();
        assertThat(flag("hasBookableService")).isFalse();
        assertThat(flag("publicPageReady")).isFalse();
    }

    @Test
    @DisplayName("clearing the opening hours takes the page out of the ready state")
    void clearing_the_hours_unmakes_readiness() {
        String service = createService("Haircut");
        String employee = createEmployee("Nino Beridze");
        giveSchedule(employee);
        owner.put("/services/" + service + "/employees", Map.of("employeeIds", List.of(employee)));

        owner.put("/business/hours", Map.of("hours", List.of()));

        assertThat(flag("hoursConfigured")).isFalse();
        // publicPageReady is the full conjunction, so this holds even though every catalog flag is
        // still true.
        assertThat(flag("publicPageReady")).isFalse();
        assertThat(flag("hasBookableService")).isTrue();
    }

    @Test
    @DisplayName("an assignment to an employee with no schedule is not a bookable service")
    void an_unscheduled_employee_does_not_make_a_service_bookable() {
        String service = createService("Haircut");
        String employee = createEmployee("Nino Beridze");
        owner.put("/services/" + service + "/employees", Map.of("employeeIds", List.of(employee)));

        assertThat(flag("hasActiveService")).isTrue();
        assertThat(flag("hasActiveEmployee")).isTrue();
        assertThat(flag("hasBookableService")).isFalse();
    }

    @Test
    @DisplayName("one bookable service is enough, even alongside services nobody can perform")
    void one_bookable_service_is_enough() {
        String bookable = createService("Haircut");
        createService("Colour");
        String employee = createEmployee("Nino Beridze");
        giveSchedule(employee);
        owner.put("/services/" + bookable + "/employees", Map.of("employeeIds", List.of(employee)));

        assertThat(flag("hasBookableService")).isTrue();
        assertThat(flag("publicPageReady")).isTrue();
    }
}
