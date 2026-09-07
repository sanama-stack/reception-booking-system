package dev.reception.staff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Local phone input into E.164, resolved against the Business's country.
 *
 * <p>This is load-bearing from phase 06, where a Customer is identified by
 * {@code (business_id, normalised phone)}: two spellings of one number that normalise differently
 * become two customers with two separate histories, and nothing notices.
 */
class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource({
        // The same Georgian mobile, written the four ways a person actually types it.
        "555123456,     GE, +995555123456",
        "555 12 34 56,  GE, +995555123456",
        "(555) 12-34-56,GE, +995555123456",
        "0555123456,    GE, +995555123456",
    })
    @DisplayName("one number written several ways normalises to one string")
    void normalises_local_spellings_to_one_form(String raw, String country, String expected) {
        assertThat(PhoneNumbers.toE164(raw, country)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "+995 555 12 34 56, GE, +995555123456",
        "+44 20 7946 0958,  GE, +442079460958",
        "+1 415 555 2671,   GE, +14155552671",
    })
    @DisplayName("a number that already carries its country code is taken at its word")
    void accepts_international_form(String raw, String country, String expected) {
        // The + makes the number self-describing, so the business's country is irrelevant — which is
        // how an employee abroad is reachable at all.
        assertThat(PhoneNumbers.toE164(raw, country)).contains(expected);
    }

    @Test
    @DisplayName("the same digits mean different numbers in different countries")
    void resolves_a_local_number_against_the_business_country() {
        // The point of carrying the country at all. Reading this as one number would merge two
        // people in phase 06.
        assertThat(PhoneNumbers.toE164("2079460958", "GB")).contains("+442079460958");
        assertThat(PhoneNumbers.toE164("4155552671", "US")).contains("+14155552671");
    }

    @Test
    @DisplayName("a business with no country set can still accept an international number")
    void accepts_international_form_without_a_country() {
        assertThat(PhoneNumbers.toE164("+995555123456", null)).contains("+995555123456");
    }

    @Test
    @DisplayName("a local number cannot be understood without a country, and is refused rather than guessed")
    void refuses_a_local_number_without_a_country() {
        // Guessing a region here is how a number silently becomes someone else's.
        assertThat(PhoneNumbers.toE164("555123456", null)).isEmpty();
        assertThat(PhoneNumbers.toE164("555123456", "")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12", "not a number", "+", "999999999999999999", "555-CALL-NOW"})
    @DisplayName("unparseable input is refused rather than stored as typed")
    void refuses_unparseable_input(String raw) {
        assertThat(PhoneNumbers.toE164(raw, "GE")).isEmpty();
    }

    @Test
    @DisplayName("a number of the right length that is not a real range is still refused")
    void refuses_a_possible_but_invalid_number() {
        // isValidNumber, not isPossibleNumber: length alone would accept a typo that no one can be
        // reached on, and store it as though it were a way to contact someone.
        assertThat(PhoneNumbers.toE164("+995111111111", null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank is empty rather than an error — the caller decides what blank means")
    void treats_blank_as_absent(String raw) {
        // For an Employee, blank means "clear the field", not "this is a bad number".
        assertThat(PhoneNumbers.toE164(raw, "GE")).isEmpty();
    }

    @Test
    @DisplayName("null is empty")
    void treats_null_as_absent() {
        assertThat(PhoneNumbers.toE164(null, "GE")).isEmpty();
    }

    @Test
    @DisplayName("normalisation is idempotent — running it twice changes nothing")
    void is_idempotent() {
        // The property phase 06's customer lookup depends on: a stored number re-normalises to
        // itself, so a repeat visit finds the existing record instead of creating a second one.
        String once = PhoneNumbers.toE164("555 12 34 56", "GE").orElseThrow();
        assertThat(PhoneNumbers.toE164(once, "GE")).contains(once);
        assertThat(PhoneNumbers.toE164(once, null)).contains(once);
    }
}
