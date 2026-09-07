package dev.reception.staff;

import dev.reception.tenancy.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Employees are tenant-owned; see {@code ServiceRepository} for the shape rule and why it exists. */
@TenantScoped
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    List<Employee> findByBusinessIdOrderByFullNameAsc(UUID businessId);

    List<Employee> findByBusinessIdAndActiveOrderByFullNameAsc(UUID businessId, boolean active);

    Optional<Employee> findByBusinessIdAndId(UUID businessId, UUID id);

    /** Resolves a submitted assignment set. An id belonging to another tenant simply does not come back. */
    List<Employee> findByBusinessIdAndIdIn(UUID businessId, Collection<UUID> ids);

    long deleteByBusinessIdAndId(UUID businessId, UUID id);
}
