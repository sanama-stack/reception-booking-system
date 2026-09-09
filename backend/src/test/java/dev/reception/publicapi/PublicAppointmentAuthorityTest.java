package dev.reception.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.notifications.ManageTokenService;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * The two proofs a Customer can offer, and everything they must not buy.
 *
 * <p>This is the security half of phase 08. A Customer has no account, so an Appointment's whole
 * defence is a Confirmation Code checked together with a phone number, or a signed Manage Link that
 * authorises exactly one appointment (docs/06-security.md §6).
 */
class PublicAppointmentAuthorityTest extends IntegrationTest {

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

    @Autowired
    private ManageTokenService manageTokens;

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

    // ---------------------------------------------------------------- lookup

    @Test
    @DisplayName("the code and the phone number together resolve the appointment")
    void lookup_with_code_and_phone_succeeds() {
        Booking booking = book(scenario.at(scenario.monday, 10, 0));

        ResponseEntity<String> response = lookup(booking.code(), BookingScenario.CUSTOMER_PHONE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.id")).isEqualTo(booking.id());
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<String>read(response.getBody(), "$.business.name")).isEqualTo("Salon Aria");
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.canCancel")).isTrue();
    }

    @Test
    @DisplayName("the right code with the wrong number proves nothing")
    void the_code_alone_is_not_enough() {
        Booking booking = book(scenario.at(scenario.monday, 10, 30));

        ResponseEntity<String> response = lookup(booking.code(), "+995555999888");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"INVALID_CONFIRMATION_CODE\"");
        // The same code an unknown confirmation code produces. A response that distinguished them
        // would answer the question a brute-forcer is asking.
        assertThat(lookup("ZZZZZZZZ", BookingScenario.CUSTOMER_PHONE).getBody())
                .contains("\"code\":\"INVALID_CONFIRMATION_CODE\"");
    }

    @Test
    @DisplayName("a phone number on its own is refused by the schema, before it costs an attempt")
    void the_phone_alone_is_a_schema_rejection() {
        ResponseEntity<String> response =
                stranger.post("/public/appointments/lookup", Map.of("phone", BookingScenario.CUSTOMER_PHONE));

        // 422, not 401: it never reaches the lookup, so it never spends from the five-an-hour budget
        // that exists for guesses which could conceivably be right.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    @Test
    @DisplayName("a lower-case code typed off a phone screen still works")
    void the_code_is_matched_case_insensitively() {
        Booking booking = book(scenario.at(scenario.monday, 11, 0));

        assertThat(lookup(booking.code().toLowerCase(), BookingScenario.CUSTOMER_PHONE)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a local number is normalised against the business that issued the code")
    void the_phone_is_normalised_per_candidate_business() {
        scenario.owner.patch("/business", Map.of("country", "GE"));
        Booking booking = book(scenario.at(scenario.monday, 11, 30));

        // The same human number, typed the way a Georgian customer would type it. There is no slug
        // on this path, so the country cannot be looked up before the code is — it travels with the
        // candidate instead.
        assertThat(lookup(booking.code(), "555 12 34 56").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------- manage link

    @Test
    @DisplayName("the token from the confirmation email opens that appointment and no other")
    void the_emailed_manage_link_resolves() {
        Booking booking = book(scenario.at(scenario.monday, 12, 0));

        // Read out of the outbox row the booking wrote, not minted by the test. This is the link a
        // customer actually receives.
        String token = tokenFrom(confirmationBodyFor(booking.id()));

        ResponseEntity<String> response = stranger.get("/public/appointments/manage?token=" + token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.id")).isEqualTo(booking.id());
        assertThat(JsonPath.<String>read(response.getBody(), "$.confirmationCode")).isEqualTo(booking.code());
    }

    @Test
    @DisplayName("a tampered, expired or foreign token is one indistinguishable 401")
    void a_token_that_authorises_nothing_says_only_that() {
        Booking booking = book(scenario.at(scenario.monday, 12, 30));
        String token = tokenFrom(confirmationBodyFor(booking.id()));

        // Same payload, one byte of signature changed.
        //
        // Flipped in the middle of the signature, never at its end. An HMAC-SHA256 is 32 bytes,
        // which is 42 full base64url characters plus a 43rd carrying only four significant bits —
        // so four different final characters all decode to the same 32 bytes and "tamper with the
        // last character" tampers with nothing. Written that way this assertion passed for a token
        // that was still perfectly valid, and would have gone on passing if verification broke.
        String tampered = flipCharAt(token, token.indexOf('.') + 5);
        // Correctly signed, and expired an hour ago.
        String expired = manageTokens.issue(
                UUID.fromString(booking.id()), Instant.now(clock).minusSeconds(60 * 60 * 25));
        // Names an appointment that does not exist.
        String orphan = manageTokens.issue(UUID.randomUUID(), Instant.now(clock).plusSeconds(86_400));

        for (String bad : List.of(tampered, expired, orphan, "not-a-token", "a.b")) {
            ResponseEntity<String> response = stranger.get("/public/appointments/manage?token=" + bad);
            assertThat(response.getStatusCode())
                    .describedAs("token %s", bad)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("\"code\":\"MANAGE_TOKEN_INVALID\"");
            // The token is a capability, and an error message is one of the places one leaks.
            assertThat(response.getBody()).doesNotContain(bad);
        }
    }

    @Test
    @DisplayName("a token for one appointment cannot act on another")
    void a_token_for_a_is_refused_on_bs_path() {
        Booking mine = book(scenario.at(scenario.monday, 13, 0));
        Booking theirs = book(scenario.at(scenario.monday, 14, 0), "+995555777666");

        String myToken = tokenFrom(confirmationBodyFor(mine.id()));

        ResponseEntity<String> response = stranger.post(
                "/public/appointments/" + theirs.id() + "/cancel",
                Map.of("authority", Map.of("manageToken", myToken)));

        // 404 rather than 403: "is not yours" and "does not exist" are indistinguishable everywhere
        // else, and a distinct code here would confirm that the other appointment exists.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // And it is still confirmed, which is the assertion that actually matters.
        assertThat(JsonPath.<String>read(
                        scenario.owner.get("/appointments/" + theirs.id()).getBody(), "$.appointment.status"))
                .isEqualTo("CONFIRMED");
    }

    // --------------------------------------------------------------- cancel

    @Test
    @DisplayName("cancelling outside the window succeeds and enqueues the cancellation email")
    void a_customer_can_cancel_outside_the_window() {
        Booking booking = book(scenario.at(scenario.monday, 15, 0));
        jdbc.update("delete from notifications");

        ResponseEntity<String> response = cancelWith(booking, Map.of("manageToken", tokenFor(booking)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.canCancel")).isFalse();
        assertThat(jdbc.queryForList("select type from notifications", String.class))
                .contains("CANCELLATION");
    }

    @Test
    @DisplayName("cancelling twice is the same answer and does not send a second email")
    void cancelling_is_idempotent() {
        Booking booking = book(scenario.at(scenario.monday, 15, 30));
        Map<String, Object> proof = Map.of("manageToken", tokenFor(booking));

        cancelWith(booking, proof);
        jdbc.update("delete from notifications");

        assertThat(cancelWith(booking, proof).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForList("select type from notifications", String.class))
                .isEmpty();
    }

    @Test
    @DisplayName("inside the window a customer is refused, and the business is not")
    void the_cancellation_window_binds_only_the_customer() {
        // A week is the longest window the settings allow, and the appointment below is at most
        // three days out — so it is inside the window on every day of the week this suite runs.
        scenario.owner.patch("/business", Map.of("cancellationWindowHours", 168));
        Booking booking = book(atNearWeekday());

        ResponseEntity<String> refused = cancelWith(booking, Map.of("manageToken", tokenFor(booking)));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody()).contains("\"code\":\"CANCELLATION_WINDOW_CLOSED\"");
        // The page is told before the customer presses anything, which is what lets it show the
        // business's own policy instead of an error.
        assertThat(JsonPath.<Boolean>read(
                        stranger.get("/public/appointments/manage?token=" + tokenFor(booking)).getBody(),
                        "$.canCancel"))
                .isFalse();

        // The same appointment, cancelled by the business from the dashboard. A Business is never
        // bound by its own customer-facing deadline (CONTEXT.md).
        assertThat(scenario.owner
                        .post("/appointments/" + booking.id() + "/cancel", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a confirmation code and phone number authorise a cancel just as a link does")
    void the_lookup_proof_also_cancels() {
        Booking booking = book(scenario.at(scenario.monday, 16, 0));

        ResponseEntity<String> response = cancelWith(
                booking,
                Map.of("confirmationCode", booking.code(), "phone", BookingScenario.CUSTOMER_PHONE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("presenting no proof at all is a validation failure")
    void an_empty_authority_is_refused() {
        // 09:00, not 16:30: a sixty-minute service starting at 16:30 ends after the 17:00 close and
        // is refused with OUTSIDE_BUSINESS_HOURS before this test reaches what it is about.
        Booking booking = book(scenario.at(scenario.monday, 9, 0));

        ResponseEntity<String> response = cancelWith(booking, new HashMap<>());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    // ----------------------------------------------------------- reschedule

    @Test
    @DisplayName("a customer moves the appointment in place, keeping its id and its code")
    void a_customer_can_reschedule() {
        Booking booking = book(scenario.at(scenario.monday, 10, 0));

        ResponseEntity<String> response = stranger.post(
                "/public/appointments/" + booking.id() + "/reschedule",
                Map.of(
                        "authority", Map.of("manageToken", tokenFor(booking)),
                        "startsAt", scenario.at(scenario.monday, 14, 0).toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The id and the code survive: the customer is holding an email with that code in it.
        assertThat(JsonPath.<String>read(response.getBody(), "$.id")).isEqualTo(booking.id());
        assertThat(JsonPath.<String>read(response.getBody(), "$.confirmationCode")).isEqualTo(booking.code());
        assertThat(JsonPath.<String>read(response.getBody(), "$.startsAt"))
                .isEqualTo(BookingScenario.wireTime(scenario.monday, 14, 0));
    }

    @Test
    @DisplayName("a reschedule onto a taken slot is refused and the appointment does not move")
    void a_reschedule_validates_availability() {
        Booking mine = book(scenario.at(scenario.monday, 10, 0));
        book(scenario.at(scenario.monday, 12, 0), "+995555777666");

        ResponseEntity<String> response = stranger.post(
                "/public/appointments/" + mine.id() + "/reschedule",
                Map.of(
                        "authority", Map.of("manageToken", tokenFor(mine)),
                        "startsAt", scenario.at(scenario.monday, 12, 0).toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"SLOT_UNAVAILABLE\"");
        assertThat(JsonPath.<String>read(
                        stranger.get("/public/appointments/manage?token=" + tokenFor(mine)).getBody(),
                        "$.startsAt"))
                .isEqualTo(BookingScenario.wireTime(scenario.monday, 10, 0));
    }

    @Test
    @DisplayName("the reschedule grid offers the customer their own time back")
    void manage_availability_excludes_the_appointment_being_moved() {
        Booking booking = book(scenario.at(scenario.monday, 10, 0));
        String token = tokenFor(booking);

        String publicGrid = stranger
                .get("/public/businesses/%s/availability?serviceId=%s&from=%s&to=%s"
                        .formatted(slug, scenario.serviceId, scenario.monday, scenario.monday))
                .getBody();
        String manageGrid = stranger
                .get("/public/appointments/manage/availability?token=%s&from=%s&to=%s"
                        .formatted(token, scenario.monday, scenario.monday))
                .getBody();

        String ownTime = BookingScenario.wireTime(scenario.monday, 10, 0);
        // The ordinary grid counts the appointment against itself; the token-authorised one does
        // not. Without this a customer could not move a 10:00 booking to 10:15 — their own buffers
        // would refuse the move they are being offered.
        assertThat(JsonPath.<List<String>>read(publicGrid, "$.days[0].slots[*].startsAt"))
                .doesNotContain(ownTime);
        assertThat(JsonPath.<List<String>>read(manageGrid, "$.days[0].slots[*].startsAt"))
                .contains(ownTime);
    }

    @Test
    @DisplayName("the reschedule grid cannot be pointed at somebody else's appointment")
    void manage_availability_takes_no_appointment_id_from_the_caller() {
        Booking mine = book(scenario.at(scenario.monday, 10, 0));
        Booking theirs = book(scenario.at(scenario.monday, 12, 0), "+995555777666");

        // The only handle this endpoint accepts is a token, and the token names the appointment.
        // There is no parameter that could name theirs — passing one changes nothing.
        String grid = stranger
                .get("/public/appointments/manage/availability?token=%s&from=%s&to=%s&excludeAppointmentId=%s"
                        .formatted(tokenFor(mine), scenario.monday, scenario.monday, theirs.id()))
                .getBody();

        assertThat(JsonPath.<List<String>>read(grid, "$.days[0].slots[*].startsAt"))
                .contains(BookingScenario.wireTime(scenario.monday, 10, 0))
                .doesNotContain(BookingScenario.wireTime(scenario.monday, 12, 0));
    }

    // ------------------------------------------------------------------ helpers

    private record Booking(String id, String code) {}

    private Booking book(OffsetDateTime startsAt) {
        return book(startsAt, BookingScenario.CUSTOMER_PHONE);
    }

    private Booking book(OffsetDateTime startsAt, String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", scenario.serviceId);
        body.put("employeeId", scenario.employeeId);
        body.put("startsAt", startsAt.toString());
        body.put("customer", Map.of("fullName", "Ana Tsereteli", "phone", phone, "email", "ana@example.test"));

        ResponseEntity<String> response =
                stranger.post("/public/businesses/" + slug + "/appointments", body);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Fixture could not book: " + response.getBody());
        }
        return new Booking(
                JsonPath.read(response.getBody(), "$.id"),
                JsonPath.read(response.getBody(), "$.confirmationCode"));
    }

    private ResponseEntity<String> lookup(String code, String phone) {
        return stranger.post("/public/appointments/lookup", Map.of("confirmationCode", code, "phone", phone));
    }

    private ResponseEntity<String> cancelWith(Booking booking, Map<String, Object> authority) {
        return stranger.post(
                "/public/appointments/" + booking.id() + "/cancel", Map.of("authority", authority));
    }

    /** Minted rather than read from the outbox, for the tests that are not about the email. */
    private String tokenFor(Booking booking) {
        Instant endsAt = jdbc.queryForObject(
                "select ends_at from appointments where id = ?", Instant.class, UUID.fromString(booking.id()));
        return manageTokens.issue(UUID.fromString(booking.id()), endsAt);
    }

    private String confirmationBodyFor(String appointmentId) {
        return jdbc.queryForObject(
                "select body_text from notifications where appointment_id = ? and type = 'BOOKING_CONFIRMATION'",
                String.class,
                UUID.fromString(appointmentId));
    }

    /** One character of a token replaced, so the signature it decodes to is genuinely different. */
    private static String flipCharAt(String token, int index) {
        char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
        return token.substring(0, index) + replacement + token.substring(index + 1);
    }

    /** The URL sits on its own line in the text part, after the "Change or cancel:" label. */
    private static String tokenFrom(String text) {
        String marker = "/manage/";
        int start = text.indexOf(marker) + marker.length();
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    /**
     * A start time on the next open weekday, at most three days out.
     *
     * <p>{@code BookingScenario.monday} is seven to thirteen days ahead depending on which day the
     * suite runs, which is no use for a test about a seven-day cancellation window. From any day of
     * the week there is a Monday-to-Friday within three, so this is inside a 168-hour window
     * always — never sometimes.
     */
    private OffsetDateTime atNearWeekday() {
        LocalDate date = LocalDate.now(clock.withZone(BookingScenario.TBILISI)).plusDays(1);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.atTime(12, 0).atZone(BookingScenario.TBILISI).toOffsetDateTime();
    }
}
