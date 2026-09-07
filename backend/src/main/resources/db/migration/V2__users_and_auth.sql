-- Phase 02 — Authentication and Tenancy.
--
-- Creates the authenticated principal, the tenant root, the link between them, and the refresh
-- token lineage. `businesses` and `business_hours` are deliberately minimal: registration must
-- create them atomically (docs/phases/phase-02-authentication.md), and phase 03 adds the profile,
-- booking-settings and AI columns by ALTER. Migrations are additive; no phase edits an earlier one.

-- ---------------------------------------------------------------------------
-- users — authenticated principals.
--
-- Deliberately carries no business_id. The membership is the link, so a user belonging to two
-- businesses later needs no migration (ADR-0006).
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid         PRIMARY KEY,
    -- citext, so uniqueness is case-insensitive at the storage layer rather than depending on
    -- every call site remembering to lowercase. The extension comes from V1.
    email         citext       NOT NULL,
    -- BCrypt cost 12 produces a 60-character hash; the column has room for a longer scheme later.
    password_hash varchar(100) NOT NULL,
    full_name     varchar(120) NOT NULL,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,

    CONSTRAINT users_email_unique UNIQUE (email)
);

-- ---------------------------------------------------------------------------
-- businesses — the tenant root.
--
-- Every other tenant-owned record references this. Phase 03 adds description, address, contacts,
-- booking settings and the AI columns.
-- ---------------------------------------------------------------------------
CREATE TABLE businesses (
    id         uuid         PRIMARY KEY,
    name       varchar(120) NOT NULL,
    slug       varchar(140) NOT NULL,
    -- IANA zone id, validated in the application against ZoneId.getAvailableZoneIds().
    -- Load-bearing from phase 03 onward (ADR-0003).
    timezone   varchar(64)  NOT NULL,
    currency   char(3)      NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,

    CONSTRAINT businesses_slug_unique  UNIQUE (slug),
    -- Lowercase, URL-safe, no leading, trailing or repeated hyphens. The slug appears in a public
    -- URL, so the shape is a storage constraint rather than a presentation preference.
    CONSTRAINT businesses_slug_format  CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT businesses_currency_fmt CHECK (currency ~ '^[A-Z]{3}$')
);

-- ---------------------------------------------------------------------------
-- memberships — the link between a User and a Business, carrying that user's role.
--
-- MVP creates exactly one OWNER membership per user; the shape supports more without change.
-- ---------------------------------------------------------------------------
CREATE TABLE memberships (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id),
    business_id uuid        NOT NULL REFERENCES businesses (id),
    role        varchar(20) NOT NULL,
    created_at  timestamptz NOT NULL,

    -- varchar + CHECK rather than a native enum: ALTER TYPE is a migration hazard and strings are
    -- readable in psql (docs/03-data-model.md §1).
    CONSTRAINT memberships_role_check          CHECK (role IN ('OWNER', 'ADMIN', 'STAFF')),
    -- Prevents duplicate role rows with conflicting roles for one user in one business.
    CONSTRAINT memberships_user_business_unique UNIQUE (user_id, business_id)
);

CREATE INDEX memberships_user_id_idx ON memberships (user_id);

-- ---------------------------------------------------------------------------
-- refresh_tokens — rotation lineage with replay detection.
--
-- The raw token is never stored, only its SHA-256 hash. Presenting an already-used token revokes
-- the entire family_id, which is the standard response to a stolen refresh token
-- (docs/06-security.md §2).
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    -- SHA-256 rendered as 64 lowercase hex characters.
    token_hash varchar(64) NOT NULL,
    family_id  uuid        NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    -- For the user's session list later. Truncated by the application; a header is attacker-controlled.
    user_agent varchar(400),
    -- Wide enough for IPv6 including a zone index.
    ip         varchar(45),

    CONSTRAINT refresh_tokens_hash_unique UNIQUE (token_hash)
);

-- The lookup on presentation.
CREATE INDEX refresh_tokens_token_hash_idx    ON refresh_tokens (token_hash);
-- Revoking a family, and listing a user's live sessions.
CREATE INDEX refresh_tokens_user_revoked_idx  ON refresh_tokens (user_id, revoked_at);
CREATE INDEX refresh_tokens_family_idx        ON refresh_tokens (family_id);

-- ---------------------------------------------------------------------------
-- business_hours — the wall-clock times a Business is open, per day of week.
--
-- Stored as `time` + `day_of_week`, never instants: 09:00 must remain 09:00 across a DST change
-- (ADR-0003). A day with no row is closed — absence is meaningful.
--
-- Registration seeds Mon-Fri 09:00-17:00 so a new account is never a blank slate. Phase 03 adds
-- UNIQUE (business_id, day_of_week, opens_at), once the whole-week replace endpoint exists to
-- respect it.
-- ---------------------------------------------------------------------------
CREATE TABLE business_hours (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,
    -- ISO-8601: 1 = Monday .. 7 = Sunday, matching java.time.DayOfWeek.getValue() so there is no
    -- off-by-one translation layer.
    day_of_week smallint    NOT NULL,
    opens_at    time        NOT NULL,
    closes_at   time        NOT NULL,

    CONSTRAINT business_hours_day_check  CHECK (day_of_week BETWEEN 1 AND 7),
    -- Hours cannot cross midnight. A documented limitation; overnight businesses are a V1.2 item
    -- (docs/future/future-features.md).
    CONSTRAINT business_hours_time_order CHECK (opens_at < closes_at),
    -- The schema-wide tenancy rule: every tenant-owned table declares UNIQUE (business_id, id), so
    -- a later cross-entity reference can use a composite foreign key (docs/03-data-model.md §1).
    CONSTRAINT business_hours_tenant_key UNIQUE (business_id, id)
);

CREATE INDEX business_hours_business_id_idx ON business_hours (business_id);
