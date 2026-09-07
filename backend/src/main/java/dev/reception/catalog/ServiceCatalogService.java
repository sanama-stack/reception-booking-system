package dev.reception.catalog;

import dev.reception.business.AppointmentImpact;
import dev.reception.business.BusinessService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.common.ids.IdGenerator;
import dev.reception.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a Business sells: the Service catalogue.
 *
 * <p>Named {@code ServiceCatalogService} rather than {@code ServiceService} — and the Spring
 * stereotype below is fully qualified because {@code Service} in this package is the entity, not the
 * annotation. That is the price of keeping the domain's central noun (CONTEXT.md) and it is paid
 * once, here.
 */
@org.springframework.stereotype.Service
public class ServiceCatalogService {

    /** The index behind the case-insensitive name rule; matched by name to tell it from any other. */
    private static final String NAME_CONSTRAINT = "services_business_name_unique";

    private final ServiceRepository services;
    private final BusinessService businesses;
    private final AppointmentImpact appointments;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public ServiceCatalogService(
            ServiceRepository services,
            BusinessService businesses,
            AppointmentImpact appointments,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.services = services;
        this.businesses = businesses;
        this.appointments = appointments;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * A Service whose bookability just changed, and how many upcoming Appointments it affects.
     *
     * <p>The count is reported and nothing is cancelled — the same rule Business Closures follow.
     * Deactivating stops new bookings; the ones already made belong to customers who were told a
     * time, and unmaking those is a decision with a phone call attached (docs/01-prd.md FR-2).
     */
    public record BookabilityChange(Service service, long affectedFutureAppointments) {}

    /**
     * @param active {@code null} lists everything, which is what the management screen wants;
     *     {@code true} is what the booking surfaces want. A caller that means "everything" should
     *     not have to say so twice.
     */
    @Transactional(readOnly = true)
    public List<Service> list(Boolean active) {
        UUID businessId = tenant.businessId();
        return active == null
                ? services.findByBusinessIdOrderByNameAsc(businessId)
                : services.findByBusinessIdAndActiveOrderByNameAsc(businessId, active);
    }

    @Transactional(readOnly = true)
    public Service read(UUID id) {
        return services.findByBusinessIdAndId(tenant.businessId(), id)
                .orElseThrow(() -> ApiException.notFound("No such service."));
    }

    /**
     * The currency is stamped from the Business and is never accepted from the caller.
     *
     * <p>It is also never re-stamped afterwards. A Business that switches from USD to GEL has not
     * said that 60.00 is now 60.00 GEL — that is a different amount of money — so the code the price
     * was written in stays with the price, and re-pricing is the owner's deliberate act.
     */
    @Transactional
    public Service create(
            String name,
            String description,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            BigDecimal priceAmount) {
        UUID businessId = tenant.businessId();
        String trimmedName = name.trim();

        ServiceValidation validation = new ServiceValidation()
                .duration("durationMinutes", durationMinutes)
                .buffer("bufferBeforeMinutes", bufferBeforeMinutes)
                .buffer("bufferAfterMinutes", bufferAfterMinutes)
                .price("price", priceAmount);
        rejectDuplicateName(validation, businessId, trimmedName, existing -> true);
        validation.throwIfFailed();

        Service service = new Service(
                ids.newId(),
                businessId,
                trimmedName,
                blankToNull(description),
                durationMinutes,
                bufferBeforeMinutes,
                bufferAfterMinutes,
                // Stored at the column's own scale, so the value that comes back from a create is
                // the same string a later read produces. Validation has already refused anything
                // finer than two places, so this rounds nothing.
                priceAmount.setScale(2),
                businesses.read().currency(),
                clock.instant());
        return saveOrReportDuplicateName(service);
    }

    /** Absent leaves a field alone; see {@link Service#apply} for the rest of the rule. */
    @Transactional
    public Service patch(
            UUID id,
            String name,
            String description,
            Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            BigDecimal priceAmount) {
        UUID businessId = tenant.businessId();
        Service service = read(id);
        String trimmedName = name == null ? null : name.trim();

        ServiceValidation validation = new ServiceValidation()
                .duration("durationMinutes", durationMinutes)
                .buffer("bufferBeforeMinutes", bufferBeforeMinutes)
                .buffer("bufferAfterMinutes", bufferAfterMinutes)
                .price("price", priceAmount);
        if (trimmedName != null) {
            // A service is allowed to keep its own name: the collision that matters is with a
            // different row, and without this a patch that does not touch the name would refuse
            // itself.
            rejectDuplicateName(validation, businessId, trimmedName, other -> !other.getId().equals(id));
        }
        validation.throwIfFailed();

        service.apply(
                trimmedName,
                description,
                durationMinutes,
                bufferBeforeMinutes,
                bufferAfterMinutes,
                priceAmount == null ? null : priceAmount.setScale(2),
                clock.instant());
        return saveOrReportDuplicateName(service);
    }

    @Transactional
    public BookabilityChange setActive(UUID id, boolean active) {
        Service service = read(id);
        service.setActive(active, clock.instant());
        services.save(service);

        // Only a deactivation has an impact worth reporting; re-activating one affects nothing that
        // is not already true.
        long affected = active ? 0 : appointments.countFutureForService(tenant.businessId(), id);
        return new BookabilityChange(service, affected);
    }

    /**
     * Hard delete, refused once the Service has ever been booked.
     *
     * <p>The refusal is by history rather than by the calendar: an Appointment from last year still
     * names the Service it was for, and removing the row would leave that record pointing at
     * nothing. {@code active = false} is the supported way to retire something
     * (docs/03-data-model.md §1), and the message says so, because a {@code 409} whose text does not
     * name the alternative just leaves the owner stuck.
     */
    @Transactional
    public void delete(UUID id) {
        UUID businessId = tenant.businessId();
        // Read first: a delete of another tenant's service must be a 404, not a 409, or the status
        // would confirm the row exists (docs/06-security.md §3).
        read(id);

        if (appointments.everBooked(businessId, id)) {
            throw new ApiException(
                    ErrorCode.SERVICE_IN_USE,
                    "This service has appointments booked against it, so it cannot be deleted. "
                            + "Deactivate it instead — it will stop being offered and its history stays intact.");
        }
        services.deleteByBusinessIdAndId(businessId, id);
    }

    /**
     * The database has the last word on the name rule, and this turns its answer back into a field
     * error.
     *
     * <p>The lookup above loses the race with a concurrent create roughly never; this is what
     * happens when it does. Without it the owner would get a {@code 500} for a mistake the form
     * already knows how to explain.
     */
    private Service saveOrReportDuplicateName(Service service) {
        try {
            // Explicit, so the constraint violation surfaces here rather than at commit, where it
            // would escape this try and reach the handler as a generic 500.
            return services.saveAndFlush(service);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(NAME_CONSTRAINT)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "One or more fields are invalid.",
                        List.of(new FieldError(
                                "name", "You already have a service with this name. Pick a different one.")));
            }
            throw e;
        }
    }

    /**
     * @param isACollision distinguishes "a row with this name exists" from "a <em>different</em> row
     *     with this name exists", which is the difference between a create and a patch
     */
    private void rejectDuplicateName(
            ServiceValidation validation, UUID businessId, String name, Predicate<Service> isACollision) {
        services.findByBusinessIdAndNameIgnoreCase(businessId, name)
                .filter(isACollision)
                .ifPresent(existing -> validation.reject(
                        "name", "You already have a service with this name. Pick a different one."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
