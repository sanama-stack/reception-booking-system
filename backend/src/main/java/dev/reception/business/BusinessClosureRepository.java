package dev.reception.business;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Business Closures are tenant-owned, so every method takes {@code businessId} first and there is
 * no {@code findById} to forget it on.
 *
 * <p>{@code findByBusinessIdAndId} returning empty is what makes a cross-tenant read indistinguishable
 * from a missing row: the caller turns both into the same {@code 404} (docs/06-security.md §3).
 */
@TenantScoped
public interface BusinessClosureRepository extends JpaRepository<BusinessClosure, UUID> {

    List<BusinessClosure> findByBusinessIdOrderByStartsAtAsc(UUID businessId);

    Optional<BusinessClosure> findByBusinessIdAndId(UUID businessId, UUID id);

    long deleteByBusinessIdAndId(UUID businessId, UUID id);
}
