package dev.reception.notifications;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Everything a template may say, gathered before anything is rendered.
 *
 * <p>A flat record of plain values rather than the entities they came from, and that is the point:
 * {@code EmailTemplateRenderer} can then be tested without a database, and the {@code notifications}
 * package does not import {@code Appointment}, {@code Customer}, {@code Service},
 * {@code Employee} and {@code Business} in order to read five strings off them. The assembling is
 * {@code NotificationEnqueuer}'s job, which is the class that already holds all five.
 *
 * <p>Instants rather than formatted strings, because the formatting is a rule — the Business's
 * timezone, spelled out in a fixed locale — and a rule belongs with the renderer that owns it, not
 * with each caller who would eventually apply it differently.
 *
 * @param businessPhone nullable; the footer line is dropped when there is none
 * @param previousStartsAt nullable; only a reschedule has one
 * @param cancellationReason nullable; a cancellation need not carry a reason
 */
public record MailModel(
        String businessName,
        String businessPhone,
        ZoneId timezone,
        String customerName,
        String serviceName,
        String employeeName,
        Instant startsAt,
        Instant previousStartsAt,
        String confirmationCode,
        String manageUrl,
        String cancellationReason) {}
