package dev.reception.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The range algebra everything else rests on (docs/08-testing-strategy.md §5).
 *
 * <p>Cheap to test and expensive to get wrong: an off-by-one in {@code overlaps} does not fail
 * anywhere visible, it just quietly stops offering back-to-back appointments — or starts offering
 * double bookings, which the exclusion constraint would then refuse with a message about a
 * constraint.
 */
class TimeRangeTest {

    /** Minutes past a fixed midnight. Every case reads as a clock rather than as an epoch second. */
    private static Instant at(int hour, int minute) {
        return Instant.parse("2026-09-08T00:00:00Z").plus(Duration.ofMinutes(hour * 60L + minute));
    }

    private static TimeRange range(int fromHour, int toHour) {
        return TimeRange.of(at(fromHour, 0), at(toHour, 0));
    }

    @Test
    @DisplayName("an empty range cannot be constructed")
    void refuses_an_empty_range() {
        assertThatThrownBy(() -> TimeRange.of(at(9, 0), at(9, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeRange.of(at(10, 0), at(9, 0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("overlap")
    class Overlap {

        @Test
        @DisplayName("adjacent ranges do not overlap — this is what makes back-to-back bookable")
        void adjacent_ranges_do_not_overlap() {
            assertThat(range(9, 10).overlaps(range(10, 11))).isFalse();
            assertThat(range(10, 11).overlaps(range(9, 10))).isFalse();
        }

        @Test
        @DisplayName("ranges sharing any interior instant overlap, in both directions")
        void overlapping_ranges_overlap() {
            assertThat(range(9, 11).overlaps(range(10, 12))).isTrue();
            assertThat(range(10, 12).overlaps(range(9, 11))).isTrue();
        }

        @Test
        @DisplayName("a range contained in another overlaps it")
        void containment_is_overlap() {
            assertThat(range(9, 17).overlaps(range(12, 13))).isTrue();
            assertThat(range(12, 13).overlaps(range(9, 17))).isTrue();
        }

        @Test
        @DisplayName("disjoint ranges do not overlap")
        void disjoint_ranges_do_not_overlap() {
            assertThat(range(9, 10).overlaps(range(11, 12))).isFalse();
        }

        @Test
        @DisplayName("the end instant is outside the range")
        void the_end_instant_is_excluded() {
            assertThat(range(9, 10).contains(at(9, 0))).isTrue();
            assertThat(range(9, 10).contains(at(9, 59))).isTrue();
            assertThat(range(9, 10).contains(at(10, 0))).isFalse();
        }
    }

    @Nested
    @DisplayName("intersection")
    class Intersection {

        @Test
        @DisplayName("partial overlap yields the shared part")
        void partial_overlap() {
            assertThat(range(9, 12).intersection(range(11, 14))).contains(range(11, 12));
        }

        @Test
        @DisplayName("containment yields the inner range")
        void containment() {
            assertThat(range(9, 17).intersection(range(12, 13))).contains(range(12, 13));
        }

        @Test
        @DisplayName("adjacent ranges intersect in nothing, not in a zero-length range")
        void adjacent_ranges_intersect_in_nothing() {
            assertThat(range(9, 10).intersection(range(10, 11))).isEmpty();
        }

        @Test
        @DisplayName("disjoint ranges intersect in nothing")
        void disjoint_ranges_intersect_in_nothing() {
            assertThat(range(9, 10).intersection(range(11, 12))).isEmpty();
        }
    }

    @Nested
    @DisplayName("subtraction")
    class Subtraction {

        @Test
        @DisplayName("a hole in the middle leaves two remainders")
        void a_hole_in_the_middle_leaves_two() {
            assertThat(range(9, 17).subtract(range(12, 13))).containsExactly(range(9, 12), range(13, 17));
        }

        @Test
        @DisplayName("a hole overlapping one end leaves one remainder")
        void a_hole_at_an_end_leaves_one() {
            assertThat(range(9, 17).subtract(range(8, 12))).containsExactly(range(12, 17));
            assertThat(range(9, 17).subtract(range(15, 20))).containsExactly(range(9, 15));
        }

        @Test
        @DisplayName("a hole covering the whole range leaves nothing")
        void a_covering_hole_leaves_nothing() {
            assertThat(range(9, 17).subtract(range(8, 18))).isEmpty();
            assertThat(range(9, 17).subtract(range(9, 17))).isEmpty();
        }

        @Test
        @DisplayName("a hole that only touches the range leaves it whole")
        void an_adjacent_hole_leaves_the_range_whole() {
            assertThat(range(9, 17).subtract(range(17, 18))).containsExactly(range(9, 17));
            assertThat(range(9, 17).subtract(range(8, 9))).containsExactly(range(9, 17));
        }
    }

    @Nested
    @DisplayName("union")
    class Union {

        @Test
        @DisplayName("overlapping ranges merge")
        void overlapping_ranges_merge() {
            assertThat(TimeRange.union(List.of(range(9, 12), range(11, 14)))).containsExactly(range(9, 14));
        }

        @Test
        @DisplayName("adjacent ranges merge — a split row is still one continuous open stretch")
        void adjacent_ranges_merge() {
            assertThat(TimeRange.union(List.of(range(9, 12), range(12, 17)))).containsExactly(range(9, 17));
        }

        @Test
        @DisplayName("disjoint ranges stay separate, in order")
        void disjoint_ranges_stay_separate() {
            assertThat(TimeRange.union(List.of(range(14, 18), range(9, 12))))
                    .containsExactly(range(9, 12), range(14, 18));
        }

        @Test
        @DisplayName("a range wholly inside another disappears into it")
        void a_contained_range_disappears() {
            assertThat(TimeRange.union(List.of(range(9, 17), range(11, 12)))).containsExactly(range(9, 17));
        }

        @Test
        @DisplayName("an empty input unions to nothing")
        void empty_input() {
            assertThat(TimeRange.union(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("intersect and subtractAll")
    class SetOperations {

        @Test
        @DisplayName("intersecting two sets keeps only the shared stretches")
        void intersect_two_sets() {
            List<TimeRange> open = List.of(range(9, 13), range(14, 18));
            List<TimeRange> working = List.of(range(11, 16));
            assertThat(TimeRange.intersect(open, working)).containsExactly(range(11, 13), range(14, 16));
        }

        @Test
        @DisplayName("overlapping rows on one side do not produce duplicate windows")
        void overlapping_input_does_not_duplicate() {
            List<TimeRange> open = List.of(range(9, 13), range(10, 14));
            assertThat(TimeRange.intersect(open, List.of(range(9, 17)))).containsExactly(range(9, 14));
        }

        @Test
        @DisplayName("disjoint sets intersect in nothing")
        void disjoint_sets() {
            assertThat(TimeRange.intersect(List.of(range(9, 12)), List.of(range(13, 17))))
                    .isEmpty();
        }

        @Test
        @DisplayName("holes are removed from every range")
        void subtract_many_holes() {
            assertThat(TimeRange.subtractAll(List.of(range(9, 18)), List.of(range(11, 12), range(15, 16))))
                    .containsExactly(range(9, 11), range(12, 15), range(16, 18));
        }

        @Test
        @DisplayName("overlapsAny is false against an empty set")
        void overlaps_nothing() {
            assertThat(TimeRange.overlapsAny(range(9, 10), List.of())).isFalse();
            assertThat(TimeRange.overlapsAny(range(9, 10), List.of(range(10, 11)))).isFalse();
            assertThat(TimeRange.overlapsAny(range(9, 10), List.of(range(11, 12), range(9, 10))))
                    .isTrue();
        }
    }
}
