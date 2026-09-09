package dev.reception.notifications;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A real Appointment with an empty outbox, plus a way to put arbitrary rows in it.
 *
 * <p>The Appointment is booked over HTTP rather than inserted, because a hand-written row would have
 * to satisfy composite foreign keys into four tables and would drift the first time one of them
 * changes. Its own two notification rows are then deleted: the dispatcher tests are about
 * {@code attempts}, {@code scheduled_for} and {@code status}, and reaching a fifth failed attempt
 * through the booking API would mean building a business in order to say nothing about businesses.
 *
 * <p>{@link #row} writes straight to the table on purpose. It is the only way to produce the states
 * this phase has to handle and no code path can reach — a row that has already failed four times, or
 * one whose {@code scheduled_for} is two hours out.
 */
final class OutboxFixture {

    static final String RECIPIENT = "ana@example.test";

    private OutboxFixture() {}

    /** Books one appointment, empties the outbox it filled, and returns its id. */
    static UUID appointmentWithEmptyOutbox(TestRestTemplate rest, int port, Clock clock, JdbcTemplate jdbc) {
        BookingScenario aria = BookingScenario.open(rest, port, clock);
        ResponseEntity<String> booked = aria.book(
                aria.at(aria.monday, 10, 0), aria.employeeId, "Ana Tsereteli", BookingScenario.CUSTOMER_PHONE, RECIPIENT);
        if (!booked.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Fixture could not book: " + booked.getBody());
        }
        String id = JsonPath.read(booked.getBody(), "$.appointment.id");
        jdbc.update("delete from notifications");
        return UUID.fromString(id);
    }

    /** One outbox row in exactly the state a test needs, bodies included so the send is real. */
    static UUID row(
            JdbcTemplate jdbc,
            Clock clock,
            UUID appointmentId,
            NotificationType type,
            Instant scheduledFor,
            int attempts,
            String recipient) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into notifications
                  (id, business_id, appointment_id, type, channel, recipient_email,
                   subject, body_html, body_text, scheduled_for, status, attempts, created_at)
                select ?, a.business_id, a.id, ?, 'EMAIL', ?,
                       ?, ?, ?, ?, 'PENDING', ?, ?
                  from appointments a where a.id = ?
                """,
                id,
                type.name(),
                recipient,
                "Subject for " + type,
                "<p>Body for " + type + "</p>",
                "Body for " + type,
                java.sql.Timestamp.from(scheduledFor),
                attempts,
                java.sql.Timestamp.from(clock.instant()),
                appointmentId);
        return id;
    }
}
