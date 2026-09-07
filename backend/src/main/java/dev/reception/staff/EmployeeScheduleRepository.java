package dev.reception.staff;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Working Schedules, always read and written a whole week at a time.
 *
 * <p>There is no "find Tuesday" here and there should never be one, for the same reason
 * {@code BusinessHoursRepository} has none: overlap is a property of a day, so a writer that can
 * only see one interval cannot tell whether the week it leaves behind is consistent.
 */
@TenantScoped
public interface EmployeeScheduleRepository extends JpaRepository<EmployeeSchedule, UUID> {

    List<EmployeeSchedule> findByBusinessIdAndEmployeeIdOrderByDayOfWeekAscStartsAtAsc(
            UUID businessId, UUID employeeId);

    long deleteByBusinessIdAndEmployeeId(UUID businessId, UUID employeeId);
}
