package dev.reception.catalog.web;

import dev.reception.catalog.AssignmentService;
import dev.reception.catalog.EmployeeServiceAssignment;
import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Service catalogue surface (docs/04-api-overview.md §5).
 *
 * <p>No method here names a business. The {@code {id}} path variable is a Service, looked up by
 * {@code (businessId, id)} inside the application service — which is why another tenant's id comes
 * back as {@code 404} rather than as their data.
 *
 * <p>Configuration is an owner's job: {@code OWNER} and {@code ADMIN} only, declared once for the
 * class (docs/06-security.md §3).
 */
@RestController
@RequestMapping("/services")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class ServiceController {

    private final ServiceCatalogService catalog;
    private final AssignmentService assignments;

    public ServiceController(ServiceCatalogService catalog, AssignmentService assignments) {
        this.catalog = catalog;
        this.assignments = assignments;
    }

    /** @param active omitted lists everything, which is what the management screen wants. */
    @GetMapping
    public ServiceResponses.ServiceList list(@RequestParam(required = false) Boolean active) {
        List<Service> services = catalog.list(active);
        // One query for every assignment in the business rather than one per service: the list is
        // the screen most likely to have fifty rows on it.
        Map<UUID, List<UUID>> employeesByService = assignments.all().stream()
                .collect(Collectors.groupingBy(
                        EmployeeServiceAssignment::serviceId,
                        Collectors.mapping(EmployeeServiceAssignment::employeeId, Collectors.toList())));
        return ServiceResponses.ServiceList.of(services, employeesByService);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponses.ServiceDetail create(@Valid @RequestBody ServiceRequests.CreateService request) {
        Service created = catalog.create(
                request.name(),
                request.description(),
                request.durationMinutes(),
                zeroIfAbsent(request.bufferBeforeMinutes()),
                zeroIfAbsent(request.bufferAfterMinutes()),
                request.price());
        // A service is created with nobody assigned; saying so explicitly beats leaving the client
        // to infer it from an absent field.
        return ServiceResponses.ServiceDetail.of(created, List.of());
    }

    @GetMapping("/{id}")
    public ServiceResponses.ServiceDetail read(@PathVariable UUID id) {
        return ServiceResponses.ServiceDetail.of(catalog.read(id), assignments.employeesFor(id));
    }

    @PatchMapping("/{id}")
    public ServiceResponses.ServiceDetail patch(
            @PathVariable UUID id, @Valid @RequestBody ServiceRequests.PatchService request) {
        Service patched = catalog.patch(
                id,
                request.name(),
                request.description(),
                request.durationMinutes(),
                request.bufferBeforeMinutes(),
                request.bufferAfterMinutes(),
                request.price());
        return ServiceResponses.ServiceDetail.of(patched, assignments.employeesFor(id));
    }

    @PostMapping("/{id}/activate")
    public ServiceResponses.BookabilityChange activate(@PathVariable UUID id) {
        return ServiceResponses.BookabilityChange.of(catalog.setActive(id, true), assignments.employeesFor(id));
    }

    /** Reports how many upcoming Appointments this affects. Nothing is cancelled. */
    @PostMapping("/{id}/deactivate")
    public ServiceResponses.BookabilityChange deactivate(@PathVariable UUID id) {
        return ServiceResponses.BookabilityChange.of(catalog.setActive(id, false), assignments.employeesFor(id));
    }

    /** Refused with {@code 409 SERVICE_IN_USE} once the Service has ever been booked. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        catalog.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Replaces the whole set. Absent ids are removed; the operation is idempotent. */
    @PutMapping("/{id}/employees")
    public ServiceResponses.AssignedEmployees replaceEmployees(
            @PathVariable UUID id, @Valid @RequestBody ServiceRequests.ReplaceEmployees request) {
        return new ServiceResponses.AssignedEmployees(assignments.replaceEmployeesFor(id, request.employeeIds()));
    }

    private static int zeroIfAbsent(Integer minutes) {
        return minutes == null ? 0 : minutes;
    }
}
