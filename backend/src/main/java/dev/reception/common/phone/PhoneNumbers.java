package dev.reception.common.phone;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Optional;

/**
 * Local phone input into E.164, using the Business's country to resolve what "555 12 34" means.
 *
 * <p>Pure and static, so the rules can be tested without a Spring context, a database or a tenant —
 * the same reason {@code BusinessHoursService.validateWeek} is.
 *
 * <p><strong>Why a library.</strong> The same reasoning {@code BusinessValidation} applies to
 * timezones and currencies: a hand-written table of calling codes is a second copy of a registry
 * that goes stale, and it could only ever reformat a number, never tell a real one from a typo.
 * From phase 06 this is load-bearing rather than cosmetic — a Customer is identified by
 * {@code (business_id, normalised phone)}, so a normalisation that disagrees with itself splits one
 * person into two records with two separate histories.
 */
public final class PhoneNumbers {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNumbers() {}

    /**
     * Normalises a number a person typed.
     *
     * <p>A leading {@code +} makes the number self-describing and the country is then irrelevant,
     * which is the path a Business with no country set still has. Without one, the country is what
     * turns a local number into a global one — so a business that has not set theirs can only accept
     * the international form, and the caller says so rather than guessing a region.
     *
     * @param raw what the owner typed, in any format
     * @param country the Business's ISO-3166-1 alpha-2 code, or {@code null} if it is not set
     * @return the E.164 form, or empty when the input is blank or is not a possible number in that
     *     country — the caller decides which of those two is an error
     */
    public static Optional<String> toE164(String raw, String country) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String region = raw.trim().startsWith("+") ? null : blankToNull(country);
        try {
            Phonenumber.PhoneNumber parsed = UTIL.parse(raw, region);
            // isValidNumber, not isPossibleNumber: possible only checks the length, and a
            // seven-digit string that is not an allocated range would then be stored as a number
            // nobody can be reached on.
            return UTIL.isValidNumber(parsed)
                    ? Optional.of(UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164))
                    : Optional.empty();
        } catch (NumberParseException e) {
            // Includes the case where there is no region to interpret a local number against.
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
