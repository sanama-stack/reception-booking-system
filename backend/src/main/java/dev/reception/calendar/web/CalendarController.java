package dev.reception.calendar.web;

import dev.reception.calendar.CalendarService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /calendar?from=&to=} — everything a day or week view draws, in one call.
 *
 * <p>The phase document's "one request per view, no per-day fetching" is this endpoint. See
 * {@link CalendarService} for why that is a correctness requirement and not a performance one.
 *
 * <p>No parameter names a business: the tenant comes from the Membership on the session.
 */
@RestController
@RequestMapping("/calendar")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class CalendarController {

    private final CalendarService calendar;

    public CalendarController(CalendarService calendar) {
        this.calendar = calendar;
    }

    /**
     * @param from first calendar date, in the Business timezone
     * @param to last calendar date, <strong>inclusive</strong>. At most 35 days from {@code from},
     *     and never before it; either is a {@code 422} rather than a quietly corrected range
     */
    @GetMapping
    public CalendarResponses.Calendar view(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return CalendarResponses.Calendar.of(calendar.between(from, to));
    }
}
