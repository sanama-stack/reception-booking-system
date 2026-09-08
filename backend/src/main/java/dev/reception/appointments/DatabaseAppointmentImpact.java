package dev.reception.appointments;

import dev.reception.business.AppointmentImpact;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real answers, replacing the phase-03 stub that could only say "no appointment can be
 * affected".
 *
 * <p>{@code EmptyAppointmentImpact} was deleted in the same commit that added this, which is what
 * its TODO asked for. Two beans implementing one interface stop the context from starting — a loud
 * failure — while a stub left as a fallback fails quietly: {@code blockedRangesFor} would go on
 * reporting every Employee free forever, and the system would offer Slots that are already booked.
 *
 * <p>This class is the whole of phase 06's contribution to phases 03 and 04. Nothing in
 * {@code ClosureService}, {@code ServiceCatalogService}, {@code EmployeeService} or
 * {@code AvailabilityService} changes; they asked the right questions two phases ago and now get
 * true answers.
 */
@Service
public class DatabaseAppointmentImpact implements AppointmentImpact {

    private final AppointmentRepository appointments;
    private final Clock clock;

    public DatabaseAppointmentImpact(AppointmentRepository appointments, Clock clock) {
        this.appointments = appointments;
        this.clock = clock;
    }

    /**
     * Live Appointments overlapping the proposed Business Closure.
     *
     * <p>Cancelled ones are excluded — they hold no time, and reporting them would tell an owner to
     * go and deal with appointments that no longer exist.
     */
    @Override
    @Transactional(readOnly = true)
    public long countWithin(UUID businessId, Instant startsAt, Instant endsAt) {
        return appointments.countByBusinessIdAndStatusNotAndStartsAtLessThanAndEndsAtGreaterThan(
                businessId, AppointmentStatus.CANCELLED, endsAt, startsAt);
    }

    /**
     * "Future" is measured against this class's own {@link Clock} rather than a parameter, because
     * the caller has no reason to hold an opinion about it and passing one would be an invitation to
     * pass a different one from two places (see {@link AppointmentImpact}).
     */
    @Override
    @Transactional(readOnly = true)
    public long countFutureForService(UUID businessId, UUID serviceId) {
        return appointments.countByBusinessIdAndServiceIdAndStatusAndStartsAtAfter(
                businessId, serviceId, AppointmentStatus.CONFIRMED, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public long countFutureForEmployee(UUID businessId, UUID employeeId) {
        return appointments.countByBusinessIdAndEmployeeIdAndStatusAndStartsAtAfter(
                businessId, employeeId, AppointmentStatus.CONFIRMED, clock.instant());
    }

    /**
     * Any status, any date. A hard delete is refused by history rather than by the calendar: an
     * Appointment from last year still names the Service it was for, and removing the row would
     * leave that record pointing at nothing.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean everBooked(UUID businessId, UUID serviceId) {
        return appointments.existsByBusinessIdAndServiceId(businessId, serviceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<BlockedRange>> blockedRangesFor(
            UUID businessId,
            Collection<UUID> employeeIds,
            Instant from,
            Instant to,
            UUID excludingAppointmentId) {
        if (employeeIds.isEmpty()) {
            // `in ()` is not valid SQL, and Hibernate's rendering of an empty collection differs by
            // dialect. Answering without asking is both faster and one less thing to be surprised by.
            return Map.of();
        }
        return appointments
                .findByBusinessIdAndEmployeesOverlapping(businessId, employeeIds, from, to, excludingAppointmentId)
                .stream()
                .collect(Collectors.groupingBy(
                        AppointmentRepository.BlockedRangeRow::getEmployeeId,
                        Collectors.mapping(
                                row -> new BlockedRange(row.getBlockedFrom(), row.getBlockedTo()),
                                Collectors.toList())));
    }
}
