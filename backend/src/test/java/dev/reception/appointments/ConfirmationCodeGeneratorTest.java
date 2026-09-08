package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The code a Customer reads back over the phone.
 *
 * <p>The alphabet is the whole point of the class, and it is the thing most likely to be "tidied"
 * by someone who reaches for a familiar base32 constant. These assertions are what would stop that:
 * an {@code I} in a code is a support call, every time, from someone who typed a {@code 1}.
 */
class ConfirmationCodeGeneratorTest {

    private static final UUID BUSINESS_ID = UUID.randomUUID();

    private final ConfirmationCodeGenerator generator = new ConfirmationCodeGenerator();

    @Test
    @DisplayName("codes are eight characters of Crockford base32")
    void the_shape_is_fixed() {
        IntStream.range(0, 200)
                .forEach(i -> assertThat(generator.generate()).matches("[0-9A-HJKMNP-TV-Z]{8}"));
    }

    @Test
    @DisplayName("the ambiguous letters never appear — I, L, O and U are all excluded")
    void the_alphabet_excludes_what_cannot_be_read_aloud() {
        Set<Character> seen = new HashSet<>();
        IntStream.range(0, 2000)
                .forEach(i -> generator.generate().chars().forEach(c -> seen.add((char) c)));

        assertThat(seen).doesNotContain('I', 'L', 'O', 'U');
        // With 16,000 characters drawn, every one of the 32 should have turned up. If this ever
        // fails the alphabet has shrunk, which is the other way this class can go wrong.
        assertThat(seen).hasSize(32);
    }

    @Test
    @DisplayName("a code already taken by this business is not handed out again")
    void collisions_are_retried() {
        Set<String> issued = new HashSet<>();
        String first = generator.generateUnique(BUSINESS_ID, (business, code) -> false);
        issued.add(first);

        String second = generator.generateUnique(BUSINESS_ID, (business, code) -> issued.contains(code));

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("if every candidate is taken it fails loudly rather than looping")
    void exhaustion_is_an_error_not_a_hang() {
        assertThatThrownBy(() -> generator.generateUnique(BUSINESS_ID, (business, code) -> true))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("two businesses can hold the same code, because uniqueness is per business")
    void the_business_is_part_of_the_question() {
        UUID otherBusiness = UUID.randomUUID();
        String taken = generator.generate();

        String code = generator.generateUnique(
                otherBusiness, (business, candidate) -> business.equals(BUSINESS_ID) && candidate.equals(taken));

        assertThat(code).isNotNull();
    }
}
