package dev.reception.publicapi;

import dev.reception.appointments.Actor;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentSource;
import dev.reception.appointments.BookingService;
import dev.reception.business.BusinessService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.customers.CustomerFieldNames;
import dev.reception.customers.CustomerService;
import dev.reception.staff.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /public/businesses/{slug}/appointments} — a stranger books, with no account.
 *
 * <p><strong>The same {@link BookingService} the dashboard calls.</strong> The exclusion constraint,
 * the availability re-check, the price snapshot, the Confirmation Code, the audit event and the
 * confirmation email all come with it — this class adds a source and an actor and nothing else. A
 * public booking path that wrote its own appointment row is the arrangement in which the public
 * flow and the dashboard eventually disagree about what a booking is.
 *
 * <p>The confirmation email is free here, and that is phase 07's design showing its work: the
 * outbox rows are enqueued inside the booking transaction by {@code BookingService} itself, so
 * nothing in {@code notifications} needed to learn that this endpoint exists.
 *
 * <p>{@code CLASSIC} is set here and could not be sent by the caller. A body that could name its own
 * source would let anyone claim to be the Receptionist, or claim a booking came from the front desk.
 */
@RestController
@RequestMapping("/public/businesses/{slug}/appointments")
public class PublicBookingController {

    private final BookingService booking;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final BusinessService businesses;
    private final CustomerService customers;

    public PublicBookingController(
            BookingService booking,
            ServiceCatalogService catalog,
            EmployeeService employees,
            BusinessService businesses,
            CustomerService customers) {
        this.booking = booking;
        this.catalog = catalog;
        this.employees = employees;
        this.businesses = businesses;
        this.customers = customers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PublicResponses.BookedAppointment create(@Valid @RequestBody PublicRequests.CreateAppointment request) {
        Appointment booked = booking.book(
                new BookingService.BookingRequest(
                        request.serviceId(),
                        request.employeeId(),
                        request.startsAt().toInstant(),
                        request.customer().fullName(),
                        request.customer().phone(),
                        request.customer().email(),
                        request.note(),
                        // Dotted, because this request nests them. A failure reported as "phone"
                        // would match no input on the page and the message would vanish.
                        CustomerFieldNames.PUBLIC_BOOKING),
                AppointmentSource.CLASSIC,
                // Not Actor.customer(): that actor is bound by the Cancellation Window, which has
                // nothing to say about making a booking, and recording a Customer as the actor on a
                // creation event is simply what happened.
                Actor.customer());

        // The resolved Customer, not the email that was typed. A returning phone number keeps the
        // address already on file (CustomerService.findOrCreate), so what the caller sent is not
        // evidence that anything was sent — and when the stored record has no address, nothing was.
        // Asking the same predicate NotificationEnqueuer asked is what keeps the screen and the
        // outbox from disagreeing (ADR-0007).
        boolean confirmationSent = customers.read(booked.customerId()).hasEmail();

        // Re-read rather than held from before the write: all three are already loaded inside the
        // booking transaction, and reading them again here is indexed lookups against rows this
        // request just proved exist.
        return PublicResponses.BookedAppointment.of(
                booked,
                catalog.read(booked.serviceId()),
                employees.read(booked.employeeId()),
                businesses.read().timezone(),
                confirmationSent);
    }
}
