package dev.reception.staff;

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
import org.springframework.http.ResponseEntity;

/** {@code /employees} — the people a Business can book work onto. */
class EmployeeEndpointTest extends IntegrationTest {

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

    private ResponseEntity<String> create(String fullName) {
        return owner.post("/employees", Map.of("fullName", fullName));
    }

    private String createId(String fullName) {
        return JsonPath.read(create(fullName).getBody(), "$.id");
    }

    @Test
    @DisplayName("a created employee reads back, active and assigned to nothing")
    void creates_and_reads_back() {
        ResponseEntity<String> created = create("Nino Beridze");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = created.getBody();
        assertThat((String) JsonPath.read(body, "$.fullName")).isEqualTo("Nino Beridze");
        assertThat((boolean) JsonPath.read(body, "$.active")).isTrue();
        assertThat(JsonPath.<List<String>>read(body, "$.serviceIds")).isEmpty();
    }

    @Test
    @DisplayName("optional details are stored when given")
    void stores_optional_details() {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("email", "nino@aria.test");
        body.put("jobTitle", "Senior stylist");

        String created = owner.post("/employees", body).getBody();
        assertThat((String) JsonPath.read(created, "$.email")).isEqualTo("nino@aria.test");
        assertThat((String) JsonPath.read(created, "$.jobTitle")).isEqualTo("Senior stylist");
    }

    @Test
    @DisplayName("the list comes back in name order")
    void lists_in_name_order() {
        create("Nino Beridze");
        create("Dato Kapanadze");

        assertThat(JsonPath.<List<String>>read(owner.get("/employees").getBody(), "$.employees[*].fullName"))
                .containsExactly("Dato Kapanadze", "Nino Beridze");
    }

    // -----------------------------------------------------------------------
    // Phone normalisation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a local number is normalised to E.164 using the business's country")
    void normalises_a_local_number() {
        owner.patch("/business", Map.of("country", "GE"));

        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("phone", "555 12 34 56");

        assertThat((String) JsonPath.read(owner.post("/employees", body).getBody(), "$.phone"))
                .isEqualTo("+995555123456");
    }

    @Test
    @DisplayName("an unparseable number is refused at entry rather than stored as typed")
    void refuses_an_unparseable_number() {
        owner.patch("/business", Map.of("country", "GE"));

        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("phone", "12");

        ResponseEntity<String> response = owner.post("/employees", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field"))
                .containsExactly("phone");
    }

    @Test
    @DisplayName("with no country set, a local number is refused and the message says why")
    void explains_a_missing_country() {
        // A new business has no country. "That is not a valid number" would send the owner looking
        // at the number; the fix is in Settings.
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("phone", "555123456");

        String response = owner.post("/employees", body).getBody();
        assertThat((String) JsonPath.read(response, "$.errors[0].message")).contains("country");
    }

    @Test
    @DisplayName("with no country set, an international number is still accepted")
    void accepts_international_form_without_a_country() {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("phone", "+995 555 12 34 56");

        assertThat((String) JsonPath.read(owner.post("/employees", body).getBody(), "$.phone"))
                .isEqualTo("+995555123456");
    }

    @Test
    @DisplayName("a blank phone clears the field rather than being read as a bad number")
    void clears_the_phone_with_a_blank() {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("phone", "+995555123456");
        String id = JsonPath.read(owner.post("/employees", body).getBody(), "$.id");

        ResponseEntity<String> patched = owner.patch("/employees/" + id, Map.of("phone", ""));

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(patched.getBody(), "$.phone")).isNull();
    }

    // -----------------------------------------------------------------------
    // Patch
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a patch naming one field leaves the others alone")
    void patches_partially() {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("jobTitle", "Stylist");
        String id = JsonPath.read(owner.post("/employees", body).getBody(), "$.id");

        owner.patch("/employees/" + id, Map.of("jobTitle", "Senior stylist"));

        String read = owner.get("/employees/" + id).getBody();
        assertThat((String) JsonPath.read(read, "$.fullName")).isEqualTo("Nino Beridze");
        assertThat((String) JsonPath.read(read, "$.jobTitle")).isEqualTo("Senior stylist");
    }

    @Test
    @DisplayName("a blank name is refused — an employee with no name is not a record worth keeping")
    void refuses_a_blank_name() {
        String id = createId("Nino Beridze");

        assertThat(owner.patch("/employees/" + id, Map.of("fullName", " ")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a malformed email is refused")
    void refuses_a_malformed_email() {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Nino Beridze");
        body.put("email", "not-an-address");

        assertThat(owner.post("/employees", body).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // -----------------------------------------------------------------------
    // Activation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("deactivating hides an employee from the active listing but not from the management one")
    void deactivating_hides_them_from_the_active_listing() {
        String id = createId("Nino Beridze");
        create("Dato Kapanadze");

        owner.post("/employees/" + id + "/deactivate", Map.of());

        assertThat(JsonPath.<List<String>>read(owner.get("/employees?active=true").getBody(), "$.employees[*].fullName"))
                .containsExactly("Dato Kapanadze");
        assertThat(JsonPath.<List<String>>read(owner.get("/employees").getBody(), "$.employees[*].fullName"))
                .containsExactly("Dato Kapanadze", "Nino Beridze");
    }

    @Test
    @DisplayName("a deactivation reports how many upcoming appointments it affects, and cancels none")
    void reports_the_deactivation_impact() {
        String id = createId("Nino Beridze");

        String body = owner.post("/employees/" + id + "/deactivate", Map.of()).getBody();

        assertThat((boolean) JsonPath.read(body, "$.employee.active")).isFalse();
        // Zero until phase 06 — no appointment can yet exist. The field is published now so nothing
        // about the response shape changes when it stops being zero.
        assertThat(((Number) JsonPath.read(body, "$.affectedFutureAppointments")).longValue())
                .isZero();
    }

    @Test
    @DisplayName("a deactivated employee keeps their assignments")
    void keeps_assignments_through_a_deactivation() {
        String employee = createId("Nino Beridze");
        Map<String, Object> service = new HashMap<>();
        service.put("name", "Haircut");
        service.put("durationMinutes", 45);
        service.put("price", "60.00");
        String serviceId = JsonPath.read(owner.post("/services", service).getBody(), "$.id");
        owner.put("/employees/" + employee + "/services", Map.of("serviceIds", List.of(serviceId)));

        owner.post("/employees/" + employee + "/deactivate", Map.of());

        // Deactivation stops new bookings; it is not a way of unconfiguring someone. Re-activating
        // must not require rebuilding what they can do.
        assertThat(JsonPath.<List<String>>read(owner.get("/employees/" + employee).getBody(), "$.serviceIds"))
                .containsExactly(serviceId);
    }

    @Test
    @DisplayName("reading or patching an employee that does not exist is a 404")
    void unknown_employees_are_not_found() {
        String missing = UUID.randomUUID().toString();

        assertThat(owner.get("/employees/" + missing).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.patch("/employees/" + missing, Map.of("fullName", "X")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.post("/employees/" + missing + "/deactivate", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
