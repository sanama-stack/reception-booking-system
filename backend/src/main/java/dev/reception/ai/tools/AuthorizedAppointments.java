package dev.reception.ai.tools;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Which Appointments this conversation has proven it may act on.
 *
 * <p>The central authorisation object of the Receptionist, and the reason a hallucinated appointment
 * id achieves nothing. It starts from whatever the conversation record already held, grows during a
 * turn, and is written back when the turn ends.
 *
 * <p><strong>Mutable, and deliberately so.</strong> A set that grew by being replaced would mean
 * every tool returning a new context and the loop threading it through — and a tool that forgot
 * would silently drop an authorisation that had genuinely been earned. One object, appended to by
 * the two tools entitled to append to it, is the shape that cannot be forgotten.
 *
 * <p><strong>Nothing here validates.</strong> {@link #authorize} is called only after a service
 * call has already resolved an Appointment inside this conversation's tenant, which is what makes
 * the id safe to hold. This class is the record of that decision, not the decision.
 */
public final class AuthorizedAppointments {

    private final Set<UUID> ids;

    public AuthorizedAppointments(Collection<UUID> seed) {
        this.ids = new LinkedHashSet<>(Objects.requireNonNull(seed, "seed"));
    }

    public static AuthorizedAppointments none() {
        return new AuthorizedAppointments(Set.of());
    }

    /**
     * Records that this conversation proved ownership of {@code appointmentId}.
     *
     * <p>Three callers, and there will never be a fourth without an ADR: {@code create_appointment}
     * on success, {@code lookup_appointment} on a matching code and phone, and session creation from
     * a valid Manage Link (ADR-0004).
     */
    public void authorize(UUID appointmentId) {
        ids.add(Objects.requireNonNull(appointmentId, "appointmentId"));
    }

    /** Whether a write tool may proceed. Asked before the application service is reached. */
    public boolean contains(UUID appointmentId) {
        return appointmentId != null && ids.contains(appointmentId);
    }

    /** What the conversation row should now hold. */
    public Set<UUID> snapshot() {
        return Set.copyOf(ids);
    }

    public UUID[] toArray() {
        return ids.toArray(UUID[]::new);
    }
}
