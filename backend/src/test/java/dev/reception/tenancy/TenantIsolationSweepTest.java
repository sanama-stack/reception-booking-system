package dev.reception.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Every endpoint that takes an id, handed an id belonging to somebody else.
 *
 * <p>The per-module isolation tests already probe most of these by hand, and they are the better
 * place to assert what a particular endpoint does with a borrowed id — that nothing was written,
 * that the row is untouched, that a second read still sees the old name. This sweep asserts
 * something they structurally cannot: <strong>that the list is complete</strong>. It is driven by
 * {@link EndpointCatalogue}, which {@link EndpointCoverageTest} holds against Spring's own routing
 * table, so an endpoint added in a later phase is probed here whether or not anyone remembers to
 * write a test for it.
 *
 * <p><strong>Every probe is preceded by its own control.</strong> The same request is first sent
 * with the caller's own id, and must come back {@code 2xx} or {@code 409}. Without that, a probe is
 * worth nothing: a typo in the path, a body the validator rejects before the row is ever looked up,
 * or a method the endpoint does not support all produce a status that is not {@code 200}, and a test
 * asserting only "not 200" would call that isolation. A control that resolved a real row is what
 * makes the {@code 404} that follows mean "the row exists and you cannot see it".
 *
 * <p><strong>404, never 403.</strong> A status that varies with existence enumerates what it
 * protects (docs/06-security.md §3): {@code 403} on a borrowed id tells the caller the id is real.
 */
class TenantIsolationSweepTest extends IntegrationTest {

    /**
     * A start time for the control bookings, one hour apart so none of them collide.
     *
     * <p>Its own counter, separate from {@link #unique}. Sharing one was a real bug here: the
     * service names and the closure dates drew from the same sequence, pushed the hour past the
     * 17:00 close, and six probes failed at their control call for a reason that had nothing to do
     * with tenancy.
     */
    private final AtomicInteger hour = new AtomicInteger(9);

    /** Distinguishes rows that must not collide — Service names, closure and time-off dates. */
    private final AtomicInteger unique = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    /** Salon Aria — the caller doing the probing, and the owner of every control id. */
    private BookingScenario aria;

    private String ariaSlug;

    /** Datos Auto — the tenant whose ids are borrowed. */
    private AuthTestClient auto;

    private String autoSlug;
    private final Map<EndpointCatalogue.Borrowed, String> borrowed = new HashMap<>();
    private String autoTimeOff;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        aria = BookingScenario.open(rest, port, clock);
        ariaSlug = JsonPath.read(aria.owner.get("/business").getBody(), "$.slug");

        auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", BookingScenario.PASSWORD, "Datos Auto");
        auto.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        autoSlug = JsonPath.read(auto.get("/business").getBody(), "$.slug");

