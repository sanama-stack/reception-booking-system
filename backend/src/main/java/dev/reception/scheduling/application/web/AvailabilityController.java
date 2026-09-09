package dev.reception.scheduling.application.web;

import dev.reception.scheduling.application.AvailabilityService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /availability} — the internal read of the one engine everything books through.
 *
 * <p>The public booking page (phase 08) and the Receptionist's Tools (phase 09) call the same
 * {@link AvailabilityService}, not a copy of it. Two implementations of "when are you free" would
 * eventually disagree, and the one a Customer saw would be the wrong one.
 *
 * <p>No parameter here names a business: the tenant comes from the Membership on the session, and
 * {@code TenantRepositoryShapeTest} fails the build if that ever stops being true.
 *
 * <p>Read-only. Phase 05 writes nothing anywhere.
 */
@RestController
@RequestMapping("/availability")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    /**
     * @param from first calendar date, in the Business timezone; {@code to} is inclusive
     * @param employeeId optional. Omitted asks "anyone who can do this", and every returned Slot
     *     still carries the Employee who would perform it
     * @param excludeAppointmentId optional, and only meaningful while rescheduling: the Appointment
     *     being moved, so the time it currently holds is offered back rather than counted against
     *     itself (phase 08).
     *     <p>Safe to accept from the caller <em>here</em> and nowhere else. This endpoint is
     *     authenticated and tenant-scoped, so an id belonging to another Business excludes nothing —
     *     {@code findByBusinessIdAndEmployeesOverlapping} never had those rows in scope to begin
     *     with, and the grid comes back identical. The public surface has no such protection, which
     *     is why it resolves the exclusion from a Manage Link instead of from a parameter
     */
    @GetMapping
    public AvailabilityResponses.Availability find(
            @RequestParam UUID serviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) UUID excludeAppointmentId) {
        return AvailabilityResponses.Availability.of(
                availability.find(serviceId, from, to, employeeId, excludeAppointmentId));
    }
}
