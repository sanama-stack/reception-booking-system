package dev.reception.common.phone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The words a refused phone number comes back with.
 *
 * <p>Copy is usually not worth a unit test. This copy is, because it is the only place in the
 * application where a message may name a remedy <em>one audience cannot reach</em>, and getting it
 * wrong fails silently: phase 08 told Customers to change a Settings field they have no account for,
 * and nothing broke — the sentence was simply impossible to act on. A test is what turns that back
 * into something that fails.
 *
 * <p>The wiring — that each entry point passes the right {@link PhoneAudience} — is proved at the
 * endpoints instead, in {@code PublicBookingTest} and {@code BookingEndpointTest}. A matrix here
 * would pass with every controller passing the same wrong constant.
 */
class PhoneFieldTest {

    /** What an owner is offered and a Customer must never be. */
    private static final String OWNER_ONLY_REMEDY = "Settings";

    @ParameterizedTest
    @EnumSource(PhoneAudience.class)
    @DisplayName("with a country set, both audiences get the same actionable sentence")
    void a_country_makes_the_audience_irrelevant(PhoneAudience audience) {
        String message = PhoneField.unreadableMessage("GE", audience);

        assertThat(message).doesNotContain(OWNER_ONLY_REMEDY);
        assertThat(message).contains("international form starting with +");
    }

    @Test
    @DisplayName("with no country, an owner is told about the setting that would fix it")
    void an_owner_is_offered_the_country_setting() {
        String message = PhoneField.unreadableMessage(null, PhoneAudience.OWNER);

        assertThat(message).contains(OWNER_ONLY_REMEDY);
        assertThat(message).contains("international form");
    }

    @ParameterizedTest
    @EnumSource(PhoneAudience.class)
    @DisplayName("a blank country is treated as no country, not as a country called \"\"")
    void blank_is_unset(PhoneAudience audience) {
        assertThat(PhoneField.unreadableMessage("   ", audience))
                .isEqualTo(PhoneField.unreadableMessage(null, audience));
    }

    /**
     * The regression this whole change exists for.
     *
     * <p>Asserted as an absence, which is weaker than asserting a string and is the right shape
     * anyway: what must hold is that no wording ever sends a Customer to the dashboard, and pinning
     * one sentence would let a reworded one reintroduce it.
     */
    @Test
    @DisplayName("with no country, a Customer is never sent to a screen they cannot reach")
    void a_customer_is_never_sent_to_settings() {
        String message = PhoneField.unreadableMessage(null, PhoneAudience.CUSTOMER);

        assertThat(message).doesNotContain(OWNER_ONLY_REMEDY);
        assertThat(message).doesNotContainIgnoringCase("dashboard");
        assertThat(message).doesNotContainIgnoringCase("your account");
        // And still says what to do, because a refusal that only refuses is its own defect.
        assertThat(message).contains("+");
    }
}
