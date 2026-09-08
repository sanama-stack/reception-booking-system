package dev.reception.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Slot grid, and the two days a year it is not obvious.
 *
 * <p>A suite that only ever runs in UTC cannot see a timezone bug, because in UTC every conversion
 * to and from UTC is the identity. These cases name their zone and their date explicitly, so they
 * are hostile on every machine and in every month (docs/08-testing-strategy.md §4).
 */
class SlotGeneratorTest {

    /** EU rules: forward on the last Sunday of March, back on the last Sunday of October. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** UTC+4 all year. The control case: no transition to get right or wrong. */
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    private static final LocalDate SPRING_FORWARD = LocalDate.of(2026, 3, 29);
    private static final LocalDate FALL_BACK = LocalDate.of(2026, 10, 25);
    private static final LocalDate ORDINARY = LocalDate.of(2026, 9, 8);

    private static List<LocalTime> localTimesOf(List<Instant> starts, ZoneId zone) {
        return starts.stream().map(start -> start.atZone(zone).toLocalTime()).toList();
    }

    @Test
    @DisplayName("an ordinary day is the whole day on the grid, starting at local midnight")
    void an_ordinary_day() {
        List<Instant> starts = SlotGenerator.candidateStarts(ORDINARY, BERLIN, 30);

        assertThat(starts).hasSize(48);
        assertThat(starts.getFirst()).isEqualTo(Instant.parse("2026-09-07T22:00:00Z"));
        assertThat(localTimesOf(starts, BERLIN).getFirst()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(localTimesOf(starts, BERLIN).getLast()).isEqualTo(LocalTime.of(23, 30));
    }

    @Test
    @DisplayName("the grid is on clean local times, not clean UTC times")
    void the_grid_is_local() {
        // Tbilisi is UTC+4, so a grid anchored at a UTC instant would land on :00 in UTC and be
        // right here by accident. Berlin in summer is UTC+2 and a 45-minute stride makes the
        // difference visible: the local times must stay clean whatever the offset is.
        assertThat(localTimesOf(SlotGenerator.candidateStarts(ORDINARY, BERLIN, 45), BERLIN))
                .startsWith(LocalTime.of(0, 0), LocalTime.of(0, 45), LocalTime.of(1, 30));
    }

    @Test
    @DisplayName("spring forward: the local times that do not exist are skipped, not shifted")
    void spring_forward_skips_the_missing_hour() {
        List<LocalTime> times = localTimesOf(SlotGenerator.candidateStarts(SPRING_FORWARD, BERLIN, 30), BERLIN);

        // 02:00 and 02:30 name no instant on this date. Naive conversion would move them to 03:00
        // and 03:30 — where two candidates already are — and the day would silently gain duplicates.
        assertThat(times).doesNotContain(LocalTime.of(2, 0), LocalTime.of(2, 30));
        assertThat(times).contains(LocalTime.of(1, 30), LocalTime.of(3, 0), LocalTime.of(3, 30));
        assertThat(times).hasSize(46).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("spring forward: the candidates either side of the gap are one stride apart in real time")
    void spring_forward_leaves_no_hole_in_real_time() {
        List<Instant> starts = SlotGenerator.candidateStarts(SPRING_FORWARD, BERLIN, 60);

        assertThat(starts).contains(Instant.parse("2026-03-29T00:00:00Z"), Instant.parse("2026-03-29T01:00:00Z"));
        // 01:00 local (+01:00) is 00:00Z; the next candidate is 03:00 local (+02:00), also one hour
        // later in real time. The clock jumped; the grid did not.
        assertThat(starts).hasSize(23);
    }

    @Test
    @DisplayName("fall back: the repeated hour is offered once, at its first occurrence")
    void fall_back_offers_the_repeated_hour_once() {
        List<Instant> starts = SlotGenerator.candidateStarts(FALL_BACK, BERLIN, 60);
        List<LocalTime> times = localTimesOf(starts, BERLIN);

        assertThat(times).hasSize(24).doesNotHaveDuplicates();
        assertThat(times).filteredOn(LocalTime.of(2, 0)::equals).hasSize(1);
        // The earlier instant: 02:00 still on CEST (+02:00), not the 02:00 an hour later on CET.
        assertThat(starts).contains(Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(starts).doesNotContain(Instant.parse("2026-10-25T01:00:00Z"));
    }

    @Test
    @DisplayName("fall back: the day is 25 hours long but still has a day's worth of candidates")
    void fall_back_does_not_lengthen_the_grid() {
        assertThat(SlotGenerator.candidateStarts(FALL_BACK, BERLIN, 15)).hasSize(96);
    }

    @Test
    @DisplayName("a zone with no daylight saving behaves identically on all three dates")
    void a_zone_without_dst_is_unremarkable() {
        for (LocalDate date : List.of(ORDINARY, SPRING_FORWARD, FALL_BACK)) {
            List<Instant> starts = SlotGenerator.candidateStarts(date, TBILISI, 30);
            assertThat(starts).as("%s in Tbilisi", date).hasSize(48);
            assertThat(localTimesOf(starts, TBILISI).getFirst()).isEqualTo(LocalTime.MIDNIGHT);
        }
    }

    @Test
    @DisplayName("candidates come back in ascending order of real time")
    void candidates_are_ordered() {
        for (LocalDate date : List.of(ORDINARY, SPRING_FORWARD, FALL_BACK)) {
            assertThat(SlotGenerator.candidateStarts(date, BERLIN, 20))
                    .as("%s", date)
                    .isSorted();
        }
    }
}
