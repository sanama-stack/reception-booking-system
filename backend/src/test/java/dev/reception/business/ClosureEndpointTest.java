package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
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

/** {@code /business/closures} — create from local dates, list, delete. */
class ClosureEndpointTest extends IntegrationTest {

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
        owner.patch("/business", Map.of("timezone", "Asia/Tbilisi"));
    }

    /** A HashMap rather than {@code Map.of}, because a closure with no reason is a legitimate case. */
    private ResponseEntity<String> create(String start, String end, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("startDate", start);
        body.put("endDate", end);
        body.put("reason", reason);
        return owner.post("/business/closures", body);
    }

    @Test
    @DisplayName("a closure is created from local dates and comes back as both dates and instants")
    void creates_a_closure() {
        ResponseEntity<String> response = create("2026-12-24", "2026-12-26", "Christmas");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = response.getBody();
        assertThat((String) JsonPath.read(body, "$.closure.startDate")).isEqualTo("2026-12-24");
        assertThat((String) JsonPath.read(body, "$.closure.endDate")).isEqualTo("2026-12-26");
        assertThat((String) JsonPath.read(body, "$.closure.reason")).isEqualTo("Christmas");
        // Tbilisi is UTC+4, so the local day begins at 20:00 the previous day.
        assertThat((String) JsonPath.read(body, "$.closure.startsAt")).startsWith("2026-12-23T20:00:00");
    }

    @Test
    @DisplayName("creating a closure reports how many appointments it covers, and cancels none of them")
    void reports_the_appointments_it_covers() {
        // Zero until phase 06 gives appointments somewhere to exist. The field is present now so
        // the shape does not change under a client that has already shipped, and so the dashboard
        // has somewhere to put the warning when the number stops being zero.
        assertThat(JsonPath.<Integer>read(create("2026-12-24", "2026-12-26", null).getBody(), "$.affectedAppointments"))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("closures list in date order, with the business's zone alongside")
    void lists_closures_in_order() {
        create("2026-12-24", "2026-12-26", "Christmas");
        create("2026-08-01", "2026-08-14", "Summer");

        String body = owner.get("/business/closures").getBody();
        assertThat(JsonPath.<List<String>>read(body, "$.closures[*].startDate"))
                .containsExactly("2026-08-01", "2026-12-24");
        assertThat((String) JsonPath.read(body, "$.timezone")).isEqualTo("Asia/Tbilisi");
    }

    @Test
    @DisplayName("a closure with no reason is legitimate")
    void a_reason_is_optional() {
        ResponseEntity<String> response =
                owner.post("/business/closures", Map.of("startDate", "2026-12-24", "endDate", "2026-12-24"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) JsonPath.read(response.getBody(), "$.closure.reason")).isNull();
    }

    @Test
    @DisplayName("a deleted closure is gone")
    void deletes_a_closure() {
        String id = JsonPath.read(create("2026-12-24", "2026-12-26", "Christmas").getBody(), "$.closure.id");

        assertThat(owner.delete("/business/closures/" + id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(JsonPath.<List<String>>read(owner.get("/business/closures").getBody(), "$.closures[*].id"))
                .isEmpty();
    }

    @Test
    @DisplayName("deleting a closure that does not exist is a 404, not a silent success")
    void deleting_an_unknown_closure_is_not_found() {
        assertThat(owner.delete("/business/closures/" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a last day before the first is rejected")
    void rejects_an_inverted_range() {
        ResponseEntity<String> response = create("2026-12-26", "2026-12-24", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) JsonPath.read(response.getBody(), "$.errors[0].field")).isEqualTo("endDate");
    }

    @Test
    @DisplayName("a missing date is rejected")
    void requires_both_dates() {
        assertThat(owner.post("/business/closures", Map.of("startDate", "2026-12-24"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("changing the timezone afterwards does not move a closure's instants")
    void a_timezone_change_does_not_rewrite_stored_closures() {
        String created = create("2026-12-24", "2026-12-26", null).getBody();
        String startsAt = JsonPath.read(created, "$.closure.startsAt");

        owner.patch("/business", Map.of("timezone", "America/New_York"));

        String listed = owner.get("/business/closures").getBody();
        // The instant is unchanged; only the local dates it is displayed as have moved. That is
        // exactly the effect a timezone change has, and the reason the UI must confirm it
        // (ADR-0003).
        assertThat(JsonPath.<List<String>>read(listed, "$.closures[*].startsAt")).containsExactly(startsAt);
        assertThat(JsonPath.<List<String>>read(listed, "$.closures[*].startDate")).containsExactly("2026-12-23");
    }
}
