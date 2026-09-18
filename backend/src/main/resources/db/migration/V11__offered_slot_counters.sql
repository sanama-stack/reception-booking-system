-- Phase 11 — two counters that outlive the transcript they are derived from.
--
-- ADR-0012. A Customer asks for "the Monday after next" and the Receptionist writes a different day
-- about 10.6% of the time (#17). Every layer below the model is behaving correctly while it
-- happens: ownership is proven, the Slot is real, the engine returned it, and the exclusion
-- constraint has nothing to object to. The day is the part that is wrong, and it is the part
-- nothing downstream can check -- because nothing in the runtime holds the relation between the
-- Slots that were offered and the time that was finally written.
--
-- The check itself needs no schema. An Offered Slot is read back from ai_messages, where every
-- find_available_slots result already sits with its arguments and its answer. What needs schema is
-- what survives those rows: V10 deletes a transcript at ninety days and keeps its parent, so a
-- Conversation whose transcript has gone would otherwise have nothing left to say about whether its
-- writes were ever offered.
--
-- TWO COUNTERS, NOT A FLAG.
--
-- A rate is the figure every document about #17 is written in, and a count yields one at any
-- horizon while a boolean does not. "One write, and it missed" and "five writes, one missed" are
-- the same boolean and very different conversations. A flag is derivable from these
-- (unoffered_writes > 0); these are not derivable from a flag.
--
-- They also keep V10's split intact. The parent row carries counters and a cost estimate and no
-- free text; two integers are that, where the dates themselves would not have been -- a date a
-- Customer asked about is closer to what was said than to what it cost.
--
-- WHAT IS COUNTED.
--
-- create_appointment and reschedule_appointment: the two Tools whose arguments carry a time.
-- cancel_appointment carries none and would only dilute the denominator. This means the rate is
-- NOT comparable to #17's recorded figures, which measure reschedules alone -- an accepted cost,
-- recorded in ADR-0012 rather than discovered later by someone comparing two numbers that were
-- never measuring the same thing.
--
-- NOT NULL DEFAULT 0 rather than nullable: a Conversation that predates this migration made its
-- writes without anything watching, and zero-of-zero is the honest description of that. A null
-- would invite a rate computed over a denominator that was never counted.

alter table ai_conversations
    add column writes integer not null default 0,
    add column unoffered_writes integer not null default 0;

comment on column ai_conversations.writes is
    'Appointment writes this Conversation made through create_appointment or reschedule_appointment.';

comment on column ai_conversations.unoffered_writes is
    'Of those, how many landed on a time matching no Offered Slot this Conversation had quoted (ADR-0012).';

-- The owner''s Conversations screen filters on this, so it is the predicate rather than the column
-- that is indexed: the interesting rows are a minority and the common query asks only for them.
create index ai_conversations_unoffered_idx
    on ai_conversations (business_id, last_message_at desc)
    where unoffered_writes > 0;
