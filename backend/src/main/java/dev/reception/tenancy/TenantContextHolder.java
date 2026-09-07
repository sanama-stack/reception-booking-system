package dev.reception.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The per-request implementation of {@link TenantContext}.
 *
 * <p>A {@code ThreadLocal} rather than a request-scoped bean: one request is served on one thread
 * (virtual threads included), and this avoids a proxy on the hottest object in the application.
 * {@link TenantContextFilter} is the only thing that sets it, and it clears it in a {@code finally}
 * so a pooled or reused carrier thread can never inherit another request's tenant.
 */
@Component
public class TenantContextHolder implements TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    @Override
    public UUID businessId() {
        UUID businessId = CURRENT.get();
        if (businessId == null) {
            throw new IllegalStateException(
                    "No tenant resolved for this request. A tenant-scoped operation ran outside "
                            + "TenantContextFilter — that is a wiring defect, not an authorization failure.");
        }
        return businessId;
    }

    @Override
    public Optional<UUID> currentBusinessId() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void set(UUID businessId) {
        CURRENT.set(businessId);
    }

    static void clear() {
        CURRENT.remove();
    }
}
