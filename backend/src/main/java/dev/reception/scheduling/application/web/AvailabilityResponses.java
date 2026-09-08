package dev.reception.scheduling.application.web;

import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.scheduling.domain.EmptyReason;
import dev.reception.scheduling.domain.Slot;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The wire shape of {@code GET /availability}, fixed in docs/04-api-overview.md §5.
 *
 * <p>Times carry the Business's offset and the response names the zone, so a client never has to
 * guess which clock a Slot is on and never has to consult the browser's (ADR-0003).
 */
public final class AvailabilityResponses {

    private AvailabilityResponses() {}

    /**
     * @param emptyReason {@code null} whenever any day holds a Slot, and serialised as an explicit
     *     {@code null} rather than omitted — a client must be able to tell "there is no reason" from
     *     "this server did not answer". A reason rather than a bare empty array is what lets phase
     *     09's Receptionist explain <em>why</em> instead of inventing an explanation
     */
    public record Availability(String timezone, List<Day> days, EmptyReason emptyReason) {

        public static Availability of(AvailabilityService.Availability availability) {
            ZoneId zone = availability.timezone();
            return new Availability(
                    zone.getId(),
                    availability.result().days().stream()
                            .map(day -> Day.of(day, zone))
                            .toList(),
                    availability.result().emptyReason());
        }
    }

    /** One calendar date in the Business timezone, present even when it holds nothing. */
    public record Day(LocalDate date, List<AvailableSlot> slots) {

        static Day of(Slot.Day day, ZoneId zone) {
            return new Day(
                    day.date(), day.slots().stream().map(slot -> AvailableSlot.of(slot, zone)).toList());
        }
    }

    /**
     * @param employee always present, even when the caller named no Employee — booking must never
     *     have to resolve one, because a second resolution is a second chance to pick a different
     *     answer than the one the Customer was shown
     */
    public record AvailableSlot(OffsetDateTime startsAt, OffsetDateTime endsAt, EmployeeSummary employee) {

        static AvailableSlot of(Slot slot, ZoneId zone) {
            return new AvailableSlot(
                    slot.startsAt().atZone(zone).toOffsetDateTime(),
                    slot.endsAt().atZone(zone).toOffsetDateTime(),
                    new EmployeeSummary(slot.employeeId(), slot.employeeName()));
        }
    }

    /** Name and id only. Nothing here is unsafe to show a Customer once phase 08 opens this shape. */
    public record EmployeeSummary(UUID id, String fullName) {}
}
