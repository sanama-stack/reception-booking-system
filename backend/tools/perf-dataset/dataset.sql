-- The performance dataset.
--
-- Loaded by generate.sh into a database this project's own Flyway migrations have already built,
-- so every index, CHECK and EXCLUDE constraint measured is the shipped one. Nothing here creates
-- or alters a table; if a column name below is wrong, that is a migration this file has not caught
-- up with, and it fails loudly rather than measuring a schema nobody ships.
--
-- ---------------------------------------------------------------------------------------------
-- THE SHAPE, AND WHY EACH PART OF IT IS THERE
--
--   10 000 appointments for one target Business, and 21 600 across nine others.
--
-- The second half is the part that is easy to leave out and the part that decides whether the
-- measurement means anything (T28). Load 10 000 rows for a single Business into an empty table and
-- `business_id = X` matches EVERY row: a sequential scan genuinely is the cheapest plan, PostgreSQL
-- correctly chooses it, and the green tick you get says the table was small — not that the index
-- works. At 31.6% of the table the target is the harder case for index selection, not the easier.
--
--   Slots at 09:00 / 11:00 / 13:00 / 15:00, 60 minutes, 10-minute buffers either side.
--
-- The blocked ranges of one Employee never touch, so `appointments_no_overlap` is satisfied BY
-- CONSTRUCTION rather than by inserting and retrying. The constraint is still doing its work — it
-- judges all 31 600 rows on the way in.
--
--   440 days behind, 60 ahead, for the target.
--
-- Revenue and the rates filter on status. An all-future dataset is 100% CONFIRMED, the revenue
-- query would have had nothing to sum, and it would have looked fast for the wrong reason.
--
--   260 Business Closures and 1 300 Time Off rows.
--
-- Five years at one a week, far past what a real business enters. Both tables share the unbounded
-- overlap shape the calendar query had, and would otherwise have been assumed rather than measured.
-- ---------------------------------------------------------------------------------------------

\set ON_ERROR_STOP on

-- Refuse to run anywhere but a scratch database. This file inserts 31 600 appointments; the one
-- thing it must never do is put them in the database somebody is developing against.
DO $$
BEGIN
    IF current_database() NOT LIKE '%perf%' THEN
        RAISE EXCEPTION
            'Refusing to load the performance dataset into "%". The database name must contain "perf".',
            current_database();
    END IF;
END $$;

BEGIN;

-- Deterministic ids, so a row can be recognised on sight and two runs produce the same database.
-- The leading group says what the row is: 1 = business, 2 = employee, 3 = service, 4 = customer,
-- 5 = appointment, 6 = hours, 7 = schedule, 8 = closure, 9 = time off.
CREATE OR REPLACE FUNCTION perf_id(kind int, n bigint) RETURNS uuid AS $$
    SELECT (repeat(kind::text, 8) || '-0000-4000-8000-' || lpad(to_hex(n), 12, '0'))::uuid;
$$ LANGUAGE sql IMMUTABLE;

-- The origin. Everything is placed relative to midnight UTC today, so a dataset generated on any
-- day has the same shape — 440 days of history and 60 of future for the target.
CREATE OR REPLACE FUNCTION perf_today() RETURNS timestamptz AS $$
    SELECT date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
$$ LANGUAGE sql STABLE;

-- Ten tenants. Tenant 0 is the one every measurement is taken against.
CREATE TEMP TABLE perf_tenants AS
SELECT b                                             AS b,
       CASE WHEN b = 0 THEN 500 ELSE 120 END         AS days,
       CASE WHEN b = 0 THEN 440 ELSE 110 END         AS days_past
  FROM generate_series(0, 9) AS g(b);

INSERT INTO businesses (id, name, slug, timezone, currency, created_at, updated_at)
SELECT perf_id(1, b),
       'Perf Tenant ' || b,
       'perf-tenant-' || b,
       'UTC',
       'EUR',
       perf_today() - INTERVAL '3 years',
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants;

INSERT INTO business_hours (id, business_id, day_of_week, opens_at, closes_at)
SELECT perf_id(6, b * 10 + d), perf_id(1, b), d, TIME '08:00', TIME '20:00'
  FROM perf_tenants CROSS JOIN generate_series(1, 6) AS g(d);

-- Five Employees each. Five is the number the NFR check for availability is written against, and
-- it is what makes 500 x 5 x 4 come to exactly 10 000 with no trimming.
INSERT INTO employees (id, business_id, full_name, job_title, active, created_at, updated_at)
SELECT perf_id(2, b * 100 + e),
       perf_id(1, b),
       'Employee ' || b || '-' || e,
       'Perf fixture',
       true,
       perf_today() - INTERVAL '3 years',
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants CROSS JOIN generate_series(0, 4) AS g(e);

INSERT INTO employee_schedules (id, business_id, employee_id, day_of_week, starts_at, ends_at, created_at, updated_at)
SELECT perf_id(7, (b * 100 + e) * 10 + d),
       perf_id(1, b),
       perf_id(2, b * 100 + e),
       d,
       TIME '08:00',
       TIME '20:00',
       perf_today() - INTERVAL '3 years',
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants
  CROSS JOIN generate_series(0, 4) AS e(e)
  CROSS JOIN generate_series(1, 6) AS d(d);

-- Three Services, so "top services" has something to rank and the price snapshot varies.
INSERT INTO services (id, business_id, name, duration_minutes, buffer_before_minutes,
                      buffer_after_minutes, price_amount, currency, active, created_at, updated_at)
SELECT perf_id(3, b * 100 + s),
       perf_id(1, b),
       'Service ' || s,
       60,
       10,
       10,
       (40 + s * 40)::numeric,
       'EUR',
       true,
       perf_today() - INTERVAL '3 years',
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants CROSS JOIN generate_series(0, 2) AS g(s);

