package dev.reception.publicapi;

import dev.reception.appointments.Actor;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.CancellationService;
import dev.reception.appointments.CancellationWindow;
import dev.reception.appointments.RescheduleService;
import dev.reception.business.Business;
import dev.reception.business.BusinessService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.customers.CustomerService;
import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.scheduling.application.web.AvailabilityResponses;
import dev.reception.staff.EmployeeService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a Customer may do with an Appointment they can prove is theirs.
 *
 * <p><strong>These paths carry no slug, and that is not an inconsistency.</strong> A Customer
 * following a Manage Link from an email does not know which slug their appointment is under, and
 * requiring one would mean a second identifier that could disagree with the token. The tenant is
 * derived from the proof instead, by {@link PublicAppointmentAuthority} — which verifies first and
 * adopts the tenant of whatever the proof turned out to name.
 *
 * <p><strong>The path id never selects the appointment.</strong> On cancel and reschedule the
 * authority resolves an appointment on its own, and the {@code {id}} in the path is then compared
 * against it. A Manage Link for appointment A presented on B's path is refused — the id in a URL is
 * a claim, and the token is the proof, so the proof decides and the claim is checked against it.
 *
 * <p>The Cancellation Window applies to everything here, because everything here is a Customer
 * acting. The dashboard's identical operations stay exempt: a Business is never bound by its own
 * customer-facing deadline (CONTEXT.md).
 */
@RestController
@RequestMapping("/public/appointments")
public class PublicAppointmentController {

    private final PublicAppointmentAuthority authority;
    private final CancellationService cancellation;
    private final RescheduleService reschedule;
    private final CancellationWindow window;
    private final AvailabilityService availability;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final BusinessService businesses;
    private final CustomerService customers;

    public PublicAppointmentController(
            PublicAppointmentAuthority authority,
            CancellationService cancellation,
            RescheduleService reschedule,
            CancellationWindow window,
            AvailabilityService availability,
            ServiceCatalogService catalog,
            EmployeeService employees,
            BusinessService businesses,
            CustomerService customers) {
        this.authority = authority;
        this.cancellation = cancellation;
        this.reschedule = reschedule;
        this.window = window;
        this.availability = availability;
        this.catalog = catalog;
        this.employees = employees;
        this.businesses = businesses;
        this.customers = customers;
    }

    /**
     * Confirmation Code <strong>and</strong> phone number, in a {@code POST} body.
     *
     * <p>A {@code POST} because a phone number must never enter a URL, a log line or a referrer
     * header (docs/04-api-overview.md §6). It is also the most aggressively limited endpoint in the
     * system — five an hour per address — because it is the one an attacker would brute-force.
     */
    @PostMapping("/lookup")
    public PublicResponses.ManagedAppointment lookup(@Valid @RequestBody PublicRequests.Lookup request) {
        return render(authority.byLookup(request.confirmationCode(), request.phone()));
    }

    /** Resolves a Manage Link. The token stays in the query string of a request nothing logs. */
    @GetMapping("/manage")
    public PublicResponses.ManagedAppointment manage(@RequestParam String token) {
        return render(authority.byManageToken(token));
    }

    /**
     * The Slots a rescheduling Customer may move to.
     *
     * <p>Excluding their own appointment, which is the whole reason this endpoint exists rather than
     * the Customer reusing the public availability grid: the time they currently hold — and its
     * Buffers, which reach further — is blocked by their own booking, so an unexcluded grid would
     * refuse to offer them a slot fifteen minutes later. See
     * {@code AvailabilityService#find(UUID, LocalDate, LocalDate, UUID, UUID)}.
     *
     * <p><strong>The exclusion is the token's appointment and cannot be anything else.</strong>
     * There is no parameter here a caller could point at somebody else's booking — which is exactly
     * what the public availability endpoint refuses to offer, and why this one is separate.
     *
     * <p>The Service is the appointment's own. A Customer rescheduling is moving what they booked,
     * not choosing again.
     */
    @GetMapping("/manage/availability")
    public AvailabilityResponses.Availability manageAvailability(
            @RequestParam String token,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID employeeId) {
        Appointment appointment = authority.byManageToken(token);
        return AvailabilityResponses.Availability.of(
                availability.find(appointment.serviceId(), from, to, employeeId, appointment.getId()));
    }

