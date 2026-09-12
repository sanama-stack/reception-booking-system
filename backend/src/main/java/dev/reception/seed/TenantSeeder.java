package dev.reception.seed;

import dev.reception.appointments.Actor;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentEventRecorder;
import dev.reception.appointments.AppointmentRepository;
import dev.reception.appointments.AppointmentSource;
import dev.reception.appointments.AppointmentStatus;
import dev.reception.appointments.AppointmentStatusService;
import dev.reception.appointments.CancellationService;
import dev.reception.appointments.ConfirmationCodeGenerator;
import dev.reception.auth.AuthService;
import dev.reception.auth.RefreshTokenService.RequestFingerprint;
import dev.reception.business.BusinessHoursService;
import dev.reception.business.BusinessPatch;
import dev.reception.business.BusinessService;
import dev.reception.business.ClosureService;
import dev.reception.business.FaqService;
import dev.reception.catalog.AssignmentService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.common.ids.IdGenerator;
import dev.reception.customers.Customer;
import dev.reception.customers.CustomerFieldNames;
import dev.reception.customers.CustomerService;
import dev.reception.scheduling.domain.ServiceSpec;
import dev.reception.scheduling.domain.TimeRange;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeScheduleService;
import dev.reception.staff.EmployeeService;
import dev.reception.staff.TimeOffService;
import dev.reception.tenancy.TenantAdoption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns one {@link Blueprint.Tenant} into rows.
 *
 * <p><strong>Configuration goes through the application services, not the repositories.</strong>
 * The hours, the catalog, the staff, the schedules, the assignments, the closures and the FAQs are
 * all written by the same classes the dashboard calls, so every validation rule an owner would meet
 * applies to the seed as well. A fixture that wrote around them could describe a business the
 * application would refuse to accept — and it would do so silently, which is the worst way for a
 * demo to be wrong.
 *
 * <p><strong>Appointments are the one exception, and the reason is that the engine cannot book the
 * past.</strong> Half of this data is history, and history is what makes Analytics show anything at
 * all. So an Appointment is constructed and saved directly — but it is saved {@code CONFIRMED},
 * which means the {@code EXCLUDE} constraint still judges every one of them, the composite foreign
 * keys still refuse a row that mixes tenants, and the {@code V8}/{@code V9} ceilings still apply.
 * The Buffer arithmetic is {@link ServiceSpec#occupancyFor}'s own rather than a copy of it. What is
 * skipped is availability and the confirmation email: the first is impossible for a past date, and
 * the second would fill Mailpit with mail nobody sent (see {@code SeedRunner}).
 *
 * <p>Everything after the insert is the real path again: {@link AppointmentStatusService} closes an
 * Appointment out and {@link CancellationService} cancels one, so the seed cannot produce a status
 * the state machine forbids.
 */
@Component
class TenantSeeder {

    /** Registration wants to know where the request came from. This one came from a shell. */
    private static final RequestFingerprint FINGERPRINT = new RequestFingerprint("reception-seed", "127.0.0.1");

    /** A plausible interval between booking and appointment, for rows whose history is invented. */
    private static final Duration BOOKED_IN_ADVANCE = Duration.ofDays(3);

    private final TenantAdoption tenants;
    private final AuthService auth;
    private final BusinessService businesses;
    private final BusinessHoursService hours;
    private final ClosureService closures;
    private final FaqService faqs;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final EmployeeScheduleService schedules;
    private final AssignmentService assignments;
    private final TimeOffService timeOff;
    private final CustomerService customers;
    private final AppointmentRepository appointments;
    private final AppointmentEventRecorder events;
    private final AppointmentStatusService statuses;
    private final CancellationService cancellations;
    private final ConfirmationCodeGenerator codes;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    TenantSeeder(
            TenantAdoption tenants,
            AuthService auth,
            BusinessService businesses,
            BusinessHoursService hours,
            ClosureService closures,
            FaqService faqs,
            ServiceCatalogService catalog,
            EmployeeService employees,
            EmployeeScheduleService schedules,
            AssignmentService assignments,
            TimeOffService timeOff,
            CustomerService customers,
            AppointmentRepository appointments,
            AppointmentEventRecorder events,
            AppointmentStatusService statuses,
            CancellationService cancellations,
            ConfirmationCodeGenerator codes,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate transactions) {
        this.tenants = tenants;
        this.auth = auth;
        this.businesses = businesses;
        this.hours = hours;
        this.closures = closures;
        this.faqs = faqs;
        this.catalog = catalog;
        this.employees = employees;
        this.schedules = schedules;
        this.assignments = assignments;
        this.timeOff = timeOff;
        this.customers = customers;
        this.appointments = appointments;
        this.events = events;
        this.statuses = statuses;
        this.cancellations = cancellations;
        this.codes = codes;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * Writes the whole tenant.
     *
     * <p>Deliberately not one transaction. Registration, the profile patch and each write below own
     * their own, exactly as they do when a person performs them — and a seed that half-succeeded is
     * recoverable by running {@code make seed} again, which resets before it writes.
     */
    Seeded seed(Blueprint.Tenant tenant) {
        Blueprint.Profile profile = tenant.profile();

        AuthService.Session session = auth.register(
                tenant.owner().email(), tenant.owner().password(), tenant.owner().fullName(), profile.name(),
                FINGERPRINT);
        UUID businessId = session.business().getId();
        UUID ownerId = session.user().getId();

        // Every call below this line is tenant-scoped and reads the business id from here. Adopting
        // it explicitly is the same gesture TenantContextFilter performs for a request.
        tenants.adopt(businessId);
        businesses.patch(patchFor(profile));

        hours.replaceWeek(intervals(tenant.hours()));

        Map<String, dev.reception.catalog.Service> services = new LinkedHashMap<>();
        for (Blueprint.Service service : tenant.services()) {
            services.put(
                    service.name(),
                    catalog.create(
                            service.name(),
                            service.description(),
                            service.durationMinutes(),
                            service.bufferBeforeMinutes(),
                            service.bufferAfterMinutes(),
                            service.price()));
        }

        ZoneId zone = profile.timezone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);

        Map<String, Employee> staff = new LinkedHashMap<>();
        for (Blueprint.Employee employee : tenant.employees()) {
            Employee created =
                    employees.create(employee.fullName(), employee.email(), employee.phone(), employee.jobTitle());
            staff.put(employee.fullName(), created);

            schedules.replaceWeek(created.getId(), employee.schedule().stream()
                    .map(interval -> new EmployeeScheduleService.Interval(interval.day(), interval.from(), interval.to()))
                    .toList());
            assignments.replaceServicesFor(
                    created.getId(),
                    employee.services().stream()
                            .map(name -> requireService(services, name).getId())
                            .toList());
            for (Blueprint.TimeOff away : employee.timeOff()) {
                timeOff.create(
                        created.getId(),
                        Weeks.date(today, away.weekOffset(), away.from()),
                        Weeks.date(today, away.weekOffset(), away.to()),
                        away.reason());
            }
        }

        for (Blueprint.Closure closure : tenant.closures()) {
            LocalDate date = nextOccurrence(closure.day(), today);
            closures.create(date, date, closure.reason());
        }

        int sortOrder = 0;
        for (Blueprint.Faq faq : tenant.faqs()) {
            faqs.create(faq.question(), faq.answer(), sortOrder++);
        }

        Map<String, Customer> people = new HashMap<>();
        for (Blueprint.Customer customer : tenant.customers()) {
            people.put(
                    customer.fullName(),
                    customers.findOrCreate(
                            customer.phone(),
                            customer.fullName(),
                            customer.email(),
                            CustomerFieldNames.DASHBOARD_BOOKING));
        }

        List<Appointment> written = new ArrayList<>(tenant.appointments().size());
        for (Blueprint.Appointment appointment : tenant.appointments()) {
            written.add(write(appointment, businessId, ownerId, zone, today, services, staff, people));
        }

        return new Seeded(businessId, ownerId, profile, staff, services, written);
    }

    private Appointment write(
            Blueprint.Appointment blueprint,
            UUID businessId,
            UUID ownerId,
            ZoneId zone,
            LocalDate today,
            Map<String, dev.reception.catalog.Service> services,
            Map<String, Employee> staff,
            Map<String, Customer> people) {
        dev.reception.catalog.Service service = requireService(services, blueprint.service());
        Employee employee = require(staff, blueprint.employee(), "employee");
        Customer customer = require(people, blueprint.customer(), "customer");

        Instant startsAt = Weeks.instant(today, blueprint.when(), zone);
        ServiceSpec spec = new ServiceSpec(
                service.getId(),
                service.durationMinutes(),
                service.bufferBeforeMinutes(),
                service.bufferAfterMinutes());
        TimeRange booked = TimeRange.of(startsAt, spec.duration());
        TimeRange occupancy = spec.occupancyFor(booked);

        Instant now = clock.instant();
        Instant createdAt = earlier(now, startsAt.minus(BOOKED_IN_ADVANCE));
        Actor actor = actorFor(blueprint.source(), ownerId);

        Appointment appointment = new Appointment(
                ids.newId(),
                businessId,
                employee.getId(),
                service.getId(),
                customer.getId(),
                booked.start(),
                booked.end(),
                occupancy.start(),
                occupancy.end(),
                service.priceAmount(),
                service.currency(),
                codes.generateUnique(businessId, appointments::existsByBusinessIdAndConfirmationCode),
                blueprint.source(),
                blueprint.note(),
                createdAt);

        // One transaction for the row and its audit event, because AppointmentEventRecorder
        // declares MANDATORY propagation: an event that could be written without the change it
        // describes would be a history that disagrees with the table it is a history of.
        //
        // saveAndFlush inside it for the same reason BookingService uses it — the INSERT, and
        // therefore the exclusion constraint, must run here, where a collision in the fixture is
        // reported against the appointment that caused it rather than at commit against nothing in
        // particular.
        Appointment saved = transactions.execute(status -> {
            Appointment written = appointments.saveAndFlush(appointment);
            events.created(written, actor);
            return written;
        });

        return switch (blueprint.outcome()) {
            case BOOKED -> saved;
            case COMPLETED -> closeOut(saved, AppointmentStatus.COMPLETED, actor, now);
            case NO_SHOW -> closeOut(saved, AppointmentStatus.NO_SHOW, actor, now);
            case CANCELLED_BY_CUSTOMER -> cancellations.cancel(
                    saved.getId(), Actor.customer(), "Something came up — I will rebook.");
            case CANCELLED_BY_BUSINESS -> cancellations.cancel(
                    saved.getId(), Actor.user(ownerId), "We had to close that slot.");
        };
    }

    /**
     * Marks an Appointment completed or missed, but only once it has ended.
     *
     * <p>An Appointment the blueprint expects to be history stays {@code CONFIRMED} while it is
     * still ahead of the clock. Which of this week's rows that applies to depends on the day the
     * seed runs, and that is the intended behaviour rather than a rounding error: on a Tuesday
     * fewer of this week's appointments are behind us than on a Friday.
     */
    private Appointment closeOut(Appointment appointment, AppointmentStatus status, Actor actor, Instant now) {
        if (!appointment.endsAt().isBefore(now)) {
            return appointment;
        }
        return statuses.moveTo(appointment.getId(), status, actor);
    }

    /** Who performed the booking, so the audit trail reads the way the source says it happened. */
    private static Actor actorFor(AppointmentSource source, UUID ownerId) {
        return switch (source) {
            case DASHBOARD -> Actor.user(ownerId);
            case CLASSIC -> Actor.customer();
            case AI -> Actor.ai();
        };
    }

    private BusinessPatch patchFor(Blueprint.Profile profile) {
        return new BusinessPatch(
                profile.name(),
                profile.slug(),
                profile.timezone().getId(),
                profile.currency(),
                profile.description(),
                profile.addressLine(),
                profile.city(),
                profile.country(),
                profile.phone(),
                profile.email(),
                profile.website(),
                profile.slotIntervalMinutes(),
                profile.minLeadTimeMinutes(),
                profile.maxAdvanceDays(),
                profile.cancellationWindowHours(),
                profile.cancellationPolicy(),
                true,
                profile.aiAdditionalInfo(),
                null);
    }

    private static List<BusinessHoursService.Interval> intervals(List<Blueprint.Interval> week) {
        return week.stream()
                .map(interval -> new BusinessHoursService.Interval(interval.day(), interval.from(), interval.to()))
                .toList();
    }

    /** The next time this calendar day comes round, today included. */
    private static LocalDate nextOccurrence(MonthDay day, LocalDate today) {
        LocalDate thisYear = day.atYear(today.getYear());
        return thisYear.isBefore(today) ? day.atYear(today.getYear() + 1) : thisYear;
    }

    private static Instant earlier(Instant one, Instant other) {
        return one.isBefore(other) ? one : other;
    }

    private static dev.reception.catalog.Service requireService(
            Map<String, dev.reception.catalog.Service> services, String name) {
        return require(services, name, "service");
    }

    private static <T> T require(Map<String, T> known, String name, String what) {
        T found = known.get(name);
        if (found == null) {
            throw new IllegalStateException(
                    "The blueprint names a %s that it never defines: %s".formatted(what, name));
        }
        return found;
    }

    /** What one seeded tenant turned into, for the summary and for the checks. */
    record Seeded(
            UUID businessId,
            UUID ownerId,
            Blueprint.Profile profile,
            Map<String, Employee> employees,
            Map<String, dev.reception.catalog.Service> services,
            List<Appointment> appointments) {}
}
