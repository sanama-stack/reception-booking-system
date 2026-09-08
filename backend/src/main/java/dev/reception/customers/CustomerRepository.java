package dev.reception.customers;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Customers are tenant-owned; see {@code ServiceRepository} for the shape rule and why it exists. */
@TenantScoped
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByBusinessIdAndId(UUID businessId, UUID id);

    /**
     * The identity lookup. {@code phone} must already be E.164 — see
     * {@code CustomerService.findOrCreate}, which is the only caller that normalises it.
     */
    Optional<Customer> findByBusinessIdAndPhone(UUID businessId, String phone);

    Page<Customer> findByBusinessIdOrderByFullNameAsc(UUID businessId, Pageable pageable);

    /**
     * Dashboard search over name, phone and email.
     *
     * <p>The method name still begins with {@code findByBusinessId} because
     * {@code TenantRepositoryShapeTest} reads names rather than queries: a {@code @Query} is exactly
     * where a tenant filter is easiest to omit, so the naming rule has to hold hardest here.
     *
     * <p>{@code lower(full_name)} matches the expression index in V5. Written as {@code lower(…)
     * like} rather than {@code ilike} for that reason — {@code ilike} would not use it.
     */
    @Query(
            """
            select c from Customer c
             where c.businessId = :businessId
               and (lower(c.fullName) like :pattern
                    or lower(c.phone) like :pattern
                    or lower(c.email) like :pattern)
             order by c.fullName asc
            """)
    Page<Customer> findByBusinessIdAndMatching(
            @Param("businessId") UUID businessId, @Param("pattern") String pattern, Pageable pageable);

    List<Customer> findByBusinessIdAndIdIn(UUID businessId, List<UUID> ids);
}
