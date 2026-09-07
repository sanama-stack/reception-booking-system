package dev.reception.business;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.common.ids.IdGenerator;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business Closures — created from business-local dates, stored as instants.
 *
 * <p>The conversion happens here, once, and it is the only place in the system that turns a
 * closure's dates into a span of time. Everything downstream — the availability engine from phase
 * 05 — receives instants and applies the same range algebra it uses for appointments and time off
 * (ADR-0003).
 */
@Service
public class ClosureService {

    private final BusinessClosureRepository closures;
    private final BusinessRepository businesses;
    private final AppointmentImpact appointmentImpact;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public ClosureService(
            BusinessClosureRepository closures,
            BusinessRepository businesses,
            AppointmentImpact appointmentImpact,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.closures = closures;
        this.businesses = businesses;
        this.appointmentImpact = appointmentImpact;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * A created closure, and how many Appointments it covers.
     *
     * <p>The count is reported and nothing is cancelled. An owner blocking out a week has said they
     * are closed, not that their customers' appointments should silently disappear — those are
     * cancelled deliberately, one at a time, with the customer told (docs/01-prd.md FR-2).
     */
    public record CreatedClosure(BusinessClosure closure, long affectedAppointments) {}

    @Transactional(readOnly = true)
    public List<BusinessClosure> list() {
        return closures.findByBusinessIdOrderByStartsAtAsc(tenant.businessId());
    }

    /**
     * Creates a closure from an inclusive range of business-local dates.
     *
     * <p>{@code endDate} is inclusive because that is what an owner means by "closed the 24th to the
     * 26th"; the stored {@code ends_at} is the start of the following day, which is the same span
     * expressed as a half-open interval. Half-open is what the engine wants — two adjacent closures
     * then neither overlap nor leave a gap — and inclusive is what a person wants, so the
     * translation happens at the edge rather than in either of their heads.
     */
    @Transactional
    public CreatedClosure create(LocalDate startDate, LocalDate endDate, String reason) {
        if (endDate.isBefore(startDate)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The closure's dates are not valid.",
                    List.of(new FieldError("endDate", "The last day cannot be before the first day.")));
        }

        ZoneId zone = business().timezone();
        // atStartOfDay resolves a daylight-saving gap forward rather than throwing, so a closure
        // beginning on the morning a clock jumps starts at the first instant that day actually has.
        Instant startsAt = startDate.atStartOfDay(zone).toInstant();
        Instant endsAt = endDate.plusDays(1).atStartOfDay(zone).toInstant();

        UUID businessId = tenant.businessId();
        BusinessClosure closure =
                new BusinessClosure(ids.newId(), businessId, startsAt, endsAt, blankToNull(reason), clock.instant());
        return new CreatedClosure(
                closures.save(closure), appointmentImpact.countWithin(businessId, startsAt, endsAt));
    }

    /** Hard delete: a closure is a statement about the future and nothing references it. */
    @Transactional
    public void delete(UUID id) {
        if (closures.deleteByBusinessIdAndId(tenant.businessId(), id) == 0) {
            // Another tenant's closure and a closure that never existed are the same answer, by
            // design (docs/06-security.md §3).
            throw ApiException.notFound("No such closure.");
        }
    }

    private Business business() {
        return businesses
                .findById(tenant.businessId())
                .orElseThrow(() -> ApiException.notFound("This business no longer exists."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
