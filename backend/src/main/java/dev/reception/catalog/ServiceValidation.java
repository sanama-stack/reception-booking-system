package dev.reception.catalog;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The field rules for a Service, collected rather than thrown one at a time.
 *
 * <p>These live here rather than as Bean Validation annotations on the request DTOs because the
 * same rules govern both {@code POST} and {@code PATCH}, and because "a multiple of five" is a
 * consequence of how the slot grid is built rather than an arbitrary bound — the reasoning belongs
 * next to the rule, not spread across two records.
 *
 * <p>Every method skips {@code null}, which is what makes one validator serve a create and a
 * partial update: an absent field has no value to be wrong.
 *
 * <p>An owner filling in a form should learn about all their mistakes at once, not submit four
 * times to discover them one by one. That is why this collects and {@link #throwIfFailed} throws
 * once.
 */
final class ServiceValidation {

    private final List<FieldError> errors = new ArrayList<>();

    /**
     * Bounds <em>and</em> the grid.
     *
     * <p>The multiple-of-five rule is not cosmetic: slots are generated on a stride the owner picks
     * from {5, 10, 15, 20, 30, 60}, and a duration that does not land on that stride produces start
     * times that drift further across the day with every appointment.
     */
    ServiceValidation duration(String field, Integer minutes) {
        if (minutes == null) {
            return this;
        }
        if (minutes < Service.MIN_DURATION_MINUTES || minutes > Service.MAX_DURATION_MINUTES) {
            errors.add(new FieldError(
                    field,
                    "How long this takes must be between " + Service.MIN_DURATION_MINUTES + " minutes and "
                            + (Service.MAX_DURATION_MINUTES / 60) + " hours."));
        } else if (minutes % Service.DURATION_GRANULARITY_MINUTES != 0) {
            errors.add(new FieldError(
                    field, "Use a multiple of " + Service.DURATION_GRANULARITY_MINUTES + " minutes, like 30 or 45."));
        }
        return this;
    }

    /** Zero is the common case and is not a mistake — most services need no padding at all. */
    ServiceValidation buffer(String field, Integer minutes) {
        if (minutes == null) {
            return this;
        }
        if (minutes < 0) {
            errors.add(new FieldError(field, "Extra time cannot be negative."));
        } else if (minutes > Service.MAX_BUFFER_MINUTES) {
            errors.add(new FieldError(
                    field, "Extra time can be at most " + (Service.MAX_BUFFER_MINUTES / 60) + " hours."));
        }
        return this;
    }

    /**
     * Zero is allowed: a free consultation is a service, and refusing to price one at nothing would
     * make the owner invent a fake price.
     */
    ServiceValidation price(String field, BigDecimal amount) {
        if (amount == null) {
            return this;
        }
        if (amount.signum() < 0) {
            errors.add(new FieldError(field, "The price cannot be negative."));
        } else if (amount.scale() > 2) {
            // numeric(12,2) would round this silently, so the owner would be shown a price they did
            // not type. Refusing is the honest answer.
            errors.add(new FieldError(field, "Use at most two decimal places."));
        } else if (amount.precision() - amount.scale() > 10) {
            // The column is numeric(12,2); anything wider fails as a driver error with no field name.
            errors.add(new FieldError(field, "That price is too large."));
        }
        return this;
    }

    ServiceValidation reject(String field, String message) {
        errors.add(new FieldError(field, message));
        return this;
    }

    boolean isEmpty() {
        return errors.isEmpty();
    }

    void throwIfFailed() {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", errors);
        }
    }
}
