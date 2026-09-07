-- Phase 03 — Business Setup.
--
-- Completes the tenant root that V2 left minimal, and adds the two collections an owner manages
-- alongside it. Migrations are additive: this ALTERs `businesses` rather than editing V2
-- (docs/09-phase-plan.md §5, rule 1).
--
-- Every CHECK here restates a rule the application also enforces. That duplication is deliberate:
-- the application's copy produces a message written for a person, and the database's copy is what
-- remains true when a future caller forgets to go through the service.

-- ---------------------------------------------------------------------------
-- businesses — profile, booking settings and the Receptionist's knowledge.
--
-- All nullable except the settings, which carry defaults. A business is created by registration
-- with none of this filled in, so a NOT NULL column here would mean either a fake value at
-- registration or a migration that cannot run against existing rows.
-- ---------------------------------------------------------------------------
ALTER TABLE businesses
    ADD COLUMN description  text,
    ADD COLUMN address_line varchar(200),
    ADD COLUMN city         varchar(120),
    -- ISO-3166-1 alpha-2, used to normalise customer phone numbers from phase 06 onward.
    ADD COLUMN country      char(2),
    ADD COLUMN phone        varchar(20),
    ADD COLUMN email        varchar(254),
    ADD COLUMN website      varchar(300),

    -- Booking policy. Defaults match docs/01-prd.md FR-2 and are applied to the rows registration
    -- has already created, so no business is left with a null policy the engine would have to
    -- interpret.
    ADD COLUMN slot_interval_minutes     int  NOT NULL DEFAULT 15,
    ADD COLUMN min_lead_time_minutes     int  NOT NULL DEFAULT 60,
    ADD COLUMN max_advance_days          int  NOT NULL DEFAULT 60,
    ADD COLUMN cancellation_window_hours int  NOT NULL DEFAULT 24,
    ADD COLUMN cancellation_policy       text,

    -- The Receptionist. `ai_additional_info` is bounded because it enters every system prompt;
    -- an unbounded column here is an unbounded token bill (docs/05-ai-architecture.md).
    ADD COLUMN ai_enabled             boolean NOT NULL DEFAULT true,
    ADD COLUMN ai_additional_info     varchar(2000),
    ADD COLUMN ai_daily_cost_cap_cents int    NOT NULL DEFAULT 500;

ALTER TABLE businesses
    ADD CONSTRAINT businesses_country_fmt CHECK (country IS NULL OR country ~ '^[A-Z]{2}$'),
    -- The interval an owner may pick, not an arbitrary range: slots are generated on this stride,
    -- and a value that does not divide the hour produces a grid that drifts across the day.
    ADD CONSTRAINT businesses_slot_interval_check
        CHECK (slot_interval_minutes IN (5, 10, 15, 20, 30, 60)),
    -- Zero to one week.
    ADD CONSTRAINT businesses_min_lead_time_check
        CHECK (min_lead_time_minutes BETWEEN 0 AND 10080),
    ADD CONSTRAINT businesses_max_advance_check
        CHECK (max_advance_days BETWEEN 1 AND 365),
    -- Zero to one week. Zero means a customer may cancel up to the appointment itself.
    ADD CONSTRAINT businesses_cancellation_window_check
        CHECK (cancellation_window_hours BETWEEN 0 AND 168),
    ADD CONSTRAINT businesses_ai_cost_cap_check
        CHECK (ai_daily_cost_cap_cents BETWEEN 0 AND 1000000);

-- ---------------------------------------------------------------------------
-- business_hours — the constraint V2 deferred.
--
-- Held back until the endpoint that respects it existed, because a whole-week replace is the only
-- writer that can guarantee the week it produces satisfies it. Multiple rows per day remain legal
-- (split shifts); what this forbids is two intervals on one day starting at the same time, which
-- is a duplicate rather than a shift.
-- ---------------------------------------------------------------------------
ALTER TABLE business_hours
    ADD CONSTRAINT business_hours_day_open_unique UNIQUE (business_id, day_of_week, opens_at);

-- ---------------------------------------------------------------------------
-- business_closures — a date range during which a Business is closed regardless of its hours.
--
-- Stored as instants, converted from business-local dates at write time, so the availability
-- engine subtracts a closure with exactly the same range algebra it uses for appointments and
-- time off (docs/03-data-model.md). One representation in the engine, one conversion at the edge.
-- ---------------------------------------------------------------------------
CREATE TABLE business_closures (
    id          uuid         PRIMARY KEY,
    business_id uuid         NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,
    starts_at   timestamptz  NOT NULL,
    ends_at     timestamptz  NOT NULL,
    reason      varchar(200),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,

    CONSTRAINT business_closures_time_order CHECK (starts_at < ends_at),
    -- The schema-wide tenancy rule (docs/03-data-model.md §1).
    CONSTRAINT business_closures_tenant_key UNIQUE (business_id, id)
);

-- Leads with business_id because every query is tenant-scoped; an index in this schema that does
-- not begin with business_id is a design smell.
CREATE INDEX business_closures_range_idx ON business_closures (business_id, starts_at, ends_at);

-- ---------------------------------------------------------------------------
-- business_faqs — rendered verbatim into the Receptionist's system prompt.
--
-- The ≤ 50 rows per business ceiling is enforced in the application rather than here: a CHECK
-- cannot count sibling rows, and a trigger would put a business rule somewhere no one reading the
-- service would find it.
-- ---------------------------------------------------------------------------
CREATE TABLE business_faqs (
    id          uuid          PRIMARY KEY,
    business_id uuid          NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,
    question    varchar(300)  NOT NULL,
    answer      varchar(1000) NOT NULL,
    sort_order  int           NOT NULL,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,

    CONSTRAINT business_faqs_sort_order_check CHECK (sort_order >= 0),
    CONSTRAINT business_faqs_tenant_key       UNIQUE (business_id, id)
);

CREATE INDEX business_faqs_business_order_idx ON business_faqs (business_id, sort_order);
