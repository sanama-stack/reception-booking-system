package dev.reception.scheduling.domain;

/**
 * Why there are no Slots — returned instead of a bare empty list.
 *
 * <p>An empty array tells a Customer nothing and tells the Receptionist even less: phase 09 must be
 * able to say "we are closed all week" rather than guess at it, and a guess from a language model
 * about why a business has no availability is exactly the kind of confident invention this
 * architecture is built to prevent (docs/04-api-overview.md §5, ADR-0004).
 *
 * <p><strong>The order of the constants is the order the engine tries them</strong>, and each one
 * makes the next moot. There is no combination that produces two.
 */
public enum EmptyReason {

    /** Nobody can perform this Service at all — no active, assigned Employee with a schedule. */
    NO_ELIGIBLE_EMPLOYEE,

    /**
     * Every date asked about falls outside the Booking Horizon — wholly in the past, inside the
     * minimum lead time, or beyond the maximum advance. Asked before "are you open?" because a
     * range in the past is not a question about opening hours, and telling someone the shop is
     * closed on a date that has already happened is an unhelpful truth.
     */
    OUTSIDE_HORIZON,

    /**
     * Nowhere in the range could have held this Service in the first place: the Business is closed,
     * the Employee is not scheduled, the two schedules never meet, or every open stretch is shorter
     * than the Service itself.
     *
     * <p>That last case is why this is decided against the Service and not against the calendar. An
     * hour of opening time cannot hold a two-hour Service, and reporting {@link #FULLY_BOOKED} for
     * it would send an owner looking for a cancellation that would not help.
     */
    CLOSED,

    /**
     * The Service fits somewhere in the range and every one of those places is taken — by an
     * Appointment, Time Off or a Closure. The only reason here that can change on its own.
     */
    FULLY_BOOKED
}
