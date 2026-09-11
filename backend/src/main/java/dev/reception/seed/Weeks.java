package dev.reception.seed;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Week-anchored dates, in the Business's own timezone.
 *
 * <p>The origin is the Monday of the week the seed runs in, computed from the Business's zone
 * rather than the machine's: a seed run at 23:00 in Berlin is already Saturday in Tbilisi, and a
 * fixture that took the host's date would put one tenant's week a day out from the other's.
 *
 * <p>Everything the blueprint places is expressed as an offset in whole weeks plus a weekday, so an
 * Appointment written for Wednesday lands on a Wednesday whichever day the seed is run — which is
 * the property a fixture keyed on "three days ago" does not have, and the reason none of the demo
 * data is written that way.
 */
final class Weeks {

    private Weeks() {}

    /** {@code weekOffset} weeks from this week's Monday, then forward to {@code day}. */
    static LocalDate date(LocalDate today, int weekOffset, DayOfWeek day) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(weekOffset)
                .plusDays(day.getValue() - DayOfWeek.MONDAY.getValue());
    }

    /** The same date, at the placement's wall-clock time, as the instant the database stores. */
    static Instant instant(LocalDate today, Blueprint.Placement placement, ZoneId zone) {
        return date(today, placement.weekOffset(), placement.day())
                .atTime(placement.at())
                .atZone(zone)
                .toInstant();
    }
}
