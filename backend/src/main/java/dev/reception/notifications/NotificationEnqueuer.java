package dev.reception.notifications;

import dev.reception.appointments.Appointment;
import dev.reception.business.Business;
import dev.reception.business.BusinessService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.common.ids.IdGenerator;
import dev.reception.customers.Customer;
import dev.reception.customers.CustomerService;
import dev.reception.staff.EmployeeService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes outbox rows in the caller's transaction, and decides which rows are owed.
 *
 * <p><strong>{@code MANDATORY} propagation, exactly as {@code AppointmentEventRecorder}.</strong>
 * This is the entire reason the outbox is a table (ADR-0005): a confirmation email must commit with
 * the booking that justifies it or vanish with it. {@code REQUIRES_NEW} would queue mail for
 * appointments that rolled back; no annotation at all would let a stray caller commit one alone. A
 * loud failure outside a transaction is the point.
 *
 * <p><strong>Rendering happens here, not in the poller.</strong> By the time a message goes out the
 * Service may have been renamed and the Employee may have left; the row has to hold what the
 * Customer was told at the moment they were told it. It also means the poller needs no tenant
 * context, no catalog and no business record — it reads three columns and sends them, which is what
 * lets it run as {@code Actor.system()} across every tenant at once.
 *
 * <p><strong>No email address, no rows, and the booking still succeeds.</strong> A Customer who gave
 * a phone number and no email is a completely ordinary customer — the Receptionist takes bookings
 * over the phone — and their Confirmation Code is shown on screen instead. Refusing the booking, or
 * queueing a message with nowhere to go, would both be worse than sending nothing.
 */
@Component
public class NotificationEnqueuer {

    /** How far ahead of the appointment the reminder goes out. */
    private static final Duration REMINDER_LEAD = Duration.ofHours(24);

    /** The states in which a message is owed or was delivered — the live set (see V6). */
    private static final List<NotificationStatus> LIVE =
            List.of(NotificationStatus.PENDING, NotificationStatus.SENT);

    private final NotificationRepository notifications;
    private final EmailTemplateRenderer renderer;
    private final ManageTokenService manageTokens;
    private final BusinessService businesses;
    private final CustomerService customers;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final IdGenerator ids;
    private final Clock clock;
    private final String publicUrl;

    public NotificationEnqueuer(
            NotificationRepository notifications,
            EmailTemplateRenderer renderer,
            ManageTokenService manageTokens,
            BusinessService businesses,
            CustomerService customers,
            ServiceCatalogService catalog,
            EmployeeService employees,
            IdGenerator ids,
            Clock clock,
            @Value("${app.public-url}") String publicUrl) {
        this.notifications = notifications;
        this.renderer = renderer;
        this.manageTokens = manageTokens;
        this.businesses = businesses;
        this.customers = customers;
        this.catalog = catalog;
        this.employees = employees;
        this.ids = ids;
        this.clock = clock;
        this.publicUrl = publicUrl;
    }

