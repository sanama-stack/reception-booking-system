package dev.reception.appointments;

/**
 * Which door an Appointment came in through.
 *
 * <p>Recorded because the three are worth telling apart in analytics and in an incident: "the
 * Receptionist booked this" is the first thing to know when a customer says the booking is wrong,
 * and it is the measure of whether the AI is earning its place (docs/05-ai-architecture.md).
 *
 * <p>Never supplied by a caller. Each entry point sets its own, which is what keeps the field
 * truthful — a request body field would let the Receptionist claim to be a person.
 */
public enum AppointmentSource {

    /** Booked by the Receptionist through a Tool (phase 09). */
    AI,

    /** Booked by a Customer through the Classic Flow on the public page (phase 08). */
    CLASSIC,

    /** Booked by the Business from the dashboard. */
    DASHBOARD
}
