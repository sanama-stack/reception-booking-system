-- Phase 04 — Services and Employees.
--
-- After this migration every input the availability engine needs exists in the database: what is
-- sold, who can perform it, when they work, and when they do not.
--
-- This is the migration where the isolation backstop from docs/03-data-model.md §1 stops being a
-- schema-wide decision and becomes a constraint. `employee_services` is the first table that joins
-- two tenant-owned parents, and it references both by (business_id, id) rather than by id — so a
-- row pairing Salon Aria's employee with Datos Auto's service is refused by Postgres, not by an
-- application check that a future caller could route around.

-- ---------------------------------------------------------------------------
-- services — what a Business sells.
--
-- Duration and buffers are minutes rather than intervals: they are multiplied, summed and compared
-- against a slot stride, and `interval` carries a month component that makes none of that total.
-- ---------------------------------------------------------------------------
CREATE TABLE services (
    id                    uuid          PRIMARY KEY,
    business_id           uuid          NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,
    name                  varchar(120)  NOT NULL,
    description           text,

    -- The stride a slot grid is built on divides the hour, and a service whose duration does not
    -- land on that grid produces start times that drift across the day. Five is the finest stride
    -- an owner may choose (businesses_slot_interval_check), so it is the granularity here.
    duration_minutes      int           NOT NULL,
    buffer_before_minutes int           NOT NULL DEFAULT 0,
    buffer_after_minutes  int           NOT NULL DEFAULT 0,

    -- numeric, never a float. The currency is denormalised from the Business rather than joined:
    -- an Appointment records the price it was booked at, and a Business that changes currency must
    -- not silently reprice the history that references these rows.
    price_amount          numeric(12,2) NOT NULL,
    currency              char(3)       NOT NULL,

    -- Deactivate, never delete: Appointments reference a Service forever, and a hard delete would
    -- rewrite history. `active = false` is the supported path (docs/03-data-model.md §1).
    active                boolean       NOT NULL DEFAULT true,
    created_at            timestamptz   NOT NULL,
    updated_at            timestamptz   NOT NULL,

    CONSTRAINT services_duration_check
        CHECK (duration_minutes BETWEEN 5 AND 1440 AND duration_minutes % 5 = 0),
    CONSTRAINT services_buffer_before_check CHECK (buffer_before_minutes BETWEEN 0 AND 240),
    CONSTRAINT services_buffer_after_check  CHECK (buffer_after_minutes  BETWEEN 0 AND 240),
    -- Zero is legitimate: a free consultation is a service.
    CONSTRAINT services_price_check         CHECK (price_amount >= 0),
    CONSTRAINT services_currency_fmt        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT services_tenant_key          UNIQUE (business_id, id)
);

-- An expression index rather than a table constraint, because UNIQUE (…) cannot hold a function.
-- Case-insensitive because "Haircut" and "haircut" are two rows the owner cannot tell apart in a
-- dropdown, which is the failure this prevents.
CREATE UNIQUE INDEX services_business_name_unique ON services (business_id, lower(name));

-- Every listing filters on active, and every query is tenant-scoped; an index in this schema that
-- does not begin with business_id is a design smell.
CREATE INDEX services_business_active_idx ON services (business_id, active);

-- ---------------------------------------------------------------------------
-- employees — who performs a Service.
--
-- An Employee is not a User (ADR-0006). `user_id` is the seam for staff login and is unused in the
-- MVP: nothing writes it, and it exists now so the table that phase 12 would otherwise have to
-- ALTER under load already has the column.
-- ---------------------------------------------------------------------------
CREATE TABLE employees (
    id          uuid         PRIMARY KEY,
    business_id uuid         NOT NULL REFERENCES businesses (id) ON DELETE CASCADE,

    -- ON DELETE SET NULL, not CASCADE: deleting a login must not delete the bookable resource, or
    -- removing a user account would take their appointment history with it.
    user_id     uuid         REFERENCES users (id) ON DELETE SET NULL,

    full_name   varchar(120) NOT NULL,
    -- Contact details are for the owner, never for a Customer: no public endpoint selects them
    -- (docs/06-security.md).
    email       varchar(254),
    phone       varchar(20),
    job_title   varchar(120),

    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,

    CONSTRAINT employees_tenant_key UNIQUE (business_id, id)
);

-- Partial, so the many Employees with no login do not all collide on NULL. One login maps to at
-- most one Employee record (docs/03-data-model.md §4).
CREATE UNIQUE INDEX employees_user_unique ON employees (user_id) WHERE user_id IS NOT NULL;

