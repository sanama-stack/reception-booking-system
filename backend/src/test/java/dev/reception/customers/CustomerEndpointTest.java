package dev.reception.customers;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
import org.springframework.http.ResponseEntity;

/**
 * {@code /customers} — who has booked, and what they booked.
 *
 * <p>A Customer has no create endpoint: they come into existence by booking. Every test here
 * therefore starts by taking a booking, which is also the honest way to exercise the identity rule —
 * the same phone number twice must be one person, and the search has to find them by any of the
 * three things a receptionist would actually type.
 */
class CustomerEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    private AuthTestClient owner;
    private String serviceId;
    private String employeeId;
    private LocalDate monday;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");
        owner.patch("/business", Map.of("timezone", TBILISI.getId()));

        Map<String, Object> service = new HashMap<>();
        service.put("name", "Haircut");
        service.put("durationMinutes", 60);
        service.put("price", "60.00");
        serviceId = JsonPath.read(owner.post("/services", service).getBody(), "$.id");

        employeeId = JsonPath.read(
                owner.post("/employees", Map.of("fullName", "Nino Beridze")).getBody(), "$.id");
        owner.put("/employees/" + employeeId + "/services", Map.of("serviceIds", List.of(serviceId)));
        owner.put(
                "/employees/" + employeeId + "/schedule",
                Map.of(
                        "schedule",
                        List.of(1, 2, 3, 4, 5).stream()
                                .map(day -> Map.<String, Object>of(
                                        "dayOfWeek", day, "startsAt", "09:00", "endsAt", "17:00"))
                                .toList()));

        monday = LocalDate.now(clock.withZone(TBILISI))
                .plusDays(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    @Test
    @DisplayName("a booking creates the customer, and a second booking reuses them")
    void the_phone_number_is_the_identity() {
        book(10, "Ana Tsereteli", "+995555123456");
        book(12, "Ana Tsereteli", "+995555123456");

        String body = owner.get("/customers").getBody();

        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.content[0].totalAppointments")).isEqualTo(2);
        assertThat(JsonPath.<String>read(body, "$.content[0].phone")).isEqualTo("+995555123456");
    }

    @Test
    @DisplayName("the same number written differently is still the same person")
    void normalisation_is_what_makes_the_identity_hold() {
        book(10, "Ana Tsereteli", "+995555123456");
        // Spaces and dashes are how a person actually types a number.
        book(12, "Ana Tsereteli", "+995 555 12-34-56");

        assertThat(JsonPath.<Integer>read(owner.get("/customers").getBody(), "$.totalElements"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("search matches on name, phone or email, case-insensitively")
    void search_covers_what_a_receptionist_would_type() {
        book(10, "Ana Tsereteli", "+995555123456");
        book(12, "Giorgi Beridze", "+995555987654");

        assertThat(matches("?q=ana")).containsExactly("Ana Tsereteli");
        assertThat(matches("?q=TSERETELI")).containsExactly("Ana Tsereteli");
        assertThat(matches("?q=987654")).containsExactly("Giorgi Beridze");
        // Blank is not a filter: an empty search box means "show me everyone".
        assertThat(matches("?q=")).containsExactly("Ana Tsereteli", "Giorgi Beridze");
        assertThat(matches("")).containsExactly("Ana Tsereteli", "Giorgi Beridze");
        assertThat(matches("?q=nobody")).isEmpty();
    }

    @Test
    @DisplayName("a name can be corrected, and the correction is not a new customer")
    void a_name_can_be_corrected() {
        book(10, "Ana Tsereteli", "+995555123456");
        String id = JsonPath.read(owner.get("/customers").getBody(), "$.content[0].id");

        ResponseEntity<String> response =
                owner.patch("/customers/" + id, Map.of("fullName", "Ana Tsereteli-Kapanadze"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.customer.fullName"))
                .isEqualTo("Ana Tsereteli-Kapanadze");
        assertThat(JsonPath.<Integer>read(owner.get("/customers").getBody(), "$.totalElements"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the phone number is not patchable, because it is half the identity")
    void the_phone_is_not_correctable() {
        book(10, "Ana Tsereteli", "+995555123456");
        String id = JsonPath.read(owner.get("/customers").getBody(), "$.content[0].id");

        // Sent anyway, the way a client that had not read the docs would send it.
        owner.patch("/customers/" + id, Map.of("fullName", "Ana", "phone", "+995555000000"));

        assertThat(JsonPath.<String>read(owner.get("/customers/" + id).getBody(), "$.customer.phone"))
                .isEqualTo("+995555123456");
    }

    @Test
    @DisplayName("a customer's history is their appointments, newest first")
    void the_history_reads_backwards() {
        book(10, "Ana Tsereteli", "+995555123456");
        book(14, "Ana Tsereteli", "+995555123456");
        String id = JsonPath.read(owner.get("/customers").getBody(), "$.content[0].id");

        String body = owner.get("/customers/" + id + "/appointments").getBody();

        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(2);
        // A profile is read as "what happened most recently", the opposite of the appointment list.
        List<String> starts = JsonPath.read(body, "$.content[*].startsAt");
        assertThat(starts.getFirst()).isGreaterThan(starts.getLast());
    }

    @Test
    @DisplayName("a customer with no appointments left is still a customer, with a count of zero")
    void cancelling_does_not_remove_the_person() {
        String id = JsonPath.read(book(10, "Ana Tsereteli", "+995555123456").getBody(), "$.appointment.customer.id");
        String appointment =
                JsonPath.read(owner.get("/customers/" + id + "/appointments").getBody(), "$.content[0].id");
        owner.post("/appointments/" + appointment + "/cancel", Map.of());

        // Cancelled, not deleted: the count is of everything that ever happened, in any status.
        assertThat(JsonPath.<Integer>read(owner.get("/customers/" + id).getBody(), "$.customer.totalAppointments"))
                .isEqualTo(1);
    }

    private ResponseEntity<String> book(int hour, String customerName, String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", serviceId);
        body.put("employeeId", employeeId);
        body.put("startsAt", monday.atTime(hour, 0).atZone(TBILISI).toOffsetDateTime().toString());
        body.put("customerName", customerName);
        body.put("customerPhone", phone);
        ResponseEntity<String> response = owner.post("/appointments", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private List<String> matches(String query) {
        return JsonPath.read(owner.get("/customers" + query).getBody(), "$.content[*].fullName");
    }
}