    /**
     * A confirmation now, and a reminder a day before — two rows, or one, or none.
     *
     * <p>The reminder is skipped when {@code startsAt − 24h} has already passed. Enqueuing it anyway
     * would mean the poller sends a "your appointment is tomorrow" message within the minute, for an
     * appointment this afternoon. Same-day bookings are the common case in this domain, not the
     * exception, so this branch runs constantly.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bookingConfirmed(Appointment appointment) {
        Customer customer = customers.read(appointment.customerId());
        if (!customer.hasEmail()) {
            return;
        }
        MailModel model = modelFor(appointment, customer, null, null);
        Instant now = clock.instant();

        enqueue(appointment, NotificationType.BOOKING_CONFIRMATION, customer, model, now, now);

        Instant remindAt = appointment.startsAt().minus(REMINDER_LEAD);
        if (remindAt.isAfter(now)) {
            enqueue(appointment, NotificationType.REMINDER_24H, customer, model, remindAt, now);
        }
    }

    /**
     * Supersedes everything still pending and says the appointment is off.
     *
     * <p>The pending rows go first. A reminder for an appointment that is not happening is worse
     * than no reminder — it is the system contradicting a message it sent moments earlier.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appointmentCancelled(Appointment appointment) {
        cancelPending(appointment);

        Customer customer = customers.read(appointment.customerId());
        if (!customer.hasEmail()) {
            return;
        }
        Instant now = clock.instant();
        enqueue(
                appointment,
                NotificationType.CANCELLATION,
                customer,
                modelFor(appointment, customer, null, appointment.cancellationReason()),
                now,
                now);
    }

    /**
     * Says where the appointment went, and moves its reminder with it.
     *
     * <p>Three writes in one transaction, and the order matters. The old reminder is superseded and
     * <strong>flushed</strong> before the replacement is inserted: both rows are
     * {@code (appointment_id, REMINDER_24H)}, and until the first is out of the live set the partial
     * unique index refuses the second.
     *
     * <p><strong>A reminder that already went out is not replaced.</strong> It can only have gone out
     * if the appointment was inside twenty-four hours, and a {@code SENT} row cannot be superseded —
     * saying it was cancelled would be false, and {@code notifications_sent_fields} rejects the row
     * anyway. The Customer is not left uninformed: the reschedule email below carries the new time,
     * which is the message that actually matters.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appointmentRescheduled(Appointment appointment, Instant previousStartsAt) {
        // Before the address is consulted, exactly as cancelling does it. A reminder minted against
        // the old start time is wrong the moment the appointment moves, and it is wrong whether or
        // not anybody is reachable — recipientEmail was copied at enqueue and is not updatable, so
        // an address cleared in between does not stop the poller sending it at the old hour with
        // the old time in the body.
        boolean reminderAlreadySent = supersedePendingReminder(appointment);

        Customer customer = customers.read(appointment.customerId());
        if (!customer.hasEmail()) {
            return;
        }
        Instant now = clock.instant();
        MailModel model = modelFor(appointment, customer, previousStartsAt, null);

        Instant remindAt = appointment.startsAt().minus(REMINDER_LEAD);
        if (!reminderAlreadySent && remindAt.isAfter(now)) {
            enqueue(appointment, NotificationType.REMINDER_24H, customer, model, remindAt, now);
        }
        enqueue(appointment, NotificationType.RESCHEDULE, customer, model, now, now);
    }

    /** @return whether a reminder for this Appointment has already been delivered */
    private boolean supersedePendingReminder(Appointment appointment) {
        List<Notification> reminders = notifications.findByBusinessIdAndAppointmentIdAndTypeAndStatusIn(
                appointment.businessId(), appointment.getId(), NotificationType.REMINDER_24H, LIVE);

        boolean sent = false;
        for (Notification reminder : reminders) {
            if (reminder.status() == NotificationStatus.SENT) {
                sent = true;
            } else {
                reminder.cancel();
            }
        }
        // saveAllAndFlush, not saveAll: the INSERT that follows is checked against the live partial
        // unique index, and an update sitting unflushed in the persistence context is not yet out of
        // it. Without the flush, Hibernate's own ordering — inserts before updates — guarantees the
        // collision rather than merely risking it.
        notifications.saveAllAndFlush(reminders);
        return sent;
    }

    private void cancelPending(Appointment appointment) {
        List<Notification> pending = notifications.findByBusinessIdAndAppointmentIdAndStatusIn(
                appointment.businessId(), appointment.getId(), List.of(NotificationStatus.PENDING));
        pending.forEach(Notification::cancel);
        notifications.saveAllAndFlush(pending);
    }

    private void enqueue(
            Appointment appointment,
            NotificationType type,
            Customer customer,
            MailModel model,
            Instant scheduledFor,
            Instant now) {
        notifications.save(new Notification(
                ids.newId(),
                appointment.businessId(),
                appointment.getId(),
                type,
                customer.email(),
                renderer.render(type, model),
                scheduledFor,
                now));
    }

    private MailModel modelFor(
            Appointment appointment, Customer customer, Instant previousStartsAt, String cancellationReason) {
        Business business = businesses.read();
        return new MailModel(
                business.name(),
                business.phone(),
                business.timezone(),
                customer.fullName(),
                catalog.read(appointment.serviceId()).name(),
                employees.read(appointment.employeeId()).fullName(),
                appointment.startsAt(),
                previousStartsAt,
                appointment.confirmationCode(),
                manageUrlFor(appointment),
                cancellationReason);
    }

    /**
     * The link the Customer opens. Phase 08 owns the page it lands on; the token is issued here
     * because the email is the only thing that carries it.
     */
    private String manageUrlFor(Appointment appointment) {
        UUID id = appointment.getId();
        return publicUrl + "/manage/" + manageTokens.issue(id, appointment.endsAt());
    }
}
