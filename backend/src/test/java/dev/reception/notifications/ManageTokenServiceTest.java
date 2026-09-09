package dev.reception.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Manage Link is the only credential a Customer ever holds. Everything below is a way for it to
 * be forged, replayed or outlived, and each one has to fail.
 */
class ManageTokenServiceTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-to-be-realistic";
    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-09-10T15:00:00Z");

    private final ManageTokenService tokens = serviceAt(NOW, SECRET);

    private static ManageTokenService serviceAt(Instant now, String secret) {
        return new ManageTokenService(secret, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void a_token_round_trips_to_the_appointment_it_was_issued_for() {
        UUID appointmentId = UUID.randomUUID();

        assertThat(tokens.verify(tokens.issue(appointmentId, ENDS_AT))).contains(appointmentId);
    }

    @Test
    void two_appointments_do_not_share_a_token() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(tokens.issue(first, ENDS_AT)).isNotEqualTo(tokens.issue(second, ENDS_AT));
        assertThat(tokens.verify(tokens.issue(first, ENDS_AT))).contains(first);
    }

    @Test
    void a_token_survives_until_twenty_four_hours_after_the_appointment_ends() {
        String token = tokens.issue(UUID.randomUUID(), ENDS_AT);

        assertThat(serviceAt(ENDS_AT.plusSeconds(23 * 3600), SECRET).verify(token)).isPresent();
        assertThat(serviceAt(ENDS_AT.plusSeconds(24 * 3600 + 1), SECRET).verify(token))
                .isEmpty();
    }

    /**
     * The payload is readable — it is base64, not encryption — so an attacker can and will edit it.
     * What stops them is that the signature covers it.
     */
    @Test
    void editing_the_payload_invalidates_the_token() {
        String token = tokens.issue(UUID.randomUUID(), ENDS_AT);
        String forged = tokens.issue(UUID.randomUUID(), ENDS_AT);

        // Somebody else's payload with this token's signature, and the reverse.
        String swapped = forged.substring(0, forged.indexOf('.')) + token.substring(token.indexOf('.'));

        assertThat(tokens.verify(swapped)).isEmpty();
    }

    @Test
    void editing_the_signature_invalidates_the_token() {
        String token = tokens.issue(UUID.randomUUID(), ENDS_AT);
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(tokens.verify(tampered)).isEmpty();
    }

    /** A token minted against a different deployment's secret must not open this one's appointment. */
    @Test
    void a_token_signed_with_another_secret_is_refused() {
        String foreign = serviceAt(NOW, "a-completely-different-secret-of-the-same-sort")
                .issue(UUID.randomUUID(), ENDS_AT);

        assertThat(tokens.verify(foreign)).isEmpty();
    }

    /**
     * Every malformed shape returns the same empty result rather than throwing. The endpoint that
     * will consume these in phase 08 is public, and an exception that escaped would be a 500 telling
     * a prober which of their guesses was closest.
     */
    @Test
    void nothing_that_is_not_a_token_throws() {
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("   ")).isEmpty();
        assertThat(tokens.verify("no-dot-at-all")).isEmpty();
        assertThat(tokens.verify(".")).isEmpty();
        assertThat(tokens.verify(".signature")).isEmpty();
        assertThat(tokens.verify("payload.")).isEmpty();
        assertThat(tokens.verify("not!base64.not!base64")).isEmpty();
        assertThat(tokens.verify("a.b.c")).isEmpty();
    }
}