    /**
     * Cancels, inside the Cancellation Window's rules.
     *
     * <p>Idempotent, like the dashboard's: a Customer who taps a Manage Link twice, or whose request
     * timed out after the server committed, gets the same appointment back and no second email.
     */
    @PostMapping("/{id}/cancel")
    public PublicResponses.ManagedAppointment cancel(
            @PathVariable UUID id, @Valid @RequestBody PublicRequests.CancelAppointment request) {
        Appointment appointment = require(request.authority(), id);
        // Actor.customer() is what binds the Cancellation Window. CancellationService reads it from
        // the actor rather than from which controller called it, which is why the dashboard's
        // exemption needs no flag here to stay true.
        return render(cancellation.cancel(appointment.getId(), Actor.customer(), request.reason()));
    }

    /** Moves it, in place, validating the new time against the same engine a new booking uses. */
    @PostMapping("/{id}/reschedule")
    public PublicResponses.ManagedAppointment reschedule(
            @PathVariable UUID id, @Valid @RequestBody PublicRequests.RescheduleAppointment request) {
        Appointment appointment = require(request.authority(), id);
        return render(reschedule.reschedule(
                appointment.getId(), request.startsAt().toInstant(), request.employeeId(), Actor.customer()));
    }

    /**
     * The Appointment the caller proved, once it is confirmed to be the one they addressed.
     *
     * <p>The comparison is the guard the phase document names: <em>a manage token for appointment A
     * cannot act on appointment B</em>. It is a {@code 404} rather than a {@code 403}, matching the
     * rule that "does not exist" and "is not yours" are indistinguishable everywhere else
     * (docs/06-security.md §3) — a distinct code would confirm that B exists.
     */
    private Appointment require(PublicRequests.Authority proof, UUID addressed) {
        Appointment appointment = resolve(proof);
        if (!appointment.getId().equals(addressed)) {
            throw ApiException.notFound("No such appointment.");
        }
        return appointment;
    }

    /**
     * Whichever of the two proofs was presented.
     *
     * <p>Neither is a schema requirement, because "exactly one of these" is not expressible as a
     * field annotation. Presenting neither is a validation failure rather than a failed attempt: it
     * cannot be a lucky guess, so it does not spend from the lookup budget.
     */
    private Appointment resolve(PublicRequests.Authority proof) {
        if (proof.hasManageToken()) {
            return authority.byManageToken(proof.manageToken());
        }
        if (isBlank(proof.confirmationCode()) || isBlank(proof.phone())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "One or more fields are invalid.",
                    List.of(
                            new FieldError("authority.confirmationCode", "Enter your confirmation code."),
                            new FieldError("authority.phone", "Enter the phone number you booked with.")));
        }
        return authority.byLookup(proof.confirmationCode(), proof.phone());
    }

    /**
     * Everything a Manage Link page shows, assembled inside the tenant the proof resolved.
     *
     * <p>The Customer is read for <strong>one bit and nothing else</strong>: whether an address is
     * on record, which is what {@code NotificationEnqueuer} gates every cancellation and reschedule
     * email on. Without it the page has to guess, and it guessed wrong — a Manage Link arrives by
     * email, so the address was there when the token was issued, but the Business can clear it from
     * the dashboard and the token stays valid until the appointment ends. See ADR-0008. Nothing else
     * off the record reaches {@code ManagedAppointment}, and that is the point: the person reading
     * it is the Customer, so echoing their details back would only turn a proof into a way to read
     * them.
     */
    private PublicResponses.ManagedAppointment render(Appointment appointment) {
        Business business = businesses.read();
        return PublicResponses.ManagedAppointment.of(
                appointment,
                catalog.read(appointment.serviceId()),
                employees.read(appointment.employeeId()),
                business,
                window.isOpenFor(appointment),
                // The same predicate the outbox asks, so the screen and the enqueuer cannot drift
                // (ADR-0007). One definition of "reachable by email", two callers, now three.
                customers.read(appointment.customerId()).hasEmail());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
