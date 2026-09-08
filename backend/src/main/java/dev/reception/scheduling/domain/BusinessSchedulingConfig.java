package dev.reception.scheduling.domain;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Everything about the Business that shapes a Slot, flattened out of the entity.
 *
 * @param timezone the IANA zone every wall-clock time in here is interpreted in
 * @param openIntervals every Business Hours row, all seven days together — the engine picks the
 *     weekday it needs, so a caller cannot hand it the wrong day's hours by mistake
 * @param slotIntervalMinutes the grid candidate starts land on, anchored at <em>local</em> midnight
 * @param minLeadTimeMinutes how soon from now a Slot may be
 * @param maxAdvanceDays how far ahead a Slot may be
 * @param closures Business Closures as real time, already converted at write time
 */
public record BusinessSchedulingConfig(
        ZoneId timezone,
        List<WeeklyInterval> openIntervals,
        int slotIntervalMinutes,
        int minLeadTimeMinutes,
        int maxAdvanceDays,
        List<TimeRange> closures) {

    public BusinessSchedulingConfig {
        Objects.requireNonNull(timezone, "timezone");
        openIntervals = List.copyOf(openIntervals);
        closures = List.copyOf(closures);
        if (slotIntervalMinutes <= 0) {
            throw new IllegalArgumentException("slotIntervalMinutes must be positive");
        }
    }
}
