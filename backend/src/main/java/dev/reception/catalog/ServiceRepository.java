package dev.reception.catalog;

import dev.reception.tenancy.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Services are tenant-owned, so every method takes {@code businessId} first and there is no
 * {@code findById} to forget it on.
 *
 * <p>{@code findByBusinessIdAndId} returning empty is what makes another tenant's service
 * indistinguishable from one that does not exist: the caller turns both into the same {@code 404}
 * (docs/06-security.md §3).
 */
@TenantScoped
public interface ServiceRepository extends JpaRepository<Service, UUID> {

    List<Service> findByBusinessIdOrderByNameAsc(UUID businessId);

    List<Service> findByBusinessIdAndActiveOrderByNameAsc(UUID businessId, boolean active);

    Optional<Service> findByBusinessIdAndId(UUID businessId, UUID id);

    /** Used to resolve a submitted assignment set; ids that are not another tenant's come back. */
    List<Service> findByBusinessIdAndIdIn(UUID businessId, Collection<UUID> ids);

    /**
     * Case-insensitive, matching {@code services_business_name_unique}. Two services called
     * "Haircut" and "haircut" are two rows an owner cannot tell apart in a dropdown, which is the
     * failure the constraint prevents — so the check that produces a readable message has to ask
     * the same question the constraint does.
     */
    Optional<Service> findByBusinessIdAndNameIgnoreCase(UUID businessId, String name);

    long deleteByBusinessIdAndId(UUID businessId, UUID id);
}
