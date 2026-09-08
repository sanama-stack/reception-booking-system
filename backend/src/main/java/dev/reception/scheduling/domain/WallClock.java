package dev.reception.scheduling.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Optional;

/**
 * The one place a wall-clock time becomes an instant.
 *
 * <p>Business Hours and Working Schedules are stored as {@link java.time.LocalTime}, because a
 * Business that opens at 09:00 opens at 09:00 on both sides of a daylight-saving change; the engine
 * converts them to instants for a particular date, and never the reverse (ADR-0003). On the two days
 * a year when that conversion is not a bijection, the answer depends on what is being converted —
 * which is why there are two methods here rather than one.
 *
 * <p>{@code java.time}'s own defaults are wrong for one of the two cases and right for the other,
 * and both look identical at the call site. {@code ZonedDateTime.of} shifts a non-existent local
 * time <em>forward by the length of the gap</em>, so 02:00 and 02:30 on a spring-forward day become
 * 03:00 and 03:30 — an interval that has silently moved rather than shrunk. That is the trap these
 * methods exist to keep out of the engine.
 */
final class WallClock {

    private WallClock() {}

    /**
     * The instant a local time names, or empty when it names none.
     *
     * <p>For a candidate Slot start: on a spring-forward day the missing hour is <em>skipped</em>,
     * because there is no such moment to book. On a fall-back day the repeated hour resolves to the
     * earlier — pre-transition — instant, so it is offered once rather than twice (ADR-0003).
     */
    static Optional<Instant> exact(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            return Optional.empty();
        }
        // getValidOffsets lists the pre-transition offset first, and the larger offset yields the
        // earlier instant. Taking index 0 is therefore the "resolve to the first occurrence" rule.
        return Optional.of(local.toInstant(offsets.getFirst()));
    }

    /**
     * The instant an interval boundary names, clamping a non-existent one to the transition.
     *
     * <p>A boundary cannot simply be dropped the way a candidate start can — an interval needs two
     * ends. Clamping is what makes the missing hour <em>disappear</em> from the day instead of
     * displacing the rest of it: a Business open 01:00–09:00 on a spring-forward day is open for
     * seven real hours, and one open only 02:00–02:30 inside the gap is not open at all, which
     * {@link WeeklyInterval#on} reports by producing no range.
     */
    static Instant boundary(LocalDateTime local, ZoneId zone) {
        return exact(local, zone).orElseGet(() -> {
            ZoneOffsetTransition gap = zone.getRules().getTransition(local);
            return gap.getInstant();
        });
    }
}
