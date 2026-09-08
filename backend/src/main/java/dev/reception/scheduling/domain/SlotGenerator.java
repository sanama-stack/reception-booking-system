package dev.reception.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The candidate start times for one calendar date, on the Business's Slot grid.
 *
 * <p><strong>The grid is anchored at local midnight, not at an instant</strong> (ADR-0003). A
 * 30-minute grid then produces 09:00, 09:30, 10:00 year-round; anchoring it at a UTC instant would
 * slide every Slot to 08:30, 09:00, 09:30 on the day the clocks change, and a business whose
 * appointments moved half an hour twice a year would reasonably call that a bug.
 *
 * <p>Stepping in local time is also what makes the two DST cases fall out rather than needing to be
 * detected: the day simply has fewer or more candidates than usual.
 */
public final class SlotGenerator {

    private SlotGenerator() {}

    /**
     * Every instant on the grid for {@code date}, in ascending order.
     *
     * <p>A spring-forward day yields fewer candidates than a normal one — the local times inside the
     * gap name no instant and are skipped, rather than being silently shifted into the following
     * hour where they would collide with the candidates already there.
     *
     * <p>A fall-back day yields the usual number, not more: the repeated local hour resolves to its
     * first occurrence and is offered once. Offering it twice would produce two Slots reading
     * "01:30" that a Customer could not tell apart.
     */
    public static List<Instant> candidateStarts(LocalDate date, ZoneId zone, int slotIntervalMinutes) {
        if (slotIntervalMinutes <= 0) {
            throw new IllegalArgumentException("slotIntervalMinutes must be positive");
        }
        List<Instant> starts = new ArrayList<>(1 + (24 * 60) / slotIntervalMinutes);
        LocalDateTime local = date.atStartOfDay();
        while (local.toLocalDate().equals(date)) {
            WallClock.exact(local, zone).ifPresent(starts::add);
            local = local.plusMinutes(slotIntervalMinutes);
        }
        return List.copyOf(starts);
    }
}
