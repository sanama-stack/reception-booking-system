package dev.reception.appointments;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The short code a Customer reads back to prove an Appointment is theirs.
 *
 * <p><strong>Crockford base32.</strong> The alphabet omits {@code I}, {@code L}, {@code O} and
 * {@code U} — the first three because they cannot be told from {@code 1} and {@code 0} over a phone
 * or in a confirmation email skimmed on a bus, and {@code U} because excluding it removes most of
 * the short English obscenities a random generator would otherwise produce and hand to a customer.
 *
 * <p>Eight characters is 32^8 ≈ 1.1 × 10^12 codes, and uniqueness is only required within one
 * Business, so a collision needs about a million appointments before it is worth thinking about.
 * The retry below is for the case where it happens anyway, and the database's
 * {@code appointments_code_unique} is what makes the answer correct if two requests generate the
 * same code in the same instant.
 *
 * <p><strong>Not a secret.</strong> A code alone grants nothing; it is checked together with the
 * phone number that booked (docs/06-security.md). {@link SecureRandom} is used anyway because the
 * alternative saves nothing and a predictable code would turn a guessable phone number into an
 * enumeration.
 */
@Component
public class ConfirmationCodeGenerator {

    /** Crockford base32: the digits, then the letters, less I, L, O and U. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    static final int LENGTH = 8;

    /**
     * Enough that exhausting them means something is wrong other than luck — a business with a
     * meaningful fraction of 10^12 codes taken, or a broken random source. Failing loudly beats
     * looping forever on either.
     */
    private static final int MAX_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();

    /**
     * A code not already in use by this Business.
     *
     * @param taken answers whether a candidate is already used. Passed in rather than injecting the
     *     repository, so this class stays testable without a database and cannot grow a second
     *     reason to touch one
     */
    public String generateUnique(UUID businessId, CodeTakenCheck taken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = generate();
            if (!taken.isTaken(businessId, candidate)) {
                return candidate;
            }
        }
        throw new ApiException(
                ErrorCode.INTERNAL_ERROR,
                "Could not allocate a confirmation code. Quote the request id if you contact support.");
    }

    String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /** Whether a Business already has an Appointment carrying this code. */
    @FunctionalInterface
    public interface CodeTakenCheck {
        boolean isTaken(UUID businessId, String code);
    }
}
