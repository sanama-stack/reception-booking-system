package dev.reception.appointments;

import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.notifications.NotificationEnqueuer;
import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.scheduling.domain.ServiceSpec;
import dev.reception.scheduling.domain.TimeRange;
import dev.reception.scheduling.domain.UnbookableReason;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving an Appointment to a new time, in place.
 *
 * <p><strong>An update, not a cancel-and-create.</strong> Cancelling first releases the original
 * time, and a concurrent booking can take it in the gap before the new row is written — which would
 * leave the Customer with nothing, having asked only to move something. Updating in place means the
 * appointment either moves or stays where it was, and the exclusion constraint judges the new
 * position exactly as it judged the old one.
 *
 * <p>The id and the Confirmation Code survive the move: the Customer is holding an email with that
 * code in it, and reissuing it because the time changed would lock them out of the appointment they
 * were just told about.
 */
@org.springframework.stereotype.Service
public class RescheduleService {

    private final AppointmentRepository appointments;
    private final AppointmentLookup lookup;
    private final CancellationWindow window;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final AvailabilityService availability;
    private final AppointmentEventRecorder events;
    private final NotificationEnqueuer notifications;
    private final Clock clock;

    public RescheduleService(
            AppointmentRepository appointments,
            AppointmentLookup lookup,
            CancellationWindow window,
            ServiceCatalogService catalog,
            EmployeeService employees,
            AvailabilityService availability,
            AppointmentEventRecorder events,
            NotificationEnqueuer notifications,
            Clock clock) {
        this.appointments = appointments;
        this.lookup = lookup;
        this.window = window;
        this.catalog = catalog;
        this.employees = employees;
        this.availability = availability;
        this.events = events;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * @param newEmployeeId the person who will now perform it, or {@code null} to keep the current
     *     one. The Employee free at the new hour need not be the one who was free at the old one,
     *     so a reschedule that could not change it would refuse moves that are perfectly possible
     */
    @Transactional
    public Appointment reschedule(UUID id, Instant newStartsAt, UUID newEmployeeId, Actor actor) {
        Appointment appointment = lookup.require(id);

        if (appointment.status() != AppointmentStatus.CONFIRMED) {
            throw new ApiException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "This appointment is marked %s and cannot be moved."
                            .formatted(appointment.status().name().toLowerCase().replace('_', ' ')));
        }
        // The same predicate CancellationService applies, now that there is one. A direct
        // comparison to CUSTOMER here would have let the Receptionist move an appointment that a
        // Customer was refused permission to move (Actor#boundByCancellationWindow).
        if (actor.boundByCancellationWindow()) {
            window.requireOpenFor(appointment);
        }

        UUID employeeId = newEmployeeId != null ? newEmployeeId : appointment.employeeId();
        Service service = catalog.read(appointment.serviceId());
        Employee employee = employees.read(employeeId);

        // Excluding this appointment. Moving a 10:00 booking to 10:15 overlaps the time it currently
        // holds, and counting it against itself would refuse the one move being asked for. The
        // database has no such problem — an exclusion constraint never compares a row with itself.
        Optional<UnbookableReason> refusal =
                availability.reasonNotBookable(appointment.serviceId(), employeeId, newStartsAt, id);
        if (refusal.isPresent()) {
            throw BookingRefusal.of(refusal.get(), service.name(), employee.fullName());
        }

        // Recomputed from the Service as it is now, not from the Buffers the original booking was
        // written with. The occupancy being stored is a new one, and it should describe how the
        // Service is performed today.
        ServiceSpec spec = BookingService.specFor(service);
        TimeRange moved = TimeRange.of(newStartsAt, spec.duration());
        TimeRange occupancy = spec.occupancyFor(moved);

        Instant previousStartsAt = appointment.startsAt();
        Instant previousEndsAt = appointment.endsAt();
        UUID previousEmployeeId = appointment.employeeId();

        appointment.moveTo(
                employeeId, moved.start(), moved.end(), occupancy.start(), occupancy.end(), clock.instant());

        // saveAndFlush for both of the ways this can fail: the exclusion constraint if somebody took
        // the new time, and the optimistic lock if somebody else moved this same appointment while
        // we were deciding. At commit time neither would have a handler that knew what it meant.
        Appointment saved = appointments.saveAndFlush(appointment);
        events.rescheduled(saved, previousStartsAt, previousEndsAt, previousEmployeeId, actor);
        // The old reminder is superseded and a new one scheduled inside this same transaction, so a
        // move that loses the exclusion-constraint race leaves the original reminder untouched.
        notifications.appointmentRescheduled(saved, previousStartsAt);
        return saved;
    }
}
