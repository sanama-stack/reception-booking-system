package dev.reception.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The stored form of a refresh token.
 *
 * <p>Worth its own test because the property is easy to lose and impossible to notice: if the raw
 * token were ever stored, a database dump would be a set of live sessions.
 */
class RefreshTokenHashingTest {

    @Test
    void the_stored_value_is_not_the_token() {
        String token = "L3xQ_wG4example-token-value";

        assertThat(RefreshTokenService.hash(token)).isNotEqualTo(token).doesNotContain(token);
    }

    @Test
    void hashing_is_deterministic_so_the_lookup_is_an_indexed_equality_match() {
        assertThat(RefreshTokenService.hash("same")).isEqualTo(RefreshTokenService.hash("same"));
    }

    @Test
    void different_tokens_hash_differently() {
        assertThat(RefreshTokenService.hash("one")).isNotEqualTo(RefreshTokenService.hash("two"));
    }

    /** 64 lowercase hex characters — the width the column was sized for. */
    @Test
    void the_hash_fits_the_column_it_was_sized_for() {
        assertThat(RefreshTokenService.hash("anything")).hasSize(64).matches("^[0-9a-f]{64}$");
    }
}
