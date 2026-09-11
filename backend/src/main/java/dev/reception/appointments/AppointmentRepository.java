package dev.reception.appointments;

import dev.reception.tenancy.TenantScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Appointments are tenant-owned; see {@code ServiceRepository} for the shape rule and why it exists.
 *
 * <p>Every method name begins with a tenant-scoped prefix, including the {@code @Query} ones. That
 * is where the rule matters most: a derived query cannot forget the filter its name declares, but a
 * hand-written JPQL string can, and {@code TenantRepositoryShapeTest} reads names rather than
 * queries.
 *
 * <p><strong>Overlap is written as {@code blockedFrom < to and blockedTo > from} throughout</strong>
 * — strict on both sides, which is the half-open {@code '[)'} the exclusion constraint uses. Written
 * with {@code <=} anywhere, a 16:00 appointment would count as overlapping a range ending at 16:00
 * and back-to-back bookings would start reporting conflicts.
 */
@TenantScoped
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    Optional<Appointment> findByBusinessIdAndId(UUID businessId, UUID id);

    boolean existsByBusinessIdAndConfirmationCode(UUID businessId, String confirmationCode);

    /** Whether this Service has ever been booked, in any status. The hard-delete guard. */
    boolean existsByBusinessIdAndServiceId(UUID businessId, UUID serviceId);

    long countByBusinessIdAndServiceIdAndStatusAndStartsAtAfter(
            UUID businessId, UUID serviceId, AppointmentStatus status, Instant after);

    long countByBusinessIdAndEmployeeIdAndStatusAndStartsAtAfter(
            UUID businessId, UUID employeeId, AppointmentStatus status, Instant after);

    /**
     * How many live Appointments a proposed Business Closure would cover.
     *
     * <p>Cancelled ones are excluded: they hold no time and a closure cannot affect them. Completed
     * and no-show ones are counted, because a closure may be entered over a date that has passed and
     * "this covers appointments that already happened" is still true.
     */
    long countByBusinessIdAndStatusNotAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID businessId, AppointmentStatus excluded, Instant to, Instant from);

    /**
     * What the availability engine reads — every Employee's committed time in one call.
     *
     * <p>A projection rather than entities. This is the query that runs on every page of the public
     * booking flow, and the engine needs three columns of it; loading the whole aggregate, its
     * price, note and audit trail included, would be work done to throw away.
     *
     * <p>{@code excluding} is null for an ordinary availability read and is the Appointment being
     * moved during a reschedule — see {@code AppointmentImpact.blockedRangesFor} for why an
     * appointment must not be counted against itself.
     */
    @Query(
            """
            select a.employeeId as employeeId, a.blockedFrom as blockedFrom, a.blockedTo as blockedTo
              from Appointment a
             where a.businessId = :businessId
               and a.employeeId in :employeeIds
               and a.status = dev.reception.appointments.AppointmentStatus.CONFIRMED
               and a.blockedFrom < :to
               and a.blockedTo > :from
               and (:excluding is null or a.id <> :excluding)
            """)
    List<BlockedRangeRow> findByBusinessIdAndEmployeesOverlapping(
            @Param("businessId") UUID businessId,
            @Param("employeeIds") Collection<UUID> employeeIds,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("excluding") UUID excluding);

    /** The three columns {@code AppointmentImpact.blockedRangesFor} answers with. */
    interface BlockedRangeRow {
        UUID getEmployeeId();

        Instant getBlockedFrom();

        Instant getBlockedTo();
    }

    /**
     * The dashboard listing. Every filter is optional and a null one means "do not filter", which is
     * what an untouched control on the screen means.
     *
     * <p>{@code from} and {@code to} are compared against {@code startsAt} rather than against the
     * blocked range: an owner filtering "this week" means appointments that begin this week, not
     * ones whose cleanup buffer reaches into it.
     *
     * <p><strong>Every optional parameter is cast before it is compared to null.</strong> Without
     * the cast PostgreSQL sees a bare placeholder in {@code ? is null}, cannot infer its type from
     * anything around it, and refuses the whole statement with <em>could not determine data type of
     * parameter</em> — at runtime, on the one code path where every filter happens to be absent,
     * which is the default listing.
     */
    @Query(
            """
            select a from Appointment a
             where a.businessId = :businessId
               and (cast(:from as Instant) is null or a.startsAt >= :from)
               and (cast(:to as Instant) is null or a.startsAt < :to)
               and (cast(:status as String) is null or a.status = :status)
               and (cast(:employeeId as java.util.UUID) is null or a.employeeId = :employeeId)
            """)
    Page<Appointment> findByBusinessIdAndFilters(
            @Param("businessId") UUID businessId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("status") AppointmentStatus status,
            @Param("employeeId") UUID employeeId,
            Pageable pageable);

    Page<Appointment> findByBusinessIdAndCustomerIdOrderByStartsAtDesc(
            UUID businessId, UUID customerId, Pageable pageable);

    /** The counts beside each row of the customer list, in one query rather than one per customer. */
    @Query(
            """
            select a.customerId as customerId, count(a) as total, max(a.startsAt) as lastVisit
              from Appointment a
             where a.businessId = :businessId
               and a.customerId in :customerIds
             group by a.customerId
            """)
    List<CustomerActivityRow> findByBusinessIdAndCustomerActivity(
            @Param("businessId") UUID businessId, @Param("customerIds") Collection<UUID> customerIds);

    /** One customer's booking history in summary. */
    interface CustomerActivityRow {
        UUID getCustomerId();

        long getTotal();

        Instant getLastVisit();
    }

    /**
     * Everything a calendar view has to draw: the Appointments that <strong>overlap</strong> the
     * range, not the ones that start inside it.
     *
     * <p><strong>The two are equivalent today, and the overlap form is still the right one.</strong>
     * A booking cannot currently cross midnight — {@code AvailabilityEngine} requires the whole
     * appointment to be contained in one of that date's opening intervals, and intervals do not span
     * days — so nothing exists that starts before a day-aligned range and ends inside it. Written
     * the other way this query would be correct by coincidence, and would start dropping blocks off
     * the top of the view the day overnight hours become expressible. Getting it right costs one
     * comparison.
     *
     * <p>Unpaged, deliberately, and the range is capped by the caller instead. A week for three
     * employees is comfortably more than a page, and a calendar that silently rendered the first
     * hundred would be wrong in exactly the way nobody notices: by leaving things out.
     *
     * <p>Ordered by start so the view can lay columns out in one pass.
     */
    List<Appointment> findByBusinessIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
            UUID businessId, Instant rangeEnd, Instant rangeStart);

}
