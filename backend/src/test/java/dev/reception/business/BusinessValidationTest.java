package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The field rules that need a registry rather than a regex.
 *
 * <p>Every one of these is a rule a hand-written list would eventually get wrong: zones are
 * renamed, currencies are redenominated, countries change codes. Asking the JDK is the only version
 * of these rules that stays true without anyone maintaining it.
 */
class BusinessValidationTest {

    private static boolean accepts(java.util.function.Consumer<BusinessValidation> rule) {
        BusinessValidation validation = new BusinessValidation();
        rule.accept(validation);
        return validation.isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Tbilisi", "Europe/London", "America/New_York"})
    @DisplayName("real IANA zones are accepted")
    void accepts_real_timezones(String zone) {
        assertThat(ZoneId.getAvailableZoneIds()).contains(zone);
        assertThat(accepts(v -> v.timezone("timezone", zone))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Atlantis", "GMT+04:00", "Tbilisi", "", "utc"})
    @DisplayName("anything ZoneId does not know is rejected, including a zone whose case is wrong")
    void rejects_unknown_timezones(String zone) {
        assertThat(accepts(v -> v.timezone("timezone", zone))).isFalse();
    }

    @Test
    @DisplayName("an absent timezone is not a failure — this is a PATCH")
    void ignores_an_absent_timezone() {
        assertThat(accepts(v -> v.timezone("timezone", null))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "GEL", "EUR", "JPY"})
    void accepts_real_currencies(String currency) {
        assertThat(accepts(v -> v.currency("currency", currency))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "US", "USDD", "usd", ""})
    @DisplayName("a code ISO-4217 does not list is rejected, and so is the lowercase form")
    void rejects_unknown_currencies(String currency) {
        assertThat(accepts(v -> v.currency("currency", currency))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GE", "US", "GB", "JP"})
    void accepts_real_countries(String country) {
        assertThat(accepts(v -> v.country("country", country))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZ", "GEO", "g", "ge"})
    void rejects_unknown_countries(String country) {
        assertThat(accepts(v -> v.country("country", country))).isFalse();
    }

    @Test
    @DisplayName("a blank country is accepted — blank clears an optional field")
    void accepts_a_blank_country() {
        assertThat(accepts(v -> v.country("country", ""))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"salon-aria", "aria", "a1", "salon-aria-2", "123"})
    void accepts_well_formed_slugs(String slug) {
        assertThat(accepts(v -> v.slug("slug", slug))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Salon-Aria", "salon aria", "-aria", "aria-", "salon--aria", "salon_aria", "", "salon.aria"})
    @DisplayName("anything the schema's own CHECK would refuse is refused here first, with a sentence")
    void rejects_malformed_slugs(String slug) {
        assertThat(accepts(v -> v.slug("slug", slug))).isFalse();
    }

    @Test
    @DisplayName("a slug longer than the column is rejected rather than truncated")
    void rejects_an_overlong_slug() {
        assertThat(accepts(v -> v.slug("slug", "a".repeat(141)))).isFalse();
    }
}
