package dev.reception.appointments;

/**
 * What happened to an Appointment.
 *
 * <p>Deliberately not the same enum as {@link AppointmentStatus}. A status is where the Appointment
 * <em>is</em>; an event is what was <em>done</em> to it — and {@code RESCHEDULED} is the proof they
 * are different, since it changes nothing about the status and is the transition a customer is most
 * likely to ask about later.
 */
public enum AppointmentEventType {

    /** Booked. The first event on every Appointment. */
    CREATED,

    /** Moved to a new time, keeping its id and Confirmation Code. */
    RESCHEDULED,

    CANCELLED,
    COMPLETED,
    NO_SHOW
}
