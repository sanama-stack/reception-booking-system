package dev.reception.tenancy;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Adopts a tenant part-way through a request, for the one surface that cannot know it any earlier.
 *
 * <p>Every other door resolves the tenant before a controller runs — {@link TenantContextFilter}
 * from the Membership on the session, {@link SlugTenantContextFilter} from the slug in the path. The
 * public Manage Link and Confirmation Code endpoints have neither: {@code /public/appointments/*}
 * carries no slug, and which Business the request concerns is not known until the capability has
 * been verified and the Appointment it names has been found.
 *
 * <p><strong>The order is the whole of the safety property.</strong> Authority is proven first and
 * the tenant is derived from what that authority turned out to authorise — never the reverse, and
 * never from anything the caller sent. A token names an Appointment; the Appointment names the
 * Business. A caller who supplies a token for another Business's appointment gets that Business, and
 * gets nothing in it beyond the one appointment their token already authorised.
 *
 * <p>Public so that {@code publicapi} can call it, and deliberately the only public way to set a
 * tenant anywhere in the application — {@code TenantContextHolder.set} stays package-private, so
 * every tenant resolution in the system is this class, the two filters, and nothing else.
 * {@code PublicAppointmentAuthority} is its only caller; a second one is worth reading carefully.
 */
@Component
public class TenantAdoption {

    /**
     * Confines the rest of this request to {@code businessId}.
     *
     * <p>Cleared by {@link TenantContextFilter}'s {@code finally} like any other resolution, so a
     * pooled carrier thread cannot inherit it.
     */
    public void adopt(UUID businessId) {
        TenantContextHolder.set(businessId);
        MDC.put(TenantContextFilter.MDC_KEY, businessId.toString());
    }
}
