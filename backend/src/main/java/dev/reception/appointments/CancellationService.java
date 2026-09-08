package dev.reception.appointments;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calling an Appointment off, and the one rule that differs depending on who is doing it.
 *
 * <p>A cancellation releases the Employee's time the instant it is written — that is the partial
 * {@code WHERE status = 'CONFIRMED'} on the exclusion constraint doing its work, not application
 * code deciding to free a slot.
 */
@Service
public class CancellationService {

    private final AppointmentRepository appointments;
    private final AppointmentLookup lookup;
    private final CancellationWindow window;
    private final AppointmentEventRecorder events;
    private final Clock clock;

    public CancellationService(
            AppointmentRepository appointments,
            AppointmentLookup lookup,
            CancellationWindow window,
            AppointmentEventRecorder events,
            Clock clock) {
        this.appointments = appointments;
        this.lookup = lookup;
        this.window = window;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Cancels, or does nothing if it is already cancelled.
     *
     * <p><strong>Idempotent</strong> (docs/04-api-overview.md §2). A repeated call returns the same
     * appointment, writes no second audit event and — from phase 07 — sends no second email. The
     * case is ordinary rather than exotic: a Customer taps a Manage Link twice, or a request times
     * out after the server committed and the client retries.
     *
     * <p>Idempotence is answered <em>before</em> the state machine is consulted, so the two never
     * have to agree about what "already cancelled" means. {@code AppointmentStatus} says a terminal
     * state is terminal; this says repeating the move you already made is not an error.
     */
    @Transactional
    public Appointment cancel(UUID id, Actor actor, String reason) {
        Appointment appointment = lookup.require(id);

        if (appointment.status() == AppointmentStatus.CANCELLED) {
            return appointment;
        }
        if (!appointment.status().canMoveTo(AppointmentStatus.CANCELLED)) {
            throw new ApiException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "This appointment is already marked %s and cannot be cancelled."
                            .formatted(appointment.status().name().toLowerCase().replace('_', ' ')));
        }

        CancelledBy by = actor.asCancellingParty();
        if (by == CancelledBy.CUSTOMER) {
            window.requireOpenFor(appointment);
        }

        appointment.cancel(by, blankToNull(reason), clock.instant());
        // saveAndFlush so the release of the time is visible to anything later in this transaction —
        // and so an optimistic-lock failure is raised here rather than at commit, where no handler
        // would know what it referred to.
        Appointment cancelled = appointments.saveAndFlush(appointment);
        events.cancelled(cancelled, actor);
        return cancelled;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
