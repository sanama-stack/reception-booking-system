package dev.reception.business;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * FAQs are tenant-owned. Ordering is by {@code sort_order} then {@code created_at}, so two FAQs an
 * owner never reordered still come back in a stable order rather than whatever the heap returns.
 */
@TenantScoped
public interface BusinessFaqRepository extends JpaRepository<BusinessFaq, UUID> {

    List<BusinessFaq> findByBusinessIdOrderBySortOrderAscCreatedAtAsc(UUID businessId);

    Optional<BusinessFaq> findByBusinessIdAndId(UUID businessId, UUID id);

    long countByBusinessId(UUID businessId);

    long deleteByBusinessIdAndId(UUID businessId, UUID id);
}
