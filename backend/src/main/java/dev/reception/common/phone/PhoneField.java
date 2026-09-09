package dev.reception.common.phone;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import java.util.List;
import java.util.Optional;

/**
 * {@link PhoneNumbers} with the refusal a request needs — one normalisation rule and one set of
 * words for failing it.
 *
 * <p>Split from {@code PhoneNumbers} so that stays a pure predicate the unit suite can exercise
 * without an HTTP vocabulary, and split from the two application services that use it because they
 * are the same rule: an Employee's number and a Customer's number are normalised identically, and a
 * second copy of this would be a second chance for the two to drift.
 *
 * <p>Drifting matters more than it looks. From phase 06 a Customer is identified by
 * {@code (business_id, normalised phone)} — if the booking path and the correction path disagreed
 * about what a number normalises to, one person would become two records with two histories, and
 * nothing would report an error.
 *
 * <p><strong>Two ways in, because there are two kinds of caller</strong> (phase 08). A request DTO
 * whose field is genuinely called {@code phone} uses {@link #normalise} and gets the failure
 * attached for it. An application service that is called by three entry points with three different
 * wire vocabularies uses {@link #parse} and {@link #unreadableMessage}, and reports the failure
 * under whichever name the request it is serving actually used — see {@code CustomerFieldNames}.
 */
public final class PhoneField {

    private PhoneField() {}

    /**
     * @param field the request field to attach a failure to
     * @param raw what the caller typed, in any format
     * @param country the Business's ISO-3166-1 alpha-2 code, or {@code null} if it is not set
     * @return the E.164 form, or {@code null} when {@code raw} is absent or blank — an absent number
     *     is not an invalid one, and the caller decides whether it is required
     * @throws ApiException {@code VALIDATION_FAILED} when a number was given and cannot be read
     */
    public static String normalise(String field, String raw, String country) {
        if (isAbsent(raw)) {
            return null;
        }
        return parse(raw, country)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "One or more fields are invalid.",
                        List.of(new FieldError(field, unreadableMessage(country)))));
    }

    /** Whether the caller supplied anything at all. An absent number is not an invalid one. */
    public static boolean isAbsent(String raw) {
        return raw == null || raw.isBlank();
    }

    /**
     * The normalisation itself, with no opinion about how a failure is reported.
     *
     * @return the E.164 form, or empty when {@code raw} was given and cannot be read. A blank
     *     {@code raw} is also empty here, so callers that treat absent and unreadable differently
     *     must ask {@link #isAbsent} first
     */
    public static Optional<String> parse(String raw, String country) {
        return isAbsent(raw) ? Optional.empty() : PhoneNumbers.toE164(raw, country);
    }

    /**
     * A business with no country set cannot have a local number interpreted, and saying so is more
     * use than "that number is not valid" — the fix is different in each case.
     */
    public static String unreadableMessage(String country) {
        return country == null || country.isBlank()
                ? "Enter the number in international form, starting with +, or set your country in "
                        + "Settings so local numbers can be understood."
                : "That does not look like a phone number we can reach. Check it, or enter it in "
                        + "international form starting with +.";
    }
}
