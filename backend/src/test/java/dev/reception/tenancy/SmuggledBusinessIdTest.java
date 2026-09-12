package dev.reception.tenancy;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * No endpoint honours a {@code business_id} the caller supplies.
 *
 * <p>The tenant comes from the Membership, the slug or the conversation record, and never from the
 * request (docs/04-api-overview.md §1). {@code TenantRepositoryShapeTest} already enforces the
 * compile-time half — no controller binds a parameter named for the business, and no DTO under
 * {@code ..web..} declares a {@code businessId} field — which means a request cannot smuggle one in
 * through a path, a query or a body <em>by being bound to it</em>.
 *
 * <p>This is the runtime half, and it exists because that rule governs only what is <em>declared</em>.
 * A {@code @RequestParam Map&lt;String, String&gt;}, a catch-all filter, a
 * {@code @JsonAnySetter}, or a repository method reached through some other name could all honour a
 * parameter nobody declared. So every readable endpoint is asked twice — once plainly, once with
 * another Business's id appended — and the two answers must be <strong>byte-identical</strong>.
 *
 * <p>Byte-identical rather than "does not contain Datos Auto": an endpoint that reacted to the
 * parameter by returning less, or by erroring, or by reordering, would pass a containment check and
 * would still be reading the caller's request for a tenant.
 *
 * <p>Spring Boot leaves {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so a {@code businessId} in a request
 * body is silently dropped rather than rejected. Silently dropped is the right outcome and the
 * dangerous-looking one, which is why {@link #a_smuggled_body_field_does_not_move_a_write} checks
 * where the row actually landed rather than trusting the status code.
 */
class SmuggledBusinessIdTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    /** Salon Aria — the caller, and the only tenant whose data may ever come back. */
    private BookingScenario aria;

    private String ariaBusinessId;
    private String ariaAppointment;
    private String ariaCustomer;
    private String ariaConversation;
    private String ariaClosure;
    private String ariaFaq;

    /** Datos Auto — the Business whose id is being offered to every endpoint. */
    private String autoBusinessId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        aria = BookingScenario.open(rest, port, clock);
        ariaBusinessId = JsonPath.read(aria.owner.get("/business").getBody(), "$.id");

        String booked = aria.book(aria.at(aria.monday, 10, 0)).getBody();
        ariaAppointment = JsonPath.read(booked, "$.appointment.id");
        ariaCustomer = JsonPath.read(booked, "$.appointment.customer.id");
        ariaClosure = JsonPath.read(
                aria.owner
                        .post("/business/closures", Map.of("startDate", "2027-04-01", "endDate", "2027-04-02"))
                        .getBody(),
                "$.closure.id");
        ariaFaq = JsonPath.read(
                aria.owner
                        .post("/business/faqs", Map.of("question", "Parking?", "answer", "On the street."))
                        .getBody(),
                "$.id");
        ariaConversation = JsonPath.read(
                new AuthTestClient(rest, port)
                        .post(
                                "/public/businesses/"
                                        + JsonPath.read(aria.owner.get("/business").getBody(), "$.slug")
                                        + "/chat/session",
                                Map.of())
                        .getBody(),
                "$.conversationId");

        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", BookingScenario.PASSWORD, "Datos Auto");
        autoBusinessId = JsonPath.read(auto.get("/business").getBody(), "$.id");

        // Datos Auto is furnished so that honouring the smuggled id would visibly change an answer.
        // Against an empty Business, an endpoint that obeyed the parameter would return an empty
        // list — and a test comparing two responses would see a difference, but a test looking for
        // the word "Datos" would not.
        String service = BookingScenario.createService(auto, "Oil change", 60, "40.00", 0, 0);
        String employee = BookingScenario.createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + employee + "/services", Map.of("serviceIds", List.of(service)));
        BookingScenario.setSchedule(auto, employee, "09:00", "17:00");
        auto.post("/business/faqs", Map.of("question", "Cards?", "answer", "We take them."));
    }

    /**
     * Every readable endpoint, asked plainly and then asked again with the parameter appended.
     *
     * <p>Driven by {@link EndpointCatalogue} rather than by a list written here, so an endpoint
     * added in a later phase is swept whether or not anyone remembers this file exists.
     */
    @TestFactory
    Stream<DynamicTest> no_read_honours_a_business_id_in_the_query() {
        return readableEndpoints()
                .map(endpoint -> DynamicTest.dynamicTest(
                        endpoint + " — ?businessId= changes nothing", () -> compare(endpoint)));
    }

    private void compare(String path) {
        ResponseEntity<String> plain = aria.owner.get(path);
        assertThat(plain.getStatusCode().is2xxSuccessful())
                .as(
                        """
                        The control for GET %s failed with %s. Until the plain request succeeds, \
                        comparing it with the smuggled one compares two errors. Body was: %s"""
                                .formatted(path, plain.getStatusCode(), plain.getBody()))
                .isTrue();

        String separator = path.contains("?") ? "&" : "?";
        ResponseEntity<String> smuggled = aria.owner.get(path + separator + "businessId=" + autoBusinessId);

        assertThat(smuggled.getStatusCode())
                .as("GET %s answered differently once another tenant's id was appended", path)
                .isEqualTo(plain.getStatusCode());
        assertThat(smuggled.getBody())
                .as(
                        """
                        GET %s returned a different body once another tenant's id was appended, \
                        which means something in the request path read it. The tenant comes from \
                        the Membership and from nowhere else (docs/04-api-overview.md §1)."""
                                .formatted(path))
                .isEqualTo(plain.getBody());
    }

    /**
     * The catalogued {@code GET}s an owner can call, with real ids and with the parameters each one
     * requires.
     *
     * <p>Derived from the catalogue and then filled in here, which is the only part that has to be
     * maintained by hand: a range endpoint needs a range, and reflection cannot invent one. An
     * endpoint the catalogue knows about and this method cannot fill is a failure rather than a
     * silent skip — see {@link #every_readable_endpoint_is_swept}.
     */
    private Stream<String> readableEndpoints() {
        return EndpointCatalogue.ENDPOINTS.keySet().stream()
                .filter(endpoint -> endpoint.startsWith("GET "))
                .map(endpoint -> endpoint.substring("GET ".length()))
                .filter(pattern -> !pattern.startsWith("/public/"))
                .filter(pattern -> !pattern.equals("/health"))
                .map(this::fill);
    }

    private String fill(String pattern) {
        String range = "from=" + aria.monday + "&to=" + aria.monday.plusDays(6);
        return switch (pattern) {
            case "/calendar", "/analytics/summary" -> pattern + "?" + range;
            case "/availability" -> pattern + "?serviceId=" + aria.serviceId + "&" + range;
            default -> pattern.replace("{id}", idFor(pattern));
        };
    }

    private String idFor(String pattern) {
        if (pattern.startsWith("/services")) {
            return aria.serviceId;
        }
        if (pattern.startsWith("/employees")) {
            return aria.employeeId;
        }
        if (pattern.startsWith("/appointments")) {
            return ariaAppointment;
        }
        if (pattern.startsWith("/customers")) {
            return ariaCustomer;
        }
        if (pattern.startsWith("/conversations")) {
            return ariaConversation;
        }
        if (pattern.startsWith("/business/closures")) {
            return ariaClosure;
        }
        if (pattern.startsWith("/business/faqs")) {
            return ariaFaq;
        }
        return "";
    }

    /**
     * The sweep's own completeness check.
     *
     * <p>{@link #fill} is the one hand-maintained step in this file, and the failure mode it invites
     * is a new endpoint quietly producing a path with an unfilled {@code {id}} in it — which would
     * 404, fail the control, and be read as a bug in the endpoint rather than a hole in the sweep.
     */
    @Test
    @DisplayName("every readable endpoint the catalogue knows about is actually swept")
    void every_readable_endpoint_is_swept() {
        assertThat(readableEndpoints())
                .as("a catalogued GET that fill() could not supply an id for")
                .isNotEmpty()
                .allSatisfy(path -> assertThat(path).doesNotContain("{"));
    }

    /**
     * The body half: a smuggled field is dropped, and the row lands where the Membership says.
     *
     * <p>Asserted in SQL rather than from the response, because a response that names no Business is
     * exactly what both the right answer and the wrong one look like.
     */
    @Test
    @DisplayName("a businessId in a request body does not move the row it creates")
    void a_smuggled_body_field_does_not_move_a_write() {
        Map<String, Object> service = new HashMap<>();
        service.put("name", "Smuggled service");
        service.put("durationMinutes", 30);
        service.put("price", "10.00");
        service.put("businessId", autoBusinessId);

        ResponseEntity<String> created = aria.owner.post("/services", service);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("the smuggled field should be ignored, not fatal: %s", created.getBody())
                .isTrue();

        assertThat(jdbc.queryForObject(
                        "select business_id from services where id = ?::uuid",
                        String.class,
                        JsonPath.<String>read(created.getBody(), "$.id")))
                .as("the Service was created under the Business named in the body, not the caller's")
                .isEqualTo(ariaBusinessId)
                .isNotEqualTo(autoBusinessId);

        Map<String, Object> employee = new HashMap<>();
        employee.put("fullName", "Smuggled employee");
        employee.put("businessId", autoBusinessId);

        ResponseEntity<String> hired = aria.owner.post("/employees", employee);
        assertThat(jdbc.queryForObject(
                        "select business_id from employees where id = ?::uuid",
                        String.class,
                        JsonPath.<String>read(hired.getBody(), "$.id")))
                .isEqualTo(ariaBusinessId);
    }
}
