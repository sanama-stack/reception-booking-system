package dev.reception.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
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
 * The isolation probes for {@code /public/*} (docs/09-phase-plan.md §5, rule 5).
 *
 * <p>The public surface resolves its tenant from a slug rather than from a session, which is a
 * second door into the same building — so it earns its own probes rather than inheriting the
 * dashboard's. The question each one asks is the same: <em>can a caller reach one business's data
 * through another business's address?</em>
 *
 * <p><strong>Every id borrowed here is real.</strong> Probing with a random UUID would pass against
 * an implementation with no tenant filter at all, which is the implementation this exists to catch.
 */
class PublicIsolationTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    /** Salon Aria — the page a stranger is browsing. */
    private BookingScenario aria;

    private String ariaSlug;

    /** Datos Auto — the tenant whose ids are being borrowed. */
    private AuthTestClient auto;

    private String autoSlug;
    private String autoService;
    private String autoEmployee;

    private PublicTestClient stranger;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
        ariaSlug = JsonPath.read(aria.owner.get("/business").getBody(), "$.slug");

        auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", BookingScenario.PASSWORD, "Datos Auto");
        auto.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        autoSlug = JsonPath.read(auto.get("/business").getBody(), "$.slug");
        autoService = BookingScenario.createService(auto, "Oil change", 60, "40.00", 0, 0);
        autoEmployee = BookingScenario.createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + autoEmployee + "/services", Map.of("serviceIds", List.of(autoService)));
        BookingScenario.setSchedule(auto, autoEmployee, "09:00", "17:00");

        stranger = new PublicTestClient(rest, port);
    }

    @Test
    @DisplayName("one page never lists another business's services or staff")
    void each_page_shows_only_its_own_catalog() {
        assertThat(JsonPath.<List<String>>read(
                        stranger.get("/public/businesses/" + ariaSlug + "/services").getBody(), "$[*].name"))
                .containsExactly("Haircut");
        assertThat(JsonPath.<List<String>>read(
                        stranger.get("/public/businesses/" + autoSlug + "/services").getBody(), "$[*].name"))
                .containsExactly("Oil change");

        assertThat(JsonPath.<List<String>>read(
                        stranger.get("/public/businesses/" + ariaSlug + "/employees").getBody(), "$[*].fullName"))
                .containsExactly("Nino Beridze");
        assertThat(JsonPath.<List<String>>read(
                        stranger.get("/public/businesses/" + autoSlug + "/employees").getBody(), "$[*].fullName"))
                .containsExactly("Dato Kapanadze");
    }

    @Test
    @DisplayName("another business's service id on this page is a 404, not their service")
    void a_borrowed_service_id_is_not_found() {
        assertThat(stranger
                        .get("/public/businesses/" + ariaSlug + "/employees?serviceId=" + autoService)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(stranger
                        .get("/public/businesses/%s/availability?serviceId=%s&from=%s&to=%s"
                                .formatted(ariaSlug, autoService, aria.monday, aria.monday))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a booking cannot be assembled from two businesses' ids")
    void a_booking_cannot_mix_tenants() {
        // Aria's slug, Aria's service, Datos Auto's employee. The tenant comes from the slug, so
        // the employee simply is not there — and it is a 404 rather than a leak.
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", aria.serviceId);
        body.put("employeeId", autoEmployee);
        body.put("startsAt", aria.at(aria.monday, 10, 0).toString());
        body.put("customer", Map.of("fullName", "Ana", "phone", BookingScenario.CUSTOMER_PHONE));

        ResponseEntity<String> response =
                stranger.post("/public/businesses/" + ariaSlug + "/appointments", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"NOT_FOUND\"");
        // Nothing was written into either tenant.
        assertThat(JsonPath.<Integer>read(aria.owner.get("/appointments").getBody(), "$.totalElements"))
                .isZero();
        assertThat(JsonPath.<Integer>read(auto.get("/appointments").getBody(), "$.totalElements"))
                .isZero();
    }

    @Test
    @DisplayName("a confirmation code proves nothing against a phone number from another business")
    void a_code_is_not_transferable_between_tenants() {
        String ariaCode = bookOn(ariaSlug, aria.serviceId, aria.employeeId, "+995555111222");
        String autoCode = bookOn(autoSlug, autoService, autoEmployee, "+995555333444");

        // Each code with the other customer's number. Both proofs are individually real and neither
        // combination is.
        assertThat(lookup(ariaCode, "+995555333444").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(lookup(autoCode, "+995555111222").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // And each with its own, so the test is failing for the reason it claims.
        assertThat(lookup(ariaCode, "+995555111222").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup(autoCode, "+995555333444").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a lookup returns only the appointment it proved, never the business's others")
    void a_lookup_is_one_appointment_not_a_listing() {
        String code = bookOn(ariaSlug, aria.serviceId, aria.employeeId, "+995555111222");
        bookOn(ariaSlug, aria.serviceId, aria.employeeId, "+995555111222", 12);

        String body = lookup(code, "+995555111222").getBody();

        // An object, not an array: the proof authorises one appointment. Two bookings from the same
        // number is the case where "list everything this person has" would be tempting and wrong.
        assertThat(body).startsWith("{");
        assertThat(JsonPath.<String>read(body, "$.confirmationCode")).isEqualTo(code);
    }

    private String bookOn(String slug, String serviceId, String employeeId, String phone) {
        return bookOn(slug, serviceId, employeeId, phone, 10);
    }

    private String bookOn(String slug, String serviceId, String employeeId, String phone, int hour) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", serviceId);
        body.put("employeeId", employeeId);
        body.put("startsAt", aria.at(aria.monday, hour, 0).toString());
        body.put("customer", Map.of("fullName", "Ana Tsereteli", "phone", phone));

        ResponseEntity<String> response = stranger.post("/public/businesses/" + slug + "/appointments", body);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Fixture could not book: " + response.getBody());
        }
        return JsonPath.read(response.getBody(), "$.confirmationCode");
    }

    private ResponseEntity<String> lookup(String code, String phone) {
        return stranger.post("/public/appointments/lookup", Map.of("confirmationCode", code, "phone", phone));
    }
}
