package dev.reception.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code /employees/{id}/time-off} — when one Employee is away.
 *
 * <p>The translation under test is the same one {@code ClosureEndpointTest} covers for a Business
 * Closure: the owner names an inclusive range of local dates, and the row holds a half-open span of
 * instants. Getting it wrong by one day is invisible in the response and disastrous in phase 05.
 */
class TimeOffEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    private AuthTestClient owner;
    private String employee;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");
        employee = JsonPath.read(
                owner.post("/employees", Map.of("fullName", "Nino Beridze")).getBody(), "$.id");
    }

    private ResponseEntity<String> create(String startDate, String endDate, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("startDate", startDate);
        body.put("endDate", endDate);
        body.put("reason", reason);
        return owner.post("/employees/" + employee + "/time-off", body);
    }

    private String list() {
        return owner.get("/employees/" + employee + "/time-off").getBody();
    }

    @Test
    @DisplayName("a new employee has no time off")
    void starts_empty() {
        assertThat(JsonPath.<List<String>>read(list(), "$.timeOff")).isEmpty();
    }

    @Test
    @DisplayName("a created absence reads back with the dates the owner entered")
    void creates_and_reads_back() {
        ResponseEntity<String> created = create("2026-12-24", "2026-12-26", "Holiday");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = created.getBody();
        assertThat((String) JsonPath.read(body, "$.startDate")).isEqualTo("2026-12-24");
        // The owner's last day off, not the stored half-open end. Rendering endsAt would tell them
        // they are away a day longer than they said.
        assertThat((String) JsonPath.read(body, "$.endDate")).isEqualTo("2026-12-26");
        assertThat((String) JsonPath.read(body, "$.reason")).isEqualTo("Holiday");
    }

    @Test
    @DisplayName("the stored range is half-open — ends_at is the start of the day after the last day off")
    void stores_a_half_open_range() {
        owner.patch("/business", Map.of("timezone", "Asia/Tbilisi"));

        create("2026-12-24", "2026-12-26", null);

        // Asia/Tbilisi is UTC+4, so local midnight on the 24th is 20:00 UTC on the 23rd, and the
        // exclusive end is 20:00 UTC on the 26th. Half-open is what lets two adjacent absences meet
        // exactly rather than overlapping or leaving an hour between them.
        Map<String, Object> row = jdbc.queryForMap("select starts_at, ends_at from employee_time_off");
        assertThat(((java.sql.Timestamp) row.get("starts_at")).toInstant())
                .isEqualTo(Instant.parse("2026-12-23T20:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("ends_at")).toInstant())
                .isEqualTo(Instant.parse("2026-12-26T20:00:00Z"));
    }

    @Test
    @DisplayName("the response carries the zone as well as both representations, so nothing is re-derived")
    void reports_the_timezone() {
        owner.patch("/business", Map.of("timezone", "Asia/Tbilisi"));
        create("2026-12-24", "2026-12-26", null);

        String body = list();
        assertThat((String) JsonPath.read(body, "$.timezone")).isEqualTo("Asia/Tbilisi");
        assertThat((String) JsonPath.read(body, "$.timeOff[0].startDate")).isEqualTo("2026-12-24");
        assertThat((String) JsonPath.read(body, "$.timeOff[0].endDate")).isEqualTo("2026-12-26");
        assertThat((String) JsonPath.read(body, "$.timeOff[0].startsAt")).isNotBlank();
    }

    @Test
    @DisplayName("a single day off is the same date twice, and stores as a full day")
    void stores_a_single_day() {
        assertThat(create("2026-12-24", "2026-12-24", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Not a zero-length range, which the CHECK would refuse.
        Map<String, Object> row = jdbc.queryForMap("select starts_at, ends_at from employee_time_off");
        assertThat(((java.sql.Timestamp) row.get("starts_at")).toInstant())
                .isEqualTo(Instant.parse("2026-12-24T00:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("ends_at")).toInstant())
                .isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
    }

    @Test
    @DisplayName("an inverted range is refused, and the message lands on the end date")
    void refuses_an_inverted_range() {
        ResponseEntity<String> response = create("2026-12-26", "2026-12-24", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field"))
                .containsExactly("endDate");
    }

    @Test
    @DisplayName("a missing date is refused")
    void refuses_a_missing_date() {
        assertThat(owner.post("/employees/" + employee + "/time-off", Map.of("startDate", "2026-12-24"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("the list comes back in start order")
    void lists_in_start_order() {
        create("2026-12-24", "2026-12-26", "Christmas");
        create("2026-08-01", "2026-08-14", "Summer");

        assertThat(JsonPath.<List<String>>read(list(), "$.timeOff[*].reason"))
                .containsExactly("Summer", "Christmas");
    }

    @Test
    @DisplayName("a deleted absence is gone")
    void deletes() {
        String id = JsonPath.read(create("2026-12-24", "2026-12-26", null).getBody(), "$.id");

        assertThat(owner.delete("/employees/" + employee + "/time-off/" + id).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(JsonPath.<List<String>>read(list(), "$.timeOff")).isEmpty();
    }

    @Test
    @DisplayName("an absence cannot be deleted through a different employee")
    void cannot_be_deleted_through_another_employee() {
        String id = JsonPath.read(create("2026-12-24", "2026-12-26", null).getBody(), "$.id");
        String other = JsonPath.read(
                owner.post("/employees", Map.of("fullName", "Dato Kapanadze")).getBody(), "$.id");

        // The nested id is checked against the employee as well as the tenant, so a real id cannot
        // be borrowed across employees inside one business.
        assertThat(owner.delete("/employees/" + other + "/time-off/" + id).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<List<String>>read(list(), "$.timeOff[*].id")).containsExactly(id);
    }

    @Test
    @DisplayName("one employee's absences are not another's")
    void keeps_absences_independent() {
        String other = JsonPath.read(
                owner.post("/employees", Map.of("fullName", "Dato Kapanadze")).getBody(), "$.id");
        create("2026-12-24", "2026-12-26", null);

        assertThat(JsonPath.<List<String>>read(
                        owner.get("/employees/" + other + "/time-off").getBody(), "$.timeOff"))
                .isEmpty();
    }

    @Test
    @DisplayName("listing or creating time off for an employee that does not exist is a 404")
    void unknown_employees_are_not_found() {
        String missing = UUID.randomUUID().toString();

        assertThat(owner.get("/employees/" + missing + "/time-off").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.post("/employees/" + missing + "/time-off", Map.of("startDate", "2026-12-24", "endDate", "2026-12-26"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
