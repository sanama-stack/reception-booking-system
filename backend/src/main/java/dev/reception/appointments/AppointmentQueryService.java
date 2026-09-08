package dev.reception.appointments;

import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.customers.Customer;
import dev.reception.customers.CustomerService;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeService;
import dev.reception.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading Appointments, with the names a screen needs attached.
 *
 * <p>An Appointment row holds three foreign keys and no words. Every screen that shows one needs the
 * Service's name, the Employee's name and the Customer's name, so resolving them is done here, once,
 * rather than by each response class discovering it needs another lookup.
 *
 * <p><strong>Three queries per page, not three per row.</strong> A business has few services and few
 * employees, so both are listed whole and joined in memory; customers are fetched by the ids on the
 * page. The alternative — a lazy association per row — is the N+1 that only shows itself once a
 * business has a year of history, which is exactly when it is hardest to change.
 */
@org.springframework.stereotype.Service
public class AppointmentQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AppointmentRepository appointments;
    private final AppointmentEventRepository events;
    private final AppointmentLookup lookup;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final CustomerService customers;
    private final TenantContext tenant;

    public AppointmentQueryService(
            AppointmentRepository appointments,
            AppointmentEventRepository events,
            AppointmentLookup lookup,
            ServiceCatalogService catalog,
            EmployeeService employees,
            CustomerService customers,
            TenantContext tenant) {
        this.appointments = appointments;
        this.events = events;
        this.lookup = lookup;
        this.catalog = catalog;
        this.employees = employees;
        this.customers = customers;
        this.tenant = tenant;
    }

    /** One Appointment with everything a row needs to render. */
    public record AppointmentView(Appointment appointment, String serviceName, String employeeName, Customer customer) {}

    /** The same, plus its audit trail. Only the detail screen pays for the events. */
    public record AppointmentDetailView(AppointmentView view, List<AppointmentEvent> history) {}

    /**
     * @param from inclusive, {@code to} exclusive; both null means every date
     * @param status null means every status
     * @param employeeId null means everybody
     */
    @Transactional(readOnly = true)
    public Page<AppointmentView> list(
            Instant from, Instant to, AppointmentStatus status, UUID employeeId, int page, int size) {
        // Ascending by start: an appointment list is read forwards, as a day is lived. The customer
        // history below is the opposite and says why.
        Pageable pageable = PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.ASC, "startsAt"));
        return hydrate(appointments.findByBusinessIdAndFilters(
                tenant.businessId(), from, to, status, employeeId, pageable));
    }

    /** One Customer's history, newest first — the order a profile is read in. */
    @Transactional(readOnly = true)
    public Page<AppointmentView> historyFor(UUID customerId, int page, int size) {
        // read() first, so a customer id from another tenant is a 404 rather than an empty page,
        // which would tell the caller the id exists somewhere.
        customers.read(customerId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return hydrate(
                appointments.findByBusinessIdAndCustomerIdOrderByStartsAtDesc(tenant.businessId(), customerId, pageable));
    }

    @Transactional(readOnly = true)
    public AppointmentDetailView read(UUID id) {
        Appointment appointment = lookup.require(id);
        return new AppointmentDetailView(
                hydrate(List.of(appointment)).getFirst(),
                events.findByBusinessIdAndAppointmentIdOrderByCreatedAtAsc(tenant.businessId(), id));
    }

    /** How many appointments each of these Customers has, and when they were last in. */
    @Transactional(readOnly = true)
    public Map<UUID, CustomerActivity> activityFor(List<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return appointments.findByBusinessIdAndCustomerActivity(tenant.businessId(), customerIds).stream()
                .collect(Collectors.toMap(
                        AppointmentRepository.CustomerActivityRow::getCustomerId,
                        row -> new CustomerActivity(row.getTotal(), row.getLastVisit())));
    }

    /** @param lastAppointmentAt the most recent booking in either direction, not the last completed one */
    public record CustomerActivity(long totalAppointments, Instant lastAppointmentAt) {}

    private Page<AppointmentView> hydrate(Page<Appointment> page) {
        List<AppointmentView> views = hydrate(page.getContent());
        return new PageImpl<>(views, page.getPageable(), page.getTotalElements());
    }

    private List<AppointmentView> hydrate(List<Appointment> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> serviceNames = catalog.list(null).stream()
                .collect(Collectors.toMap(Service::getId, Service::name));
        Map<UUID, String> employeeNames =
                employees.list(null).stream().collect(Collectors.toMap(Employee::getId, Employee::fullName));
        Map<UUID, Customer> customersById =
                customers.byIds(rows.stream().map(Appointment::customerId).distinct().toList());

        return rows.stream()
                .map(appointment -> new AppointmentView(
                        appointment,
                        // A Service or Employee can be deactivated but never deleted while an
                        // appointment references it, so a missing name here would mean a composite
                        // foreign key had been violated — which the database does not permit.
                        serviceNames.get(appointment.serviceId()),
                        employeeNames.get(appointment.employeeId()),
                        customersById.get(appointment.customerId())))
                .toList();
    }
}
