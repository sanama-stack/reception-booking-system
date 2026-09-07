package dev.reception.staff.web;

import dev.reception.business.BusinessService;
import dev.reception.catalog.AssignmentService;
import dev.reception.catalog.EmployeeServiceAssignment;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeScheduleService;
import dev.reception.staff.EmployeeService;
import dev.reception.staff.TimeOffService;
import jakarta.validation.Valid;
import java.time.DayOfWeek;
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
 * The Employee surface (docs/04-api-overview.md §5) — the person, their service assignments, their
 * Working Schedule and their Time Off.
 *
 * <p>No method here names a business. Every {@code {id}} is an Employee, looked up by
 * {@code (businessId, id)} inside the application service, which is why another tenant's id comes
 * back as {@code 404} rather than as their data. The nested {@code {offId}} is checked against the
 * employee <em>and</em> the tenant, so a Time Off id cannot be borrowed across employees either.
 *
 * <p>{@code OWNER} and {@code ADMIN} only, declared once for the class.
 */
@RestController
@RequestMapping("/employees")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class EmployeeController {

    private final EmployeeService employees;
    private final EmployeeScheduleService schedules;
    private final TimeOffService timeOff;
    private final AssignmentService assignments;
    private final BusinessService businesses;

    public EmployeeController(
            EmployeeService employees,
            EmployeeScheduleService schedules,
            TimeOffService timeOff,
            AssignmentService assignments,
            BusinessService businesses) {
        this.employees = employees;
        this.schedules = schedules;
        this.timeOff = timeOff;
        this.assignments = assignments;
        this.businesses = businesses;
    }

    @GetMapping
    public EmployeeResponses.EmployeeList list(@RequestParam(required = false) Boolean active) {
        List<Employee> found = employees.list(active);
        // One query for every assignment in the business rather than one per employee.
        Map<UUID, List<UUID>> servicesByEmployee = assignments.all().stream()
                .collect(Collectors.groupingBy(
                        EmployeeServiceAssignment::employeeId,
                        Collectors.mapping(EmployeeServiceAssignment::serviceId, Collectors.toList())));
        return EmployeeResponses.EmployeeList.of(found, servicesByEmployee);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponses.EmployeeDetail create(@Valid @RequestBody EmployeeRequests.CreateEmployee request) {
        Employee created =
                employees.create(request.fullName(), request.email(), request.phone(), request.jobTitle());
        return EmployeeResponses.EmployeeDetail.of(created, List.of());
    }

    @GetMapping("/{id}")
    public EmployeeResponses.EmployeeDetail read(@PathVariable UUID id) {
        return EmployeeResponses.EmployeeDetail.of(employees.read(id), assignments.servicesFor(id));
    }

    @PatchMapping("/{id}")
    public EmployeeResponses.EmployeeDetail patch(
            @PathVariable UUID id, @Valid @RequestBody EmployeeRequests.PatchEmployee request) {
        Employee patched =
                employees.patch(id, request.fullName(), request.email(), request.phone(), request.jobTitle());
        return EmployeeResponses.EmployeeDetail.of(patched, assignments.servicesFor(id));
    }

    @PostMapping("/{id}/activate")
    public EmployeeResponses.BookabilityChange activate(@PathVariable UUID id) {
        return EmployeeResponses.BookabilityChange.of(employees.setActive(id, true), assignments.servicesFor(id));
    }

    /** Reports how many upcoming Appointments this affects. Nothing is cancelled. */
    @PostMapping("/{id}/deactivate")
    public EmployeeResponses.BookabilityChange deactivate(@PathVariable UUID id) {
        return EmployeeResponses.BookabilityChange.of(employees.setActive(id, false), assignments.servicesFor(id));
    }

    /** Replaces the whole set. The mirror of {@code PUT /services/{id}/employees}. */
    @PutMapping("/{id}/services")
    public EmployeeResponses.AssignedServices replaceServices(
            @PathVariable UUID id, @Valid @RequestBody EmployeeRequests.ReplaceServices request) {
        return new EmployeeResponses.AssignedServices(assignments.replaceServicesFor(id, request.serviceIds()));
    }

    // -----------------------------------------------------------------------
    // Working Schedule
    // -----------------------------------------------------------------------

    @GetMapping("/{id}/schedule")
    public EmployeeResponses.WeekSchedule readSchedule(@PathVariable UUID id) {
        return EmployeeResponses.WeekSchedule.of(timezone(), schedules.read(id));
    }

    /** Replaces the whole week. A day absent from the payload is a day off. */
    @PutMapping("/{id}/schedule")
    public EmployeeResponses.WeekSchedule replaceSchedule(
            @PathVariable UUID id, @Valid @RequestBody EmployeeRequests.ReplaceSchedule request) {
        List<EmployeeScheduleService.Interval> week = request.schedule().stream()
                .map(interval -> new EmployeeScheduleService.Interval(
                        DayOfWeek.of(interval.dayOfWeek()), interval.startsAt(), interval.endsAt()))
                .toList();
        return EmployeeResponses.WeekSchedule.of(timezone(), schedules.replaceWeek(id, week));
    }

    // -----------------------------------------------------------------------
    // Time Off
    // -----------------------------------------------------------------------

    @GetMapping("/{id}/time-off")
    public EmployeeResponses.TimeOffList readTimeOff(@PathVariable UUID id) {
        return EmployeeResponses.TimeOffList.of(businesses.read().timezone(), timeOff.list(id));
    }

    @PostMapping("/{id}/time-off")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponses.TimeOff createTimeOff(
            @PathVariable UUID id, @Valid @RequestBody EmployeeRequests.CreateTimeOff request) {
        return EmployeeResponses.TimeOff.of(
                timeOff.create(id, request.startDate(), request.endDate(), request.reason()),
                businesses.read().timezone());
    }

    @DeleteMapping("/{id}/time-off/{offId}")
    public ResponseEntity<Void> deleteTimeOff(@PathVariable UUID id, @PathVariable UUID offId) {
        timeOff.delete(id, offId);
        return ResponseEntity.noContent().build();
    }

    private String timezone() {
        return businesses.read().timezone().getId();
    }
}
