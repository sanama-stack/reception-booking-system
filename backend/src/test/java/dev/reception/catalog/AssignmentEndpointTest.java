package dev.reception.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

/**
 * {@code PUT /services/{id}/employees} and {@code PUT /employees/{id}/services} — two views of one
 * table, so they are tested together.
 *
 * <p>The operation is a <em>replace</em>, which is what makes it idempotent: a multi-select control
 * knows which boxes are ticked now, not which ones changed, and a sequence of adds and removes would
 * make the client responsible for a diff it cannot compute reliably.
 */
class AssignmentEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient owner;
    private String haircut;
    private String colour;
    private String nino;
    private String dato;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");

        haircut = createService("Haircut", 45);
        colour = createService("Colour", 120);
        nino = createEmployee("Nino Beridze");
        dato = createEmployee("Dato Kapanadze");
    }

    private String createService(String name, int durationMinutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", durationMinutes);
        body.put("price", "60.00");
        return JsonPath.read(owner.post("/services", body).getBody(), "$.id");
    }

    private String createEmployee(String fullName) {
        return JsonPath.read(owner.post("/employees", Map.of("fullName", fullName)).getBody(), "$.id");
    }

    private List<String> employeesOf(String serviceId) {
        return JsonPath.read(owner.get("/services/" + serviceId).getBody(), "$.employeeIds");
    }

    private List<String> servicesOf(String employeeId) {
        return JsonPath.read(owner.get("/employees/" + employeeId).getBody(), "$.serviceIds");
    }

    @Test
    @DisplayName("assigning employees to a service reads back from both ends")
    void assigns_from_the_service_end() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino, dato)));

        assertThat(employeesOf(haircut)).containsExactlyInAnyOrder(nino, dato);
        assertThat(servicesOf(nino)).containsExactly(haircut);
        assertThat(servicesOf(dato)).containsExactly(haircut);
    }

    @Test
    @DisplayName("assigning services to an employee reads back from both ends")
    void assigns_from_the_employee_end() {
        owner.put("/employees/" + nino + "/services", Map.of("serviceIds", List.of(haircut, colour)));

        assertThat(servicesOf(nino)).containsExactlyInAnyOrder(haircut, colour);
        assertThat(employeesOf(haircut)).containsExactly(nino);
        assertThat(employeesOf(colour)).containsExactly(nino);
    }

    @Test
    @DisplayName("replacing is idempotent — the same set twice leaves the same rows")
    void is_idempotent() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino, dato)));
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino, dato)));

        // Without the flush between the delete and the inserts this is where the primary key
        // collides with rows that are about to be removed.
        assertThat(employeesOf(haircut)).containsExactlyInAnyOrder(nino, dato);
    }

    @Test
    @DisplayName("a replace removes the rows that are no longer in the set")
    void removes_stale_rows() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino, dato)));

        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(dato)));

        assertThat(employeesOf(haircut)).containsExactly(dato);
        assertThat(servicesOf(nino)).isEmpty();
    }

    @Test
    @DisplayName("an empty set clears the assignments")
    void clears_with_an_empty_set() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino)));

        // Legitimate, and not the same as omitting the field: nobody performs this service yet.
        assertThat(owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(employeesOf(haircut)).isEmpty();
    }

    @Test
    @DisplayName("omitting the list is a malformed request, unlike sending an empty one")
    void refuses_an_absent_list() {
        assertThat(owner.put("/services/" + haircut + "/employees", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a repeated id in the submitted set is tolerated, not refused")
    void tolerates_a_repeated_id() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino, nino)));

        assertThat(employeesOf(haircut)).containsExactly(nino);
    }

    @Test
    @DisplayName("one employee's set is independent of another's")
    void keeps_sets_independent() {
        owner.put("/employees/" + nino + "/services", Map.of("serviceIds", List.of(haircut, colour)));
        owner.put("/employees/" + dato + "/services", Map.of("serviceIds", List.of(colour)));

        assertThat(servicesOf(nino)).containsExactlyInAnyOrder(haircut, colour);
        assertThat(employeesOf(colour)).containsExactlyInAnyOrder(nino, dato);
        assertThat(employeesOf(haircut)).containsExactly(nino);
    }

    @Test
    @DisplayName("an id that does not exist is a 404, and the set is left untouched")
    void refuses_an_unknown_id() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino)));

        // Dropping the unknown id would let an owner believe they had assigned someone they had not,
        // and the symptom would arrive much later as a service nobody can book.
        assertThat(owner.put(
                                "/services/" + haircut + "/employees",
                                Map.of("employeeIds", List.of(dato, UUID.randomUUID().toString())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(employeesOf(haircut)).containsExactly(nino);
    }

    @Test
    @DisplayName("deleting a service takes its assignments with it")
    void cascades_on_service_delete() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino)));

        owner.delete("/services/" + haircut);

        assertThat(servicesOf(nino)).isEmpty();
    }

    @Test
    @DisplayName("the list endpoints carry the assignments, so a screen needs no request per row")
    void lists_carry_assignments() {
        owner.put("/services/" + haircut + "/employees", Map.of("employeeIds", List.of(nino)));

        String services = owner.get("/services").getBody();
        assertThat(JsonPath.<List<List<String>>>read(services, "$.services[?(@.name == 'Haircut')].employeeIds"))
                .containsExactly(List.of(nino));

        String employees = owner.get("/employees").getBody();
        assertThat(JsonPath.<List<List<String>>>read(
                        employees, "$.employees[?(@.fullName == 'Nino Beridze')].serviceIds"))
                .containsExactly(List.of(haircut));
    }
}
