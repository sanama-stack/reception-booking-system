package dev.reception.appointments.web;

import dev.reception.appointments.Actor;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentQueryService;
import dev.reception.appointments.AppointmentSource;
import dev.reception.appointments.AppointmentStatus;
import dev.reception.appointments.AppointmentStatusService;
import dev.reception.appointments.BookingService;
import dev.reception.appointments.CancellationService;
import dev.reception.appointments.CurrentActor;
import dev.reception.appointments.RescheduleService;
import dev.reception.business.BusinessService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Appointment surface (docs/04-api-overview.md §5).
 *
 * <p>No method here names a business. Every {@code {id}} is an Appointment looked up by
 * {@code (businessId, id)} inside the application layer, which is why another tenant's id comes back
 * as {@code 404} rather than as their customer's phone number.
 *
 * <p>Everything that decides anything happens below this class. What is left here is the two
 * conversions that genuinely belong at the edge: calendar dates into instants in the Business's
 * zone, and the signed-in user into an {@link Actor}.
 *
 * <p>{@code OWNER} and {@code ADMIN} only, declared once for the class.
 */
@RestController
@RequestMapping("/appointments")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class AppointmentController {

    private final BookingService booking;
    private final CancellationService cancellation;
    private final RescheduleService reschedule;
    private final AppointmentStatusService statuses;
    private final AppointmentQueryService appointments;
    private final BusinessService businesses;
    private final CurrentActor currentActor;

    public AppointmentController(
            BookingService booking,
            CancellationService cancellation,
            RescheduleService reschedule,
            AppointmentStatusService statuses,
            AppointmentQueryService appointments,
            BusinessService businesses,
            CurrentActor currentActor) {
        this.booking = booking;
        this.cancellation = cancellation;
        this.reschedule = reschedule;
        this.statuses = statuses;
        this.appointments = appointments;
        this.businesses = businesses;
        this.currentActor = currentActor;
    }

    /**
     * @param from first calendar date, in the Business timezone; {@code to} is <strong>inclusive</strong>,
     *     matching {@code GET /availability}. An owner filtering "the 5th to the 5th" means that day,
     *     not nothing
     */
    @GetMapping
    public AppointmentResponses.AppointmentPage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ZoneId zone = timezone();
        return AppointmentResponses.AppointmentPage.of(
                appointments.list(
                        from == null ? null : from.atStartOfDay(zone).toInstant(),
                        to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                        status,
                        employeeId,
                        page,
                        size),
                zone);
    }

    @GetMapping("/{id}")
    public AppointmentResponses.AppointmentWithHistory read(@PathVariable UUID id) {
        return AppointmentResponses.AppointmentWithHistory.of(appointments.read(id), timezone());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponses.BookedAppointment create(
            @Valid @RequestBody AppointmentRequests.CreateAppointment request) {
        Appointment booked = booking.book(
                new BookingService.BookingRequest(
                        request.serviceId(),
                        request.employeeId(),
                        request.startsAt().toInstant(),
                        request.customerName(),
                        request.customerPhone(),
                        request.customerEmail(),
                        request.customerNote()),
                // Set here, never taken from the body. Phase 08 and phase 09 have their own
                // entry points and set their own.
                AppointmentSource.DASHBOARD,
                currentActor.resolve());
        return AppointmentResponses.BookedAppointment.of(appointments.read(booked.getId()), timezone());
    }

    /**
     * Cancels. The actor is a dashboard user, so the Cancellation Window does not apply — a business
     * is never bound by its own customer-facing deadline (CONTEXT.md).
     *
     * <p>Idempotent: cancelling an already-cancelled appointment returns {@code 200} and changes
     * nothing.
     */
    @PostMapping("/{id}/cancel")
    public AppointmentResponses.AppointmentWithHistory cancel(
            @PathVariable UUID id, @Valid @RequestBody(required = false) AppointmentRequests.CancelAppointment request) {
        cancellation.cancel(id, currentActor.resolve(), request == null ? null : request.reason());
        return AppointmentResponses.AppointmentWithHistory.of(appointments.read(id), timezone());
    }

    @PostMapping("/{id}/reschedule")
    public AppointmentResponses.AppointmentWithHistory reschedule(
            @PathVariable UUID id, @Valid @RequestBody AppointmentRequests.RescheduleAppointment request) {
        reschedule.reschedule(id, request.startsAt().toInstant(), request.employeeId(), currentActor.resolve());
        return AppointmentResponses.AppointmentWithHistory.of(appointments.read(id), timezone());
    }

    /** {@code COMPLETED} or {@code NO_SHOW}. Cancelling has its own endpoint and its own rules. */
    @PostMapping("/{id}/status")
    public AppointmentResponses.AppointmentWithHistory changeStatus(
            @PathVariable UUID id, @Valid @RequestBody AppointmentRequests.ChangeStatus request) {
        statuses.moveTo(id, request.status(), currentActor.resolve());
        return AppointmentResponses.AppointmentWithHistory.of(appointments.read(id), timezone());
    }

    private ZoneId timezone() {
        return businesses.read().timezone();
    }
}
