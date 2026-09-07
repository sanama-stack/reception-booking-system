package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.reception.business.BusinessHoursService.Interval;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole-week rules, tested as the pure function they are.
 *
 * <p>The case that matters most is the one that is easy to get wrong in the obvious direction:
 * 09:00–13:00 followed by 13:00–17:00 is a split shift, not an overlap. A validator written with
 * {@code <=} rejects it and forbids the most natural way to express a lunch break.
 */
class BusinessHoursValidationTest {

    private static Interval interval(DayOfWeek day, String opens, String closes) {
        return new Interval(day, LocalTime.parse(opens), LocalTime.parse(closes));
    }

    @Test
    @DisplayName("an ordinary week is accepted")
    void accepts_a_plain_week() {
        assertThatCode(() -> BusinessHoursService.validateWeek(List.of(
                        interval(DayOfWeek.MONDAY, "09:00", "17:00"),
                        interval(DayOfWeek.TUESDAY, "09:00", "17:00"),
                        interval(DayOfWeek.SATURDAY, "10:00", "14:00"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty week is accepted — a business closed every day is a legitimate state")
    void accepts_an_empty_week() {
        assertThatCode(() -> BusinessHoursService.validateWeek(List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adjacent intervals on one day are a split shift, not an overlap")
    void allows_adjacent_intervals() {
        assertThatCode(() -> BusinessHoursService.validateWeek(List.of(
                        interval(DayOfWeek.MONDAY, "09:00", "13:00"),
                        interval(DayOfWeek.MONDAY, "13:00", "17:00"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("overlapping intervals on one day are rejected")
    void rejects_overlapping_intervals() {
        assertThatThrownBy(() -> BusinessHoursService.validateWeek(List.of(
                        interval(DayOfWeek.MONDAY, "09:00", "14:00"),
                        interval(DayOfWeek.MONDAY, "13:00", "17:00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors())
                            .extracting(FieldError::field)
                            // The later interval, which is the one the owner most likely just added.
                            .containsExactly("hours[1].opensAt");
                });
    }

    @Test
    @DisplayName("an interval overlapping one submitted before it is still caught")
    void detects_overlap_regardless_of_submitted_order() {
        assertThatThrownBy(() -> BusinessHoursService.validateWeek(List.of(
                        interval(DayOfWeek.MONDAY, "13:00", "17:00"),
                        interval(DayOfWeek.MONDAY, "09:00", "14:00"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("intervals that overlap only across different days are fine")
    void does_not_compare_intervals_on_different_days() {
        assertThatCode(() -> BusinessHoursService.validateWeek(List.of(
                        interval(DayOfWeek.MONDAY, "09:00", "17:00"),
                        interval(DayOfWeek.TUESDAY, "09:00", "17:00"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("closing before opening is rejected")
    void rejects_inverted_interval() {
        assertThatThrownBy(() -> BusinessHoursService.validateWeek(
                        List.of(interval(DayOfWeek.MONDAY, "17:00", "09:00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fieldErrors())
                        .extracting(FieldError::field)
                        .containsExactly("hours[0].closesAt"));
    }

    @Test
    @DisplayName("closing at the opening time is rejected — a zero-length opening is not a shift")
    void rejects_zero_length_interval() {
        assertThatThrownBy(() -> BusinessHoursService.validateWeek(
                        List.of(interval(DayOfWeek.MONDAY, "09:00", "09:00"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("every failure in the week is reported at once, not one per submission")
    void reports_all_failures_together() {
        assertThatThrownBy(() -> BusinessHoursService.validateWeek(List.of(
                        interval(DayOfWeek.MONDAY, "17:00", "09:00"),
                        interval(DayOfWeek.TUESDAY, "09:00", "14:00"),
                        interval(DayOfWeek.TUESDAY, "13:00", "17:00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fieldErrors())
                        .extracting(FieldError::field)
                        .containsExactlyInAnyOrder("hours[0].closesAt", "hours[2].opensAt"));
    }
}
