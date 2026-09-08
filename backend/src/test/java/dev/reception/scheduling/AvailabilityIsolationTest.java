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
 * The isolation probe for {@code /availability} (docs/09-phase-plan.md §5, rule 5).
 *
 * <p>Availability takes two borrowed ids rather than one, and both must come back {@code 404}
 * rather than {@code 403} — a status that varies with existence enumerates what it is protecting
 * (docs/06-security.md §3).
 *
 * <p>It also leaks in a way no other endpoint so far can: a Slot list is a statement about when
 * somebody is <em>busy</em>. The last case here is the one that matters — Salon Aria's answer must
 * be shaped by Salon Aria's hours and staff and by nothing else, even when the other tenant's
 * business is configured to be open at completely different times.
 *
 * <p><strong>Every id used here is real.</strong> Probing with a random UUID would pass against an
 * implementation with no tenant filter at all, which is the implementation this exists to catch.
 */
class AvailabilityIsolationTest extends IntegrationTest {

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

    /** Salon Aria — the caller doing the probing. */
    private AuthTestClient aria;

    private String ariaService;

    /** Datos Auto — the tenant whose ids are being borrowed. */
    private String autoService;
    private String autoEmployee;

    private LocalDate monday;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");
        ariaService = createService(aria, "Haircut");
        String ariaEmployee = createEmployee(aria, "Nino Beridze");
        aria.put("/employees/" + ariaEmployee + "/services", Map.of("serviceIds", List.of(ariaService)));
        setSchedule(aria, ariaEmployee, "09:00", "13:00");

        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", PASSWORD, "Datos Auto");
        autoService = createService(auto, "Oil change");
        autoEmployee = createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + autoEmployee + "/services", Map.of("serviceIds", List.of(autoService)));
        // Deliberately different hours: if Aria's answer ever borrowed them it would be obvious.
        setSchedule(auto, autoEmployee, "14:00", "17:00");

        monday = LocalDate.now(clock.withZone(TBILISI))
                .plusDays(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private String createService(AuthTestClient as, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", 60);
        body.put("price", "60.00");
        return JsonPath.read(as.post("/services", body).getBody(), "$.id");
    }

    private String createEmployee(AuthTestClient as, String fullName) {
        return JsonPath.read(as.post("/employees", Map.of("fullName", fullName)).getBody(), "$.id");
    }

    private void setSchedule(AuthTestClient as, String employeeId, String from, String to) {
        List<Map<String, Object>> week = List.of(1, 2, 3, 4, 5).stream()
                .map(day -> Map.<String, Object>of("dayOfWeek", day, "startsAt", from, "endsAt", to))
                .toList();
        as.put("/employees/" + employeeId + "/schedule", Map.of("schedule", week));
    }

    @Test
    @DisplayName("another tenant's serviceId is 404, not 403")
    void another_tenants_service() {
        var response = aria.get(
                "/availability?serviceId=%s&from=%s&to=%s".formatted(autoService, monday, monday));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("another tenant's employeeId is 404, not a refusal that confirms they exist")
    void another_tenants_employee() {
        var response = aria.get("/availability?serviceId=%s&from=%s&to=%s&employeeId=%s"
                .formatted(ariaService, monday, monday, autoEmployee));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Not EMPLOYEE_CANNOT_PERFORM_SERVICE, which is what the assignment check would say if the
        // tenant check did not run first — and which would confirm the employee is real.
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("an answer is shaped by the caller's own configuration and nobody else's")
    void the_answer_is_the_callers_own() {
        String body = aria.get("/availability?serviceId=%s&from=%s&to=%s".formatted(ariaService, monday, monday))
                .getBody();

        List<String> employees = JsonPath.read(body, "$.days[0].slots[*].employee.fullName");
        assertThat(employees).isNotEmpty().containsOnly("Nino Beridze");
        // Aria's employee works 09:00–13:00; the other tenant's works 14:00–17:00. Any slot in the
        // afternoon would mean the other business's schedule had reached this answer.
        assertThat(JsonPath.<List<String>>read(body, "$.days[0].slots[*].startsAt"))
                .allSatisfy(start -> assertThat(OffsetDateTime.parse(start).getHour())
                        .isBetween(9, 12));
    }
}
