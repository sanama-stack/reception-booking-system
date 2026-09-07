package dev.reception.business;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Business Hours are tenant-owned, so every method takes {@code businessId} first and there is no
 * {@code findById} to forget it on.
 */
@TenantScoped
public interface BusinessHoursRepository extends JpaRepository<BusinessHours, UUID> {

    List<BusinessHours> findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(UUID businessId);

    long countByBusinessId(UUID businessId);
}
