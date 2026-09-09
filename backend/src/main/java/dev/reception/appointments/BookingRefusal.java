package dev.reception.appointments;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.scheduling.domain.UnbookableReason;

/**
 * The engine's domain reason, turned into the published error code and a sentence for a person.
 *
 * <p>This is the edge the availability engine's documentation points at: the engine is pure and
 * knows nothing about HTTP, so the translation happens once, here, where a status is chosen — the
 * same place every other error body in the application is produced.
 *
 * <p><strong>Every value of {@link UnbookableReason} is handled explicitly and there is no
 * {@code default}.</strong> Adding an eighth reason then fails to compile, which is the only way to
 * be sure a new refusal does not silently become a generic one.
 */
final class BookingRefusal {

    private BookingRefusal() {}

    /**
     * @param employeeName named in the two refusals where the caller's remedy is to pick someone
     *     else; a message that says "outside working hours" without saying whose is a message the
     *     reader has to guess at
     */
    static ApiException of(UnbookableReason reason, String serviceName, String employeeName) {
        return switch (reason) {
            case BOOKING_IN_PAST -> new ApiException(ErrorCode.BOOKING_IN_PAST, "That time has already passed.");
            case BELOW_MIN_LEAD_TIME -> new ApiException(
                    ErrorCode.BELOW_MIN_LEAD_TIME, "That is sooner than this business accepts bookings.");
            case BEYOND_MAX_ADVANCE -> new ApiException(
                    ErrorCode.BEYOND_MAX_ADVANCE, "That is further ahead than this business accepts bookings.");
            // No published code of its own (docs/04-api-overview.md §3): the request named a time
            // that is not one this business offers, which is a malformed question rather than a
            // scheduling conflict. It is unreachable from any screen, because every start a client
            // can click came off the grid — a caller assembling requests by hand gets it.
            case NOT_ON_SLOT_GRID -> new ApiException(
                    ErrorCode.VALIDATION_FAILED, "That is not one of the start times this business offers.");
            case OUTSIDE_BUSINESS_HOURS -> new ApiException(
                    ErrorCode.OUTSIDE_BUSINESS_HOURS,
                    "%s does not fit inside the opening hours at that time.".formatted(serviceName));
            case OUTSIDE_WORKING_HOURS -> new ApiException(
                    ErrorCode.OUTSIDE_WORKING_HOURS, "%s is not working at that time.".formatted(employeeName));
            case SLOT_TAKEN -> new ApiException(
                    ErrorCode.SLOT_UNAVAILABLE, "%s is not free at that time.".formatted(employeeName));
        };
    }
}
