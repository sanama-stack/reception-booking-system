package dev.reception.catalog;

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
import org.springframework.http.HttpStatus;

/**
 * The isolation probes for every endpoint phase 04 adds
 * (docs/09-phase-plan.md §5, rule 5).
 *
 * <p>Two shapes, because there are two ways to reach another tenant's data. An endpoint that takes
 * an id is handed one that exists but belongs to someone else, and must answer {@code 404} — not
 * {@code 403}, which would confirm the row exists (docs/06-security.md §3). An endpoint that takes
 * no id is asked for its collection and must return only its own.
 *
 * <p><strong>Every id used here is real.</strong> Probing with a random UUID would pass against an
 * implementation that had no tenant filter at all, which is exactly the implementation this is
 * meant to catch.
 */
class CatalogIsolationTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    /** Salon Aria — the caller doing the probing. */
    private AuthTestClient aria;

    private String ariaService;
    private String ariaEmployee;

    /** Datos Auto — the tenant whose ids are being borrowed. */
    private AuthTestClient auto;

    private String autoService;
    private String autoEmployee;
    private String autoTimeOff;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");
        ariaService = createService(aria, "Haircut");
        ariaEmployee = createEmployee(aria, "Nino Beridze");

        auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", PASSWORD, "Datos Auto");
        autoService = createService(auto, "Oil change");
        autoEmployee = createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + autoEmployee + "/schedule",
                Map.of("schedule", List.of(Map.of("dayOfWeek", 6, "startsAt", "10:00", "endsAt", "14:00"))));
        autoTimeOff = JsonPath.read(
                auto.post(
                                "/employees/" + autoEmployee + "/time-off",
                                Map.of("startDate", "2026-08-01", "endDate", "2026-08-14"))
                        .getBody(),
                "$.id");
    }

    private static String createService(AuthTestClient client, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", 30);
        body.put("price", "40.00");
        return JsonPath.read(client.post("/services", body).getBody(), "$.id");
    }

    private static String createEmployee(AuthTestClient client, String fullName) {
        return JsonPath.read(client.post("/employees", Map.of("fullName", fullName)).getBody(), "$.id");
    }

    // -----------------------------------------------------------------------
    // Services
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /services returns only the caller's own")
    void services_are_not_shared() {
        String body = aria.get("/services").getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.services[*].name")).containsExactly("Haircut");
        assertThat(body).doesNotContain("Oil change");
    }

    @Test
    @DisplayName("reading another tenant's service is a 404")
    void another_tenants_service_cannot_be_read() {
        assertThat(aria.get("/services/" + autoService).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("patching another tenant's service is a 404, and leaves it unchanged")
    void another_tenants_service_cannot_be_patched() {
        assertThat(aria.patch("/services/" + autoService, Map.of("name", "Renamed by a stranger"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat((String) JsonPath.read(auto.get("/services/" + autoService).getBody(), "$.name"))
                .isEqualTo("Oil change");
    }

    @Test
    @DisplayName("deactivating another tenant's service is a 404, and leaves it bookable")
    void another_tenants_service_cannot_be_deactivated() {
        assertThat(aria.post("/services/" + autoService + "/deactivate", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat((boolean) JsonPath.read(auto.get("/services/" + autoService).getBody(), "$.active"))
                .isTrue();
    }

    @Test
    @DisplayName("deleting another tenant's service is a 404, and leaves it in place")
    void another_tenants_service_cannot_be_deleted() {
        assertThat(aria.delete("/services/" + autoService).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(auto.get("/services/" + autoService).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("replacing the employees of another tenant's service is a 404")
    void another_tenants_service_cannot_be_reassigned() {
        assertThat(aria.put("/services/" + autoService + "/employees", Map.of("employeeIds", List.of(ariaEmployee)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("another tenant's employee cannot be assigned to the caller's own service")
    void another_tenants_employee_cannot_be_assigned() {
        assertThat(aria.put("/services/" + ariaService + "/employees", Map.of("employeeIds", List.of(autoEmployee)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(JsonPath.<List<String>>read(aria.get("/services/" + ariaService).getBody(), "$.employeeIds"))
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Employees
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /employees returns only the caller's own")
    void employees_are_not_shared() {
        String body = aria.get("/employees").getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.employees[*].fullName")).containsExactly("Nino Beridze");
        assertThat(body).doesNotContain("Dato Kapanadze");
    }

    @Test
    @DisplayName("reading another tenant's employee is a 404")
    void another_tenants_employee_cannot_be_read() {
        assertThat(aria.get("/employees/" + autoEmployee).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("patching another tenant's employee is a 404, and leaves them unchanged")
    void another_tenants_employee_cannot_be_patched() {
        assertThat(aria.patch("/employees/" + autoEmployee, Map.of("fullName", "Renamed by a stranger"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat((String) JsonPath.read(auto.get("/employees/" + autoEmployee).getBody(), "$.fullName"))
                .isEqualTo("Dato Kapanadze");
    }

    @Test
    @DisplayName("deactivating another tenant's employee is a 404")
    void another_tenants_employee_cannot_be_deactivated() {
        assertThat(aria.post("/employees/" + autoEmployee + "/deactivate", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat((boolean) JsonPath.read(auto.get("/employees/" + autoEmployee).getBody(), "$.active"))
                .isTrue();
    }

    @Test
    @DisplayName("replacing the services of another tenant's employee is a 404")
    void another_tenants_employee_cannot_be_reassigned() {
        assertThat(aria.put("/employees/" + autoEmployee + "/services", Map.of("serviceIds", List.of(ariaService)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("another tenant's service cannot be assigned to the caller's own employee")
    void another_tenants_service_cannot_be_assigned() {
        assertThat(aria.put("/employees/" + ariaEmployee + "/services", Map.of("serviceIds", List.of(autoService)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -----------------------------------------------------------------------
    // Working Schedule and Time Off
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reading another tenant's schedule is a 404, not an empty week")
    void another_tenants_schedule_cannot_be_read() {
        // An empty week would be the more dangerous answer: it looks like a legitimate result and
        // would go unnoticed.
        assertThat(aria.get("/employees/" + autoEmployee + "/schedule").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("replacing another tenant's schedule is a 404, and leaves it in place")
    void another_tenants_schedule_cannot_be_replaced() {
        assertThat(aria.put("/employees/" + autoEmployee + "/schedule", Map.of("schedule", List.of()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(JsonPath.<List<Integer>>read(
                        auto.get("/employees/" + autoEmployee + "/schedule").getBody(), "$.schedule[*].dayOfWeek"))
                .containsExactly(6);
    }

    @Test
    @DisplayName("reading another tenant's time off is a 404")
    void another_tenants_time_off_cannot_be_read() {
        assertThat(aria.get("/employees/" + autoEmployee + "/time-off").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("creating time off against another tenant's employee is a 404")
    void another_tenants_employee_cannot_be_given_time_off() {
        assertThat(aria.post(
                                "/employees/" + autoEmployee + "/time-off",
                                Map.of("startDate", "2026-09-01", "endDate", "2026-09-02"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("deleting another tenant's time off is a 404, and leaves it in place")
    void another_tenants_time_off_cannot_be_deleted() {
        assertThat(aria.delete("/employees/" + autoEmployee + "/time-off/" + autoTimeOff)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(JsonPath.<List<String>>read(
                        auto.get("/employees/" + autoEmployee + "/time-off").getBody(), "$.timeOff[*].id"))
                .containsExactly(autoTimeOff);
    }

    @Test
    @DisplayName("a time-off id cannot be deleted by pairing it with the caller's own employee")
    void a_time_off_id_cannot_be_borrowed() {
        // The employee resolves inside the caller's tenant, so the tenant check passes; the row does
        // not belong to that employee, and the delete must still refuse.
        assertThat(aria.delete("/employees/" + ariaEmployee + "/time-off/" + autoTimeOff)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(JsonPath.<List<String>>read(
                        auto.get("/employees/" + autoEmployee + "/time-off").getBody(), "$.timeOff[*].id"))
                .containsExactly(autoTimeOff);
    }

    // -----------------------------------------------------------------------
    // Onboarding
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one tenant's configuration does not make another tenant's page ready")
    void readiness_is_not_shared() {
        // Datos Auto has a service, an employee and a schedule; Salon Aria has a service and an
        // employee with no schedule. A readiness query missing its tenant filter would report Aria
        // as further along than it is.
        auto.put("/services/" + autoService + "/employees", Map.of("employeeIds", List.of(autoEmployee)));

        String ariaChecklist = aria.get("/business/onboarding").getBody();
        assertThat((boolean) JsonPath.read(ariaChecklist, "$.hasEmployeeSchedule")).isFalse();
        assertThat((boolean) JsonPath.read(ariaChecklist, "$.hasBookableService")).isFalse();

        assertThat((boolean) JsonPath.read(auto.get("/business/onboarding").getBody(), "$.hasBookableService"))
                .isTrue();
    }
}
