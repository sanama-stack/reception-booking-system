package dev.reception.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The isolation backstop, proven by going around the application entirely.
 *
 * <p>Every other isolation test in this suite shows that the <em>application</em> refuses to cross a
 * tenant boundary. This one shows that the refusal does not depend on the application being correct:
 * the insert is written straight to Postgres with {@link JdbcTemplate}, bypassing the service layer,
 * the tenant filter and Hibernate, and it still fails.
 *
 * <p>That distinction is the whole point of the composite foreign keys in
 * {@code V4__catalog_and_staff.sql} (docs/03-data-model.md §1). A future bug — a missing
 * {@code businessId}, a copied-and-edited query, an admin script — cannot produce a cross-tenant row
 * because there is no such row to produce.
 */
class CrossTenantAssignmentTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    /** Salon Aria. */
    private UUID ariaBusiness;

    private UUID ariaService;
    private UUID ariaEmployee;

    /** Datos Auto — the other tenant. */
    private UUID autoBusiness;

    private UUID autoService;
    private UUID autoEmployee;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        AuthTestClient aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");
        ariaBusiness = businessIdOf(aria);
        ariaService = createService(aria, "Haircut");
        ariaEmployee = createEmployee(aria, "Nino Beridze");

        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", PASSWORD, "Datos Auto");
        autoBusiness = businessIdOf(auto);
        autoService = createService(auto, "Oil change");
        autoEmployee = createEmployee(auto, "Dato Kapanadze");
    }

    private static UUID businessIdOf(AuthTestClient client) {
        return UUID.fromString(JsonPath.read(client.get("/business").getBody(), "$.id"));
    }

    private static UUID createService(AuthTestClient client, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", 30);
        body.put("price", "40.00");
        return UUID.fromString(JsonPath.read(client.post("/services", body).getBody(), "$.id"));
    }

    private static UUID createEmployee(AuthTestClient client, String fullName) {
        return UUID.fromString(
                JsonPath.read(client.post("/employees", Map.of("fullName", fullName)).getBody(), "$.id"));
    }

    private void insertAssignment(UUID businessId, UUID employeeId, UUID serviceId) {
        jdbc.update(
                "insert into employee_services (business_id, employee_id, service_id, created_at) "
                        + "values (?, ?, ?, ?)",
                businessId,
                employeeId,
                serviceId,
                java.sql.Timestamp.from(Instant.parse("2026-09-07T12:00:00Z")));
    }

    @Test
    @DisplayName("a legitimate assignment written straight to the database is accepted")
    void accepts_a_same_tenant_row() {
        // The control. Without it the three tests below would pass against a table nothing can be
        // written to at all, and would be proving nothing.
        assertThatCode(() -> insertAssignment(ariaBusiness, ariaEmployee, ariaService))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("select count(*) from employee_services", Long.class))
                .isOne();
    }

    @Test
    @DisplayName("one tenant's employee cannot be assigned to another tenant's service")
    void refuses_an_employee_from_another_tenant() {
        // Aria's business id and employee agree; the service belongs to Datos Auto. The service-side
        // composite key is what refuses it.
        assertThatThrownBy(() -> insertAssignment(ariaBusiness, ariaEmployee, autoService))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from employee_services", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("one tenant's service cannot be assigned to another tenant's employee")
    void refuses_a_service_from_another_tenant() {
        assertThatThrownBy(() -> insertAssignment(autoBusiness, ariaEmployee, autoService))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a third business id cannot be used to smuggle two other tenants' rows together")
    void refuses_a_business_id_that_matches_neither_parent() {
        // The shape a bug would actually take: a stale or defaulted business_id paired with ids that
        // are individually real. Both composite keys refuse it, because neither parent has a row at
        // (that business, that id).
        assertThatThrownBy(() -> insertAssignment(UUID.randomUUID(), ariaEmployee, ariaService))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("through the API, another tenant's employee id is simply not found")
    void refuses_a_cross_tenant_assignment_through_the_api() {
        AuthTestClient aria = new AuthTestClient(rest, port);
        aria.login("nino@aria.test", PASSWORD);

        // 404 rather than 403: a status that varied with existence would confirm that Datos Auto has
        // an employee with that id (docs/06-security.md §3).
        assertThat(aria.put(
                                "/services/" + ariaService + "/employees",
                                Map.of("employeeIds", java.util.List.of(autoEmployee.toString())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(jdbc.queryForObject("select count(*) from employee_services", Long.class))
                .isZero();
    }
}
