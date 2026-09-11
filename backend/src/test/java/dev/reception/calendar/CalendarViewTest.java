package dev.reception.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.LocalDate;
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
 * {@code GET /calendar} — phase 10's one read per view.
 *
 * <p>Two of these tests are about what the endpoint <em>does not</em> send: a cancelled appointment,
 * whose time was released and which drawn as a block would hide free time; and the customer's
 * contact details and confirmation code, which a rectangle on a grid has no use for and which the
 * detail drawer fetches separately when an appointment is actually opened.
 */
class CalendarViewTest extends IntegrationTest {

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
    @DisplayName("one call carries the appointments, the closures and the time off")
    void the_whole_view_arrives_at_once() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));
        aria.owner.post(
                "/business/closures",
                Map.of("startDate", aria.monday.plusDays(1).toString(), "endDate",
                        aria.monday.plusDays(1).toString(), "reason", "Public holiday"));
        aria.owner.post(
                "/employees/" + aria.employeeId + "/time-off",
                Map.of("startDate", aria.monday.plusDays(2).toString(), "endDate",
                        aria.monday.plusDays(2).toString(), "reason", "Wedding"));

        String body = calendar(aria.monday, aria.monday.plusDays(6)).getBody();

        assertThat(JsonPath.<List<Object>>read(body, "$.appointments[*]")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(body, "$.closures[*].reason")).containsExactly("Public holiday");
        assertThat(JsonPath.<List<String>>read(body, "$.timeOff[*].reason")).containsExactly("Wedding");
        assertThat(JsonPath.<String>read(body, "$.range.timezone")).isEqualTo(BookingScenario.TBILISI.getId());
    }

    /**
     * A column with nothing in it has to be explicable, and the absence is what explains it — so the
     * name is on the entry rather than left for the client to look up per employee.
     */
    @Test
    @DisplayName("time off names the employee whose absence it is")
    void time_off_carries_the_employees_name() {
        aria.owner.post(
                "/employees/" + aria.employeeId + "/time-off",
                Map.of("startDate", aria.monday.toString(), "endDate", aria.monday.toString(), "reason", "Wedding"));

        String body = calendar(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<String>read(body, "$.timeOff[0].employee.name")).isEqualTo("Nino Beridze");
        assertThat(JsonPath.<String>read(body, "$.timeOff[0].employee.id")).isEqualTo(aria.employeeId);
    }

    /**
     * <strong>The time is released the instant an appointment is cancelled.</strong> Drawn on the
     * grid anyway, it covers an hour that is in fact free — and the owner declines to book somebody
     * into it.
     */
    @Test
    @DisplayName("a cancelled appointment is not a block on the calendar")
    void a_cancelled_appointment_leaves_no_block() {
        String cancelled = aria.bookedAt(aria.at(aria.monday, 9, 0));
        aria.bookedAt(aria.at(aria.monday, 10, 0));
        aria.owner.post("/appointments/" + cancelled + "/cancel", Map.of());

        String body = calendar(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.appointments[*].id")).doesNotContain(cancelled);
        assertThat(JsonPath.<List<Object>>read(body, "$.appointments[*]")).hasSize(1);
    }

    /**
     * The endpoint's data diet, asserted rather than assumed.
     *
     * <p>Reusing the detail record would have shipped a week of confirmation codes and customer
     * phone numbers to the browser to render rectangles. This test fails the moment somebody does.
     */
    @Test
    @DisplayName("a calendar block carries a name, and none of the customer's contact details")
    void the_calendar_sends_no_contact_details_and_no_code() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));

        String body = calendar(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<String>read(body, "$.appointments[0].customerName")).isEqualTo("Ana Tsereteli");
        assertThat(body)
                .as("the phone the fixture booked with must not be anywhere in this response")
                .doesNotContain(BookingScenario.CUSTOMER_PHONE)
                .doesNotContain("confirmationCode")
                .doesNotContain("price");
    }

    @Test
    @DisplayName("a block knows what booked it, so the AI badge has something to draw from")
    void an_appointment_carries_its_source() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));

        assertThat(JsonPath.<String>read(calendar(aria.monday, aria.monday).getBody(), "$.appointments[0].source"))
                .isEqualTo("DASHBOARD");
    }

    /** Every time on the endpoint is the Business's, offset included, decided on the server. */
    @Test
    @DisplayName("times are rendered in the business timezone, offset and all")
    void times_are_in_the_business_timezone() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));

        String body = calendar(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<String>read(body, "$.appointments[0].startsAt"))
                .isEqualTo(BookingScenario.wireTime(aria.monday, 9, 0));
        assertThat(JsonPath.<String>read(body, "$.appointments[0].endsAt"))
                .isEqualTo(BookingScenario.wireTime(aria.monday, 10, 0));
    }

    @Test
    @DisplayName("a range wider than 35 days is refused rather than quietly narrowed")
    void a_range_over_five_weeks_is_refused() {
        ResponseEntity<String> response = calendar(aria.monday, aria.monday.plusDays(35));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a backwards range is refused, not swapped")
    void a_backwards_range_is_refused() {
        assertThat(calendar(aria.monday, aria.monday.minusDays(1)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /**
     * The isolation probe for this endpoint (docs/09-phase-plan.md §5, rule 5).
     *
     * <p>Like the analytics summary there is no id to borrow — only dates — so what is probed is the
     * whole view: three separate reads, each of which could forget its tenant independently of the
     * other two.
     */
    @Test
    @DisplayName("another business's appointments, closures and time off are in nobody else's view")
    void the_calendar_shows_one_tenant_only() {
        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", BookingScenario.PASSWORD, "Datos Auto");
        auto.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        String autoService = BookingScenario.createService(auto, "Oil Change", 30, "40.00", 0, 0);
        String autoEmployee = BookingScenario.createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + autoEmployee + "/services", Map.of("serviceIds", List.of(autoService)));
        BookingScenario.setSchedule(auto, autoEmployee, "09:00", "17:00");
        auto.post(
                "/appointments",
                Map.of(
                        "serviceId", autoService,
                        "employeeId", autoEmployee,
                        "startsAt", aria.at(aria.monday, 9, 0).toString(),
                        "customerName", "Giorgi",
                        "customerPhone", "+995555987654"));
        auto.post(
                "/business/closures",
                Map.of("startDate", aria.monday.toString(), "endDate", aria.monday.toString(), "reason", "Stocktake"));
        auto.post(
                "/employees/" + autoEmployee + "/time-off",
                Map.of("startDate", aria.monday.toString(), "endDate", aria.monday.toString(), "reason", "Away"));

        String body = calendar(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<List<Object>>read(body, "$.appointments[*]")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(body, "$.closures[*]")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(body, "$.timeOff[*]")).isEmpty();
        assertThat(body).doesNotContain("Dato Kapanadze").doesNotContain("Stocktake");
    }

    private ResponseEntity<String> calendar(LocalDate from, LocalDate to) {
        return aria.owner.get("/calendar?from=" + from + "&to=" + to);
    }
}
