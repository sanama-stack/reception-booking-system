package dev.reception.analytics.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.reception.analytics.AnalyticsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The wire shape of {@code GET /analytics/summary}, fixed in docs/04-api-overview.md §5. */
public final class AnalyticsResponses {

    private AnalyticsResponses() {}

    /**
     * Money as a decimal string, as everywhere else in this API — see {@code ServiceResponses.Money}
     * for why a price is never a float.
     *
     * <p><strong>{@code basis} is a constant and is sent anyway.</strong> A revenue figure is
     * meaningless without knowing what it counts, and "does this include the bookings that were
     * cancelled?" is the first question anyone asks of it. Sending the answer beside the number
     * costs a dozen bytes and stops the screen from having to assert it in prose that can drift.
     */
    public record Revenue(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currency, String basis) {

        private static final String COMPLETED_ONLY = "COMPLETED_ONLY";

        static Revenue of(AnalyticsService.Revenue revenue) {
            // Scale fixed at two so an empty range reads "0.00" rather than "0": the sum of no rows
            // comes back unscaled, and a money field that changes shape when it is empty is a
            // formatting bug waiting to happen on the client.
            return new Revenue(
                    revenue.amount().setScale(2, RoundingMode.HALF_UP), revenue.currency(), COMPLETED_ONLY);
        }
    }

    /** Both dates inclusive, and the zone every boundary behind them was resolved in. */
    public record Range(LocalDate from, LocalDate to, String timezone) {}

    public record Counts(long confirmed, long completed, long cancelled, long noShow, long total) {}

    public record Periods(long today, long thisWeek, long thisMonth) {}

    /**
     * <strong>Null, not zero, when there were no appointments.</strong> Serialized as JSON {@code
     * null} rather than omitted, so the client distinguishes "no data" from a field it forgot to
     * read.
     */
    public record Rates(BigDecimal cancellation, BigDecimal noShow) {}

    public record TopService(UUID serviceId, String name, long count) {}

    public record Summary(
            Range range, Counts counts, Periods periods, Revenue revenue, Rates rates, List<TopService> topServices) {

        public static Summary of(AnalyticsService.Summary summary) {
            return new Summary(
                    new Range(summary.range().from(), summary.range().to(), summary.range().timezone().getId()),
                    new Counts(
                            summary.counts().confirmed(),
                            summary.counts().completed(),
                            summary.counts().cancelled(),
                            summary.counts().noShow(),
                            summary.counts().total()),
                    new Periods(
                            summary.periods().today(), summary.periods().thisWeek(), summary.periods().thisMonth()),
                    Revenue.of(summary.revenue()),
                    new Rates(summary.rates().cancellation(), summary.rates().noShow()),
                    summary.topServices().stream()
                            .map(top -> new TopService(top.serviceId(), top.name(), top.count()))
                            .toList());
        }
    }
}
