package dev.reception.publicapi;

import dev.reception.business.BusinessHoursService;
import dev.reception.business.BusinessService;
import dev.reception.catalog.AssignmentService;
import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a stranger may read about a Business before booking with it.
 *
 * <p><strong>No method here takes a business.</strong> The {@code {slug}} in the path is consumed by
 * {@code SlugTenantContextFilter} before this class runs, and every service below reads from the
 * tenant it resolved — the same services, and the same tenant scoping, the dashboard uses. That is
 * what makes "the public surface adds no privileged path" a structural claim rather than a promise:
 * there is no query here that the authenticated surface does not already make.
 *
 * <p>The slug is bound and ignored. Declaring it is what makes the mapping match; using it would be
 * resolving a tenant twice, and the second resolution is the one that eventually disagrees with the
 * first.
 *
 * <p>Every response is a {@code PublicResponses} record built by hand. Nothing on this surface
 * returns an entity, and {@code PublicFieldAllowListTest} enforces it.
 */
@RestController
@RequestMapping("/public/businesses/{slug}")
public class PublicBusinessController {

    private final BusinessService businesses;
    private final BusinessHoursService hours;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final AssignmentService assignments;

    public PublicBusinessController(
            BusinessService businesses,
            BusinessHoursService hours,
            ServiceCatalogService catalog,
            EmployeeService employees,
            AssignmentService assignments) {
        this.businesses = businesses;
        this.hours = hours;
        this.catalog = catalog;
        this.employees = employees;
        this.assignments = assignments;
    }

    @GetMapping
    public PublicResponses.BusinessProfile profile() {
        return PublicResponses.BusinessProfile.of(businesses.read(), hours.read());
    }

    /**
     * The bookable catalog.
     *
     * <p>Active only, and not because the page would look untidy otherwise: an inactive Service is
     * one the business has switched off, and offering it would produce a booking the availability
     * engine refuses with {@code SERVICE_INACTIVE} after the Customer has entered their details.
     */
    @GetMapping("/services")
    public List<PublicResponses.ServiceSummary> services() {
        return catalog.list(true).stream().map(PublicResponses.ServiceSummary::of).toList();
    }

    /**
     * Who could perform a Service, for the "Any available, or somebody in particular" choice.
     *
     * @param serviceId optional. Given, the list is the active Employees assigned to that Service —
     *     which is the only list worth showing, because naming anyone else produces
     *     {@code EMPLOYEE_CANNOT_PERFORM_SERVICE} at the end of the flow rather than at the start
     */
    @GetMapping("/employees")
    public List<PublicResponses.EmployeeSummary> employees(@RequestParam(required = false) UUID serviceId) {
        List<Employee> active = employees.list(true);
        if (serviceId == null) {
            return active.stream().map(PublicResponses.EmployeeSummary::of).toList();
        }

        // read() first, so an unknown or another tenant's serviceId is a 404 rather than an empty
        // list. "Nobody can do this" and "there is no such service" are different answers and only
        // one of them means the caller should try a different service.
        Service service = catalog.read(serviceId);
        Set<UUID> assigned = Set.copyOf(assignments.employeesFor(service.getId()));
        return active.stream()
                .filter(employee -> assigned.contains(employee.getId()))
                .map(PublicResponses.EmployeeSummary::of)
                .toList();
    }
}
