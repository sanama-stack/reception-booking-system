-- Phase 06 — Customers and Appointments.
--
-- The write path. Everything before this migration was configuration: what is sold, who performs
-- it, when they are available. This is where the system starts recording commitments to real
-- people, and it is the one migration whose correctness cannot be recovered by fixing application
-- code afterwards — a double booking that reached the table is a customer standing in a doorway.
--
-- THE EXCLUSION CONSTRAINT IS THE POINT OF THIS FILE (ADR-0002). The application re-checks
-- availability before writing, and that check exists so a person gets a sentence they can act on.
-- It does not exist for correctness: two requests can pass it simultaneously and only the database
-- can settle which one wins. `btree_gist` was installed in V1 for this constraint alone, and it is
-- why this project runs PostgreSQL and tests against Testcontainers rather than H2.

-- ---------------------------------------------------------------------------
-- customers — a person who books with one Business.
--
-- No password, no account, no global identity. The same human at two businesses is two rows, which
-- is a deliberate cost: it means there is no cross-tenant customer record to leak and no join a
-- future feature could accidentally widen (CONTEXT.md).
--
-- Identity is (business_id, phone). Phone rather than email because a booking taken over the phone
-- or by the Receptionist may have no email at all, and because a phone number normalised to E.164
-- is the one identifier a customer can reliably repeat back.
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id          uuid         PRIMARY KEY,
    business_id uuid         NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,

    full_name   varchar(120) NOT NULL,

    -- E.164, normalised on write against the Business's country, exactly as employees.phone is.
    -- An un-normalised number is one that will quietly fail to match itself on the next booking,
    -- which presents as a duplicate customer rather than as an error.
    phone       varchar(20)  NOT NULL,
    email       varchar(254),

    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,

    CONSTRAINT customers_phone_unique UNIQUE (business_id, phone),
    CONSTRAINT customers_tenant_key   UNIQUE (business_id, id)
);

-- Dashboard search is case-insensitive, so the index has to be too — an index on full_name would
-- be ignored by a lower(full_name) predicate and the search would seq-scan the table.
CREATE INDEX customers_business_name_idx ON customers (business_id, lower(full_name));

-- ---------------------------------------------------------------------------
-- appointments — the critical table.
--
-- Two pairs of times, and they are not the same pair:
--
--   starts_at / ends_at      what the Customer agreed to, and what every screen displays
--   blocked_from / blocked_to  the same span widened by the Service's Buffers, which is what
--                              actually makes the Employee unavailable and what the exclusion
--                              constraint compares
--
-- Storing both is not denormalisation. The Buffers belong to the Service and the Service's buffers
-- can change; an Appointment must go on blocking the time it blocked when it was booked, or the
-- constraint would start permitting overlaps with rows already written.
-- ---------------------------------------------------------------------------
CREATE TABLE appointments (
    id                  uuid          PRIMARY KEY,
    business_id         uuid          NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,

    employee_id         uuid          NOT NULL,
    service_id          uuid          NOT NULL,
    customer_id         uuid          NOT NULL,

    starts_at           timestamptz   NOT NULL,
    ends_at             timestamptz   NOT NULL,
    blocked_from        timestamptz   NOT NULL,
    blocked_to          timestamptz   NOT NULL,

    status              varchar(20)   NOT NULL,

    -- A SNAPSHOT, taken at booking. Without it, an owner raising a price would rewrite last
    -- month's revenue, and a report run twice would disagree with itself. The column looks like
    -- needless duplication of services.price_amount until you ask what "revenue in March" means
    -- after a price change.
    price_amount        numeric(12,2) NOT NULL,
    currency            char(3)       NOT NULL,

    -- Crockford base32, 8 characters. varchar(12) leaves room to lengthen it without a migration
    -- against a table that will be large.
    confirmation_code   varchar(12)   NOT NULL,

    source              varchar(20)   NOT NULL,
    customer_note       varchar(1000),

    cancelled_at        timestamptz,
    cancelled_by        varchar(20),
    cancellation_reason varchar(500),

    -- Optimistic locking. Two owners rescheduling the same appointment in two tabs is the case:
    -- without it the second write silently wins and the first person believes they moved it.
    version             bigint        NOT NULL DEFAULT 0,

    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,

    CONSTRAINT appointments_status_check
        CHECK (status IN ('CONFIRMED', 'CANCELLED', 'COMPLETED', 'NO_SHOW')),
    CONSTRAINT appointments_source_check
        CHECK (source IN ('AI', 'CLASSIC', 'DASHBOARD')),
    CONSTRAINT appointments_cancelled_by_check
        CHECK (cancelled_by IS NULL OR cancelled_by IN ('CUSTOMER', 'BUSINESS')),
    CONSTRAINT appointments_price_check    CHECK (price_amount >= 0),
    CONSTRAINT appointments_currency_fmt   CHECK (currency ~ '^[A-Z]{3}$'),

    -- The occupancy must contain the appointment. A buffer that fell inside the appointment, or
    -- times in the wrong order, would let the constraint below compare a range that does not mean
    -- what its name says.
    CONSTRAINT appointments_time_order
        CHECK (starts_at < ends_at AND blocked_from <= starts_at AND ends_at <= blocked_to),

    -- A Customer proves an Appointment is theirs with their phone number and this code, so it has
    -- to be unique within the Business that will be asked (docs/04-api-overview.md §6). Globally
    -- unique would leak nothing but would make collisions across every tenant one shared budget.
    CONSTRAINT appointments_code_unique UNIQUE (business_id, confirmation_code),

    -- Both directions. A cancelled row with no timestamp cannot be reported on; a timestamp on a
    -- live row would mean a cancellation was reversed without clearing its trace.
    CONSTRAINT appointments_cancel_fields
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),

    -- The isolation backstop (docs/03-data-model.md §1). Composite, so business_id must agree with
    -- the employee's, the service's and the customer's. An appointment pairing one tenant's
    -- employee with another's customer is not rejected by a check somewhere — it is unrepresentable.
    CONSTRAINT appointments_employee_fk
        FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id),
    CONSTRAINT appointments_service_fk
        FOREIGN KEY (business_id, service_id)  REFERENCES services  (business_id, id),
    CONSTRAINT appointments_customer_fk
        FOREIGN KEY (business_id, customer_id) REFERENCES customers (business_id, id),

    CONSTRAINT appointments_tenant_key UNIQUE (business_id, id)
);

