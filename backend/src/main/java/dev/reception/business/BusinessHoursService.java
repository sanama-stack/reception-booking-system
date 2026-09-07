package dev.reception.business;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.common.ids.IdGenerator;
import dev.reception.tenancy.TenantContext;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the opening week, and replacing it whole.
 *
 * <p>There is no "update Tuesday" here and there should never be one. Overlap is a property of a
 * day, not of an interval, so a writer that sees one day cannot tell whether the week it leaves
 * behind is consistent — it would have to read the others back, at which point it is a whole-week
 * replace with extra steps and a race in the middle.
 *
 * <p><strong>A day with no interval is closed.</strong> Absence is the representation, so "closed
 * on Sunday" is expressed by sending no Sunday interval, not by a flag.
 */
@Service
public class BusinessHoursService {

    private final BusinessHoursRepository hours;
    private final TenantContext tenant;
    private final IdGenerator ids;

    public BusinessHoursService(BusinessHoursRepository hours, TenantContext tenant, IdGenerator ids) {
        this.hours = hours;
        this.tenant = tenant;
        this.ids = ids;
    }

    /** One interval on one day, as the caller supplied it. */
    public record Interval(DayOfWeek dayOfWeek, LocalTime opensAt, LocalTime closesAt) {}

    @Transactional(readOnly = true)
    public List<BusinessHours> read() {
        return hours.findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(tenant.businessId());
    }

    /**
     * Replaces the entire week.
     *
     * <p>Validated in full <em>before</em> anything is deleted. The transaction would roll a partial
     * write back anyway, but validating first is what makes the guarantee independent of the
     * transaction: an invalid Friday leaves Monday to Thursday untouched because they were never
     * touched, not because a rollback repaired them.
     */
    @Transactional
    public List<BusinessHours> replaceWeek(List<Interval> week) {
        validateWeek(week);

        UUID businessId = tenant.businessId();
        hours.deleteByBusinessId(businessId);
        // Without this the inserts can reach the database before the deletes, and
        // UNIQUE (business_id, day_of_week, opens_at) does not care that the row it collides with
        // is about to be removed. Hibernate orders by entity type, not by the order operations were
        // requested in, so the flush is the ordering.
        hours.flush();

        List<BusinessHours> replaced = new ArrayList<>(week.size());
        for (Interval interval : week) {
            replaced.add(new BusinessHours(
                    ids.newId(), businessId, interval.dayOfWeek(), interval.opensAt(), interval.closesAt()));
        }
        return hours.saveAll(replaced);
    }

    /**
     * Every rule at once, with each failure named by its position in the submitted array so the
     * form can put the message on the row that caused it.
     *
     * <p>Static and package-private because it depends on nothing but its argument, which is what
     * lets the overlap rules be tested without a database, a Spring context or a tenant.
     */
    static void validateWeek(List<Interval> week) {
        List<FieldError> errors = new ArrayList<>();

        for (int i = 0; i < week.size(); i++) {
            Interval interval = week.get(i);
            if (!interval.opensAt().isBefore(interval.closesAt())) {
                // Equal is rejected along with inverted: a zero-length opening is not a shift, and
                // hours cannot cross midnight (docs/03-data-model.md).
                errors.add(new FieldError(
                        "hours[" + i + "].closesAt", "Closing time must be later than opening time."));
            }
        }

        // Overlap is checked per day, on a sorted copy, so the check is linear and the message can
        // name the later of the two intervals — the one the owner most likely just added.
        for (DayOfWeek day : DayOfWeek.values()) {
            List<Indexed> onDay = new ArrayList<>();
            for (int i = 0; i < week.size(); i++) {
                if (week.get(i).dayOfWeek() == day) {
                    onDay.add(new Indexed(i, week.get(i)));
                }
            }
            onDay.sort(Comparator.comparing(indexed -> indexed.interval().opensAt()));

            for (int i = 1; i < onDay.size(); i++) {
                LocalTime previousCloses = onDay.get(i - 1).interval().closesAt();
                Indexed current = onDay.get(i);
                // Strictly before, so 09:00-13:00 and 13:00-17:00 are accepted. Adjacent intervals
                // are a lunch-less split shift, not an overlap, and rejecting them would forbid the
                // most obvious way to express one.
                if (current.interval().opensAt().isBefore(previousCloses)) {
                    errors.add(new FieldError(
                            "hours[" + current.index() + "].opensAt",
                            "These hours overlap another interval on the same day."));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The opening hours are not valid.", errors);
        }
    }

    private record Indexed(int index, Interval interval) {}
}
