package dev.reception.business;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * The time each of these Employees is already committed to, for the availability engine.
     *
     * <p>Asked for every Employee at once rather than one at a time. Availability is computed for a
     * whole range across every Employee who can perform a Service, and a query per Employee would
     * grow with the size of the team on the one endpoint that is called on every page of the public
     * booking flow.
     *
     * <p><strong>This is a fifth question on this port rather than a port of its own.</strong> All
     * five are answered from {@code appointments}, so phase 06 still has exactly one class to
     * delete. A second stub would be a second chance to leave one behind — and a forgotten
     * {@code EmptyBusyRanges} does not fail loudly the way two competing beans do: it goes on
     * reporting every Employee free forever, and the system offers Slots that are already booked.
     *
     * @param from inclusive, {@code to} exclusive — the window availability was asked about, already
     *     widened by the caller to cover Buffers that reach outside it
     * @param excludingAppointmentId an Appointment to leave out, or {@code null} for all of them.
     *     This is what makes a reschedule answerable: moving a 10:00 booking to 10:15 overlaps the
     *     time it currently holds, and counting the appointment against itself would refuse the one
     *     move it is being asked to make. The database has no such problem — an exclusion constraint
     *     never compares a row with itself — so without this the pre-check would be stricter than
     *     the rule it exists to explain
     * @return blocked ranges by Employee id. An Employee with nothing booked may be absent or map to
     *     an empty list; callers must treat the two the same
     */
    Map<UUID, List<BlockedRange>> blockedRangesFor(
            UUID businessId,
            Collection<UUID> employeeIds,
            Instant from,
            Instant to,
            UUID excludingAppointmentId);

    /**
     * One Appointment's buffer-inclusive occupancy — {@code blocked_from} to {@code blocked_to}, the
     * columns the exclusion constraint compares (docs/03-data-model.md §4).
     *
     * <p>Not the Appointment's own start and end: what makes an Employee unavailable is the whole
     * span including Buffers, and the engine must test against the same interval the database will.
     */
    record BlockedRange(Instant from, Instant to) {}
}
