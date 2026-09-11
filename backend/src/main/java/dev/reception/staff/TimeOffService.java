package dev.reception.staff;

import dev.reception.business.BusinessService;
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
 * Time Off — created from business-local dates, stored as instants.
 *
 * <p>The conversion happens here and nowhere else, exactly as {@code ClosureService} does it for a
 * Business Closure. Phase 05 then subtracts an absent employee and a closed business with one piece
 * of range algebra instead of two (ADR-0003).
 */
@Service
public class TimeOffService {

    private final EmployeeTimeOffRepository timeOff;
    private final EmployeeRepository employees;
    private final BusinessService businesses;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public TimeOffService(
            EmployeeTimeOffRepository timeOff,
            EmployeeRepository employees,
            BusinessService businesses,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.timeOff = timeOff;
        this.employees = employees;
        this.businesses = businesses;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EmployeeTimeOff> list(UUID employeeId) {
        UUID businessId = tenant.businessId();
        requireEmployee(businessId, employeeId);
        return timeOff.findByBusinessIdAndEmployeeIdOrderByStartsAtAsc(businessId, employeeId);
    }

    /**
     * Every Employee's absences across a calendar range (phase 10).
     *
     * <p>No employee is named and none is checked, because there is no id here to get wrong — the
     * tenant filter is the whole of the scoping, exactly as it is for the appointments the same
     * view draws beside these.
     */
    @Transactional(readOnly = true)
    public List<EmployeeTimeOff> inRange(Instant from, Instant to) {
        return timeOff.findByBusinessIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
                tenant.businessId(), to, from);
    }

    /**
     * Creates an absence from an inclusive range of business-local dates.
     *
     * <p>{@code endDate} is inclusive because "off from the 24th to the 26th" includes the 26th;
     * the stored {@code ends_at} is the start of the following day, which is the same span written
     * half-open. Half-open is what the engine wants — two adjacent absences then meet exactly rather
     * than overlapping or leaving an hour between them — and inclusive is what a person means, so the
     * translation happens at the edge rather than in either of their heads.
     *
     * <p>A single day off is {@code startDate == endDate}, which stores as a full 24 hours rather
     * than as a zero-length range the {@code CHECK} would refuse.
     */
    @Transactional
    public EmployeeTimeOff create(UUID employeeId, LocalDate startDate, LocalDate endDate, String reason) {
        UUID businessId = tenant.businessId();
        requireEmployee(businessId, employeeId);

        if (endDate.isBefore(startDate)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The dates are not valid.",
                    List.of(new FieldError("endDate", "The last day cannot be before the first day.")));
        }

        ZoneId zone = businesses.read().timezone();
        // atStartOfDay resolves a daylight-saving gap forward rather than throwing, so an absence
        // beginning on the morning a clock jumps starts at the first instant that day actually has.
        Instant startsAt = startDate.atStartOfDay(zone).toInstant();
        Instant endsAt = endDate.plusDays(1).atStartOfDay(zone).toInstant();

        EmployeeTimeOff created = new EmployeeTimeOff(
                ids.newId(), businessId, employeeId, startsAt, endsAt, blankToNull(reason), clock.instant());
        return timeOff.save(created);
    }

    /** Hard delete: an absence is a statement about the future and nothing references it. */
    @Transactional
    public void delete(UUID employeeId, UUID id) {
        UUID businessId = tenant.businessId();
        requireEmployee(businessId, employeeId);

        if (timeOff.deleteByBusinessIdAndEmployeeIdAndId(businessId, employeeId, id) == 0) {
            // Another tenant's row and a row that never existed are the same answer, by design.
            throw ApiException.notFound("No such time off.");
        }
    }

    private void requireEmployee(UUID businessId, UUID employeeId) {
        employees.findByBusinessIdAndId(businessId, employeeId)
                .orElseThrow(() -> ApiException.notFound("No such employee."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
