package dev.reception.publicapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request bodies for {@code /public/*}, fixed in docs/04-api-overview.md §6.
 *
 * <p>None of these carries a {@code businessId}, a {@code price}, a {@code source} or a
 * {@code status}. The tenant comes from the slug, the price is snapshotted from the Service, the
 * source is set by the controller, and a Customer does not get to choose what state their
 * appointment is in.
 *
 * <p>The customer's details are nested, unlike the dashboard's flat body. That is the published
 * contract rather than an accident, and it is why {@code CustomerFieldNames.PUBLIC_BOOKING} reports
 * failures under dotted paths — the client indexes errors by the name it sent, and for this request
 * that name is {@code customer.phone}.
 */
public final class PublicRequests {

    private PublicRequests() {}

    /**
     * Booking from the public page.
     *
     * <p>{@code startsAt} is an {@link OffsetDateTime} for the reason the dashboard's is: the offset
     * the client believed it was using travels with the request and is checked rather than assumed.
     *
     * <p>{@code employeeId} is required even when the Customer chose "Any available". The Slot they
     * were shown already named the Employee who would perform it, and resolving one again here would
     * be a second chance to pick somebody other than the person on the screen they clicked.
     */
    public record CreateAppointment(
            @NotNull(message = "Choose a service.") UUID serviceId,
            @NotNull(message = "Choose who will perform it.") UUID employeeId,
            @NotNull(message = "Choose a start time.") OffsetDateTime startsAt,
            @NotNull(message = "Enter your details.") @Valid CustomerDetails customer,
            @Size(max = 1000, message = "The note can be at most 1000 characters.") String note) {}

    /** Who is booking. No password, no account — a Customer is a name and a reachable number. */
    public record CustomerDetails(
            @NotBlank(message = "Enter your name.")
                    @Size(max = 120, message = "Names can be at most 120 characters.")
                    String fullName,
            @NotBlank(message = "Enter a phone number.")
                    @Size(max = 30, message = "That is longer than a phone number.")
                    String phone,
            @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String email) {}

    /**
     * Proving an appointment is yours: the Confirmation Code <strong>and</strong> the number that
     * booked.
     *
     * <p>Both are {@code @NotBlank}, so presenting one alone is a schema rejection rather than a
     * failed attempt — it never reaches the lookup and never spends from the five-an-hour budget,
     * which is reserved for guesses that could conceivably be right.
     */
    public record Lookup(
            @NotBlank(message = "Enter your confirmation code.")
                    @Size(max = 16, message = "That is longer than a confirmation code.")
                    String confirmationCode,
            @NotBlank(message = "Enter the phone number you booked with.")
                    @Size(max = 30, message = "That is longer than a phone number.")
                    String phone) {}

    /**
     * The proof carried by a cancel or a reschedule.
     *
     * <p>One of two shapes, and the controller decides which is present rather than the schema:
     * either a Manage Link token, or the Confirmation Code and phone number a lookup would take.
     * Neither is {@code @NotBlank}, because "exactly one of these" is not expressible as a field
     * annotation and a rule half-enforced in two places is worse than one enforced in one.
     *
     * <p>The token is never logged and never echoed back. It is a capability, and an error message
     * is one of the places a capability leaks (docs/06-security.md §6).
     */
    public record Authority(String manageToken, String confirmationCode, String phone) {

        public boolean hasManageToken() {
            return manageToken != null && !manageToken.isBlank();
        }
    }

    /** A Customer cancelling. The reason is optional and goes into the audit trail. */
    public record CancelAppointment(
            @NotNull(message = "This link is no longer valid.") @Valid Authority authority,
            @Size(max = 500, message = "The reason can be at most 500 characters.") String reason) {}

    /**
     * A Customer moving one. {@code employeeId} is optional and {@code null} keeps the current
     * Employee — "same person, later" is the common case.
     */
    public record RescheduleAppointment(
            @NotNull(message = "This link is no longer valid.") @Valid Authority authority,
            @NotNull(message = "Choose a new start time.") OffsetDateTime startsAt,
            UUID employeeId) {}
}
