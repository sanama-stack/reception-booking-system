package dev.reception.scheduling.domain;

/**
 * Why one specific start time cannot be booked — the answer
 * {@link AvailabilityEngine#isSlotBookable} gives phase 06.
 *
 * <p>Deliberately a domain enum rather than an {@code ErrorCode}. The engine is pure and knows
 * nothing about HTTP; phase 06 maps these onto the published codes
 * ({@code OUTSIDE_BUSINESS_HOURS}, {@code SLOT_UNAVAILABLE} and the rest) at the edge where a status
 * is chosen, which is the same place every other error body is produced.
 *
 * <p>Each value distinguishes a cause the owner or Customer can act on differently, which is why
 * "outside the Business's hours" and "outside this Employee's schedule" are not one value: the first
 * means try another time, the second means try another person.
 */
public enum UnbookableReason {

    /** The start has already passed. */
    BOOKING_IN_PAST,

    /** Sooner than the Business accepts bookings. */
    BELOW_MIN_LEAD_TIME,

    /** Further ahead than the Business accepts bookings. */
    BEYOND_MAX_ADVANCE,

    /**
     * Not on the Business's Slot grid. Checked because a caller may send any instant it likes, and a
     * booking off the grid would leave an unfillable sliver in front of it that availability would
     * never offer again.
     */
    NOT_ON_SLOT_GRID,

    /** The Service does not fit inside a single stretch of Business Hours. */
    OUTSIDE_BUSINESS_HOURS,

    /** It fits the Business's hours but not this Employee's Working Schedule. */
    OUTSIDE_WORKING_HOURS,

    /**
     * The Employee's time is already taken — by an Appointment, their Time Off, or a Business
     * Closure — once Buffers are counted.
     */
    SLOT_TAKEN
}
