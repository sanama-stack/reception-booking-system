package dev.reception.business;

import dev.reception.common.error.ApiException;
import dev.reception.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is still missing before a Business can take a booking.
 *
 * <p><strong>Derived on every read, never stored.</strong> A persisted checklist is a second copy of
 * the truth, and the moment an owner deactivates their last employee it becomes a wrong one — with
 * nothing to notice, because nothing would have written to it. Deriving costs a handful of counts
 * and cannot drift.
 */
@Service
public class OnboardingService {

    private final BusinessRepository businesses;
    private final BusinessHoursRepository hours;
    private final CatalogReadiness catalog;
    private final TenantContext tenant;

    public OnboardingService(
            BusinessRepository businesses,
            BusinessHoursRepository hours,
            CatalogReadiness catalog,
            TenantContext tenant) {
        this.businesses = businesses;
        this.hours = hours;
        this.catalog = catalog;
        this.tenant = tenant;
    }

    /**
     * @param hoursConfigured at least one opening interval exists
     * @param publicPageReady every other flag is true — a customer can reach the page and book
     * @param bookingUrl where the public booking page lives, so the dashboard can link to it before
     *     phase 08 builds it
     */
    public record Checklist(
            boolean hoursConfigured,
            boolean hasActiveService,
            boolean hasActiveEmployee,
            boolean hasEmployeeSchedule,
            boolean hasBookableService,
            boolean publicPageReady,
            String bookingUrl) {}

    @Transactional(readOnly = true)
    public Checklist checklist() {
        UUID businessId = tenant.businessId();
        Business business = businesses
                .findById(businessId)
                .orElseThrow(() -> ApiException.notFound("This business no longer exists."));

        boolean hoursConfigured = hours.countByBusinessId(businessId) > 0;
        CatalogReadiness.Snapshot readiness = catalog.of(businessId);

        // Stated as the full conjunction rather than the two flags that actually decide it.
        // hasBookableService already implies an active service, an active employee and a schedule —
        // but writing that shortcut here would make this line depend on a definition kept in another
        // module, and it would keep compiling after that definition changed.
        boolean publicPageReady = hoursConfigured
                && readiness.hasActiveService()
                && readiness.hasActiveEmployee()
                && readiness.hasEmployeeSchedule()
                && readiness.hasBookableService();

        return new Checklist(
                hoursConfigured,
                readiness.hasActiveService(),
                readiness.hasActiveEmployee(),
                readiness.hasEmployeeSchedule(),
                readiness.hasBookableService(),
                publicPageReady,
                "/book/" + business.slug());
    }
}
