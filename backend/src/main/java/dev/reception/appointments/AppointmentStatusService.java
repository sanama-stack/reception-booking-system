package dev.reception.appointments;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marking what actually happened: the Customer came, or they did not.
 *
 * <p>Thin on purpose. The rule about which moves are legal lives in {@link AppointmentStatus}, in
 * one table, and this class does nothing but consult it and record the result — which is what keeps
 * "can a no-show be completed later?" a question with one answer rather than three.
 *
 * <p>Cancelling is <em>not</em> done here even though it is a status change, because it carries
 * rules this does not: who cancelled, whether the window is open, and idempotence. See
 * {@link CancellationService}.
 */
@Service
public class AppointmentStatusService {

    private final AppointmentRepository appointments;
    private final AppointmentLookup lookup;
    private final AppointmentEventRecorder events;
    private final Clock clock;

    public AppointmentStatusService(
            AppointmentRepository appointments,
            AppointmentLookup lookup,
            AppointmentEventRecorder events,
            Clock clock) {
        this.appointments = appointments;
        this.lookup = lookup;
        this.events = events;
        this.clock = clock;
    }

    /** @param next {@code COMPLETED} or {@code NO_SHOW}; anything else is refused here */
    @Transactional
    public Appointment moveTo(UUID id, AppointmentStatus next, Actor actor) {
        if (next == AppointmentStatus.CANCELLED) {
            // Refused rather than delegated. A cancellation needs a cancelling party and a window
            // check that this call has no way to supply, and writing one without them produces a row
            // the database rejects (appointments_cancel_fields) — a 500 where an explanation belongs.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "One or more fields are invalid.",
                    List.of(new FieldError("status", "Use the cancel action to cancel an appointment.")));
        }

        Appointment appointment = lookup.require(id);
        AppointmentStatus from = appointment.status();

        if (!from.canMoveTo(next)) {
            throw new ApiException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "An appointment marked %s cannot be marked %s.".formatted(readable(from), readable(next)));
        }

        appointment.moveToStatus(next, clock.instant());
        Appointment saved = appointments.saveAndFlush(appointment);
        events.statusChanged(saved, from, actor);
        return saved;
    }

    private static String readable(AppointmentStatus status) {
        return status.name().toLowerCase().replace('_', ' ');
    }
}
