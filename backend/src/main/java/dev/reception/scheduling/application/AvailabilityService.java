package dev.reception.scheduling.application;

import dev.reception.business.AppointmentImpact;
import dev.reception.business.Business;
import dev.reception.business.BusinessHours;
import dev.reception.business.BusinessHoursService;
import dev.reception.business.BusinessService;
import dev.reception.business.ClosureService;
import dev.reception.catalog.AssignmentService;
import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.scheduling.domain.AvailabilityEngine;
import dev.reception.scheduling.domain.AvailabilityQuery;
import dev.reception.scheduling.domain.AvailabilityResult;
import dev.reception.scheduling.domain.BusinessSchedulingConfig;
import dev.reception.scheduling.domain.EmployeeAvailabilityInput;
import dev.reception.scheduling.domain.ServiceSpec;
import dev.reception.scheduling.domain.TimeRange;
import dev.reception.scheduling.domain.UnbookableReason;
import dev.reception.scheduling.domain.WeeklyInterval;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeSchedule;
import dev.reception.staff.EmployeeService;
import dev.reception.staff.EmployeeScheduleService;
import dev.reception.staff.EmployeeTimeOff;
import dev.reception.staff.TimeOffService;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads what the availability engine needs, validates the question, and calls it.
 *
 * <p>The split is the point of phase 05: everything that touches a repository is here, and
 * everything that decides whether a Slot is bookable is in {@link AvailabilityEngine}, which has no
 * way to reach a database even by accident. When phase 06 produces a wrong Slot, the cause is
 * unambiguous.
 *
 * <p>Reads go through the existing application services rather than around them, so another tenant's
 * Service is a {@code 404} here by the same code path as everywhere else — one tenancy rule, not a
 * second copy of it.
 */
@org.springframework.stereotype.Service
public class AvailabilityService {

    /**
     * The longest range that may be asked for in one call (docs/01-prd.md FR-5).
     *
     * <p>A ceiling rather than pagination: the cost is Employees × days × grid, and an unbounded
     * range on a five-minute grid is a slow query that phase 08 will let a stranger ask for
     * anonymously.
     */
    public static final int MAX_RANGE_DAYS = 31;

    private final BusinessService businesses;
    private final BusinessHoursService hours;
    private final ClosureService closures;
    private final ServiceCatalogService catalog;
    private final AssignmentService assignments;
    private final EmployeeService employees;
    private final EmployeeScheduleService schedules;
    private final TimeOffService timeOff;
    private final AppointmentImpact appointments;
    private final TenantContext tenant;
    private final AvailabilityEngine engine;
    private final Clock clock;

    public AvailabilityService(
            BusinessService businesses,
            BusinessHoursService hours,
            ClosureService closures,
            ServiceCatalogService catalog,
            AssignmentService assignments,
            EmployeeService employees,
            EmployeeScheduleService schedules,
            TimeOffService timeOff,
            AppointmentImpact appointments,
            TenantContext tenant,
            Clock clock) {
        this.businesses = businesses;
        this.hours = hours;
        this.closures = closures;
        this.catalog = catalog;
        this.assignments = assignments;
        this.employees = employees;
        this.schedules = schedules;
        this.timeOff = timeOff;
        this.appointments = appointments;
        this.tenant = tenant;
        // Constructed rather than injected. It is a pure function with no collaborators, and making
        // it a bean would leave a seam for something to be injected into it later.
        this.engine = new AvailabilityEngine();
        this.clock = clock;
    }

