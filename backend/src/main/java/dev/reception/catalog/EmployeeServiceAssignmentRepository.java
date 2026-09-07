package dev.reception.catalog;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The eligible-employee sets, readable from either end.
 *
 * <p>Tenant-scoped like every other repository here, even though the composite foreign keys already
 * make a cross-tenant row impossible. The database constraint stops a bad <em>write</em>; this stops
 * a bad <em>read</em>, and neither substitutes for the other.
 */
@TenantScoped
public interface EmployeeServiceAssignmentRepository
        extends JpaRepository<EmployeeServiceAssignment, EmployeeServiceAssignment.Key> {

    /** Which Employees may perform this Service. */
    List<EmployeeServiceAssignment> findByBusinessIdAndServiceId(UUID businessId, UUID serviceId);

    /** Which Services this Employee may perform. */
    List<EmployeeServiceAssignment> findByBusinessIdAndEmployeeId(UUID businessId, UUID employeeId);

    /** Every assignment in the business, for a list screen that would otherwise ask once per row. */
    List<EmployeeServiceAssignment> findByBusinessId(UUID businessId);

    long deleteByBusinessIdAndServiceId(UUID businessId, UUID serviceId);

    long deleteByBusinessIdAndEmployeeId(UUID businessId, UUID employeeId);
}
