package dev.reception.business;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and updating the tenant's own profile and settings.
 *
 * <p>Every method resolves the business from {@link TenantContext} and nowhere else. There is no
 * overload taking a business id, which is what makes "which business?" a question this class cannot
 * be asked wrongly (docs/02-product-architecture.md §4).
 */
@Service
public class BusinessService {

    /** The constraint the database reports, so an integrity error is mapped rather than guessed. */
    private static final String SLUG_CONSTRAINT = "businesses_slug_unique";

    private final BusinessRepository businesses;
    private final TenantContext tenant;
    private final Clock clock;

    public BusinessService(BusinessRepository businesses, TenantContext tenant, Clock clock) {
        this.businesses = businesses;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Business read() {
        return current();
    }

    /**
     * Applies a partial update.
     *
     * <p>The slug is the only field with a rule beyond its own format, because it is the only one
     * that is public and shared: two businesses cannot hold one URL. A requested slug that is taken
     * is <strong>refused</strong> rather than suffixed — registration suffixes, because it is
     * deriving a slug the owner never asked for, but an owner who types {@code salon-aria} and
     * silently receives {@code salon-aria-2} has been given a different address than the one they
     * chose and will not find out until they print it on something.
     *
     * <p>Changing the slug changes the public booking page's URL immediately, and the old one stops
     * resolving. That is the intended behaviour and the reason the confirmation lives in the UI.
     */
    @Transactional
    public Business patch(BusinessPatch patch) {
        Business business = current();
        BusinessPatch normalised = normalise(patch);
        validate(normalised);

        if (normalised.slug() != null && !normalised.slug().equals(business.slug())
                && businesses.existsBySlug(normalised.slug())) {
            throw slugTaken();
        }

        business.apply(normalised, clock.instant());
        try {
            // Explicit, so the unique-constraint violation surfaces here rather than at commit,
            // where it would escape this try and reach the handler as a generic 500.
            return businesses.saveAndFlush(business);
        } catch (DataIntegrityViolationException e) {
            // The existsBySlug check above loses a race with a concurrent registration roughly
            // never, and this is what happens when it does.
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(SLUG_CONSTRAINT)) {
                throw slugTaken();
            }
            throw e;
        }
    }

    /**
     * Trims the free text and lowercases the two fields whose storage is case-defined.
     *
     * <p>An owner typing {@code Salon-Aria} or {@code ge} has not made a mistake worth a form error;
     * an owner typing {@code Salon Aria} has, because a space cannot be silently turned into
     * something they would recognise as their address.
     */
    private BusinessPatch normalise(BusinessPatch p) {
        return new BusinessPatch(
                trim(p.name()),
                lower(p.slug()),
                trim(p.timezone()),
                upper(p.currency()),
                trim(p.description()),
                trim(p.addressLine()),
                trim(p.city()),
                upper(p.country()),
                trim(p.phone()),
                trim(p.email()),
                trim(p.website()),
                p.slotIntervalMinutes(),
                p.minLeadTimeMinutes(),
                p.maxAdvanceDays(),
                p.cancellationWindowHours(),
                trim(p.cancellationPolicy()),
                p.aiEnabled(),
                trim(p.aiAdditionalInfo()),
                p.aiDailyCostCapCents());
    }

    /**
     * The rules Bean Validation cannot express — real zone, real currency, real country, legal slug.
     * Ranges and lengths are already guaranteed by the request DTO before anything reaches here.
     */
    private void validate(BusinessPatch patch) {
        BusinessValidation validation = new BusinessValidation()
                .timezone("timezone", patch.timezone())
                .currency("currency", patch.currency())
                .country("country", patch.country())
                .slug("slug", patch.slug());
        if (patch.name() != null && patch.name().isBlank()) {
            // The DTO already rejects this. Repeated here because the rule belongs to the domain
            // and the DTO belongs to one caller: a name is not an optional field that blanking
            // clears, and this class must not depend on remembering that somewhere else.
            validation.reject("name", "Enter your business name.");
        }
        validation.throwIfFailed();
    }

    private Business current() {
        return businesses
                .findById(tenant.businessId())
                // The tenant comes from a signed token, so this means the row was deleted under a
                // live session rather than that a caller named someone else's business.
                .orElseThrow(() -> ApiException.notFound("This business no longer exists."));
    }

    private static ApiException slugTaken() {
        return new ApiException(
                ErrorCode.SLUG_TAKEN, "That web address is already taken. Try a different one.");
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
