package dev.reception.appointments.web;

import dev.reception.appointments.AppointmentStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request bodies for {@code /appointments/*}.
 *
 * <p>Bean Validation covers presence, length and shape. Everything about <em>whether the time
 * works</em> — opening hours, the Employee's schedule, the horizon, what is already booked — is the
 * availability engine's, and none of it is expressible as an annotation.
 *
 * <p>None of these carries a {@code businessId}, a {@code price} or a {@code source}. The tenant
 * comes from the Membership, the price is snapshotted from the Service, and each entry point sets
 * its own source — a caller who could name one could have the Receptionist claim to be a person.
 */
public final class AppointmentRequests {

    private AppointmentRequests() {}

    /**
     * Booking from the dashboard.
     *
     * <p>{@code startsAt} is an {@link OffsetDateTime} rather than an {@code Instant} so the offset
     * the client believed it was using travels with the request and is checked rather than assumed.
     * A bare local time would be interpreted against the server's zone, which is the class of bug
     * that cost this project two phases.
     *
     * <p>{@code employeeId} is required. Every Slot a client can offer already names the Employee
     * who would perform it, and resolving one again at booking time would be a second chance to
     * choose differently from what the Customer was shown.
     */
    public record CreateAppointment(
            @NotNull(message = "Choose a service.") UUID serviceId,
            @NotNull(message = "Choose who will perform it.") UUID employeeId,
            @NotNull(message = "Choose a start time.") OffsetDateTime startsAt,
            @NotBlank(message = "Enter the customer's name.")
                    @Size(max = 120, message = "Names can be at most 120 characters.")
                    String customerName,
            @NotBlank(message = "Enter a phone number.")
                    @Size(max = 30, message = "That is longer than a phone number.")
                    String customerPhone,
            @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String customerEmail,
            @Size(max = 1000, message = "The note can be at most 1000 characters.") String customerNote) {}

    /**
     * Moving one. {@code employeeId} is optional and {@code null} keeps the current Employee — the
     * common case is "same person, different hour".
     */
    public record RescheduleAppointment(
            @NotNull(message = "Choose a new start time.") OffsetDateTime startsAt, UUID employeeId) {}

    /** A reason is optional; a business cancelling on the phone often has one worth recording. */
    public record CancelAppointment(
            @Size(max = 500, message = "The reason can be at most 500 characters.") String reason) {}

    /**
     * {@code COMPLETED} or {@code NO_SHOW}.
     *
     * <p>Cancelling is deliberately <em>not</em> reachable here even though it is a status change:
     * it carries rules this endpoint has no way to express — who cancelled, whether the Cancellation
     * Window is open, and idempotence — and it has its own endpoint for that reason.
     */
    public record ChangeStatus(@NotNull(message = "Choose a status.") AppointmentStatus status) {}
}
