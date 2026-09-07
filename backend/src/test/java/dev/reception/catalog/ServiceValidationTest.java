package dev.reception.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.FieldError;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The numeric rules a Service has to satisfy, tested without a database, a Spring context or a
 * tenant — which is the whole reason {@link ServiceValidation} is a plain collecting object rather
 * than a set of annotations spread over two request records.
 */
class ServiceValidationTest {

    private static boolean accepts(Consumer<ServiceValidation> rule) {
        return failures(rule).isEmpty();
    }

    /** The messages a rule produced, so a test can assert how many as well as whether. */
    private static List<FieldError> failures(Consumer<ServiceValidation> rule) {
        ServiceValidation validation = new ServiceValidation();
        rule.accept(validation);
        try {
            validation.throwIfFailed();
            return List.of();
        } catch (ApiException e) {
            return e.fieldErrors();
        }
    }

    // -----------------------------------------------------------------------
    // Duration
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {5, 15, 30, 45, 60, 90, 480, 1440})
    @DisplayName("a duration on the five-minute grid and inside the bounds is accepted")
    void accepts_durations_on_the_grid(int minutes) {
        assertThat(accepts(v -> v.duration("durationMinutes", minutes))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 7, 13, 46, 91})
    @DisplayName("a duration off the five-minute grid is refused")
    void rejects_durations_off_the_grid(int minutes) {
        // Not pedantry: slots are generated on a stride the owner picks from {5,10,15,20,30,60}, and
        // a duration that does not land on it produces start times that drift across the day.
        assertThat(accepts(v -> v.duration("durationMinutes", minutes))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 1445, 2880})
    @DisplayName("a duration outside five minutes to twenty-four hours is refused")
    void rejects_durations_outside_the_bounds(int minutes) {
        assertThat(accepts(v -> v.duration("durationMinutes", minutes))).isFalse();
    }

    @Test
    @DisplayName("a duration that is both out of range and off the grid is reported once")
    void reports_a_bad_duration_once() {
        // 3 fails both rules. One field, one sentence — two messages on one input reads as two
        // separate mistakes and sends the owner looking for a second one.
        assertThat(failures(v -> v.duration("durationMinutes", 3))).hasSize(1);
    }

    @Test
    @DisplayName("an absent duration is not a failure — a patch may leave it alone")
    void skips_an_absent_duration() {
        assertThat(accepts(v -> v.duration("durationMinutes", null))).isTrue();
    }

    // -----------------------------------------------------------------------
    // Buffers
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 15, 240})
    @DisplayName("a buffer between zero and four hours is accepted, and zero is the common case")
    void accepts_buffers_in_range(int minutes) {
        assertThat(accepts(v -> v.buffer("bufferAfterMinutes", minutes))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 241, 1000})
    @DisplayName("a negative or oversized buffer is refused")
    void rejects_buffers_out_of_range(int minutes) {
        assertThat(accepts(v -> v.buffer("bufferAfterMinutes", minutes))).isFalse();
    }

    @Test
    @DisplayName("a buffer is not held to the five-minute grid")
    void does_not_hold_buffers_to_the_grid() {
        // Deliberate: a buffer pads the employee's occupancy, it does not start a slot. Holding it
        // to the grid would refuse "13 minutes to clean the chair" for no benefit.
        assertThat(accepts(v -> v.buffer("bufferAfterMinutes", 13))).isTrue();
    }

    // -----------------------------------------------------------------------
    // Price
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("zero is a legitimate price")
    void accepts_a_free_service() {
        // A free consultation is a service. Refusing to price one at nothing would make the owner
        // invent a fake number.
        assertThat(accepts(v -> v.price("price", BigDecimal.ZERO))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "5", "60.00", "1234567890.99"})
    @DisplayName("a non-negative price with at most two decimal places is accepted")
    void accepts_valid_prices(String amount) {
        assertThat(accepts(v -> v.price("price", new BigDecimal(amount)))).isTrue();
    }

    @Test
    @DisplayName("a negative price is refused")
    void rejects_a_negative_price() {
        assertThat(accepts(v -> v.price("price", new BigDecimal("-0.01")))).isFalse();
    }

    @Test
    @DisplayName("a third decimal place is refused rather than rounded away")
    void rejects_more_than_two_decimal_places() {
        // numeric(12,2) would round it silently, and the owner would be shown a price they did not
        // type. Refusing is the honest answer.
        assertThat(accepts(v -> v.price("price", new BigDecimal("60.005")))).isFalse();
    }

    @Test
    @DisplayName("a price too wide for numeric(12,2) is refused as a field error, not a driver error")
    void rejects_a_price_wider_than_the_column() {
        assertThat(accepts(v -> v.price("price", new BigDecimal("12345678901.00")))).isFalse();
    }

    // -----------------------------------------------------------------------
    // Collection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("every failure is collected, so one submission reports all of them")
    void collects_every_failure() {
        ServiceValidation validation = new ServiceValidation()
                .duration("durationMinutes", 7)
                .buffer("bufferBeforeMinutes", -1)
                .price("price", new BigDecimal("-5"));

        assertThat(validation.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("three bad fields produce three messages, named by field")
    void names_every_failing_field() {
        // The owner should learn about all three at once rather than submitting three times.
        List<FieldError> errors = failures(v -> v.duration("durationMinutes", 7)
                .buffer("bufferBeforeMinutes", -1)
                .price("price", new BigDecimal("-5")));

        assertThat(errors).extracting(FieldError::field)
                .containsExactly("durationMinutes", "bufferBeforeMinutes", "price");
    }
}
