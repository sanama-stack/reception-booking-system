package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
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
 * What happens to an Appointment after it exists: cancel, reschedule, and marking the outcome.
 *
 * <p>Every case here also asserts the audit trail, because the trail is the half that is easy to
 * leave out and impossible to reconstruct afterwards. A cancellation that changed the row and wrote
 * no event would pass every other assertion in this file.
 */
class AppointmentLifecycleTest extends IntegrationTest {

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

    private BookingScenario aria;
    private String appointment;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
        appointment = aria.bookedAt(aria.at(aria.monday, 10, 0));
    }

    // -----------------------------------------------------------------------
    // Cancellation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("cancelling records who did it, when, and why")
    void cancelling_records_the_circumstances() {
        ResponseEntity<String> response =
                aria.owner.post("/appointments/" + appointment + "/cancel", Map.of("reason", "Stylist is unwell"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.appointment.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<String>read(body, "$.appointment.cancelledBy")).isEqualTo("BUSINESS");
        assertThat(JsonPath.<String>read(body, "$.appointment.cancellationReason")).isEqualTo("Stylist is unwell");
        assertThat(JsonPath.<String>read(body, "$.appointment.cancelledAt")).isNotNull();
        assertThat(JsonPath.<List<String>>read(body, "$.history[*].type")).containsExactly("CREATED", "CANCELLED");
    }

    @Test
    @DisplayName("cancelling twice is idempotent and writes no second event")
    void cancelling_is_idempotent() {
        aria.owner.post("/appointments/" + appointment + "/cancel", Map.of("reason", "First"));
        ResponseEntity<String> second =
                aria.owner.post("/appointments/" + appointment + "/cancel", Map.of("reason", "Second"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The first reason survives: a repeat is not a correction.
        assertThat(JsonPath.<String>read(second.getBody(), "$.appointment.cancellationReason"))
                .isEqualTo("First");
        assertThat(events("CANCELLED")).isEqualTo(1);
    }

    @Test
    @DisplayName("the business is never bound by its own cancellation window")
    void the_business_may_cancel_inside_the_window() {
        // A window wider than the booking is far out — nothing a customer could cancel through.
        aria.owner.patch("/business", Map.of("cancellationWindowHours", 168));

        ResponseEntity<String> response = aria.owner.post("/appointments/" + appointment + "/cancel", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.appointment.status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a cancelled appointment's time becomes bookable again")
    void cancelling_frees_the_slot() {
        aria.owner.post("/appointments/" + appointment + "/cancel", Map.of());

        ResponseEntity<String> rebooked =
                aria.book(aria.at(aria.monday, 10, 0), aria.employeeId, "Giorgi Beridze", "+995555987654");

        // This is the partial WHERE status = 'CONFIRMED' on the exclusion constraint. Without it,
        // the business would lose the slot it just freed.
        assertThat(rebooked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("a completed appointment cannot then be cancelled")
    void a_terminal_appointment_cannot_be_cancelled() {
        aria.owner.post("/appointments/" + appointment + "/status", Map.of("status", "COMPLETED"));

        ResponseEntity<String> response = aria.owner.post("/appointments/" + appointment + "/cancel", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(BookingScenario.codeOf(response)).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    // -----------------------------------------------------------------------
    // Reschedule
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("rescheduling keeps the id and the code, and records the times it moved from")
    void rescheduling_moves_in_place() {
        String codeBefore = JsonPath.read(
                aria.owner.get("/appointments/" + appointment).getBody(), "$.appointment.confirmationCode");

        ResponseEntity<String> response = aria.owner.post(
                "/appointments/" + appointment + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday, 14, 0).toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.appointment.id")).isEqualTo(appointment);
        // The customer is holding an email with this code in it.
        assertThat(JsonPath.<String>read(body, "$.appointment.confirmationCode")).isEqualTo(codeBefore);
        assertThat(JsonPath.<String>read(body, "$.appointment.startsAt"))
                .isEqualTo(BookingScenario.wireTime(aria.monday, 14, 0));
        assertThat(JsonPath.<List<String>>read(body, "$.history[*].type")).containsExactly("CREATED", "RESCHEDULED");
        assertThat(JsonPath.<String>read(body, "$.history[1].payload.previousStartsAt"))
                .isEqualTo(aria.at(aria.monday, 10, 0).toInstant().toString());
        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a move that overlaps the appointment's own current time is allowed")
    void an_appointment_does_not_block_itself() {
        // 10:15 overlaps 10:00–11:00. Counting the appointment against itself would refuse the one
        // move it is being asked to make — the database never does, and neither must the pre-check.
        ResponseEntity<String> response = aria.owner.post(
                "/appointments/" + appointment + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday, 10, 15).toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.appointment.startsAt"))
                .isEqualTo(BookingScenario.wireTime(aria.monday, 10, 15));
    }

    @Test
    @DisplayName("rescheduling into a taken slot is refused and leaves the original where it was")
    void a_taken_slot_refuses_the_move() {
        aria.book(aria.at(aria.monday, 14, 0), aria.employeeId, "Giorgi Beridze", "+995555987654");

        ResponseEntity<String> response = aria.owner.post(
                "/appointments/" + appointment + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday, 14, 0).toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(BookingScenario.codeOf(response)).isEqualTo("SLOT_UNAVAILABLE");
        assertThat(JsonPath.<String>read(
                        aria.owner.get("/appointments/" + appointment).getBody(), "$.appointment.startsAt"))
                .isEqualTo(BookingScenario.wireTime(aria.monday, 10, 0));
    }

    @Test
    @DisplayName("rescheduling can move the appointment to a different employee")
    void the_employee_can_change_with_the_time() {
        String tamar = BookingScenario.createEmployee(aria.owner, "Tamar Lomidze");
        aria.owner.put("/employees/" + tamar + "/services", Map.of("serviceIds", List.of(aria.serviceId)));
        BookingScenario.setSchedule(aria.owner, tamar, "09:00", "17:00");

        ResponseEntity<String> response = aria.owner.post(
                "/appointments/" + appointment + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday, 15, 0).toString(), "employeeId", tamar));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.appointment.employee.id")).isEqualTo(tamar);
    }

    @Test
    @DisplayName("a cancelled appointment cannot be moved")
    void a_cancelled_appointment_cannot_be_rescheduled() {
        aria.owner.post("/appointments/" + appointment + "/cancel", Map.of());

        ResponseEntity<String> response = aria.owner.post(
                "/appointments/" + appointment + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday, 14, 0).toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(BookingScenario.codeOf(response)).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    // -----------------------------------------------------------------------
    // Status
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("marking completed writes the status and an event naming the move")
    void completing_records_the_move() {
        ResponseEntity<String> response =
                aria.owner.post("/appointments/" + appointment + "/status", Map.of("status", "COMPLETED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.appointment.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<List<String>>read(body, "$.history[*].type")).containsExactly("CREATED", "COMPLETED");
        assertThat(JsonPath.<String>read(body, "$.history[1].payload.from")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<String>read(body, "$.history[1].payload.to")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a no-show cannot later be completed")
    void terminal_statuses_are_terminal_over_http() {
        aria.owner.post("/appointments/" + appointment + "/status", Map.of("status", "NO_SHOW"));

        ResponseEntity<String> response =
                aria.owner.post("/appointments/" + appointment + "/status", Map.of("status", "COMPLETED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(BookingScenario.codeOf(response)).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("cancelling through the status endpoint is refused and points at the cancel action")
    void cancelling_is_not_a_status_change() {
        ResponseEntity<String> response =
                aria.owner.post("/appointments/" + appointment + "/status", Map.of("status", "CANCELLED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(BookingScenario.codeOf(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field")).contains("status");
        // And nothing moved.
        assertThat(JsonPath.<String>read(
                        aria.owner.get("/appointments/" + appointment).getBody(), "$.appointment.status"))
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a completed appointment still holds no time, so its slot is bookable again")
    void a_completed_appointment_releases_nothing_it_should_keep() {
        aria.owner.post("/appointments/" + appointment + "/status", Map.of("status", "COMPLETED"));

        // COMPLETED is outside the constraint's CONFIRMED predicate, so the time is free. That is
        // correct for a past appointment and is the reason status is never set on a future one by
        // anything but a person.
        ResponseEntity<String> rebooked =
                aria.book(aria.at(aria.monday, 10, 0), aria.employeeId, "Giorgi Beridze", "+995555987654");
        assertThat(rebooked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private long events(String type) {
        return jdbc.queryForObject(
                "select count(*) from appointment_events where appointment_id = ?::uuid and event_type = ?",
                Long.class,
                appointment,
                type);
    }
}
