package dev.reception.analytics;

import dev.reception.appointments.AppointmentStatus;
import dev.reception.business.Business;
import dev.reception.business.BusinessService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one summary the dashboard's analytics screen reads.
 *
 * <p><strong>Every boundary in here is resolved in the Business's own timezone</strong>, never the
 * server's and never the browser's. A salon in Tbilisi closes at midnight Tbilisi, and a "today"
 * computed in UTC would put its evening appointments on tomorrow and its numbers four hours out of
 * step with the calendar the owner is looking at. The zone is read once, at the top, and every
 * {@code Instant} below is derived from it.
 *
 * <p><strong>A rate over no appointments is {@code null}, not zero.</strong> "0% cancellation" and
 * "no appointments yet" are different facts, and only one of them is worth acting on — an owner who
 * reads the first will conclude their retention is perfect. The phase document calls this out and
 * it is the easiest thing here to get wrong, because {@code 0/0} in a spreadsheet is how everyone
 * has seen it done.
 */
@org.springframework.stereotype.Service
public class AnalyticsService {

    /**
     * The widest range the endpoint will answer, in days, inclusive of both ends.
     *
     * <p>A year plus a day, so "the last twelve months" and "this calendar year" both fit including
     * a leap day, and nothing beyond it does. The cap exists because the range is caller-supplied
     * and unbounded aggregates over a decade of history are how a dashboard becomes a way to take
     * the database down.
     */
    static final int MAX_RANGE_DAYS = 366;

    /** How many services the top-services table shows. Enough to be a ranking, short enough to read. */
    static final int TOP_SERVICES = 5;

    /**
     * Rates are reported to three decimal places, which is a tenth of a percent.
     *
     * <p>Deliberately not the raw quotient: {@code 6/82} is 0.07317073170731707, and a number with
     * seventeen digits of precision claims an accuracy that eighty-two appointments do not support.
     */
    private static final int RATE_SCALE = 3;

    private final AnalyticsRepository analytics;
    private final BusinessService businesses;
    private final ServiceCatalogService catalog;
    private final TenantContext tenant;
    private final Clock clock;

    public AnalyticsService(
            AnalyticsRepository analytics,
            BusinessService businesses,
            ServiceCatalogService catalog,
            TenantContext tenant,
            Clock clock) {
        this.analytics = analytics;
        this.businesses = businesses;
        this.catalog = catalog;
        this.tenant = tenant;
        this.clock = clock;
    }

    /** Both dates inclusive, in the Business's timezone, exactly as the owner picked them. */
    @Transactional(readOnly = true)
    public Summary summarise(LocalDate from, LocalDate to) {
        validate(from, to);

        Business business = businesses.read();
        ZoneId zone = business.timezone();
        UUID businessId = tenant.businessId();

        // The half-open instant the whole of `to` falls inside. `to` is inclusive to the owner and
        // exclusive to the query, and this one line is where the two meanings meet.
        Instant rangeFrom = from.atStartOfDay(zone).toInstant();
        Instant rangeTo = to.plusDays(1).atStartOfDay(zone).toInstant();

        Map<AppointmentStatus, Long> counts = countsByStatus(businessId, rangeFrom, rangeTo);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        return new Summary(
                new Range(from, to, zone),
                new Counts(
                        counts.get(AppointmentStatus.CONFIRMED),
                        counts.get(AppointmentStatus.COMPLETED),
                        counts.get(AppointmentStatus.CANCELLED),
                        counts.get(AppointmentStatus.NO_SHOW),
                        total),
                periods(businessId, zone),
                new Revenue(
                        analytics.sumByBusinessIdAndCompletedRevenue(
                                businessId, AppointmentStatus.COMPLETED, business.currency(), rangeFrom, rangeTo),
                        business.currency()),
                new Rates(
                        rate(counts.get(AppointmentStatus.CANCELLED), total),
                        rate(counts.get(AppointmentStatus.NO_SHOW), total)),
                topServices(businessId, rangeFrom, rangeTo));
    }