-- ---------------------------------------------------------------------------
-- THE CONSTRAINT.
--
-- Two details, each of which is a silent product defect if it is wrong:
--
--   '[)'  A 15:00–16:00 and a 16:00–17:00 appointment must both be bookable. With '[]' they
--         collide on the shared instant, and back-to-back booking — the normal way a busy salon
--         runs — stops working with a message about a conflict nobody can see.
--
--   WHERE status = 'CONFIRMED'
--         Without the predicate a cancelled appointment blocks its own time forever, and the
--         business loses the slot it just freed.
--
-- employee_id WITH = is what needs btree_gist: GiST cannot compare uuids for equality on its own.
-- business_id is deliberately NOT in the constraint — an employee belongs to exactly one business
-- (enforced above), so adding it would widen the index for no additional guarantee.
-- ---------------------------------------------------------------------------
ALTER TABLE appointments ADD CONSTRAINT appointments_no_overlap
    EXCLUDE USING gist (
        employee_id WITH =,
        tstzrange(blocked_from, blocked_to, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED');

-- Every index is led by business_id, because every query is tenant-scoped. One that is not is a
-- design smell in this schema (docs/03-data-model.md §2).
CREATE INDEX appointments_business_starts_idx  ON appointments (business_id, starts_at);
CREATE INDEX appointments_business_status_idx  ON appointments (business_id, status, starts_at);
CREATE INDEX appointments_customer_idx         ON appointments (business_id, customer_id, starts_at DESC);
-- The availability engine's read: this Business's Employees, over a window. It is the hot one.
CREATE INDEX appointments_employee_idx         ON appointments (business_id, employee_id, starts_at);

-- ---------------------------------------------------------------------------
-- appointment_events — append-only audit of every transition.
--
-- Never updated, never deleted, and deliberately not derivable from the appointment row: the row
-- holds the current state, and this holds what happened to get there. "When was this moved, by
-- whom, and from what time" is a question a business gets asked by a customer and cannot answer
-- from a mutable record.
--
-- No updated_at, because an event that could be edited would not be an audit trail.
-- ---------------------------------------------------------------------------
CREATE TABLE appointment_events (
    id             uuid        PRIMARY KEY,
    business_id    uuid        NOT NULL,
    appointment_id uuid        NOT NULL,

    event_type     varchar(30) NOT NULL,

    -- Who acted, in the system's own terms rather than as a user id that may be null. A Customer
    -- has no account and the Receptionist has no user row, so actor_id is nullable and actor_type
    -- is the field that always means something.
    actor_type     varchar(20) NOT NULL,
    actor_id       uuid,

    -- Old and new times, the cancellation reason, the status moved to. jsonb rather than columns
    -- because each event type carries a different shape and a table of mostly-null columns would
    -- be the alternative.
    payload        jsonb       NOT NULL DEFAULT '{}'::jsonb,

    created_at     timestamptz NOT NULL,

    CONSTRAINT appointment_events_type_check
        CHECK (event_type IN ('CREATED', 'RESCHEDULED', 'CANCELLED', 'COMPLETED', 'NO_SHOW')),
    CONSTRAINT appointment_events_actor_check
        CHECK (actor_type IN ('CUSTOMER', 'USER', 'AI', 'SYSTEM')),

    CONSTRAINT appointment_events_appointment_fk
        FOREIGN KEY (business_id, appointment_id) REFERENCES appointments (business_id, id) ON DELETE CASCADE
);

-- The detail screen's read: one appointment's history in order.
CREATE INDEX appointment_events_appointment_idx ON appointment_events (appointment_id, created_at);
