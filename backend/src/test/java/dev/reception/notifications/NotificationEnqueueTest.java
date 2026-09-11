package dev.reception.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
 * What ends up in the outbox when an owner books, cancels and moves appointments.
 *
 * <p>Driven entirely over HTTP and asserted against the table. Calling
 * {@code NotificationEnqueuer} directly would need a tenant context stood up by hand and would skip
 * the transaction boundary that is the entire subject — the point is not that the enqueuer can write
 * a row, it is that the row commits with the booking and only with the booking.
 */
class NotificationEnqueueTest extends IntegrationTest {

    private static final String CUSTOMER_EMAIL = "ana@example.test";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private BookingScenario aria;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
    }

    @Test
    @DisplayName("a booking enqueues exactly two rows, in the booking's own transaction")
    void a_booking_enqueues_a_confirmation_and_a_reminder() {
        String id = bookWithEmail(aria.at(aria.monday, 10, 0));

        assertThat(typesFor(id)).containsExactlyInAnyOrder("BOOKING_CONFIRMATION", "REMINDER_24H");
        assertThat(statusesFor(id)).containsOnly("PENDING");
        assertThat(recipientsFor(id)).containsOnly(CUSTOMER_EMAIL);
    }

    /**
     * The reminder's schedule, read straight out of the column. Asserted as a difference rather than
     * an absolute so the test does not have to restate the fixture's arithmetic.
     */
    @Test
    void the_reminder_is_scheduled_twenty_four_hours_before_the_start() {
        OffsetDateTime startsAt = aria.at(aria.monday, 10, 0);
        String id = bookWithEmail(startsAt);

        Long gapSeconds = jdbc.queryForObject(
                """
                select extract(epoch from (a.starts_at - n.scheduled_for))::bigint
                  from notifications n join appointments a on a.id = n.appointment_id
                 where n.appointment_id = ?::uuid and n.type = 'REMINDER_24H'
                """,
                Long.class,
                id);

        assertThat(gapSeconds).isEqualTo(24 * 60 * 60);
    }

    /**
     * <strong>The branch that runs constantly in this domain.</strong> Same-day bookings are normal,
     * and a reminder whose moment has passed would be sent by the very next poll — "your appointment
     * is tomorrow", about an appointment in two hours.
     */
    @Test
    @DisplayName("an appointment less than 24 hours away gets a confirmation and no reminder")
    void no_reminder_is_scheduled_when_it_would_already_be_due() {
        // The fixture opens itself around a time a few hours out, because what is being tested is
        // the lead-time branch and not whether the suite happens to be running inside the business's
        // opening hours — or on one of the days it opens at all.
        OffsetDateTime soon = aStartInsideTwentyFourHours();
        String id = bookAt(soon);

        assertThat(typesFor(id)).containsExactly("BOOKING_CONFIRMATION");
    }

    @Test
    @DisplayName("a booking with no email enqueues nothing and still succeeds")
    void a_customer_without_an_email_gets_no_rows_and_keeps_their_appointment() {
        ResponseEntity<String> response =
                aria.book(aria.at(aria.monday, 11, 0), aria.employeeId, "Giorgi", "+995555999888", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = JsonPath.read(response.getBody(), "$.appointment.id");
        assertThat(typesFor(id)).isEmpty();
        // The Confirmation Code is still issued — it is what the owner reads out over the phone.
        assertThat((String) JsonPath.read(response.getBody(), "$.appointment.confirmationCode"))
                .isNotBlank();
    }

    /**
     * The other half of "in the same transaction". A booking that the exclusion constraint refuses
     * must leave no trace in the outbox — otherwise the poller would mail a confirmation for an
     * appointment that does not exist.
     */
    @Test
    @DisplayName("a refused booking enqueues nothing")
    void a_rolled_back_booking_leaves_no_rows() {
        OffsetDateTime contested = aria.at(aria.monday, 12, 0);
        bookWithEmail(contested);

        ResponseEntity<String> second =
                aria.book(contested, aria.employeeId, "Someone Else", "+995555777666", "else@example.test");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Two rows in total — the first booking's — and none belonging to the refused one.
        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("cancelling supersedes the pending rows and enqueues a cancellation")
    void cancelling_cancels_what_was_pending() {
        String id = bookWithEmail(aria.at(aria.monday, 13, 0));

        aria.owner.post("/appointments/" + id + "/cancel", Map.of("reason", "Closed for the day"));

        assertThat(rowsFor(id))
                .containsExactlyInAnyOrder(
                        Map.entry("BOOKING_CONFIRMATION", "CANCELLED"),
                        Map.entry("REMINDER_24H", "CANCELLED"),
                        Map.entry("CANCELLATION", "PENDING"));
    }

    /** The idempotence guard in {@code CancellationService}, seen from the outbox. */
    @Test
    @DisplayName("cancelling twice does not enqueue a second email")
    void a_repeated_cancellation_enqueues_nothing_further() {
        String id = bookWithEmail(aria.at(aria.monday, 13, 30));
        aria.owner.post("/appointments/" + id + "/cancel", Map.of("reason", "First"));

        ResponseEntity<String> again = aria.owner.post("/appointments/" + id + "/cancel", Map.of("reason", "Second"));

        assertThat(again.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(typesFor(id).stream().filter("CANCELLATION"::equals)).hasSize(1);
    }

    @Test
    @DisplayName("a reschedule supersedes the old reminder, schedules a new one, and says so")
    void rescheduling_moves_the_reminder_with_the_appointment() {
        String id = bookWithEmail(aria.at(aria.monday, 14, 0));

        aria.owner.post(
                "/appointments/" + id + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday.plusDays(1), 15, 0).toString()));

        assertThat(rowsFor(id))
                .containsExactlyInAnyOrder(
                        Map.entry("BOOKING_CONFIRMATION", "PENDING"),
                        Map.entry("REMINDER_24H", "CANCELLED"),
                        Map.entry("REMINDER_24H", "PENDING"),
                        Map.entry("RESCHEDULE", "PENDING"));

        Long gapSeconds = jdbc.queryForObject(
                """
                select extract(epoch from (a.starts_at - n.scheduled_for))::bigint
                  from notifications n join appointments a on a.id = n.appointment_id
                 where n.appointment_id = ?::uuid and n.type = 'REMINDER_24H' and n.status = 'PENDING'
                """,
                Long.class,
                id);
        assertThat(gapSeconds).isEqualTo(24 * 60 * 60);
    }

    /**
     * The sibling of the test above, on the path ADR-0008 opened.
     *
     * <p>A Customer whose address is cleared between booking and moving gets no reschedule email,
     * and that much is deliberate. The reminder is not. {@code appointmentRescheduled} returned on
     * {@code !hasEmail()} <strong>before</strong> superseding it, so the row minted against the old
     * start time survived {@code PENDING} — aimed at a moment the appointment had left, carrying a
     * body that still named the old time. {@code recipientEmail} is copied at enqueue and is not
     * updatable, so the address being gone does not stop the poller sending it.
     *
     * <p>{@code appointmentCancelled} supersedes before it checks for an address, and this is the
     * same shape. Nothing replaces the superseded row: a reminder needs a recipient, and there is
     * none — no reminder is the honest outcome, and a wrong one is not.
     */
    @Test
    @DisplayName("a reschedule supersedes the old reminder even with no address on file")
    void rescheduling_without_an_address_still_supersedes_the_reminder() {
        String id = bookWithEmail(aria.at(aria.monday, 14, 30));
        clearTheAddressBookedWith(id);

        aria.owner.post(
                "/appointments/" + id + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday.plusDays(1), 15, 30).toString()));

        assertThat(rowsFor(id))
                .containsExactlyInAnyOrder(
                        Map.entry("BOOKING_CONFIRMATION", "PENDING"),
                        Map.entry("REMINDER_24H", "CANCELLED"));
    }

    /**
     * <strong>The reason the partial unique index carries a type predicate.</strong> Two moves owe
     * two emails; an index over every type would have refused the second insert and taken the whole
     * reschedule down with it. See the note in {@code V6__notifications.sql}.
     */
    @Test
    @DisplayName("rescheduling twice sends two reschedule emails")
    void a_second_move_is_a_second_fact() {
        String id = bookWithEmail(aria.at(aria.monday, 15, 0));

        aria.owner.post(
                "/appointments/" + id + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday.plusDays(1), 10, 0).toString()));
        ResponseEntity<String> secondMove = aria.owner.post(
                "/appointments/" + id + "/reschedule",
                Map.of("startsAt", aria.at(aria.monday.plusDays(2), 10, 0).toString()));

        assertThat(secondMove.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(typesFor(id).stream().filter("RESCHEDULE"::equals)).hasSize(2);
    }

    /**
     * The index itself, exercised directly rather than through a path that happens to trip it.
     * Everything above depends on this refusing; if it stopped refusing, they would all still pass.
     */
    @Test
    @DisplayName("a second live confirmation for one appointment is refused by the database")
    void the_partial_unique_index_refuses_a_duplicate() {
        String id = bookWithEmail(aria.at(aria.monday, 16, 0));

        Throwable duplicate = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                """
                insert into notifications
                  (id, business_id, appointment_id, type, channel, recipient_email,
                   subject, body_html, body_text, scheduled_for, status, attempts, created_at)
                select gen_random_uuid(), business_id, appointment_id, type, channel, recipient_email,
                       subject, body_html, body_text, scheduled_for, 'PENDING', 0, created_at
                  from notifications
                 where appointment_id = ?::uuid and type = 'BOOKING_CONFIRMATION'
                """,
                id));

        assertThat(duplicate).isNotNull();
        assertThat(duplicate.getMessage()).contains("notifications_live_type_idx");
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Clears the Customer's address the way a Business actually can — the dashboard {@code PATCH},
     * not a write straight at the column. The reachability of this state is half the point.
     */
    private void clearTheAddressBookedWith(String appointmentId) {
        UUID customerId = jdbc.queryForObject(
                "select customer_id from appointments where id = ?", UUID.class, UUID.fromString(appointmentId));
        ResponseEntity<String> cleared = aria.owner.patch("/customers/" + customerId, Map.of("email", ""));
        if (!cleared.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Fixture could not clear the address: " + cleared.getBody());
        }
    }

    private String bookWithEmail(OffsetDateTime startsAt) {
        return bookAt(startsAt);
    }

    private String bookAt(OffsetDateTime startsAt) {
        ResponseEntity<String> response =
                aria.book(startsAt, aria.employeeId, "Ana Tsereteli", BookingScenario.CUSTOMER_PHONE, CUSTOMER_EMAIL);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Fixture could not book: " + response.getBody());
        }
        return JsonPath.read(response.getBody(), "$.appointment.id");
    }

    /**
     * A bookable start inside twenty-four hours — <strong>made, not found</strong>.
     *
     * <p>This used to search the fixture's calendar forward an hour at a time, which cannot reach
     * Monday from a Friday afternoon: the business opens 09:00–17:00 Monday to Friday, so from
     * 15:00 on a Friday there is no bookable start in the next twenty-four hours at all, and the
     * test threw. Red every Friday after 15:00 and all weekend, for a branch that has nothing to do
     * with which day it is. The old version fixed the same failure one axis short — it survived any
     * hour of the day, and then met the week.
     *
     * <p>So the hours are moved to the appointment instead of the appointment to the hours: pick a
     * time two to three hours out and open the business, and the employee, across it. Replacing the
     * week leaves this business open on one day only, which is exactly as much calendar as the one
     * booking in this test needs.
     *
     * <p>The step past 22:00 is the one edge: an opening interval cannot span midnight, so a
     * candidate late enough for a sixty-minute appointment to cross it is moved to the next
     * morning — still comfortably inside the day this test is about.
     */
    private OffsetDateTime aStartInsideTwentyFourHours() {
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(BookingScenario.TBILISI));
        // Truncate first, then add: three whole hours from the top of this hour is never less than
        // the two the minimum lead time needs, whatever the minute hand says.
        OffsetDateTime candidate = now.truncatedTo(ChronoUnit.HOURS).plusHours(3);
        if (candidate.getHour() >= 22) {
            candidate = candidate.plusDays(1).withHour(9);
        }
        openAcross(candidate);
        return candidate;
    }

    /** The business and the one employee, open for the two hours around a candidate start. */
    private void openAcross(OffsetDateTime candidate) {
        int dayOfWeek = candidate.getDayOfWeek().getValue();
        String opensAt = "%02d:00".formatted(candidate.getHour());
        String closesAt = "%02d:00".formatted(candidate.getHour() + 2);

        ResponseEntity<String> hours = aria.owner.put(
                "/business/hours",
                Map.of("hours", List.of(Map.of("dayOfWeek", dayOfWeek, "opensAt", opensAt, "closesAt", closesAt))));
        ResponseEntity<String> schedule = aria.owner.put(
                "/employees/" + aria.employeeId + "/schedule",
                Map.of("schedule", List.of(Map.of("dayOfWeek", dayOfWeek, "startsAt", opensAt, "endsAt", closesAt))));

        if (!hours.getStatusCode().is2xxSuccessful() || !schedule.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "Fixture could not open the business: " + hours.getBody() + " / " + schedule.getBody());
        }
    }

    private List<String> typesFor(String appointmentId) {
        return jdbc.queryForList(
                "select type from notifications where appointment_id = ?::uuid", String.class, appointmentId);
    }

    private List<String> statusesFor(String appointmentId) {
        return jdbc.queryForList(
                "select status from notifications where appointment_id = ?::uuid", String.class, appointmentId);
    }

    private List<String> recipientsFor(String appointmentId) {
        return jdbc.queryForList(
                "select recipient_email from notifications where appointment_id = ?::uuid",
                String.class,
                appointmentId);
    }

    private List<Map.Entry<String, String>> rowsFor(String appointmentId) {
        return jdbc.query(
                "select type, status from notifications where appointment_id = ?::uuid",
                (rs, i) -> Map.entry(rs.getString("type"), rs.getString("status")),
                appointmentId);
    }
}
