package dev.reception.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
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

/**
 * {@code /employees/{id}/schedule} — the whole week, replaced at once.
 *
 * <p>Deliberately the same cases as {@code BusinessHoursEndpointTest}, because it is deliberately
 * the same contract: one editor serves both, and phase 05 intersects the two lists.
 */
class EmployeeScheduleEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

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

    private static Map<String, Object> interval(int day, String starts, String ends) {
        return Map.of("dayOfWeek", day, "startsAt", starts, "endsAt", ends);
    }

    private ResponseEntity<String> replace(Object... intervals) {
        return owner.put("/employees/" + employee + "/schedule", Map.of("schedule", List.of(intervals)));
    }

    private String read() {
        return owner.get("/employees/" + employee + "/schedule").getBody();
    }

    @Test
    @DisplayName("a new employee has no schedule at all")
    void starts_empty() {
        // Not "seven closed days": absence is the representation, here and in storage.
        assertThat(JsonPath.<List<Integer>>read(read(), "$.schedule")).isEmpty();
    }

    @Test
    @DisplayName("a replaced week reads back in day and time order, with the business timezone")
    void replaces_and_reads_back() {
        replace(interval(3, "09:00", "17:00"), interval(1, "09:00", "17:00"));

        String body = read();
        assertThat(JsonPath.<List<Integer>>read(body, "$.schedule[*].dayOfWeek")).containsExactly(1, 3);
        // HH:mm, which is what <input type="time"> both produces and expects; Jackson's ISO default
        // would add a seconds field no client wants to strip.
        assertThat(JsonPath.<List<String>>read(body, "$.schedule[*].startsAt")).containsExactly("09:00", "09:00");
        assertThat((String) JsonPath.read(body, "$.timezone")).isEqualTo("UTC");
    }

    @Test
    @DisplayName("a day with no interval is a day off, expressed by absence")
    void a_missing_day_is_a_day_off() {
        replace(interval(1, "09:00", "17:00"));

        assertThat(JsonPath.<List<Integer>>read(read(), "$.schedule[*].dayOfWeek")).containsExactly(1);
    }

    @Test
    @DisplayName("an empty week is legitimate and clears the schedule")
    void accepts_an_empty_week() {
        replace(interval(1, "09:00", "17:00"));

        assertThat(replace().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<List<Integer>>read(read(), "$.schedule")).isEmpty();
    }

    @Test
    @DisplayName("omitting the week is a malformed request, unlike sending an empty one")
    void refuses_an_absent_week() {
        assertThat(owner.put("/employees/" + employee + "/schedule", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("submitting the same week twice succeeds")
    void a_replace_can_reuse_the_times_it_is_replacing() {
        replace(interval(1, "09:00", "17:00"));

        // The case that fails without the flush between the delete and the inserts: Hibernate orders
        // by entity type, and UNIQUE (employee_id, day_of_week, starts_at) does not care that the
        // row it collides with is about to be removed.
        assertThat(replace(interval(1, "09:00", "17:00")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<List<Integer>>read(read(), "$.schedule[*].dayOfWeek")).containsExactly(1);
    }

    @Test
    @DisplayName("a split shift is two intervals on one day")
    void accepts_a_split_shift() {
        replace(interval(1, "09:00", "13:00"), interval(1, "14:00", "18:00"));

        assertThat(JsonPath.<List<String>>read(read(), "$.schedule[*].startsAt")).containsExactly("09:00", "14:00");
    }

    @Test
    @DisplayName("an overlap is refused, and the message lands on the row that caused it")
    void refuses_an_overlap() {
        ResponseEntity<String> response = replace(
                interval(1, "09:00", "17:00"), interval(2, "09:00", "13:00"), interval(2, "12:00", "18:00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // Index 2 — its position in the submitted array, not its day. A form maps that back to the
        // row it drew.
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field"))
                .containsExactly("schedule[2].startsAt");
    }

    @Test
    @DisplayName("an invalid week leaves the previous one untouched")
    void a_rejected_replace_changes_nothing() {
        replace(interval(1, "09:00", "17:00"));

        replace(interval(2, "09:00", "13:00"), interval(2, "12:00", "18:00"));

        // Validated in full before anything is deleted, so this holds because the rows were never
        // touched — not because a rollback repaired them.
        assertThat(JsonPath.<List<Integer>>read(read(), "$.schedule[*].dayOfWeek")).containsExactly(1);
    }

    @Test
    @DisplayName("an end before its start is refused")
    void refuses_an_inverted_interval() {
        assertThat(replace(interval(1, "17:00", "09:00")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a day outside 1..7 is refused")
    void refuses_an_impossible_day() {
        assertThat(replace(interval(8, "09:00", "17:00")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a schedule wider than the business's opening hours is stored as given")
    void does_not_clamp_to_business_hours() {
        // Registration seeds Mon–Fri 09:00–17:00. This employee is willing to start at 07:00, and
        // that is their answer to a different question — phase 05 intersects the two. Clamping here
        // would silently re-cut every employee's week whenever the opening hours moved.
        assertThat(replace(interval(1, "07:00", "21:00")).getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = read();
        assertThat(JsonPath.<List<String>>read(body, "$.schedule[*].startsAt")).containsExactly("07:00");
        assertThat(JsonPath.<List<String>>read(body, "$.schedule[*].endsAt")).containsExactly("21:00");
    }

    @Test
    @DisplayName("a schedule on a day the business is closed is stored as given")
    void allows_a_day_the_business_is_closed() {
        // Sunday. Same reasoning: willingness to work is not the same fact as being open.
        assertThat(replace(interval(7, "10:00", "14:00")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("one employee's schedule is independent of another's")
    void keeps_schedules_independent() {
        String other = JsonPath.read(
                owner.post("/employees", Map.of("fullName", "Dato Kapanadze")).getBody(), "$.id");
        replace(interval(1, "09:00", "17:00"));

        owner.put("/employees/" + other + "/schedule", Map.of("schedule", List.of(interval(2, "10:00", "18:00"))));

        assertThat(JsonPath.<List<Integer>>read(read(), "$.schedule[*].dayOfWeek")).containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(
                        owner.get("/employees/" + other + "/schedule").getBody(), "$.schedule[*].dayOfWeek"))
                .containsExactly(2);
    }

    @Test
    @DisplayName("reading or replacing the schedule of an employee that does not exist is a 404")
    void unknown_employees_are_not_found() {
        String missing = UUID.randomUUID().toString();

        assertThat(owner.get("/employees/" + missing + "/schedule").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.put("/employees/" + missing + "/schedule", Map.of("schedule", List.of()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
