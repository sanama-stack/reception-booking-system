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

    /**
     * The delete half of the whole-week replace.
     *
     * <p>Derived rather than a {@code @Query}, so it is subject to the same shape rule as every
     * other method here. It flushes within the caller's transaction; {@link BusinessHoursService}
     * forces that flush before inserting, because the unique constraint on
     * {@code (business_id, day_of_week, opens_at)} does not care that the row it collides with is
     * about to be deleted.
     */
    long deleteByBusinessId(UUID businessId);
}
