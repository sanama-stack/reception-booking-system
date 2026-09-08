package dev.reception.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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

/**
 * {@code GET /availability} against a fully configured business.
 *
 * <p>The engine's own correctness is settled by the pure suite, which can enumerate cases this one
 * cannot reach. What is tested here is everything between the request and the engine: which
 * Employees are eligible, which questions are refused and with which code, and whether the times
 * come back in a shape a client can read without guessing a zone.
 *
 * <p>Dates are taken from the injected {@code Clock} rather than written down, because the Booking
 * Horizon is measured from the real now. The <em>weekday</em> is pinned instead: a business open
 * Monday to Friday has nothing to say about a Sunday, and a test that silently landed on one would
 * fail once a week.
 */
class AvailabilityEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    private AuthTestClient owner;
    private String haircut;
    private String nino;
    private LocalDate monday;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");
        // A real zone rather than the UTC default, so an offset that was never applied is visible.
        owner.patch("/business", Map.of("timezone", TBILISI.getId()));

        // Registration seeds Monday to Friday, 09:00 to 17:00.
        haircut = createService("Haircut", 60);
        nino = createEmployee("Nino Beridze");
        owner.put("/employees/" + nino + "/services", Map.of("serviceIds", List.of(haircut)));
        setSchedule(nino, "09:00", "17:00");

        monday = LocalDate.now(clock.withZone(TBILISI))
                .plusDays(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private String createService(String name, int durationMinutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", durationMinutes);
        body.put("price", "60.00");
        return JsonPath.read(owner.post("/services", body).getBody(), "$.id");
    }

    private String createEmployee(String fullName) {
        return JsonPath.read(owner.post("/employees", Map.of("fullName", fullName)).getBody(), "$.id");
    }

    private void setSchedule(String employeeId, String from, String to) {
        List<Map<String, Object>> week = List.of(1, 2, 3, 4, 5).stream()
                .map(day -> Map.<String, Object>of("dayOfWeek", day, "startsAt", from, "endsAt", to))
                .toList();
        owner.put("/employees/" + employeeId + "/schedule", Map.of("schedule", week));
    }

    private String availability(String query) {
        return owner.get("/availability?" + query).getBody();
    }

    private String forHaircutOn(LocalDate date) {
        return availability("serviceId=%s&from=%s&to=%s".formatted(haircut, date, date));
    }

    @Test
    @DisplayName("a configured business answers with a day of slots on the grid")
    void slots_for_a_configured_business() {
        String body = forHaircutOn(monday);

        assertThat(JsonPath.<String>read(body, "$.timezone")).isEqualTo("Asia/Tbilisi");
        assertThat(JsonPath.<List<String>>read(body, "$.days[*].date")).containsExactly(monday.toString());
        // 09:00 to 16:00 on the default fifteen-minute grid, for a sixty-minute service.
        assertThat(JsonPath.<List<String>>read(body, "$.days[0].slots[*].startsAt")).hasSize(29);
        assertThat(JsonPath.<Object>read(body, "$.emptyReason")).isNull();
    }

    @Test
    @DisplayName("times carry the business offset, and the response names the zone")
    void times_carry_the_offset() {
        String body = forHaircutOn(monday);
        String first = JsonPath.<List<String>>read(body, "$.days[0].slots[*].startsAt").getFirst();

        OffsetDateTime start = OffsetDateTime.parse(first);
        assertThat(start.getOffset().getTotalSeconds()).isEqualTo(4 * 3600);
        assertThat(start.toLocalTime().toString()).isEqualTo("09:00");
        assertThat(OffsetDateTime.parse(
                                JsonPath.<List<String>>read(body, "$.days[0].slots[*].endsAt").getFirst())
                        .toLocalTime()
                        .toString())
                .isEqualTo("10:00");
    }

    @Test
    @DisplayName("every slot carries the employee who would perform it, even though none was asked for")
    void every_slot_carries_its_employee() {
        String body = forHaircutOn(monday);

        assertThat(JsonPath.<List<String>>read(body, "$.days[0].slots[*].employee.id")).containsOnly(nino);
        assertThat(JsonPath.<List<String>>read(body, "$.days[0].slots[*].employee.fullName"))
                .containsOnly("Nino Beridze");
    }

    @Test
    @DisplayName("a closed day comes back present and empty, with a reason")
    void a_closed_day() {
        String body = forHaircutOn(monday.minusDays(1));

        assertThat(JsonPath.<List<Object>>read(body, "$.days[0].slots")).isEmpty();
        assertThat(JsonPath.<String>read(body, "$.emptyReason")).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("a service nobody is assigned to reports NO_ELIGIBLE_EMPLOYEE")
    void nobody_can_perform_it() {
        String colour = createService("Colour", 60);
        String body = availability("serviceId=%s&from=%s&to=%s".formatted(colour, monday, monday));

        assertThat(JsonPath.<String>read(body, "$.emptyReason")).isEqualTo("NO_ELIGIBLE_EMPLOYEE");
    }

    @Test
    @DisplayName("a deactivated employee stops producing availability immediately")
    void a_deactivated_employee_disappears() {
        owner.post("/employees/" + nino + "/deactivate", Map.of());

        assertThat(JsonPath.<String>read(forHaircutOn(monday), "$.emptyReason")).isEqualTo("NO_ELIGIBLE_EMPLOYEE");
    }

    @Test
    @DisplayName("time off removes exactly the days it covers")
    void time_off_removes_its_days() {
        owner.post(
                "/employees/" + nino + "/time-off",
                Map.of("startDate", monday.toString(), "endDate", monday.toString(), "reason", "Away"));

        String body = availability(
                "serviceId=%s&from=%s&to=%s".formatted(haircut, monday, monday.plusDays(1)));

        assertThat(JsonPath.<List<Object>>read(body, "$.days[0].slots")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(body, "$.days[1].slots")).isNotEmpty();
    }

    @Test
    @DisplayName("a closure removes the day for the whole business")
    void a_closure_removes_the_day() {
        owner.post(
                "/business/closures",
                Map.of("startDate", monday.toString(), "endDate", monday.toString(), "reason", "Public holiday"));

        assertThat(JsonPath.<List<Object>>read(forHaircutOn(monday), "$.days[0].slots"))
                .isEmpty();
    }

    @Test
    @DisplayName("narrowing the working schedule narrows availability")
    void the_schedule_narrows_availability() {
        setSchedule(nino, "13:00", "16:00");
        String body = forHaircutOn(monday);

        List<String> starts = JsonPath.read(body, "$.days[0].slots[*].startsAt");
        // 13:00 to 15:00 on the quarter-hour: a sixty-minute service must end by 16:00.
        assertThat(starts).hasSize(9);
        assertThat(OffsetDateTime.parse(starts.getFirst()).toLocalTime()).hasToString("13:00");
        assertThat(OffsetDateTime.parse(starts.getLast()).toLocalTime()).hasToString("15:00");
    }

    @Test
    @DisplayName("asking for one employee answers only about them")
    void asking_for_one_employee() {
        String dato = createEmployee("Dato Kapanadze");
        owner.put("/employees/" + dato + "/services", Map.of("serviceIds", List.of(haircut)));
        setSchedule(dato, "09:00", "12:00");

        String body = availability(
                "serviceId=%s&from=%s&to=%s&employeeId=%s".formatted(haircut, monday, monday, dato));

        assertThat(JsonPath.<List<String>>read(body, "$.days[0].slots[*].employee.id")).containsOnly(dato);
        assertThat(JsonPath.<List<String>>read(body, "$.days[0].slots[*].startsAt")).hasSize(9);
    }

    @Test
    @DisplayName("a range longer than 31 days is refused with a field error")
    void too_long_a_range() {
        var response = owner.get("/availability?serviceId=%s&from=%s&to=%s"
                .formatted(haircut, monday, monday.plusDays(31)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field")).containsExactly("to");
    }

    @Test
    @DisplayName("exactly 31 days is accepted")
    void exactly_thirty_one_days() {
        var response = owner.get("/availability?serviceId=%s&from=%s&to=%s"
                .formatted(haircut, monday, monday.plusDays(30)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<List<Object>>read(response.getBody(), "$.days")).hasSize(31);
    }

    @Test
    @DisplayName("a range that ends before it starts is refused")
    void a_backwards_range() {
        var response = owner.get("/availability?serviceId=%s&from=%s&to=%s"
                .formatted(haircut, monday, monday.minusDays(1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a deactivated service is refused rather than answered with nothing")
    void a_deactivated_service() {
        owner.post("/services/" + haircut + "/deactivate", Map.of());
        var response = owner.get("/availability?serviceId=%s&from=%s&to=%s".formatted(haircut, monday, monday));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("SERVICE_INACTIVE");
    }

    @Test
    @DisplayName("an employee not assigned to the service is refused by name")
    void an_unassigned_employee() {
        String dato = createEmployee("Dato Kapanadze");
        var response = owner.get("/availability?serviceId=%s&from=%s&to=%s&employeeId=%s"
                .formatted(haircut, monday, monday, dato));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("EMPLOYEE_CANNOT_PERFORM_SERVICE");
        assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).contains("Dato Kapanadze");
    }

    @Test
    @DisplayName("a deactivated employee asked for by name is refused as inactive")
    void a_deactivated_employee_by_name() {
        owner.post("/employees/" + nino + "/deactivate", Map.of());
        var response = owner.get("/availability?serviceId=%s&from=%s&to=%s&employeeId=%s"
                .formatted(haircut, monday, monday, nino));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("EMPLOYEE_INACTIVE");
    }

    @Test
    @DisplayName("a service id that does not exist is a 404")
    void an_unknown_service() {
        var response = owner.get("/availability?serviceId=%s&from=%s&to=%s"
                .formatted("00000000-0000-0000-0000-000000000000", monday, monday));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an anonymous caller gets nothing")
    void anonymous_callers_are_refused() {
        assertThat(owner.getAnonymously("/availability?serviceId=%s&from=%s&to=%s".formatted(haircut, monday, monday))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("nothing is written — phase 05 is read-only")
    void nothing_is_written() {
        forHaircutOn(monday);
        forHaircutOn(monday.plusDays(1));

        // The only tables a booking would touch do not exist yet, so the honest assertion available
        // today is that the configuration the read is derived from is unchanged by reading it.
        assertThat(JsonPath.<List<Object>>read(owner.get("/employees/" + nino + "/time-off").getBody(), "$.timeOff"))
                .isEmpty();
        assertThat(JsonPath.<Boolean>read(owner.get("/services/" + haircut).getBody(), "$.active"))
                .isTrue();
    }
}
