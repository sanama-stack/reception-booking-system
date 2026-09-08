package dev.reception.scheduling.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What was asked for: a Service, a range of calendar dates, and optionally one Employee.
 *
 * <p>The dates are calendar dates <em>in the Business timezone</em> (docs/04-api-overview.md §2),
 * not instants. "Tuesday" is what a Customer picks; which instants that covers is the engine's
 * answer, not the question.
 *
 * @param employeeId {@code null} means "anyone who can do it", and every returned Slot then carries
 *     the Employee who would perform it. Resolving that later would reintroduce the race the
 *     exclusion constraint exists to remove
 */
public record AvailabilityQuery(ServiceSpec service, LocalDate from, LocalDate to, UUID employeeId) {

    public AvailabilityQuery {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("An availability range must end on or after it starts");
        }
    }

    /** Every date asked about, inclusive of both ends. */
    public List<LocalDate> dates() {
        return from.datesUntil(to.plusDays(1)).toList();
    }
}
