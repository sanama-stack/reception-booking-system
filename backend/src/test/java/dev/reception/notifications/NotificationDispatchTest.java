package dev.reception.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The send side: what the dispatcher does with a batch, and what it does when the transport says no.
 *
 * <p>{@link EmailSender} is replaced with a mock, which is the entire reason it is a port. A real
 * SMTP server can be made to accept a message and cannot be made to refuse one on the third attempt
 * and accept it on the fourth — and the retry path is where the interesting behaviour is.
 * {@code NotificationDeliveryTest} covers the real transport.
 *
 * <p>Rows are inserted directly rather than booked over HTTP. Everything here is about
 * {@code attempts}, {@code scheduled_for} and {@code status}, and reaching a fifth failed attempt
 * through the booking API would mean building a business to say nothing about businesses.
 */
class NotificationDispatchTest extends IntegrationTest {

    @MockitoBean
    private EmailSender email;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private UUID appointmentId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        appointmentId = OutboxFixture.appointmentWithEmptyOutbox(rest, port, clock, jdbc);
    }

    @Test
    @DisplayName("a due row is sent and marked SENT with a timestamp")
    void due_rows_go_out() {
        UUID id = enqueue(NotificationType.BOOKING_CONFIRMATION, clock.instant().minusSeconds(5), 0);

        assertThat(dispatcher.dispatchDueBatch()).isEqualTo(1);

        verify(email).send(eq("ana@example.test"), any(RenderedEmail.class));
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(sentAtOf(id)).isNotNull();
        assertThat(attemptsOf(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a row scheduled in the future is left alone")
    void future_rows_are_not_claimed() {
        UUID id = enqueue(NotificationType.REMINDER_24H, clock.instant().plus(2, ChronoUnit.HOURS), 0);

        assertThat(dispatcher.dispatchDueBatch()).isZero();

        verify(email, never()).send(any(), any());
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(attemptsOf(id)).isZero();
    }

    @Test
    @DisplayName("a failure counts the attempt, records the reason and backs off")
    void a_failed_send_is_recorded_and_rescheduled() {
        Instant due = clock.instant().minusSeconds(5);
        UUID id = enqueue(NotificationType.BOOKING_CONFIRMATION, due, 0);
        doThrow(new EmailDeliveryException("MailSendException: Connection refused", null))
                .when(email)
                .send(any(), any());

        assertThat(dispatcher.dispatchDueBatch()).isZero();

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(attemptsOf(id)).isEqualTo(1);
        assertThat(lastErrorOf(id)).contains("Connection refused");
        // One minute after the failure, which is RetryBackoff's first step. Asserted as "later than
        // it was due" rather than to the second, because the dispatcher's clock reading and the
        // test's are not the same instant.
        assertThat(scheduledForOf(id)).isAfter(due.plus(50, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("the fifth failure is the last: FAILED, and never claimed again")
    void retries_stop_at_the_cap() {
        UUID id = enqueue(
                NotificationType.BOOKING_CONFIRMATION,
                clock.instant().minusSeconds(5),
                RetryBackoff.MAX_ATTEMPTS - 1);
        doThrow(new EmailDeliveryException("SMTPSendFailedException: 550 mailbox unavailable", null))
                .when(email)
                .send(any(), any());

        dispatcher.dispatchDueBatch();

        assertThat(attemptsOf(id)).isEqualTo(RetryBackoff.MAX_ATTEMPTS);
        assertThat(statusOf(id)).isEqualTo("FAILED");

        // The claim query only ever asks for PENDING, so a second poll must find nothing at all.
        assertThat(dispatcher.dispatchDueBatch()).isZero();
        assertThat(attemptsOf(id)).isEqualTo(RetryBackoff.MAX_ATTEMPTS);
    }

    /**
     * <strong>The property "a failing row never blocks the others" actually means this.</strong> The
     * whole batch is one transaction, so an exception allowed to escape would roll back the SENT
     * marks of every row that did go out — and they would all be sent a second time on the next poll.
     */
    @Test
    @DisplayName("one poisoned row does not stop the rest of the batch")
    void a_failing_row_does_not_take_the_batch_with_it() {
        UUID poisoned = enqueue(NotificationType.BOOKING_CONFIRMATION, clock.instant().minusSeconds(9), 0, "bad@example.test");
        UUID first = enqueue(NotificationType.REMINDER_24H, clock.instant().minusSeconds(8), 0, "one@example.test");
        UUID second = enqueue(NotificationType.CANCELLATION, clock.instant().minusSeconds(7), 0, "two@example.test");

        doNothing().when(email).send(any(), any());
        doThrow(new EmailDeliveryException("MailSendException: refused", null))
                .when(email)
                .send(eq("bad@example.test"), any());

        assertThat(dispatcher.dispatchDueBatch()).isEqualTo(2);

        assertThat(statusOf(poisoned)).isEqualTo("PENDING");
        assertThat(attemptsOf(poisoned)).isEqualTo(1);
        assertThat(statusOf(first)).isEqualTo("SENT");
        assertThat(statusOf(second)).isEqualTo("SENT");
    }

    /**
     * The {@code ORDER BY scheduled_for} in the claim query, which is what stops a backlog of old
     * rows being starved by newer ones arriving faster than a batch drains.
     *
     * <p>Five {@code CANCELLATION} rows, because that type is deliberately outside the live unique
     * index — one appointment can be cancelled, rebooked and cancelled again. Trying this with five
     * confirmations is what the index exists to refuse, and it does.
     */
    @Test
    @DisplayName("the batch goes out oldest first")
    void the_claim_is_ordered() {
        Instant base = clock.instant().minus(10, ChronoUnit.HOURS);
        for (int i = 0; i < 5; i++) {
            enqueue(NotificationType.CANCELLATION, base.plusSeconds(i * 60L), 0, "queue" + i + "@example.test");
        }

        assertThat(dispatcher.dispatchDueBatch()).isEqualTo(5);

        InOrder byDueTime = inOrder(email);
        for (int i = 0; i < 5; i++) {
            byDueTime.verify(email).send(eq("queue" + i + "@example.test"), any(RenderedEmail.class));
        }
        byDueTime.verifyNoMoreInteractions();
    }

    // --- helpers ------------------------------------------------------------

    private UUID enqueue(NotificationType type, Instant scheduledFor, int attempts) {
        return enqueue(type, scheduledFor, attempts, "ana@example.test");
    }

    private UUID enqueue(NotificationType type, Instant scheduledFor, int attempts, String recipient) {
        return OutboxFixture.row(jdbc, clock, appointmentId, type, scheduledFor, attempts, recipient);
    }

    private String statusOf(UUID id) {
        return one(id, "status", String.class);
    }

    private Integer attemptsOf(UUID id) {
        return one(id, "attempts", Integer.class);
    }

    private String lastErrorOf(UUID id) {
        return one(id, "last_error", String.class);
    }

    private Instant sentAtOf(UUID id) {
        return one(id, "sent_at", Instant.class);
    }

    private Instant scheduledForOf(UUID id) {
        return one(id, "scheduled_for", Instant.class);
    }

    private <T> T one(UUID id, String column, Class<T> type) {
        return jdbc.queryForObject("select " + column + " from notifications where id = ?", type, id);
    }
}
