package dev.reception.appointments;

import java.util.UUID;

/**
 * Who is performing an operation on an Appointment.
 *
 * <p>Passed explicitly into every write rather than read from an ambient security context inside
 * them. The same three services are called by the dashboard, by the public Classic Flow (phase 08)
 * and by the Receptionist's Tools (phase 09), and only two of those three have a
 * {@code SecurityContext} to read. A service that reached for one would work for the caller that
 * has it and silently record the wrong actor — or nothing — for the two that do not.
 *
 * <p>This is the same reasoning the project applies to {@code Clock}: what a method needs is an
 * argument, not something it fetches.
 *
 * @param id the acting User, or {@code null} for a Customer, the Receptionist or the system, none of
 *     which has a user row
 */
public record Actor(ActorType type, UUID id) {

    public static Actor user(UUID userId) {
        return new Actor(ActorType.USER, userId);
    }

    /** A Customer acting through a Manage Link or a Confirmation Code (phase 08). */
    public static Actor customer() {
        return new Actor(ActorType.CUSTOMER, null);
    }

    /** The Receptionist, acting through a Tool (phase 09). */
    public static Actor ai() {
        return new Actor(ActorType.AI, null);
    }

    /** The application itself — a poller or a sweep, with nobody behind it. */
    public static Actor system() {
        return new Actor(ActorType.SYSTEM, null);
    }

    /** Whether the Cancellation Window binds this actor. Only a Customer is bound (CONTEXT.md). */
    public CancelledBy asCancellingParty() {
        return type == ActorType.CUSTOMER ? CancelledBy.CUSTOMER : CancelledBy.BUSINESS;
    }
}
