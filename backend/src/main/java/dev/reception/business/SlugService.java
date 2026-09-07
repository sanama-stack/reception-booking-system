package dev.reception.business;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Derives the lowercase, URL-safe identifier a business's public booking page lives at.
 *
 * <p>Two properties matter. The result must be a legal slug for <em>any</em> business name,
 * including one written in a non-Latin script or consisting entirely of punctuation — a business
 * called "!!!" still needs a page. And it must be unique, because the slug is the public URL.
 */
@Service
public class SlugService {

    private static final int MAX_LENGTH = 140;
    /** Beyond this, numeric suffixing is losing a race and a random suffix ends it. */
    private static final int MAX_NUMERIC_SUFFIX = 9;

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private static final String SLUG_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RANDOM_SUFFIX_LENGTH = 6;

    private final BusinessRepository businesses;
    private final RandomGenerator random;

    // Two constructors, so Spring is told which one rather than left to guess. The other exists
    // for tests that need a deterministic tie-break.
    @Autowired
    public SlugService(BusinessRepository businesses) {
        this(businesses, new SecureRandom());
    }

    SlugService(BusinessRepository businesses, RandomGenerator random) {
        this.businesses = businesses;
        this.random = random;
    }

    /**
     * Reduces a name to slug characters.
     *
     * <p>Accented Latin is decomposed and stripped to its base letters, so "Café Aría" becomes
     * "cafe-aria" rather than losing the words entirely. A name with no Latin characters at all —
     * Georgian, Greek, Cyrillic, emoji — reduces to nothing, and the caller substitutes a fallback
     * rather than producing an empty URL.
     *
     * @return the derived slug, or an empty string when the name contains nothing usable
     */
    public String derive(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String withoutAccents = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lowered = withoutAccents.toLowerCase(Locale.ROOT);
        String hyphenated = NON_SLUG.matcher(lowered).replaceAll("-");
        String trimmed = EDGE_HYPHENS.matcher(hyphenated).replaceAll("");
        return trimmed.length() > MAX_LENGTH ? EDGE_HYPHENS.matcher(trimmed.substring(0, MAX_LENGTH))
                .replaceAll("") : trimmed;
    }

    /** Derives a slug for {@code name} that no existing business holds. */
    public String deriveUnique(String name) {
        return deriveUnique(name, slug -> !businesses.existsBySlug(slug));
    }

    /**
     * The pure half, so the collision rules can be tested without a database.
     *
     * <p>Suffixing runs {@code -2}, {@code -3} … which is what an owner expects to see. After a few
     * attempts it switches to a random suffix instead: past that point the numeric candidates are
     * being taken by a concurrent registration, and continuing to count is a race that a random
     * suffix simply does not have.
     */
    String deriveUnique(String name, Predicate<String> isAvailable) {
        String base = derive(name);
        if (base.isEmpty()) {
            // Punctuation-only, or a script with no Latin characters. A page still has to exist.
            base = "business-" + shortRandom();
        }

        if (isAvailable.test(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_NUMERIC_SUFFIX; suffix++) {
            String candidate = truncateFor(base, "-" + suffix) + "-" + suffix;
            if (isAvailable.test(candidate)) {
                return candidate;
            }
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            String random = shortRandom();
            String candidate = truncateFor(base, "-" + random) + "-" + random;
            if (isAvailable.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not derive a unique slug for: " + name);
    }

    /** Keeps the result inside the column, and never leaves a trailing hyphen behind the cut. */
    private String truncateFor(String base, String suffix) {
        int room = MAX_LENGTH - suffix.length();
        String cut = base.length() <= room ? base : base.substring(0, room);
        return EDGE_HYPHENS.matcher(cut).replaceAll("");
    }

    /** Six lowercase alphanumerics — 36^6 combinations, which is ample for a tie-break. */
    private String shortRandom() {
        StringBuilder builder = new StringBuilder(RANDOM_SUFFIX_LENGTH);
        for (int i = 0; i < RANDOM_SUFFIX_LENGTH; i++) {
            builder.append(SLUG_ALPHABET.charAt(random.nextInt(SLUG_ALPHABET.length())));
        }
        return builder.toString();
    }
}