        String service = BookingScenario.createService(auto, "Oil change", 60, "40.00", 0, 0);
        String employee = BookingScenario.createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + employee + "/services", Map.of("serviceIds", List.of(service)));
        BookingScenario.setSchedule(auto, employee, "09:00", "17:00");

        Map<String, Object> booking = new HashMap<>();
        booking.put("serviceId", service);
        booking.put("employeeId", employee);
        booking.put("startsAt", aria.at(aria.monday, 11, 0).toString());
        booking.put("customerName", "Levan Gogia");
        booking.put("customerPhone", "+995555777888");
        String booked = require(auto.post("/appointments", booking), "book for Datos Auto");

        autoTimeOff = JsonPath.read(
                require(
                        auto.post(
                                "/employees/" + employee + "/time-off",
                                Map.of("startDate", "2027-03-01", "endDate", "2027-03-02")),
                        "give Datos Auto time off"),
                "$.id");

        borrowed.put(EndpointCatalogue.Borrowed.SERVICE, service);
        borrowed.put(EndpointCatalogue.Borrowed.EMPLOYEE, employee);
        borrowed.put(EndpointCatalogue.Borrowed.EMPLOYEE_AND_TIME_OFF, employee);
        borrowed.put(EndpointCatalogue.Borrowed.APPOINTMENT, JsonPath.read(booked, "$.appointment.id"));
        borrowed.put(EndpointCatalogue.Borrowed.CUSTOMER, JsonPath.read(booked, "$.appointment.customer.id"));
        borrowed.put(
                EndpointCatalogue.Borrowed.CLOSURE,
                JsonPath.read(
                        require(
                                auto.post(
                                        "/business/closures",
                                        Map.of("startDate", "2027-04-01", "endDate", "2027-04-02")),
                                "close Datos Auto"),
                        "$.closure.id"));
        borrowed.put(
                EndpointCatalogue.Borrowed.FAQ,
                JsonPath.read(
                        require(
                                auto.post(
                                        "/business/faqs",
                                        Map.of("question", "Do you take cards?", "answer", "We do.")),
                                "add a FAQ to Datos Auto"),
                        "$.id"));
        borrowed.put(EndpointCatalogue.Borrowed.CONVERSATION, startConversation(autoSlug));
    }

    /**
     * One dynamic test per catalogued endpoint that takes an id — named by the endpoint, so a
     * failure names the route rather than an index into a list.
     */
    @TestFactory
    Stream<DynamicTest> every_borrowed_id_comes_back_404() {
        return EndpointCatalogue.ENDPOINTS.entrySet().stream()
                .filter(entry -> entry.getValue().isolation() == EndpointCatalogue.Isolation.OWNER_RESOURCE_ID)
                .map(entry -> DynamicTest.dynamicTest(
                        entry.getKey() + " — another tenant's id does not exist",
                        () -> probe(entry.getKey(), entry.getValue().borrowed())));
    }

    private void probe(String endpoint, EndpointCatalogue.Borrowed kind) {
        String method = endpoint.substring(0, endpoint.indexOf(' '));
        String pattern = endpoint.substring(endpoint.indexOf(' ') + 1);

        ResponseEntity<String> control = send(method, fill(pattern, ownControlIds(kind)), bodyFor(endpoint));
        assertThat(control.getStatusCode().value())
                .as(
                        """
                        The control for %s failed: sent with the caller's OWN id it should have \
                        resolved a real row. Until it does, the 404 this test is about to assert \
                        proves nothing — a bad path answers 404 and a rejected body answers 422, \
                        whether or not the tenant filter exists. Body was: %s"""
                                .formatted(endpoint, control.getBody()))
                .satisfiesAnyOf(
                        status -> assertThat(status).isBetween(200, 299),
                        status -> assertThat(status).isEqualTo(HttpStatus.CONFLICT.value()));

        ResponseEntity<String> probe = send(method, fill(pattern, borrowedIds(kind)), bodyFor(endpoint));
        assertThat(probe.getStatusCode())
                .as("%s with another tenant's id. 403 would confirm the row exists; 200 would hand it over", endpoint)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -----------------------------------------------------------------------
    // Ids
    // -----------------------------------------------------------------------

    /**
     * Fresh rows of the caller's own, one set per probe.
     *
     * <p>Fresh rather than shared because half of these endpoints are destructive: the control for
     * {@code DELETE /services/{id}} really does delete a Service, and a shared fixture would leave
     * every later control reading a row that the earlier one removed — which would look exactly
     * like a tenancy failure and would not be one.
     */
    private Map<String, String> ownControlIds(EndpointCatalogue.Borrowed kind) {
        return switch (kind) {
            case SERVICE -> Map.of("id", freshAriaService());
            case EMPLOYEE -> Map.of("id", freshAriaEmployee());
            case EMPLOYEE_AND_TIME_OFF -> {
                String employee = freshAriaEmployee();
                yield Map.of("id", employee, "offId", ariaTimeOff(employee));
            }
            case APPOINTMENT, CUSTOMER -> {
                String booked = freshAriaBooking();
                yield Map.of(
                        "id",
                        JsonPath.read(
                                booked,
                                kind == EndpointCatalogue.Borrowed.APPOINTMENT
                                        ? "$.appointment.id"
                                        : "$.appointment.customer.id"));
            }
            case CONVERSATION -> Map.of("id", startConversation(ariaSlug));
            case CLOSURE -> Map.of("id", freshAriaClosure());
            case FAQ -> Map.of("id", freshAriaFaq());
            case NONE -> throw new IllegalStateException("an id probe was catalogued as borrowing nothing");
        };
    }

    private Map<String, String> borrowedIds(EndpointCatalogue.Borrowed kind) {
        return kind == EndpointCatalogue.Borrowed.EMPLOYEE_AND_TIME_OFF
                ? Map.of("id", borrowed.get(kind), "offId", autoTimeOff)
                : Map.of("id", borrowed.get(kind));
    }

    /**
     * A Service name is unique within a Business, so every control needs its own.
     *
     * <p>Found the hard way: five probes failed at once with {@code PathNotFoundException} on
     * {@code $.id}, which reads like a response-shape mistake and was a second service called
     * "Control" being refused.
     */
    private String freshAriaService() {
        return JsonPath.read(
                require(
                        aria.owner.post(
                                "/services",
                                Map.of(
                                        "name",
                                        "Control service " + unique.getAndIncrement(),
                                        "durationMinutes",
                                        30,
                                        "price",
                                        "10.00")),
                        "create a control service"),
                "$.id");
    }

    private String freshAriaEmployee() {
        String employee = BookingScenario.createEmployee(aria.owner, "Control Employee");
        BookingScenario.setSchedule(aria.owner, employee, "09:00", "17:00");
        return employee;
    }

    private String ariaTimeOff(String employee) {
        LocalDate start = LocalDate.of(2027, 6, 1).plusDays(unique.getAndIncrement() * 3L);
        return JsonPath.read(
                require(
                        aria.owner.post(
                                "/employees/" + employee + "/time-off",
                                Map.of("startDate", start.toString(), "endDate", start.plusDays(1).toString())),
                        "give the control employee time off"),
                "$.id");
    }

    private String freshAriaBooking() {
        return require(aria.book(aria.at(aria.monday, hour.getAndIncrement(), 0)), "book a control appointment");
    }

    private String freshAriaClosure() {
        LocalDate start = LocalDate.of(2028, 1, 1).plusDays(unique.getAndIncrement() * 3L);
        return JsonPath.read(
                require(
                        aria.owner.post(
                                "/business/closures",
                                Map.of("startDate", start.toString(), "endDate", start.plusDays(1).toString())),
                        "close the control business"),
                "$.closure.id");
    }

    private String freshAriaFaq() {
        return JsonPath.read(
                require(
                        aria.owner.post(
                                "/business/faqs", Map.of("question", "Control question?", "answer", "Control answer.")),
                        "add a control FAQ"),
                "$.id");
    }

    /**
     * A conversation on a slug, opened by a stranger.
     *
     * <p>No model is called: opening a session writes the row and issues its token, and nothing
     * asks the Receptionist anything. A fresh client each time, because the conversation belongs to
     * whoever holds the session token and an owner's cookies have no part in it.
     */
    private String startConversation(String slug) {
        return JsonPath.read(
                require(
                        new AuthTestClient(rest, port)
                                .post("/public/businesses/" + slug + "/chat/session", Map.of()),
                        "open a conversation on " + slug),
                "$.conversationId");
    }

    // -----------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------

    /**
     * A body valid enough to reach the tenant lookup.
     *
     * <p>These are not arbitrary. A body the validator rejects never reaches the repository, so the
     * probe would assert a {@code 400} that says nothing about tenancy — which is exactly what the
     * control call exists to catch.
     */
    private Object bodyFor(String endpoint) {
        return switch (endpoint) {
            case "PATCH /services/{id}" -> Map.of("name", "Renamed by a stranger");
            case "PUT /services/{id}/employees" -> Map.of("employeeIds", List.of());
            case "PATCH /employees/{id}" -> Map.of("fullName", "Renamed by a stranger");
            case "PUT /employees/{id}/services" -> Map.of("serviceIds", List.of());
            case "PUT /employees/{id}/schedule" -> Map.of("schedule", List.of());
            case "POST /employees/{id}/time-off" -> Map.of("startDate", "2029-02-01", "endDate", "2029-02-02");
            case "PATCH /customers/{id}" -> Map.of("fullName", "Renamed by a stranger");
            case "POST /appointments/{id}/cancel" -> Map.of();
            case "POST /appointments/{id}/reschedule" -> Map.of(
                    "startsAt", aria.at(aria.monday.plusDays(1), 9, 0).toString());
            case "POST /appointments/{id}/status" -> Map.of("status", "COMPLETED");
            case "PATCH /business/faqs/{id}" -> Map.of("answer", "Rewritten by a stranger.");
            default -> null;
        };
    }

    private ResponseEntity<String> send(String method, String path, Object body) {
        return switch (method) {
            case "GET" -> aria.owner.get(path);
            case "POST" -> aria.owner.post(path, body == null ? Map.of() : body);
            case "PUT" -> aria.owner.put(path, body);
            case "PATCH" -> aria.owner.patch(path, body);
            case "DELETE" -> aria.owner.delete(path);
            default -> throw new IllegalStateException("no way to send " + method);
        };
    }

    private static String fill(String pattern, Map<String, String> ids) {
        String path = pattern;
        for (Map.Entry<String, String> id : ids.entrySet()) {
            path = path.replace("{" + id.getKey() + "}", id.getValue());
        }
        if (path.contains("{")) {
            throw new IllegalStateException("no id was supplied for a variable in " + pattern);
        }
        return path;
    }

    /** Fails loudly at the fixture rather than quietly at the assertion it was meant to support. */
    private static String require(ResponseEntity<String> response, String what) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "The fixture could not " + what + ": " + (response == null ? "no response" : response.getBody()));
        }
        return response.getBody();
    }
}
