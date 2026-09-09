package dev.reception.appointments;

import dev.reception.common.ids.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one audit row per transition.
 *
 * <p><strong>{@code MANDATORY} propagation, on purpose.</strong> An event must be written in the
 * caller's transaction and no other: if the booking rolls back, the "created" event must vanish with
 * it, and if this ever gets called outside a transaction the failure should be loud rather than a
 * committed event describing something that did not happen.
 *
 * <p>Every method takes the same {@link Actor} the operation was performed by. Nothing here reads
 * the security context — see {@link Actor}.
 */
@Component
public class AppointmentEventRecorder {

    private final AppointmentEventRepository events;
    private final IdGenerator ids;
    private final Clock clock;

    public AppointmentEventRecorder(AppointmentEventRepository events, IdGenerator ids, Clock clock) {
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Appointment appointment, Actor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startsAt", appointment.startsAt().toString());
        payload.put("endsAt", appointment.endsAt().toString());
        payload.put("employeeId", appointment.employeeId().toString());
        payload.put("serviceId", appointment.serviceId().toString());
        payload.put("source", appointment.source().name());
        record(appointment, AppointmentEventType.CREATED, actor, payload);
    }

    /**
     * The old times as well as the new ones. Recording only the new ones would make the trail
     * useless for the question it exists to answer — a customer asking what their appointment
     * <em>was</em> before someone moved it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rescheduled(
            Appointment appointment, Instant previousStartsAt, Instant previousEndsAt, UUID previousEmployeeId, Actor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousStartsAt", previousStartsAt.toString());
        payload.put("previousEndsAt", previousEndsAt.toString());
        payload.put("previousEmployeeId", previousEmployeeId.toString());
        payload.put("startsAt", appointment.startsAt().toString());
        payload.put("endsAt", appointment.endsAt().toString());
        payload.put("employeeId", appointment.employeeId().toString());
        record(appointment, AppointmentEventType.RESCHEDULED, actor, payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelled(Appointment appointment, Actor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancelledBy", appointment.cancelledBy().name());
        if (appointment.cancellationReason() != null) {
            payload.put("reason", appointment.cancellationReason());
        }
        record(appointment, AppointmentEventType.CANCELLED, actor, payload);
    }

    /** {@code COMPLETED} or {@code NO_SHOW}, whose event type has the same name as the status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Appointment appointment, AppointmentStatus from, Actor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.name());
        payload.put("to", appointment.status().name());
        record(appointment, AppointmentEventType.valueOf(appointment.status().name()), actor, payload);
    }

    private void record(Appointment appointment, AppointmentEventType type, Actor actor, Map<String, Object> payload) {
        events.save(new AppointmentEvent(
                ids.newId(),
                appointment.businessId(),
                appointment.getId(),
                type,
                actor.type(),
                actor.id(),
                payload,
                clock.instant()));
    }
}
