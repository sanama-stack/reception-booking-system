package dev.reception.staff;

import dev.reception.business.AppointmentImpact;
import dev.reception.business.BusinessService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.common.ids.IdGenerator;
import dev.reception.common.phone.PhoneField;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people a Business can book work onto.
 *
 * <p>An Employee is never deleted through this service. Appointments name the Employee who performed
 * them, and the record of who did what is the thing a business is least able to lose
 * (ADR-0006, docs/03-data-model.md §1) — so {@code active = false} is the only way to retire
 * someone, and it stops new bookings without touching the ones already made.
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final BusinessService businesses;
    private final AppointmentImpact appointments;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public EmployeeService(
            EmployeeRepository employees,
            BusinessService businesses,
            AppointmentImpact appointments,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.employees = employees;
        this.businesses = businesses;
        this.appointments = appointments;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * An Employee whose bookability just changed, and how many upcoming Appointments it affects.
     *
     * <p>Reported, never acted on — the same rule Business Closures and Services follow. Nothing is
     * auto-cancelled (docs/04-api-overview.md §5).
     */
    public record BookabilityChange(Employee employee, long affectedFutureAppointments) {}

    /** @param active {@code null} lists everyone, which is what the management screen wants. */
    @Transactional(readOnly = true)
    public List<Employee> list(Boolean active) {
        UUID businessId = tenant.businessId();
        return active == null
                ? employees.findByBusinessIdOrderByFullNameAsc(businessId)
                : employees.findByBusinessIdAndActiveOrderByFullNameAsc(businessId, active);
    }

    @Transactional(readOnly = true)
    public Employee read(UUID id) {
        return employees.findByBusinessIdAndId(tenant.businessId(), id)
                .orElseThrow(() -> ApiException.notFound("No such employee."));
    }

    @Transactional
    public Employee create(String fullName, String email, String phone, String jobTitle) {
        Employee employee = new Employee(
                ids.newId(),
                tenant.businessId(),
                fullName.trim(),
                blankToNull(email),
                normalisedPhone("phone", phone),
                blankToNull(jobTitle),
                clock.instant());
        return employees.save(employee);
    }

    /** Absent leaves, blank clears, a value sets — see {@link Employee#apply}. */
    @Transactional
    public Employee patch(UUID id, String fullName, String email, String phone, String jobTitle) {
        Employee employee = read(id);
        List<FieldError> errors = new ArrayList<>();
        if (fullName != null && fullName.isBlank()) {
            // The DTO rejects this too. Restated because the rule belongs to the domain rather than
            // to one request shape: an Employee with no name is not something this service creates.
            errors.add(new FieldError("fullName", "Enter a name."));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", errors);
        }

        // A blank phone means "clear it" and must reach apply() as a blank; only a non-blank value
        // is a number to normalise. Passing "" through the parser would read a deliberate clear as
        // an unparseable number.
        String phoneToStore = phone != null && phone.isBlank() ? phone : normalisedPhone("phone", phone);

        employee.apply(trim(fullName), trim(email), phoneToStore, trim(jobTitle), clock.instant());
        return employees.save(employee);
    }

    @Transactional
    public BookabilityChange setActive(UUID id, boolean active) {
        Employee employee = read(id);
        employee.setActive(active, clock.instant());
        employees.save(employee);

        long affected = active ? 0 : appointments.countFutureForEmployee(tenant.businessId(), id);
        return new BookabilityChange(employee, affected);
    }

    /**
     * E.164, resolved against the Business's country.
     *
     * <p>Unparseable input is rejected at entry rather than stored as typed (docs/06-security.md
     * §9). Storing it as typed is the option that looks kinder and is not: a Customer is identified
     * by their normalised number, so a number that was never normalised is one that will quietly
     * fail to match itself later.
     *
     * <p>The rule and its wording live in {@link PhoneField}, shared with {@code CustomerService} —
     * the two must agree, and agreeing by having one copy is cheaper than agreeing by review.
     */
    private String normalisedPhone(String field, String raw) {
        return PhoneField.normalise(field, raw, businesses.read().country());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
