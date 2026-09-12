-- Phase 11 — the retention window, and the column that makes a purged transcript say so.
--
-- docs/05-ai-architecture.md §11 has said "Retention: 90 days, documented" since phase 09 and
-- deferred the job itself to V1.1. Phase 11 pulled it forward by principal decision: an ai_message
-- holds the Customer's name and phone number as they typed them, and NOTHING IN THIS SYSTEM HAS
-- EVER DELETED ONE. The window was already decided; this is the machinery under it.
--
-- No new table. One nullable column and one partial index, and each is load-bearing for a
-- different reason.
--
-- WHAT IS PURGED, AND WHAT IS NOT.
--
-- The messages go; the ai_conversations row stays. That is the principal's call, and the shape the
-- phase checklist already named -- "No ai_message outlives the documented retention window" says
-- nothing about the parent. The parent carries no free text: business_id, a hash, a customer_id
-- pointer, four counters and the authority array. What it does carry is the token and cost
-- accounting, which is the record of what the Receptionist cost this business and is worth more at
-- ninety days than the transcript is.
--
-- THE CUTOFF IS THE CONVERSATION'S last_message_at, NOT EACH MESSAGE'S created_at.
--
-- A per-message cutoff would split a conversation that straddles the boundary: its opening turns
-- deleted, its later ones kept, and a reader left with a transcript that begins mid-sentence. That
-- is precisely the half-scrubbed transcript phase 11 rejected when it chose purging over redaction.
-- All-or-nothing per transcript, anchored on the parent, is the only shape that cannot produce one.
--
-- The honest statement of the window is therefore "ninety days after a conversation's last
-- activity" rather than "ninety days after a message was written". The two differ by the length of
-- one conversation -- capped at forty messages inside a single browsing session -- and the first is
-- the one this schema can actually enforce.

-- ---------------------------------------------------------------------------
-- messages_purged_at
--
-- NULL means "this conversation's messages are as they were written". Non-null means the purge has
-- been here, and is a fact about the row rather than an inference from it.
--
-- THE INFERENCE IS AVAILABLE AND IS NOT GOOD ENOUGH. message_count is denormalised onto this table
-- and the purge does not decrement it, so `message_count > 0 AND no rows in ai_messages` does
-- identify a purged conversation. It also reads as a broken foreign key to anyone who meets it
-- without knowing the purge exists, and the transcript screen already has an empty state that says
-- "opened but nothing was ever said in it" -- which for a purged conversation is a LIE, sitting
-- directly under a Rows count that contradicts it. A screen cannot tell the truth about a state the
-- API cannot name.
--
-- It is also what makes the purge cheap to run forever: see the index below.
-- ---------------------------------------------------------------------------
ALTER TABLE ai_conversations ADD COLUMN messages_purged_at timestamptz;

COMMENT ON COLUMN ai_conversations.messages_purged_at IS
    'When the retention purge deleted this conversation''s ai_messages rows. NULL means it has not '
    'run against this conversation. Set by TranscriptPurge and by nothing else; no request path '
    'writes it.';

-- ---------------------------------------------------------------------------
-- The purge's own index, and the reason it is partial.
--
-- The job asks one question on a timer: which conversations last spoke before the cutoff and still
-- have messages. Without the WHERE clause this index would answer the first half and the job would
-- re-visit every conversation it has ever purged, on every run, forever -- work proportional to the
-- whole history to delete the day's worth that aged out of it. That is the same mistake phase 10
-- found in the calendar's overlap query, which read a tenant's entire history to return one week.
--
-- Partial, the index holds only rows that are still candidates. It SHRINKS as the purge works, and
-- a database whose transcripts are all older than the window ends up with an empty one.
--
-- Not tenant-scoped, and deliberately so: the purge is the system acting on behalf of every
-- Business at once, which is the same actor the notification poller has and the same reason
-- NotificationClaimRepository exists as its own type.
-- ---------------------------------------------------------------------------
CREATE INDEX ai_conversations_purge_idx
    ON ai_conversations (last_message_at)
    WHERE messages_purged_at IS NULL;

COMMENT ON INDEX ai_conversations_purge_idx IS
    'Serves the retention purge only. Partial on messages_purged_at IS NULL so a purged '
    'conversation leaves the candidate set permanently, rather than being re-scanned on every run '
    'for the rest of the database''s life.';
