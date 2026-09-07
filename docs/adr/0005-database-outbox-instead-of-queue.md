# Notifications use a database outbox, not a message queue

**Status:** accepted

Notifications must not be lost when a booking commits, and must not be sent when a booking rolls back. A
message broker cannot give us that without a distributed transaction — or without an outbox in front of it
anyway. So we write notification rows in the same transaction as the state change and drain them with a
`@Scheduled` poller using `SELECT … FOR UPDATE SKIP LOCKED`, which is multi-instance-safe with no extra
infrastructure.

## Consequences

- No Redis, RabbitMQ or Kafka in the stack. The MVP has exactly one asynchronous workload, and adding a
  broker for it would be architecture for its own sake.
- Delivery latency is up to the poll interval (60 seconds). Acceptable: confirmations and 24-hour reminders
  are not latency-sensitive.
- The `notifications` row is the authoritative record of what was sent, because subject and body are
  rendered at enqueue time. A template change cannot alter a pending message.
- A partial unique index on `(appointment_id, type)` for non-cancelled rows makes duplicate confirmations
  and reminders impossible even if enqueue logic runs twice.
- Throughput is bounded by Postgres. At a scale where that matters, the poller becomes a broker consumer and
  the outbox stays exactly as it is — which is the standard migration path, not a rewrite.
- Adding SMS or WhatsApp later means a new `channel` value and a new adapter behind the existing
  `EmailSender`-shaped port, not a new delivery mechanism.
