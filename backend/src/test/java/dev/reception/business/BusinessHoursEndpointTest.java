package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
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
 * {@code GET} and {@code PUT /business/hours}.
 *
 * <p>The property being defended is that the week is only ever replaced whole. The atomicity case
 * is the one worth reading: a payload whose fifth day is invalid must leave the first four exactly
 * as they were, and it must do so because nothing was written — not because a rollback repaired
 * something that had been.
 */
class BusinessHoursEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient owner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");
    }

    private static Map<String, Object> interval(int day, String opens, String closes) {
        return Map.of("dayOfWeek", day, "opensAt", opens, "closesAt", closes);
    }

    private static Object week(Object... intervals) {
        return Map.of("hours", List.of(intervals));
    }

    @Test
    @DisplayName("registration's default week reads back as Monday to Friday, 09:00 to 17:00")
    void reads_the_default_week() {
        String body = owner.get("/business/hours").getBody();

        assertThat(JsonPath.<List<Integer>>read(body, "$.hours[*].dayOfWeek")).containsExactly(1, 2, 3, 4, 5);
        assertThat(JsonPath.<List<String>>read(body, "$.hours[*].opensAt"))
                .containsExactly("09:00", "09:00", "09:00", "09:00", "09:00");
        // The zone travels with the times, because 09:00 means nothing without it.
        assertThat((String) JsonPath.read(body, "$.timezone")).isEqualTo("UTC");
    }

    @Test
    @DisplayName("the whole week is replaced, and a day absent from the payload is closed")
    void a_day_with_no_interval_is_closed() {
        ResponseEntity<String> response = owner.put(
                "/business/hours",
                week(interval(1, "10:00", "18:00"), interval(6, "10:00", "14:00")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = owner.get("/business/hours").getBody();
        assertThat(JsonPath.<List<Integer>>read(body, "$.hours[*].dayOfWeek")).containsExactly(1, 6);
        assertThat(JsonPath.<List<String>>read(body, "$.hours[*].opensAt")).containsExactly("10:00", "10:00");
    }

    @Test
    @DisplayName("an empty week is accepted and means closed every day")
    void an_empty_week_closes_the_business() {
        assertThat(owner.put("/business/hours", Map.of("hours", List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(JsonPath.<List<Integer>>read(owner.get("/business/hours").getBody(), "$.hours[*].dayOfWeek"))
                .isEmpty();
    }

    @Test
    @DisplayName("a split shift is stored as two intervals on one day")
    void stores_a_split_shift() {
        owner.put("/business/hours", week(interval(2, "09:00", "13:00"), interval(2, "14:00", "18:00")));

        String body = owner.get("/business/hours").getBody();
        assertThat(JsonPath.<List<Integer>>read(body, "$.hours[*].dayOfWeek")).containsExactly(2, 2);
        assertThat(JsonPath.<List<String>>read(body, "$.hours[*].opensAt")).containsExactly("09:00", "14:00");
    }

    @Test
    @DisplayName("an invalid fifth day leaves the first four exactly as they were")
    void an_invalid_day_leaves_the_rest_of_the_week_untouched() {
        ResponseEntity<String> response = owner.put(
                "/business/hours",
                week(
                        interval(1, "08:00", "18:00"),
                        interval(2, "08:00", "18:00"),
                        interval(3, "08:00", "18:00"),
                        interval(4, "08:00", "18:00"),
                        // Closes before it opens.
                        interval(5, "18:00", "08:00")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) JsonPath.read(response.getBody(), "$.errors[0].field"))
                .isEqualTo("hours[4].closesAt");

        // Still registration's week: nothing was deleted, so nothing needed restoring.
        String body = owner.get("/business/hours").getBody();
        assertThat(JsonPath.<List<Integer>>read(body, "$.hours[*].dayOfWeek")).containsExactly(1, 2, 3, 4, 5);
        assertThat(JsonPath.<List<String>>read(body, "$.hours[*].opensAt"))
                .containsExactly("09:00", "09:00", "09:00", "09:00", "09:00");
    }

    @Test
    @DisplayName("overlapping intervals on one day are rejected")
    void rejects_overlapping_intervals() {
        ResponseEntity<String> response = owner.put(
                "/business/hours", week(interval(1, "09:00", "14:00"), interval(1, "13:00", "17:00")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) JsonPath.read(response.getBody(), "$.errors[0].field")).isEqualTo("hours[1].opensAt");
    }

    @Test
    @DisplayName("adjacent intervals on one day are accepted — that is a split shift with no break")
    void accepts_adjacent_intervals() {
        assertThat(owner.put(
                                "/business/hours",
                                week(interval(1, "09:00", "13:00"), interval(1, "13:00", "17:00")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a day outside 1..7 is rejected")
    void rejects_an_impossible_day() {
        assertThat(owner.put("/business/hours", week(interval(8, "09:00", "17:00")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(owner.put("/business/hours", week(interval(0, "09:00", "17:00")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("replacing the same week twice does not collide with the rows it is replacing")
    void a_replace_can_reuse_the_times_it_is_replacing() {
        // The delete and the inserts share (business_id, day_of_week, opens_at), so this is the
        // case that fails if the delete is not flushed before the inserts.
        Object same = week(interval(1, "09:00", "17:00"));

        assertThat(owner.put("/business/hours", same).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(owner.put("/business/hours", same).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(JsonPath.<List<Integer>>read(owner.get("/business/hours").getBody(), "$.hours[*].dayOfWeek"))
                .containsExactly(1);
    }

    @Test
    @DisplayName("a missing hours field is a malformed request, not an empty week")
    void requires_the_hours_field() {
        assertThat(owner.put("/business/hours", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
