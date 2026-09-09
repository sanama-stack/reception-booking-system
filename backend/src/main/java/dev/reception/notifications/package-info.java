/**
 * The outbox: notification rows, the poller that sends them, the templates they are rendered from,
 * and the Manage Link they carry.
 *
 * <p><strong>The shape of this package is one decision (ADR-0005).</strong> Rows are written in the
 * same transaction as the state change that justifies them, by {@code NotificationEnqueuer}, whose
 * {@code MANDATORY} propagation makes that structural rather than customary. Everything else follows
 * from it: rendering happens at enqueue time, so the row records what the Customer was told; the
 * poller therefore needs no tenant context and no domain services, which is what lets it run for
 * every Business at once as {@code Actor.system()}.
 *
 * <p>Two repositories, deliberately. {@code NotificationRepository} is tenant-scoped like every other
 * repository in this application; {@code NotificationClaimRepository} holds the one query that must
 * cross tenants, so the rule stays a rule instead of a rule with an exception.
 *
 * <p>The pieces a reader is most likely to want first: {@code NotificationEnqueuer} for what is owed
 * and when, {@code NotificationDispatcher} for what happens to a batch, {@code V6__notifications.sql}
 * for why duplicate enqueue is impossible, and {@code ManageTokenService} for the only credential a
 * Customer ever holds.
 */
package dev.reception.notifications;