-- Every Employee performs every Service. The availability engine will not offer a Slot for a
-- Service nobody is assigned to, so without these rows the engine's benchmark would measure an
-- empty answer arriving quickly.
INSERT INTO employee_services (business_id, employee_id, service_id, created_at)
SELECT perf_id(1, b),
       perf_id(2, b * 100 + e),
       perf_id(3, b * 100 + s),
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants
  CROSS JOIN generate_series(0, 4) AS emp(e)
  CROSS JOIN generate_series(0, 2) AS svc(s);

-- 250 Customers each, so the target's 10 000 appointments are forty visits apiece rather than one
-- customer row per appointment — which is the shape `appointments_customer_idx` is for.
INSERT INTO customers (id, business_id, full_name, phone, email, created_at, updated_at)
SELECT perf_id(4, b * 1000 + c),
       perf_id(1, b),
       'Customer ' || b || '-' || c,
       '+100' || lpad((b * 1000 + c)::text, 7, '0'),
       'customer' || b || '-' || c || '@perf.invalid',
       perf_today() - INTERVAL '3 years',
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants CROSS JOIN generate_series(0, 249) AS g(c);

-- ---------------------------------------------------------------------------------------------
-- The appointments.
--
-- `idx` is the row's position within its tenant — ((day * 5) + employee) * 4 + slot — which makes
-- every derived value a pure function of where the row sits. Two runs on the same day produce the
-- same 31 600 rows, statuses included.
-- ---------------------------------------------------------------------------------------------
INSERT INTO appointments (id, business_id, employee_id, service_id, customer_id,
                          starts_at, ends_at, blocked_from, blocked_to,
                          status, price_amount, currency, confirmation_code, source,
                          cancelled_at, cancelled_by, created_at, updated_at)
SELECT perf_id(5, b * 100000 + idx),
       perf_id(1, b),
       perf_id(2, b * 100 + emp),
       perf_id(3, b * 100 + (idx % 3)),
       perf_id(4, b * 1000 + (idx % 250)),
       starts_at,
       starts_at + INTERVAL '60 minutes',
       starts_at - INTERVAL '10 minutes',
       starts_at + INTERVAL '70 minutes',
       status,
       (40 + (idx % 3) * 40)::numeric,
       'EUR',
       'P' || lpad(to_hex(idx), 8, '0'),
       (ARRAY['CLASSIC', 'DASHBOARD', 'AI'])[1 + (idx % 3)],
       CASE WHEN status = 'CANCELLED' THEN starts_at - INTERVAL '1 day' END,
       CASE WHEN status = 'CANCELLED' THEN (ARRAY['CUSTOMER', 'BUSINESS'])[1 + (idx % 2)] END,
       starts_at - INTERVAL '3 days',
       starts_at - INTERVAL '3 days'
  FROM (
    SELECT t.b,
           e.emp,
           ((d.day * 5) + e.emp) * 4 + s.slot                                  AS idx,
           d.day < t.days_past                                                 AS is_past,
           perf_today()
             - make_interval(days => t.days_past - d.day)
             + make_interval(hours => 9 + s.slot * 2)                          AS starts_at
      FROM perf_tenants t
      CROSS JOIN generate_series(0, 499) AS d(day)
      CROSS JOIN generate_series(0, 4)   AS e(emp)
      CROSS JOIN generate_series(0, 3)   AS s(slot)
     WHERE d.day < t.days
  ) placed
  CROSS JOIN LATERAL (
    -- Everything ahead of today is CONFIRMED; history is mostly closed out. The buckets are
    -- deterministic in idx rather than random, so the status mix is reproducible and the revenue
    -- query has ~6 100 COMPLETED rows to sum for the target.
    SELECT CASE
             WHEN NOT is_past             THEN 'CONFIRMED'
             WHEN idx % 1000 < 100        THEN 'NO_SHOW'
             WHEN idx % 1000 < 215        THEN 'CANCELLED'
             WHEN idx % 1000 < 300        THEN 'CONFIRMED'
             ELSE                              'COMPLETED'
           END AS status
  ) resolved;

-- Five years of weekly closures and time off for every tenant, which is far more than a real
-- business enters. Both tables are queried by overlap with no lower bound on their own start.
INSERT INTO business_closures (id, business_id, starts_at, ends_at, reason, created_at, updated_at)
SELECT perf_id(8, b * 1000 + w),
       perf_id(1, b),
       perf_today() - make_interval(days => (260 - w) * 7),
       perf_today() - make_interval(days => (260 - w) * 7) + INTERVAL '1 day',
       'Perf closure ' || w,
       perf_today() - INTERVAL '3 years',
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants CROSS JOIN generate_series(0, 259) AS g(w);

INSERT INTO employee_time_off (id, business_id, employee_id, starts_at, ends_at, reason, created_at, updated_at)
SELECT perf_id(9, (b * 100 + e) * 1000 + w),
       perf_id(1, b),
       perf_id(2, b * 100 + e),
       perf_today() - make_interval(days => (260 - w) * 7) + INTERVAL '2 days',
       perf_today() - make_interval(days => (260 - w) * 7) + INTERVAL '3 days',
       'Perf time off ' || w,
       perf_today() - INTERVAL '3 years',
       perf_today() - INTERVAL '3 years'
  FROM perf_tenants
  CROSS JOIN generate_series(0, 4)   AS e(e)
  CROSS JOIN generate_series(0, 259) AS w(w);

DROP FUNCTION perf_id(int, bigint);
DROP FUNCTION perf_today();

COMMIT;
