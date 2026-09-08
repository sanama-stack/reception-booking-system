package dev.reception.appointments;

import dev.reception.common.error.ApiException;
import dev.reception.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One Appointment by id, scoped to the current tenant.
 *
 * <p>Four services need this and all four must refuse another tenant's id the same way — as a
 * {@code 404}, indistinguishable from an id that does not exist (docs/06-security.md §3). Four
 * copies of a two-line method is four places for one of them to be written without the tenant.
 */
@Component
public class AppointmentLookup {

    private final AppointmentRepository appointments;
    private final TenantContext tenant;

    public AppointmentLookup(AppointmentRepository appointments, TenantContext tenant) {
        this.appointments = appointments;
        this.tenant = tenant;
    }

    @Transactional(readOnly = true)
    public Appointment require(UUID id) {
        return appointments
                .findByBusinessIdAndId(tenant.businessId(), id)
                .orElseThrow(() -> ApiException.notFound("No such appointment."));
    }
}
