package dev.reception.publicapi;

import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.scheduling.application.web.AvailabilityResponses;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /public/businesses/{slug}/availability} — the phase 05 engine, unchanged.
 *
 * <p><strong>The same {@link AvailabilityService}, not a copy of it.</strong> Two implementations of
 * "when are you free" would eventually disagree, and the one a Customer saw would be the wrong one.
 * The response shape is the internal endpoint's too: {@code AvailabilityResponses} is hand-written,
 * carries no field a stranger may not see — a Slot, its Business-offset times, and the name of the
 * Employee who would perform it — and reusing it is what makes "public availability matches the
 * internal endpoint exactly" true by construction rather than by two tests agreeing.
 *
 * <p><strong>No exclusion parameter.</strong> The internal endpoint takes one, because it is
 * authenticated and tenant-scoped. Here an anonymous caller could pass any id, and the difference
 * between the grid with and without it would say whether that appointment exists and what time it
 * holds. A rescheduling Customer gets the same facility through
 * {@code PublicAppointmentController.manageAvailability}, where the excluded appointment is the one
 * their Manage Link authorises and cannot be anything else.
 */
@RestController
@RequestMapping("/public/businesses/{slug}/availability")
public class PublicAvailabilityController {

    private final AvailabilityService availability;

    public PublicAvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping
    public AvailabilityResponses.Availability find(
            @RequestParam UUID serviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID employeeId) {
        return AvailabilityResponses.Availability.of(availability.find(serviceId, from, to, employeeId, null));
    }
}
