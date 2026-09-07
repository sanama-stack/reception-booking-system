package dev.reception.staff;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.common.ids.IdGenerator;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading an Employee's Working Schedule, and replacing it whole.
 *
 * <p>Deliberately the same shape as {@code BusinessHoursService}, down to the validation and the
 * flush between the delete and the inserts. Two editors that behave differently for no reason are
 * two things to learn; the frontend gets to reuse one component for both because the wire format is
 * the same, and phase 05 gets to intersect two lists that mean the same kind of thing.
 *
 * <p><strong>A day with no interval is a day off.</strong> Absence is the representation, so there
 * is no closed flag here and none on the wire.
 *
 * <p><strong>The schedule is stored as given, even when it is wider than Business Hours.</strong>
 * Phase 05 intersects the two. Clamping here would destroy the owner's actual answer to "when is
 * this person willing to work", and would silently re-cut every employee's week whenever the
 * opening hours moved.
 */
@Service
public class EmployeeScheduleService {

    private final EmployeeScheduleRepository schedules;
    private final EmployeeRepository employees;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public EmployeeScheduleService(
            EmployeeScheduleRepository schedules,
            EmployeeRepository employees,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.schedules = schedules;
        this.employees = employees;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    /** One interval on one day, as the caller supplied it. */
    public record Interval(DayOfWeek dayOfWeek, LocalTime startsAt, LocalTime endsAt) {}

    @Transactional(readOnly = true)
    public List<EmployeeSchedule> read(UUID employeeId) {
        UUID businessId = tenant.businessId();
        requireEmployee(businessId, employeeId);
        return schedules.findByBusinessIdAndEmployeeIdOrderByDayOfWeekAscStartsAtAsc(businessId, employeeId);
    }

    /**
     * Replaces the entire week.
     *
     * <p>Validated in full before anything is deleted, so an invalid Friday leaves Monday to
     * Thursday untouched <em>because they were never touched</em> — a guarantee that does not depend
     * on the transaction rolling a partial write back.
     */
    @Transactional
    public List<EmployeeSchedule> replaceWeek(UUID employeeId, List<Interval> week) {
        UUID businessId = tenant.businessId();
        // Before validating the payload: an employee who does not exist — or belongs to someone else
        // — is a 404, and it would be odd to answer a request for a resource that is not there by
        // criticising its body.
        requireEmployee(businessId, employeeId);
        validateWeek(week);

        schedules.deleteByBusinessIdAndEmployeeId(businessId, employeeId);
        // Hibernate orders operations by entity type rather than by the order they were requested
        // in, so the inserts can otherwise reach the database first and collide with
        // UNIQUE (employee_id, day_of_week, starts_at) rows that are about to be removed. The flush
        // is the ordering. Submitting the same week twice is the case that fails without it.
        schedules.flush();

        List<EmployeeSchedule> replaced = new ArrayList<>(week.size());
        for (Interval interval : week) {
            replaced.add(new EmployeeSchedule(
                    ids.newId(),
                    businessId,
                    employeeId,
                    interval.dayOfWeek(),
                    interval.startsAt(),
                    interval.endsAt(),
                    clock.instant()));
        }
        return schedules.saveAll(replaced);
    }

    /**
     * Every rule at once, each failure named by its position in the submitted array so the form can
     * put the message on the row that caused it.
     *
     * <p>Static and package-private because it depends on nothing but its argument — which is what
     * lets the overlap rules be tested without a database, a Spring context or a tenant.
     */
    static void validateWeek(List<Interval> week) {
        List<FieldError> errors = new ArrayList<>();

        for (int i = 0; i < week.size(); i++) {
            Interval interval = week.get(i);
            if (!interval.startsAt().isBefore(interval.endsAt())) {
                // Equal is rejected along with inverted: a zero-length shift is not a shift, and a
                // working day cannot cross midnight.
                errors.add(new FieldError("schedule[" + i + "].endsAt", "The end time must be later than the start."));
            }
        }

        // Overlap is a property of a day, checked on a sorted copy so the pass is linear and the
        // message can name the later of the two intervals — the one the owner most likely just added.
        for (DayOfWeek day : DayOfWeek.values()) {
            List<Indexed> onDay = new ArrayList<>();
            for (int i = 0; i < week.size(); i++) {
                if (week.get(i).dayOfWeek() == day) {
                    onDay.add(new Indexed(i, week.get(i)));
                }
            }
            onDay.sort(Comparator.comparing(indexed -> indexed.interval().startsAt()));

            for (int i = 1; i < onDay.size(); i++) {
                LocalTime previousEnds = onDay.get(i - 1).interval().endsAt();
                Indexed current = onDay.get(i);
                // Strictly before, so 09:00-13:00 and 13:00-17:00 are accepted. Adjacent intervals
                // are one shift expressed in two rows, not an overlap, and refusing them would
                // forbid the most obvious way to write a split day.
                if (current.interval().startsAt().isBefore(previousEnds)) {
                    errors.add(new FieldError(
                            "schedule[" + current.index() + "].startsAt",
                            "These hours overlap another interval on the same day."));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The working schedule is not valid.", errors);
        }
    }

    private void requireEmployee(UUID businessId, UUID employeeId) {
        employees.findByBusinessIdAndId(businessId, employeeId)
                .orElseThrow(() -> ApiException.notFound("No such employee."));
    }

    private record Indexed(int index, Interval interval) {}
}
