package dev.reception.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * One wall-clock interval on one day of the week — a row of Business Hours or of a Working Schedule.
 *
 * <p>The two are the same shape deliberately. "When are we open" and "when is this person willing to
 * work" are different facts stored in different tables, but they are the same <em>kind</em> of fact,
 * and the engine's whole job on a given date is to intersect them. One type means one conversion to
 * instants and one set of DST rules, rather than two that could drift apart.
 */
public record WeeklyInterval(DayOfWeek day, LocalTime startsAt, LocalTime endsAt) {

    public WeeklyInterval {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!startsAt.isBefore(endsAt)) {
            // Business Hours cannot cross midnight — a documented MVP limitation (ADR-0003), and the
            // reason a day's intervals can be converted against a single date.
            throw new IllegalArgumentException("A weekly interval must start before it ends: " + startsAt + "–" + endsAt);
        }
    }

    /**
     * This interval as real time on a particular date, or empty when the date leaves nothing of it.
     *
     * <p>Empty is only reachable on a spring-forward day, when the whole interval sits inside the
     * hour that does not exist. See {@link WallClock#boundary}.
     */
    public Optional<TimeRange> on(LocalDate date, ZoneId zone) {
        if (date.getDayOfWeek() != day) {
            return Optional.empty();
        }
        Instant from = WallClock.boundary(date.atTime(startsAt), zone);
        Instant to = WallClock.boundary(date.atTime(endsAt), zone);
        return from.isBefore(to) ? Optional.of(new TimeRange(from, to)) : Optional.empty();
    }
}
