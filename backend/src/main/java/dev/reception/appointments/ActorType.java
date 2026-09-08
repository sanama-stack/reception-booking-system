package dev.reception.appointments;

/**
 * Who performed a recorded transition.
 *
 * <p>Nullable {@code actorId} beside a non-null type, because two of these four have no id to
 * record: a Customer has no account, and the Receptionist has no user row. The type is the field
 * that always means something, which is why the audit table makes it {@code NOT NULL} and the id
 * not.
 */
public enum ActorType {

    /** A Customer acting on their own Appointment, through a Manage Link or a Confirmation Code. */
    CUSTOMER,

    /** A signed-in dashboard user. {@code actorId} is their user id. */
    USER,

    /** The Receptionist, acting through a Tool. */
    AI,

    /** The application itself — a scheduled sweep, a migration, a poller. */
    SYSTEM
}
