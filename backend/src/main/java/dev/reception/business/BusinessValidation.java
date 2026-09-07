package dev.reception.business;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The field rules Bean Validation cannot express.
 *
 * <p>A regex can say a timezone is sixty-four characters of text; only {@link ZoneId} knows whether
 * {@code Europe/Atlantis} is a place. The same goes for ISO-4217 and ISO-3166 — the JDK ships the
 * registries, so hand-written lists here would be a second copy that goes stale.
 *
 * <p>Errors are <em>collected</em> rather than thrown one at a time. An owner filling in a settings
 * form should be told about all four mistakes at once, not made to submit four times to discover
 * them. That is why this is a builder rather than a set of {@code check…} calls that throw.
 */
final class BusinessValidation {

    /** Mirrors {@code businesses_slug_format} in the schema. Lowercase, no leading, trailing or repeated hyphens. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final int SLUG_MAX_LENGTH = 140;

    /**
     * Cached: {@link Locale#getISOCountries()} allocates a fresh array on every call, and this runs
     * on a request path.
     */
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    private final List<FieldError> errors = new ArrayList<>();

    /** Rejects a zone {@link ZoneId} does not know, which is the only definition of "real" that matters. */
    BusinessValidation timezone(String field, String value) {
        if (value != null && !ZoneId.getAvailableZoneIds().contains(value)) {
            errors.add(new FieldError(field, "That is not a timezone we recognise. Pick one from the list."));
        }
        return this;
    }

    BusinessValidation currency(String field, String value) {
        if (value == null) {
            return this;
        }
        try {
            Currency.getInstance(value);
        } catch (IllegalArgumentException e) {
            // Also the path for a code of the wrong length; the message covers both.
            errors.add(new FieldError(field, "That is not a currency code we recognise, such as USD or GEL."));
        }
        return this;
    }

    /** Blank is legitimate — it clears an optional field (see {@link Business#apply}). */
    BusinessValidation country(String field, String value) {
        if (value != null && !value.isBlank() && !ISO_COUNTRIES.contains(value)) {
            errors.add(new FieldError(field, "That is not a country code we recognise, such as GE or US."));
        }
        return this;
    }

    BusinessValidation slug(String field, String value) {
        if (value == null) {
            return this;
        }
        if (value.length() > SLUG_MAX_LENGTH) {
            errors.add(new FieldError(field, "That web address is too long."));
        } else if (!SLUG.matcher(value).matches()) {
            errors.add(new FieldError(
                    field,
                    "Use lowercase letters, numbers and single hyphens between them, like salon-aria."));
        }
        return this;
    }

    BusinessValidation reject(String field, String message) {
        errors.add(new FieldError(field, message));
        return this;
    }

    boolean isEmpty() {
        return errors.isEmpty();
    }

    /** Throws once, carrying every failure, or returns having found none. */
    void throwIfFailed() {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", errors);
        }
    }
}
