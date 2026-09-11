package dev.reception.staff;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Time Off is tenant-owned, and read two ways: one Employee at a time for their own screen, and
 * every Employee at once for a calendar range (phase 10).
 */
@TenantScoped
public interface EmployeeTimeOffRepository extends JpaRepository<EmployeeTimeOff, UUID> {

    List<EmployeeTimeOff> findByBusinessIdAndEmployeeIdOrderByStartsAtAsc(UUID businessId, UUID employeeId);

    long deleteByBusinessIdAndEmployeeIdAndId(UUID businessId, UUID employeeId, UUID id);

    /**
     * Every Employee's time off across a range, in one query.
     *
     * <p>The per-employee method above answers the Employee screen, where one person is in view.
     * A calendar has every column on screen at once, and asking it once per employee is the N+1
     * that arrives the day a business hires its fifth person.
     */
    List<EmployeeTimeOff> findByBusinessIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
            UUID businessId, java.time.Instant rangeEnd, java.time.Instant rangeStart);

}
