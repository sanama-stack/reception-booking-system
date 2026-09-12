package dev.reception.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.ai.support.ScriptedChatModel;
import dev.reception.appointments.BookingScenario;
import dev.reception.notifications.ManageTokenService;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The public surface, swept — the half of the catalogue no session protects.
 *
 * <p>These twelve endpoints cannot be probed uniformly the way the owner-session ones can. There is
 * no borrowed id to substitute into a path for {@code GET /public/businesses/{slug}/services}, and
 * a Manage Link carries its own authority rather than a Membership's. So instead of one mechanical
 * probe, each endpoint gets a probe written for its own shape — and
 * {@link #every_public_endpoint_has_a_probe} holds that registry against
 * {@link EndpointCatalogue}, so a public endpoint added later fails the build until somebody writes
 * one. The guarantee is the same as the owner-session sweep's; only the uniformity is given up.
 *
 * <p>The two tenants here are deliberately alike — same service duration, same working week, both
 * bookable on the same Monday. A leak between businesses that differ in every field is caught by
 * almost any assertion; a leak between two that differ only in ownership is the one worth writing a
 * test for.
 */
class PublicSurfaceSweepTest extends IntegrationTest {

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

    @Autowired
    private ManageTokenService manageTokens;

    /** Autowired only to assert it was never called: a refusal must come before the provider. */
    @Autowired
    private ScriptedChatModel model;

    /** Salon Aria — whose public page every probe is aimed at. */
    private BookingScenario aria;

    private String ariaSlug;
    private String ariaBusinessId;
    private String ariaAppointment;
    private String ariaManageToken;

    /** Datos Auto — whose rows must never appear on Aria's page. */
    private AuthTestClient auto;

    private String autoSlug;
    private String autoService;
    private String autoEmployee;
    private String autoAppointment;
    private String autoCode;

    private static final String AUTO_CUSTOMER_PHONE = "+995555777888";

    /** A stranger: no cookies, no session, which is how the public surface is really reached. */
    private AuthTestClient stranger;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        model.reset();

        aria = BookingScenario.open(rest, port, clock);
        ariaSlug = JsonPath.read(aria.owner.get("/business").getBody(), "$.slug");
        ariaBusinessId = JsonPath.read(aria.owner.get("/business").getBody(), "$.id");
        ariaAppointment = aria.bookedAt(aria.at(aria.monday, 10, 0));
        ariaManageToken = manageTokens.issue(
                java.util.UUID.fromString(ariaAppointment),
                aria.at(aria.monday, 11, 0).toInstant());

        auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", BookingScenario.PASSWORD, "Datos Auto");
        auto.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        autoSlug = JsonPath.read(auto.get("/business").getBody(), "$.slug");
        autoService = BookingScenario.createService(auto, "Oil change", 60, "40.00", 0, 0);
        autoEmployee = BookingScenario.createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + autoEmployee + "/services", Map.of("serviceIds", List.of(autoService)));
        BookingScenario.setSchedule(auto, autoEmployee, "09:00", "17:00");

        Map<String, Object> booking = new HashMap<>();
        booking.put("serviceId", autoService);
        booking.put("employeeId", autoEmployee);
        booking.put("startsAt", aria.at(aria.monday, 10, 0).toString());
        booking.put("customerName", "Levan Gogia");
        booking.put("customerPhone", AUTO_CUSTOMER_PHONE);
        String booked = auto.post("/appointments", booking).getBody();
        autoAppointment = JsonPath.read(booked, "$.appointment.id");
        autoCode = JsonPath.read(booked, "$.appointment.confirmationCode");

        stranger = new AuthTestClient(rest, port);
    }

    @TestFactory
    Stream<DynamicTest> the_public_surface_keeps_the_tenants_apart() {
        return probes().entrySet().stream()
                .map(probe -> DynamicTest.dynamicTest(probe.getKey(), probe.getValue()));
    }

    /**
     * One probe per public endpoint, keyed exactly as the catalogue keys it.
     *
     * <p>Built after the fixtures rather than declared statically, because every one of them needs
     * a real id from a real row — the point made in every isolation test in this suite: a probe with
     * an invented id passes against an implementation that has no tenant filter at all.
     */
    private Map<String, Executable> probes() {
        Map<String, Executable> probes = new LinkedHashMap<>();

        probes.put("GET /public/businesses/{slug}", () -> {
            String aria = require(stranger.getAnonymously("/public/businesses/" + ariaSlug));
            assertThat(JsonPath.<String>read(aria, "$.name")).isEqualTo("Salon Aria");
            assertThat(aria).doesNotContain("Datos Auto");

            String auto = require(stranger.getAnonymously("/public/businesses/" + autoSlug));
            assertThat(JsonPath.<String>read(auto, "$.name")).isEqualTo("Datos Auto");
            assertThat(auto).doesNotContain("Salon Aria");
        });

        probes.put("GET /public/businesses/{slug}/services", () -> {
            // A bare array, not a {"services": …} envelope — the two management endpoints wrap and
            // these two do not, which is worth knowing before writing a path.
            String aria = require(stranger.getAnonymously("/public/businesses/" + ariaSlug + "/services"));
            assertThat(JsonPath.<List<String>>read(aria, "$[*].name")).containsExactly("Haircut");

            String auto = require(stranger.getAnonymously("/public/businesses/" + autoSlug + "/services"));
            assertThat(JsonPath.<List<String>>read(auto, "$[*].name")).containsExactly("Oil change");
        });

        probes.put("GET /public/businesses/{slug}/employees", () -> {
            String aria = require(stranger.getAnonymously("/public/businesses/" + ariaSlug + "/employees"));
            assertThat(JsonPath.<List<String>>read(aria, "$[*].fullName")).containsExactly("Nino Beridze");

            String auto = require(stranger.getAnonymously("/public/businesses/" + autoSlug + "/employees"));
            assertThat(JsonPath.<List<String>>read(auto, "$[*].fullName")).containsExactly("Dato Kapanadze");
        });

        probes.put("GET /public/businesses/{slug}/availability", () -> {
            // The controls: each page answers a grid for its own service.
            assertThat(availabilityOn(ariaSlug, aria.serviceId).getStatusCode().is2xxSuccessful())
                    .as("the control grid should have been readable")
                    .isTrue();
            assertThat(availabilityOn(autoSlug, autoService).getStatusCode().is2xxSuccessful())
                    .as("the control grid should have been readable")
                    .isTrue();

            // And neither page answers for the other's, in either direction.
            assertThat(availabilityOn(ariaSlug, autoService).getStatusCode())
                    .as("Datos Auto's service, asked for on Salon Aria's page")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(availabilityOn(autoSlug, aria.serviceId).getStatusCode())
                    .as("Salon Aria's service, asked for on Datos Auto's page")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        });

        probes.put("POST /public/businesses/{slug}/appointments", () -> {
            assertThat(bookOn(ariaSlug, autoService, aria.employeeId).getStatusCode())
                    .as("a booking on Aria's page naming Datos Auto's service")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(bookOn(ariaSlug, aria.serviceId, autoEmployee).getStatusCode())
                    .as("a booking on Aria's page naming Datos Auto's employee")
                    .isEqualTo(HttpStatus.NOT_FOUND);

            assertThat(jdbc.queryForObject(
                            "select count(*) from appointments where business_id = ?::uuid", Long.class, ariaBusinessId))
                    .as("neither refusal may have written anything")
                    .isOne();
        });

        probes.put("POST /public/businesses/{slug}/chat/session", () -> {
            // A Manage Link for Aria's appointment, presented on Datos Auto's page. The session is
            // allowed to open — a stale link should not break the chat panel — but it must open
            // with no authority, and the row must belong to the page it was opened on.
            String body = require(stranger.post(
                    "/public/businesses/" + autoSlug + "/chat/session", Map.of("manageToken", ariaManageToken)));
            String conversation = JsonPath.read(body, "$.conversationId");

            assertThat(jdbc.queryForObject(
                            "select business_id from ai_conversations where id = ?::uuid", String.class, conversation))
                    .as("the conversation belongs to the slug it was opened on, not to the token's tenant")
                    .isNotEqualTo(ariaBusinessId);

            assertThat(jdbc.queryForObject(
                            "select ?::uuid = any (authorized_appointment_ids) from ai_conversations "
                                    + "where id = ?::uuid",
                            Boolean.class,
                            ariaAppointment,
                            conversation))
                    .as("a token for Aria's appointment must not seed authority over it on another page")
                    .isFalse();
        });

        probes.put("POST /public/businesses/{slug}/chat", () -> {
            String token = JsonPath.read(
                    require(stranger.post("/public/businesses/" + ariaSlug + "/chat/session", Map.of())),
                    "$.sessionToken");

            Map<String, Object> message = new HashMap<>();
            message.put("sessionToken", token);
            message.put("message", "When are you open?");

            assertThat(stranger
                            .post("/public/businesses/" + autoSlug + "/chat", message)
                            .getStatusCode())
                    .as("a session opened on Aria's page, continued on Datos Auto's")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(model.callCount())
                    .as("the refusal must come before the provider, not after it has answered")
                    .isZero();
        });

        probes.put("POST /public/appointments/lookup", () -> {
            // The control: Datos Auto's own code and phone resolve.
            assertThat(lookup(autoCode, AUTO_CUSTOMER_PHONE).getStatusCode().is2xxSuccessful())
                    .as("the control lookup should have resolved")
                    .isTrue();

            // 401 rather than the 404 every other probe in this suite asserts, and deliberately so:
            // the other endpoints hide whether a row exists, while this one is a proof that either
            // holds or does not. It reveals nothing either way — every wrong pair answers alike —
            // and PublicIsolationTest pins the same status from the other direction.
            assertThat(lookup(autoCode, BookingScenario.CUSTOMER_PHONE).getStatusCode())
                    .as("Datos Auto's code with Salon Aria's customer's phone")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        });

        probes.put("GET /public/appointments/manage", () -> {
            String body = require(stranger.getAnonymously("/public/appointments/manage?token=" + ariaManageToken));
            assertThat(JsonPath.<String>read(body, "$.id")).isEqualTo(ariaAppointment);
            assertThat(body).doesNotContain(autoAppointment).doesNotContain("Datos Auto");
        });

        probes.put("GET /public/appointments/manage/availability", () -> {
            String body = require(stranger.getAnonymously("/public/appointments/manage/availability?token="
                    + ariaManageToken + "&from=" + aria.monday + "&to=" + aria.monday.plusDays(4)));
            // The grid is the token's own Business's. Datos Auto works the same week with the same
            // hours, so a grid that had crossed tenants would look entirely plausible — the employee
            // id is the only thing that gives it away.
            assertThat(body).doesNotContain(autoEmployee).doesNotContain(autoService);
        });

        probes.put("POST /public/appointments/{id}/cancel", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("authority", Map.of("manageToken", ariaManageToken));
            assertThat(stranger
                            .post("/public/appointments/" + autoAppointment + "/cancel", body)
                            .getStatusCode())
                    .as("Aria's Manage Link, aimed at Datos Auto's appointment")
                    .isEqualTo(HttpStatus.NOT_FOUND);

            assertThat(statusOf(autoAppointment)).isEqualTo("CONFIRMED");
        });

        probes.put("POST /public/appointments/{id}/reschedule", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("authority", Map.of("manageToken", ariaManageToken));
            body.put("startsAt", aria.at(aria.monday, 14, 0).toString());
            assertThat(stranger
                            .post("/public/appointments/" + autoAppointment + "/reschedule", body)
                            .getStatusCode())
                    .as("Aria's Manage Link, aimed at Datos Auto's appointment")
                    .isEqualTo(HttpStatus.NOT_FOUND);

            assertThat(jdbc.queryForObject(
                            "select count(*) from appointments where id = ?::uuid and starts_at = ?",
                            Long.class,
                            autoAppointment,
                            java.sql.Timestamp.from(aria.at(aria.monday, 10, 0).toInstant())))
                    .as("the appointment must still be at the time it was booked for")
                    .isOne();
        });

        return probes;
    }

    /**
     * The registry's own completeness check — the same guarantee {@link EndpointCoverageTest} gives
     * the catalogue, one level down.
     */
    @Test
    @DisplayName("every public endpoint in the catalogue has a probe written for it")
    void every_public_endpoint_has_a_probe() {
        Set<String> catalogued = new TreeSet<>();
        EndpointCatalogue.ENDPOINTS.forEach((endpoint, classification) -> {
            if (classification.isolation() == EndpointCatalogue.Isolation.PUBLIC_SLUG
                    || classification.isolation() == EndpointCatalogue.Isolation.PUBLIC_MANAGE_TOKEN) {
                catalogued.add(endpoint);
            }
        });

        Set<String> unprobed = new TreeSet<>(catalogued);
        unprobed.removeAll(probes().keySet());
        assertThat(unprobed)
                .as("public endpoints with no probe in this file. They cannot be swept mechanically "
                        + "— write one for the endpoint's own shape")
                .isEmpty();

        Set<String> stale = new TreeSet<>(probes().keySet());
        stale.removeAll(catalogued);
        assertThat(stale).as("probes for public endpoints the catalogue does not list").isEmpty();
    }

    // -----------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------

    private ResponseEntity<String> availabilityOn(String slug, String serviceId) {
        return stranger.getAnonymously("/public/businesses/" + slug + "/availability?serviceId=" + serviceId
                + "&from=" + aria.monday + "&to=" + aria.monday.plusDays(4));
    }

    private ResponseEntity<String> bookOn(String slug, String serviceId, String employeeId) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", serviceId);
        body.put("employeeId", employeeId);
        body.put("startsAt", aria.at(aria.monday, 15, 0).toString());
        body.put("customer", Map.of("fullName", "A Stranger", "phone", "+995555000111"));
        return stranger.post("/public/businesses/" + slug + "/appointments", body);
    }

    private ResponseEntity<String> lookup(String code, String phone) {
        return stranger.post("/public/appointments/lookup", Map.of("confirmationCode", code, "phone", phone));
    }

    private String statusOf(String appointmentId) {
        return jdbc.queryForObject("select status from appointments where id = ?::uuid", String.class, appointmentId);
    }

    private static String require(ResponseEntity<String> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("A probe's own setup failed: " + response.getBody());
        }
        return response.getBody();
    }
}
