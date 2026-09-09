package dev.reception.appointments;

import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.common.ids.IdGenerator;
import dev.reception.customers.Customer;
import dev.reception.customers.CustomerFieldNames;
import dev.reception.customers.CustomerService;
import dev.reception.notifications.NotificationEnqueuer;
import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.scheduling.domain.ServiceSpec;
import dev.reception.scheduling.domain.TimeRange;
import dev.reception.scheduling.domain.UnbookableReason;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeService;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write path: one transaction from a requested time to a booked Appointment.
 *
 * <p>Everything in {@link #book} happens or none of it does — validation, the availability re-check,
 * finding or creating the Customer, the price snapshot, the Confirmation Code, the row, the audit
 * event and, since phase 07, the notification rows (docs/02-product-architecture.md §5). Those last
 * ones are the reason the outbox is a table rather than a queue (ADR-0005).
 *
 * <p><strong>The re-check is not what makes this correct.</strong> A Slot list is seconds stale by
 * the time somebody clicks it, and two requests can both pass the re-check. What settles the race is
 * {@code appointments_no_overlap}, which physically cannot admit both rows (ADR-0002). The re-check
 * exists so that the ordinary case — a Service switched off, an Employee whose schedule changed —
 * produces a sentence the caller can act on instead of a constraint violation.
 *
 * <p>{@code source} and {@link Actor} are parameters rather than something this class infers. The
 * dashboard, the public Classic Flow (phase 08) and the Receptionist's Tools (phase 09) all land
 * here, and a service that guessed which one it was serving would eventually guess wrong.
 */
@org.springframework.stereotype.Service
public class BookingService {

    private final AppointmentRepository appointments;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final CustomerService customers;
    private final AvailabilityService availability;
    private final ConfirmationCodeGenerator codes;
    private final AppointmentEventRecorder events;
    private final NotificationEnqueuer notifications;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public BookingService(
            AppointmentRepository appointments,
            ServiceCatalogService catalog,
            EmployeeService employees,
            CustomerService customers,
            AvailabilityService availability,
            ConfirmationCodeGenerator codes,
            AppointmentEventRecorder events,
            NotificationEnqueuer notifications,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.appointments = appointments;
        this.catalog = catalog;
        this.employees = employees;
        this.customers = customers;
        this.availability = availability;
        this.codes = codes;
        this.events = events;
        this.notifications = notifications;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * What a caller asks for. A record because the alternative is a nine-argument method.
     *
     * <p>{@code customerFields} is not data about the booking — it is what the caller's own request
     * body called the three customer values, so a validation failure comes back under a name the
     * client sent and can therefore look up. The web layer is the only thing that knows this, and it
     * is the layer that builds this record. See {@link CustomerFieldNames}.
     */
    public record BookingRequest(
            UUID serviceId,
            UUID employeeId,
            Instant startsAt,
            String customerName,
            String customerPhone,
            String customerEmail,
            String customerNote,
            CustomerFieldNames customerFields) {}

    @Transactional
    public Appointment book(BookingRequest request, AppointmentSource source, Actor actor) {
        UUID businessId = tenant.businessId();

        // read() is what turns another tenant's id into a 404, and it runs before anything is
        // written — the Customer included, which is why a failed booking leaves no row behind.
        Service service = catalog.read(request.serviceId());
        Employee employee = employees.read(request.employeeId());

        Optional<UnbookableReason> refusal = availability.reasonNotBookable(
                request.serviceId(), request.employeeId(), request.startsAt(), null);
        if (refusal.isPresent()) {
            throw BookingRefusal.of(refusal.get(), service.name(), employee.fullName());
        }

        Customer customer = customers.findOrCreate(
                request.customerPhone(), request.customerName(), request.customerEmail(), request.customerFields());

        ServiceSpec spec = specFor(service);
        TimeRange appointment = TimeRange.of(request.startsAt(), spec.duration());
        TimeRange occupancy = spec.occupancyFor(appointment);
        Instant now = clock.instant();

        Appointment booked = new Appointment(
                ids.newId(),
                businessId,
                employee.getId(),
                service.getId(),
                customer.getId(),
                appointment.start(),
                appointment.end(),
                occupancy.start(),
                occupancy.end(),
                // The snapshot. Read from the Service now and never consulted again, so a later
                // price change cannot rewrite what this appointment cost.
                service.priceAmount(),
                service.currency(),
                codes.generateUnique(businessId, appointments::existsByBusinessIdAndConfirmationCode),
                source,
                blankToNull(request.customerNote()),
                now);

        // saveAndFlush, not save. The INSERT — and therefore the exclusion constraint — must run
        // while this method is still on the stack, or a lost race would surface at commit time as a
        // 500 from a place that has no idea what it was doing. GlobalExceptionHandler turns the
        // violation into 409 SLOT_UNAVAILABLE by constraint name.
        Appointment saved = appointments.saveAndFlush(booked);
        events.created(saved, actor);
        // In this transaction, which is the whole of ADR-0005: a booking that rolls back takes its
        // confirmation email with it, and one that commits cannot lose it in the gap before a broker
        // was told. Enqueued after the flush so a lost race never renders an email for a booking the
        // exclusion constraint is about to refuse.
        notifications.bookingConfirmed(saved);
        return saved;
    }

    static ServiceSpec specFor(Service service) {
        return new ServiceSpec(
                service.getId(),
                service.durationMinutes(),
                service.bufferBeforeMinutes(),
                service.bufferAfterMinutes());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
