package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * {@code POST /appointments} — the write path, end to end.
 *
 * <p>What is tested here is everything the availability engine's own suite cannot see: that a
 * booking writes exactly what it should, that a refused booking writes <em>nothing</em>, and that
 * each way of asking for an impossible time comes back with its own code rather than a generic one.
 *
 * <p>Rows are counted directly through {@link JdbcTemplate} in the places where the assertion is
 * about what reached the database. Reading it back through the API would pass against an
 * implementation that never wrote the row at all.
 */
class BookingEndpointTest extends IntegrationTest {

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

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
    }

    @Test
    @DisplayName("a booking returns the appointment, its code and the price agreed")
    void books() {
        ResponseEntity<String> response = aria.book(aria.at(aria.monday, 10, 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.timezone")).isEqualTo("Asia/Tbilisi");
        assertThat(JsonPath.<String>read(body, "$.appointment.status")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<String>read(body, "$.appointment.source")).isEqualTo("DASHBOARD");
        assertThat(JsonPath.<String>read(body, "$.appointment.price.amount")).isEqualTo("60.00");
        assertThat(JsonPath.<String>read(body, "$.appointment.confirmationCode")).matches("[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(JsonPath.<String>read(body, "$.appointment.customer.phone")).isEqualTo(BookingScenario.CUSTOMER_PHONE);
        // The times carry the business offset, not the server's — the suite runs at UTC+14.
        assertThat(JsonPath.<String>read(body, "$.appointment.startsAt")).isEqualTo(BookingScenario.wireTime(aria.monday, 10, 0));
        assertThat(JsonPath.<String>read(body, "$.appointment.endsAt")).isEqualTo(BookingScenario.wireTime(aria.monday, 11, 0));
    }

    @Test
    @DisplayName("the appointment and its CREATED event are written in one transaction")
    void writes_the_row_and_its_audit_event() {
        String id = aria.bookedAt(aria.at(aria.monday, 10, 0));

        assertThat(count("appointments")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select event_type from appointment_events where appointment_id = ?::uuid", String.class, id))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                        "select actor_type from appointment_events where appointment_id = ?::uuid", String.class, id))
                .isEqualTo("USER");
    }

    @Test
    @DisplayName("buffers widen the blocked range without changing what the customer sees")
    void buffers_become_the_blocked_range() {
        // Fifteen minutes before, ten after — deliberately different, so a transposition shows.
        String withBuffers = BookingScenario.createService(aria.owner, "Colour", 60, "120.00", 15, 10);
        aria.owner.put(
                "/employees/" + aria.employeeId + "/services",
                Map.of("serviceIds", List.of(aria.serviceId, withBuffers)));

        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", withBuffers);
        body.put("employeeId", aria.employeeId);
        body.put("startsAt", aria.at(aria.monday, 12, 0).toString());
        body.put("customerName", "Ana Tsereteli");
        body.put("customerPhone", BookingScenario.CUSTOMER_PHONE);
        String id = JsonPath.read(aria.owner.post("/appointments", body).getBody(), "$.appointment.id");

        Map<String, Object> row = jdbc.queryForMap(
                "select starts_at, ends_at, blocked_from, blocked_to from appointments where id = ?::uuid", id);
        Instant startsAt = ((java.sql.Timestamp) row.get("starts_at")).toInstant();
        Instant endsAt = ((java.sql.Timestamp) row.get("ends_at")).toInstant();
        Instant blockedFrom = ((java.sql.Timestamp) row.get("blocked_from")).toInstant();
        Instant blockedTo = ((java.sql.Timestamp) row.get("blocked_to")).toInstant();

        assertThat(java.time.Duration.between(blockedFrom, startsAt)).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(java.time.Duration.between(endsAt, blockedTo)).isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(java.time.Duration.between(startsAt, endsAt)).isEqualTo(java.time.Duration.ofHours(1));
    }

    @Test
    @DisplayName("the price is a snapshot: changing the service afterwards does not move it")
    void the_price_is_snapshotted() {
        String id = aria.bookedAt(aria.at(aria.monday, 10, 0));

        aria.owner.patch("/services/" + aria.serviceId, Map.of("price", "95.00"));

        String body = aria.owner.get("/appointments/" + id).getBody();
        assertThat(JsonPath.<String>read(body, "$.appointment.price.amount")).isEqualTo("60.00");
    }

    @Test
    @DisplayName("booking a taken slot is 409 SLOT_UNAVAILABLE and leaves one row")
    void a_taken_slot_is_refused() {
        aria.bookedAt(aria.at(aria.monday, 10, 0));

        ResponseEntity<String> second =
                aria.book(aria.at(aria.monday, 10, 0), aria.employeeId, "Giorgi Beridze", "+995555987654");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(BookingScenario.codeOf(second)).isEqualTo("SLOT_UNAVAILABLE");
        assertThat(count("appointments")).isEqualTo(1);
    }

    @Test
    @DisplayName("back-to-back appointments with no buffer are both bookable")
    void back_to_back_is_allowed() {
        aria.bookedAt(aria.at(aria.monday, 10, 0));

        ResponseEntity<String> next =
                aria.book(aria.at(aria.monday, 11, 0), aria.employeeId, "Giorgi Beridze", "+995555987654");

        // The '[)' bound on the exclusion constraint, observed rather than asserted about the SQL.
        // With '[]' this is the case that would silently start failing.
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("appointments")).isEqualTo(2);
    }

    @Test
    @DisplayName("a refused booking writes nothing at all — not even the customer")
    void a_failed_booking_is_atomic() {
        // Sunday: the business is shut, so this is refused after the request is otherwise valid.
        ResponseEntity<String> response = aria.book(
                aria.at(aria.monday.minusDays(1), 10, 0), aria.employeeId, "Someone New", "+995555111222");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(count("appointments")).isZero();
        assertThat(count("customers")).isZero();
        assertThat(count("appointment_events")).isZero();
    }

    @Test
    @DisplayName("each rejection carries its own code, not one generic refusal")
    void every_refusal_is_named() {
        assertThat(codeFor(aria.book(aria.at(aria.pastMonday, 10, 0)))).isEqualTo("BOOKING_IN_PAST");
        assertThat(codeFor(aria.book(aria.at(aria.monday.minusDays(1), 10, 0)))).isEqualTo("OUTSIDE_BUSINESS_HOURS");
        assertThat(codeFor(aria.book(aria.at(aria.monday.plusYears(2), 10, 0)))).isEqualTo("BEYOND_MAX_ADVANCE");
        assertThat(codeFor(aria.book(aria.at(aria.monday, 8, 0)))).isEqualTo("OUTSIDE_BUSINESS_HOURS");

        // Outside the employee's week but inside the business's — the two must not collapse into one.
        BookingScenario.setSchedule(aria.owner, aria.employeeId, "13:00", "17:00");
        assertThat(codeFor(aria.book(aria.at(aria.monday, 10, 0)))).isEqualTo("OUTSIDE_WORKING_HOURS");
    }

    @Test
    @DisplayName("too soon is BELOW_MIN_LEAD_TIME, not BOOKING_IN_PAST")
    void the_lead_time_has_its_own_refusal() {
        // A week of lead time, then a booking on the next working day — comfortably in the future,
        // comfortably inside the horizon, and inside the window the business will not take.
        aria.owner.patch("/business", Map.of("minLeadTimeMinutes", 7 * 24 * 60));

        LocalDate soon = LocalDate.now(clock.withZone(BookingScenario.TBILISI)).plusDays(1);
        while (soon.getDayOfWeek().getValue() > 5) {
            soon = soon.plusDays(1);
        }

        assertThat(codeFor(aria.book(aria.at(soon, 10, 0)))).isEqualTo("BELOW_MIN_LEAD_TIME");
    }

    @Test
    @DisplayName("an inactive service or employee is refused by name, before anything is written")
    void bookability_is_checked() {
        String other = BookingScenario.createEmployee(aria.owner, "Tamar Lomidze");
        assertThat(codeFor(aria.book(aria.at(aria.monday, 10, 0), other, "Ana", BookingScenario.CUSTOMER_PHONE)))
                .isEqualTo("EMPLOYEE_CANNOT_PERFORM_SERVICE");

        aria.owner.post("/employees/" + aria.employeeId + "/deactivate", null);
        assertThat(codeFor(aria.book(aria.at(aria.monday, 10, 0)))).isEqualTo("EMPLOYEE_INACTIVE");

        aria.owner.post("/employees/" + aria.employeeId + "/activate", null);
        aria.owner.post("/services/" + aria.serviceId + "/deactivate", null);
        assertThat(codeFor(aria.book(aria.at(aria.monday, 10, 0)))).isEqualTo("SERVICE_INACTIVE");
    }

    @Test
    @DisplayName("a start off the slot grid is refused, so no unfillable sliver is created")
    void off_grid_starts_are_refused() {
        // The default grid is fifteen minutes; 10:07 is not on it.
        assertThat(codeFor(aria.book(aria.at(aria.monday, 10, 7)))).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("the same phone twice is one customer, and the stored name is not overwritten")
    void a_returning_phone_reuses_the_customer() {
        aria.bookedAt(aria.at(aria.monday, 10, 0));
        // The same number, a different name — somebody booking for a family member.
        ResponseEntity<String> second = aria.book(
                aria.at(aria.monday, 12, 0), aria.employeeId, "Ana's daughter", BookingScenario.CUSTOMER_PHONE);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("customers")).isEqualTo(1);
        assertThat(JsonPath.<String>read(second.getBody(), "$.appointment.customer.fullName"))
                .isEqualTo("Ana Tsereteli");
    }

    @Test
    @DisplayName("an unreadable phone number is a field error, not a customer nobody can reach")
    void the_phone_must_be_normalisable() {
        // No country is set on the business, so a local number cannot be interpreted.
        ResponseEntity<String> response =
                aria.book(aria.at(aria.monday, 10, 0), aria.employeeId, "Ana", "555 12 34");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(BookingScenario.codeOf(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field")).contains("phone");
        assertThat(count("appointments")).isZero();
    }

    @Test
    @DisplayName("deleting a service that has ever been booked is refused with 409 SERVICE_IN_USE")
    void history_refuses_the_hard_delete() {
        String id = aria.bookedAt(aria.at(aria.monday, 10, 0));
        aria.owner.post("/appointments/" + id + "/cancel", Map.of());

        ResponseEntity<String> response = aria.owner.delete("/services/" + aria.serviceId);

        // Cancelled, and still refused: a delete is refused by history, not by the calendar.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(BookingScenario.codeOf(response)).isEqualTo("SERVICE_IN_USE");
    }

    @Test
    @DisplayName("a booked slot disappears from availability, with no change to that endpoint")
    void the_availability_engine_sees_the_booking() {
        String before = aria.owner
                .get("/availability?serviceId=%s&from=%s&to=%s".formatted(aria.serviceId, aria.monday, aria.monday))
                .getBody();
        List<String> beforeStarts = JsonPath.read(before, "$.days[0].slots[*].startsAt");

        aria.bookedAt(aria.at(aria.monday, 10, 0));

        String after = aria.owner
                .get("/availability?serviceId=%s&from=%s&to=%s".formatted(aria.serviceId, aria.monday, aria.monday))
                .getBody();
        List<String> afterStarts = JsonPath.read(after, "$.days[0].slots[*].startsAt");

        assertThat(beforeStarts).contains(BookingScenario.wireTime(aria.monday, 10, 0));
        assertThat(afterStarts).doesNotContain(BookingScenario.wireTime(aria.monday, 10, 0));

        // Seven starts go, not one: a sixty-minute service on a fifteen-minute grid overlaps a
        // 10:00–11:00 booking from any start between 09:15 and 10:45. The two that survive at the
        // edges are the '[)' bound again — 09:00 ends exactly as the booking begins, and 11:00
        // begins exactly as it ends.
        assertThat(afterStarts).hasSize(beforeStarts.size() - 7);
        assertThat(afterStarts)
                .contains(BookingScenario.wireTime(aria.monday, 9, 0), BookingScenario.wireTime(aria.monday, 11, 0));
    }

    private String codeFor(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected a refusal, got %s", response.getBody())
                .isFalse();
        return BookingScenario.codeOf(response);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

}
