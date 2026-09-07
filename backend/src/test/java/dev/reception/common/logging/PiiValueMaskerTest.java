package dev.reception.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The redaction list in docs/06-security.md §10 is a promise. This is where it becomes a check.
 */
class PiiValueMaskerTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "password=hunter2|password=[REDACTED]",
                "\"password\": \"hunter2\"|\"password\": \"[REDACTED]\"",
                "refresh_token=abc123def456|refresh_token=[REDACTED]",
                "Authorization: Bearer abc.def|Authorization: [REDACTED]",
                "manage_link_token=deadbeefcafe|manage_link_token=[REDACTED]",
                "api_key=xyz789|api_key=[REDACTED]",
                "confirmation_code=8HJ4K2M9|confirmation_code=[REDACTED]",
                "manage_token=deadbeefcafe|manage_token=[REDACTED]",
            })
    void secrets_carried_in_key_value_pairs_are_redacted(String input, String expected) {
        assertThat(PiiValueMasker.redact(input)).isEqualTo(expected);
    }

    @Test
    void jwt_shaped_values_are_redacted_wherever_they_appear() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1g";

        assertThat(PiiValueMasker.redact("cookie carried " + jwt + " onward"))
                .isEqualTo("cookie carried [REDACTED_JWT] onward");
    }

    @Test
    void openai_shaped_api_keys_are_redacted() {
        assertThat(PiiValueMasker.redact("calling with sk-proj-AbCdEfGhIjKlMnOpQrSt"))
                .isEqualTo("calling with [REDACTED_API_KEY]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nino@aria.ge", "customer.name+tag@example.co.uk"})
    void customer_email_addresses_are_redacted(String email) {
        assertThat(PiiValueMasker.redact("booking for " + email)).doesNotContain(email);
    }

    @Test
    void customer_phone_numbers_are_redacted() {
        assertThat(PiiValueMasker.redact("reached +995599123456 successfully"))
                .isEqualTo("reached [REDACTED_PHONE] successfully");
    }

    @Test
    void a_customer_id_is_not_redacted_because_it_is_the_permitted_identifier() {
        String line = "customer_id=0198f3a1-7c2e-7a90-8b41-0f2c5d6e7a8b booked";

        assertThat(PiiValueMasker.redact(line)).isEqualTo(line);
    }

    @Test
    void an_ordinary_log_line_is_left_alone() {
        String line = "Applied migration V1__extensions.sql in 12ms";

        assertThat(PiiValueMasker.redact(line)).isEqualTo(line);
    }
}
