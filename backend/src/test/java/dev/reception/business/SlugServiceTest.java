package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Slug derivation, tested against the inputs a real business name actually takes: accents, other
 * scripts, punctuation and collisions. The slug is a public URL, so "it worked for Salon Aria" is
 * not evidence.
 */
class SlugServiceTest {

    private final SlugService slugs = new SlugService(null, new Random(42));

    @ParameterizedTest
    @CsvSource({
        "Salon Aria,           salon-aria",
        "SALON ARIA,           salon-aria",
        "  Salon   Aria  ,     salon-aria",
        "Dato's Auto,          dato-s-auto",
        "Café Aría,            cafe-aria",
        "Björn & Sons,         bjorn-sons",
        "Hair 24/7,            hair-24-7",
        "--Leading--Trailing--,leading-trailing",
    })
    void derives_a_url_safe_slug(String name, String expected) {
        assertThat(slugs.derive(name)).isEqualTo(expected);
    }

    /**
     * A name written in a script with no Latin characters reduces to nothing. That is not an error
     * — the business still needs a page — so the caller substitutes a fallback rather than the
     * derivation inventing a transliteration it cannot do correctly.
     */
    @ParameterizedTest
    @CsvSource({"სალონი არია", "Салон", "美容室", "!!!", "---", "'   '"})
    void a_name_with_nothing_usable_derives_to_empty(String name) {
        assertThat(slugs.derive(name)).isEmpty();
    }

    @Test
    void a_punctuation_only_name_still_gets_a_usable_slug() {
        String slug = slugs.deriveUnique("!!!", candidate -> true);

        assertThat(slug).startsWith("business-").matches("^[a-z0-9]+(-[a-z0-9]+)*$");
    }

    @Test
    void a_non_latin_name_still_gets_a_usable_slug() {
        String slug = slugs.deriveUnique("სალონი არია", candidate -> true);

        assertThat(slug).startsWith("business-").matches("^[a-z0-9]+(-[a-z0-9]+)*$");
    }

    @Test
    void collisions_are_suffixed_in_the_order_an_owner_would_expect() {
        Set<String> taken = new HashSet<>(Set.of("salon-aria", "salon-aria-2", "salon-aria-3"));

        assertThat(slugs.deriveUnique("Salon Aria", candidate -> !taken.contains(candidate)))
                .isEqualTo("salon-aria-4");
    }

    /**
     * Past a handful of attempts the numeric candidates are being taken by concurrent
     * registrations, and counting further is a race a random suffix simply does not have.
     */
    @Test
    void exhausting_the_numeric_suffixes_falls_back_to_a_random_one() {
        Set<String> taken = new HashSet<>();
        taken.add("salon-aria");
        for (int i = 2; i <= 9; i++) {
            taken.add("salon-aria-" + i);
        }

        String slug = slugs.deriveUnique("Salon Aria", candidate -> !taken.contains(candidate));

        assertThat(slug).startsWith("salon-aria-").doesNotMatch("^salon-aria-\\d$");
        assertThat(slug).matches("^[a-z0-9]+(-[a-z0-9]+)*$");
    }

    @Test
    void a_very_long_name_is_truncated_to_fit_the_column_including_its_suffix() {
        String name = "a".repeat(300) + " very long";

        String base = slugs.derive(name);
        String suffixed = slugs.deriveUnique(name, candidate -> !candidate.equals(base));

        assertThat(base).hasSizeLessThanOrEqualTo(140);
        assertThat(suffixed).hasSizeLessThanOrEqualTo(140).matches("^[a-z0-9]+(-[a-z0-9]+)*$");
    }
}
