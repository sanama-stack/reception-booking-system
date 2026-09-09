package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The isolation probes for {@code /appointments} and {@code /customers}
 * (docs/09-phase-plan.md §5, rule 5).
 *
 * <p>These endpoints hold the most sensitive rows in the system — a named person, their phone
 * number, and when they will be standing in a particular building. Every borrowed id must come back
 * {@code 404}, never {@code 403}: a status that varies with existence enumerates what it is
 * protecting (docs/06-security.md §3).
 *
 * <p><strong>Every id used here is real.</strong> Probing with a random UUID would pass against an
 * implementation with no tenant filter at all, which is the implementation this exists to catch.
 */
class AppointmentIsolationTest extends IntegrationTest {

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

    /** Salon Aria — the caller doing the probing. */
    private BookingScenario aria;

    /** Datos Auto — the tenant whose ids are being borrowed. */
    private AuthTestClient auto;

    private String autoAppointment;
    private String autoCustomer;
    private String autoEmployee;
    private String autoService;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);

        auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", BookingScenario.PASSWORD, "Datos Auto");
        auto.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        autoService = BookingScenario.createService(auto, "Oil change", 60, "40.00", 0, 0);
        autoEmployee = BookingScenario.createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + autoEmployee + "/services", Map.of("serviceIds", List.of(autoService)));
        BookingScenario.setSchedule(auto, autoEmployee, "09:00", "17:00");

        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", autoService);
        body.put("employeeId", autoEmployee);
        body.put("startsAt", aria.at(aria.monday, 10, 0).toString());
        body.put("customerName", "Levan Gogia");
        body.put("customerPhone", "+995555777888");
        String booked = auto.post("/appointments", body).getBody();
        autoAppointment = JsonPath.read(booked, "$.appointment.id");
        autoCustomer = JsonPath.read(booked, "$.appointment.customer.id");
    }

    @Test
    @DisplayName("another tenant's appointment is 404 on every action, not 403")
    void a_borrowed_appointment_id_does_not_exist() {
        assertThat(aria.owner.get("/appointments/" + autoAppointment).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aria.owner
                        .post("/appointments/" + autoAppointment + "/cancel", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aria.owner
                        .post(
                                "/appointments/" + autoAppointment + "/reschedule",
                                Map.of("startsAt", aria.at(aria.monday, 14, 0).toString()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aria.owner
                        .post("/appointments/" + autoAppointment + "/status", Map.of("status", "COMPLETED"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // And nothing was touched by any of it.
        assertThat(jdbc.queryForObject(
                        "select status from appointments where id = ?::uuid", String.class, autoAppointment))
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("another tenant's customer is 404, and their history is not readable")
    void a_borrowed_customer_id_does_not_exist() {
        assertThat(aria.owner.get("/customers/" + autoCustomer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aria.owner.get("/customers/" + autoCustomer + "/appointments").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aria.owner
                        .patch("/customers/" + autoCustomer, Map.of("fullName", "Renamed"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("booking with another tenant's service or employee is 404, and writes nothing")
    void a_booking_cannot_borrow_ids() {
        Map<String, Object> borrowedService = new HashMap<>();
        borrowedService.put("serviceId", autoService);
        borrowedService.put("employeeId", aria.employeeId);
        borrowedService.put("startsAt", aria.at(aria.monday, 12, 0).toString());
        borrowedService.put("customerName", "Ana");
        borrowedService.put("customerPhone", BookingScenario.CUSTOMER_PHONE);
        assertThat(aria.owner.post("/appointments", borrowedService).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> borrowedEmployee = new HashMap<>(borrowedService);
        borrowedEmployee.put("serviceId", aria.serviceId);
        borrowedEmployee.put("employeeId", autoEmployee);
        assertThat(aria.owner.post("/appointments", borrowedEmployee).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customers", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a listing shows only this tenant's rows, even when the other's are in the same hour")
    void listings_are_scoped() {
        aria.bookedAt(aria.at(aria.monday, 10, 0));

        String appointments = aria.owner.get("/appointments").getBody();
        assertThat(JsonPath.<Integer>read(appointments, "$.totalElements")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(appointments, "$.content[*].customer.fullName"))
                .containsExactly("Ana Tsereteli");

        String customers = aria.owner.get("/customers").getBody();
        assertThat(JsonPath.<List<String>>read(customers, "$.content[*].phone"))
                .containsExactly(BookingScenario.CUSTOMER_PHONE);
    }

    @Test
    @DisplayName("the other tenant still sees their own appointment untouched")
    void the_other_tenant_is_unaffected() {
        ResponseEntity<String> theirs = auto.get("/appointments/" + autoAppointment);

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(theirs.getBody(), "$.appointment.customer.fullName"))
                .isEqualTo("Levan Gogia");
    }
}
