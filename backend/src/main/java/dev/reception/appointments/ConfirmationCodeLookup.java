package dev.reception.appointments;

import dev.reception.common.phone.PhoneField;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turning a Confirmation Code and a phone number into the Appointments they actually prove.
 *
 * <p>One definition, because there are now two callers that must not disagree about what a valid
 * proof is: the public lookup endpoint, which has no tenant yet and adopts whichever one the proof
 * names, and the Receptionist's {@code lookup_appointment} tool, which already has a tenant and must
 * never leave it.
 *
 * <p><strong>The difference between those two callers is the whole reason this class exists
 * separately from the proof itself.</strong> {@code PublicAppointmentAuthority} answers "whose
 * appointment is this", across every Business, because a Customer following a link from an email has
 * not told us which Business they mean. The Receptionist's caller has: they are talking to one
 * business's booking page, and a code that names another business's appointment is not a match to be
 * adopted — it is a code for somewhere else. Sharing the <em>matching</em> and separating the
 * <em>scoping</em> is what keeps a conversation from being able to reach out of its tenant while
 * still letting a stranger with an email reach in.
 *
 * <p>Nothing here is authorisation on its own. It answers which rows a proof matches; deciding what
 * the holder may then do with one is the caller's, and both callers do it differently.
 */
@Component
public class ConfirmationCodeLookup {

    private final AppointmentDirectory directory;

    public ConfirmationCodeLookup(AppointmentDirectory directory) {
        this.directory = directory;
    }

    /**
     * Every Appointment, in any Business, that this code and this number together name.
     *
     * <p>Usually nought or one. A list because a Confirmation Code is unique within a Business and
     * this question spans all of them — see {@link AppointmentDirectory}.
     */
    @Transactional(readOnly = true)
    public List<AppointmentDirectory.ConfirmationCodeMatch> matching(String confirmationCode, String presentedPhone) {
        return directory.findByConfirmationCodeAcrossTenants(normalise(confirmationCode)).stream()
                .filter(candidate -> phoneMatches(presentedPhone, candidate))
                .toList();
    }

    /**
     * The same question, confined to one Business.
     *
     * <p>The Receptionist's path. A code belonging to another Business simply does not match here,
     * and the customer is told the code was not found — which is true of this business, is the same
     * answer they would get for a code that never existed, and does not confirm that the code is
     * real somewhere else (docs/06-security.md §3, §6).
     */
    @Transactional(readOnly = true)
    public Optional<AppointmentDirectory.ConfirmationCodeMatch> withinBusiness(
            java.util.UUID businessId, String confirmationCode, String presentedPhone) {
        return matching(confirmationCode, presentedPhone).stream()
                .filter(candidate -> candidate.getBusinessId().equals(businessId))
                .findFirst();
    }

    /**
     * Codes are generated in upper-case Crockford base32; a customer reading one off a phone screen
     * is not going to hold the shift key.
     */
    public static String normalise(String confirmationCode) {
        return confirmationCode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Whether the number the caller typed is the one that booked this candidate.
     *
     * <p>Normalised against <em>that candidate's</em> Business, because a local number means
     * different things in different countries and the stored value is E.164. This is why the country
     * travels with each row rather than being looked up once: across tenants there is no single
     * Business to look it up from.
     *
     * <p>A plain comparison rather than a constant-time one, and deliberately. What is worth
     * brute-forcing here is the code, and the index probe that found this candidate has
     * data-dependent timing this method cannot do anything about — a constant-time compare
     * underneath it would be theatre. The control that actually bounds guessing is five attempts per
     * hour on the public endpoint, and the conversation ceiling on the Receptionist's.
     */
    private static boolean phoneMatches(String presented, AppointmentDirectory.ConfirmationCodeMatch candidate) {
        Optional<String> normalised = PhoneField.parse(presented, candidate.getBusinessCountry());
        return normalised.isPresent() && normalised.get().equals(candidate.getCustomerPhone());
    }
}
