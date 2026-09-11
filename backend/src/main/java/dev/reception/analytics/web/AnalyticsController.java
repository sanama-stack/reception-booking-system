package dev.reception.analytics.web;

import dev.reception.analytics.AnalyticsService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /analytics/summary} — the single metrics endpoint (docs/04-api-overview.md §5).
 *
 * <p>One endpoint and one screen, by design. A dashboard that fetches counts, revenue and a ranking
 * separately renders in three stages and can show three different moments of the same business.
 *
 * <p>No parameter names a business: the tenant comes from the Membership on the session, exactly as
 * every other tenant-scoped controller here.
 */
@RestController
@RequestMapping("/analytics")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /**
     * @param from first calendar date, in the Business timezone
     * @param to last calendar date, <strong>inclusive</strong> — the day the owner picked is a day
     *     they expect to see counted. At most 366 days from {@code from}, and never before it;
     *     either is a {@code 422} rather than a quietly corrected range
     */
    @GetMapping("/summary")
    public AnalyticsResponses.Summary summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return AnalyticsResponses.Summary.of(analytics.summarise(from, to));
    }
}
