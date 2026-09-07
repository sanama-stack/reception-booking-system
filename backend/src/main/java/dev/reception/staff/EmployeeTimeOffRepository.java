package dev.reception.staff;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Time Off is tenant-owned, and always read for one Employee at a time. */
@TenantScoped
public interface EmployeeTimeOffRepository extends JpaRepository<EmployeeTimeOff, UUID> {

    List<EmployeeTimeOff> findByBusinessIdAndEmployeeIdOrderByStartsAtAsc(UUID businessId, UUID employeeId);

    long deleteByBusinessIdAndEmployeeIdAndId(UUID businessId, UUID employeeId, UUID id);
}
