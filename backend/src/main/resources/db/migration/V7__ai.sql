-- Phase 09 — the AI Receptionist.
--
-- Two tables, and between them they hold every turn of every conversation. Nothing else in this
-- phase persists anything: the tools write through the same application services the dashboard and
-- the Classic Flow already call, so appointments, customers and notifications are untouched here.
-- That is ADR-0004 showing its work — the Receptionist adds no storage because it adds no
-- capability.
--
-- The one genuinely new thing being stored is AUTHORITY: which appointments a given conversation
-- has proven it may act on. It lives here rather than in memory because a conversation outlives a
-- request, and it lives in a column the model cannot address because the model has no tool that
-- writes it.

-- ---------------------------------------------------------------------------
-- ai_conversations
--
-- One row per conversation, carrying the three things the loop has to know before it may call a
-- model: which tenant it is in, how much of its budget it has spent, and what it is allowed to
-- touch.
-- ---------------------------------------------------------------------------
CREATE TABLE ai_conversations (
    id                  uuid        PRIMARY KEY,
    business_id         uuid        NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,

    -- SHA-256 of the token the browser holds, never the token. The same reasoning as password
    -- hashes and for the same threat: this column is a session capability, and a database read
    -- must not yield the ability to resume somebody's conversation.
    --
    -- UNIQUE, which is a deliberate strengthening of docs/03-data-model.md's plain index. A session
    -- token names exactly one conversation — that is what the client assumes when it resumes on
    -- reload — and a second row with the same hash would make the lookup return an arbitrary one of
    -- them. An invariant the code depends on belongs in the schema rather than in the hope that
    -- nothing ever inserts twice.
    session_token_hash  varchar(64) NOT NULL,

    -- Null until identity is known, which for most conversations is the moment a booking succeeds.
    -- No FK: a conversation may name a Customer, but a Customer deleted for a data request must not
    -- take the transcript's existence with it.
    customer_id         uuid,

    status              varchar(20) NOT NULL,

    -- Enforces the turn ceiling. Denormalised from ai_messages on purpose: the ceiling is checked
    -- before every model call, and a count(*) over a growing child table is the wrong shape for a
    -- question asked on the hot path.
    message_count       int         NOT NULL DEFAULT 0,

    prompt_tokens       int         NOT NULL DEFAULT 0,
    completion_tokens   int         NOT NULL DEFAULT 0,

    -- Estimated, and named so. It is derived from token counts and a price the application holds,
    -- not from anything the provider billed — good enough to enforce a daily cap, and not to be
    -- mistaken for an invoice.
    estimated_cost_cents int        NOT NULL DEFAULT 0,

    -- THE AUTHORITY COLUMN. Appended to only by a successful create_appointment or
    -- lookup_appointment in this conversation, or seeded from a Manage Link at session creation
    -- (ADR-0004). No tool takes it as a parameter and no tool writes it; the write tools read it
    -- and refuse anything outside it.
    --
    -- An array rather than a join table because it is read whole, on every write-tool call, and is
    -- never queried across conversations. No FK is possible on an array element — the composite
    -- (business_id, id) backstop that appointments carries cannot apply here, so the guard is that
    -- ids only ever enter this column from a service call that already resolved inside this
    -- conversation's tenant.
    authorized_appointment_ids uuid[] NOT NULL DEFAULT '{}',

    started_at          timestamptz NOT NULL,
    last_message_at     timestamptz NOT NULL,

    CONSTRAINT ai_conversations_status_check
        CHECK (status IN ('ACTIVE', 'CLOSED', 'LIMIT_REACHED')),
    CONSTRAINT ai_conversations_counts_check
        CHECK (message_count >= 0 AND prompt_tokens >= 0 AND completion_tokens >= 0
               AND estimated_cost_cents >= 0),

    -- The same tenant key every other table in this schema carries, so ai_messages can reference
    -- (business_id, conversation_id) and a message cannot cross into another tenant's conversation.
    CONSTRAINT ai_conversations_tenant_key UNIQUE (business_id, id)
);

-- Resuming a conversation from the token in sessionStorage. Unique for the reason above.
CREATE UNIQUE INDEX ai_conversations_session_idx ON ai_conversations (session_token_hash);

-- The owner's transcript list, newest first, and the daily cost cap's sum — both are
-- "this business, ordered by when it started", which is the same index.
CREATE INDEX ai_conversations_business_idx ON ai_conversations (business_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- ai_messages
--
-- Every user message, every model response and every tool call with its arguments and its result.
-- Written BEFORE the next iteration of the loop runs, so a crash mid-turn leaves a readable trail
-- rather than a conversation that jumped.
--
-- THE SYSTEM PROMPT IS NOT HERE, and its absence is a design decision rather than an omission
-- (docs/05-ai-architecture.md §4). It is rebuilt deterministically from configuration on every
-- turn, so it cannot drift between turns and cannot be reconstructed by anyone who gets a look at
-- this table.
-- ---------------------------------------------------------------------------
CREATE TABLE ai_messages (
    id              uuid        PRIMARY KEY,
    conversation_id uuid        NOT NULL,
    business_id     uuid        NOT NULL,

    role            varchar(20) NOT NULL,

    -- Null on an assistant turn that only called tools, which is the ordinary shape of the first
    -- iteration: the model's answer to "book me Thursday" is a tool call, not prose.
    content         text,

    tool_name       varchar(60),
    tool_call_id    varchar(80),

    -- jsonb rather than text because these are read by a human debugging a transcript, and the
    -- dashboard renders them. Storing the arguments the model actually sent — not a re-serialised
    -- form — is what makes this table usable as evidence.
    tool_arguments  jsonb,
    tool_result     jsonb,

    created_at      timestamptz NOT NULL,

    CONSTRAINT ai_messages_role_check
        CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),

    -- A TOOL row is meaningless without the call it answers, and only a TOOL row may have one.
    -- Both directions, like notifications_sent_fields: a half-populated row here is a transcript
    -- that cannot be read back.
    CONSTRAINT ai_messages_tool_fields
        CHECK ((role = 'TOOL') = (tool_name IS NOT NULL AND tool_call_id IS NOT NULL)),

    CONSTRAINT ai_messages_conversation_fk
        FOREIGN KEY (business_id, conversation_id)
            REFERENCES ai_conversations (business_id, id) ON DELETE CASCADE
);

-- The sliding window's query and the transcript screen's, which are the same one: this
-- conversation, in order.
CREATE INDEX ai_messages_conversation_idx ON ai_messages (conversation_id, created_at);
