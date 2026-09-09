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

/**
 * {@code GET /appointments} — the filters, the order and the page.
 *
 * <p>Each filter is exercised on its own <em>and</em> the no-filter case is exercised, because they
 * fail differently: an optional parameter compared to null is the case PostgreSQL cannot type, and
 * it only ever appears when the filter is absent. A suite that always passed a date would have been
 * green while the default listing returned a 500.
 */
class AppointmentListingTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    private BookingScenario aria;
    private String tamar;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);

        tamar = BookingScenario.createEmployee(aria.owner, "Tamar Lomidze");
        aria.owner.put("/employees/" + tamar + "/services", Map.of("serviceIds", List.of(aria.serviceId)));
        BookingScenario.setSchedule(aria.owner, tamar, "09:00", "17:00");

        // Monday: two with Nino, one with Tamar. Tuesday: one with Nino, cancelled.
        aria.bookedAt(aria.at(aria.monday, 10, 0));
        aria.bookedAt(aria.at(aria.monday, 12, 0));
        aria.book(aria.at(aria.monday, 10, 0), tamar, "Giorgi Beridze", "+995555987654");
        String tuesday = aria.bookedAt(aria.at(aria.monday.plusDays(1), 15, 0));
        aria.owner.post("/appointments/" + tuesday + "/cancel", Map.of());
    }

    @Test
    @DisplayName("with no filters at all, everything comes back in start order")
    void the_default_listing_is_everything_in_order() {
        String body = aria.owner.get("/appointments").getBody();

        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(4);
        assertThat(JsonPath.<String>read(body, "$.timezone")).isEqualTo("Asia/Tbilisi");
        assertThat(JsonPath.<List<String>>read(body, "$.content[*].startsAt"))
                .containsExactly(
                        BookingScenario.wireTime(aria.monday, 10, 0),
                        BookingScenario.wireTime(aria.monday, 10, 0),
                        BookingScenario.wireTime(aria.monday, 12, 0),
                        BookingScenario.wireTime(aria.monday.plusDays(1), 15, 0));
    }

    @Test
    @DisplayName("a date range is inclusive of both ends, read in the business's zone")
    void the_range_is_inclusive() {
        String mondayOnly = aria.owner
                .get("/appointments?from=%s&to=%s".formatted(aria.monday, aria.monday))
                .getBody();
        assertThat(JsonPath.<Integer>read(mondayOnly, "$.totalElements")).isEqualTo(3);

        String bothDays = aria.owner
                .get("/appointments?from=%s&to=%s".formatted(aria.monday, aria.monday.plusDays(1)))
                .getBody();
        assertThat(JsonPath.<Integer>read(bothDays, "$.totalElements")).isEqualTo(4);
    }

    @Test
    @DisplayName("filtering by status and by employee each narrows the list on its own")
    void each_filter_works_alone() {
        assertThat(JsonPath.<Integer>read(
                        aria.owner.get("/appointments?status=CANCELLED").getBody(), "$.totalElements"))
                .isEqualTo(1);
        assertThat(JsonPath.<Integer>read(
                        aria.owner.get("/appointments?status=CONFIRMED").getBody(), "$.totalElements"))
                .isEqualTo(3);
        assertThat(JsonPath.<Integer>read(
                        aria.owner.get("/appointments?employeeId=" + tamar).getBody(), "$.totalElements"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("filters combine")
    void filters_combine() {
        String body = aria.owner
                .get("/appointments?from=%s&to=%s&status=CONFIRMED&employeeId=%s"
                        .formatted(aria.monday, aria.monday, aria.employeeId))
                .getBody();

        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(body, "$.content[*].employee.name"))
                .containsOnly("Nino Beridze");
    }

    @Test
    @DisplayName("a page carries its own numbers and the rows a client needs to render")
    void pagination_is_spelled_out() {
        String body = aria.owner.get("/appointments?page=0&size=2").getBody();

        assertThat(JsonPath.<Integer>read(body, "$.page")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.size")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(4);
        assertThat(JsonPath.<Integer>read(body, "$.totalPages")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(body, "$.content[*].service.name")).containsOnly("Haircut");
        assertThat(JsonPath.<List<String>>read(body, "$.content[*].customer.phone")).isNotEmpty();
    }

    @Test
    @DisplayName("the detail endpoint carries the history and never the blocked range")
    void the_detail_shows_history_and_hides_the_occupancy() {
        String id = JsonPath.read(aria.owner.get("/appointments").getBody(), "$.content[0].id");

        String body = aria.owner.get("/appointments/" + id).getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.history[*].type")).containsExactly("CREATED");
        // Buffers are the exclusion constraint's business. A screen showing them would be showing a
        // customer cleanup time as if it were part of their appointment.
        assertThat(body).doesNotContain("blockedFrom").doesNotContain("blockedTo");
    }

    @Test
    @DisplayName("an unknown appointment is 404, the same answer another tenant's id gets")
    void an_unknown_id_is_not_found() {
        assertThat(aria.owner
                        .get("/appointments/00000000-0000-7000-8000-000000000000")
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
