package dev.reception.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.FieldError;
import dev.reception.staff.EmployeeScheduleService.Interval;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Working Schedule rules, tested without a database, a Spring context or a tenant — which is
 * what {@code validateWeek} being static and package-private buys.
 *
 * <p>Deliberately the same cases as {@code BusinessHoursValidationTest}. The two editors are the
 * same control and phase 05 intersects the two lists, so a rule that held for one and not the other
 * would be a bug that only showed up as unexplained missing availability.
 */
class EmployeeScheduleValidationTest {

    private static Interval on(DayOfWeek day, String starts, String ends) {
        return new Interval(day, LocalTime.parse(starts), LocalTime.parse(ends));
    }

    private static List<FieldError> validate(List<Interval> week) {
        try {
            EmployeeScheduleService.validateWeek(week);
            return List.of();
        } catch (ApiException e) {
            return e.fieldErrors();
        }
    }

    @Test
    @DisplayName("an empty week is valid — this person works no fixed days")
    void accepts_an_empty_week() {
        assertThatCode(() -> EmployeeScheduleService.validateWeek(List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an ordinary week is valid")
    void accepts_an_ordinary_week() {
        assertThat(validate(List.of(
                        on(DayOfWeek.MONDAY, "09:00", "17:00"),
                        on(DayOfWeek.TUESDAY, "09:00", "17:00"),
                        on(DayOfWeek.SATURDAY, "10:00", "14:00"))))
                .isEmpty();
    }

    @Test
    @DisplayName("two intervals on one day are a split shift, not a mistake")
    void accepts_a_split_shift() {
        assertThat(validate(List.of(on(DayOfWeek.MONDAY, "09:00", "13:00"), on(DayOfWeek.MONDAY, "14:00", "18:00"))))
                .isEmpty();
    }

    @Test
    @DisplayName("intervals that touch exactly are accepted")
    void accepts_adjacent_intervals() {
        // 09:00-13:00 and 13:00-17:00 is one shift written in two rows. Refusing it would forbid the
        // most obvious way to express a day with a nominal break in it.
        assertThat(validate(List.of(on(DayOfWeek.MONDAY, "09:00", "13:00"), on(DayOfWeek.MONDAY, "13:00", "17:00"))))
                .isEmpty();
    }

    @Test
    @DisplayName("overlapping intervals on one day are refused, and the message lands on the later one")
    void rejects_an_overlap() {
        List<FieldError> errors =
                validate(List.of(on(DayOfWeek.MONDAY, "09:00", "13:00"), on(DayOfWeek.MONDAY, "12:00", "17:00")));

        // Index 1, not 0: the later interval is the one the owner most likely just added, and it is
        // the row their eye is already on.
        assertThat(errors).extracting(FieldError::field).containsExactly("schedule[1].startsAt");
    }

    @Test
    @DisplayName("an overlap is named by its position in the submitted array, not by its day")
    void names_the_overlap_by_its_submitted_position() {
        // The array holds Wednesday first. An index read as a day number would put this message on
        // Monday, which is a message that is confidently wrong.
        List<FieldError> errors = validate(List.of(
                on(DayOfWeek.WEDNESDAY, "09:00", "17:00"),
                on(DayOfWeek.THURSDAY, "09:00", "17:00"),
                on(DayOfWeek.THURSDAY, "16:00", "20:00")));

        assertThat(errors).extracting(FieldError::field).containsExactly("schedule[2].startsAt");
    }

    @Test
    @DisplayName("the same times on two different days do not overlap")
    void does_not_confuse_days() {
        assertThat(validate(List.of(on(DayOfWeek.MONDAY, "09:00", "17:00"), on(DayOfWeek.TUESDAY, "09:00", "17:00"))))
                .isEmpty();
    }

    @Test
    @DisplayName("an end before its start is refused")
    void rejects_an_inverted_interval() {
        assertThat(validate(List.of(on(DayOfWeek.MONDAY, "17:00", "09:00"))))
                .extracting(FieldError::field)
                .containsExactly("schedule[0].endsAt");
    }

    @Test
    @DisplayName("a zero-length interval is refused along with an inverted one")
    void rejects_a_zero_length_interval() {
        // Equal is not a shift, and a schedule cannot cross midnight — the same rule the CHECK
        // constraint states.
        assertThat(validate(List.of(on(DayOfWeek.MONDAY, "09:00", "09:00")))).hasSize(1);
    }

    @Test
    @DisplayName("every failure in a week is reported at once")
    void collects_every_failure() {
        List<FieldError> errors = validate(List.of(
                on(DayOfWeek.MONDAY, "17:00", "09:00"),
                on(DayOfWeek.TUESDAY, "09:00", "13:00"),
                on(DayOfWeek.TUESDAY, "12:00", "18:00")));

        assertThat(errors)
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("schedule[0].endsAt", "schedule[2].startsAt");
    }

    @Test
    @DisplayName("the failure is an ApiException carrying VALIDATION_FAILED")
    void fails_as_a_validation_problem() {
        assertThatThrownBy(() -> EmployeeScheduleService.validateWeek(List.of(on(DayOfWeek.MONDAY, "17:00", "09:00"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(dev.reception.common.error.ErrorCode.VALIDATION_FAILED));
    }
}