    /**
     * The Slots a caller may be offered.
     *
     * <p>{@code excludingAppointmentId} is explicit rather than defaulted, matching
     * {@link #reasonNotBookable}: the two questions must be asked about the same world, and an
     * overload that quietly meant "count everything" is how they would come to disagree.
     *
     * @param excludingAppointmentId the Appointment being moved, or {@code null} when the caller is
     *     booking a new one.
     *     <p><strong>A grid drawn without it refuses the move it exists to offer.</strong> A
     *     rescheduling customer is looking at times they might move to, and the time they currently
     *     hold — plus its Buffers, which reach further — is blocked by their own booking. Moving a
     *     10:00 appointment to 10:15 would show no Slot at 10:15, because the appointment overlaps
     *     itself. The database has no such problem: an exclusion constraint never compares a row
     *     with itself, so without this the offer would be stricter than the rule it is previewing.
     *     <p>Callers on the public surface must not take this from the caller. See
     *     {@code PublicAppointmentController.manageAvailability}, where it is the appointment the
     *     Manage Link authorises and nothing else
     */
    @Transactional(readOnly = true)
    public Availability find(
            UUID serviceId, LocalDate from, LocalDate to, UUID employeeId, UUID excludingAppointmentId) {
        validateRange(from, to);

        Business business = businesses.read();
        ZoneId zone = business.timezone();
        Service service = bookableService(serviceId);
        ServiceSpec spec = specFor(service);

        AvailabilityResult result = engine.findSlots(
                new AvailabilityQuery(spec, from, to, employeeId),
                configFor(business),
                candidatesFor(service.getId(), employeeId, spec, from, to, zone, excludingAppointmentId),
                clock);
        return new Availability(zone, result);
    }

    /**
     * Whether one specific start is bookable by one specific Employee — the check phase 06 runs
     * before it writes.
     *
     * <p>It lives here rather than in the booking service because every input it needs is already
     * loaded here, by the code the Slot list itself came from. Two copies of "what makes a Slot
     * bookable" is the arrangement in which a booking is refused seconds after being offered, and
     * the refusal reads as a race that is not one.
     *
     * <p>The eligibility refusals are the same ones {@link #find} produces, from the same code path,
     * so a Service switched off between seeing a Slot and booking it says {@code SERVICE_INACTIVE}
     * rather than a bare "unavailable".
     *
     * <p><strong>This is not the last line of defence.</strong> The exclusion constraint is
     * (ADR-0002). This is what turns a lost race into a sentence a person can act on.
     *
     * @param excludingAppointmentId the Appointment being moved, or {@code null} when booking a new
     *     one. See {@code AppointmentImpact.blockedRangesFor}
     * @return empty when the Slot is bookable
     */
    @Transactional(readOnly = true)
    public Optional<UnbookableReason> reasonNotBookable(
            UUID serviceId, UUID employeeId, Instant startsAt, UUID excludingAppointmentId) {
        Business business = businesses.read();
        ZoneId zone = business.timezone();
        Service service = bookableService(serviceId);
        ServiceSpec spec = specFor(service);

        // One date, because a start time is on exactly one of them in the Business's zone — which is
        // the zone that decides, not the caller's and not the server's.
        LocalDate date = startsAt.atZone(zone).toLocalDate();
        List<EmployeeAvailabilityInput> candidates =
                candidatesFor(service.getId(), employeeId, spec, date, date, zone, excludingAppointmentId);
        if (candidates.isEmpty()) {
            // Unreachable when employeeId is given, because candidatesFor has already refused an
            // inactive or unassigned Employee by name. Kept so a future caller passing null gets a
            // refusal rather than an IndexOutOfBoundsException.
            throw new ApiException(
                    ErrorCode.EMPLOYEE_CANNOT_PERFORM_SERVICE, "Nobody can perform this service at the moment.");
        }
        return engine.isSlotBookable(startsAt, spec, configFor(business), candidates.getFirst(), clock);
    }

    /** The engine's answer together with the zone every instant in it must be read in. */
    public record Availability(ZoneId timezone, AvailabilityResult result) {}

    private Service bookableService(UUID serviceId) {
        Service service = catalog.read(serviceId);
        if (!service.active()) {
            throw new ApiException(
                    ErrorCode.SERVICE_INACTIVE,
                    "%s is not currently offered, so it has no availability.".formatted(service.name()));
        }
        return service;
    }

