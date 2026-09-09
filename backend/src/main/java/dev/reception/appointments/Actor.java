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

    /**
     * Which side of the appointment this actor is acting for.
     *
     * <p><strong>The Receptionist is the customer's side.</strong> It is reached only from the
     * public booking page, by the person whose appointment it is, and it can do nothing they could
     * not do themselves through a Manage Link — so a cancellation it performs is a customer
     * cancelling, and is recorded as one. Sorted into {@code BUSINESS} instead, the Receptionist
     * would become a way around the Cancellation Window: a customer refused at
     * {@code /manage/{token}} could open the chat panel and ask, and the same operation would go
     * through. A policy the owner set would then be enforced by which door the customer happened to
     * use, which is not a policy.
     *
     * <p>That the Receptionist did it is not lost by this. The actor is recorded separately on the
     * audit event, and {@code AppointmentSource.AI} records which door the booking came in through
     * — {@link CancelledBy} answers a different question, and it has only ever had two answers
     * because there are only two sides.
     *
     * <p>{@code SYSTEM} is the business's side by elimination: a sweep or a poller acts for the
     * business, and nothing that runs without a person behind it should be held to a deadline
     * written for customers.
     */
    public CancelledBy asCancellingParty() {
        return switch (type) {
            case CUSTOMER, AI -> CancelledBy.CUSTOMER;
            case USER, SYSTEM -> CancelledBy.BUSINESS;
        };
    }

    /**
     * Whether the Cancellation Window binds this actor — the one predicate, asked by both paths.
     *
     * <p>Cancelling and rescheduling both have to answer it, and until phase 09 they answered it
     * with two different expressions: {@code CancellationService} went through
     * {@link #asCancellingParty()}, while {@code RescheduleService} compared the type to
     * {@code CUSTOMER} directly. Identical for the two actors that existed, and they would have
     * diverged the moment a third was introduced — which is exactly what {@link Actor#ai()} is.
     *
     * <p>Derived from {@link #asCancellingParty()} rather than listed again, for the reason
     * {@code CancellationWindow} gives about its own arithmetic: the two must not be able to
     * disagree, and the only way to guarantee that is for one of them to be the other.
     */
    public boolean boundByCancellationWindow() {
        return asCancellingParty() == CancelledBy.CUSTOMER;
    }
}
