package dev.reception.scheduling.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One candidate Employee, with everything that makes them free or busy.
 *
 * <p>Only Employees who can actually perform the Service reach the engine — active, assigned, and
 * belonging to the Business. Eligibility is a database question and the engine does not ask it;
 * an empty list is how the caller says "nobody", and the engine answers
 * {@link EmptyReason#NO_ELIGIBLE_EMPLOYEE}.
 *
 * @param workingIntervals the Working Schedule, wall-clock, all seven days. Stored wider than
 *     Business Hours on purpose; intersecting the two is this engine's job and not the writer's
 * @param timeOff absences as real time
 * @param appointments the buffer-inclusive blocked ranges of this Employee's {@code CONFIRMED}
 *     Appointments. Empty until phase 06 creates any — see {@code AppointmentImpact}
 */
public record EmployeeAvailabilityInput(
        UUID employeeId,
        String fullName,
        List<WeeklyInterval> workingIntervals,
        List<TimeRange> timeOff,
        List<TimeRange> appointments) {

    public EmployeeAvailabilityInput {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(fullName, "fullName");
        workingIntervals = List.copyOf(workingIntervals);
        timeOff = List.copyOf(timeOff);
        appointments = List.copyOf(appointments);
    }
}
