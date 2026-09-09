package dev.reception.publicapi;

import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentDirectory;
import dev.reception.appointments.AppointmentLookup;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.phone.PhoneField;
import dev.reception.notifications.ManageTokenService;
import dev.reception.tenancy.TenantAdoption;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two ways a Customer proves an Appointment is theirs, and the only way the public surface
 * reaches one.
 *
 * <p>A Customer has no account, so authority is per appointment rather than per person
 * (docs/06-security.md §6). Both proofs converge here and both produce the same thing: one
 * Appointment, in the tenant that owns it, and nothing else.
 *
 * <p><strong>Proof first, tenant second, read third.</strong> Every method here verifies before it
 * adopts a tenant and adopts before it reads. That order is what makes a stranger's request safe:
 * nothing the caller sent chooses the Business, and the Appointment they end up holding is the one
 * their proof named. A caller who presents a token for another Business's appointment gets that
 * appointment — which is correct, it is theirs — and gains nothing else there, because the token
 * authorises one id and the read that follows is scoped to it.
 *
 * <p><strong>Every failure is the same failure.</strong> A missing appointment, a tampered token and
 * an expired one are one code, exactly as {@code ManageTokenService.verify} returns them; a wrong
 * code and a wrong phone number are another. Distinguishing them would answer the question an
 * attacker is asking (docs/06-security.md §6).
 */
@Component
public class PublicAppointmentAuthority {

    private final AppointmentDirectory directory;
    private final AppointmentLookup appointments;
    private final ManageTokenService tokens;
    private final TenantAdoption tenants;

    public PublicAppointmentAuthority(
            AppointmentDirectory directory,
            AppointmentLookup appointments,
            ManageTokenService tokens,
            TenantAdoption tenants) {
        this.directory = directory;
        this.appointments = appointments;
        this.tokens = tokens;
        this.tenants = tenants;
    }

    /**
     * The Appointment a Manage Link authorises.
     *
     * @throws ApiException {@code MANAGE_TOKEN_INVALID} for every way a token can fail to authorise
     *     anything, the appointment having been deleted included
     */
    @Transactional(readOnly = true)
    public Appointment byManageToken(String token) {
        UUID appointmentId = tokens.verify(token).orElseThrow(PublicAppointmentAuthority::tokenInvalid);
        UUID businessId =
                directory.findBusinessIdAcrossTenants(appointmentId).orElseThrow(PublicAppointmentAuthority::tokenInvalid);

        tenants.adopt(businessId);
        // Scoped to the tenant just adopted, so this is the same read the dashboard performs and
        // there is no second code path that could forget the filter.
        return appointments.require(appointmentId);
    }

    /**
     * The Appointment a Confirmation Code and a phone number together prove.
     *
     * <p><strong>Both, never either.</strong> A phone number alone would let anyone who knows one
     * list and cancel that person's appointments, which docs/06-security.md §6 records as the
     * largest hole in the original specification. A code alone is eight characters and the endpoint
     * it would be guessed at is rate limited to five attempts an hour for exactly that reason.
     *
     * @throws ApiException {@code INVALID_CONFIRMATION_CODE} when the code names nothing, or names
     *     something the presented number did not book
     */
    @Transactional(readOnly = true)
    public Appointment byLookup(String confirmationCode, String presentedPhone) {
        // Codes are generated in upper-case Crockford base32; a customer reading one off a phone
        // screen is not going to hold the shift key.
        String code = confirmationCode.trim().toUpperCase(java.util.Locale.ROOT);

        for (AppointmentDirectory.ConfirmationCodeMatch candidate :
                directory.findByConfirmationCodeAcrossTenants(code)) {
            if (!phoneMatches(presentedPhone, candidate)) {
                continue;
            }
            tenants.adopt(candidate.getBusinessId());
            return appointments.require(candidate.getAppointmentId());
        }
        throw new ApiException(
                ErrorCode.INVALID_CONFIRMATION_CODE,
                "We could not find an appointment with that code and phone number.");
    }

    /**
     * Whether the number the caller typed is the one that booked this candidate.
     *
     * <p>Normalised against <em>that candidate's</em> Business, because a local number means
     * different things in different countries and the stored value is E.164. This is why the country
     * travels with each row rather than being looked up once: with no slug in the path there is no
     * single Business to look it up from.
     *
     * <p>A plain comparison rather than a constant-time one, and deliberately. What is worth
     * brute-forcing here is the code, and the index probe that found this candidate has data-dependent
     * timing this method cannot do anything about — a constant-time compare underneath it would be
     * theatre. The control that actually bounds guessing is five attempts per hour per address.
     */
    private static boolean phoneMatches(String presented, AppointmentDirectory.ConfirmationCodeMatch candidate) {
        Optional<String> normalised = PhoneField.parse(presented, candidate.getBusinessCountry());
        return normalised.isPresent() && normalised.get().equals(candidate.getCustomerPhone());
    }

    private static ApiException tokenInvalid() {
        return new ApiException(
                ErrorCode.MANAGE_TOKEN_INVALID, "This link is no longer valid. Ask the business for a new one.");
    }
}
