-- Phase 07 — Notifications.
--
-- The outbox. One table, and the reason it is a table rather than a message broker is the whole
-- design (ADR-0005): a notification row is written in the SAME TRANSACTION as the state change
-- that justifies it. Either the appointment and its emails commit together, or neither does.
--
-- A broker cannot offer that. Publishing inside the transaction sends a message for a booking that
-- may still roll back; publishing after it commits loses the message if the process dies in the
-- gap. Both failures are invisible until a customer says they never got an email — and by then
-- there is nothing in the system that even records one was owed. Here, the row IS the record.
--
-- Everything else in this file exists to make the send side safe: the poller can run on more than
-- one instance, a failing row cannot block the others, and a duplicate enqueue is refused by an
-- index rather than by application logic remembering to check.

-- ---------------------------------------------------------------------------
-- notifications
--
-- SUBJECT AND BODY ARE STORED, not rendered at send time. Two reasons, and the second is the one
-- that matters: a template edit must not be able to change the content of a message already
-- queued, and after the fact this row is the only evidence of what the customer actually received.
-- Re-rendering at send would mean the system could never answer "what did we tell them", only
-- "what would we tell them now".
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id              uuid         PRIMARY KEY,
    business_id     uuid         NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,
    appointment_id  uuid         NOT NULL,

    type            varchar(30)  NOT NULL,
    channel         varchar(20)  NOT NULL,

    -- Denormalised from the customer on purpose. The address a message was sent to is part of what
    -- happened; reading it back through customers would report where a later correction points
    -- instead (the customer profile is editable, phase 06).
    recipient_email varchar(254) NOT NULL,

    subject         text         NOT NULL,
    body_html       text         NOT NULL,
    body_text       text         NOT NULL,

    scheduled_for   timestamptz  NOT NULL,
    status          varchar(20)  NOT NULL,

    attempts        int          NOT NULL DEFAULT 0,
    last_error      text,

    sent_at         timestamptz,
    created_at      timestamptz  NOT NULL,

    CONSTRAINT notifications_type_check
        CHECK (type IN ('BOOKING_CONFIRMATION', 'REMINDER_24H', 'CANCELLATION', 'RESCHEDULE')),
    CONSTRAINT notifications_channel_check
        CHECK (channel IN ('EMAIL')),
    CONSTRAINT notifications_status_check
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT notifications_attempts_check
        CHECK (attempts >= 0),

    -- Both directions, exactly as appointments_cancel_fields is. A SENT row with no timestamp
    -- cannot be reported on, and a timestamp on a PENDING row would claim a send that has not
    -- happened — which is the one lie that would make the outbox untrustworthy.
    CONSTRAINT notifications_sent_fields
        CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),

    -- Composite, so a notification cannot reference another tenant's appointment. The same
    -- backstop appointments itself carries (docs/03-data-model.md §1).
    CONSTRAINT notifications_appointment_fk
        FOREIGN KEY (business_id, appointment_id) REFERENCES appointments (business_id, id) ON DELETE CASCADE,

    CONSTRAINT notifications_tenant_key UNIQUE (business_id, id)
);

-- The poller's claim query, and the only index it needs. Partial on PENDING because that is the
-- only status it ever asks for, and the table is append-mostly: within a month the overwhelming
-- majority of rows are SENT, and an unfiltered index would carry all of them to answer a question
-- that is only ever about the few that are not.
--
-- Deliberately NOT led by business_id, which every other index in this schema is. The poller is
-- the system acting for every tenant at once; leading with business_id would force it to either
-- scan the whole index or iterate tenants, and there is no per-tenant query against this table.
CREATE INDEX notifications_due_idx ON notifications (scheduled_for) WHERE status = 'PENDING';

-- Reading one appointment's outbox — the detail screen, and the reschedule path that has to find
-- the reminder it is about to cancel.
CREATE INDEX notifications_appointment_idx ON notifications (appointment_id, type);

-- ---------------------------------------------------------------------------
-- THE INDEX THAT MAKES DUPLICATE ENQUEUE IMPOSSIBLE.
--
-- One live notification of each *once-only* type per appointment. Partial on ('PENDING','SENT') so
-- that the states meaning "this message is owed or was delivered" are exclusive, while CANCELLED
-- and FAILED rows fall out of the index entirely.
--
-- Both status exclusions are load-bearing, and neither is an optimisation:
--
--   CANCELLED  A reschedule cancels the old REMINDER_24H and enqueues a new one. Both rows are for
--              the same (appointment_id, type). If CANCELLED counted, the second insert would be
--              refused and the moved appointment would carry a reminder for the time it left.
--
--   FAILED     A row that exhausted its retries has to be able to be re-enqueued by hand without
--              first deleting the evidence of why it failed.
--
-- THE TYPE PREDICATE IS A DEPARTURE FROM docs/03-data-model.md, AND DELIBERATE.
--
-- That document specifies the index over every type. Written that way it also forbids the second
-- CANCELLATION or RESCHEDULE row — and a customer who moves an appointment twice is owed two
-- emails, which docs/phases/phase-07-notifications.md requires in the same breath ("Reschedule …
-- RESCHEDULE email sent"). The two statements cannot both hold.
--
-- The prose in both documents says what the index is FOR: it "makes duplicate confirmations and
-- reminders impossible even if enqueue logic runs twice". Those are exactly the two types that are
-- owed once per appointment for its whole life. Cancellation and reschedule are events, and an
-- event that happens twice is two facts, not one fact repeated.
--
-- Nor could the application work around a wider index. Superseding the previous RESCHEDULE row
-- would mean setting a SENT row to CANCELLED, which is both a lie — it *was* sent — and a violation
-- of notifications_sent_fields two constraints above.
--
-- This is the same argument the exclusion constraint in V5 makes: enqueue logic running twice is an
-- ordinary event — a retried request, a poller restart mid-transaction — and the defence belongs in
-- the database rather than in an application check that has to remember to run. It just has to
-- defend the claim that is actually true.
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX notifications_live_type_idx
    ON notifications (appointment_id, type)
 WHERE status IN ('PENDING', 'SENT')
   AND type IN ('BOOKING_CONFIRMATION', 'REMINDER_24H');