    /**
     * Two refusals, and they are refusals rather than corrections.
     *
     * <p>A backwards range is silently swappable and a too-wide one is silently clampable, and both
     * would answer a question the caller did not ask with a number they would then act on. The
     * availability tool clamps because a model cannot be asked again mid-turn; a screen can.
     */
    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The range ends before it starts.",
                    List.of(new FieldError("to", "must not be before from")));
        }
        // +1 because both ends are inclusive: the 1st to the 1st is one day, not zero.
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A range may cover at most " + MAX_RANGE_DAYS + " days.",
                    List.of(new FieldError("to", "is " + days + " days from from, and the maximum is " + MAX_RANGE_DAYS)));
        }
    }

    /**
     * The four counts, with the statuses nobody used filled in as zero.
     *
     * <p>{@code group by} returns the rows that exist, so a business whose appointments are all
     * CONFIRMED gets one row back. Filling the rest here is what lets the caller read all four
     * without a null check each.
     */
    private Map<AppointmentStatus, Long> countsByStatus(UUID businessId, Instant from, Instant to) {
        Map<AppointmentStatus, Long> counts = new EnumMap<>(AppointmentStatus.class);
        for (AppointmentStatus status : AppointmentStatus.values()) {
            counts.put(status, 0L);
        }
        for (AnalyticsRepository.StatusCountRow row : analytics.findByBusinessIdAndStatusCounts(businessId, from, to)) {
            counts.put(row.getStatus(), row.getTotal());
        }
        return counts;
    }

    /**
     * Today, this week and this month — <strong>relative to now, not to the requested range.</strong>
     *
     * <p>The contract does not say which, and the two readings differ: bounded by the range, a
     * report on last September would say "today: 0" for every business on earth. These three exist
     * to drive the dashboard's home screen, where the owner is asking "what is happening now", so
     * they answer that and ignore the range entirely. Recorded here because the next reader will
     * wonder, and because a caller who wants the range's own totals already has {@code counts}.
     *
     * <p><strong>The week starts on Monday</strong>, ISO-8601, everywhere and for everybody. A
     * locale-dependent week start would make the same business's numbers differ by who was looking,
     * which is worse than being unfamiliar to some of them.
     */
    private Periods periods(UUID businessId, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return new Periods(
                countBetween(businessId, zone, today, today.plusDays(1)),
                countBetween(
                        businessId,
                        zone,
                        today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)),
                        today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusWeeks(1)),
                countBetween(
                        businessId,
                        zone,
                        today.withDayOfMonth(1),
                        today.withDayOfMonth(1).plusMonths(1)));
    }

    private long countBetween(UUID businessId, ZoneId zone, LocalDate fromInclusive, LocalDate toExclusive) {
        return analytics.countByBusinessIdAndStartsAtGreaterThanEqualAndStartsAtLessThan(
                businessId, fromInclusive.atStartOfDay(zone).toInstant(), toExclusive.atStartOfDay(zone).toInstant());
    }

    /** Null over an empty denominator, which is the whole point; see the class comment. */
    private static BigDecimal rate(long part, long total) {
        return total == 0
                ? null
                : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The ranking, with names attached.
     *
     * <p>The catalog is listed whole and joined in memory — the same trade {@code
     * AppointmentQueryService} makes and for the same reason: a business has a handful of services,
     * and five lookups by id would be five round trips to save reading a list that fits in a cache
     * line. {@code null} is passed for the active filter deliberately, so a deactivated service
     * that was booked twenty times this month still has a name in the table.
     */
    private List<TopService> topServices(UUID businessId, Instant from, Instant to) {
        List<AnalyticsRepository.ServiceCountRow> rows =
                analytics.findByBusinessIdAndTopServices(businessId, from, to, PageRequest.of(0, TOP_SERVICES));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = catalog.list(null).stream()
                .collect(Collectors.toMap(dev.reception.catalog.Service::getId, dev.reception.catalog.Service::name));
        return rows.stream()
                .map(row -> new TopService(row.getServiceId(), names.get(row.getServiceId()), row.getTotal()))
                .toList();
    }

    /** What the endpoint answers. One record per object in the documented body. */
    public record Summary(Range range, Counts counts, Periods periods, Revenue revenue, Rates rates,
            List<TopService> topServices) {}

    /** Both dates inclusive, and the zone they were resolved in, so the caller need not assume one. */
    public record Range(LocalDate from, LocalDate to, ZoneId timezone) {}

    public record Counts(long confirmed, long completed, long cancelled, long noShow, long total) {}

    /** Relative to now rather than to the range — see {@code periods}. */
    public record Periods(long today, long thisWeek, long thisMonth) {}

    /**
     * @param amount completed appointments only, at the prices that were agreed
     * @param currency the Business's current currency, and the only one {@code amount} covers
     */
    public record Revenue(BigDecimal amount, String currency) {}

    /** Either may be null, and null means "no appointments", not "none cancelled". */
    public record Rates(BigDecimal cancellation, BigDecimal noShow) {}

    public record TopService(UUID serviceId, String name, long count) {}
}
