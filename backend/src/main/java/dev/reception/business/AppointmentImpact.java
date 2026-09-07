package dev.reception.business;

import java.time.Instant;
import java.util.UUID;

/**
 * How many Appointments a proposed Business Closure would cover.
 *
 * <p>Creating a closure over existing appointments is allowed, and the system does <strong>not</strong>
 * auto-cancel them — it reports the count so the owner can cancel them deliberately
 * (docs/01-prd.md FR-2). Silently cancelling a customer's appointment because an owner blocked out
 * a week is the kind of helpfulness that loses a business its customers.
 *
 * <p>Appointments arrive in phase 06; this port is what lets the closure response carry its final
 * shape from phase 03. See {@link CatalogReadiness} for the same seam and the same reasoning.
 */
public interface AppointmentImpact {

    /**
     * @param startsAt inclusive
     * @param endsAt exclusive
     * @return the number of this business's appointments that overlap the range
     */
    long countWithin(UUID businessId, Instant startsAt, Instant endsAt);

    /**
     * How many upcoming Appointments would be affected by deactivating this Service.
     *
     * <p>Reported, never acted on. Deactivating stops new bookings; it does not cancel the ones
     * already made, and the owner decides what to do about them (docs/04-api-overview.md §5).
     *
     * <p>"Future" is relative to the implementation's own Clock rather than to a parameter, because
     * the caller has no reason to hold an opinion about it and passing one would be an invitation to
     * pass a different one from two places.
     */
    long countFutureForService(UUID businessId, UUID serviceId);

    /** The same question for an Employee. */
    long countFutureForEmployee(UUID businessId, UUID employeeId);

    /**
     * Whether this Service has <em>ever</em> been booked — past appointments included.
     *
     * <p>Deliberately not the same question as {@link #countFutureForService}. A hard delete is
     * refused by history, not by the calendar: an Appointment from last year still names the Service
     * it was for, and removing the row would leave that record pointing at nothing.
     */
    boolean everBooked(UUID businessId, UUID serviceId);
}