CREATE INDEX employees_business_active_idx ON employees (business_id, active);

-- ---------------------------------------------------------------------------
-- employee_services — which Employees may perform which Services.
--
-- THE ISOLATION BACKSTOP. Both foreign keys are composite, so business_id must agree with the
-- employee's *and* with the service's. A cross-tenant assignment is not merely rejected by the
-- application; it is unrepresentable.
--
-- `created_at` without `updated_at`: an assignment has no mutable state. It exists or it does not,
-- and the whole set is replaced rather than edited (PUT /services/{id}/employees).
-- ---------------------------------------------------------------------------
CREATE TABLE employee_services (
    business_id uuid        NOT NULL,
    employee_id uuid        NOT NULL,
    service_id  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,

    PRIMARY KEY (employee_id, service_id),

    CONSTRAINT employee_services_employee_fk
        FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) ON DELETE CASCADE,
    CONSTRAINT employee_services_service_fk
        FOREIGN KEY (business_id, service_id)  REFERENCES services  (business_id, id) ON DELETE CASCADE
);

-- The primary key already answers "what can this employee do". This one answers "who can perform
-- this service", which is the question the availability engine asks on every search.
CREATE INDEX employee_services_service_idx ON employee_services (business_id, service_id);

-- ---------------------------------------------------------------------------
-- employee_schedules — the wall-clock times an Employee is willing to work.
--
-- `time` + `day_of_week`, never instants, so 09:00 stays 09:00 across a daylight-saving change
-- (ADR-0003). Multiple intervals per day are permitted and unioned — a split shift is one Employee
-- with a gap in the middle, not two.
--
-- A Working Schedule may be WIDER than Business Hours and is stored as given. Phase 05 intersects
-- the two. That keeps "when is this person willing to work" separate from "when are we open" — two
-- facts that genuinely differ — and it means changing opening hours does not require re-editing
-- every employee.
-- ---------------------------------------------------------------------------
CREATE TABLE employee_schedules (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL,
    employee_id uuid        NOT NULL,
    day_of_week smallint    NOT NULL,
    starts_at   time        NOT NULL,
    ends_at     time        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,

    -- ISO-8601: 1 = Monday … 7 = Sunday, matching java.time.DayOfWeek.getValue() so there is no
    -- off-by-one translation layer anywhere (docs/03-data-model.md §1).
    CONSTRAINT employee_schedules_day_check   CHECK (day_of_week BETWEEN 1 AND 7),
    -- Equal is rejected along with inverted: a zero-length shift is not a shift, and a schedule
    -- cannot cross midnight.
    CONSTRAINT employee_schedules_time_order  CHECK (starts_at < ends_at),
    -- Two intervals on one day starting at the same time are a duplicate, not a split shift. Full
    -- overlap detection needs to compare pairs and lives in EmployeeScheduleService; this catches
    -- the case a whole-week replace could otherwise write twice.
    CONSTRAINT employee_schedules_day_start_unique UNIQUE (employee_id, day_of_week, starts_at),

    CONSTRAINT employee_schedules_employee_fk
        FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) ON DELETE CASCADE,
    CONSTRAINT employee_schedules_tenant_key  UNIQUE (business_id, id)
);

CREATE INDEX employee_schedules_employee_day_idx
    ON employee_schedules (business_id, employee_id, day_of_week);

-- ---------------------------------------------------------------------------
-- employee_time_off — when one Employee is unavailable regardless of their schedule.
--
-- Instants, converted from business-local dates at write time, exactly as business_closures are.
-- The engine then subtracts a Time Off and a Business Closure with the same range algebra rather
-- than with two representations it has to keep straight.
-- ---------------------------------------------------------------------------
CREATE TABLE employee_time_off (
    id          uuid         PRIMARY KEY,
    business_id uuid         NOT NULL,
    employee_id uuid         NOT NULL,
    starts_at   timestamptz  NOT NULL,
    ends_at     timestamptz  NOT NULL,
    reason      varchar(200),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,

    CONSTRAINT employee_time_off_time_order CHECK (starts_at < ends_at),

    CONSTRAINT employee_time_off_employee_fk
        FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) ON DELETE CASCADE,
    CONSTRAINT employee_time_off_tenant_key  UNIQUE (business_id, id)
);

CREATE INDEX employee_time_off_range_idx
    ON employee_time_off (business_id, employee_id, starts_at, ends_at);
