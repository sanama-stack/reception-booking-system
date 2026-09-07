package dev.reception.catalog;

import dev.reception.common.error.ApiException;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeRepository;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which Employees may perform which Services — both directions of one table.
 *
 * <p>{@code PUT /services/{id}/employees} and {@code PUT /employees/{id}/services} are two views of
 * {@code employee_services}, so they are written here rather than split between the catalog and the
 * staff services. One table, one writer: two writers would each have to know the other's rules about
 * what a valid pair is, and the day they disagreed the disagreement would be a cross-tenant row.
 *
 * <p><strong>Replace, not add and remove.</strong> A submitted set is the whole truth — rows not in
 * it go. That makes the operation idempotent, which a sequence of adds and removes is not, and it
 * matches what a multi-select control actually knows: which boxes are ticked now, not which ones
 * changed.
 */
@org.springframework.stereotype.Service
public class AssignmentService {

    private final EmployeeServiceAssignmentRepository assignments;
    private final ServiceRepository services;
    private final EmployeeRepository employees;
    private final TenantContext tenant;
    private final Clock clock;

    public AssignmentService(
            EmployeeServiceAssignmentRepository assignments,
            ServiceRepository services,
            EmployeeRepository employees,
            TenantContext tenant,
            Clock clock) {
        this.assignments = assignments;
        this.services = services;
        this.employees = employees;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UUID> employeesFor(UUID serviceId) {
        UUID businessId = tenant.businessId();
        requireService(businessId, serviceId);
        return assignments.findByBusinessIdAndServiceId(businessId, serviceId).stream()
                .map(EmployeeServiceAssignment::employeeId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UUID> servicesFor(UUID employeeId) {
        UUID businessId = tenant.businessId();
        requireEmployee(businessId, employeeId);
        return assignments.findByBusinessIdAndEmployeeId(businessId, employeeId).stream()
                .map(EmployeeServiceAssignment::serviceId)
                .toList();
    }

    /** Every assignment in the business, so a list screen can count them without a query per row. */
    @Transactional(readOnly = true)
    public List<EmployeeServiceAssignment> all() {
        return assignments.findByBusinessId(tenant.businessId());
    }

    /** Replaces the set of Employees eligible to perform one Service. */
    @Transactional
    public List<UUID> replaceEmployeesFor(UUID serviceId, List<UUID> employeeIds) {
        UUID businessId = tenant.businessId();
        requireService(businessId, serviceId);

        Set<UUID> requested = distinct(employeeIds);
        Set<UUID> known = employees.findByBusinessIdAndIdIn(businessId, requested).stream()
                .map(Employee::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // An id belonging to another tenant simply did not come back, which is the same answer as
        // an id that never existed — by design (docs/06-security.md §3). The composite foreign key
        // would refuse the row anyway; this is what turns that into a sentence instead of a 500.
        requireAllResolved(requested, known, "employeeIds", "employee");

        assignments.deleteByBusinessIdAndServiceId(businessId, serviceId);
        // Hibernate orders operations by entity type, not by the order they were requested in, so
        // without this the inserts can reach the database before the deletes and collide with the
        // primary key they are about to free (the same trap BusinessHoursService.replaceWeek pays).
        assignments.flush();

        List<EmployeeServiceAssignment> replaced = new ArrayList<>(known.size());
        for (UUID employeeId : known) {
            replaced.add(new EmployeeServiceAssignment(businessId, employeeId, serviceId, clock.instant()));
        }
        assignments.saveAll(replaced);
        return List.copyOf(known);
    }

    /** Replaces the set of Services one Employee may perform. The mirror of the above. */
    @Transactional
    public List<UUID> replaceServicesFor(UUID employeeId, List<UUID> serviceIds) {
        UUID businessId = tenant.businessId();
        requireEmployee(businessId, employeeId);

        Set<UUID> requested = distinct(serviceIds);
        Set<UUID> known = services.findByBusinessIdAndIdIn(businessId, requested).stream()
                .map(Service::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        requireAllResolved(requested, known, "serviceIds", "service");

        assignments.deleteByBusinessIdAndEmployeeId(businessId, employeeId);
        assignments.flush();

        List<EmployeeServiceAssignment> replaced = new ArrayList<>(known.size());
        for (UUID serviceId : known) {
            replaced.add(new EmployeeServiceAssignment(businessId, employeeId, serviceId, clock.instant()));
        }
        assignments.saveAll(replaced);
        return List.copyOf(known);
    }

    /**
     * A submitted id that resolved to nothing is a {@code 404}, not a silently shorter set.
     *
     * <p>Dropping it would let an owner believe they had assigned someone they had not, and the
     * symptom would arrive much later as a service nobody can book.
     */
    private static void requireAllResolved(Set<UUID> requested, Set<UUID> known, String field, String noun) {
        if (known.size() != requested.size()) {
            throw ApiException.notFound("One of those " + noun + " ids does not exist.");
        }
    }

    /** Preserves submission order and tolerates a repeated id, which a set replace has no reason to refuse. */
    private static Set<UUID> distinct(List<UUID> ids) {
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    private void requireService(UUID businessId, UUID serviceId) {
        services.findByBusinessIdAndId(businessId, serviceId)
                .orElseThrow(() -> ApiException.notFound("No such service."));
    }

    private void requireEmployee(UUID businessId, UUID employeeId) {
        employees.findByBusinessIdAndId(businessId, employeeId)
                .orElseThrow(() -> ApiException.notFound("No such employee."));
    }
}
