package dev.reception.appointments;

/**
 * Which side called off an Appointment.
 *
 * <p>This is not bookkeeping: it decides whether the Cancellation Window applies. A Customer may
 * not cancel inside it; <strong>the Business never is bound by it</strong> (CONTEXT.md). A business
 * that cannot cancel its own appointment when a stylist calls in sick would phone the customer and
 * leave the row confirmed, and the calendar would be lying by lunchtime.
 */
public enum CancelledBy {
    CUSTOMER,
    BUSINESS
}
