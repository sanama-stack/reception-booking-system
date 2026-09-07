package dev.reception.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The business every query in the current request is confined to.
 *
 * <p>This is the single most important seam in the codebase. {@code business_id} is <em>derived</em>
 * here — from the authenticated Membership, from the slug on a public request, or from the
 * conversation record on an AI tool call — and never arrives from a path, a body, a header or a
 * tool argument (docs/02-product-architecture.md §4).
 */
public interface TenantContext {

    /**
     * The current business.
     *
     * @throws IllegalStateException when called outside a tenant-resolved request, which is a
     *     programming error rather than an authorization failure — a controller that needs a tenant
     *     must be behind the filter that resolves one
     */
    UUID businessId();

    /** The current business, or empty on a request with no tenant (login, registration, health). */
    Optional<UUID> currentBusinessId();
}