    private static ServiceSpec specFor(Service service) {
        return new ServiceSpec(
                service.getId(),
                service.durationMinutes(),
                service.bufferBeforeMinutes(),
                service.bufferAfterMinutes());
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The end of the range comes before its start.",
                    List.of(new FieldError("to", "Choose a date on or after the start date.")));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Availability can be asked for at most %d days at a time.".formatted(MAX_RANGE_DAYS),
                    List.of(new FieldError("to", "Ask for at most %d days at a time.".formatted(MAX_RANGE_DAYS))));
        }
    }

    /**
     * The Employees who could perform this Service, with their schedules, absences and bookings.
     *
     * <p>Eligibility is settled here rather than in the engine because every part of it is a
     * database fact. What the engine receives is already the answer to "who can do this at all",
     * which is why an empty list there means {@code NO_ELIGIBLE_EMPLOYEE} and nothing else.
     */
    private List<EmployeeAvailabilityInput> candidatesFor(
            UUID serviceId,
            UUID employeeId,
            ServiceSpec spec,
            LocalDate from,
            LocalDate to,
            ZoneId zone,
            UUID excludingAppointmentId) {

        Set<UUID> assigned = Set.copyOf(assignments.employeesFor(serviceId));

        if (employeeId != null) {
            // read() is what turns another tenant's id into a 404. The two refusals below are about
            // an Employee who genuinely exists here and still cannot take this booking — a mistaken
            // question, which deserves an answer rather than an empty list.
            Employee requested = employees.read(employeeId);
            if (!requested.active()) {
                throw new ApiException(
                        ErrorCode.EMPLOYEE_INACTIVE,
                        "%s is not currently taking appointments.".formatted(requested.fullName()));
            }
            if (!assigned.contains(employeeId)) {
                throw new ApiException(
                        ErrorCode.EMPLOYEE_CANNOT_PERFORM_SERVICE,
                        "%s is not assigned to this service.".formatted(requested.fullName()));
            }
        }

        List<Employee> eligible = employees.list(true).stream()
                .filter(employee -> assigned.contains(employee.getId()))
                .filter(employee -> employeeId == null || employee.getId().equals(employeeId))
                .sorted(Comparator.comparing(Employee::fullName))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<AppointmentImpact.BlockedRange>> booked = appointments.blockedRangesFor(
                tenant.businessId(),
                eligible.stream().map(Employee::getId).toList(),
                from.atStartOfDay(zone).toInstant().minus(Duration.ofMinutes(spec.bufferBeforeMinutes())),
                to.plusDays(1)
                        .atStartOfDay(zone)
                        .toInstant()
                        .plus(spec.duration())
                        .plus(Duration.ofMinutes(spec.bufferAfterMinutes())),
                excludingAppointmentId);

        return eligible.stream()
                .map(employee -> new EmployeeAvailabilityInput(
                        employee.getId(),
                        employee.fullName(),
                        weeklyIntervals(schedules.read(employee.getId())),
                        absenceRanges(timeOff.list(employee.getId())),
                        blockedRanges(booked.getOrDefault(employee.getId(), List.of()))))
                .toList();
    }

    private BusinessSchedulingConfig configFor(Business business) {
        return new BusinessSchedulingConfig(
                business.timezone(),
                hours.read().stream().map(AvailabilityService::weeklyInterval).toList(),
                business.slotIntervalMinutes(),
                business.minLeadTimeMinutes(),
                business.maxAdvanceDays(),
                closures.list().stream()
                        .map(closure -> new TimeRange(closure.startsAt(), closure.endsAt()))
                        .toList());
    }

    private static WeeklyInterval weeklyInterval(BusinessHours row) {
        return new WeeklyInterval(row.dayOfWeek(), row.opensAt(), row.closesAt());
    }

    private static List<WeeklyInterval> weeklyIntervals(List<EmployeeSchedule> schedule) {
        return schedule.stream()
                .map(row -> new WeeklyInterval(row.dayOfWeek(), row.startsAt(), row.endsAt()))
                .toList();
    }

    private static List<TimeRange> absenceRanges(List<EmployeeTimeOff> absences) {
        return absences.stream()
                .map(absence -> new TimeRange(absence.startsAt(), absence.endsAt()))
                .toList();
    }

    private static List<TimeRange> blockedRanges(List<AppointmentImpact.BlockedRange> booked) {
        return booked.stream()
                .map(range -> new TimeRange(range.from(), range.to()))
                .toList();
    }
}
