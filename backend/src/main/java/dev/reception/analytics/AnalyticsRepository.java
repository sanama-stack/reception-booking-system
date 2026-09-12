package dev.reception.analytics;

import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentStatus;
import dev.reception.tenancy.TenantScoped;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The dashboard's numbers, computed by the database.
 *
 * <p>A second repository over {@code Appointment} rather than more methods on
 * {@code AppointmentRepository}: everything here returns an aggregate and nothing returns a row, so
 * the two have no call site in common. Kept beside the service that owns them, which is the
 * arrangement {@code package-info} reserved this package for.
 *
 * <p><strong>Nothing here loads an Appointment.</strong> Every method returns counts, sums or a
 * short projection, because the alternative — reading a year of rows into memory and folding them
 * in Java — is a query that works on the demo and times out on a real salon. The phase document
 * says aggregate SQL and means it.
 *
 * <p><strong>Every range is half-open, {@code [from, to)}</strong>, matching the overlap convention
 * {@code AppointmentRepository} documents. Written closed, an appointment at exactly midnight would
 * be counted in both the day that ends and the day that begins, and the periods would not sum.
 *
 * <p>Names begin with a tenant-scoped prefix, as {@code TenantRepositoryShapeTest} requires — the
 * aggregate ones included, which is why {@code sumByBusinessId} is on its list.
 */
@TenantScoped
public interface AnalyticsRepository extends JpaRepository<Appointment, UUID> {

    /**
     * How many Appointments in each status started inside the range.
     *
     * <p>Grouped rather than counted four times: the four counts are one pass over the same index,
     * and a status nobody used simply does not come back — which is why the service fills the
     * missing ones with zero rather than trusting the result to have four rows.
     */
    @Query(
            """
            select a.status as status, count(a) as total
              from Appointment a
             where a.businessId = :businessId
               and a.startsAt >= :from
               and a.startsAt < :to
             group by a.status
            """)
    List<StatusCountRow> findByBusinessIdAndStatusCounts(
            @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

    /** One status and how many of it. */
    interface StatusCountRow {
        AppointmentStatus getStatus();

        long getTotal();
    }

    /**
     * Revenue: the snapshotted prices of the Appointments that were actually attended.
     *
     * <p><strong>{@code priceAmount} is the Appointment's own column, never the Service's.</strong>
     * The Service's price is the price today; this is the price that was agreed. Joining to the
     * catalog here would make last month's revenue move every time somebody edits a price, which is
     * the single easiest way to get this endpoint wrong.
     *
     * <p><strong>Filtered to one currency, deliberately.</strong> A Business can change its
     * currency in Settings, and Appointments booked before that keep the currency they were priced
     * in. Summing across them would add lari to euros and produce a number that is not money. So
     * the caller passes the currency it intends to report and the sum answers for exactly that —
     * see {@code AnalyticsService} for what this leaves out and why the contract has nowhere to put
     * it.
     *
     * <p>{@code coalesce} because {@code sum} over no rows is null, and a business with no completed
     * appointments has revenue of zero rather than revenue of nothing.
     */
    @Query(
            """
            select coalesce(sum(a.priceAmount), 0)
              from Appointment a
             where a.businessId = :businessId
               and a.status = :completed
               and a.currency = :currency
               and a.startsAt >= :from
               and a.startsAt < :to
            """)
    BigDecimal sumByBusinessIdAndCompletedRevenue(
            @Param("businessId") UUID businessId,
            @Param("completed") AppointmentStatus completed,
            @Param("currency") String currency,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * The same revenue, for every currency that is <em>not</em> the one being reported.
     *
     * <p>The remainder ADR-0010 decided to name rather than drop. {@code
     * sumByBusinessIdAndCompletedRevenue} answers for one currency and is silent about the rest;
     * this says what the rest were, one sum per currency, so the summary can carry a footnote
     * instead of a partial truth shaped like a whole one.
     *
     * <p><strong>No arithmetic crosses a currency here either.</strong> The {@code group by} is what
     * refuses it: each row is a sum within one code, and nothing adds two rows together — not in
     * this query, not in the service, and not on the screen.
     *
     * <p><strong>Filtered to COMPLETED, the same as the primary figure.</strong> That is what makes
     * the response's {@code basis} constant true of every number in the object rather than only of
     * the headline one, which is the property ADR-0010's last consequence pins.
     *
     * <p>No {@code coalesce}: a group exists only because it has rows, so its sum is never null.
     * The empty case is the empty list, which is the answer for a business that never changed
     * currency — every business today.
     *
     * <p>Ordered by currency so the list is a function of the data, for the reason the top-services
     * tie-break is in the query: an unordered aggregate reorders itself on refresh with nothing
     * having changed.
     */
    @Query(
            """
            select a.currency as currency, sum(a.priceAmount) as total
              from Appointment a
             where a.businessId = :businessId
               and a.status = :completed
               and a.currency <> :currency
               and a.startsAt >= :from
               and a.startsAt < :to
             group by a.currency
             order by a.currency asc
            """)
    List<CurrencySumRow> findByBusinessIdAndCompletedRevenueInOtherCurrencies(
            @Param("businessId") UUID businessId,
            @Param("completed") AppointmentStatus completed,
            @Param("currency") String currency,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** One currency and the completed revenue taken in it. */
    interface CurrencySumRow {
        String getCurrency();

        BigDecimal getTotal();
    }

    /** Every Appointment that started in the range, whatever became of it. The periods' counter. */
    long countByBusinessIdAndStartsAtGreaterThanEqualAndStartsAtLessThan(UUID businessId, Instant from, Instant to);

    /**
     * The most-booked Services in the range.
     *
     * <p><strong>The tie-break is in the query, not left to the database.</strong> Two services with
     * the same count come back in whatever order the plan happened to produce, which means a
     * dashboard that reorders itself on refresh with no data having changed. Ordering by id after
     * the count costs nothing and makes the answer a function of the data.
     *
     * <p>Counts every status, because "most booked" is a question about demand: a service people
     * book and cancel is still a service people book, and the cancellation rate is reported
     * separately for anyone who wants the other question answered.
     */
    @Query(
            """
            select a.serviceId as serviceId, count(a) as total
              from Appointment a
             where a.businessId = :businessId
               and a.startsAt >= :from
               and a.startsAt < :to
             group by a.serviceId
             order by count(a) desc, a.serviceId asc
            """)
    List<ServiceCountRow> findByBusinessIdAndTopServices(
            @Param("businessId") UUID businessId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable limit);

    /** One Service and how many times it was booked. The name is resolved by the service layer. */
    interface ServiceCountRow {
        UUID getServiceId();

        long getTotal();
    }
}
