package dev.reception.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
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
 * The Classic Flow, driven by somebody who has never signed in.
 *
 * <p>Every request here goes through {@link PublicTestClient}, which holds no cookies — so nothing
 * in this class can pass because a session leaked into it.
 */
class PublicBookingTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private BookingScenario scenario;
    private PublicTestClient stranger;
    private String slug;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        scenario = BookingScenario.open(rest, port, clock);
        stranger = new PublicTestClient(rest, port);
        slug = JsonPath.read(scenario.owner.get("/business").getBody(), "$.slug");
    }

    // ---------------------------------------------------------------- reads

    @Test
    @DisplayName("the booking page shows the business, its hours and its policy")
    void profile_is_readable_without_signing_in() {
        ResponseEntity<String> response = stranger.get("/public/businesses/" + slug);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.name")).isEqualTo("Salon Aria");
        assertThat(JsonPath.<String>read(response.getBody(), "$.timezone")).isEqualTo("Asia/Tbilisi");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.cancellationWindowHours"))
                .isEqualTo(24);
        // Monday to Friday, from registration's defaults.
        assertThat(JsonPath.<List<?>>read(response.getBody(), "$.hours")).hasSize(5);
    }

    @Test
    @DisplayName("services carry a duration and a price, and inactive ones are not offered")
    void only_bookable_services_are_listed() {
        String retired = BookingScenario.createService(scenario.owner, "Perm", 90, "90.00", 0, 0);
        scenario.owner.post("/services/" + retired + "/deactivate", Map.of());

        ResponseEntity<String> response = stranger.get("/public/businesses/" + slug + "/services");

        assertThat(JsonPath.<List<String>>read(response.getBody(), "$[*].name")).containsExactly("Haircut");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$[0].durationMinutes")).isEqualTo(60);
        // A string, not a float. Money that has been through a double is money that is wrong.
        assertThat(JsonPath.<String>read(response.getBody(), "$[0].price.amount")).isEqualTo("60.00");
    }

    @Test
    @DisplayName("asking who can perform a service excludes everyone who cannot")
    void employees_are_filtered_by_assignment() {
        String unassigned = BookingScenario.createEmployee(scenario.owner, "Dato Kapanadze");
        BookingScenario.setSchedule(scenario.owner, unassigned, "09:00", "17:00");

        String all = stranger.get("/public/businesses/" + slug + "/employees").getBody();
        assertThat(JsonPath.<List<String>>read(all, "$[*].fullName"))
                .containsExactlyInAnyOrder("Nino Beridze", "Dato Kapanadze");

        String forService = stranger
                .get("/public/businesses/" + slug + "/employees?serviceId=" + scenario.serviceId)
                .getBody();
        // Naming somebody who cannot perform it produces EMPLOYEE_CANNOT_PERFORM_SERVICE at the end
        // of the flow. Filtering here is what stops the page offering that mistake.
        assertThat(JsonPath.<List<String>>read(forService, "$[*].fullName")).containsExactly("Nino Beridze");
    }

    @Test
    @DisplayName("public availability is the internal endpoint's answer, byte for byte")
    void availability_matches_the_internal_endpoint() {
        String query = "?serviceId=%s&from=%s&to=%s".formatted(scenario.serviceId, scenario.monday, scenario.monday);

        String publicGrid = stranger
                .get("/public/businesses/" + slug + "/availability" + query)
                .getBody();
        String internalGrid = scenario.owner.get("/availability" + query).getBody();

        // Same engine, same DTO, same response. Two implementations of "when are you free" would
        // eventually disagree and the one a Customer saw would be the wrong one.
        assertThat(publicGrid).isEqualTo(internalGrid);
        assertThat(JsonPath.<List<?>>read(publicGrid, "$.days[0].slots")).isNotEmpty();
    }

    // ------------------------------------------------------------- the write

    @Test
    @DisplayName("a stranger books end to end and the booking is sourced CLASSIC")
    void a_stranger_can_book() {
        ResponseEntity<String> response = book(scenario.at(scenario.monday, 10, 0), "ana@example.test");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = JsonPath.read(response.getBody(), "$.confirmationCode");
        assertThat(code).hasSize(8);
        assertThat(JsonPath.<String>read(response.getBody(), "$.employee.fullName")).isEqualTo("Nino Beridze");
        assertThat(JsonPath.<String>read(response.getBody(), "$.service.name")).isEqualTo("Haircut");
        assertThat(JsonPath.<String>read(response.getBody(), "$.startsAt"))
                .isEqualTo(BookingScenario.wireTime(scenario.monday, 10, 0));

        // The owner sees it, and sees where it came from. CLASSIC has never rendered on any screen
        // before this phase.
        String id = JsonPath.read(response.getBody(), "$.id");
        assertThat(JsonPath.<String>read(scenario.owner.get("/appointments/" + id).getBody(), "$.appointment.source"))
                .isEqualTo("CLASSIC");
    }

    @Test
    @DisplayName("the confirmation email is enqueued by the booking itself, in its own transaction")
    void booking_enqueues_the_confirmation() {
        ResponseEntity<String> response = book(scenario.at(scenario.monday, 11, 0), "ana@example.test");

        // Nothing in publicapi mentions notifications. BookingService enqueues inside the booking
        // transaction, so the public flow inherited this for free the moment it called book().
        assertThat(pendingNotificationTypes()).contains("BOOKING_CONFIRMATION", "REMINDER_24H");
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.confirmationSent")).isTrue();
    }

    @Test
    @DisplayName("a booking with no email address queues nothing and says so")
    void booking_without_an_email_still_books() {
        ResponseEntity<String> response = book(scenario.at(scenario.monday, 12, 0), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pendingNotificationTypes()).isEmpty();
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.confirmationSent")).isFalse();
    }

    // ---------------------------------------------------- the returning Customer (ADR-0007)
    //
    // A Customer is identified by phone alone and findOrCreate returns a match untouched, so the
    // email in the request body is not necessarily the address the confirmation goes to — and is
    // not necessarily an address at all. Both tests below book the same CUSTOMER_PHONE twice,
    // which is what every helper in this class already does.

    @Test
    @DisplayName("a returning number keeps its stored address, and the response still says a confirmation went")
    void a_returning_customer_is_mailed_at_the_address_on_file() {
        // First booking creates the Customer, and with it the stored address.
        book(scenario.at(scenario.monday, 10, 0), "ada@example.test");

        // Second booking, same phone, a different address typed into the form.
        ResponseEntity<String> response = book(scenario.at(scenario.monday, 11, 0), "typed@example.test");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // What was actually enqueued went to the address on file. This is the behaviour, not a
        // defect — the same rule that refuses to rename a Customer from a public form.
        assertThat(recipientEmails()).containsOnly("ada@example.test").doesNotContain("typed@example.test");

        // So the screen may promise a confirmation — but it learns that from here rather than from
        // what it typed, and it must not name an address.
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.confirmationSent")).isTrue();
        assertThat(response.getBody()).doesNotContain("ada@example.test");
    }

    @Test
    @DisplayName("a returning number with no stored address is mailed nothing, and the response admits it")
    void a_returning_customer_without_a_stored_address_is_not_mailed() {
        // First booking gives no email, so the Customer is stored without one.
        book(scenario.at(scenario.monday, 10, 0), null);

        // Second booking supplies one. findOrCreate discards it, so the Customer still has none.
        ResponseEntity<String> response = book(scenario.at(scenario.monday, 11, 0), "typed@example.test");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Nothing was enqueued: no confirmation, and no reminder either. This is the case the
        // confirmation screen used to promise an email for.
        assertThat(pendingNotificationTypes()).isEmpty();
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.confirmationSent")).isFalse();
    }

    @Test
    @DisplayName("losing the race for a slot is a 409, not a 500")
    void a_taken_slot_is_refused_with_slot_unavailable() {
        OffsetDateTime contested = scenario.at(scenario.monday, 13, 0);
        assertThat(book(contested, "first@example.test").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = book(contested, "second@example.test");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"code\":\"SLOT_UNAVAILABLE\"");
    }

    @Test
    @DisplayName("an inactive service, an inactive employee and an unassigned one each say why")
    void each_refusal_carries_its_own_code() {
        OffsetDateTime when = scenario.at(scenario.monday, 14, 0);

        String unassigned = BookingScenario.createEmployee(scenario.owner, "Dato Kapanadze");
        BookingScenario.setSchedule(scenario.owner, unassigned, "09:00", "17:00");
        assertThat(book(when, scenario.serviceId, unassigned, "ana@example.test").getBody())
                .contains("\"code\":\"EMPLOYEE_CANNOT_PERFORM_SERVICE\"");

        scenario.owner.post("/employees/" + scenario.employeeId + "/deactivate", Map.of());
        assertThat(book(when, "ana@example.test").getBody()).contains("\"code\":\"EMPLOYEE_INACTIVE\"");

        scenario.owner.post("/employees/" + scenario.employeeId + "/activate", Map.of());
        scenario.owner.post("/services/" + scenario.serviceId + "/deactivate", Map.of());
        assertThat(book(when, "ana@example.test").getBody()).contains("\"code\":\"SERVICE_INACTIVE\"");
    }

    @Test
    @DisplayName("a bad phone number is reported under the name the request used for it")
    void a_field_error_names_the_field_that_was_sent() {
        Map<String, Object> body = bookingBody(scenario.at(scenario.monday, 15, 0), scenario.serviceId, scenario.employeeId, null);
        body.put("customer", Map.of("fullName", "Ana", "phone", "not-a-number"));

        ResponseEntity<String> response = stranger.post("/public/businesses/" + slug + "/appointments", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // customer.phone, because that is what this request called it. Reported as "phone" — the
        // domain's own word, which is what phase 06 shipped — it would match no input on the page
        // and the one sentence explaining the fix would never be shown.
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field"))
                .containsExactly("customer.phone");

        // And the sentence itself has to be one a Customer can act on. This scenario's Business has
        // no country — registration leaves it unset — which is the branch that used to answer "set
        // your country in Settings": a screen the person booking has no account for. Proved here
        // rather than only in PhoneFieldTest because what can go wrong is this controller handing
        // over the wrong CustomerFieldNames, which no unit test of the copy would notice.
        String message = JsonPath.read(response.getBody(), "$.errors[0].message");
        assertThat(message).doesNotContain("Settings");
        assertThat(message).contains("+");
    }

    // ------------------------------------------------------------ unknown slug

    @Test
    @DisplayName("an unknown slug is a 404 on every public path, before any other work")
    void an_unknown_slug_is_not_found_everywhere() {
        String missing = "/public/businesses/no-such-business";

        assertThat(stranger.get(missing).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger.get(missing + "/services").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger.get(missing + "/employees").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger
                        .get(missing + "/availability?serviceId=" + scenario.serviceId + "&from="
                                + scenario.monday + "&to=" + scenario.monday)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The write too, and this is the one that matters: refused by the filter before the body is
        // read, so a booking cannot be written into a tenant that does not exist.
        ResponseEntity<String> booking = stranger.post(
                missing + "/appointments",
                bookingBody(scenario.at(scenario.monday, 16, 0), scenario.serviceId, scenario.employeeId, null));
        assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(booking.getBody()).contains("\"code\":\"NOT_FOUND\"");
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<String> book(OffsetDateTime startsAt, String email) {
        return book(startsAt, scenario.serviceId, scenario.employeeId, email);
    }

    private ResponseEntity<String> book(OffsetDateTime startsAt, String serviceId, String employeeId, String email) {
        return stranger.post(
                "/public/businesses/" + slug + "/appointments",
                bookingBody(startsAt, serviceId, employeeId, email));
    }

    private Map<String, Object> bookingBody(
            OffsetDateTime startsAt, String serviceId, String employeeId, String email) {
        Map<String, Object> customer = new HashMap<>();
        customer.put("fullName", "Ana Tsereteli");
        customer.put("phone", BookingScenario.CUSTOMER_PHONE);
        if (email != null) {
            customer.put("email", email);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", serviceId);
        body.put("employeeId", employeeId);
        body.put("startsAt", startsAt.toString());
        body.put("customer", customer);
        return body;
    }

    /**
     * The outbox, read in SQL.
     *
     * <p>It has no HTTP surface and should not grow one: the rows are the application's own
     * bookkeeping, not something a Customer or an owner asks for. Reading the table directly is the
     * only honest way to assert that a booking wrote them.
     */
    private List<String> pendingNotificationTypes() {
        return jdbc.queryForList("select type from notifications", String.class);
    }

    /** Who the outbox is actually addressed to, which is not always who the request named. */
    private List<String> recipientEmails() {
        return jdbc.queryForList("select distinct recipient_email from notifications", String.class);
    }
}
