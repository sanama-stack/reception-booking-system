package dev.reception.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A half-open span of real time, {@code [start, end)}.
 *
 * <p><strong>Half-open is the whole point.</strong> A 15:00–16:00 Appointment and a 16:00–17:00
 * Appointment touch but do not overlap, so both are bookable — which is what the {@code '[)'} bound
 * on the exclusion constraint says in SQL (docs/03-data-model.md §4, ADR-0002) and what this record
 * says in Java. Using closed ranges anywhere would silently forbid back-to-back bookings, and it
 * would do so identically in both places, so no test comparing them would notice.
 *
 * <p>An empty range is not representable: {@code start} must be strictly before {@code end}. Every
 * operation that can produce nothing says so with an {@link Optional} or by omitting the range from
 * a list, rather than by handing back a zero-length range that would then have to be filtered
 * everywhere it is used.
 *
 * <p>This is the only range algebra in the system. Business Closures, Time Off and Appointments'
 * blocked ranges are all stored as instants precisely so the engine subtracts them with one piece of
 * arithmetic instead of three (ADR-0003).
 */
public record TimeRange(Instant start, Instant end) implements Comparable<TimeRange> {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("A TimeRange must start before it ends: " + start + " to " + end);
        }
    }

    public static TimeRange of(Instant start, Instant end) {
        return new TimeRange(start, end);
    }

    public static TimeRange of(Instant start, Duration length) {
        return new TimeRange(start, start.plus(length));
    }

    /**
     * Whether the two share at least one instant.
     *
     * <p>Adjacent ranges do <em>not</em> overlap: {@code [09:00, 10:00)} and {@code [10:00, 11:00)}
     * return {@code false}. That single line is what makes back-to-back appointments bookable.
     */
    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    /** Whether {@code other} lies wholly inside this range, boundaries included. */
    public boolean contains(TimeRange other) {
        return !start.isAfter(other.start) && !end.isBefore(other.end);
    }

    /** Whether the instant lies in {@code [start, end)} — the end instant is <em>not</em> in it. */
    public boolean contains(Instant instant) {
        return !start.isAfter(instant) && end.isAfter(instant);
    }

    public Optional<TimeRange> intersection(TimeRange other) {
        Instant from = max(start, other.start);
        Instant to = min(end, other.end);
        return from.isBefore(to) ? Optional.of(new TimeRange(from, to)) : Optional.empty();
    }

    /**
     * What remains of this range once {@code hole} is removed: nothing, one piece, or — when the
     * hole sits strictly inside — two.
     */
    public List<TimeRange> subtract(TimeRange hole) {
        if (!overlaps(hole)) {
            return List.of(this);
        }
        List<TimeRange> remainder = new ArrayList<>(2);
        if (start.isBefore(hole.start)) {
            remainder.add(new TimeRange(start, hole.start));
        }
        if (hole.end.isBefore(end)) {
            remainder.add(new TimeRange(hole.end, end));
        }
        return List.copyOf(remainder);
    }

    /** Clips this range to {@code bounds}, or empty when it falls entirely outside them. */
    public Optional<TimeRange> clip(TimeRange bounds) {
        return intersection(bounds);
    }

    public Duration length() {
        return Duration.between(start, end);
    }

    /**
     * Merges a collection into the smallest set of disjoint ranges covering the same instants.
     *
     * <p><strong>Adjacent ranges are merged</strong>, even though they do not overlap. A Business
     * open 09:00–12:00 and 12:00–17:00 in two rows is open continuously, and a two-hour Service
     * starting at 11:30 must be offered. Not merging here would make it depend on how the owner
     * happened to split the rows — the same fact expressed two ways giving two different answers.
     */
    public static List<TimeRange> union(Collection<TimeRange> ranges) {
        List<TimeRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.naturalOrder());

        List<TimeRange> merged = new ArrayList<>(sorted.size());
        for (TimeRange range : sorted) {
            if (merged.isEmpty()) {
                merged.add(range);
                continue;
            }
            TimeRange last = merged.getLast();
            if (last.end.isBefore(range.start)) {
                merged.add(range);
            } else if (last.end.isBefore(range.end)) {
                merged.set(merged.size() - 1, new TimeRange(last.start, range.end));
            }
        }
        return List.copyOf(merged);
    }

    /**
     * The pairwise intersection of two sets of ranges — the engine's {@code open ∩ working}.
     *
     * <p>Both sides are unioned first, so the result is disjoint and each output range is a genuine
     * continuous stretch. Without that, two overlapping rows on one side would each intersect the
     * other side and produce duplicate, overlapping "workable" windows, and a Slot would be offered
     * twice.
     */
    public static List<TimeRange> intersect(Collection<TimeRange> left, Collection<TimeRange> right) {
        List<TimeRange> a = union(left);
        List<TimeRange> b = union(right);
        List<TimeRange> result = new ArrayList<>();
        for (TimeRange first : a) {
            for (TimeRange second : b) {
                first.intersection(second).ifPresent(result::add);
            }
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    /** Removes every hole from every range, leaving the remainder disjoint and sorted. */
    public static List<TimeRange> subtractAll(Collection<TimeRange> ranges, Collection<TimeRange> holes) {
        List<TimeRange> remaining = union(ranges);
        for (TimeRange hole : holes) {
            List<TimeRange> next = new ArrayList<>(remaining.size() + 1);
            for (TimeRange range : remaining) {
                next.addAll(range.subtract(hole));
            }
            remaining = next;
        }
        remaining.sort(Comparator.naturalOrder());
        return List.copyOf(remaining);
    }

    /** Whether {@code candidate} touches any of {@code ranges} — the busy check, said once. */
    public static boolean overlapsAny(TimeRange candidate, Collection<TimeRange> ranges) {
        return ranges.stream().anyMatch(candidate::overlaps);
    }

    /** Ordered by start, then by end, so {@link #union} and every test see a stable sequence. */
    @Override
    public int compareTo(TimeRange other) {
        int byStart = start.compareTo(other.start);
        return byStart != 0 ? byStart : end.compareTo(other.end);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
