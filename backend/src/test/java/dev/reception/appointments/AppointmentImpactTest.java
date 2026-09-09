package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The four questions phases 03 and 04 have been asking a stub, now answered from real rows.
 *
 * <p>{@code EmptyAppointmentImpact} answered zero to all of them, truthfully, because no appointment
 * could exist. Every one of those call sites was written and tested against that answer, and none of
 * them changed this phase — so this is the test that says the seam works, rather than that the
 * callers still compile.
 *
 * <p>The fifth question, {@code blockedRangesFor}, is the one whose wrong answer is silent: a
 * forgotten stub goes on reporting every Employee free forever and the system offers Slots that are
 * already booked. It is exercised in {@code BookingEndpointTest}, against the availability endpoint,
 * where the symptom would actually appear.
 */
class AppointmentImpactTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    private BookingScenario aria;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
    }

    @Test
    @DisplayName("a proposed closure reports the appointments it would cover, and cancels nothing")
    void a_closure_counts_what_it_covers() {
        aria.bookedAt(aria.at(aria.monday, 10, 0));
        aria.bookedAt(aria.at(aria.monday, 12, 0));

        String created = aria.owner
                .post(
                        "/business/closures",
                        Map.of("startDate", aria.monday.toString(), "endDate", aria.monday.toString(), "reason", "Refit"))
                .getBody();

        assertThat(JsonPath.<Integer>read(created, "$.affectedAppointments")).isEqualTo(2);
        // Reported, never acted on. Silently cancelling a customer's appointment because an owner
        // blocked out a week is the kind of helpfulness that loses a business its customers.
        assertThat(JsonPath.<Integer>read(aria.owner.get("/appointments?status=CONFIRMED").getBody(), "$.totalElements"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a cancelled appointment is not counted by a closure, because it holds no time")
    void a_closure_ignores_cancelled_appointments() {
        String id = aria.bookedAt(aria.at(aria.monday, 10, 0));
        aria.owner.post("/appointments/" + id + "/cancel", Map.of());

        String created = aria.owner
                .post(
                        "/business/closures",
                        Map.of("startDate", aria.monday.toString(), "endDate", aria.monday.toString()))
                .getBody();

        assertThat(JsonPath.<Integer>read(created, "$.affectedAppointments")).isZero();
    }

    @Test
    @DisplayName("deactivating a service reports its upcoming appointments and cancels none")
    void deactivating_a_service_counts_the_future() {
        aria.bookedAt(aria.at(aria.monday, 10, 0));

        String body = aria.owner.post("/services/" + aria.serviceId + "/deactivate", null).getBody();

        assertThat(JsonPath.<Integer>read(body, "$.affectedFutureAppointments")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(aria.owner.get("/appointments?status=CONFIRMED").getBody(), "$.totalElements"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("deactivating an employee reports theirs")
    void deactivating_an_employee_counts_the_future() {
        aria.bookedAt(aria.at(aria.monday, 10, 0));

        String body = aria.owner.post("/employees/" + aria.employeeId + "/deactivate", null).getBody();

        assertThat(JsonPath.<Integer>read(body, "$.affectedFutureAppointments")).isEqualTo(1);
    }

    @Test
    @DisplayName("a cancelled appointment is not an upcoming one, so nothing is reported")
    void cancelled_appointments_are_not_upcoming() {
        String id = aria.bookedAt(aria.at(aria.monday, 10, 0));
        aria.owner.post("/appointments/" + id + "/cancel", Map.of());

        String body = aria.owner.post("/employees/" + aria.employeeId + "/deactivate", null).getBody();

        assertThat(JsonPath.<Integer>read(body, "$.affectedFutureAppointments")).isZero();
    }
}
