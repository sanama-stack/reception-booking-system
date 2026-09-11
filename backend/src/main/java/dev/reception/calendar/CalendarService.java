package dev.reception.calendar;

import dev.reception.appointments.AppointmentQueryService;
import dev.reception.business.Business;
import dev.reception.business.BusinessService;
import dev.reception.business.ClosureService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeService;
import dev.reception.staff.TimeOffService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * The calendar's single read.
 *
 * <p><strong>One request per view is the requirement, not an optimisation</strong> (phase 10). A
 * calendar assembled from three or four fetches renders in stages, and an owner watching
 * appointments appear over an empty grid cannot tell a slow request from a free afternoon. Worse,
 * the four answers are four different moments: a booking made between the first fetch and the last
 * shows up as a block with no employee, or an employee with no block.
 *
 * <p><strong>An empty column has to be explicable.</strong> The closures and the time off are here
 * for that reason alone — without them, a Tuesday with nothing on it looks identical whether the
 * business was shut, the only employee who can cut hair was away, or nobody happened to book.
 * Those are three different facts and the owner acts differently on each.
 *
 * <p>Composed from the services that already own these rows rather than querying around them, so
 * the tenant filter, the timezone conversion and the cancellation rule are each still written in
 * exactly one place.
 */
@org.springframework.stereotype.Service
public class CalendarService {

    /**
     * The widest range the calendar will answer, in days.
     *
     * <p>Five weeks. The views are a day and a week, and a month grid spilling into the weeks either
     * side is the largest thing anyone can reasonably draw from this — beyond that it is a report,
     * and reports are the analytics endpoint's job. The cap matters because this query is unpaged:
     * something has to bound it, and a date range is a bound a caller understands.
     */
    static final int MAX_RANGE_DAYS = 35;

    private final AppointmentQueryService appointments;
    private final ClosureService closures;
    private final TimeOffService timeOff;
    private final EmployeeService employees;
    private final BusinessService businesses;

    public CalendarService(
            AppointmentQueryService appointments,
            ClosureService closures,
            TimeOffService timeOff,
            EmployeeService employees,
            BusinessService businesses) {
        this.appointments = appointments;
        this.closures = closures;
        this.timeOff = timeOff;
        this.employees = employees;
        this.businesses = businesses;
    }

    /** Both dates inclusive, in the Business's timezone — the days the owner is looking at. */
    @Transactional(readOnly = true)
    public CalendarView between(LocalDate from, LocalDate to) {
        validate(from, to);

        Business business = businesses.read();
        ZoneId zone = business.timezone();

        // The Business's own midnights, not the server's. A calendar drawn on UTC days would put a
        // Tbilisi salon's evening appointments in tomorrow's column.
        Instant rangeFrom = from.atStartOfDay(zone).toInstant();
        Instant rangeTo = to.plusDays(1).atStartOfDay(zone).toInstant();

        return new CalendarView(
                from,
                to,
                zone,
                appointments.overlapping(rangeFrom, rangeTo),
                closures.inRange(rangeFrom, rangeTo),
                timeOff.inRange(rangeFrom, rangeTo),
                // Listed whole, including the deactivated: somebody who left last month may still
                // have next week's time off on the calendar, and a column headed by a blank is worse
                // than one headed by a former employee's name.
                employees.list(null).stream().collect(Collectors.toMap(Employee::getId, Employee::fullName)));
    }

    /** Refused rather than corrected, for the reason {@code AnalyticsService.validate} gives. */
    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The range ends before it starts.",
                    List.of(new FieldError("to", "must not be before from")));
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A calendar range may cover at most " + MAX_RANGE_DAYS + " days.",
                    List.of(new FieldError(
                            "to", "is " + days + " days from from, and the maximum is " + MAX_RANGE_DAYS)));
        }
    }
}
